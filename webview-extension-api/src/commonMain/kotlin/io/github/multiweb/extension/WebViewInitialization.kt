package io.github.multiweb.extension

import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.WebViewConfig

/**
 * 跨平台控制器的声明式初始化参数。
 *
 * 平台控制器仍由各自模块创建原生视图，但可共用同一份安全配置、导航策略和扩展列表，避免 Android、iOS、
 * Desktop 入口重复定义行为。外部跳转、窗口全屏、存储权限等系统能力不在此对象中，必须由宿主显式传入。
 */
data class WebViewInitialization(
  /** WebView 的最小权限安全配置。 */
  val webViewConfig: WebViewConfig = WebViewConfig(),
  /** 处理主文档与用户导航的策略，默认只允许 HTTPS。 */
  val navigationPolicy: NavigationPolicy = DefaultNavigationPolicy,
  /** 在控制器生命周期内接收页面事件和受限 JS 桥调用的扩展。 */
  val extensions: List<WebViewExtension> = emptyList(),
)
