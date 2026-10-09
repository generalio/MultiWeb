package io.github.multiweb.desktop

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame

class DesktopWebViewControllerCloseTest {

  @Test
  fun `创建已请求时释放会等待创建回调后仅关闭一次`() {
    val invocations = mutableListOf<String>()
    val lifecycle = DesktopBrowserCloseLifecycle(
      closeCreatedBrowser = { invocations += "closeCreated" },
      disposeUncreatedBrowser = { invocations += "disposeUncreated" },
    )

    lifecycle.onBrowserCreationStarted()
    lifecycle.dispose()

    assertEquals(emptyList(), invocations)

    lifecycle.onBrowserCreated()
    lifecycle.onBrowserCreated()
    lifecycle.dispose()

    assertEquals(listOf("closeCreated"), invocations)
  }

  @Test
  fun `关闭浏览器先暂停顶层媒体再允许正常关闭`() {
    val invocations = mutableListOf<String>()
    val frame = Proxy.newProxyInstance(
      CefFrame::class.java.classLoader,
      arrayOf(CefFrame::class.java),
    ) { _, method, arguments ->
      if (method.name == "executeJavaScript") {
        assertEquals(
          "document.querySelectorAll('audio,video').forEach(function(media){media.pause();});",
          arguments!![0],
        )
        assertEquals("multiweb://dispose", arguments[1])
        assertEquals(0, arguments[2])
        invocations += "pauseMedia"
      }
      null
    } as CefFrame

    closeDesktopBrowser(browser(invocations, frame))

    assertEquals(listOf("stopLoad", "getMainFrame", "pauseMedia", "setCloseAllowed", "close:false"), invocations)
  }

  @Test
  fun `尚无主框架的浏览器也正常关闭`() {
    val invocations = mutableListOf<String>()

    closeDesktopBrowser(browser(invocations, null))

    assertEquals(listOf("stopLoad", "getMainFrame", "setCloseAllowed", "close:false"), invocations)
  }

  private fun browser(invocations: MutableList<String>, frame: CefFrame?): CefBrowser =
    Proxy.newProxyInstance(
      CefBrowser::class.java.classLoader,
      arrayOf(CefBrowser::class.java),
    ) { _, method, arguments ->
      invocations += if (method.name == "close") "close:${arguments!![0]}" else method.name
      if (method.name == "getMainFrame") frame else null
    } as CefBrowser
}
