package io.github.multiweb.android

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.ScriptBridgeWithFacade
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 验证 Android System WebView 对精确 HTTPS 来源规则的实际桥接行为。 */
class AndroidScriptBridgeInstrumentationTest {
  @Test
  fun 精确Https来源可以通过SystemWebView调用受限桥() {
    assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))

    val bridgeCalled = CountDownLatch(1)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var webView: WebView
    instrumentation.runOnMainSync {
      webView = WebView(instrumentation.targetContext).apply {
        settings.javaScriptEnabled = true
        AndroidScriptBridgeInstaller.install(
          webView = this,
          javaScriptEnabled = true,
          bridges = listOf(
            object : ScriptBridgeWithFacade {
              override val name: String = "TrustedBridge"
              override val transportName: String = "__multiweb_trusted_bridge"
              override val allowedHosts: Set<String> = setOf("trusted.example")
              override val facade: ScriptBridgeFacade = ScriptBridgeFacade(setOf("ping"))

              override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse {
                if (call.method == "ping" && call.payload == "from-page") {
                  bridgeCalled.countDown()
                }
                return ScriptBridgeResponse(isSuccess = true)
              }
            },
          ),
        )
        loadDataWithBaseURL(
          "https://trusted.example/",
          "<script>window.TrustedBridge.ping('from-page');</script>",
          "text/html",
          "utf-8",
          null,
        )
      }
    }

    try {
      assertTrue("受信任 HTTPS 页面未能调用 Android System WebView 桥", bridgeCalled.await(10, TimeUnit.SECONDS))
    } finally {
      instrumentation.runOnMainSync { webView.destroy() }
    }
  }
}
