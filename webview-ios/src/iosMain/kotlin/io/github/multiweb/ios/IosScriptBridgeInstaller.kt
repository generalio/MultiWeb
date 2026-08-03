package io.github.multiweb.ios

import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.darwin.NSObject

/**
 * 为 WKWebView 安装受限来源的单向 JS 命令桥。
 *
 * WebKit 的基础消息处理器不能可靠地把 Kotlin 调用结果同步回传给网页，因此本实现仅保证命令已交给
 * [ScriptBridge.handle] 处理，不会伪造
 * [io.github.multiweb.extension.ScriptBridgeResponse] 已送达。桥同时通过 document-start 脚本和原生消息
 * 入口校验 HTTPS 精确主机名，避免未受信任页面获得原生能力。
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
        userContentController.addScriptMessageHandler(handler, configuration.bridge.name)
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
  fun dispose() {
    handlers.forEach { handler ->
      userContentController.removeScriptMessageHandlerForName(handler.bridgeName)
    }
    userContentController.removeAllUserScripts()
  }
}

/** 经过精确来源校验后才可注册的 iOS JS 桥配置。 */
@OptIn(ExperimentalForeignApi::class)
internal data class IosScriptBridgeConfiguration(
  val bridge: ScriptBridge,
  val allowedHosts: Set<String>,
) {
  fun injectionScript(): String {
    val hostCheck = allowedHosts.joinToString(" || ") { host -> "window.location.hostname === '$host'" }
    return """
      (function() {
        if (window.location.protocol !== 'https:' || !($hostCheck)) return;
        var handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.${bridge.name};
        if (!handler) return;
        try {
          Object.defineProperty(window, '${bridge.name}', {
            value: Object.freeze({
              postMessage: function(method, payload) {
                handler.postMessage(encodeURIComponent(String(method)) + ':' +
                  encodeURIComponent(payload == null ? '' : String(payload)));
              }
            }),
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

    fun create(bridges: List<ScriptBridge>): List<IosScriptBridgeConfiguration> {
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
            "JS 桥只允许精确 ASCII 主机名且不支持通配符：$host"
          }
          host.lowercase()
        }
        IosScriptBridgeConfiguration(bridge, allowedHosts)
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
  /** 用于释放时从 WKUserContentController 注销当前处理器的桥名称。 */
  val bridgeName: String = configuration.bridge.name

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

    val call = (didReceiveScriptMessage.body as? String)?.toScriptBridgeCall() ?: return
    runCatching { configuration.bridge.handle(call) }
  }

  private fun String.toScriptBridgeCall(): ScriptBridgeCall? {
    val separatorIndex = indexOf(':')
    if (separatorIndex <= 0) {
      return null
    }
    val method = substring(0, separatorIndex).decodePercentEncodedUtf8() ?: return null
    if (method.isBlank()) {
      return null
    }
    val payload = substring(separatorIndex + 1).decodePercentEncodedUtf8() ?: return null
    return ScriptBridgeCall(method = method, payload = payload)
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
}
