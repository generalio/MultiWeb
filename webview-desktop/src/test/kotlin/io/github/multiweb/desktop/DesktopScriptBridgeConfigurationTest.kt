package io.github.multiweb.desktop

import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeResponse
import java.lang.reflect.Proxy
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefQueryCallback
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopScriptBridgeConfigurationTest {
  @Test
  fun `只允许 HTTPS 精确主机并生成受限注入脚本`() {
    val configuration = DesktopScriptBridgeConfiguration.create(
      listOf(bridge(hosts = setOf("EXAMPLE.com", "api.example.com"))),
    ).single()

    assertEquals(setOf("example.com", "api.example.com"), configuration.allowedHosts)
    assertEquals(true, configuration.isAllowedUrl("https://example.com/page"))
    assertEquals(false, configuration.isAllowedUrl("http://example.com/page"))
    assertEquals(false, configuration.isAllowedUrl("https://evil.example.com/page"))
    assertContains(configuration.injectionScript(), "__multiweb_query_multiWeb")
    assertContains(configuration.injectionScript(), "window.location.hostname")
    assertContains(configuration.injectionScript(), "JSON.parse(requestOrMethod)")
  }

  @Test
  fun `拒绝重复桥名称和不安全主机`() {
    assertFailsWith<IllegalArgumentException> {
      DesktopScriptBridgeConfiguration.create(
        listOf(bridge(), bridge()),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      DesktopScriptBridgeConfiguration.create(
        listOf(bridge(hosts = setOf("*.example.com"))),
      )
    }
  }

  @Test
  fun `原生侧拒绝未受信任来源并向受信任页面回传桥响应`() {
    val bridge = bridgeResponse("pong")
    val handler = DesktopScriptBridgeHandler(
      DesktopScriptBridgeConfiguration.create(listOf(bridge)).single(),
    )
    val trustedCallback = RecordingQueryCallback()

    assertTrue(
      handler.onQuery(
        browser = proxy(),
        frame = frame("https://example.com/page", isMain = true),
        queryId = 1L,
        request = "ping:request%20payload",
        persistent = false,
        callback = trustedCallback,
      ),
    )
    assertEquals("{\"isSuccess\":true,\"payload\":\"pong\",\"errorCode\":null}", trustedCallback.response)

    val rejectedCallback = RecordingQueryCallback()
    assertTrue(
      handler.onQuery(
        browser = proxy(),
        frame = frame("https://untrusted.example/page", isMain = true),
        queryId = 2L,
        request = "ping:request",
        persistent = false,
        callback = rejectedCallback,
      ),
    )
    assertEquals(403, rejectedCallback.failureCode)
    assertEquals("untrusted_origin", rejectedCallback.failureMessage)
  }

  @Test
  fun `方法门面使用独立内部传输对象`() {
    val configuration = DesktopScriptBridgeConfiguration.create(
      listOf(
        object : ScriptBridge {
          override val name = "AndroidWebView"
          override val transportName = "__multiweb_android_transport"
          override val allowedHosts = setOf("example.com")
          override val facade = ScriptBridgeFacade(setOf("getToken", "setFullscreen"))

          override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
        },
      ),
    ).single()

    assertEquals("__multiweb_query___multiweb_android_transport", configuration.queryFunction)
    assertContains(configuration.injectionScript(), "AndroidWebView")
    assertContains(configuration.injectionScript(), "__multiweb_android_transport")
    assertContains(configuration.injectionScript(), "nativeBridge.postMessage")
  }

  private fun bridge(
    name: String = "multiWeb",
    hosts: Set<String> = setOf("example.com"),
  ): ScriptBridge {
    return object : ScriptBridge {
      override val name = name
      override val allowedHosts = hosts

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
    }
  }

  private fun bridgeResponse(payload: String): ScriptBridge {
    return object : ScriptBridge {
      override val name = "multiWeb"
      override val allowedHosts = setOf("example.com")

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse {
        return ScriptBridgeResponse(isSuccess = true, payload = payload)
      }
    }
  }

  private fun frame(url: String, isMain: Boolean): CefFrame {
    return proxy { methodName ->
      when (methodName) {
        "getURL" -> url
        "isMain" -> isMain
        else -> null
      }
    }
  }

  private inline fun <reified T> proxy(
    noinline invocation: (String) -> Any? = { null },
  ): T {
    return Proxy.newProxyInstance(
      T::class.java.classLoader,
      arrayOf(T::class.java),
    ) { _, method, _ -> invocation(method.name) } as T
  }

  private class RecordingQueryCallback : CefQueryCallback {
    var response: String? = null
    var failureCode: Int? = null
    var failureMessage: String? = null

    override fun success(response: String) {
      this.response = response
    }

    override fun failure(errorCode: Int, errorMessage: String) {
      failureCode = errorCode
      failureMessage = errorMessage
    }
  }
}
