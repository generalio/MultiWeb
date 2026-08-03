package io.github.multiweb.android

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeResponse
import org.json.JSONObject
import java.net.URI

/**
 * 通过 AndroidX WebKit 的 Web Message Listener 安装受限来源的 JS 桥。
 *
 * 不使用 `addJavascriptInterface`：后者会把整个对象暴露给所有已加载页面，无法满足扩展 API 的精确
 * 域名限制。消息桥仅允许 HTTPS 精确主机名、主框架页面与 JSON 文本协议。
 */
internal object AndroidScriptBridgeInstaller {
  fun install(webView: WebView, bridges: List<ScriptBridge>) {
    if (bridges.isEmpty()) {
      return
    }
    require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      "当前 Android System WebView 不支持受限来源 JS 桥。"
    }

    AndroidScriptBridgeConfiguration.create(bridges).forEach { configuration ->
      WebViewCompat.addWebMessageListener(
        webView,
        configuration.bridge.name,
        configuration.allowedOriginRules,
        AndroidScriptBridgeListener(configuration),
      )
    }
  }
}

/** 经过安全校验的 Android JS 桥配置。 */
internal data class AndroidScriptBridgeConfiguration(
  val bridge: ScriptBridge,
  val allowedOriginRules: Set<String>,
) {
  companion object {
    private val bridgeNamePattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun create(bridges: List<ScriptBridge>): List<AndroidScriptBridgeConfiguration> {
      val bridgeNames = mutableSetOf<String>()
      return bridges.map { bridge ->
        require(bridgeNamePattern.matches(bridge.name)) {
          "JS 桥名称必须是 ASCII JavaScript 标识符：${bridge.name}"
        }
        require(bridgeNames.add(bridge.name)) {
          "JS 桥名称不能重复：${bridge.name}"
        }
        require(bridge.allowedHosts.isNotEmpty()) {
          "JS 桥必须声明至少一个受信任主机：${bridge.name}"
        }

        val allowedHosts = bridge.allowedHosts.mapTo(linkedSetOf()) { host ->
          require(isValidHost(host)) {
            "JS 桥只允许精确主机名且不支持通配符：$host"
          }
          host.lowercase()
        }
        AndroidScriptBridgeConfiguration(
          bridge = bridge,
          allowedOriginRules = allowedHosts.mapTo(linkedSetOf()) { "https://$it" },
        )
      }
    }

    private fun isValidHost(host: String): Boolean {
      if (host.isBlank() || host != host.trim() || '*' in host || '/' in host) {
        return false
      }
      val normalizedHost = host.lowercase()
      val parsedHost = runCatching { URI("https://$normalizedHost").host }.getOrNull()
      return parsedHost == normalizedHost
    }
  }
}

/** 将受限来源的网页消息转换为 [ScriptBridge] 调用。 */
private class AndroidScriptBridgeListener(
  private val configuration: AndroidScriptBridgeConfiguration,
) : WebViewCompat.WebMessageListener {
  override fun onPostMessage(
    view: WebView,
    message: WebMessageCompat,
    sourceOrigin: Uri,
    isMainFrame: Boolean,
    replyProxy: JavaScriptReplyProxy,
  ) {
    if (!isMainFrame || sourceOrigin.toString() !in configuration.allowedOriginRules) {
      replyProxy.postMessage(failureResponse("untrusted_origin"))
      return
    }

    val call = message.data?.toScriptBridgeCall()
    if (call == null) {
      replyProxy.postMessage(failureResponse("invalid_request"))
      return
    }

    val response = runCatching { configuration.bridge.handle(call) }
      .getOrElse { ScriptBridgeResponse(isSuccess = false, errorCode = "bridge_exception") }
      ?: ScriptBridgeResponse(isSuccess = true)
    replyProxy.postMessage(response.toJson())
  }

  private fun String.toScriptBridgeCall(): ScriptBridgeCall? {
    return runCatching {
      val request = JSONObject(this)
      val method = request.optString("method")
      require(method.isNotBlank())
      ScriptBridgeCall(
        method = method,
        payload = request.optString("payload"),
      )
    }.getOrNull()
  }

  private fun failureResponse(errorCode: String): String {
    return ScriptBridgeResponse(isSuccess = false, errorCode = errorCode).toJson()
  }

  private fun ScriptBridgeResponse.toJson(): String {
    return JSONObject()
      .put("isSuccess", isSuccess)
      .put("payload", payload)
      .put("errorCode", errorCode)
      .toString()
  }
}
