package io.github.multiweb.sample

import io.github.multiweb.extension.HostUiRequest
import io.github.multiweb.extension.ScriptBridgeCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SampleWebViewExtensionTest {
  @Test
  fun `桥只暴露给示例页面并分发全屏请求`() {
    var fullscreenRequest: HostUiRequest? = null
    val extension = SampleWebViewExtension(
      hostUiRequestHandler = { request -> fullscreenRequest = request },
    )
    val bridge = extension.scriptBridges.single()

    assertEquals(SampleScriptBridgeName, bridge.name)
    assertEquals(SampleScriptBridgeHosts, bridge.allowedHosts)
    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("setFullscreen", "true"))).isSuccess)
    assertEquals(true, assertIs<HostUiRequest.SetFullscreen>(fullscreenRequest).enabled)
    assertEquals(
      "invalid_fullscreen_value",
      requireNotNull(bridge.handle(ScriptBridgeCall("setFullscreen", "TRUE"))).errorCode,
    )
  }

  @Test
  fun `图片保存只接受声明 CDN 的 HTTPS 地址`() {
    val requestedUrls = mutableListOf<String>()
    val bridge = SampleWebViewExtension(onImageSaveRequested = requestedUrls::add).scriptBridges.single()
    val validUrl = "https://cdn.redrock.team/app/poster.png?version=1"

    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("saveImage", validUrl))).isSuccess)
    assertEquals(listOf(validUrl), requestedUrls)

    listOf(
      "http://cdn.redrock.team/app/poster.png",
      "https://cdn.redrock.team:8443/app/poster.png",
      "https://user@cdn.redrock.team/app/poster.png",
      "https://cdn.redrock.team.evil.example/app/poster.png",
      "https://app.redrock.team/app/poster.png",
      "https://cdn.redrock.team/unsafe path.png",
      "https://cdn.redrock.team/unsafe\u007fpath.png",
    ).forEach { url ->
      assertFalse(requireNotNull(bridge.handle(ScriptBridgeCall("saveImage", url))).isSuccess)
    }
    assertEquals(listOf(validUrl), requestedUrls)
  }

  @Test
  fun `兼容桥图片保存同样拒绝非受信任地址`() {
    val requestedUrls = mutableListOf<String>()
    val bridge = sampleNativeWebViewBridgeExtension(onImageSaveRequested = requestedUrls::add)
      .scriptBridges
      .single()

    val response = requireNotNull(
      bridge.handle(ScriptBridgeCall("savePic", "https://untrusted.example/poster.png")),
    )

    assertFalse(response.isSuccess)
    assertEquals("untrusted_image_url", response.errorCode)
    assertTrue(requestedUrls.isEmpty())

    val validUrl = "https://cdn.redrock.team/app/poster.png"
    assertTrue(requireNotNull(bridge.handle(ScriptBridgeCall("savePic", validUrl))).isSuccess)
    assertEquals(listOf(validUrl), requestedUrls)
  }

  @Test
  fun `兼容桥示例显式启用受控旧脚本执行`() {
    val response = sampleNativeWebViewBridgeExtension().scriptBridges.single()
      .handle(ScriptBridgeCall("onLoad", "window.initializeSample()"))

    assertFalse(requireNotNull(response).isSuccess)
    assertEquals("javascript_executor_unavailable", requireNotNull(response).errorCode)
  }

  @Test
  fun `未知桥方法明确拒绝`() {
    val response = SampleWebViewExtension().scriptBridges.single()
      .handle(ScriptBridgeCall("executeNativeCode"))

    assertFalse(requireNotNull(response).isSuccess)
    assertEquals("unknown_method", requireNotNull(response).errorCode)
  }
}
