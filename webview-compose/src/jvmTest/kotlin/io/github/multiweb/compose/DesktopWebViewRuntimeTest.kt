package io.github.multiweb.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopWebViewRuntimeTest {

  @Test
  fun `macOS 且宿主未配置时启用混合互操作`() {
    var configuredValue: String? = null

    prepareMacosComposeInterop(
      operatingSystemName = "Mac OS X",
      currentBlendingValue = null,
      setBlendingValue = { configuredValue = it },
    )

    assertEquals("true", configuredValue)
  }

  @Test
  fun `不会覆盖宿主已配置的混合互操作值`() {
    var configuredValue: String? = null

    prepareMacosComposeInterop(
      operatingSystemName = "Mac OS X",
      currentBlendingValue = "false",
      setBlendingValue = { configuredValue = it },
    )

    assertNull(configuredValue)
  }

  @Test
  fun `非 macOS 不配置混合互操作`() {
    var configuredValue: String? = null

    prepareMacosComposeInterop(
      operatingSystemName = "Linux",
      currentBlendingValue = null,
      setBlendingValue = { configuredValue = it },
    )

    assertNull(configuredValue)
  }
}
