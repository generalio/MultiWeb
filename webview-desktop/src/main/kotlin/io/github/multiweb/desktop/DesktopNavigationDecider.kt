package io.github.multiweb.desktop

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebViewConfig
import java.net.URI

/**
 * JCEF 的导航决策器。
 *
 * 文件访问和主机名白名单校验先于业务策略执行，避免业务策略意外放行本地文件或非预期域名。
 */
internal class DesktopNavigationDecider(
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

    val host = runCatching { URI(url).host }.getOrNull() ?: return false
    return config.allowedHosts.any { allowedHost ->
      host.equals(allowedHost, ignoreCase = true)
    }
  }
}
