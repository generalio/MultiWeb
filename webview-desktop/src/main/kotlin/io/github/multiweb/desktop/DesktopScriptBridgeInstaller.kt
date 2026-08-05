package io.github.multiweb.desktop

import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.ScriptBridgeWithFacade
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter

/**
 * JCEF 单个桥的安全配置。
 *
 * CEF 会把查询函数注入所有页面，因此配置中的主机校验既在脚本注入前执行，也在原生回调中再次执行。
 */
internal data class DesktopScriptBridgeConfiguration(
  /** 业务桥定义。 */
  val bridge: ScriptBridge,
  /** 平台消息通道使用的内部对象名称。 */
  val transportName: String,
  /** 网页公开的受限方法门面；未声明时只暴露消息通道。 */
  val facade: ScriptBridgeFacade?,
  /** 当前桥在页面中使用的查询函数名。 */
  val queryFunction: String,
  /** 规范化后的精确 HTTPS 主机名，仅匹配默认 HTTPS 端口 443。 */
  val allowedHosts: Set<String>,
) {
  /** 判断 JCEF 回调所在的主框架是否属于受信任来源。 */
  fun isAllowedUrl(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    return parsed.scheme.equals("https", ignoreCase = true) &&
      parsed.host?.lowercase() in allowedHosts &&
      parsed.port in setOf(-1, 443)
  }

  /**
   * 仅在受信任主文档中定义桥对象，桥请求通过 CEF 异步查询回传 Promise。
   *
   * 同时兼容 `postMessage(method, payload)` 和单参数 JSON 字符串，后者可与 Android 的 Web Message Listener
   * 共用同一段网页脚本。
   */
  fun injectionScript(): String {
    val hostExpression = allowedHosts.joinToString(" || ") { host ->
      "window.location.hostname === ${host.toJavaScriptString()}"
    }
    val transportName = transportName.toJavaScriptString()
    val queryFunction = queryFunction.toJavaScriptString()
    val transportScript = """
      (function() {
        if (window.location.protocol !== 'https:' || (window.location.port !== '' && window.location.port !== '443') || !($hostExpression)) return;
        var query = window[$queryFunction];
        if (typeof query !== 'function') return;
        try {
          Object.defineProperty(window, $transportName, {
            value: Object.freeze({
              postMessage: function(requestOrMethod, payload) {
                var method = requestOrMethod;
                var requestPayload = payload;
                if (arguments.length === 1 && typeof requestOrMethod === 'string') {
                  try {
                    var request = JSON.parse(requestOrMethod);
                    if (request && typeof request.method === 'string') {
                      method = request.method;
                      requestPayload = request.payload;
                    }
                  } catch (_) {}
                }
                if (typeof method !== 'string' || method.length === 0) return;
                return new Promise(function(resolve, reject) {
                  query({
                    request: encodeURIComponent(method) + ':' +
                      encodeURIComponent(requestPayload == null ? '' : String(requestPayload)),
                    persistent: false,
                    onSuccess: function(response) {
                      try { resolve(JSON.parse(response)); }
                      catch (_) { reject(new Error('invalid_bridge_response')); }
                    },
                    onFailure: function(code, message) {
                      reject(new Error(message || ('bridge_error_' + code)));
                    }
                  });
                });
              }
            }),
            enumerable: false,
            writable: false,
            configurable: false
          });
        } catch (_) {}
      })();
    """.trimIndent()
    return transportScript + facadeInjectionScript().orEmpty()
  }

  /** 受限门面只允许其显式声明的方法；普通消息桥仍由桥自身定义可调用方法。 */
  fun isMethodAllowed(method: String): Boolean {
    return facade?.methodNames?.contains(method) ?: true
  }

  /** 基于内部消息对象创建兼容旧方法名的 Promise 门面。 */
  private fun facadeInjectionScript(): String? {
    val bridgeFacade = facade ?: return null
    val methods = bridgeFacade.methodNames.joinToString(",") { method ->
      "${method.toJavaScriptString()}:function(payload){return nativeBridge.postMessage(JSON.stringify({method:${method.toJavaScriptString()},payload:payload == null ? '' : String(payload)}));}"
    }
    return """

      (function() {
        var nativeBridge = window[${transportName.toJavaScriptString()}];
        if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') return;
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

  companion object {
    private val bridgeNamePattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    /** 校验桥名称和主机名，拒绝通配符、端口及非 ASCII 来源。 */
    fun create(bridges: List<ScriptBridge>): List<DesktopScriptBridgeConfiguration> {
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
            "JS 桥只允许精确 ASCII 主机名且不支持通配符：$host"
          }
          host.lowercase()
        }
        DesktopScriptBridgeConfiguration(
          bridge = bridge,
          transportName = transportName,
          facade = facade,
          queryFunction = "__multiweb_query_$transportName",
          allowedHosts = allowedHosts,
        )
      }
    }

    private fun isValidHost(host: String): Boolean {
      if (
        host.isBlank() || host != host.trim() || host.any { it.code > 0x7f } ||
        host.any { it in "*/:?#@'\\\"" }
      ) {
        return false
      }
      val normalizedHost = host.lowercase()
      val parsedHost = runCatching { URI("https://$normalizedHost").host }.getOrNull()
      return parsedHost == normalizedHost
    }
  }
}

/** 管理 Desktop 桥的 JCEF 路由、处理器和注入生命周期。 */
internal class DesktopScriptBridgeInstallation private constructor(
  private val client: CefClient?,
  private val bindings: List<Binding>,
  private val configurations: List<DesktopScriptBridgeConfiguration>,
) {
  /** 在主文档开始加载时只向受信任页面注入对应桥对象。 */
  fun inject(frame: CefFrame) {
    if (!frame.isMain) {
      return
    }
    configurations
      .filter { configuration -> configuration.isAllowedUrl(frame.url) }
      .forEach { configuration ->
        frame.executeJavaScript(configuration.injectionScript(), "multiweb://bridge", 0)
      }
  }

  /** 从客户端移除并销毁所有路由，确保浏览器关闭后不再收到脚本回调。 */
  fun dispose() {
    bindings.forEach { binding ->
      client?.removeMessageRouter(binding.router)
      binding.router.removeHandler(binding.handler)
      binding.router.dispose()
    }
  }

  private data class Binding(
    val router: CefMessageRouter,
    val handler: DesktopScriptBridgeHandler,
  )

  companion object {
    /** 在 JCEF 客户端注册桥路由；JavaScript 关闭或无桥时返回空安装。 */
    fun install(
      client: CefClient,
      enabled: Boolean,
      bridges: List<ScriptBridge>,
    ): DesktopScriptBridgeInstallation {
      if (!enabled || bridges.isEmpty()) {
        return DesktopScriptBridgeInstallation(null, emptyList(), emptyList())
      }
      val configurations = DesktopScriptBridgeConfiguration.create(bridges)
      val bindings = configurations.map { configuration ->
        val handler = DesktopScriptBridgeHandler(configuration)
        val router = CefMessageRouter.create(
          CefMessageRouter.CefMessageRouterConfig(
            configuration.queryFunction,
            "${configuration.queryFunction}Cancel",
          ),
          handler,
        )
        client.addMessageRouter(router)
        Binding(router, handler)
      }
      return DesktopScriptBridgeInstallation(client, bindings, configurations)
    }
  }
}

/** 在 JCEF UI 线程处理查询，并在原生侧再次执行来源校验。 */
internal class DesktopScriptBridgeHandler(
  private val configuration: DesktopScriptBridgeConfiguration,
) : CefMessageRouterHandlerAdapter() {
  override fun onQuery(
    browser: CefBrowser,
    frame: CefFrame,
    queryId: Long,
    request: String,
    persistent: Boolean,
    callback: CefQueryCallback,
  ): Boolean {
    if (!frame.isMain || !configuration.isAllowedUrl(frame.url)) {
      callback.failure(403, "untrusted_origin")
      return true
    }
    val call = request.toScriptBridgeCall()
    if (call == null) {
      callback.failure(400, "invalid_request")
      return true
    }
    if (!configuration.isMethodAllowed(call.method)) {
      callback.success(ScriptBridgeResponse(isSuccess = false, errorCode = "method_not_allowed").toJson())
      return true
    }
    val response = runCatching { configuration.bridge.handle(call) }
      .getOrElse { ScriptBridgeResponse(isSuccess = false, errorCode = "bridge_exception") }
      ?: ScriptBridgeResponse(isSuccess = true)
    callback.success(response.toJson())
    return true
  }

  private fun String.toScriptBridgeCall(): ScriptBridgeCall? {
    val separatorIndex = indexOf(':')
    if (separatorIndex <= 0) {
      return null
    }
    val method = decodePercentEncodedUtf8(substring(0, separatorIndex)) ?: return null
    val payload = decodePercentEncodedUtf8(substring(separatorIndex + 1)) ?: return null
    return method.takeIf(String::isNotBlank)?.let { ScriptBridgeCall(it, payload) }
  }

  private fun decodePercentEncodedUtf8(value: String): String? {
    return runCatching {
      URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrNull()
  }

  private fun ScriptBridgeResponse.toJson(): String {
    return """{"isSuccess":$isSuccess,"payload":${payload.toJavaScriptString()},"errorCode":${errorCode.toJsonValue()}}"""
  }

  private fun String?.toJsonValue(): String = this?.toJavaScriptString() ?: "null"
}

/** 生成安全的 JavaScript/JSON 字符串字面量，避免桥响应内容破坏脚本上下文。 */
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
          append("\\u%04x".format(character.code))
        } else {
          append(character)
        }
      }
    }
    append('"')
  }
}
