package io.github.multiweb.android

import android.webkit.WebSettings
import io.github.multiweb.extension.WebViewExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidWebViewCompatibilityExtensionTest {
  @Test
  fun `默认兼容扩展映射旧页面所需的全部 WebSettings`() {
    val settings = AndroidWebViewCompatibilityExtension().toAndroidWebViewCompatibilitySettings()

    assertEquals(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW, settings.mixedContentMode)
    assertFalse(settings.mediaPlaybackRequiresUserGesture)
    assertTrue(settings.useWideViewPort)
    assertTrue(settings.loadWithOverviewMode)
    assertTrue(settings.supportZoom)
    assertTrue(settings.builtInZoomControls)
    assertFalse(settings.displayZoomControls)
  }

  @Test
  fun `每种混合内容模式都会映射为对应平台常量`() {
    assertEquals(
      WebSettings.MIXED_CONTENT_NEVER_ALLOW,
      AndroidWebViewCompatibilityExtension(
        mixedContentMode = AndroidMixedContentMode.NeverAllow,
      ).toAndroidWebViewCompatibilitySettings().mixedContentMode,
    )
    assertEquals(
      WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE,
      AndroidWebViewCompatibilityExtension(
        mixedContentMode = AndroidMixedContentMode.CompatibilityMode,
      ).toAndroidWebViewCompatibilitySettings().mixedContentMode,
    )
  }

  @Test
  fun `未添加扩展时不会产生兼容设置`() {
    assertNull(emptyList<WebViewExtension>().singleAndroidWebViewCompatibilityExtension())
  }

  @Test
  fun `重复兼容扩展会被明确拒绝`() {
    val exception = assertFailsWith<IllegalArgumentException> {
      listOf<WebViewExtension>(
        AndroidWebViewCompatibilityExtension(),
        AndroidWebViewCompatibilityExtension(),
      ).singleAndroidWebViewCompatibilityExtension()
    }

    assertTrue(exception.message.orEmpty().contains("AndroidWebViewCompatibilityExtension"))
  }
}
