package io.github.multiweb.android

import android.content.Intent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.extension.NativeWebViewBridgeExtension
import io.github.multiweb.extension.NativeWebViewBridgeHost
import io.github.multiweb.extension.NativeWebViewBridgeResult
import io.github.multiweb.extension.OriginPolicyAwareScriptBridge
import io.github.multiweb.extension.PageFinishedEvent
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeFacade
import io.github.multiweb.extension.ScriptBridgeOriginPolicy
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.ScriptBridgeWithFacade
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
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
    val activity = launchWebViewActivity()
    lateinit var webView: WebView
    instrumentation.runOnMainSync {
      webView = WebView(activity).apply {
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
        activity.setContentView(this)
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
      destroyWebView(activity, webView)
    }
  }

  @Test
  fun 不安全来源策略允许顶层Https页面调用受限桥() {
    assertUnsafeBridgeCanBeCalled("https://legacy.example/")
  }

  @Test
  fun 不安全来源策略允许顶层Http页面调用受限桥() {
    assertUnsafeBridgeCanBeCalled("http://legacy.example/")
  }

  @Test
  fun 不安全来源策略不会在子框架文件或数据页面创建业务门面() {
    assertFacadeUnavailable("file:///android_asset/index.html")
    assertFacadeUnavailable(baseUrl = null)
    assertIframeFacadeUnavailable()
    assertUnsafeTransportCannotCallBridge("file:///android_asset/index.html")
    assertUnsafeTransportCannotCallBridge(baseUrl = null)
    assertIframeTransportCannotCallBridge()
  }

  @Test
  fun 不安全来源策略会在Http主文档执行旧桥onLoad和exeJs() {
    assumeBridgeFeaturesSupported()
    val scriptCompleted = CountDownLatch(1)
    val result = AtomicReference<String>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = launchWebViewActivity()
    lateinit var controller: AndroidWebViewController
    val bridge = NativeWebViewBridgeExtension(
      originPolicy = ScriptBridgeOriginPolicy.UnsafeAnyHttpOrHttps,
      host = NativeWebViewBridgeHost { NativeWebViewBridgeResult.Failure("unsupported_operation") },
      enableLegacyJavaScriptExecution = true,
    )
    val verificationExtension = object : io.github.multiweb.extension.WebViewExtension {
      override fun onPageFinished(event: PageFinishedEvent) {
        controller.view.postDelayed({
          controller.view.evaluateJavascript(
            "window.__onLoadExecuted === true && window.__exeJsExecuted === true",
          ) { value ->
            result.set(value)
            scriptCompleted.countDown()
          }
        }, 100)
      }
    }
    instrumentation.runOnMainSync {
      controller = AndroidWebViewController(
        context = activity,
        config = WebViewConfig(javaScriptEnabled = true),
        navigationPolicy = NavigationPolicy { NavigationDecision.Allow },
        extensions = listOf(bridge, verificationExtension),
      )
      activity.setContentView(controller.view)
      controller.view.loadDataWithBaseURL(
        "http://legacy.example/",
        "<script>window.AndroidWebView.onLoad('window.__onLoadExecuted = true');" +
          "window.AndroidWebView.exeJs('window.__exeJsExecuted = true');</script>",
        "text/html",
        "utf-8",
        null,
      )
    }

    try {
      assertTrue("未完成旧桥脚本执行检查", scriptCompleted.await(10, TimeUnit.SECONDS))
      assertEquals("true", result.get())
    } finally {
      instrumentation.runOnMainSync {
        activity.setContentView(FrameLayout(activity))
        controller.dispose()
        activity.finish()
      }
    }
  }

  private fun launchWebViewActivity(): WebViewTestActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return instrumentation.startActivitySync(
      Intent(instrumentation.targetContext, WebViewTestActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as WebViewTestActivity
  }

  private fun assertUnsafeBridgeCanBeCalled(baseUrl: String) {
    assumeBridgeFeaturesSupported()
    val bridgeCalled = CountDownLatch(1)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = launchWebViewActivity()
    lateinit var webView: WebView
    instrumentation.runOnMainSync {
      webView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        AndroidScriptBridgeInstaller.install(
          webView = this,
          javaScriptEnabled = true,
          bridges = listOf(unsafeBridge { call ->
            if (call.method == "ping" && call.payload == "from-page") {
              bridgeCalled.countDown()
            }
          }),
        )
        activity.setContentView(this)
        loadDataWithBaseURL(
          baseUrl,
          "<script>window.AndroidWebView.ping('from-page');</script>",
          "text/html",
          "utf-8",
          null,
        )
      }
    }

    try {
      assertTrue("顶层页面未能调用不安全兼容桥：$baseUrl", bridgeCalled.await(10, TimeUnit.SECONDS))
    } finally {
      destroyWebView(activity, webView)
    }
  }

  private fun assertFacadeUnavailable(baseUrl: String?) {
    assumeBridgeFeaturesSupported()
    val evaluationCompleted = CountDownLatch(1)
    val result = AtomicReference<String>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = launchWebViewActivity()
    lateinit var webView: WebView
    instrumentation.runOnMainSync {
      webView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        AndroidScriptBridgeInstaller.install(
          webView = this,
          javaScriptEnabled = true,
          bridges = listOf(unsafeBridge()),
        )
        webViewClient = object : WebViewClient() {
          override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript("window.AndroidWebView === undefined") { value ->
              result.set(value)
              evaluationCompleted.countDown()
            }
          }
        }
        activity.setContentView(this)
        if (baseUrl == null) {
          loadData("<html><body>data page</body></html>", "text/html", "utf-8")
        } else {
          loadDataWithBaseURL(baseUrl, "<html><body>file page</body></html>", "text/html", "utf-8", null)
        }
      }
    }

    try {
      assertTrue("未完成门面检查：${baseUrl ?: "data:"}", evaluationCompleted.await(10, TimeUnit.SECONDS))
      assertEquals("true", result.get())
    } finally {
      destroyWebView(activity, webView)
    }
  }

  private fun assertIframeFacadeUnavailable() {
    assumeBridgeFeaturesSupported()
    val evaluationCompleted = CountDownLatch(1)
    val result = AtomicReference<String>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = launchWebViewActivity()
    lateinit var webView: WebView
    instrumentation.runOnMainSync {
      webView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        AndroidScriptBridgeInstaller.install(
          webView = this,
          javaScriptEnabled = true,
          bridges = listOf(unsafeBridge()),
        )
        webChromeClient = object : WebChromeClient() {
          override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
            val prefix = "multiweb-child-facade:"
            if (consoleMessage.message().startsWith(prefix)) {
              result.set(consoleMessage.message().removePrefix(prefix))
              evaluationCompleted.countDown()
            }
            return super.onConsoleMessage(consoleMessage)
          }
        }
        activity.setContentView(this)
        loadDataWithBaseURL(
          "https://legacy.example/",
          "<iframe srcdoc=\"<script>console.log('multiweb-child-facade:' + " +
            "(window.AndroidWebView === undefined));</script>\"></iframe>",
          "text/html",
          "utf-8",
          null,
        )
      }
    }

    try {
      assertTrue("未完成子框架门面检查", evaluationCompleted.await(10, TimeUnit.SECONDS))
      assertEquals("true", result.get())
    } finally {
      destroyWebView(activity, webView)
    }
  }

  /**
   * `*` 规则下底层传输对象可能由 AndroidX WebKit 安装；即使网页绕过公开门面直接发消息，原生侧也必须拒绝。
   */
  private fun assertUnsafeTransportCannotCallBridge(baseUrl: String?) {
    assumeBridgeFeaturesSupported()
    val bridgeCalled = CountDownLatch(1)
    val responseReceived = CountDownLatch(1)
    val responseErrorCode = AtomicReference<String>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = launchWebViewActivity()
    lateinit var webView: WebView
    instrumentation.runOnMainSync {
      webView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        AndroidScriptBridgeInstaller.install(
          webView = this,
          javaScriptEnabled = true,
          bridges = listOf(unsafeBridge { bridgeCalled.countDown() }),
        )
        webChromeClient = transportResponseWebChromeClient(responseErrorCode, responseReceived)
        activity.setContentView(this)
        val page = rawTransportCallPage()
        if (baseUrl == null) {
          loadData(page, "text/html", "utf-8")
        } else {
          loadDataWithBaseURL(baseUrl, page, "text/html", "utf-8", null)
        }
      }
    }

    try {
      assertTrue(
        "非 HTTP(S) 主文档未收到内部通道拒绝响应：${baseUrl ?: "data:"}",
        responseReceived.await(10, TimeUnit.SECONDS),
      )
      assertEquals("untrusted_origin", responseErrorCode.get())
      assertEquals(1L, bridgeCalled.count)
    } finally {
      destroyWebView(activity, webView)
    }
  }

  /** 子框架即使直接调用内部传输对象，也不能抵达宿主桥实现。 */
  private fun assertIframeTransportCannotCallBridge() {
    assumeBridgeFeaturesSupported()
    val bridgeCalled = CountDownLatch(1)
    val responseReceived = CountDownLatch(1)
    val responseErrorCode = AtomicReference<String>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = launchWebViewActivity()
    lateinit var webView: WebView
    instrumentation.runOnMainSync {
      webView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        AndroidScriptBridgeInstaller.install(
          webView = this,
          javaScriptEnabled = true,
          bridges = listOf(unsafeBridge { bridgeCalled.countDown() }),
        )
        webChromeClient = transportResponseWebChromeClient(responseErrorCode, responseReceived)
        activity.setContentView(this)
        loadDataWithBaseURL(
          "https://legacy.example/",
          "<iframe srcdoc=\"${rawTransportCallPage()}\"></iframe>",
          "text/html",
          "utf-8",
          null,
        )
      }
    }

    try {
      assertTrue("子框架未收到内部通道拒绝响应", responseReceived.await(10, TimeUnit.SECONDS))
      assertEquals("untrusted_origin", responseErrorCode.get())
      assertEquals(1L, bridgeCalled.count)
    } finally {
      destroyWebView(activity, webView)
    }
  }

  private fun destroyWebView(activity: WebViewTestActivity, webView: WebView) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      activity.setContentView(FrameLayout(activity))
      webView.destroy()
      activity.finish()
    }
  }

  private fun rawTransportCallPage(): String {
    return "<script>" +
      "var transport=window.__multiweb_android_transport;" +
      "if(!transport||typeof transport.postMessage!=='function'){" +
      "console.log('multiweb-transport-response:unavailable');}" +
      "else{transport.onmessage=function(event){try{" +
      "console.log('multiweb-transport-response:'+JSON.parse(event.data).errorCode);}" +
      "catch(_){console.log('multiweb-transport-response:invalid_response');}};" +
      "transport.postMessage(JSON.stringify({id:'blocked',method:'ping',payload:'blocked'}));}" +
      "</script>"
  }

  /** 捕获内部消息通道对测试请求的响应，避免仅按固定延迟判断请求是否已送达原生侧。 */
  private fun transportResponseWebChromeClient(
    responseErrorCode: AtomicReference<String>,
    responseReceived: CountDownLatch,
  ): WebChromeClient {
    return object : WebChromeClient() {
      override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val prefix = "multiweb-transport-response:"
        if (consoleMessage.message().startsWith(prefix)) {
          responseErrorCode.set(consoleMessage.message().removePrefix(prefix))
          responseReceived.countDown()
        }
        return super.onConsoleMessage(consoleMessage)
      }
    }
  }

  private fun unsafeBridge(onCall: (ScriptBridgeCall) -> Unit = {}): ScriptBridgeWithFacade {
    return object : ScriptBridgeWithFacade, OriginPolicyAwareScriptBridge {
      override val name: String = "AndroidWebView"
      override val transportName: String = "__multiweb_android_transport"
      override val allowedHosts: Set<String> = emptySet()
      override val originPolicy: ScriptBridgeOriginPolicy = ScriptBridgeOriginPolicy.UnsafeAnyHttpOrHttps
      override val facade: ScriptBridgeFacade = ScriptBridgeFacade(setOf("ping"))

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse {
        onCall(call)
        return ScriptBridgeResponse(isSuccess = true)
      }
    }
  }

  private fun assumeBridgeFeaturesSupported() {
    assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
  }
}
