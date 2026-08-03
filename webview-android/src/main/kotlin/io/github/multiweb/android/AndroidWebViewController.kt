package io.github.multiweb.android

import android.content.Context
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebErrorCategory
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import io.github.multiweb.extension.DownloadRequest
import io.github.multiweb.extension.PageErrorEvent
import io.github.multiweb.extension.PageFinishedEvent
import io.github.multiweb.extension.PageStartedEvent
import io.github.multiweb.extension.WebContextAction
import io.github.multiweb.extension.WebViewExtension

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
 * AndroidX WebKit 的受限来源消息通道暴露给 [io.github.multiweb.extension.ScriptBridge.allowedHosts]。
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
) : WebViewController {
  private val navigationDecider = AndroidNavigationDecider(config, navigationPolicy)

  /**
   * 供宿主添加到界面层级的原生 WebView。
   *
   * 宿主不得自行调用 `destroy()`，资源释放统一由 [dispose] 完成。
   */
  val view: WebView

  /** 控制器是否已释放。释放后除 [dispose] 外的操作都会抛出 [IllegalStateException]。 */
  var isDisposed: Boolean = false
    private set

  override var state: WebViewState = WebViewState()
    private set

  init {
    checkMainThread()
    require(config.persistentSessionEnabled) {
      "AndroidWebViewController 暂不支持隔离的临时会话。"
    }

    view = webViewFactory.create(context).also(::configureWebView)
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
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    state = state.copy(
      canGoBack = false,
      canGoForward = false,
    )
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

    view.stopLoading()
    view.loadUrl("about:blank")
    view.clearHistory()
    view.removeAllViews()
    view.destroy()
    state = state.copy(isLoading = false)
    isDisposed = true
  }

  private fun configureWebView(webView: WebView) {
    with(webView.settings) {
      javaScriptEnabled = config.javaScriptEnabled
      javaScriptCanOpenWindowsAutomatically = false
      domStorageEnabled = false
      allowFileAccess = config.fileAccessEnabled
      allowContentAccess = false
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      setSupportMultipleWindows(false)
      setGeolocationEnabled(false)
      mediaPlaybackRequiresUserGesture = true
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
    AndroidScriptBridgeInstaller.install(webView, extensions.flatMap(WebViewExtension::scriptBridges))
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
    }
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
