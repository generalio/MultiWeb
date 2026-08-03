package io.github.multiweb.android

import android.content.Context
import android.webkit.WebView

/**
 * Android 原生 WebView 创建器。
 *
 * 宿主可通过它注入自己的 [WebView] 子类，例如增加无障碍、埋点或绘制适配；控制器仍会统一安装安全
 * 配置和内部 Client，调用方不得在创建器中设置 `WebViewClient`、`WebChromeClient` 或销毁 WebView。
 */
fun interface AndroidWebViewFactory {
  /** 创建尚未附着到视图层级的 WebView 实例。 */
  fun create(context: Context): WebView
}

/** 使用系统默认 [WebView] 的创建器。 */
val DefaultAndroidWebViewFactory = AndroidWebViewFactory(::WebView)
