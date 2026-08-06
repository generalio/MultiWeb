package io.github.multiweb.extension

import io.github.multiweb.api.JavaScriptExecutor
import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebViewController

/**
 * WebView 的跨平台扩展契约。
 *
 * 扩展由平台控制器组合调用，不能替换平台内部的导航、安全校验或原生 Client。各回调默认无操作，
 * 以便业务方仅实现所需能力。平台不支持某项事件时不会回调，调用方应结合宿主需求提供降级方案。
 */
interface WebViewExtension {
  /** 需要注册到原生 WebView 的 JS 桥；平台按 [ScriptBridge.resolvedOriginPolicy] 限制可使用桥的页面。 */
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

/**
 * 需要感知控制器生命周期的可选扩展。
 *
 * 该子接口独立于 [WebViewExtension]，避免给已发布父接口新增成员而破坏既有实现的二进制兼容性。控制器仅对实现
 * 本接口的扩展分发生命周期事件；回调中不得持有平台原生视图，且应在 [onControllerDisposed] 时清理控制器引用。
 */
interface WebViewControllerLifecycleExtension : WebViewExtension {
  /** 控制器及其内部安全策略、原生 Client 和桥安装均已完成后调用。 */
  fun onControllerAttached(controller: WebViewController) = Unit

  /** 控制器释放前调用；扩展必须清理与该控制器关联的状态和引用。 */
  fun onControllerDisposed() = Unit
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
 * 平台实现必须按 [resolvedOriginPolicy] 校验当前主文档后再向网页暴露该桥。未实现
 * [OriginPolicyAwareScriptBridge] 的桥保持 [allowedHosts] 精确 HTTPS 主机语义。桥名称和
 * [ScriptBridgeWithFacade] 的方法名应使用业务命名空间，避免与页面脚本冲突；禁止向未受信任页面暴露任意原生对象。
 */
interface ScriptBridge {
  /** 页面脚本访问该桥时使用的全局名称。 */
  val name: String

  /**
   * 允许使用桥的精确 HTTPS 主机名集合。
   *
   * 未实现 [OriginPolicyAwareScriptBridge] 时不能为空且不支持通配符；实现该接口后由
   * [OriginPolicyAwareScriptBridge.originPolicy] 决定语义。
   */
  val allowedHosts: Set<String>

  /** 处理来自网页的显式方法调用，并返回可序列化的响应；不需要响应时返回 `null`。 */
  fun handle(call: ScriptBridgeCall): ScriptBridgeResponse?
}

/**
 * JS 桥允许接收网页消息的来源策略。
 *
 * 默认应使用 [ExactHttpsHosts]。只有迁移无法修改的旧网页时才可显式选择 [UnsafeAnyHttpOrHttps]；后者会将桥
 * 暴露给任意 HTTP/HTTPS 主文档，不能用于令牌、账户、支付、路由等高权限业务。平台不支持在不放宽到其他来源的
 * 前提下实现该策略时，必须拒绝安装，而不能静默放宽为全来源桥。
 */
sealed interface ScriptBridgeOriginPolicy {
  /** 仅允许指定精确主机的 HTTPS 默认端口 443 页面使用桥。 */
  data class ExactHttpsHosts(
    /** 允许使用桥的精确 HTTPS 主机名集合；不能为空且不支持通配符。 */
    val hosts: Set<String>,
  ) : ScriptBridgeOriginPolicy

  /**
   * 不安全的旧页面兼容模式：仅允许主框架的 HTTP/HTTPS 页面使用桥。
   *
   * 该模式会接受任意主机和端口，调用方必须将桥方法限制为低权限、可验证的业务请求。并非所有平台都能在不把桥
   * 暴露给 `file:`、`data:` 或子框架的前提下实现它；此类平台会在安装阶段明确拒绝。
   */
  data object UnsafeAnyHttpOrHttps : ScriptBridgeOriginPolicy
}

/**
 * 可声明自定义来源策略的 JS 桥。
 *
 * 该子接口避免向已发布的 [ScriptBridge] 增加抽象成员。未实现时，平台将 [ScriptBridge.allowedHosts] 解释为
 * [ScriptBridgeOriginPolicy.ExactHttpsHosts]，完全保持既有默认行为。
 */
interface OriginPolicyAwareScriptBridge : ScriptBridge {
  /** 当前桥的来源策略；平台的桥注入、消息处理与脚本执行必须使用同一策略。 */
  val originPolicy: ScriptBridgeOriginPolicy
}

/**
 * 可按 [ScriptBridgeOriginPolicy] 提交脚本的控制器能力。
 *
 * 该子接口避免修改已发布的 [JavaScriptExecutor]。仅实现旧接口的控制器仍可执行精确 HTTPS 主机策略；无法安全实现
 * [ScriptBridgeOriginPolicy.UnsafeAnyHttpOrHttps] 时必须返回 `false`。
 */
interface OriginPolicyAwareJavaScriptExecutor : JavaScriptExecutor {
  /** 向符合 [originPolicy] 的当前主文档提交脚本；拒绝时返回 `false`。 */
  fun executeJavaScript(
    script: String,
    originPolicy: ScriptBridgeOriginPolicy,
  ): Boolean
}

/**
 * 取得桥的有效来源策略。
 *
 * 未实现 [OriginPolicyAwareScriptBridge] 的既有桥保持精确 HTTPS 主机语义；平台实现应使用此函数而非直接读取
 * [ScriptBridge.allowedHosts]，避免桥注入、消息回调和脚本执行出现策略分叉。
 */
fun ScriptBridge.resolvedOriginPolicy(): ScriptBridgeOriginPolicy {
  return (this as? OriginPolicyAwareScriptBridge)?.originPolicy
    ?: ScriptBridgeOriginPolicy.ExactHttpsHosts(allowedHosts)
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
