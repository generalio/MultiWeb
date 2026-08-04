package io.github.multiweb.extension

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

    assertEquals("AndroidWebView", bridge.name)
    assertEquals("__multiweb_AndroidWebView_transport", bridge.transportName)
    assertEquals(setOf("app.example.com"), bridge.allowedHosts)
    assertTrue(requireNotNull(bridge.facade).methodNames.contains("getSystemBarInsets"))
    assertTrue(requireNotNull(bridge.facade).methodNames.contains("savePic"))
  }
}
