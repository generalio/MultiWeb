package io.github.multiweb.api

/**
 * 跨平台浏览器控制契约。
 *
 * 原生视图的创建与生命周期由各平台实现模块负责，调用方只通过此接口执行浏览器操作。
 */
interface WebViewController {
  /** 当前浏览器的可观察状态。 */
  val state: WebViewState

  /** 按请求加载页面。 */
  fun load(request: WebRequest)

  /** 重新加载当前页面。 */
  fun reload()

  /** 返回上一条历史记录。无历史记录时由平台实现保持当前页面。 */
  fun goBack()

  /** 前进到下一条历史记录。无可前进记录时由平台实现保持当前页面。 */
  fun goForward()

  /** 停止正在进行的页面加载。 */
  fun stopLoading()

  /** 清除 Cookie、缓存等会话数据，具体范围由平台实现文档说明。 */
  fun clearSession()

  /** 释放原生视图及其关联资源；调用后不得继续使用此控制器。 */
  fun dispose()
}

/** 一次页面加载请求。 */
data class WebRequest(
  /** 待加载的完整 URL。调用方负责提供符合导航策略的地址。 */
  val url: String,
  /** 附加请求头；平台不支持时必须在实现文档中明确说明。 */
  val headers: Map<String, String> = emptyMap(),
)

/** 浏览器运行状态快照。 */
data class WebViewState(
  /** 当前主文档 URL；尚未加载页面时为 `null`。 */
  val url: String? = null,
  /** 当前页面标题；平台尚未提供标题时为 `null`。 */
  val title: String? = null,
  /** 加载进度，取值范围为 `0f..1f`。 */
  val loadingProgress: Float = 0f,
  /** 是否正在加载主文档或其重定向页面。 */
  val isLoading: Boolean = false,
  /** 是否存在可返回的历史记录。 */
  val canGoBack: Boolean = false,
  /** 是否存在可前进的历史记录。 */
  val canGoForward: Boolean = false,
  /** 最近一次不可恢复的页面错误；成功导航后由平台实现清除。 */
  val error: WebError? = null,
)

/** 页面加载或渲染过程中产生的错误信息。 */
data class WebError(
  /** 便于跨平台统一处理的错误分类。 */
  val category: WebErrorCategory,
  /** 面向调用方展示或记录的错误描述。 */
  val description: String,
  /** 发生错误的 URL；平台无法提供时为 `null`。 */
  val failingUrl: String? = null,
)

/** 跨平台错误分类。 */
enum class WebErrorCategory {
  /** 导航被取消、拦截或无法继续。 */
  Navigation,
  /** 网络连接、超时或响应失败。 */
  Network,
  /** TLS/SSL 证书或安全协商失败。 */
  Ssl,
  /** 原生 Web 渲染进程异常退出。 */
  RenderProcess,
  /** 无法映射到统一分类的错误。 */
  Unknown,
}
