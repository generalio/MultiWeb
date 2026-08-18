package io.github.multiweb.extension

import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebErrorCategory
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
  fun `精确 HTTPS 主机策略拒绝空集合和非精确 ASCII 主机`() {
    val invalidHostSets = listOf(
      emptySet(),
      setOf("*.example.com"),
      setOf("example.com:8443"),
      setOf("user:pass@example.com"),
      setOf("例子.com"),
      setOf("münich.example"),
      setOf("https://example.com"),
      setOf("example.com/path"),
    )

    invalidHostSets.forEach { hosts ->
      assertFailsWith<IllegalArgumentException> {
        ScriptBridgeOriginPolicy.ExactHttpsHosts(hosts)
      }
    }
  }

  @Test
  fun `精确 HTTPS 主机策略接受合法大小写 ASCII 主机`() {
    val hosts = setOf("EXAMPLE.com", "api.example.com")

    val policy = ScriptBridgeOriginPolicy.ExactHttpsHosts(hosts)

    assertEquals(hosts, policy.hosts)
  }

  @Test
  fun `精确 HTTPS 主机策略防御性复制传入的可变集合`() {
    val hosts = mutableSetOf("trusted.example")

    val policy = ScriptBridgeOriginPolicy.ExactHttpsHosts(hosts)
    hosts += "evil.example"

    assertEquals(setOf("trusted.example"), policy.hosts)
  }

  @Test
  fun `精确 HTTPS 主机策略的 copy 防御性复制传入的可变集合`() {
    val policy = ScriptBridgeOriginPolicy.ExactHttpsHosts(setOf("trusted.example"))
    val copiedHosts = mutableSetOf("trusted.example")

    val copiedPolicy = policy.copy(hosts = copiedHosts)
    copiedHosts += "evil.example"

    assertEquals(setOf("trusted.example"), copiedPolicy.hosts)
    assertEquals(copiedPolicy.hosts, copiedPolicy.component1())
    assertEquals(copiedPolicy, copiedPolicy.copy())
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
