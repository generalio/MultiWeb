package io.github.multiweb.android

import android.webkit.WebSettings
import io.github.multiweb.extension.WebViewExtension

/** Android WebView 的混合内容处理模式。 */
enum class AndroidMixedContentMode {
  /** 禁止 HTTPS 页面加载 HTTP 子资源，保持默认安全行为。 */
  NeverAllow,
  /** 仅允许系统 WebView 认为兼容的 HTTP 子资源，具体范围由 Android System WebView 决定。 */
  CompatibilityMode,
  /** 允许 HTTPS 页面加载任意 HTTP 子资源，仅用于迁移无法修改的旧页面。 */
  AlwaysAllow,
}

/**
 * 旧网页显示兼容配置。
 *
 * 该扩展只在 Android 生效，必须通过 [io.github.multiweb.extension.WebViewInitialization.extensions] 或
 * [AndroidWebViewController] 的 `extensions` 传入。未添加时控制器保持 `0.2.1` 的安全默认值；每个控制器最多
 * 接受一个实例。默认值面向需要混合资源、自动播放、宽视口和缩放的旧网页，其中混合内容会降低 HTTPS 页面安全性。
 */
data class AndroidWebViewCompatibilityExtension(
  /** HTTPS 页面加载 HTTP 子资源时采用的模式；默认 [AndroidMixedContentMode.AlwaysAllow]。 */
  val mixedContentMode: AndroidMixedContentMode = AndroidMixedContentMode.AlwaysAllow,
  /** 是否要求媒体播放先经过用户手势；默认 `false` 以兼容旧页面自动播放。 */
  val mediaPlaybackRequiresUserGesture: Boolean = false,
  /** 是否启用宽视口布局；默认 `true`。 */
  val useWideViewPort: Boolean = true,
  /** 是否按概览模式缩放页面；默认 `true`。 */
  val loadWithOverviewMode: Boolean = true,
  /** 是否允许页面缩放；默认 `true`。 */
  val supportZoom: Boolean = true,
  /** 是否启用内建缩放手势；默认 `true`。 */
  val builtInZoomControls: Boolean = true,
  /** 是否显示屏幕上的缩放按钮；默认 `false`。 */
  val displayZoomControls: Boolean = false,
) : WebViewExtension

/** Android 原生 [WebSettings] 所需的兼容设置快照，便于在不创建 WebView 的单测中校验映射。 */
internal data class AndroidWebViewCompatibilitySettings(
  /** 对应 [WebSettings.mixedContentMode] 的平台常量。 */
  val mixedContentMode: Int,
  /** 对应 [WebSettings.mediaPlaybackRequiresUserGesture]。 */
  val mediaPlaybackRequiresUserGesture: Boolean,
  /** 对应 [WebSettings.useWideViewPort]。 */
  val useWideViewPort: Boolean,
  /** 对应 [WebSettings.loadWithOverviewMode]。 */
  val loadWithOverviewMode: Boolean,
  /** 对应 [WebSettings.setSupportZoom]。 */
  val supportZoom: Boolean,
  /** 对应 [WebSettings.builtInZoomControls]。 */
  val builtInZoomControls: Boolean,
  /** 对应 [WebSettings.displayZoomControls]。 */
  val displayZoomControls: Boolean,
)

/** 将公开的兼容参数映射为 Android System WebView 的设置值。 */
internal fun AndroidWebViewCompatibilityExtension.toAndroidWebViewCompatibilitySettings():
  AndroidWebViewCompatibilitySettings {
  return AndroidWebViewCompatibilitySettings(
    mixedContentMode = mixedContentMode.toPlatformMixedContentMode(),
    mediaPlaybackRequiresUserGesture = mediaPlaybackRequiresUserGesture,
    useWideViewPort = useWideViewPort,
    loadWithOverviewMode = loadWithOverviewMode,
    supportZoom = supportZoom,
    builtInZoomControls = builtInZoomControls,
    displayZoomControls = displayZoomControls,
  )
}

/** 一个控制器只允许一份显示兼容配置，避免扩展顺序隐式决定最终 WebSettings。 */
internal fun List<WebViewExtension>.singleAndroidWebViewCompatibilityExtension():
  AndroidWebViewCompatibilityExtension? {
  val compatibilityExtensions = filterIsInstance<AndroidWebViewCompatibilityExtension>()
  require(compatibilityExtensions.size <= 1) {
    "WebViewInitialization.extensions 最多只能配置一个 AndroidWebViewCompatibilityExtension。"
  }
  return compatibilityExtensions.singleOrNull()
}

/** 将跨平台枚举映射为 Android 平台常量，禁止把未知值静默降级。 */
private fun AndroidMixedContentMode.toPlatformMixedContentMode(): Int {
  return when (this) {
    AndroidMixedContentMode.NeverAllow -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
    AndroidMixedContentMode.CompatibilityMode -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    AndroidMixedContentMode.AlwaysAllow -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
  }
}
