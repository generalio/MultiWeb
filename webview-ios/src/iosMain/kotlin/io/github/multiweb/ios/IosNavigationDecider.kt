package io.github.multiweb.ios

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebViewConfig
import platform.Foundation.NSURL

/**
 * WKWebView 的导航决策器。
 *
 * 白名单校验优先于业务导航策略执行；同时拒绝未显式开启的本地文件 URL，避免业务策略错误放行。
 */
internal class IosNavigationDecider(
  private val config: WebViewConfig,
  private val navigationPolicy: NavigationPolicy,
) {
  fun decide(request: NavigationRequest): NavigationDecision {
    if (!isFileAccessAllowed(request.url) || !isAllowedHost(request.url)) {
      return NavigationDecision.Cancel
    }
    return navigationPolicy.decide(request)
  }

  private fun isFileAccessAllowed(url: String): Boolean {
    return config.fileAccessEnabled || !url.startsWith("file://", ignoreCase = true)
  }

  private fun isAllowedHost(url: String): Boolean {
    if (config.allowedHosts.isEmpty()) {
      return true
    }

    val host = NSURL(string = url).host ?: return false
    return config.allowedHosts.any { allowedHost ->
      host.equals(allowedHost, ignoreCase = true)
    }
  }
}
