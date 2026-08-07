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
import javax.swing.SwingUtilities
import org.cef.CefApp

/**
 * Desktop WebView 的进程级 JCEF 运行时。
 *
 * 宿主必须在首次调用 [rememberWebViewController] 前使用 [initialize] 注入已创建的 [CefApp]。
 * 该对象绝不会主动调用 `CefApp.dispose()`：当每个浏览器原生关闭后，会转发 [onBrowserClosed]，
 * 由宿主在确认所有浏览器均已关闭后自行销毁进程资源。
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

  internal fun requireConfiguration(): DesktopWebViewRuntimeConfiguration {
    return synchronized(this) {
      requireNotNull(configuration) {
        "Desktop WebView 使用前必须先调用 DesktopWebViewRuntime.initialize(cefApp)。"
      }
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

/** Desktop 实现内部持有的 JCEF 资源与关闭回调。 */
internal class DesktopWebViewRuntimeConfiguration(
  /** 由宿主初始化、持有并最终销毁的进程级 JCEF 实例。 */
  val cefApp: CefApp,
  /** 某个浏览器原生关闭且其客户端已释放后通知宿主。 */
  val onBrowserClosed: () -> Unit,
)

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
    DesktopWebViewController(
      cefApp = runtimeConfiguration.cefApp,
      initialization = initialization,
      onExternalNavigation = { request -> currentCallbacks.onExternalNavigation(request) },
      onBrowserClosed = runtimeConfiguration.onBrowserClosed,
    )
  }

  DisposableEffect(controller) {
    onDispose(controller::dispose)
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
