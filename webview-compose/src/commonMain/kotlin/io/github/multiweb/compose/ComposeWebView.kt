package io.github.multiweb.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebViewController
import io.github.multiweb.extension.WebViewInitialization

/**
 * Compose WebView 宿主需要显式处理的跨平台回调。
 *
 * Android、iOS 与 Desktop 控制器不会猜测宿主的 Intent、URL Scheme 或业务路由。导航策略返回外部打开时，
 * 由 [onExternalNavigation] 接收请求并决定后续动作；未提供回调时该请求不会被组件自行打开。JS/Wasm 始终
 * 使用浏览器新窗口模式，不会调用此回调。
 */
data class WebViewHostCallbacks(
  /** 处理被导航策略标记为外部打开的主框架导航。 */
  val onExternalNavigation: (NavigationRequest) -> Unit = {},
)

/**
 * 在当前 Compose 生命周期中创建并持有平台 WebView 控制器。
 *
 * Android、iOS 和 Desktop 使用同一调用方式；原生视图创建、Android 前后台转发与销毁均由平台
 * actual 实现管理。Desktop 必须先调用 `DesktopWebViewRuntime.initialize` 注入进程级 `CefApp`，
 * 因为 JCEF 实例不能由单个控制器创建或销毁。初始化参数变化时会释放旧控制器并创建新控制器。
 *
 * JS/Wasm 不创建嵌入式视图，允许的页面会由浏览器在新窗口或标签页打开。
 */
@Composable
expect fun rememberWebViewController(
  initialization: WebViewInitialization,
  hostCallbacks: WebViewHostCallbacks = WebViewHostCallbacks(),
): WebViewController

/**
 * 在 Compose 布局中显示 [rememberWebViewController] 创建的原生 WebView。
 *
 * [controller] 必须来自当前平台的 [rememberWebViewController]。Android、iOS 与 Desktop 会嵌入
 * 原生视图；JS/Wasm 没有可嵌入视图，因此本函数不绘制内容。
 */
@Composable
expect fun WebView(
  controller: WebViewController,
  modifier: Modifier = Modifier,
)
