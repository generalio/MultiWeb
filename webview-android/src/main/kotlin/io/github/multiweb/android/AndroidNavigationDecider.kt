package io.github.multiweb.android

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebViewConfig
import java.net.URI

/**
 * Android WebView 的导航决策器。
 *
 * 当 [WebViewConfig.allowedHosts] 非空时，先执行主机名白名单校验，再调用业务导航策略，
 * 避免业务策略意外放行非预期域名。
 */
internal class AndroidNavigationDecider(
  private val config: WebViewConfig,
  private val navigationPolicy: NavigationPolicy,
) {
  fun decide(request: NavigationRequest): NavigationDecision {
    if (!isAllowedHost(request.url)) {
      return NavigationDecision.Cancel
    }
    return navigationPolicy.decide(request)
  }

  private fun isAllowedHost(url: String): Boolean {
    if (config.allowedHosts.isEmpty()) {
      return true
    }

    val host = runCatching { URI(url).host }.getOrNull() ?: return false
    return config.allowedHosts.any { allowedHost ->
      host.equals(allowedHost, ignoreCase = true)
    }
  }
}
