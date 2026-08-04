package io.github.multiweb.ios

import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeResponse
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * 为 WKWebView 安装受限来源的 JS 命令桥。
 *
 * 普通消息桥维持单向 `postMessage` 行为。声明了 [io.github.multiweb.extension.ScriptBridge.facade] 的桥会在
 * document-start 脚本中安装 Promise 门面，原生处理完成后通过当前受信任主文档回传
 * [ScriptBridgeResponse]。两条路径均在脚本和原生消息入口校验 HTTPS 精确主机名。
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosScriptBridgeInstaller {
  /**
   * 创建需要放入 [platform.WebKit.WKWebViewConfiguration] 的桥接组件。
   *
   * JavaScript 被关闭时不注册桥，也不校验未启用桥的配置，确保其不会影响纯原生浏览场景。
   */
  fun create(enabled: Boolean, bridges: List<ScriptBridge>): IosScriptBridgeInstallation {
    val userContentController = WKUserContentController()
    if (!enabled || bridges.isEmpty()) {
      return IosScriptBridgeInstallation(userContentController, emptyList())
    }

    val configurations = IosScriptBridgeConfiguration.create(bridges)
    val handlers = configurations.map { configuration ->
      IosScriptBridgeMessageHandler(configuration).also { handler ->
        userContentController.addScriptMessageHandler(handler, configuration.transportName)
        userContentController.addUserScript(
          WKUserScript(
            source = configuration.injectionScript(),
            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            forMainFrameOnly = true,
          ),
        )
      }
    }
    return IosScriptBridgeInstallation(userContentController, handlers)
  }
}

/** 保存 WebKit 所需的对象引用，并在控制器释放时注销消息处理器。 */
@OptIn(ExperimentalForeignApi::class)
internal class IosScriptBridgeInstallation(
  /** 安装了桥脚本与原生消息处理器的内容控制器。 */
  val userContentController: WKUserContentController,
  /** 强引用处理器，防止其在 WebKit 回调前被释放。 */
  private val handlers: List<IosScriptBridgeMessageHandler>,
) {
  /** 绑定控制器创建完成后的 WKWebView，供 Promise 门面安全地回传结果。 */
  fun attach(webView: WKWebView) {
    handlers.forEach { handler -> handler.attach(webView) }
  }

  fun dispose() {
    handlers.forEach { handler ->
      handler.detach()
      userContentController.removeScriptMessageHandlerForName(handler.transportName)
    }
    userContentController.removeAllUserScripts()
  }
}

/** 经过精确来源校验后才可注册的 iOS JS 桥配置。 */
@OptIn(ExperimentalForeignApi::class)
internal data class IosScriptBridgeConfiguration(
  val bridge: ScriptBridge,
  val transportName: String,
  val allowedHosts: Set<String>,
) {
  /** Promise 门面在网页侧等待原生回包时使用的全局回调名称。 */
  val replyFunctionName: String = "__multiwebReply_$transportName"

  /** 判断 WKWebView 当前主文档是否仍属于当前桥的受信任来源。 */
  fun isAllowedUrl(url: String): Boolean {
    val parsed = NSURL(string = url)
    return parsed.scheme?.lowercase() == "https" && parsed.host?.lowercase() in allowedHosts
  }

  fun injectionScript(): String {
    val hostCheck = allowedHosts.joinToString(" || ") { host -> "window.location.hostname === '$host'" }
    val facade = bridge.facade
    val facadeMethods = facade?.methodNames?.joinToString(",") { method ->
      "${method.toJavaScriptString()}:function(payload){return invoke(${method.toJavaScriptString()},payload);}"
    }.orEmpty()
    val requestParser = """
      function parseRequest(requestOrMethod, payload) {
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
        if (typeof method !== 'string' || method.length === 0) return null;
        return { method: method, payload: requestPayload == null ? '' : String(requestPayload) };
      }
    """.trimIndent()
    val promiseSupport = if (facade == null) {
      """
        function send(requestOrMethod, payload) {
          var request = parseRequest(requestOrMethod, payload);
          if (!request) return;
          handler.postMessage(encodeURIComponent(request.method) + ':' +
            encodeURIComponent(request.payload));
        }
      """.trimIndent()
    } else {
      """
        var sequence = 0;
        var pending = Object.create(null);
        window[${replyFunctionName.toJavaScriptString()}] = function(id, response) {
          var callback = pending[id];
          if (!callback) return;
          delete pending[id];
          if (response && response.isSuccess) callback.resolve(response);
          else {
            var error = new Error((response && response.errorCode) || 'bridge_error');
            error.response = response;
            callback.reject(error);
          }
        };
        function send(requestOrMethod, payload) {
          var request = parseRequest(requestOrMethod, payload);
          if (!request) return Promise.reject(new Error('invalid_bridge_request'));
          return new Promise(function(resolve, reject) {
            var id = String(++sequence);
            pending[id] = { resolve: resolve, reject: reject };
            try {
              handler.postMessage(encodeURIComponent(request.method) + ':' +
                encodeURIComponent(request.payload) + ':' +
                encodeURIComponent(id));
            } catch (error) {
              delete pending[id];
              reject(error);
            }
          });
        }
      """.trimIndent()
    }
    val publicBridgeDefinition = if (facade == null) {
      """
        Object.defineProperty(window, ${bridge.name.toJavaScriptString()}, {
          value: Object.freeze({ postMessage: send }),
          enumerable: false,
          writable: false,
          configurable: false
        });
      """.trimIndent()
    } else {
      """
        function invoke(method, payload) { return send(method, payload); }
        Object.defineProperty(window, ${bridge.name.toJavaScriptString()}, {
          value: Object.freeze({$facadeMethods}),
          enumerable: false,
          writable: false,
          configurable: false
        });
      """.trimIndent()
    }
    return """
      (function() {
        if (window.location.protocol !== 'https:' || !($hostCheck)) return;
        var handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers[${transportName.toJavaScriptString()}];
        if (!handler) return;
        try {
          $requestParser
          $promiseSupport
          $publicBridgeDefinition
        } catch (_) {}
      })();
    """.trimIndent()
  }

  companion object {
    private val bridgeNamePattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun create(bridges: List<ScriptBridge>): List<IosScriptBridgeConfiguration> {
      val bridgeNames = mutableSetOf<String>()
      val transportNames = mutableSetOf<String>()
      return bridges.map { bridge ->
        require(bridgeNamePattern.matches(bridge.name)) {
          "JS 桥名称必须是 ASCII JavaScript 标识符：${bridge.name}"
        }
        require(bridgeNames.add(bridge.name)) {
          "JS 桥名称不能重复：${bridge.name}"
        }
        require(bridgeNamePattern.matches(bridge.transportName)) {
          "JS 桥内部消息名称必须是 ASCII JavaScript 标识符：${bridge.transportName}"
        }
        require(transportNames.add(bridge.transportName)) {
          "JS 桥内部消息名称不能重复：${bridge.transportName}"
        }
        require(bridge.transportName == bridge.name || bridge.facade != null) {
          "使用独立内部消息名称时必须声明 JS 桥门面：${bridge.name}"
        }
        bridge.facade?.let { facade ->
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
        IosScriptBridgeConfiguration(bridge, bridge.transportName, allowedHosts)
      }
    }

    private fun isValidHost(host: String): Boolean {
      if (
        host.isBlank() || host != host.trim() || host.any { it.code > 0x7f } ||
        host.any { it in "*/:?#@" }
      ) {
        return false
      }
      val normalizedHost = host.lowercase()
      return NSURL(string = "https://$normalizedHost").host == normalizedHost
    }
  }
}

/** 在原生侧复核来源并分发网页命令，不能仅依赖注入脚本的前置判断。 */
@OptIn(ExperimentalForeignApi::class)
internal class IosScriptBridgeMessageHandler(
  private val configuration: IosScriptBridgeConfiguration,
) : NSObject(), WKScriptMessageHandlerProtocol {
  /** 用于释放时从 WKUserContentController 注销当前处理器的内部消息名称。 */
  val transportName: String = configuration.transportName
  /** Promise 门面回包使用的 WKWebView；释放时必须清空以打破 WebKit 的引用环。 */
  private var webView: WKWebView? = null

  /** 绑定控制器创建的 WKWebView。 */
  fun attach(webView: WKWebView) {
    this.webView = webView
  }

  /** 断开 WKWebView 引用，防止内容控制器长期持有已释放控制器。 */
  fun detach() {
    webView = null
  }

  override fun userContentController(
    userContentController: WKUserContentController,
    didReceiveScriptMessage: WKScriptMessage,
  ) {
    val frameInfo = didReceiveScriptMessage.frameInfo
    val origin = frameInfo.securityOrigin
    if (
      !frameInfo.mainFrame || origin.protocol != "https" ||
      origin.host.lowercase() !in configuration.allowedHosts
    ) {
      return
    }

    val parsedCall = (didReceiveScriptMessage.body as? String)?.toScriptBridgeCall() ?: return
    val response = runCatching { configuration.bridge.handle(parsedCall.call) }
      .getOrElse { ScriptBridgeResponse(isSuccess = false, errorCode = "bridge_exception") }
      ?: ScriptBridgeResponse(isSuccess = true)
    parsedCall.id?.let { callId -> reply(callId, response) }
  }

  private fun String.toScriptBridgeCall(): ParsedScriptBridgeCall? {
    val separatorIndex = indexOf(':')
    if (separatorIndex <= 0) {
      return null
    }
    val method = substring(0, separatorIndex).decodePercentEncodedUtf8() ?: return null
    if (method.isBlank()) {
      return null
    }
    val idSeparatorIndex = indexOf(':', startIndex = separatorIndex + 1)
    val encodedPayload = if (idSeparatorIndex == -1) {
      substring(separatorIndex + 1)
    } else {
      substring(separatorIndex + 1, idSeparatorIndex)
    }
    val payload = encodedPayload.decodePercentEncodedUtf8() ?: return null
    val id = if (idSeparatorIndex == -1) {
      null
    } else {
      substring(idSeparatorIndex + 1).decodePercentEncodedUtf8()?.takeIf(String::isNotBlank)
        ?: return null
    }
    return ParsedScriptBridgeCall(
      call = ScriptBridgeCall(method = method, payload = payload),
      id = id,
    )
  }

  /** 仅向仍停留在受信任主文档的 Promise 门面回传结果，页面已跳转时静默丢弃。 */
  private fun reply(callId: String, response: ScriptBridgeResponse) {
    val currentWebView = webView ?: return
    val currentUrl = currentWebView.URL?.absoluteString ?: return
    if (!configuration.isAllowedUrl(currentUrl)) {
      return
    }
    currentWebView.evaluateJavaScript(
      "window[${configuration.replyFunctionName.toJavaScriptString()}](${callId.toJavaScriptString()},${response.toJavaScriptObject()});",
      completionHandler = null,
    )
  }

  /** 将 JavaScript `encodeURIComponent` 产生的 ASCII 字节流严格解码为 UTF-8。 */
  private fun String.decodePercentEncodedUtf8(): String? {
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
      val character = this[index]
      if (character == '%') {
        if (index + 2 >= length) {
          return null
        }
        val high = this[index + 1].hexValue() ?: return null
        val low = this[index + 2].hexValue() ?: return null
        bytes += ((high shl 4) or low).toByte()
        index += 3
      } else {
        if (character.code > 0x7f) {
          return null
        }
        bytes += character.code.toByte()
        index += 1
      }
    }
    return runCatching {
      bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()
  }

  private fun Char.hexValue(): Int? = when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    in 'A'..'F' -> code - 'A'.code + 10
    else -> null
  }

  /** Promise 门面的关联标识只存在于 iOS 消息解析层，不改变公共桥调用模型。 */
  private data class ParsedScriptBridgeCall(
    val call: ScriptBridgeCall,
    val id: String?,
  )
}

/** 将字符串编码为安全的 JavaScript 字符串字面量，供 WebKit 回包与注入脚本使用。 */
private fun String.toJavaScriptString(): String {
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

/** 将桥响应转换为不会改变当前 JavaScript 上下文的对象字面量。 */
private fun ScriptBridgeResponse.toJavaScriptObject(): String {
  return "{\"isSuccess\":$isSuccess,\"payload\":${payload.toJavaScriptString()},\"errorCode\":${errorCode?.toJavaScriptString() ?: "null"}}"
}
