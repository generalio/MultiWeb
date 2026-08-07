package io.github.multiweb.android

import android.view.View
import io.github.multiweb.extension.WebViewExtension

/** Android WebView 的图层策略。 */
enum class AndroidWebViewLayerPolicy {
  /** 保留 [View] 的系统默认图层，不调用 [View.setLayerType]。适用于 WebGL、Canvas 与常规网页。 */
  SystemDefault,
  /** 强制软件图层；仅用于规避已确认的厂商 WebView 绘制问题。 */
  Software,
  /** 强制硬件图层；仅在宿主已验证目标设备兼容性时使用。 */
  Hardware,
}

/**
 * Android WebView 的可选图层配置。
 *
 * 该扩展只在 Android 生效，必须通过 [io.github.multiweb.extension.WebViewInitialization.extensions] 或
 * [AndroidWebViewController] 的 `extensions` 传入。未添加时控制器不会修改系统默认图层；每个控制器最多接受一个
 * 实例，避免扩展顺序隐式决定最终图层。
 */
data class AndroidWebViewLayerExtension(
  /** WebView 使用的图层策略；默认保留系统决定。 */
  val layerPolicy: AndroidWebViewLayerPolicy = AndroidWebViewLayerPolicy.SystemDefault,
) : WebViewExtension

/** 一个控制器只允许一份图层配置，避免扩展顺序隐式决定最终图层。 */
internal fun List<WebViewExtension>.singleAndroidWebViewLayerExtension(): AndroidWebViewLayerExtension? {
  val layerExtensions = filterIsInstance<AndroidWebViewLayerExtension>()
  require(layerExtensions.size <= 1) {
    "WebViewInitialization.extensions 最多只能配置一个 AndroidWebViewLayerExtension。"
  }
  return layerExtensions.singleOrNull()
}

/**
 * 将显式覆盖策略映射为 Android [View] 图层常量。
 *
 * [AndroidWebViewLayerPolicy.SystemDefault] 返回 `null`，使控制器完全不调用 [View.setLayerType]。
 */
internal fun AndroidWebViewLayerPolicy.toPlatformLayerTypeOrNull(): Int? {
  return when (this) {
    AndroidWebViewLayerPolicy.SystemDefault -> null
    AndroidWebViewLayerPolicy.Software -> View.LAYER_TYPE_SOFTWARE
    AndroidWebViewLayerPolicy.Hardware -> View.LAYER_TYPE_HARDWARE
  }
}
