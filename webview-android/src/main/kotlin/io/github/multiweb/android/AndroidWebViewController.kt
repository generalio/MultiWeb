package io.github.multiweb.android

import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
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
import io.github.multiweb.extension.DownloadRequest
import io.github.multiweb.extension.OriginPolicyAwareJavaScriptExecutor
import io.github.multiweb.extension.PageErrorEvent
import io.github.multiweb.extension.PageFinishedEvent
import io.github.multiweb.extension.PageStartedEvent
import io.github.multiweb.extension.ScriptBridgeOriginPolicy
import io.github.multiweb.extension.WebContextAction
import io.github.multiweb.extension.WebFileChooserHandler
import io.github.multiweb.extension.WebFileChooserMode
import io.github.multiweb.extension.WebFileChooserRequest
import io.github.multiweb.extension.WebFileChooserResult
import io.github.multiweb.extension.WebViewExtension
import io.github.multiweb.extension.WebViewControllerLifecycleExtension
import io.github.multiweb.extension.WebViewInitialization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 基于系统 [WebView] 的 Android 控制器。
 *
 * 必须在主线程创建与调用。宿主将 [view] 放入自己的 View 层级，并在宿主暂停、恢复和销毁时调用
 * [onHostPause]、[onHostResume] 和 [dispose]。该类不会自动启动外部 Intent；外部导航交由
 * [onExternalNavigation] 处理。
 *
 * Android 的 Cookie 存储为进程级资源，当前实现不支持 [WebViewConfig.persistentSessionEnabled]
 * 为 `false` 的隔离临时会话，因此会在构造时拒绝该配置，避免产生不符合契约的安全假设。
 *
 * [extensions] 由控制器与内部 Client 组合执行，业务方不能通过它替换导航或安全处理。JS 桥仅通过
 * AndroidX WebKit 的受限来源消息通道按其来源策略暴露，禁止使用 `addJavascriptInterface`。
 */
class AndroidWebViewController(
  context: Context,
  /** 跨平台安全配置。 */
  private val config: WebViewConfig = WebViewConfig(),
  /** 业务侧导航决策策略。 */
  navigationPolicy: NavigationPolicy,
  /** 当策略返回 [NavigationDecision.OpenExternally] 时由宿主执行的操作。 */
  private val onExternalNavigation: (NavigationRequest) -> Unit = {},
  /** 可选的平台能力扩展；事件按列表顺序派发。 */
  private val extensions: List<WebViewExtension> = emptyList(),
  /** 创建原生 WebView 的工厂，可用于注入业务自定义 WebView 子类。 */
  private val webViewFactory: AndroidWebViewFactory = DefaultAndroidWebViewFactory,
) : WebViewController, WebViewStateObservable, JavaScriptExecutor, OriginPolicyAwareJavaScriptExecutor {
  /**
   * 使用跨平台初始化对象创建 Android 控制器。
   *
   * 外部导航仍由 Android 宿主处理，避免公共配置隐式创建 Intent；[webViewFactory] 保留给需要自定义 WebView
   * 子类的调用方。
   */
  constructor(
    context: Context,
    initialization: WebViewInitialization,
    onExternalNavigation: (NavigationRequest) -> Unit = {},
    webViewFactory: AndroidWebViewFactory = DefaultAndroidWebViewFactory,
  ) : this(
    context = context,
    config = initialization.webViewConfig,
    navigationPolicy = initialization.navigationPolicy,
    onExternalNavigation = onExternalNavigation,
    extensions = initialization.extensions,
    webViewFactory = webViewFactory,
  )

  private val navigationDecider = AndroidNavigationDecider(config, navigationPolicy)
  /** 文件选择扩展至多一个；缺失时网页请求必须显式取消。 */
  private val fileChooserHandler = extensions.filterIsInstance<WebFileChooserHandler>().singleOrNull()
  /** 旧网页显示兼容配置；缺失时不写入视口与缩放设置，保持历史安全默认值。 */
  private val compatibilitySettings = extensions
    .singleAndroidWebViewCompatibilityExtension()
    ?.toAndroidWebViewCompatibilitySettings()

  /**
   * 供宿主添加到界面层级的原生 WebView。
   *
   * 宿主不得自行调用 `destroy()`，资源释放统一由 [dispose] 完成。
   */
  val view: WebView

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

  /** 当前尚未完成的原生文件选择回调；同一时间只能保留一个。 */
  private var activeFileChooserCallback: ValueCallback<Array<Uri>>? = null
  /**
   * 当前主框架正在加载或已完成加载的 URL。
   *
   * 文档开始阶段的旧桥可能早于 [WebView.url] 更新调用 `exeJs`，因此不能只依赖后者复核来源。该值只由主框架
   * 生命周期回调更新，并在释放时清空，避免向上一个页面或未知页面提交脚本。
   */
  private var currentMainFrameUrl: String? = null

  init {
    checkMainThread()
    require(config.persistentSessionEnabled) {
      "AndroidWebViewController 暂不支持隔离的临时会话。"
    }
    require(extensions.count { extension -> extension is WebFileChooserHandler } <= 1) {
      "WebViewInitialization.extensions 最多只能配置一个 WebFileChooserHandler。"
    }

    view = webViewFactory.create(context).also(::configureWebView)
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
      NavigationDecision.Allow -> {
        state = state.copy(
          url = request.url,
          isLoading = true,
          loadingProgress = 0f,
          error = null,
        )
        view.loadUrl(request.url, request.headers)
      }
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
    if (view.canGoBack()) {
      view.goBack()
    }
  }

  override fun goForward() {
    ensureUsable()
    if (view.canGoForward()) {
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
    view.clearCache(true)
    view.clearFormData()
    view.clearHistory()
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
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

  /**
   * 按桥来源策略向当前主文档提交脚本。
   *
   * 精确策略仅接受 HTTPS 默认端口与配置主机；不安全兼容策略仅接受带主机名的 HTTP/HTTPS 主文档。虽然 AndroidX
   * WebKit 需要以 `*` 安装不安全模式的内部通道，脚本执行仍只针对当前主文档，且在提交前调用同一来源校验。
   */
  override fun executeJavaScript(
    script: String,
    originPolicy: ScriptBridgeOriginPolicy,
  ): Boolean {
    if (Looper.myLooper() != Looper.getMainLooper() || isDisposed) {
      return false
    }
    val currentUrl = currentMainFrameUrl ?: view.url
    if (!isTrustedJavaScriptUrl(currentUrl, originPolicy)) {
      return false
    }
    view.evaluateJavascript(script, null)
    return true
  }

  /** 通知 WebView 宿主进入暂停状态。 */
  fun onHostPause() {
    ensureUsable()
    view.onPause()
  }

  /** 通知 WebView 宿主恢复运行。 */
  fun onHostResume() {
    ensureUsable()
    view.onResume()
  }

  override fun dispose() {
    checkMainThread()
    if (isDisposed) {
      return
    }

    // 先切换状态，保证扩展释放过程中不能再向即将销毁的 WebView 提交脚本。
    isDisposed = true
    currentMainFrameUrl = null
    extensions.filterIsInstance<WebViewControllerLifecycleExtension>().forEach { extension ->
      extension.onControllerDisposed()
    }
    activeFileChooserCallback?.onReceiveValue(null)
    activeFileChooserCallback = null
    view.stopLoading()
    view.loadUrl("about:blank")
    view.clearHistory()
    view.removeAllViews()
    view.destroy()
    state = state.copy(isLoading = false)
  }

  private fun configureWebView(webView: WebView) {
    // 部分厂商 WebView 在 Compose AndroidView 中使用硬件层时会忽略宿主偏移，覆盖整个窗口。
    // 使用软件层保证其绘制边界始终遵循 Compose 测量结果，避免页面与宿主控件错位。
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    val settings = config.toAndroidWebViewSettings()
    with(webView.settings) {
      javaScriptEnabled = settings.javaScriptEnabled
      javaScriptCanOpenWindowsAutomatically = false
      domStorageEnabled = settings.domStorageEnabled
      allowFileAccess = settings.fileAccessEnabled
      allowContentAccess = false
      mixedContentMode = if (settings.mixedContentAllowed) {
        WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      } else {
        WebSettings.MIXED_CONTENT_NEVER_ALLOW
      }
      setSupportMultipleWindows(false)
      setGeolocationEnabled(false)
      mediaPlaybackRequiresUserGesture = !settings.mediaPlaybackWithoutUserGestureAllowed
      compatibilitySettings?.let { compatibility ->
        mixedContentMode = compatibility.mixedContentMode
        mediaPlaybackRequiresUserGesture = compatibility.mediaPlaybackRequiresUserGesture
        useWideViewPort = compatibility.useWideViewPort
        loadWithOverviewMode = compatibility.loadWithOverviewMode
        setSupportZoom(compatibility.supportZoom)
        builtInZoomControls = compatibility.builtInZoomControls
        displayZoomControls = compatibility.displayZoomControls
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        safeBrowsingEnabled = true
      }
    }

    CookieManager.getInstance().setAcceptThirdPartyCookies(
      webView,
      config.thirdPartyCookiesEnabled,
    )
    webView.webViewClient = createWebViewClient()
    webView.webChromeClient = createWebChromeClient()
    webView.setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
      extensions.forEach { extension ->
        extension.onDownloadRequested(
          DownloadRequest(
            url = url,
            suggestedFileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
            mimeType = mimeType,
            contentLength = contentLength.takeIf { it >= 0L },
          ),
        )
      }
    }
    webView.setOnLongClickListener { clickedView ->
      val hitTestResult = (clickedView as WebView).hitTestResult
      val action = when (hitTestResult.type) {
        WebView.HitTestResult.SRC_ANCHOR_TYPE -> hitTestResult.extra?.let(WebContextAction::LinkLongPressed)
        WebView.HitTestResult.IMAGE_TYPE,
        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
        -> hitTestResult.extra?.let(WebContextAction::ImageLongPressed)
        else -> null
      }
      action?.let { contextAction ->
        extensions.forEach { extension -> extension.onContextAction(contextAction) }
      }
      false
    }
    AndroidScriptBridgeInstaller.install(
      webView = webView,
      javaScriptEnabled = config.javaScriptEnabled,
      bridges = extensions.flatMap(WebViewExtension::scriptBridges),
    )
  }

  private fun createWebViewClient(): WebViewClient {
    return object : WebViewClient() {
      override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
      ): Boolean {
        val navigationRequest = NavigationRequest(
          url = request.url.toString(),
          isMainFrame = request.isForMainFrame,
          isUserInitiated = request.hasGesture(),
        )
        return when (navigationDecider.decide(navigationRequest)) {
          NavigationDecision.Allow -> false
          NavigationDecision.OpenExternally -> {
            onExternalNavigation(navigationRequest)
            true
          }
          NavigationDecision.Cancel -> true
        }
      }

      override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        currentMainFrameUrl = url
        state = state.copy(
          url = url ?: state.url,
          isLoading = true,
          loadingProgress = 0f,
          error = null,
        )
        url?.let { loadedUrl ->
          extensions.forEach { extension -> extension.onPageStarted(PageStartedEvent(loadedUrl)) }
        }
      }

      override fun onPageFinished(view: WebView, url: String?) {
        currentMainFrameUrl = url
        state = state.copy(
          url = url ?: state.url,
          loadingProgress = 1f,
          isLoading = false,
          canGoBack = view.canGoBack(),
          canGoForward = view.canGoForward(),
        )
        url?.let { loadedUrl ->
          extensions.forEach { extension ->
            extension.onPageFinished(PageFinishedEvent(loadedUrl, view.title))
          }
        }
      }

      override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
      ) {
        if (request.isForMainFrame) {
          updateError(
            category = WebErrorCategory.Network,
            description = error.description.toString(),
            failingUrl = request.url.toString(),
          )
        }
      }

      override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
      ) {
        handler.cancel()
        updateError(
          category = WebErrorCategory.Ssl,
          description = error.toString(),
          failingUrl = error.url,
        )
      }

      override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
      ): Boolean {
        updateError(
          category = WebErrorCategory.RenderProcess,
          description = "WebView 渲染进程已退出，宿主应释放并重建控制器。",
          failingUrl = state.url,
        )
        return true
      }
    }
  }

  private fun createWebChromeClient(): WebChromeClient {
    return object : WebChromeClient() {
      override fun onProgressChanged(view: WebView, newProgress: Int) {
        state = state.copy(
          loadingProgress = newProgress.coerceIn(0, 100) / 100f,
          isLoading = newProgress < 100,
        )
      }

      override fun onReceivedTitle(view: WebView, title: String?) {
        state = state.copy(title = title)
      }

      override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
      ): Boolean {
        activeFileChooserCallback?.onReceiveValue(null)
        activeFileChooserCallback = filePathCallback
        val request = WebFileChooserRequest(
          acceptTypes = fileChooserParams.acceptTypes.filter(String::isNotBlank),
          mode = if (fileChooserParams.mode == FileChooserParams.MODE_SAVE) {
            WebFileChooserMode.Save
          } else {
            WebFileChooserMode.Open
          },
          allowMultipleSelection = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
          // Android API 35 及以下未公开目录模式，不能依赖隐藏常量伪造该能力。
          allowDirectories = false,
        )
        val handler = fileChooserHandler
        if (handler == null) {
          completeFileChooser(filePathCallback, request, WebFileChooserResult.Cancelled)
          return true
        }

        try {
          handler.onFileChooserRequested(request) { result ->
            completeFileChooser(filePathCallback, request, result)
          }
        } catch (_: Exception) {
          // 宿主处理异常不能令网页获得未校验的文件访问能力，按取消处理。
          completeFileChooser(filePathCallback, request, WebFileChooserResult.Cancelled)
        }
        return true
      }
    }
  }

  /** 在主线程且仅针对仍活跃的请求回传一次文件选择结果。 */
  private fun completeFileChooser(
    callback: ValueCallback<Array<Uri>>,
    request: WebFileChooserRequest,
    result: WebFileChooserResult,
  ) {
    view.post {
      if (activeFileChooserCallback !== callback) {
        return@post
      }
      activeFileChooserCallback = null
      callback.onReceiveValue(result.toAndroidUris(request))
    }
  }

  /** Android WebView 只接收系统文档提供者返回的 `content://` URI。 */
  private fun WebFileChooserResult.toAndroidUris(request: WebFileChooserRequest): Array<Uri>? {
    val selected = this as? WebFileChooserResult.Selected ?: return null
    if (!request.allowMultipleSelection && selected.uris.size > 1) {
      return null
    }
    val uris = selected.uris.map(Uri::parse)
    if (uris.any { uri -> uri.scheme != "content" || uri.authority.isNullOrBlank() }) {
      return null
    }
    return uris.toTypedArray()
  }

  private fun updateError(
    category: WebErrorCategory,
    description: String,
    failingUrl: String?,
  ) {
    val error = WebError(
      category = category,
      description = description,
      failingUrl = failingUrl,
    )
    state = state.copy(
      isLoading = false,
      error = error,
    )
    extensions.forEach { extension -> extension.onPageError(PageErrorEvent(error)) }
  }

  private fun ensureUsable() {
    checkMainThread()
    check(!isDisposed) { "AndroidWebViewController 已释放，不能继续执行操作。" }
  }

  private fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "AndroidWebViewController 必须在主线程调用。"
    }
  }
}
