package io.github.multiweb.android

import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.ScriptBridgeWithFacade
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidScriptBridgeConfigurationTest {
  @Test
  fun `JavaScript 字符串会转义行与段分隔符`() {
    assertEquals("\"\\u2028\\u2029\"", "\u2028\u2029".toJavaScriptString())
  }

  @Test
  fun `受信任主机只生成 HTTPS 精确来源规则`() {
    val configuration = AndroidScriptBridgeConfiguration.create(
      listOf(bridge(name = "multiWebDevice", hosts = setOf("EXAMPLE.com", "api.example.com"))),
    ).single()

    assertEquals(
      setOf("https://example.com", "https://api.example.com"),
      configuration.allowedOriginRules,
    )
  }

  @Test
  fun `脚本执行仅接受可信 HTTPS 默认端口主文档`() {
    val allowedHosts = setOf("EXAMPLE.com")

    assertEquals(true, isTrustedJavaScriptUrl("https://example.com/page", allowedHosts))
    assertEquals(true, isTrustedJavaScriptUrl("https://example.com:443/page", allowedHosts))
    assertEquals(false, isTrustedJavaScriptUrl("https://example.com:8443/page", allowedHosts))
    assertEquals(false, isTrustedJavaScriptUrl("http://example.com/page", allowedHosts))
    assertEquals(false, isTrustedJavaScriptUrl("https://example.com/page", emptySet()))
  }

  @Test
  fun `拒绝不安全桥名称和通配符主机`() {
    assertFailsWith<IllegalArgumentException> {
      AndroidScriptBridgeConfiguration.create(listOf(bridge(name = "multi-web", hosts = setOf("example.com"))))
    }
    assertFailsWith<IllegalArgumentException> {
      AndroidScriptBridgeConfiguration.create(listOf(bridge(hosts = setOf("*.example.com"))))
    }
  }

  @Test
  fun `拒绝重复桥名称`() {
    assertFailsWith<IllegalArgumentException> {
      AndroidScriptBridgeConfiguration.create(
        listOf(
          bridge(name = "multiWeb", hosts = setOf("example.com")),
          bridge(name = "multiWeb", hosts = setOf("api.example.com")),
        ),
      )
    }
  }

  @Test
  fun `方法门面必须使用独立传输对象`() {
    assertFailsWith<IllegalArgumentException> {
      AndroidScriptBridgeConfiguration.create(
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

  @Test
  fun `方法门面使用独立传输对象并在文档开始阶段生成 Promise 调用`() {
    val configuration = AndroidScriptBridgeConfiguration.create(
      listOf(
        object : ScriptBridgeWithFacade {
          override val name = "AndroidWebView"
          override val transportName = "__multiweb_android_transport"
          override val allowedHosts = setOf("example.com")
          override val facade = ScriptBridgeFacade(setOf("getToken", "setFullscreen"))

          override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
        },
      ),
    ).single()

    assertEquals("__multiweb_android_transport", configuration.transportName)
    assertContains(requireNotNull(configuration.facadeInjectionScript()), "nativeBridge.postMessage")
    assertContains(requireNotNull(configuration.facadeInjectionScript()), "AndroidWebView")
    assertContains(requireNotNull(configuration.facadeInjectionScript()), "getToken")
  }

  @Test
  fun `方法门面只允许声明的方法`() {
    val configuration = AndroidScriptBridgeConfiguration.create(
      listOf(
        object : ScriptBridgeWithFacade {
          override val name = "AndroidWebView"
          override val transportName = "__multiweb_android_transport"
          override val allowedHosts = setOf("example.com")
          override val facade = ScriptBridgeFacade(setOf("getToken"))

          override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
        },
      ),
    ).single()

    assertEquals(true, configuration.isMethodAllowed("getToken"))
    assertEquals(false, configuration.isMethodAllowed("hiddenMethod"))
  }

  @Test
  fun `关闭 JavaScript 时不安装桥`() {
    val bridges = listOf(bridge(hosts = setOf("example.com")))

    assertEquals(false, shouldInstallScriptBridges(javaScriptEnabled = false, bridges))
    assertEquals(false, shouldInstallScriptBridges(javaScriptEnabled = true, emptyList()))
    assertEquals(true, shouldInstallScriptBridges(javaScriptEnabled = true, bridges))
  }

  private fun bridge(name: String = "multiWeb", hosts: Set<String>): ScriptBridge {
    return object : ScriptBridge {
      override val name = name
      override val allowedHosts = hosts

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
    }
  }
}
