package io.github.multiweb.android

import io.github.multiweb.api.WebViewConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidWebViewSettingsTest {
  @Test
  fun `DOM Storage 保持启用以支持现代 HTTPS 单页应用`() {
    val settings = WebViewConfig(javaScriptEnabled = true).toAndroidWebViewSettings()

    assertTrue(settings.javaScriptEnabled)
    assertTrue(settings.domStorageEnabled)
  }

  @Test
  fun `DOM Storage 不会放宽文件混合内容和自动播放限制`() {
    val settings = WebViewConfig().toAndroidWebViewSettings()

    assertTrue(settings.domStorageEnabled)
    assertFalse(settings.fileAccessEnabled)
    assertFalse(settings.mixedContentAllowed)
    assertFalse(settings.mediaPlaybackWithoutUserGestureAllowed)
  }
}
