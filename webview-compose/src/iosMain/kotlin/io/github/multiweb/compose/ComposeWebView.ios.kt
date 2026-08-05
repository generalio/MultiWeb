@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.multiweb.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.github.multiweb.api.WebViewController
import io.github.multiweb.extension.WebViewInitialization
import io.github.multiweb.ios.IosWebViewController

/** 创建并在离开组合时释放 iOS WKWebView 控制器。 */
@Composable
actual fun rememberWebViewController(
  initialization: WebViewInitialization,
  hostCallbacks: WebViewHostCallbacks,
): WebViewController {
  val currentCallbacks by rememberUpdatedState(hostCallbacks)
  val controller = remember(initialization) {
    IosWebViewController(
      initialization = initialization,
      onExternalNavigation = { request -> currentCallbacks.onExternalNavigation(request) },
    )
  }

  DisposableEffect(controller) {
    onDispose(controller::dispose)
  }
  return controller
}

/** 将公共控制器持有的 WKWebView 嵌入当前 Compose 布局。 */
@Composable
actual fun WebView(
  controller: WebViewController,
  modifier: Modifier,
) {
  val iosController = controller as? IosWebViewController
    ?: error("WebView 必须接收 rememberWebViewController 创建的 iOS 控制器。")
  UIKitView(
    factory = { iosController.view },
    modifier = modifier,
  )
}
