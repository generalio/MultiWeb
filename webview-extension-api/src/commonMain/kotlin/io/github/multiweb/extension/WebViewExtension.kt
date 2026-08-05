package io.github.multiweb.extension

import io.github.multiweb.api.WebError

/**
 * WebView 的跨平台扩展契约。
 *
 * 扩展由平台控制器组合调用，不能替换平台内部的导航、安全校验或原生 Client。各回调默认无操作，
 * 以便业务方仅实现所需能力。平台不支持某项事件时不会回调，调用方应结合宿主需求提供降级方案。
 */
interface WebViewExtension {
  /** 需要注册到原生 WebView 的 JS 桥；桥只会在其 [ScriptBridge.allowedHosts] 中声明的受信任主机生效。 */
  val scriptBridges: List<ScriptBridge>
    get() = emptyList()

  /** 主文档开始加载时调用。 */
  fun onPageStarted(event: PageStartedEvent) = Unit

  /** 主文档完成加载时调用。 */
  fun onPageFinished(event: PageFinishedEvent) = Unit

  /** 主文档发生不可恢复加载错误时调用。 */
  fun onPageError(event: PageErrorEvent) = Unit

  /** 页面请求下载文件时调用；扩展负责交由宿主或平台下载器执行。 */
  fun onDownloadRequested(request: DownloadRequest) = Unit

  /** 用户在网页内容上触发长按等上下文操作时调用。 */
  fun onContextAction(action: WebContextAction) = Unit

  /** 页面或扩展请求宿主级 UI 操作时调用，例如切换沉浸式全屏。 */
  fun onHostUiRequest(request: HostUiRequest) = Unit
}

/** 主文档开始加载事件。 */
data class PageStartedEvent(
  /** 当前开始加载的主文档地址。 */
  val url: String,
)

/** 主文档完成加载事件。 */
data class PageFinishedEvent(
  /** 已完成加载的主文档地址。 */
  val url: String,
  /** 平台可取得的页面标题；尚未取得时为 `null`。 */
  val title: String? = null,
)

/** 主文档加载错误事件。 */
data class PageErrorEvent(
  /** 已映射为跨平台模型的页面错误。 */
  val error: WebError,
)

/** 页面请求下载的文件信息。 */
data class DownloadRequest(
  /** 下载目标地址。 */
  val url: String,
  /** 页面建议的文件名；平台未提供时为 `null`。 */
  val suggestedFileName: String? = null,
  /** 服务端声明的 MIME 类型；平台未提供时为 `null`。 */
  val mimeType: String? = null,
  /** 服务端声明的内容长度；未知时为 `null`。 */
  val contentLength: Long? = null,
)

/** 网页内容上的用户上下文操作。 */
sealed interface WebContextAction {
  /** 用户长按网页链接。 */
  data class LinkLongPressed(
    /** 链接目标地址。 */
    val url: String,
  ) : WebContextAction

  /** 用户长按网页图片。 */
  data class ImageLongPressed(
    /** 图片资源地址。 */
    val url: String,
  ) : WebContextAction
}

/** 需要宿主处理的 UI 请求。 */
sealed interface HostUiRequest {
  /** 请求宿主显示或退出沉浸式全屏。 */
  data class SetFullscreen(
    /** `true` 表示进入全屏，`false` 表示恢复常规界面。 */
    val enabled: Boolean,
  ) : HostUiRequest
}

/**
 * 可暴露给受信任网页的 JS 桥。
 *
 * 平台实现必须先校验当前主文档主机名位于 [allowedHosts]，再向网页暴露该桥。桥名称和
 * [ScriptBridgeWithFacade] 的方法名应使用业务命名空间，避免与页面脚本冲突；禁止向未受信任页面暴露任意原生对象。
 */
interface ScriptBridge {
  /** 页面脚本访问该桥时使用的全局名称。 */
  val name: String

  /** 允许使用该桥的主机名集合，不能为空且不支持通配符；仅匹配 HTTPS 默认端口 443。 */
  val allowedHosts: Set<String>

  /** 处理来自网页的显式方法调用，并返回可序列化的响应；不需要响应时返回 `null`。 */
  fun handle(call: ScriptBridgeCall): ScriptBridgeResponse?
}

/**
 * 使用独立内部传输对象和受限方法门面的 JS 桥。
 *
 * 该能力单独建模，避免给既有 [ScriptBridge] 增加成员而破坏已编译实现的二进制兼容性。平台会把 [facade]
 * 声明的方法转为 [ScriptBridge.handle] 调用，不会向网页暴露任意 Kotlin 对象；每个方法以 Promise 返回
 * [ScriptBridgeResponse]。
 */
interface ScriptBridgeWithFacade : ScriptBridge {
  /** 平台消息通道使用的内部全局名称，应与 [ScriptBridge.name] 不同。 */
  val transportName: String

  /** 可暴露给受信任网页的受限方法集合。 */
  val facade: ScriptBridgeFacade
}

/**
 * 暴露给网页的受限方法集合。
 *
 * 方法名称必须由平台实现校验为 JavaScript 标识符；调用参数统一转换为字符串，复杂参数应由调用方自行使用
 * JSON 文本序列化并在 [ScriptBridge.handle] 中校验。
 */
data class ScriptBridgeFacade(
  /** 可从网页调用的方法名称集合。 */
  val methodNames: Set<String>,
)

/** JS 桥调用参数。 */
data class ScriptBridgeCall(
  /** 调用的方法名称，由扩展自行校验。 */
  val method: String,
  /** 由扩展自行定义和校验的序列化参数。 */
  val payload: String = "",
)

/** JS 桥响应数据。 */
data class ScriptBridgeResponse(
  /** 表示调用是否成功。 */
  val isSuccess: Boolean,
  /** 由扩展自行定义的序列化响应内容。 */
  val payload: String = "",
  /** 调用失败时面向页面的错误码；成功时为 `null`。 */
  val errorCode: String? = null,
)
