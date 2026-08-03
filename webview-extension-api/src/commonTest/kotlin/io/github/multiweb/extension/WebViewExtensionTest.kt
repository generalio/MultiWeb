package io.github.multiweb.extension

import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebErrorCategory
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
