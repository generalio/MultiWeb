package io.github.multiweb.compose

import io.github.multiweb.api.NavigationRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebViewHostCallbacksTest {
  @Test
  fun `外部导航回调保留原始请求`() {
    var received: NavigationRequest? = null
    val callbacks = WebViewHostCallbacks { request ->
      received = request
    }
    val request = NavigationRequest(
      url = "https://example.com/external",
      isMainFrame = true,
      isUserInitiated = true,
    )

    callbacks.onExternalNavigation(request)

    assertEquals(request, received)
  }
}
