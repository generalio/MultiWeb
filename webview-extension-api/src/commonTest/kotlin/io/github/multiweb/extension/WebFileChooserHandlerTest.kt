package io.github.multiweb.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebFileChooserHandlerTest {
  @Test
  fun `宿主处理器接收网页声明并异步返回选择结果`() {
    var receivedRequest: WebFileChooserRequest? = null
    var receivedResult: WebFileChooserResult? = null
    val handler = WebFileChooserHandler { request, complete ->
      receivedRequest = request
      complete(WebFileChooserResult.Selected(listOf("content://documents/report.pdf")))
    }
    val request = WebFileChooserRequest(
      acceptTypes = listOf("application/pdf"),
      allowMultipleSelection = true,
      allowDirectories = false,
    )

    handler.onFileChooserRequested(request) { result ->
      receivedResult = result
    }

    assertEquals(request, receivedRequest)
    assertEquals(
      listOf("content://documents/report.pdf"),
      assertIs<WebFileChooserResult.Selected>(receivedResult).uris,
    )
  }

  @Test
  fun `取消结果不携带文件地址`() {
    assertIs<WebFileChooserResult.Cancelled>(WebFileChooserResult.Cancelled)
  }

  @Test
  fun `初始化参数默认不授予文件选择能力且可通过扩展显式注入处理器`() {
    val handler = WebFileChooserHandler { _, complete ->
      complete(WebFileChooserResult.Cancelled)
    }

    assertTrue(WebViewInitialization().extensions.filterIsInstance<WebFileChooserHandler>().isEmpty())
    assertEquals(
      handler,
      WebViewInitialization(extensions = listOf(handler)).extensions.single(),
    )
  }
}
