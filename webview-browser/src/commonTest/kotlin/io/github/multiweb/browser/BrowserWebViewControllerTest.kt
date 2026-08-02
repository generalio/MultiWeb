package io.github.multiweb.browser

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BrowserWebViewControllerTest {
  @Test
  fun `允许的地址在新浏览器上下文中打开并更新状态`() {
    val openedUrls = mutableListOf<String>()
    val controller = createBrowserWebViewControllerForTesting(
      navigationPolicy = NavigationPolicy { NavigationDecision.Allow },
      openUrl = openedUrls::add,
    )

    controller.load(WebRequest("https://example.com/docs"))
    controller.reload()

    assertEquals(listOf("https://example.com/docs", "https://example.com/docs"), openedUrls)
    assertEquals("https://example.com/docs", controller.state.url)
    assertEquals(1f, controller.state.loadingProgress)
  }

  @Test
  fun `不满足白名单或策略的请求不会打开浏览器`() {
    val openedUrls = mutableListOf<String>()
    val controller = createBrowserWebViewControllerForTesting(
      config = WebViewConfig(allowedHosts = setOf("example.com")),
      navigationPolicy = NavigationPolicy { NavigationDecision.Cancel },
      openUrl = openedUrls::add,
    )

    controller.load(WebRequest("https://other.example/path"))
    controller.load(WebRequest("https://example.com/path"))

    assertEquals(emptyList(), openedUrls)
    assertNull(controller.state.url)
  }

  @Test
  fun `浏览器全局会话无法被组件清理且销毁后拒绝操作`() {
    val controller = createBrowserWebViewControllerForTesting(
      navigationPolicy = NavigationPolicy { NavigationDecision.Allow },
      openUrl = {},
    )

    assertFailsWith<UnsupportedOperationException> {
      controller.clearSession()
    }

    controller.dispose()

    assertFailsWith<IllegalStateException> {
      controller.load(WebRequest("https://example.com"))
    }
  }
}
