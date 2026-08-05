package io.github.multiweb.sample

import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.extension.WebViewExtension
import io.github.multiweb.extension.WebViewInitialization

/**
 * 创建示例各平台共用的控制器初始化参数。
 *
 * Android、iOS 与 Desktop 入口只负责创建原生控制器并提供宿主能力；JavaScript、导航策略及扩展列表在这里
 * 统一声明，避免平台间因重复配置而产生行为差异。
 */
internal fun sampleWebViewInitialization(
  extensions: List<WebViewExtension> = emptyList(),
): WebViewInitialization {
  return WebViewInitialization(
    webViewConfig = WebViewConfig(javaScriptEnabled = true),
    navigationPolicy = DefaultNavigationPolicy,
    extensions = extensions,
  )
}
