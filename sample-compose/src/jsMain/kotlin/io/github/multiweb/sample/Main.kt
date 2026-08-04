@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.multiweb.sample

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeViewport
import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.browser.BrowserWebViewController

/** JS 浏览器示例入口；页面加载操作会在新窗口或标签页打开目标 URL。 */
fun main() {
  ComposeViewport {
    val controller = remember {
      BrowserWebViewController(navigationPolicy = DefaultNavigationPolicy)
    }
    DisposableEffect(controller) {
      onDispose(controller::dispose)
    }
    SampleWebViewScreen(
      controller = controller,
      hostCapabilityNotice = "当前浏览器新窗口模式不支持原生全屏和图片保存桥。",
    ) {}
  }
}
