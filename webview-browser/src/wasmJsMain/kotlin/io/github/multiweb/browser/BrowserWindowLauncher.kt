package io.github.multiweb.browser

import kotlinx.browser.window

/** 使用新标签页打开地址，并请求浏览器隔离 opener 关系。 */
internal actual fun openUrlInNewBrowserWindow(url: String) {
  window.open(url, "_blank", "noopener,noreferrer")
}
