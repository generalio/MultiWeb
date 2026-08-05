package io.github.multiweb.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFileChooserCallbackGuardTest {
  @Test
  fun `完成后销毁取消不会重复通知原生回调`() {
    var cancellationCount = 0
    val selectedPaths = mutableListOf<List<String>>()
    val guard = DesktopFileChooserCallbackGuard(
      onCancelled = { cancellationCount++ },
      onSelected = selectedPaths::add,
    )

    guard.complete(listOf("/tmp/report.pdf"))
    guard.cancel()
    guard.complete(listOf("/tmp/another.pdf"))

    assertEquals(0, cancellationCount)
    assertEquals(listOf(listOf("/tmp/report.pdf")), selectedPaths)
  }

  @Test
  fun `取消后宿主迟到的完成结果会被忽略`() {
    var cancellationCount = 0
    val selectedPaths = mutableListOf<List<String>>()
    val guard = DesktopFileChooserCallbackGuard(
      onCancelled = { cancellationCount++ },
      onSelected = selectedPaths::add,
    )

    guard.cancel()
    guard.complete(listOf("/tmp/report.pdf"))

    assertEquals(1, cancellationCount)
    assertEquals(emptyList(), selectedPaths)
  }
}
