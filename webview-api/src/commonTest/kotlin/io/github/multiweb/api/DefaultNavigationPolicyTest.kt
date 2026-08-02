package io.github.multiweb.api

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultNavigationPolicyTest {
  @Test
  fun `allows only https navigation by default`() {
    val httpsRequest = NavigationRequest(
      url = "https://example.com",
      isMainFrame = true,
      isUserInitiated = true,
    )
    val customSchemeRequest = NavigationRequest(
      url = "myapp://callback",
      isMainFrame = true,
      isUserInitiated = true,
    )

    assertEquals(NavigationDecision.Allow, DefaultNavigationPolicy.decide(httpsRequest))
    assertEquals(NavigationDecision.Cancel, DefaultNavigationPolicy.decide(customSchemeRequest))
  }
}

