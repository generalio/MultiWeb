package io.github.multiweb.browser

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import io.github.multiweb.api.WebViewStateObservable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JS/Wasm 浏览器平台的链接控制器。
 *
 * 浏览器不能提供可嵌入、可隔离的原生 WebView；允许的请求会在新标签页或新窗口中打开。
 * [WebViewConfig.javaScriptEnabled]、Cookie 与临时会话选项由目标浏览器管理，当前模块不会尝试
 * 修改它们。[clearSession] 会明确抛出异常，防止调用方误以为已清理浏览器的全局会话数据。
 */
class BrowserWebViewController internal constructor(
  /** 浏览器平台仍会执行的跨平台导航和主机白名单配置。 */
  private val config: WebViewConfig,
  /** 业务侧导航策略。 */
  private val navigationPolicy: NavigationPolicy,
  /** 打开新浏览器上下文的实现；内部构造器允许契约测试替换该操作。 */
  private val openUrl: (String) -> Unit,
) : WebViewController, WebViewStateObservable {
  /** 使用浏览器默认新窗口行为创建控制器。 */
  constructor(
    config: WebViewConfig = WebViewConfig(),
    navigationPolicy: NavigationPolicy,
  ) : this(config, navigationPolicy, ::openUrlInNewBrowserWindow)

  /** 控制器是否已释放。释放后除 [dispose] 外的操作都会抛出 [IllegalStateException]。 */
  var isDisposed: Boolean = false
    private set

  private val mutableState = MutableStateFlow(WebViewState())

  override var state: WebViewState
    get() = mutableState.value
    private set(value) {
      mutableState.value = value
    }

  override val stateFlow: StateFlow<WebViewState> = mutableState.asStateFlow()

  override fun load(request: WebRequest) {
    ensureUsable()
    val navigationRequest = NavigationRequest(
      url = request.url,
      isMainFrame = true,
      isUserInitiated = false,
    )
    val decision = decide(navigationRequest)
    if (decision == NavigationDecision.Cancel) {
      return
    }

    openUrl(request.url)
    state = WebViewState(
      url = request.url,
      loadingProgress = 1f,
      isLoading = false,
    )
  }

  override fun reload() {
    ensureUsable()
    state.url?.let(openUrl)
  }

  override fun goBack() {
    ensureUsable()
  }

  override fun goForward() {
    ensureUsable()
  }

  override fun stopLoading() {
    ensureUsable()
  }

  override fun clearSession() {
    ensureUsable()
    throw UnsupportedOperationException(
      "浏览器平台的 Cookie 与缓存属于用户浏览器，BrowserWebViewController 无法安全清理。",
    )
  }

  override fun dispose() {
    isDisposed = true
  }

  private fun decide(request: NavigationRequest): NavigationDecision {
    if (!isAllowedHost(request.url)) {
      return NavigationDecision.Cancel
    }
    return navigationPolicy.decide(request)
  }

  private fun isAllowedHost(url: String): Boolean {
    if (config.allowedHosts.isEmpty()) {
      return true
    }
    val host = extractHost(url) ?: return false
    return config.allowedHosts.any { allowedHost ->
      host.equals(allowedHost.trimEnd('.'), ignoreCase = true)
    }
  }

  /**
   * 仅解析完整绝对 URL 的主机名，用于白名单精确匹配。
   *
   * 组件不接受没有 Scheme、包含空主机名或格式不完整的地址，避免把相对地址误判为受信任主机。
   */
  private fun extractHost(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) {
      return null
    }
    val authority = url.substring(schemeEnd + 3)
      .substringBefore('/')
      .substringBefore('?')
      .substringBefore('#')
      .substringAfterLast('@')
    if (authority.isEmpty()) {
      return null
    }
    return if (authority.startsWith('[')) {
      authority.substringAfter('[').substringBefore(']').takeIf { it.isNotEmpty() }
    } else {
      authority.substringBefore(':').trimEnd('.').takeIf { it.isNotEmpty() }
    }
  }

  private fun ensureUsable() {
    check(!isDisposed) { "BrowserWebViewController 已释放，不能继续执行操作。" }
  }

}

/** 供同模块契约测试注入浏览器打开动作，生产调用方只能使用公开构造器。 */
internal fun createBrowserWebViewControllerForTesting(
  config: WebViewConfig = WebViewConfig(),
  navigationPolicy: NavigationPolicy,
  openUrl: (String) -> Unit,
): BrowserWebViewController {
  return BrowserWebViewController(config, navigationPolicy, openUrl)
}

/** 在目标浏览器中打开 URL，具体实现必须创建新的浏览器上下文。 */
internal expect fun openUrlInNewBrowserWindow(url: String)
