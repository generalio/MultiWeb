package io.github.multiweb.android

import io.github.multiweb.api.WebViewConfig

/**
 * Android 系统 WebView 的固定安全设置快照。
 *
 * DOM Storage 不属于 [WebViewConfig] 的放宽权限开关：它是现代 HTTPS 单页应用访问同源
 * `localStorage`/`sessionStorage` 的基础能力。控制器会在 [AndroidWebViewController.clearSession] 中统一清理，
 * 避免残留数据绕过会话清理语义。
 */
internal data class AndroidWebViewSettings(
  /** 是否允许执行页面 JavaScript。 */
  val javaScriptEnabled: Boolean,
  /** 是否启用同源 DOM Storage，以支持依赖 localStorage 的页面初始化。 */
  val domStorageEnabled: Boolean = true,
  /** 是否允许加载本地文件。 */
  val fileAccessEnabled: Boolean,
  /** 是否允许 HTTP 子资源混入 HTTPS 页面。 */
  val mixedContentAllowed: Boolean = false,
  /** 是否允许页面在无用户操作时播放媒体。 */
  val mediaPlaybackWithoutUserGestureAllowed: Boolean = false,
)

/** 将跨平台配置映射为 Android 固定的安全设置，未建模能力保持最小权限默认值。 */
internal fun WebViewConfig.toAndroidWebViewSettings(): AndroidWebViewSettings {
  return AndroidWebViewSettings(
    javaScriptEnabled = javaScriptEnabled,
    fileAccessEnabled = fileAccessEnabled,
  )
}
