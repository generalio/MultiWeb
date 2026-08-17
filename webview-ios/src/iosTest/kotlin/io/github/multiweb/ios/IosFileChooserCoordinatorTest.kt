package io.github.multiweb.ios

import io.github.multiweb.extension.WebFileChooserRequest
import io.github.multiweb.extension.WebFileChooserResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class IosFileChooserCoordinatorTest {
  @Test
  fun `缺少宿主处理器时立即取消请求`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    coordinator.start(request(), hasHandler = false) { selectedUris -> results += selectedUris }

    assertEquals<List<List<String>?>>(listOf(null), results)
  }

  @Test
  fun `仅接受绝对本地文件地址`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    coordinator.start(request(), hasHandler = true) { selectedUris -> results += selectedUris }
    coordinator.complete(WebFileChooserResult.Selected(listOf("file:///private/tmp/report.pdf")))

    assertEquals<List<List<String>?>>(listOf(listOf("file:///private/tmp/report.pdf")), results)
  }

  @Test
  fun `远程或非本地地址取消整个请求`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    listOf(
      "https://example.com/report.pdf",
      "content://documents/report.pdf",
      "report.pdf",
    ).forEach { uri ->
      coordinator.start(request(), hasHandler = true) { selectedUris -> results += selectedUris }
      coordinator.complete(WebFileChooserResult.Selected(listOf(uri)))
    }

    assertEquals<List<List<String>?>>(listOf(null, null, null), results)
  }

  @Test
  fun `带 host 的文件地址取消整个请求`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    listOf(
      "file://example.com/private/tmp/report.pdf",
      "file://localhost/private/tmp/report.pdf",
    ).forEach { uri ->
      coordinator.start(request(), hasHandler = true) { selectedUris -> results += selectedUris }
      coordinator.complete(WebFileChooserResult.Selected(listOf(uri)))
    }

    assertEquals<List<List<String>?>>(listOf(null, null), results)
  }

  @Test
  fun `不允许多选时超过一个文件取消请求`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    coordinator.start(request(allowMultipleSelection = false), hasHandler = true) { selectedUris ->
      results += selectedUris
    }
    coordinator.complete(
      WebFileChooserResult.Selected(
        listOf(
          "file:///private/tmp/first.pdf",
          "file:///private/tmp/second.pdf",
        ),
      ),
    )

    assertEquals<List<List<String>?>>(listOf(null), results)
  }

  @Test
  fun `同一请求的重复完成只回传一次`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    coordinator.start(request(), hasHandler = true) { selectedUris -> results += selectedUris }
    coordinator.complete(WebFileChooserResult.Cancelled)
    coordinator.complete(WebFileChooserResult.Selected(listOf("file:///private/tmp/report.pdf")))

    assertEquals<List<List<String>?>>(listOf(null), results)
  }

  @Test
  fun `后发请求会取消前一个请求且旧完成回调无效`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    val first = coordinator.start(request(), hasHandler = true) { selectedUris -> results += selectedUris }
    val second = coordinator.start(request(), hasHandler = true) { selectedUris -> results += selectedUris }
    coordinator.complete(first, WebFileChooserResult.Selected(listOf("file:///private/tmp/first.pdf")))
    coordinator.complete(second, WebFileChooserResult.Selected(listOf("file:///private/tmp/second.pdf")))

    assertEquals<List<List<String>?>>(
      listOf(
        null,
        listOf("file:///private/tmp/second.pdf"),
      ),
      results,
    )
  }

  @Test
  fun `释放时取消仍未完成的请求`() {
    val results = mutableListOf<List<String>?>()
    val coordinator = IosFileChooserCoordinator()

    coordinator.start(request(), hasHandler = true) { selectedUris -> results += selectedUris }
    coordinator.dispose()

    assertEquals<List<List<String>?>>(listOf(null), results)
  }

  private fun request(
    allowMultipleSelection: Boolean = true,
  ): WebFileChooserRequest {
    return WebFileChooserRequest(
      allowMultipleSelection = allowMultipleSelection,
      allowDirectories = false,
    )
  }
}
