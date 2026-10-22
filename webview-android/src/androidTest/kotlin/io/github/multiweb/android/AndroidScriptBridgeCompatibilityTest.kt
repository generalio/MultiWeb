package io.github.multiweb.android

import android.content.Intent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import io.github.multiweb.extension.OriginPolicyAwareScriptBridge
import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeOriginPolicy
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.ScriptBridgeWithFacade
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 在真实 WebView 上强制缺失能力，避免模拟器内核较新而漏掉兼容分支。 */
class AndroidScriptBridgeCompatibilityTest {
  @Test
  fun 文档开始脚本不支持时安装桥不抛异常() {
    withSession(listOf(bridge()), ::withoutDocumentStart) { session ->
      assertTrue(session.deferredFacades.isNotEmpty())
    }
  }

  @Test
  fun 消息通道不支持时安装桥不抛异常且网页正常加载() {
    withSession(listOf(bridge()), { false }) { session ->
      assertTrue(session.deferredFacades.isEmpty())
      assertEquals("ok:true", session.load("https://trusted.example/", "'ok:' + (window.TrustedBridge === undefined)"))
    }
  }

  @Test
  fun 无桥或关闭JavaScript时不检查平台能力() {
    onMain {
      val webView = WebView(InstrumentationRegistry.getInstrumentation().targetContext)
      try {
        val unexpectedCheck: (String) -> Boolean = { error("不应检查能力：$it") }
        AndroidScriptBridgeInstaller.install(webView, true, emptyList(), unexpectedCheck)
        AndroidScriptBridgeInstaller.install(webView, false, listOf(bridge()), unexpectedCheck)
      } finally {
        webView.destroy()
      }
    }
  }

  @Test
  fun 无门面消息桥不检查文档开始脚本能力() {
    val plainBridge = object : ScriptBridge {
      override val name = "PlainBridge"
      override val allowedHosts = setOf("trusted.example")
      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? = null
    }
    withSession(listOf(plainBridge), { feature ->
      assertEquals(WebViewFeature.WEB_MESSAGE_LISTENER, feature)
      WebViewFeature.isFeatureSupported(feature)
    }) { session ->
      assertTrue(session.deferredFacades.isEmpty())
      assertEquals("function", session.load("https://trusted.example/", "typeof PlainBridge.postMessage"))
    }
  }

  @Test
  fun 降级门面可以完成真实原生调用及Promise回复() {
    withSession(listOf(bridge()), ::withoutDocumentStart) { session ->
      assertEquals(
        "reply",
        session.load("https://trusted.example/", "TrustedBridge.ping('reply').then(function(r){return r.payload;})"),
      )
    }
  }

  @Test
  fun 降级门面在导航后重新注入且重复注入不破坏进行中的Promise() {
    withSession(listOf(bridge()), ::withoutDocumentStart) { session ->
      val script = session.deferredFacades.single()
      val expression = "(function(){var pending=TrustedBridge.ping('pending');" +
        script + script + "return pending.then(function(r){return r.payload;});})()"
      assertEquals("pending", session.load("https://trusted.example/first", expression))
      assertEquals(
        "second",
        session.load(
          "https://trusted.example/second",
          "TrustedBridge.ping('second').then(function(r){return r.payload;})",
        ),
      )
    }
  }

  @Test
  fun 降级精确桥拒绝HTTP其他主机及非默认端口() {
    withSession(listOf(bridge()), ::withoutDocumentStart) { session ->
      for (url in listOf("http://trusted.example/", "https://other.example/", "https://trusted.example:8443/")) {
        // 伪造传输对象，确保测试检查的是脚本自己的来源守卫，而非仅依赖原生通道未安装。
        assertEquals("true", session.load(url, "window.TrustedBridge === undefined", fakeTransportPage))
      }
      assertEquals(
        "443",
        session.load("https://trusted.example:443/", "TrustedBridge.ping('443').then(function(r){return r.payload;})"),
      )
    }
  }

  @Test
  fun 降级脚本按实际文档来源拒绝旧回调对应的可信主机() {
    withSession(listOf(bridge()), ::withoutDocumentStart) { session ->
      assertEquals(
        "first",
        session.load("https://trusted.example/", "TrustedBridge.ping('first').then(function(r){return r.payload;})"),
      )
      // 与控制器共用的注入入口不信任回调 URL，直接在当前不可信文档运行脚本仍应拒绝。
      assertEquals(
        "true",
        session.load("https://untrusted.example/", "window.TrustedBridge === undefined", fakeTransportPage),
      )
    }
  }

  @Test
  fun 降级不安全兼容桥允许HTTP和HTTPS主文档() {
    withSession(listOf(unsafeBridge()), ::withoutDocumentStart) { session ->
      for (url in listOf("http://legacy.example/", "https://legacy.example/")) {
        assertEquals("legacy", session.load(url, "TrustedBridge.ping('legacy').then(function(r){return r.payload;})"))
      }
    }
  }

  @Test
  fun 降级不安全兼容桥拒绝file及data文档() {
    withSession(listOf(unsafeBridge()), ::withoutDocumentStart) { session ->
      assertEquals(
        "true",
        session.load("file:///android_asset/page.html", "window.TrustedBridge === undefined", fakeTransportPage),
      )
      assertEquals("true", session.load(null, "window.TrustedBridge === undefined", fakeTransportPage))
    }
  }

  @Test
  fun 降级门面不会在同源子框架创建() {
    withSession(listOf(bridge()), ::withoutDocumentStart) { session ->
      val script = session.deferredFacades.single().toJavaScriptString()
      val expression = "(function(){var frame=document.querySelector('iframe').contentWindow;" +
        "frame.__trusted_bridge_transport={postMessage:function(){}};frame.eval($script);" +
        "return frame.TrustedBridge === undefined && typeof TrustedBridge.ping === 'function';})()"
      assertEquals(
        "true",
        session.load("https://trusted.example/", expression, "<iframe srcdoc='<p>child</p>'></iframe>"),
      )
    }
  }

  @Test
  fun 支持文档开始脚本时继续提前安装且没有延后脚本() {
    withSession(listOf(bridge()), WebViewFeature::isFeatureSupported) { session ->
      assertTrue(session.deferredFacades.isEmpty())
      assertEquals(
        "true",
        session.load(
          "https://trusted.example/",
          "window.bridgeWasReady",
          "<script>window.bridgeWasReady=typeof TrustedBridge.ping === 'function';</script>",
        ),
      )
    }
  }

  private fun withoutDocumentStart(feature: String): Boolean {
    return feature != WebViewFeature.DOCUMENT_START_SCRIPT && WebViewFeature.isFeatureSupported(feature)
  }

  private fun bridge(): ScriptBridgeWithFacade = object : ScriptBridgeWithFacade {
    override val name = "TrustedBridge"
    override val transportName = "__trusted_bridge_transport"
    override val allowedHosts = setOf("trusted.example")
    override val facade = ScriptBridgeFacade(setOf("ping"))
    override fun handle(call: ScriptBridgeCall) = ScriptBridgeResponse(isSuccess = true, payload = call.payload)
  }

  private fun unsafeBridge(): ScriptBridgeWithFacade = object : ScriptBridgeWithFacade, OriginPolicyAwareScriptBridge {
    override val name = "TrustedBridge"
    override val transportName = "__trusted_bridge_transport"
    override val allowedHosts = emptySet<String>()
    override val originPolicy = ScriptBridgeOriginPolicy.UnsafeAnyHttpOrHttps
    override val facade = ScriptBridgeFacade(setOf("ping"))
    override fun handle(call: ScriptBridgeCall) = ScriptBridgeResponse(isSuccess = true, payload = call.payload)
  }

  private fun withSession(
    bridges: List<ScriptBridge>,
    features: (String) -> Boolean,
    test: (Session) -> Unit,
  ) {
    val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
      Intent(InstrumentationRegistry.getInstrumentation().targetContext, WebViewTestActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as WebViewTestActivity
    val session = onMain { Session(activity, bridges, features) }
    try {
      test(session)
    } finally {
      onMain { session.close() }
    }
  }

  /** 与控制器共用注入入口，在真实主文档回调中执行降级脚本并等待网页侧结果。 */
  private class Session(
    private val activity: WebViewTestActivity,
    bridges: List<ScriptBridge>,
    features: (String) -> Boolean,
  ) {
    private val view = WebView(activity)
    val deferredFacades: List<String>
    private var resultReady = CountDownLatch(1)
    private var result = ""
    private var expression = ""

    init {
      view.settings.javaScriptEnabled = true
      deferredFacades = AndroidScriptBridgeInstaller.install(view, true, bridges, features)
      view.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
          if (consoleMessage.message().startsWith("compat-result:")) {
            result = consoleMessage.message().removePrefix("compat-result:")
            resultReady.countDown()
          }
          return true
        }
      }
      view.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
          AndroidScriptBridgeInstaller.injectDeferredFacades(view, deferredFacades)
        }

        override fun onPageFinished(view: WebView, url: String?) {
          AndroidScriptBridgeInstaller.injectDeferredFacades(view, deferredFacades)
          view.evaluateJavascript(
            "Promise.resolve().then(function(){return ($expression);})" +
              ".then(function(value){console.log('compat-result:'+String(value));})" +
              ".catch(function(error){console.log('compat-result:ERROR:'+String(error));});",
            null,
          )
        }
      }
      activity.setContentView(view)
    }

    fun load(url: String?, expression: String, html: String = "<p>page</p>"): String {
      onMain {
        this.expression = expression
        resultReady = CountDownLatch(1)
        if (url == null) {
          view.loadData(html, "text/html", "utf-8")
        } else {
          view.loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
        }
      }
      assertTrue("网页未完成兼容验证：$url", resultReady.await(10, TimeUnit.SECONDS))
      assertFalse("网页脚本执行失败：$result", result.startsWith("ERROR:"))
      return result
    }

    fun close() {
      activity.setContentView(FrameLayout(activity))
      view.destroy()
      activity.finish()
    }
  }

  companion object {
    private const val fakeTransportPage =
      "<script>window.__trusted_bridge_transport={postMessage:function(){}};</script>"

    private fun <T> onMain(block: () -> T): T {
      val result = AtomicReference<Result<T>>()
      InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
      return result.get().getOrThrow()
    }
  }
}
