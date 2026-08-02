package io.github.multiweb.android

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebViewConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidNavigationDeciderTest {
  @Test
  fun `白名单为空时完全交由业务策略处理`() {
    val decider = AndroidNavigationDecider(
      config = WebViewConfig(),
      navigationPolicy = NavigationPolicy { NavigationDecision.OpenExternally },
    )

    val decision = decider.decide(request("https://example.com"))

    assertEquals(NavigationDecision.OpenExternally, decision)
  }

  @Test
  fun `白名单阻止策略意外放行的其他主机`() {
    val decider = AndroidNavigationDecider(
      config = WebViewConfig(allowedHosts = setOf("example.com")),
      navigationPolicy = NavigationPolicy { NavigationDecision.Allow },
    )

    assertEquals(NavigationDecision.Allow, decider.decide(request("https://EXAMPLE.com/path")))
    assertEquals(NavigationDecision.Cancel, decider.decide(request("https://untrusted.example")))
    assertEquals(NavigationDecision.Cancel, decider.decide(request("not a url")))
  }

  private fun request(url: String): NavigationRequest {
    return NavigationRequest(
      url = url,
      isMainFrame = true,
      isUserInitiated = true,
    )
  }
}
