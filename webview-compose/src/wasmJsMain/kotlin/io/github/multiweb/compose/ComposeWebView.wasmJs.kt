package io.github.multiweb.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.multiweb.api.WebViewController
import io.github.multiweb.browser.BrowserWebViewController
import io.github.multiweb.extension.WebViewInitialization

/** WasmJS 平台仅创建会在浏览器新窗口中打开页面的控制器。 */
@Composable
actual fun rememberWebViewController(
  initialization: WebViewInitialization,
  hostCallbacks: WebViewHostCallbacks,
): WebViewController {
  val controller = remember(initialization) {
    BrowserWebViewController(
      config = initialization.webViewConfig,
      navigationPolicy = initialization.navigationPolicy,
    )
  }
  DisposableEffect(controller) {
    onDispose(controller::dispose)
  }
  return controller
}

/** WasmJS 浏览器没有可由组件嵌入的 WebView，此处有意不绘制内容。 */
@Composable
actual fun WebView(
  controller: WebViewController,
  modifier: Modifier,
) = Unit
