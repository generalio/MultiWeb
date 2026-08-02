package io.github.multiweb.api

/** Secure defaults. Platform modules must document unsupported options explicitly. */
data class WebViewConfig(
  val javaScriptEnabled: Boolean = false,
  val thirdPartyCookiesEnabled: Boolean = false,
  val fileAccessEnabled: Boolean = false,
  val persistentSessionEnabled: Boolean = true,
  val allowedHosts: Set<String> = emptySet(),
)

fun interface NavigationPolicy {
  fun decide(request: NavigationRequest): NavigationDecision
}

data class NavigationRequest(
  val url: String,
  val isMainFrame: Boolean,
  val isUserInitiated: Boolean,
)

enum class NavigationDecision {
  Allow,
  Cancel,
  OpenExternally,
}

object DefaultNavigationPolicy : NavigationPolicy {
  override fun decide(request: NavigationRequest): NavigationDecision {
    return if (request.url.startsWith("https://")) {
      NavigationDecision.Allow
    } else {
      NavigationDecision.Cancel
    }
  }
}

