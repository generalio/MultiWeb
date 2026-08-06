package io.github.multiweb.ios

import io.github.multiweb.ios.filechooser.MultiWebFileChooserAllowsDirectories
import io.github.multiweb.ios.filechooser.MultiWebFileChooserAllowsMultipleSelection
import io.github.multiweb.ios.filechooser.MultiWebFileChooserDelegateProtocol
import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.JavaScriptExecutor
import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebErrorCategory
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import io.github.multiweb.api.WebViewStateObservable
import io.github.multiweb.extension.PageErrorEvent
import io.github.multiweb.extension.OriginPolicyAwareJavaScriptExecutor
import io.github.multiweb.extension.PageFinishedEvent
import io.github.multiweb.extension.PageStartedEvent
import io.github.multiweb.extension.ScriptBridgeOriginPolicy
import io.github.multiweb.extension.WebFileChooserHandler
import io.github.multiweb.extension.WebFileChooserRequest
import io.github.multiweb.extension.WebFileChooserResult
import io.github.multiweb.extension.WebViewExtension
import io.github.multiweb.extension.WebViewControllerLifecycleExtension
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
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKNavigationTypeOther
import platform.WebKit.WKPreferences
import platform.WebKit.WKContextMenuElementInfo
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

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
 * 不可由 WebKit 渲染的主框架响应会触发下载回调并取消当前导航，具体下载与保存仍由宿主完成。iOS 原生
 * 上下文菜单只提供链接地址，不会为图片长按伪造不可靠的资源 URL。
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
) : WebViewController, WebViewStateObservable, JavaScriptExecutor, OriginPolicyAwareJavaScriptExecutor {
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
  /** 文件选择扩展至多一个；缺失时网页请求必须显式取消。 */
  private val fileChooserHandler = extensions.filterIsInstance<WebFileChooserHandler>().singleOrNull()
  private val navigationDelegate = IosNavigationDelegate(this)
  private val uiDelegate = IosUiDelegate(this)
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

  /** 当前尚未完成的 WebKit 文件选择回调；控制器释放时必须显式取消。 */
  private var activeFileChooserCompletion: ((List<*>?) -> Unit)? = null

  init {
    checkMainThread()
    require(extensions.count { extension -> extension is WebFileChooserHandler } <= 1) {
      "WebViewInitialization.extensions 最多只能配置一个 WebFileChooserHandler。"
    }
    view = WKWebView(
      frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
      configuration = createConfiguration(),
    )
    scriptBridgeInstallation.attach(view)
    view.navigationDelegate = navigationDelegate
    view.UIDelegate = uiDelegate
    extensions.filterIsInstance<WebViewControllerLifecycleExtension>().forEach { extension ->
      extension.onControllerAttached(this)
    }
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

  /**
   * 向当前受信任主文档提交脚本。
   *
   * 不在主线程、控制器已释放或当前页面不符合 [allowedHosts] 时不执行，避免异步扩展绕过桥的来源边界。
   */
  override fun executeJavaScript(script: String, allowedHosts: Set<String>): Boolean {
    return executeJavaScript(script, ScriptBridgeOriginPolicy.ExactHttpsHosts(allowedHosts))
  }

  /** 按桥来源策略向当前主文档提交脚本；不安全模式仍只接受 HTTP/HTTPS 主文档。 */
  override fun executeJavaScript(
    script: String,
    originPolicy: ScriptBridgeOriginPolicy,
  ): Boolean {
    if (!NSThread.isMainThread || isDisposed) {
      return false
    }
    if (!isTrustedJavaScriptUrl(view.URL?.absoluteString, originPolicy)) {
      return false
    }
    view.evaluateJavaScript(script, completionHandler = null)
    return true
  }

  override fun dispose() {
    checkMainThread()
    if (isDisposed) {
      return
    }

    // 先切换状态，保证扩展释放过程中不能再向即将销毁的 WKWebView 提交脚本。
    isDisposed = true
    extensions.filterIsInstance<WebViewControllerLifecycleExtension>().forEach { extension ->
      extension.onControllerDisposed()
    }
    activeFileChooserCompletion?.invoke(null)
    activeFileChooserCompletion = null
    view.stopLoading()
    view.navigationDelegate = null
    view.UIDelegate = null
    scriptBridgeInstallation.dispose()
    view.removeFromSuperview()
    state = state.copy(isLoading = false)
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

  /**
   * 将 WebKit 不能渲染的主框架响应交给扩展处理。
   *
   * 公共扩展只传递下载元数据，没有隐式文件路径或权限模型；因此这里取消 WebKit 的内嵌导航，宿主可在
   * [WebViewExtension.onDownloadRequested] 中自行发起受控下载。
   */
  private fun handleNavigationResponse(response: WKNavigationResponse): WKNavigationResponsePolicy {
    if (!response.forMainFrame || response.canShowMIMEType) {
      return WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
    }

    val webResponse = response.response
    val downloadRequest = IosWebViewExtensionEventMapper.downloadRequest(
      url = webResponse.URL?.absoluteString,
      suggestedFileName = webResponse.suggestedFilename,
      mimeType = webResponse.MIMEType,
      contentLength = webResponse.expectedContentLength,
    ) ?: return WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
    extensions.forEach { extension -> extension.onDownloadRequested(downloadRequest) }
    state = state.copy(isLoading = false)
    return WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel
  }

  /** 将 WebKit 原生菜单公开的链接地址转发给扩展，同时保留系统默认上下文菜单。 */
  private fun handleContextAction(elementInfo: WKContextMenuElementInfo) {
    IosWebViewExtensionEventMapper.contextAction(elementInfo.linkURL?.absoluteString)?.let { action ->
      extensions.forEach { extension -> extension.onContextAction(action) }
    }
  }

  /**
   * 将 WebKit 的文件上传请求交给宿主。
   *
   * `WKUIDelegate` 的文件面板回调仅在 iOS 18.4 及以上系统可用；较低版本不会触发该委托方法，因此网页文件
   * 上传保持系统不支持状态。无论宿主处理器是否存在，都不会触发 UIKit 的默认选取器或隐式申请媒体权限。
   */
  private fun handleFileChooser(
    parameters: Any?,
    completionHandler: (List<*>?) -> Unit,
  ) {
    activeFileChooserCompletion?.invoke(null)
    activeFileChooserCompletion = completionHandler
    val request = WebFileChooserRequest(
      allowMultipleSelection = MultiWebFileChooserAllowsMultipleSelection(parameters),
      allowDirectories = MultiWebFileChooserAllowsDirectories(parameters),
    )
    val handler = fileChooserHandler
    if (handler == null) {
      completeFileChooser(completionHandler, request, WebFileChooserResult.Cancelled)
      return
    }

    fun complete(result: WebFileChooserResult) {
      completeFileChooser(completionHandler, request, result)
    }
    try {
      handler.onFileChooserRequested(request, ::complete)
    } catch (_: Exception) {
      // 宿主处理异常不能让网页获得未校验文件地址，按取消处理。
      complete(WebFileChooserResult.Cancelled)
    }
  }

  /** 在主线程且仅针对仍活跃的请求回传一次文件选择结果。 */
  private fun completeFileChooser(
    completionHandler: (List<*>?) -> Unit,
    request: WebFileChooserRequest,
    result: WebFileChooserResult,
  ) {
    dispatch_async(dispatch_get_main_queue()) {
      if (activeFileChooserCompletion !== completionHandler) {
        return@dispatch_async
      }
      activeFileChooserCompletion = null
      completionHandler(if (isDisposed) null else result.toIosUrls(request))
    }
  }

  /** iOS 仅将绝对 `file://` URI 回传给 WebKit，远程或多余文件都会取消整次请求。 */
  private fun WebFileChooserResult.toIosUrls(request: WebFileChooserRequest): List<NSURL>? {
    val selected = this as? WebFileChooserResult.Selected ?: return null
    if (!request.allowMultipleSelection && selected.uris.size > 1) {
      return null
    }
    val urls = selected.uris.map { uri -> NSURL(string = uri) }
    if (
      urls.any { url ->
        !url.isFileURL() ||
          url.path?.startsWith("/") != true ||
          url.query != null ||
          url.fragment != null
      }
    ) {
      return null
    }
    return urls
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
      decidePolicyForNavigationResponse: WKNavigationResponse,
      decisionHandler: (WKNavigationResponsePolicy) -> Unit,
    ) {
      decisionHandler(controller.handleNavigationResponse(decidePolicyForNavigationResponse))
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

  /** 仅观察 WebKit 系统上下文菜单；不提供自定义配置，以保留系统默认菜单和预览行为。 */
  private class IosUiDelegate(
    private val controller: IosWebViewController,
  ) : NSObject(), WKUIDelegateProtocol, MultiWebFileChooserDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(
      webView: WKWebView,
      contextMenuWillPresentForElement: WKContextMenuElementInfo,
    ) {
      controller.handleContextAction(contextMenuWillPresentForElement)
    }

    /** iOS 18.4+ 的网页文件上传入口；宿主完成回调前 WebKit 会保持当前选择请求。 */
    override fun webView(
      webView: Any?,
      runOpenPanelWithParameters: Any?,
      initiatedByFrame: Any?,
      completionHandler: ((List<*>?) -> Unit)?,
    ) {
      completionHandler ?: return
      controller.handleFileChooser(runOpenPanelWithParameters, completionHandler)
    }
  }
}
