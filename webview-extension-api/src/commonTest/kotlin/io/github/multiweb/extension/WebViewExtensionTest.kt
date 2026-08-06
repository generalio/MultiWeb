package io.github.multiweb.extension

import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebErrorCategory
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebViewExtensionTest {
  @Test
  fun 默认扩展不注册脚本桥且可忽略平台事件() {
    val extension = object : WebViewExtension {}

    extension.onPageStarted(PageStartedEvent("https://example.com"))
    extension.onPageFinished(PageFinishedEvent("https://example.com", "示例"))
    extension.onPageError(
      PageErrorEvent(
        WebError(WebErrorCategory.Network, "连接超时", "https://example.com"),
      ),
    )
    extension.onDownloadRequested(DownloadRequest("https://example.com/file.pdf"))
    extension.onContextAction(WebContextAction.LinkLongPressed("https://example.com/link"))
    extension.onHostUiRequest(HostUiRequest.SetFullscreen(true))

    assertTrue(extension.scriptBridges.isEmpty())
  }

  @Test
  fun 脚本桥由扩展显式声明受信任域名和响应() {
    val bridge = object : ScriptBridge {
      override val name = "multiWebDevice"
      override val allowedHosts = setOf("example.com")

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? {
        return when (call.method) {
          "getInsets" -> ScriptBridgeResponse(isSuccess = true, payload = "{\"top\":24}")
          else -> ScriptBridgeResponse(isSuccess = false, errorCode = "unknown_method")
        }
      }
    }

    assertEquals(setOf("example.com"), bridge.allowedHosts)
    assertEquals("{\"top\":24}", bridge.handle(ScriptBridgeCall("getInsets"))?.payload)
    assertEquals("unknown_method", bridge.handle(ScriptBridgeCall("other"))?.errorCode)
    assertEquals("", bridge.handle(ScriptBridgeCall("other"))?.payload)
  }

  @Test
  fun 控制器生命周期扩展仅在显式实现时接收控制器引用() {
    var attachedController: WebViewController? = null
    var disposed = false
    val extension = object : WebViewControllerLifecycleExtension {
      override fun onControllerAttached(controller: WebViewController) {
        attachedController = controller
      }

      override fun onControllerDisposed() {
        disposed = true
      }
    }
    val controller = TestWebViewController

    extension.onControllerAttached(controller)
    extension.onControllerDisposed()

    assertSame(controller, attachedController)
    assertTrue(disposed)
  }

  private object TestWebViewController : WebViewController {
    override val state: WebViewState = WebViewState()

    override fun load(request: WebRequest) = Unit

    override fun reload() = Unit

    override fun goBack() = Unit

    override fun goForward() = Unit

    override fun stopLoading() = Unit

    override fun clearSession() = Unit

    override fun dispose() = Unit
  }
}
