package io.github.multiweb.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import io.github.multiweb.api.WebViewController
import io.github.multiweb.desktop.DesktopWebViewController
import io.github.multiweb.extension.WebViewInitialization
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.Timer
import javax.swing.SwingUtilities
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp

/**
 * Desktop WebView 的进程级 JCEF 运行时。
 *
 * 宿主必须在首次调用 [rememberWebViewController] 前使用 [initialize] 注入已创建的 [CefApp]。
 * 调用 [requestApplicationExit] 时，该对象会等待所有已注册浏览器完成正常关闭，再销毁进程级 `CefApp`，
 * 确认 CEF 终止后才调用宿主通过 [bindApplicationExit] 绑定的应用退出回调。
 */
object DesktopWebViewRuntime {
  private var configuration: DesktopWebViewRuntimeConfiguration? = null

  /**
   * 在启动 Compose Desktop 应用前准备 macOS 的 Swing 互操作配置。
   *
   * windowed JCEF 依赖 Compose 的原生 Swing 混合层；使用 macOS 时，应在 `application {}` 或其他 Compose 应用入口
   * 之前调用。宿主已显式设置 `compose.interop.blending` 时保持其选择不变；非 macOS 不执行任何操作。该方法可重复调用。
   */
  fun prepareComposeInterop() {
    prepareMacosComposeInterop(
      operatingSystemName = System.getProperty("os.name"),
      currentBlendingValue = System.getProperty("compose.interop.blending"),
      setBlendingValue = { value -> System.setProperty("compose.interop.blending", value) },
    )
  }

  /**
   * 创建 macOS Cmd+Q 的 JCEF 终止处理器。
   *
   * 宿主必须在 `CefAppBuilder.build()` 前将返回值传给 `setAppHandler()`。macOS 会拦截默认 AppKit 终止流程，
   * 改由 [requestApplicationExit] 协调关闭浏览器、CEF 与 Compose；非 macOS 保持 JCEF 的默认终止行为。
   */
  fun createMacOsTerminationHandler(): MavenCefAppHandlerAdapter {
    return createMacOsTerminationHandler(
      operatingSystemName = System.getProperty("os.name"),
      scheduleOnEdt = { action -> SwingUtilities.invokeLater { action() } },
      requestApplicationExit = ::requestApplicationExit,
    )
  }

  /**
   * 注入当前进程唯一的 JCEF 应用实例。
   *
   * JCEF 不支持在同一进程中重复初始化；因此本方法只允许调用一次。应在 Desktop App 的启动代码中调用，
   * 而不是在可重组的 Composable 函数中调用。
   */
  fun initialize(
    cefApp: CefApp,
    onBrowserClosed: () -> Unit = {},
  ) {
    synchronized(this) {
      // 兼容旧接入方：即使未在 application 前准备，仍尽力提供正确的互操作默认值。
      prepareComposeInterop()
      check(configuration == null) {
        "DesktopWebViewRuntime 已初始化；同一进程只能注入一个 CefApp。"
      }
      configuration = DesktopWebViewRuntimeConfiguration(cefApp, onBrowserClosed)
    }
  }

  /**
   * 绑定 CEF 已完全终止后的宿主应用退出操作。
   *
   * 必须在窗口关闭或 Cmd+Q 前调用一次。回调只会在 `CefApp.getState() == TERMINATED` 后于 Swing EDT 执行，
   * 因此可以安全调用 Compose 的 `exitApplication`。
   */
  fun bindApplicationExit(onApplicationExit: () -> Unit) {
    runOnSwingEdt {
      requireConfiguration().bindApplicationExit(onApplicationExit)
    }
  }

  /**
   * 请求应用按受控顺序退出。
   *
   * 该方法可用于 `Window.onCloseRequest`，也由 macOS 的 [createMacOsTerminationHandler] 调用。重复请求会被忽略；
   * 浏览器始终通过既有的非强制 `close(false)` 路径关闭。
   */
  fun requestApplicationExit() {
    runOnSwingEdt {
      currentConfigurationOrNull()?.requestApplicationExit()
    }
  }

  internal fun requireConfiguration(): DesktopWebViewRuntimeConfiguration {
    return synchronized(this) {
      requireNotNull(configuration) {
        "Desktop WebView 使用前必须先调用 DesktopWebViewRuntime.initialize(cefApp)。"
      }
    }
  }

  private fun currentConfigurationOrNull(): DesktopWebViewRuntimeConfiguration? {
    return synchronized(this) { configuration }
  }

  private fun runOnSwingEdt(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
      action()
    } else {
      SwingUtilities.invokeLater { action() }
    }
  }
}

/**
 * macOS 的 JCEF windowed 浏览器属于原生窗口层；Compose 默认绘制层可能遮蔽它。
 *
 * 参数化后可在不修改 JVM 全局属性的单元测试中验证“仅 macOS、且不覆盖宿主设置”的约束。
 */
internal fun prepareMacosComposeInterop(
  operatingSystemName: String,
  currentBlendingValue: String?,
  setBlendingValue: (String) -> Unit,
) {
  if (operatingSystemName.startsWith("Mac") && currentBlendingValue == null) {
    setBlendingValue("true")
  }
}

/**
 * 处理 macOS Cmd+Q 的 JCEF 终止回调。
 *
 * JCEF 的默认实现在返回 `false` 后会直接走原生终止流程。windowed 模式下这可能与 Swing/Compose 正在关闭
 * 浏览器的过程竞争，因此 macOS 需要先转交到 [DesktopWebViewRuntime.requestApplicationExit]。非 macOS 保持
 * JCEF 原有行为，避免改变其他桌面系统的终止语义。
 */
internal fun createMacOsTerminationHandler(
  operatingSystemName: String,
  scheduleOnEdt: ((() -> Unit) -> Unit),
  requestApplicationExit: () -> Unit,
): MavenCefAppHandlerAdapter {
  return object : MavenCefAppHandlerAdapter() {
    override fun onBeforeTerminate(): Boolean {
      if (!operatingSystemName.startsWith("Mac")) {
        return false
      }
      scheduleOnEdt(requestApplicationExit)
      return true
    }
  }
}

/** 供退出协调器读取和销毁 JCEF 进程级资源的最小适配层，便于不启动真实 CEF 的单元测试。 */
internal interface DesktopCefApplication {
  /** 当前 JCEF 生命周期状态。 */
  val state: CefApp.CefAppState

  /** 请求 JCEF 开始进程级销毁。调用方必须先确认所有浏览器均已正常关闭。 */
  fun dispose()
}

/** CEF 终止前的低频状态检查器。 */
internal interface DesktopTerminationPoller {
  /** 开始检查。 */
  fun start()

  /** 停止检查并释放 Swing Timer。 */
  fun stop()
}

/** 将真实 [CefApp] 适配为退出协调器所需的最小接口。 */
private class JcefDesktopCefApplication(
  private val cefApp: CefApp,
) : DesktopCefApplication {
  override val state: CefApp.CefAppState
    get() = CefApp.getState()

  override fun dispose() {
    cefApp.dispose()
  }
}

/** 使用 Swing EDT 上的低频 Timer 等待 JCEF 终止，避免阻塞 Compose 或 CEF 回调线程。 */
private class SwingDesktopTerminationPoller(
  onTick: () -> Unit,
) : DesktopTerminationPoller {
  private val timer = Timer(100) { onTick() }.apply {
    isRepeats = true
  }

  override fun start() {
    timer.start()
  }

  override fun stop() {
    timer.stop()
  }
}

/** 返回按对象身份比较的集合，避免控制器意外实现相等性后混淆不同原生浏览器。 */
private fun <Element> identitySet(): MutableSet<Element> {
  return Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
}

/**
 * 协调 Compose 控制器、CEF 与宿主应用的受控退出顺序。
 *
 * 所有方法必须在 Swing EDT 调用。每个控制器仍通过自身的 `close(false)` 正常关闭；只有全部原生
 * `onBrowserClosed` 回调到达后才释放进程级 CEF，最后等待 [CefApp.CefAppState.TERMINATED] 再交还给宿主退出。
 */
internal class DesktopApplicationExitCoordinator<Controller>(
  private val cefApplication: DesktopCefApplication,
  private val disposeController: (Controller) -> Unit,
  private val createTerminationPoller: ((() -> Unit) -> DesktopTerminationPoller),
) {
  private val activeControllers = identitySet<Controller>()
  private val closingControllers = identitySet<Controller>()
  /** 原生先关闭、Compose 随后触发 onDispose 时防止重新加入等待集合。 */
  private val nativeClosedControllers = identitySet<Controller>()
  private var onApplicationExit: (() -> Unit)? = null
  private var terminationPoller: DesktopTerminationPoller? = null
  private var isApplicationExitRequested = false
  private var isCefShutdownStarted = false
  private var isApplicationExitDelivered = false

  /** 注册由 Compose 创建的控制器；退出已经开始时立即要求其走正常关闭路径。 */
  fun register(controller: Controller) {
    nativeClosedControllers.remove(controller)
    if (!isApplicationExitRequested) {
      activeControllers += controller
      return
    }
    closingControllers += controller
    disposeController(controller)
  }

  /**
   * 移除 Compose 对控制器的持有。
   *
   * 原生浏览器关闭是异步的，不能因为 Composable 已离开就将其从 [closingControllers] 删除；否则应用退出
   * 可能在 JCEF Client 尚未释放时提前执行 `CefApp.dispose()`。
   */
  fun unregister(controller: Controller) {
    activeControllers.remove(controller)
    if (!nativeClosedControllers.remove(controller)) {
      closingControllers += controller
    }
    startCefShutdownIfReady()
  }

  /** 接收特定控制器的原生关闭确认，绝不按集合顺序猜测对应对象。 */
  fun onControllerClosed(controller: Controller) {
    activeControllers.remove(controller)
    closingControllers.remove(controller)
    nativeClosedControllers += controller
    startCefShutdownIfReady()
  }

  /** 绑定唯一的宿主退出回调；CEF 确认终止前不会调用。 */
  fun bindApplicationExit(onApplicationExit: () -> Unit) {
    check(this.onApplicationExit == null) {
      "DesktopWebViewRuntime.bindApplicationExit 只能调用一次。"
    }
    this.onApplicationExit = onApplicationExit
  }

  /** 请求关闭当前所有浏览器；重复请求保持幂等。 */
  fun requestApplicationExit() {
    if (isApplicationExitRequested) {
      return
    }
    check(onApplicationExit != null) {
      "调用 DesktopWebViewRuntime.requestApplicationExit 前必须先绑定 bindApplicationExit。"
    }
    isApplicationExitRequested = true
    val controllersToClose = activeControllers.toList()
    activeControllers.clear()
    closingControllers += controllersToClose
    controllersToClose.forEach(disposeController)
    startCefShutdownIfReady()
  }

  /** 全部浏览器已关闭后，只开始一次进程级 CEF 关闭。 */
  private fun startCefShutdownIfReady() {
    if (
      !isApplicationExitRequested ||
      activeControllers.isNotEmpty() ||
      closingControllers.isNotEmpty() ||
      isCefShutdownStarted
    ) {
      return
    }
    isCefShutdownStarted = true
    cefApplication.dispose()
    checkCefTermination()
  }

  /** 仅在 CEF 已真正终止时交给宿主退出；不可用状态停止轮询但不伪造终止成功。 */
  private fun checkCefTermination() {
    when (cefApplication.state) {
      CefApp.CefAppState.TERMINATED -> deliverApplicationExit()
      CefApp.CefAppState.NONE,
      CefApp.CefAppState.INITIALIZATION_FAILED,
      -> stopTerminationPolling()
      else -> startTerminationPollingIfNeeded()
    }
  }

  private fun startTerminationPollingIfNeeded() {
    if (terminationPoller != null || isApplicationExitDelivered) {
      return
    }
    terminationPoller = createTerminationPoller(::checkCefTermination).also(
      DesktopTerminationPoller::start,
    )
  }

  private fun stopTerminationPolling() {
    terminationPoller?.stop()
    terminationPoller = null
  }

  private fun deliverApplicationExit() {
    if (isApplicationExitDelivered) {
      return
    }
    isApplicationExitDelivered = true
    stopTerminationPolling()
    requireNotNull(onApplicationExit).invoke()
  }
}

/** Desktop 实现内部持有的 JCEF 资源、旧关闭通知与 Compose 退出协调器。 */
internal class DesktopWebViewRuntimeConfiguration(
  /** 由宿主初始化并注入的进程级 JCEF 实例。 */
  val cefApp: CefApp,
  /** 保持旧构造入口的浏览器关闭通知；新接入方不应在这里直接销毁 CEF。 */
  private val onBrowserClosed: () -> Unit,
) {
  private val applicationExitCoordinator = DesktopApplicationExitCoordinator(
    cefApplication = JcefDesktopCefApplication(cefApp),
    disposeController = DesktopWebViewController::dispose,
    createTerminationPoller = ::SwingDesktopTerminationPoller,
  )

  /** 注册 Compose 当前持有的控制器。 */
  fun registerController(controller: DesktopWebViewController) {
    applicationExitCoordinator.register(controller)
  }

  /** Compose 不再持有控制器时，继续等待其异步原生关闭完成。 */
  fun unregisterController(controller: DesktopWebViewController) {
    applicationExitCoordinator.unregister(controller)
  }

  /** 按准确的控制器身份处理 JCEF onBeforeClose 回调。 */
  fun onControllerClosed(controller: DesktopWebViewController) {
    onBrowserClosed()
    applicationExitCoordinator.onControllerClosed(controller)
  }

  /** 绑定 CEF 终止后的宿主应用退出回调。 */
  fun bindApplicationExit(onApplicationExit: () -> Unit) {
    applicationExitCoordinator.bindApplicationExit(onApplicationExit)
  }

  /** 请求由运行时协调所有 Compose 控制器和 CEF 的正常退出。 */
  fun requestApplicationExit() {
    applicationExitCoordinator.requestApplicationExit()
  }
}

/** 创建并在离开 Compose 时关闭当前 Desktop 浏览器。 */
@Composable
actual fun rememberWebViewController(
  initialization: WebViewInitialization,
  hostCallbacks: WebViewHostCallbacks,
): WebViewController {
  val runtimeConfiguration = DesktopWebViewRuntime.requireConfiguration()
  val currentCallbacks by rememberUpdatedState(hostCallbacks)
  val controller = remember(initialization, runtimeConfiguration) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Desktop WebView 必须在 Swing EDT 中创建。"
    }
    lateinit var createdController: DesktopWebViewController
    createdController = DesktopWebViewController(
      cefApp = runtimeConfiguration.cefApp,
      initialization = initialization,
      onExternalNavigation = { request -> currentCallbacks.onExternalNavigation(request) },
      onBrowserClosed = {
        runtimeConfiguration.onControllerClosed(createdController)
      },
    )
    runtimeConfiguration.registerController(createdController)
    createdController
  }

  DisposableEffect(controller) {
    onDispose {
      runtimeConfiguration.unregisterController(controller)
      controller.dispose()
    }
  }
  return controller
}

/** 将公共控制器持有的 JCEF 组件嵌入当前 Compose Desktop 布局。 */
@Composable
actual fun WebView(
  controller: WebViewController,
  modifier: Modifier,
) {
  val desktopController = controller as? DesktopWebViewController
    ?: error("WebView 必须接收 rememberWebViewController 创建的 Desktop 控制器。")
  key(controller) {
    SwingPanel(
      factory = { desktopController.view },
      modifier = modifier,
    )
  }
}
