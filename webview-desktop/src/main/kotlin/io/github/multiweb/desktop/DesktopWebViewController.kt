package io.github.multiweb.desktop

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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Vector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Component
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCompletionCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefFileDialogCallback
import org.cef.handler.CefDialogHandler
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.network.CefCookieManager
import org.cef.network.CefRequest

/**
 * 基于 JCEF 的桌面 WebView 控制器。
 *
 * 必须在 Swing EDT 中创建和调用；宿主通过 [view] 将原生组件加入界面，并在不再需要时调用
 * [dispose]。构造器接收由宿主初始化的进程级 [CefApp]，控制器不会调用 `CefApp.dispose()`。
 *
 * JCEF 不支持按单个浏览器可靠地禁用 JavaScript、第三方 Cookie 或持久化会话。为避免安全配置被
 * 静默忽略，三个选项均要求调用方明确开启；需要更严格策略时，应在初始化 [CefApp] 前配置运行时。
 */
class DesktopWebViewController(
  /** 宿主初始化并持有的进程级 JCEF 应用实例。 */
  cefApp: CefApp,
  /** 跨平台安全配置。 */
  private val config: WebViewConfig,
  /** 业务侧导航决策策略。 */
  navigationPolicy: NavigationPolicy,
  /** 当策略要求外部处理时由宿主执行的操作。 */
  private val onExternalNavigation: (NavigationRequest) -> Unit = {},
  /** 浏览器原生关闭且 JCEF 客户端已释放后通知宿主，可用于安全销毁进程级 [CefApp]。 */
  private val onBrowserClosed: () -> Unit = {},
  /** 可选的平台能力扩展；事件按列表顺序派发。 */
  private val extensions: List<WebViewExtension> = emptyList(),
) : WebViewController, WebViewStateObservable, JavaScriptExecutor, OriginPolicyAwareJavaScriptExecutor {
  /**
   * 使用跨平台初始化对象创建桌面控制器。
   *
   * JCEF 应用实例、外部导航和原生浏览器关闭时机仍由桌面宿主控制；这些进程级资源不能收进公共配置。
   */
  constructor(
    cefApp: CefApp,
    initialization: WebViewInitialization,
    onExternalNavigation: (NavigationRequest) -> Unit = {},
    onBrowserClosed: () -> Unit = {},
  ) : this(
    cefApp = cefApp,
    config = initialization.webViewConfig,
    navigationPolicy = initialization.navigationPolicy,
    onExternalNavigation = onExternalNavigation,
    onBrowserClosed = onBrowserClosed,
    extensions = initialization.extensions,
  )

  private val navigationDecider = DesktopNavigationDecider(config, navigationPolicy)
  /** 文件选择扩展至多一个；缺失时网页请求必须显式取消。 */
  private val fileChooserHandler = extensions.filterIsInstance<WebFileChooserHandler>().singleOrNull()
  /** 已在 [load] 中通过策略校验的主框架地址，供首次 JCEF 回调直接放行。 */
  private val pendingProgrammaticMainFrameUrls = ConcurrentHashMap.newKeySet<String>()
  /** 待写入下一次主框架请求的自定义请求头；不能通过 [CefBrowser.loadRequest] 设置，避免 Chromium 拒绝无效发起方。 */
  private val pendingProgrammaticMainFrameHeaders = ConcurrentHashMap<String, Map<String, String>>()
  /** 尚未由宿主完成的 JCEF 文件选择请求；控制器销毁时必须主动取消。 */
  private val activeFileChoosers = ConcurrentHashMap.newKeySet<DesktopFileChooserCallbackGuard>()
  /** JCEF 原生浏览器创建前收到的最新请求；创建完成后必须回放，避免用户首击丢失加载操作。 */
  private var pendingInitialRequest: WebRequest? = null
  private val client: CefClient
  private val browser: CefBrowser
  /** 与当前 CefClient 同生命周期的 JS 桥路由安装。 */
  private val scriptBridgeInstallation: DesktopScriptBridgeInstallation

  /** 原生浏览器是否已完成创建；JCEF 的请求接口在此之前不能可靠执行。 */
  @Volatile
  private var isBrowserReady: Boolean = false

  /** 供宿主添加到 Swing/AWT 视图层级的 JCEF 原生组件。 */
  val view: Component

  /** 控制器是否已释放。释放后除 [dispose] 外的操作都会抛出 [IllegalStateException]。 */
  @Volatile
  var isDisposed: Boolean = false
    private set

  /** JCEF 客户端只能在 [CefLifeSpanHandlerAdapter.onBeforeClose] 后释放，防止原生浏览器仍在关闭时访问已释放对象。 */
  @Volatile
  private var isClientDisposed: Boolean = false

  /** JCEF 回调可能来自非 EDT 线程，使用 StateFlow 安全发布状态快照给宿主。 */
  private val mutableState = MutableStateFlow(WebViewState())

  override var state: WebViewState
    get() = mutableState.value
    private set(value) {
      mutableState.value = value
    }

  override val stateFlow: StateFlow<WebViewState> = mutableState.asStateFlow()

  init {
    checkEdt()
    require(config.javaScriptEnabled) {
      "JCEF 无法按单个 WebView 可靠禁用 JavaScript，必须显式开启后才能创建桌面控制器。"
    }
    require(config.thirdPartyCookiesEnabled) {
      "JCEF 无法按单个 WebView 可靠禁用第三方 Cookie，必须显式开启后才能创建桌面控制器。"
    }
    require(config.persistentSessionEnabled) {
      "JCEF 会话持久化由进程级 CefApp 配置管理，桌面控制器不支持隔离临时会话。"
    }
    require(extensions.count { extension -> extension is WebFileChooserHandler } <= 1) {
      "WebViewInitialization.extensions 最多只能配置一个 WebFileChooserHandler。"
    }

    client = cefApp.createClient().also(::configureClient)
    scriptBridgeInstallation = DesktopScriptBridgeInstallation.install(
      client = client,
      enabled = config.javaScriptEnabled,
      bridges = extensions.flatMap(WebViewExtension::scriptBridges),
    )
    browser = client.createBrowser("about:blank", false, false)
    view = browser.uiComponent
    // Compose 的 SwingPanel 不保证触发 JCEF 所需的首次 Swing 绘制；主动创建可避免原生视图保持空白。
    browser.createImmediately()
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
    browser.reload()
  }

  override fun goBack() {
    ensureUsable()
    if (browser.canGoBack()) {
      browser.goBack()
    }
  }

  override fun goForward() {
    ensureUsable()
    if (browser.canGoForward()) {
      browser.goForward()
    }
  }

  override fun stopLoading() {
    ensureUsable()
    browser.stopLoad()
    state = state.copy(isLoading = false)
  }

  override fun clearSession() {
    ensureUsable()
    CefCookieManager.getGlobalManager().deleteCookies(null, null)
    CefCookieManager.getGlobalManager().flushStore(CefCompletionCallback {})
    state = state.copy(
      canGoBack = false,
      canGoForward = false,
    )
  }

  /**
   * 向当前受信任主文档提交脚本。
   *
   * JCEF 只能在 EDT 且浏览器创建完成后安全执行脚本；来源不可信、控制器已释放或页面尚未就绪时直接拒绝。
   */
  override fun executeJavaScript(script: String, allowedHosts: Set<String>): Boolean {
    return executeJavaScript(script, ScriptBridgeOriginPolicy.ExactHttpsHosts(allowedHosts))
  }

  /** 按桥来源策略向当前主文档提交脚本；不安全模式仍只接受 HTTP/HTTPS 主文档。 */
  override fun executeJavaScript(
    script: String,
    originPolicy: ScriptBridgeOriginPolicy,
  ): Boolean {
    if (!SwingUtilities.isEventDispatchThread() || isDisposed || !isBrowserReady) {
      return false
    }
    val mainFrame = browser.mainFrame ?: return false
    if (!isTrustedJavaScriptUrl(mainFrame.url, originPolicy)) {
      return false
    }
    mainFrame.executeJavaScript(script, "multiweb://executor", 0)
    return true
  }

  override fun dispose() {
    checkEdt()
    if (isDisposed) {
      return
    }

    isDisposed = true
    extensions.filterIsInstance<WebViewControllerLifecycleExtension>().forEach { extension ->
      extension.onControllerDisposed()
    }
    pendingInitialRequest = null
    pendingProgrammaticMainFrameUrls.clear()
    pendingProgrammaticMainFrameHeaders.clear()
    activeFileChoosers.forEach(DesktopFileChooserCallbackGuard::cancel)
    activeFileChoosers.clear()
    scriptBridgeInstallation.dispose()
    browser.stopLoad()
    browser.close(true)
    state = state.copy(isLoading = false)
  }

  private fun configureClient(client: CefClient) {
    client.addRequestHandler(createRequestHandler())
    client.addLoadHandler(createLoadHandler())
    client.addDisplayHandler(createDisplayHandler())
    client.addDialogHandler(createDialogHandler())
    if (extensions.isNotEmpty()) {
      client.addDownloadHandler(createDownloadHandler())
      client.addContextMenuHandler(createContextMenuHandler())
    }
    client.addLifeSpanHandler(createLifeSpanHandler())
  }

  private fun loadAllowedRequest(request: WebRequest) {
    state = state.copy(
      url = request.url,
      isLoading = true,
      loadingProgress = 0f,
      error = null,
    )
    if (!isBrowserReady) {
      pendingInitialRequest = request
      return
    }
    loadBrowserRequest(request)
  }

  /** 将已经通过导航策略的请求交给就绪的 JCEF 原生浏览器。 */
  private fun loadBrowserRequest(request: WebRequest) {
    pendingProgrammaticMainFrameUrls += request.url
    if (request.headers.isNotEmpty()) {
      pendingProgrammaticMainFrameHeaders[request.url] = LinkedHashMap(request.headers)
    }
    // Chromium 146 会拒绝 JCEF loadRequest() 创建的顶级导航（INVALID_INITIATOR_ORIGIN，错误码 213）。
    // 使用 loadURL() 保留浏览器生成的导航上下文；自定义请求头在资源请求回调中补入。
    browser.loadURL(request.url)
  }

  /** 在 JCEF 完成原生浏览器创建后回放创建期间暂存的首个加载请求。 */
  private fun createLifeSpanHandler(): CefLifeSpanHandlerAdapter {
    return object : CefLifeSpanHandlerAdapter() {
      override fun onAfterCreated(createdBrowser: CefBrowser) {
        SwingUtilities.invokeLater {
          if (createdBrowser !== browser) {
            return@invokeLater
          }
          if (isDisposed) {
            createdBrowser.close(true)
            return@invokeLater
          }
          isBrowserReady = true
          pendingInitialRequest?.let { request ->
            pendingInitialRequest = null
            loadBrowserRequest(request)
          }
        }
      }

      override fun onBeforePopup(
        browser: CefBrowser,
        frame: CefFrame,
        targetUrl: String,
        targetFrameName: String,
      ): Boolean {
        val navigationRequest = NavigationRequest(
          url = targetUrl,
          // 弹窗被当前浏览器接管后应视为主框架导航。
          isMainFrame = true,
          isUserInitiated = true,
        )
        return when (navigationDecider.decide(navigationRequest)) {
          NavigationDecision.Allow -> {
            loadUrlInCurrentBrowser(targetUrl)
            true
          }
          NavigationDecision.OpenExternally -> {
            onExternalNavigation(navigationRequest)
            true
          }
          NavigationDecision.Cancel -> true
        }
      }

      override fun onBeforeClose(closedBrowser: CefBrowser) {
        if (closedBrowser !== browser || isClientDisposed) {
          return
        }
        isClientDisposed = true
        client.dispose()
        SwingUtilities.invokeLater {
          onBrowserClosed()
        }
      }
    }
  }

  private fun createRequestHandler(): CefRequestHandlerAdapter {
    return object : CefRequestHandlerAdapter() {
      override fun onBeforeBrowse(
        browser: CefBrowser,
        frame: CefFrame,
        request: CefRequest,
        userGesture: Boolean,
        isRedirect: Boolean,
      ): Boolean {
        if (request.url == "about:blank") {
          return false
        }

        val navigationRequest = NavigationRequest(
          url = request.url,
          isMainFrame = frame.isMain,
          isUserInitiated = userGesture,
        )
        if (
          navigationRequest.isMainFrame &&
          !navigationRequest.isUserInitiated &&
          pendingProgrammaticMainFrameUrls.remove(navigationRequest.url)
        ) {
          return false
        }

        return when (navigationDecider.decide(navigationRequest)) {
          NavigationDecision.Allow -> false
          NavigationDecision.OpenExternally -> {
            onExternalNavigation(navigationRequest)
            true
          }
          NavigationDecision.Cancel -> true
        }
      }

      override fun onOpenURLFromTab(
        browser: CefBrowser,
        frame: CefFrame,
        targetUrl: String,
        userGesture: Boolean,
      ): Boolean {
        val navigationRequest = NavigationRequest(
          url = targetUrl,
          // 新标签页一旦转由当前浏览器承载，目标请求始终成为主框架导航。
          isMainFrame = true,
          isUserInitiated = userGesture,
        )
        return when (navigationDecider.decide(navigationRequest)) {
          NavigationDecision.Allow -> {
            loadUrlInCurrentBrowser(targetUrl)
            true
          }
          NavigationDecision.OpenExternally -> {
            onExternalNavigation(navigationRequest)
            true
          }
          NavigationDecision.Cancel -> true
        }
      }

      override fun getResourceRequestHandler(
        browser: CefBrowser,
        frame: CefFrame,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String,
        disableDefaultHandling: org.cef.misc.BoolRef,
      ): CefResourceRequestHandler? {
        if (!frame.isMain || !isNavigation) {
          return null
        }
        val headers = pendingProgrammaticMainFrameHeaders.remove(request.url) ?: return null
        return createMainFrameHeaderHandler(headers)
      }

      override fun onRenderProcessTerminated(
        browser: CefBrowser,
        status: CefRequestHandler.TerminationStatus,
        errorCode: Int,
        errorString: String,
      ) {
        updateError(
          category = WebErrorCategory.RenderProcess,
          description = "JCEF 渲染进程已退出：$status（$errorCode，$errorString）。宿主应释放并重建控制器。",
          failingUrl = state.url,
        )
      }
    }
  }

  /** 将链接或脚本弹窗改为当前 WebView 的主框架导航，禁止 JCEF 创建脱离宿主的新原生窗口。 */
  private fun loadUrlInCurrentBrowser(url: String) {
    SwingUtilities.invokeLater {
      if (!isDisposed) {
        loadAllowedRequest(WebRequest(url))
      }
    }
  }

  /** 仅为当前主框架首个请求补充调用方提供的请求头，避免影响页面内的子资源与后续导航。 */
  private fun createMainFrameHeaderHandler(headers: Map<String, String>): CefResourceRequestHandler {
    return object : CefResourceRequestHandlerAdapter() {
      override fun onBeforeResourceLoad(
        browser: CefBrowser,
        frame: CefFrame,
        request: CefRequest,
      ): Boolean {
        val requestHeaders = LinkedHashMap<String, String>()
        request.getHeaderMap(requestHeaders)
        requestHeaders.putAll(headers)
        request.setHeaderMap(requestHeaders)
        return false
      }
    }
  }

  private fun createLoadHandler(): CefLoadHandlerAdapter {
    return object : CefLoadHandlerAdapter() {
      override fun onLoadingStateChange(
        browser: CefBrowser,
        isLoading: Boolean,
        canGoBack: Boolean,
        canGoForward: Boolean,
      ) {
        state = state.copy(
          isLoading = isLoading,
          loadingProgress = if (isLoading) state.loadingProgress else 1f,
          canGoBack = canGoBack,
          canGoForward = canGoForward,
        )
      }

      override fun onLoadStart(
        browser: CefBrowser,
        frame: CefFrame,
        transitionType: CefRequest.TransitionType,
      ) {
        if (frame.isMain) {
          state = state.copy(
            url = frame.url,
            isLoading = true,
            loadingProgress = 0f,
            error = null,
          )
          extensions.forEach { extension -> extension.onPageStarted(PageStartedEvent(frame.url)) }
          scriptBridgeInstallation.inject(frame)
        }
      }

      override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
        if (frame.isMain) {
          state = state.copy(
            url = frame.url,
            isLoading = false,
            loadingProgress = 1f,
            canGoBack = browser.canGoBack(),
            canGoForward = browser.canGoForward(),
          )
          extensions.forEach { extension ->
            extension.onPageFinished(PageFinishedEvent(frame.url, state.title))
          }
        }
      }

      override fun onLoadError(
        browser: CefBrowser,
        frame: CefFrame,
        errorCode: CefLoadHandler.ErrorCode,
        errorText: String,
        failedUrl: String,
      ) {
        if (frame.isMain) {
          updateError(
            category = errorCode.toWebErrorCategory(),
            description = errorText,
            failingUrl = failedUrl,
          )
        }
      }
    }
  }

  private fun createDisplayHandler(): CefDisplayHandlerAdapter {
    return object : CefDisplayHandlerAdapter() {
      override fun onTitleChange(browser: CefBrowser, title: String) {
        state = state.copy(title = title)
      }
    }
  }

  /**
   * 接管 JCEF 文件对话框并交给宿主，避免 Chromium 默认对话框绕过公共权限边界。
   *
   * JCEF 允许异步调用 [CefFileDialogCallback]，因此宿主可以在自己的 UI 线程显示选择器；回调以一次性语义
   * 保护，重复或无效结果都会被取消。
   */
  private fun createDialogHandler(): CefDialogHandler {
    return object : CefDialogHandler {
      override fun onFileDialog(
        browser: CefBrowser,
        mode: CefDialogHandler.FileDialogMode,
        title: String,
        defaultFilePath: String,
        acceptFilters: Vector<String>,
        acceptExtensions: Vector<String>,
        acceptDescriptions: Vector<String>,
        callback: CefFileDialogCallback,
      ): Boolean {
        val request = WebFileChooserRequest(
          acceptTypes = acceptFilters.filter(String::isNotBlank),
          mode = if (mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_SAVE) {
            WebFileChooserMode.Save
          } else {
            WebFileChooserMode.Open
          },
          allowMultipleSelection = mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE,
          allowDirectories = mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_FOLDER,
        )
        val handler = fileChooserHandler
        if (handler == null) {
          callback.Cancel()
          return true
        }

        val callbackGuard = DesktopFileChooserCallbackGuard(
          onCancelled = callback::Cancel,
          onSelected = { selectedPaths -> callback.Continue(Vector(selectedPaths)) },
        )
        activeFileChoosers += callbackGuard
        fun complete(result: WebFileChooserResult) {
          // 在移出活动集合前完成回调，让销毁过程可与宿主迟到的异步结果安全竞争。
          callbackGuard.complete(
            selectedPaths = if (isDisposed) null else result.toDesktopPaths(request),
          )
          activeFileChoosers -= callbackGuard
        }

        try {
          handler.onFileChooserRequested(request, ::complete)
        } catch (_: Exception) {
          // 宿主处理异常不能让 Chromium 回退到默认文件对话框，按取消处理。
          complete(WebFileChooserResult.Cancelled)
        }
        return true
      }
    }
  }

  /** Desktop 只将宿主显式返回的绝对 `file://` URI 转为 JCEF 需要的本地路径。 */
  private fun WebFileChooserResult.toDesktopPaths(request: WebFileChooserRequest): List<String>? {
    val selected = this as? WebFileChooserResult.Selected ?: return null
    if (!request.allowMultipleSelection && selected.uris.size > 1) {
      return null
    }
    val paths = selected.uris.map { uri -> uri.toDesktopPath() }
    if (paths.any { path -> path == null }) {
      return null
    }
    val resolvedPaths = paths.filterNotNull()
    if (resolvedPaths.any { path -> !path.matchesRequest(request) }) {
      return null
    }
    return resolvedPaths.map(Path::toString)
  }

  /** 拒绝远程 URI、相对路径和包含查询参数的地址，避免网页取得非宿主选择的资源。 */
  private fun String.toDesktopPath(): Path? {
    return runCatching {
      val uri = URI(this)
      require(uri.scheme == "file" && uri.query == null && uri.fragment == null)
      Paths.get(uri).takeIf { path -> path.isAbsolute }
    }.getOrNull()
  }

  /** 打开模式必须匹配已存在文件或目录；保存模式只接受存在目录下的目标。 */
  private fun Path.matchesRequest(request: WebFileChooserRequest): Boolean {
    return when (request.mode) {
      WebFileChooserMode.Open -> if (request.allowDirectories) {
        Files.isDirectory(this)
      } else {
        Files.isRegularFile(this)
      }
      WebFileChooserMode.Save -> parent?.let(Files::isDirectory) == true
    }
  }

  /** 将 JCEF 下载事件转换为跨平台扩展事件，并交由 JCEF 使用默认路径继续下载。 */
  private fun createDownloadHandler(): CefDownloadHandlerAdapter {
    return object : CefDownloadHandlerAdapter() {
      override fun onBeforeDownload(
        browser: CefBrowser,
        downloadItem: org.cef.callback.CefDownloadItem,
        suggestedName: String,
        callback: org.cef.callback.CefBeforeDownloadCallback,
      ): Boolean {
        extensions.forEach { extension ->
          extension.onDownloadRequested(
            DownloadRequest(
              url = downloadItem.url,
              suggestedFileName = suggestedName,
              mimeType = downloadItem.mimeType,
              contentLength = downloadItem.totalBytes.takeIf { it >= 0L },
            ),
          )
        }
        callback.Continue("", false)
        return true
      }
    }
  }

  /** 将链接和图片上下文菜单转换为扩展事件，同时保留 JCEF 默认菜单。 */
  private fun createContextMenuHandler(): CefContextMenuHandlerAdapter {
    return object : CefContextMenuHandlerAdapter() {
      override fun onBeforeContextMenu(
        browser: CefBrowser,
        frame: CefFrame,
        params: CefContextMenuParams,
        model: org.cef.callback.CefMenuModel,
      ) {
        val typeFlags = params.typeFlags
        if (
          typeFlags and CefContextMenuParams.TypeFlags.CM_TYPEFLAG_LINK != 0 &&
          params.mediaType != CefContextMenuParams.MediaType.CM_MEDIATYPE_IMAGE
        ) {
          params.linkUrl.takeIf(String::isNotBlank)?.let { url ->
            extensions.forEach { extension -> extension.onContextAction(WebContextAction.LinkLongPressed(url)) }
          }
        }
        if (
          params.mediaType == CefContextMenuParams.MediaType.CM_MEDIATYPE_IMAGE &&
          params.sourceUrl.isNotBlank()
        ) {
          extensions.forEach { extension ->
            extension.onContextAction(WebContextAction.ImageLongPressed(params.sourceUrl))
          }
        }
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

  private fun CefLoadHandler.ErrorCode.toWebErrorCategory(): WebErrorCategory {
    return when {
      name.startsWith("ERR_SSL") || name.startsWith("ERR_CERT") -> WebErrorCategory.Ssl
      else -> WebErrorCategory.Network
    }
  }

  private fun ensureUsable() {
    checkEdt()
    check(!isDisposed) { "DesktopWebViewController 已释放，不能继续执行操作。" }
  }

  private fun checkEdt() {
    check(SwingUtilities.isEventDispatchThread()) {
      "DesktopWebViewController 必须在 Swing EDT 中调用。"
    }
  }
}
