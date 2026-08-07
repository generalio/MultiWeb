package io.github.multiweb.android

import android.view.View
import io.github.multiweb.extension.WebViewExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidWebViewLayerExtensionTest {
  @Test
  fun `默认图层策略不覆盖系统默认图层`() {
    val extension = AndroidWebViewLayerExtension()

    assertEquals(AndroidWebViewLayerPolicy.SystemDefault, extension.layerPolicy)
    assertNull(extension.layerPolicy.toPlatformLayerTypeOrNull())
  }

  @Test
  fun `显式图层策略映射 Android View 常量`() {
    assertEquals(View.LAYER_TYPE_SOFTWARE, AndroidWebViewLayerPolicy.Software.toPlatformLayerTypeOrNull())
    assertEquals(View.LAYER_TYPE_HARDWARE, AndroidWebViewLayerPolicy.Hardware.toPlatformLayerTypeOrNull())
  }

  @Test
  fun `未添加图层扩展时保持空配置`() {
    assertNull(emptyList<WebViewExtension>().singleAndroidWebViewLayerExtension())
  }

  @Test
  fun `重复图层扩展会被明确拒绝`() {
    val exception = assertFailsWith<IllegalArgumentException> {
      listOf<WebViewExtension>(
        AndroidWebViewLayerExtension(),
        AndroidWebViewLayerExtension(AndroidWebViewLayerPolicy.Software),
      ).singleAndroidWebViewLayerExtension()
    }

    assertTrue(exception.message.orEmpty().contains("AndroidWebViewLayerExtension"))
  }
}
