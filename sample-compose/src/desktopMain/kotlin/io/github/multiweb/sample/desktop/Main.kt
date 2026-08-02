package io.github.multiweb.sample.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.desktop.DesktopWebViewController
import io.github.multiweb.sample.SampleWebViewScreen
import me.friwi.jcefmaven.CefAppBuilder

/** 桌面示例入口；JCEF 首次启动会按 jcefmaven 配置准备原生运行时。 */
fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "MultiWeb Compose 示例",
  ) {
    val cefApp = remember { CefAppBuilder().build() }
    val controller = remember {
      DesktopWebViewController(
        cefApp = cefApp,
        config = WebViewConfig(
          javaScriptEnabled = true,
          thirdPartyCookiesEnabled = true,
          persistentSessionEnabled = true,
        ),
        navigationPolicy = DefaultNavigationPolicy,
      )
    }

    DisposableEffect(controller) {
      onDispose(controller::dispose)
    }

    SampleWebViewScreen(controller) {
      SwingPanel(
        factory = { controller.view },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
