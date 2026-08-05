package io.github.multiweb.ios

import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.ScriptBridgeWithFacade
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosScriptBridgeConfigurationTest {
  @Test
  fun `只允许 HTTPS 默认端口并允许显式 443`() {
    val configuration = IosScriptBridgeConfiguration.create(listOf(bridge())).single()

    assertTrue(configuration.isAllowedUrl("https://example.com/page"))
    assertTrue(configuration.isAllowedUrl("https://example.com:443/page"))
    assertFalse(configuration.isAllowedUrl("https://example.com:8443/page"))
    assertContains(
      configuration.injectionScript(),
      "window.location.port !== '' && window.location.port !== '443'",
    )
    assertContains(configuration.injectionScript(), "window.location.hostname === \"example.com\"")
  }

  @Test
  fun `JavaScript 字符串会转义行与段分隔符`() {
    assertEquals("\"\\u2028\\u2029\"", "\u2028\u2029".toJavaScriptString())
  }

  @Test
  fun `方法门面必须使用独立传输对象`() {
    assertFailsWith<IllegalArgumentException> {
      IosScriptBridgeConfiguration.create(
        listOf(
          object : ScriptBridgeWithFacade {
            override val name = "AndroidWebView"
            override val transportName = "AndroidWebView"
            override val allowedHosts = setOf("example.com")
            override val facade = ScriptBridgeFacade(setOf("getToken"))

            override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
          },
        ),
      )
    }
  }

  private fun bridge(): ScriptBridge {
    return object : ScriptBridge {
      override val name: String = "multiWeb"
      override val allowedHosts: Set<String> = setOf("example.com")

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
    }
  }
}
