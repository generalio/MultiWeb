package io.github.multiweb.android

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.ScriptBridgeWithFacade
import org.json.JSONObject
import java.net.URI

/**
 * 通过 AndroidX WebKit 的 Web Message Listener 安装受限来源的 JS 桥。
 *
 * 不使用 `addJavascriptInterface`：后者会把整个对象暴露给所有已加载页面，无法满足扩展 API 的精确
 * 域名限制。消息桥仅允许 HTTPS 精确主机名、主框架页面与 JSON 文本协议。
 */
internal object AndroidScriptBridgeInstaller {
  fun install(
    webView: WebView,
    javaScriptEnabled: Boolean,
    bridges: List<ScriptBridge>,
  ) {
    if (!shouldInstallScriptBridges(javaScriptEnabled, bridges)) {
      return
    }
    require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      "当前 Android System WebView 不支持受限来源 JS 桥。"
    }

    AndroidScriptBridgeConfiguration.create(bridges).forEach { configuration ->
      WebViewCompat.addWebMessageListener(
        webView,
        configuration.transportName,
        configuration.allowedOriginRules,
        AndroidScriptBridgeListener(configuration),
      )
      configuration.facadeInjectionScript()?.let { script ->
        require(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
          "当前 Android System WebView 不支持文档开始阶段的 JS 桥门面。"
        }
        WebViewCompat.addDocumentStartJavaScript(
          webView,
          script,
          configuration.allowedOriginRules,
        )
      }
    }
  }
}

/** JavaScript 关闭时绝不触碰平台桥 API，避免桥配置影响纯原生浏览场景。 */
internal fun shouldInstallScriptBridges(
  javaScriptEnabled: Boolean,
  bridges: List<ScriptBridge>,
): Boolean {
  return javaScriptEnabled && bridges.isNotEmpty()
}

/** 经过安全校验的 Android JS 桥配置。 */
internal data class AndroidScriptBridgeConfiguration(
  val bridge: ScriptBridge,
  val transportName: String,
  /** 网页公开的受限方法门面；未声明时只暴露消息通道。 */
  val facade: ScriptBridgeFacade?,
  /** 规范化后的精确 HTTPS 主机名，仅匹配默认 HTTPS 端口 443。 */
  val allowedHosts: Set<String>,
  val allowedOriginRules: Set<String>,
) {
  /** 生成受限来源的 Promise 门面；内部消息对象始终使用与网页名称不同的 [transportName]。 */
  fun facadeInjectionScript(): String? {
    val bridgeFacade = facade ?: return null
    val methods = bridgeFacade.methodNames.joinToString(",") { method ->
      "${method.toJavaScriptString()}:function(payload){return invoke(${method.toJavaScriptString()},payload);}"
    }
    return """
      (function() {
        var nativeBridge = window[${transportName.toJavaScriptString()}];
        if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') return;
        var sequence = 0;
        var pending = Object.create(null);
        nativeBridge.onmessage = function(event) {
          var response;
          try { response = JSON.parse(event.data); } catch (_) { return; }
          if (!response || typeof response.id !== 'string') return;
          var callback = pending[response.id];
          if (!callback) return;
          delete pending[response.id];
          if (response.isSuccess) callback.resolve(response);
          else {
            var error = new Error(response.errorCode || 'bridge_error');
            error.response = response;
            callback.reject(error);
          }
        };
        function invoke(method, payload) {
          return new Promise(function(resolve, reject) {
            var id = String(++sequence);
            pending[id] = { resolve: resolve, reject: reject };
            try {
              nativeBridge.postMessage(JSON.stringify({
                id: id,
                method: method,
                payload: payload == null ? '' : String(payload)
              }));
            } catch (error) {
              delete pending[id];
              reject(error);
            }
          });
        }
        var facade = Object.freeze({$methods});
        try {
          Object.defineProperty(window, ${bridge.name.toJavaScriptString()}, {
            value: facade,
            enumerable: false,
            writable: false,
            configurable: false
          });
        } catch (_) {}
      })();
    """.trimIndent()
  }

  /** 受限门面只允许其显式声明的方法；普通消息桥仍由桥自身定义可调用方法。 */
  fun isMethodAllowed(method: String): Boolean {
    return facade?.methodNames?.contains(method) ?: true
  }

  companion object {
    private val bridgeNamePattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun create(bridges: List<ScriptBridge>): List<AndroidScriptBridgeConfiguration> {
      val bridgeNames = mutableSetOf<String>()
      val transportNames = mutableSetOf<String>()
      return bridges.map { bridge ->
        val bridgeWithFacade = bridge as? ScriptBridgeWithFacade
        val transportName = bridgeWithFacade?.transportName ?: bridge.name
        val facade = bridgeWithFacade?.facade
        require(bridgeNamePattern.matches(bridge.name)) {
          "JS 桥名称必须是 ASCII JavaScript 标识符：${bridge.name}"
        }
        require(bridgeNames.add(bridge.name)) {
          "JS 桥名称不能重复：${bridge.name}"
        }
        require(bridgeNamePattern.matches(transportName)) {
          "JS 桥内部消息名称必须是 ASCII JavaScript 标识符：$transportName"
        }
        require(transportNames.add(transportName)) {
          "JS 桥内部消息名称不能重复：$transportName"
        }
        require(bridgeWithFacade == null || transportName != bridge.name) {
          "JS 桥方法门面必须使用独立内部消息名称：${bridge.name}"
        }
        facade?.let { facade ->
          require(facade.methodNames.isNotEmpty()) {
            "JS 桥门面必须声明至少一个方法：${bridge.name}"
          }
          facade.methodNames.forEach { methodName ->
            require(bridgeNamePattern.matches(methodName)) {
              "JS 桥门面方法必须是 ASCII JavaScript 标识符：$methodName"
            }
          }
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
          transportName = transportName,
          facade = facade,
          allowedHosts = allowedHosts,
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

/** 复核来源与 AndroidX 注入规则一致：仅 HTTPS、精确主机及默认端口 443。 */
private fun AndroidScriptBridgeConfiguration.isAllowedOrigin(origin: Uri): Boolean {
  return origin.scheme.equals("https", ignoreCase = true) &&
    origin.host?.lowercase() in allowedHosts &&
    origin.port in setOf(-1, 443)
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
    if (!isMainFrame || !configuration.isAllowedOrigin(sourceOrigin)) {
      replyProxy.postMessage(failureResponse("untrusted_origin"))
      return
    }

    val parsedCall = message.data?.toScriptBridgeCall()
    if (parsedCall == null) {
      replyProxy.postMessage(failureResponse("invalid_request"))
      return
    }
    if (!configuration.isMethodAllowed(parsedCall.call.method)) {
      replyProxy.postMessage(
        ScriptBridgeResponse(isSuccess = false, errorCode = "method_not_allowed").toJson(parsedCall.id),
      )
      return
    }

    val response = runCatching { configuration.bridge.handle(parsedCall.call) }
      .getOrElse { ScriptBridgeResponse(isSuccess = false, errorCode = "bridge_exception") }
      ?: ScriptBridgeResponse(isSuccess = true)
    replyProxy.postMessage(response.toJson(parsedCall.id))
  }

  private fun String.toScriptBridgeCall(): ParsedScriptBridgeCall? {
    return runCatching {
      val request = JSONObject(this)
      val method = request.optString("method")
      require(method.isNotBlank())
      ParsedScriptBridgeCall(
        call = ScriptBridgeCall(
          method = method,
          payload = request.optString("payload"),
        ),
        id = request.optString("id").takeIf { it.isNotBlank() },
      )
    }.getOrNull()
  }

  private fun failureResponse(errorCode: String): String {
    return ScriptBridgeResponse(isSuccess = false, errorCode = errorCode).toJson()
  }

  private fun ScriptBridgeResponse.toJson(callId: String? = null): String {
    return JSONObject()
      .put("isSuccess", isSuccess)
      .put("payload", payload)
      .put("errorCode", errorCode)
      .apply {
        if (callId != null) {
          put("id", callId)
        }
      }
      .toString()
  }

  /** Promise 门面的关联标识只存在于平台消息协议，不泄露到公共桥调用契约。 */
  private data class ParsedScriptBridgeCall(
    val call: ScriptBridgeCall,
    val id: String?,
  )
}

/** 使用纯 Kotlin 生成 JavaScript 字符串字面量，保证本地单元测试不依赖 Android 框架桩实现。 */
internal fun String.toJavaScriptString(): String {
  return buildString(length + 2) {
    append('"')
    for (character in this@toJavaScriptString) {
      when (character) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\u2028' -> append("\\u2028")
        '\u2029' -> append("\\u2029")
        else -> if (character.code < 0x20) {
          append("\\u")
          append(character.code.toString(16).padStart(4, '0'))
        } else {
          append(character)
        }
      }
    }
    append('"')
  }
}
