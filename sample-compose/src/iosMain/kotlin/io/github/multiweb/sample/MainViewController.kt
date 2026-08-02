@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.multiweb.sample

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.ios.IosWebViewController
import platform.UIKit.UIViewController

/** iOS 宿主可调用此函数获取包含 WKWebView 示例的 UIViewController。 */
fun MainViewController(): UIViewController = ComposeUIViewController {
  val controller = remember {
    IosWebViewController(
      config = WebViewConfig(javaScriptEnabled = true),
      navigationPolicy = DefaultNavigationPolicy,
    )
  }

  DisposableEffect(controller) {
    onDispose(controller::dispose)
  }

  SampleWebViewScreen(controller) {
    UIKitView(
      factory = { controller.view },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
