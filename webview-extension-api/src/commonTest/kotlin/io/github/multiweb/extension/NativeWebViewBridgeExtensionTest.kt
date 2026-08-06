package io.github.multiweb.extension

import io.github.multiweb.api.JavaScriptExecutor
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeWebViewBridgeExtensionTest {
  @Test
  fun `旧方法名会转换为类型化宿主请求`() {
    val requests = mutableListOf<NativeWebViewBridgeRequest>()
    val bridge = NativeWebViewBridgeExtension(
      allowedHosts = setOf("app.example.com"),
      host = NativeWebViewBridgeHost { request ->
        requests += request
        when (request) {
          NativeWebViewBridgeRequest.GetToken -> NativeWebViewBridgeResult.Success("token")
          else -> NativeWebViewBridgeResult.Success()
        }
      },
    ).scriptBridges.single()

    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("savePic", "https://cdn.example.com/a.png"))).isSuccess)
    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("setFullscreen", "true"))).isSuccess)
    assertEquals("token", requireNotNull(bridge.handle(ScriptBridgeCall("getToken"))).payload)

    assertEquals(
      listOf(
        NativeWebViewBridgeRequest.SaveImage("https://cdn.example.com/a.png"),
        NativeWebViewBridgeRequest.SetFullscreen(true),
        NativeWebViewBridgeRequest.GetToken,
      ),
      requests,
    )
  }

  @Test
  fun `非法参数和宿主拒绝会返回稳定错误`() {
    val bridge = NativeWebViewBridgeExtension(
      allowedHosts = setOf("app.example.com"),
      host = NativeWebViewBridgeHost {
        NativeWebViewBridgeResult.Failure("unsupported_operation", "当前宿主未实现")
      },
    ).scriptBridges.single()

    val invalidFullscreen = requireNotNull(bridge.handle(ScriptBridgeCall("setFullscreen", "TRUE")))
    assertFalse(invalidFullscreen.isSuccess)
    assertEquals("invalid_request", invalidFullscreen.errorCode)

    val unsupportedSensor = requireNotNull(bridge.handle(ScriptBridgeCall("initSensor", "4")))
    assertFalse(unsupportedSensor.isSuccess)
    assertEquals("unsupported_operation", unsupportedSensor.errorCode)
    assertEquals("当前宿主未实现", unsupportedSensor.payload)
  }

  @Test
  fun `桥通过独立传输对象和受限方法门面暴露`() {
    val bridge = NativeWebViewBridgeExtension(
      allowedHosts = setOf("app.example.com"),
      host = NativeWebViewBridgeHost { NativeWebViewBridgeResult.Success() },
    ).scriptBridges.single()

    val bridgeWithFacade = requireNotNull(bridge as? ScriptBridgeWithFacade)
    assertEquals("AndroidWebView", bridge.name)
    assertEquals("__multiweb_AndroidWebView_transport", bridgeWithFacade.transportName)
    assertEquals(setOf("app.example.com"), bridge.allowedHosts)
    assertTrue(bridgeWithFacade.facade.methodNames.contains("getSystemBarInsets"))
    assertTrue(bridgeWithFacade.facade.methodNames.contains("savePic"))
  }

  @Test
  fun `默认继续将旧脚本请求交给宿主`() {
    val requests = mutableListOf<NativeWebViewBridgeRequest>()
    val bridge = NativeWebViewBridgeExtension(
      allowedHosts = setOf("app.example.com"),
      host = NativeWebViewBridgeHost { request ->
        requests += request
        NativeWebViewBridgeResult.Success()
      },
    ).scriptBridges.single()

    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("onLoad", "window.init()"))).isSuccess)
    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("exeJs", "window.refresh()"))).isSuccess)

    assertEquals(
      listOf(
        NativeWebViewBridgeRequest.SetPageLoadScript("window.init()"),
        NativeWebViewBridgeRequest.ExecuteJavaScript("window.refresh()"),
      ),
      requests,
    )
  }

  @Test
  fun `开启后仅执行最新页面脚本并绕过宿主`() {
    val requests = mutableListOf<NativeWebViewBridgeRequest>()
    val extension = NativeWebViewBridgeExtension(
      allowedHosts = setOf("app.example.com"),
      host = NativeWebViewBridgeHost { request ->
        requests += request
        NativeWebViewBridgeResult.Success()
      },
      enableLegacyJavaScriptExecution = true,
    )
    val executor = RecordingJavaScriptExecutor()
    extension.onControllerAttached(executor)
    val bridge = extension.scriptBridges.single()

    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("onLoad", "window.first()"))).isSuccess)
    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("onLoad", "window.latest()"))).isSuccess)
    extension.onPageFinished(PageFinishedEvent("https://app.example.com/home"))
    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("exeJs", "window.now()"))).isSuccess)

    assertEquals(emptyList(), requests)
    assertEquals(
      listOf("window.latest()", "window.now()"),
      executor.scripts,
    )
    assertEquals(
      listOf(setOf("app.example.com"), setOf("app.example.com")),
      executor.allowedHostCalls,
    )
  }

  @Test
  fun `未绑定或被拒绝的受控脚本会返回稳定错误并在释放时清理状态`() {
    val extension = NativeWebViewBridgeExtension(
      allowedHosts = setOf("app.example.com"),
      host = NativeWebViewBridgeHost { NativeWebViewBridgeResult.Success() },
      enableLegacyJavaScriptExecution = true,
    )
    val bridge = extension.scriptBridges.single()

    val unavailable = requireNotNull(bridge.handle(ScriptBridgeCall("onLoad", "window.init()")))
    assertFalse(unavailable.isSuccess)
    assertEquals("javascript_executor_unavailable", unavailable.errorCode)

    val executor = RecordingJavaScriptExecutor(shouldAccept = false)
    extension.onControllerAttached(executor)
    val rejected = requireNotNull(bridge.handle(ScriptBridgeCall("exeJs", "window.now()")))
    assertFalse(rejected.isSuccess)
    assertEquals("javascript_execution_rejected", rejected.errorCode)

    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("onLoad", "window.init()"))).isSuccess)
    extension.onControllerDisposed()
    extension.onPageFinished(PageFinishedEvent("https://app.example.com/home"))

    assertEquals(emptyList(), executor.scripts)
    val disposed = requireNotNull(bridge.handle(ScriptBridgeCall("exeJs", "window.now()")))
    assertFalse(disposed.isSuccess)
    assertEquals("javascript_executor_unavailable", disposed.errorCode)
  }

  private class RecordingJavaScriptExecutor(
    private val shouldAccept: Boolean = true,
  ) : WebViewController, JavaScriptExecutor {
    val scripts = mutableListOf<String>()
    val allowedHostCalls = mutableListOf<Set<String>>()

    override val state: WebViewState = WebViewState()

    override fun executeJavaScript(script: String, allowedHosts: Set<String>): Boolean {
      if (!shouldAccept) {
        return false
      }
      scripts += script
      allowedHostCalls += allowedHosts
      return true
    }

    override fun load(request: WebRequest) = Unit

    override fun reload() = Unit

    override fun goBack() = Unit

    override fun goForward() = Unit

    override fun stopLoading() = Unit

    override fun clearSession() = Unit

    override fun dispose() = Unit
  }
}
