package io.github.multiweb.testing

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebErrorCategory
import io.github.multiweb.api.WebRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FakeWebViewControllerTest {
  @Test
  fun `允许导航时更新状态并记录页面请求`() {
    val controller = FakeWebViewController()
    val request = WebRequest("https://example.com/home")

    controller.load(request)

    assertEquals("https://example.com/home", controller.state.url)
    assertEquals(1f, controller.state.loadingProgress)
    assertFalse(controller.state.isLoading)
    assertNull(controller.state.error)
    assertEquals(
      FakeWebViewOperation.Load(request, NavigationDecision.Allow),
      controller.operations.single(),
    )
  }

  @Test
  fun `取消或外部打开时保留当前页面`() {
    val decisions = mutableListOf(
      NavigationDecision.Allow,
      NavigationDecision.Cancel,
      NavigationDecision.OpenExternally,
    )
    val controller = FakeWebViewController(
      navigationPolicy = NavigationPolicy { decisions.removeAt(0) },
    )

    controller.load(WebRequest("https://example.com/home"))
    controller.load(WebRequest("https://example.com/cancelled"))
    controller.load(WebRequest("https://example.com/external"))

    assertEquals("https://example.com/home", controller.state.url)
    assertFalse(controller.state.canGoBack)
    assertEquals(
      NavigationDecision.Cancel,
      assertIs<FakeWebViewOperation.Load>(controller.operations[1]).decision,
    )
    assertEquals(
      NavigationDecision.OpenExternally,
      assertIs<FakeWebViewOperation.Load>(controller.operations[2]).decision,
    )
  }

  @Test
  fun `前进后退遵循历史记录并丢弃分支`() {
    val controller = FakeWebViewController()
    controller.load(WebRequest("https://example.com/first"))
    controller.load(WebRequest("https://example.com/second"))

    controller.goBack()

    assertEquals("https://example.com/first", controller.state.url)
    assertFalse(controller.state.canGoBack)
    assertTrue(controller.state.canGoForward)

    controller.goForward()

    assertEquals("https://example.com/second", controller.state.url)
    assertTrue(controller.state.canGoBack)
    assertFalse(controller.state.canGoForward)

    controller.goBack()
    controller.load(WebRequest("https://example.com/branch"))

    assertEquals("https://example.com/branch", controller.state.url)
    assertTrue(controller.state.canGoBack)
    assertFalse(controller.state.canGoForward)
  }

  @Test
  fun `错误会保留当前页面且销毁后拒绝新命令`() {
    val controller = FakeWebViewController()
    controller.load(WebRequest("https://example.com/home"))
    val error = WebError(WebErrorCategory.Network, "连接超时")

    controller.failLoading(error)

    assertEquals("https://example.com/home", controller.state.url)
    assertEquals(error, controller.state.error)

    controller.clearSession()
    controller.dispose()

    assertTrue(controller.sessionCleared)
    assertTrue(controller.isDisposed)
    assertFailsWith<IllegalStateException> {
      controller.reload()
    }
  }
}
