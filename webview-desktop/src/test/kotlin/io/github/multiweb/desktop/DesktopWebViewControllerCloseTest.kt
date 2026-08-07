package io.github.multiweb.desktop

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.cef.browser.CefBrowser

class DesktopWebViewControllerCloseTest {

  @Test
  fun `关闭浏览器会先允许正常关闭而非强制终止`() {
    val invocations = mutableListOf<String>()
    val browser = Proxy.newProxyInstance(
      CefBrowser::class.java.classLoader,
      arrayOf(CefBrowser::class.java),
    ) { _, method, arguments ->
      invocations += "${method.name}:${arguments?.joinToString() ?: ""}"
      null
    } as CefBrowser

    closeDesktopBrowser(browser)

    assertEquals(
      listOf(
        "stopLoad:",
        "setCloseAllowed:",
        "close:false",
      ),
      invocations,
    )
  }
}
