package io.github.multiweb.sample

import io.github.multiweb.api.WebRequest
import io.github.multiweb.testing.FakeWebViewController
import io.github.multiweb.testing.FakeWebViewOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SampleWebViewPresenterTest {
  @Test
  fun `加载操作使用地址栏输入并更新页面状态`() {
    val controller = FakeWebViewController()
    val presenter = SampleWebViewPresenter(controller)

    presenter.updateUrlInput("https://kotlinlang.org")
    presenter.load()

    val operation = assertIs<FakeWebViewOperation.Load>(controller.operations.single())
    assertEquals("https://kotlinlang.org", operation.request.url)
    assertEquals("https://kotlinlang.org", presenter.uiState.webViewState.url)
    assertNull(presenter.uiState.actionError)
  }

  @Test
  fun `历史导航命令委托给控制器并刷新状态`() {
    val controller = FakeWebViewController()
    val presenter = SampleWebViewPresenter(controller)
    presenter.updateUrlInput("https://example.com/first")
    presenter.load()
    presenter.updateUrlInput("https://example.com/second")
    presenter.load()

    presenter.goBack()
    assertEquals("https://example.com/first", presenter.uiState.webViewState.url)
    assertTrue(presenter.uiState.webViewState.canGoForward)

    presenter.goForward()
    assertEquals("https://example.com/second", presenter.uiState.webViewState.url)
    assertFalse(presenter.uiState.webViewState.canGoForward)
    assertEquals(FakeWebViewOperation.GoForward, controller.operations.last())
  }

  @Test
  fun `清理会话命令由控制器执行`() {
    val controller = FakeWebViewController()
    val presenter = SampleWebViewPresenter(controller)

    presenter.clearSession()

    assertTrue(controller.sessionCleared)
    assertNull(presenter.uiState.actionError)
  }

  @Test
  fun `刷新状态会同步原生控制器的异步回调结果`() {
    val controller = FakeWebViewController()
    val presenter = SampleWebViewPresenter(controller)

    controller.load(WebRequest("https://kotlinlang.org"))

    assertTrue(presenter.refreshState())
    assertEquals("https://kotlinlang.org", presenter.uiState.webViewState.url)
    assertFalse(presenter.refreshState())
  }
}
