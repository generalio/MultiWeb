package io.github.multiweb.desktop

import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
  fun `关闭浏览器先暂停顶层媒体再允许强制关闭`() {
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

    assertEquals(listOf("stopLoad", "getMainFrame", "pauseMedia", "setCloseAllowed", "close:true"), invocations)
  }

  @Test
  fun `尚无主框架的浏览器也强制关闭`() {
    val invocations = mutableListOf<String>()

    closeDesktopBrowser(browser(invocations, null))

    assertEquals(listOf("stopLoad", "getMainFrame", "setCloseAllowed", "close:true"), invocations)
  }

  @Test
  fun `EDT 原生关闭回调也先返回再完整释放客户端和通知`() {
    val invocations = mutableListOf<String>()
    val expectedBrowser = browser(mutableListOf(), null)
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = {
        assertTrue(SwingUtilities.isEventDispatchThread())
        invocations += "clientStart"
        assertEquals(listOf("callbackReturned", "clientStart"), invocations)
        invocations += "clientReturned"
      },
      onBrowserClosed = {
        assertTrue(SwingUtilities.isEventDispatchThread())
        assertEquals(listOf("callbackReturned", "clientStart", "clientReturned"), invocations)
        invocations += "closed"
      },
    )

    SwingUtilities.invokeAndWait {
      completion.onBeforeClose(expectedBrowser, expectedBrowser)
      assertTrue(invocations.isEmpty())
      invocations += "callbackReturned"
    }
    SwingUtilities.invokeAndWait {}

    assertEquals(listOf("callbackReturned", "clientStart", "clientReturned", "closed"), invocations)
  }

  @Test
  fun `并发重复关闭确认只释放和通知一次`() {
    val invocations = mutableListOf<String>()
    val expectedBrowser = browser(mutableListOf(), null)
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = { invocations += "client" },
      onBrowserClosed = { invocations += "closed" },
    )

    SwingUtilities.invokeAndWait {
      val callers = List(8) {
        thread {
          repeat(10) { completion.onBeforeClose(expectedBrowser, expectedBrowser) }
        }
      }
      callers.forEach(Thread::join)
      completion.complete()
      assertTrue(invocations.isEmpty())
    }
    SwingUtilities.invokeAndWait {}

    assertEquals(listOf("client", "closed"), invocations)
  }

  @Test
  fun `其他浏览器的关闭回调不会排队清理`() {
    val queued = mutableListOf<() -> Unit>()
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = { error("不应释放") },
      onBrowserClosed = { error("不应通知") },
      scheduleOnEdt = queued::add,
    )

    completion.onBeforeClose(browser(mutableListOf(), null), browser(mutableListOf(), null))

    assertTrue(queued.isEmpty())
  }

  @Test
  fun `客户端释放异常不会通知或重复清理`() {
    val queued = mutableListOf<() -> Unit>()
    var disposeCount = 0
    var closedCount = 0
    val failure = IllegalStateException("client cleanup failed")
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = {
        disposeCount++
        throw failure
      },
      onBrowserClosed = { closedCount++ },
      scheduleOnEdt = queued::add,
    )

    completion.complete()
    completion.complete()
    assertEquals(0, disposeCount)
    SwingUtilities.invokeAndWait {
      assertEquals(failure, assertFailsWith<IllegalStateException> { queued[0]() })
      queued[1]()
    }

    assertEquals(1, disposeCount)
    assertEquals(0, closedCount)
  }

  @Test
  fun `未发起创建时释放共用排队完成路径`() {
    val invocations = mutableListOf<String>()
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = { invocations += "client" },
      onBrowserClosed = { invocations += "closed" },
    )
    val lifecycle = DesktopBrowserCloseLifecycle(
      closeCreatedBrowser = { error("未创建不应关闭原生浏览器") },
      disposeUncreatedBrowser = completion::complete,
    )

    SwingUtilities.invokeAndWait {
      lifecycle.dispose()
      lifecycle.dispose()
      assertTrue(invocations.isEmpty())
    }
    SwingUtilities.invokeAndWait {}

    assertEquals(listOf("client", "closed"), invocations)
  }

  @Test
  fun `创建中退出后晚创建仍等待原生关闭确认`() {
    val invocations = mutableListOf<String>()
    val expectedBrowser = browser(mutableListOf(), null)
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = { invocations += "client" },
      onBrowserClosed = { invocations += "closed" },
    )
    val lifecycle = DesktopBrowserCloseLifecycle(
      closeCreatedBrowser = { invocations += "close:true" },
      disposeUncreatedBrowser = completion::complete,
    )

    SwingUtilities.invokeAndWait {
      lifecycle.onBrowserCreationStarted()
      lifecycle.dispose()
      assertTrue(invocations.isEmpty())
      lifecycle.onBrowserCreated()
      lifecycle.onBrowserCreated()
      lifecycle.dispose()
      assertEquals(listOf("close:true"), invocations)
      completion.onBeforeClose(expectedBrowser, expectedBrowser)
      assertEquals(listOf("close:true"), invocations)
    }
    SwingUtilities.invokeAndWait {}

    assertEquals(listOf("close:true", "client", "closed"), invocations)
  }

  @Test
  fun `原生浏览器集合尚未清空时不得释放客户端或通知`() {
    val queued = mutableListOf<() -> Unit>()
    val invocations = mutableListOf<String>()
    val expectedBrowser = browser(mutableListOf(), null)
    val otherBrowser = browser(mutableListOf(), null)
    var nativeBrowsers: Array<*> = arrayOf(expectedBrowser, otherBrowser)
    val retries = mutableListOf<() -> Unit>()
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = { invocations += "clientReturned" },
      onBrowserClosed = { invocations += "closed" },
      getBrowsers = { nativeBrowsers },
      scheduleOnEdt = queued::add,
      scheduleCleanupCheck = retries::add,
    )

    repeat(3) { completion.onBeforeClose(expectedBrowser, expectedBrowser) }
    assertTrue(invocations.isEmpty())
    SwingUtilities.invokeAndWait { queued.forEach { it() } }
    assertTrue(invocations.isEmpty())
    assertEquals(1, retries.size)

    nativeBrowsers = arrayOf(otherBrowser)
    SwingUtilities.invokeAndWait { retries.removeAt(0)() }
    assertTrue(invocations.isEmpty())
    assertEquals(1, retries.size)

    nativeBrowsers = emptyArray<Any>()
    SwingUtilities.invokeAndWait { retries.removeAt(0)() }
    assertEquals(listOf("clientReturned", "closed"), invocations)
    assertTrue(retries.isEmpty())
    completion.onBeforeClose(expectedBrowser, expectedBrowser)
    SwingUtilities.invokeAndWait { queued.last()() }
    assertEquals(listOf("clientReturned", "closed"), invocations)
  }

  @Test
  fun `原生集合查询异常会阻断释放和关闭确认`() {
    val queued = mutableListOf<() -> Unit>()
    val expectedBrowser = browser(mutableListOf(), null)
    val failure = IllegalAccessException("native browser snapshot unavailable")
    val completion = DesktopBrowserCloseCompletion(
      disposeClient = { error("查询失败不应释放客户端") },
      onBrowserClosed = { error("查询失败不应通知") },
      getBrowsers = { throw failure },
      scheduleOnEdt = queued::add,
      scheduleCleanupCheck = { error("查询失败不应继续轮询") },
    )

    repeat(2) { completion.onBeforeClose(expectedBrowser, expectedBrowser) }
    SwingUtilities.invokeAndWait {
      assertEquals(failure, assertFailsWith<IllegalAccessException> { queued[0]() })
      queued[1]()
    }
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
