package io.github.multiweb.ios

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebErrorCategory
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import io.github.multiweb.api.WebViewStateObservable
import io.github.multiweb.extension.PageErrorEvent
import io.github.multiweb.extension.PageFinishedEvent
import io.github.multiweb.extension.PageStartedEvent
import io.github.multiweb.extension.WebViewExtension
import io.github.multiweb.extension.WebViewInitialization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.distantPast
import platform.Foundation.NSThread
import platform.Foundation.setValue
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeOther
import platform.WebKit.WKPreferences
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * 基于系统 [WKWebView] 的 iOS 控制器。
 *
 * 必须在主线程创建与调用。宿主将 [view] 加入自己的视图层级，并在不再需要时调用 [dispose]。
 * iOS 不需要与 Android 相同的暂停、恢复转发；UIKit 会处理应用前后台切换。
 *
 * `thirdPartyCookiesEnabled` 无法由单个 WKWebView 精确控制，WebKit 使用系统级 Intelligent
 * Tracking Prevention 管理跨站 Cookie。本实现会保留系统默认隐私策略，不尝试降低该保护等级。
 * [WebRequest.headers] 仅会附加到 HTTP(S) 请求；本地文件加载不适用请求头。
 *
 * [extensions] 只会接收平台事件和受限 JS 桥调用，不能替换内部导航代理或绕过 [WebViewConfig] 的安全策略。
 */
@OptIn(ExperimentalForeignApi::class)
class IosWebViewController(
  /** 跨平台安全配置。 */
  private val config: WebViewConfig = WebViewConfig(),
  /** 业务侧导航决策策略。 */
  navigationPolicy: NavigationPolicy,
  /** 当策略要求外部处理时由宿主执行的操作。 */
  private val onExternalNavigation: (NavigationRequest) -> Unit = {},
  /** 可选的平台能力扩展；事件按列表顺序派发。 */
  private val extensions: List<WebViewExtension> = emptyList(),
) : WebViewController, WebViewStateObservable {
  /**
   * 使用跨平台初始化对象创建 iOS 控制器。
   *
   * 外部导航继续由 UIKit 宿主显式处理，公共初始化对象不会尝试推断应用的 URL Scheme 或路由策略。
   */
  constructor(
    initialization: WebViewInitialization,
    onExternalNavigation: (NavigationRequest) -> Unit = {},
  ) : this(
    config = initialization.webViewConfig,
    navigationPolicy = initialization.navigationPolicy,
    onExternalNavigation = onExternalNavigation,
    extensions = initialization.extensions,
  )

  private val navigationDecider = IosNavigationDecider(config, navigationPolicy)
  private val navigationDelegate = IosNavigationDelegate(this)
  /** JS 桥处理器必须与 WKWebView 同生命周期保存，避免被 Objective-C 运行时提前释放。 */
  private val scriptBridgeInstallation = IosScriptBridgeInstaller.create(
    enabled = config.javaScriptEnabled,
    bridges = extensions.flatMap(WebViewExtension::scriptBridges),
  )
  /** 已在 [load] 中通过策略校验的主框架地址，供首次 delegate 回调直接放行。 */
  private val pendingProgrammaticMainFrameUrls = mutableSetOf<String>()

  /** 供宿主添加到视图层级的原生 WKWebView。 */
  val view: WKWebView

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

  init {
    checkMainThread()
    view = WKWebView(
      frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
      configuration = createConfiguration(),
    )
    scriptBridgeInstallation.attach(view)
    view.navigationDelegate = navigationDelegate
  }

  override fun load(request: WebRequest) {
    ensureUsable()
    val navigationRequest = NavigationRequest(
      url = request.url,
      isMainFrame = true,
      isUserInitiated = false,
    )

    when (navigationDecider.decide(navigationRequest)) {
      NavigationDecision.Allow -> loadAllowedRequest(request)
      NavigationDecision.OpenExternally -> onExternalNavigation(navigationRequest)
      NavigationDecision.Cancel -> Unit
    }
  }

  override fun reload() {
    ensureUsable()
    view.reload()
  }

  override fun goBack() {
    ensureUsable()
    if (view.canGoBack) {
      view.goBack()
    }
  }

  override fun goForward() {
    ensureUsable()
    if (view.canGoForward) {
      view.goForward()
    }
  }

  override fun stopLoading() {
    ensureUsable()
    view.stopLoading()
    state = state.copy(isLoading = false)
  }

  override fun clearSession() {
    ensureUsable()
    view.configuration.websiteDataStore.removeDataOfTypes(
      WKWebsiteDataStore.allWebsiteDataTypes(),
      modifiedSince = NSDate.distantPast,
      completionHandler = {},
    )
    state = state.copy(
      canGoBack = false,
      canGoForward = false,
    )
  }

  override fun dispose() {
    checkMainThread()
    if (isDisposed) {
      return
    }

    view.stopLoading()
    view.navigationDelegate = null
    scriptBridgeInstallation.dispose()
    view.removeFromSuperview()
    state = state.copy(isLoading = false)
    isDisposed = true
  }

  private fun createConfiguration(): WKWebViewConfiguration {
    return WKWebViewConfiguration().apply {
      preferences = WKPreferences().apply {
        javaScriptCanOpenWindowsAutomatically = false
      }
      defaultWebpagePreferences = WKWebpagePreferences().apply {
        allowsContentJavaScript = config.javaScriptEnabled
      }
      websiteDataStore = if (config.persistentSessionEnabled) {
        WKWebsiteDataStore.defaultDataStore()
      } else {
        WKWebsiteDataStore.nonPersistentDataStore()
      }
      userContentController = scriptBridgeInstallation.userContentController
    }
  }

  private fun loadAllowedRequest(request: WebRequest) {
    val url = NSURL(string = request.url)
    val urlRequest = NSMutableURLRequest(uRL = url).apply {
      request.headers.forEach { (name, value) ->
        setValue(value, forHTTPHeaderField = name)
      }
    }
    pendingProgrammaticMainFrameUrls += request.url
    state = state.copy(
      url = request.url,
      isLoading = true,
      loadingProgress = 0f,
      error = null,
    )
    if (request.url.startsWith("file://", ignoreCase = true)) {
      view.loadFileURL(
        URL = url,
        allowingReadAccessToURL = url,
      )
    } else {
      view.loadRequest(urlRequest)
    }
  }

  private fun handleNavigationAction(action: WKNavigationAction): WKNavigationActionPolicy {
    val navigationRequest = NavigationRequest(
      url = action.request.URL?.absoluteString
        ?: return WKNavigationActionPolicy.WKNavigationActionPolicyCancel,
      isMainFrame = action.targetFrame?.mainFrame ?: true,
      isUserInitiated = action.navigationType != WKNavigationTypeOther,
    )
    if (
      navigationRequest.isMainFrame &&
      !navigationRequest.isUserInitiated &&
      pendingProgrammaticMainFrameUrls.remove(navigationRequest.url)
    ) {
      return WKNavigationActionPolicy.WKNavigationActionPolicyAllow
    }
    return when (navigationDecider.decide(navigationRequest)) {
      NavigationDecision.Allow -> WKNavigationActionPolicy.WKNavigationActionPolicyAllow
      NavigationDecision.OpenExternally -> {
        onExternalNavigation(navigationRequest)
        WKNavigationActionPolicy.WKNavigationActionPolicyCancel
      }
      NavigationDecision.Cancel -> WKNavigationActionPolicy.WKNavigationActionPolicyCancel
    }
  }

  private fun handlePageStarted() {
    state = state.copy(
      url = view.URL?.absoluteString ?: state.url,
      isLoading = true,
      loadingProgress = 0f,
      error = null,
    )
    state.url?.let { url ->
      extensions.forEach { extension -> extension.onPageStarted(PageStartedEvent(url)) }
    }
  }

  private fun handlePageFinished() {
    state = state.copy(
      url = view.URL?.absoluteString ?: state.url,
      title = view.title,
      loadingProgress = 1f,
      isLoading = false,
      canGoBack = view.canGoBack,
      canGoForward = view.canGoForward,
    )
    state.url?.let { url ->
      extensions.forEach { extension ->
        extension.onPageFinished(PageFinishedEvent(url, view.title))
      }
    }
  }

  private fun handleNavigationError(error: NSError) {
    val webError = WebError(
      category = WebErrorCategory.Network,
      description = error.localizedDescription,
      failingUrl = state.url,
    )
    state = state.copy(
      isLoading = false,
      error = webError,
    )
    extensions.forEach { extension -> extension.onPageError(PageErrorEvent(webError)) }
  }

  private fun ensureUsable() {
    checkMainThread()
    check(!isDisposed) { "IosWebViewController 已释放，不能继续执行操作。" }
  }

  private fun checkMainThread() {
    check(NSThread.isMainThread) { "IosWebViewController 必须在主线程调用。" }
  }

  private class IosNavigationDelegate(
    private val controller: IosWebViewController,
  ) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
      webView: WKWebView,
      decidePolicyForNavigationAction: WKNavigationAction,
      decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
      decisionHandler(controller.handleNavigationAction(decidePolicyForNavigationAction))
    }

    @ObjCSignatureOverride
    override fun webView(
      webView: WKWebView,
      didStartProvisionalNavigation: WKNavigation?,
    ) {
      controller.handlePageStarted()
    }

    @ObjCSignatureOverride
    override fun webView(
      webView: WKWebView,
      didFinishNavigation: WKNavigation?,
    ) {
      controller.handlePageFinished()
    }

    @ObjCSignatureOverride
    override fun webView(
      webView: WKWebView,
      didFailProvisionalNavigation: WKNavigation?,
      withError: NSError,
    ) {
      controller.handleNavigationError(withError)
    }

    @ObjCSignatureOverride
    override fun webView(
      webView: WKWebView,
      didFailNavigation: WKNavigation?,
      withError: NSError,
    ) {
      controller.handleNavigationError(withError)
    }
  }
}
