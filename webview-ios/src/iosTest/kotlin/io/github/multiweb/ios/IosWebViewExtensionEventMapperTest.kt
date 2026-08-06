package io.github.multiweb.ios

import io.github.multiweb.extension.DownloadRequest
import io.github.multiweb.extension.WebContextAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IosWebViewExtensionEventMapperTest {
  @Test
  fun `下载响应会保留 WebKit 提供的文件信息`() {
    val request = IosWebViewExtensionEventMapper.downloadRequest(
      url = "https://example.com/report.pdf",
      suggestedFileName = "report.pdf",
      mimeType = "application/pdf",
      contentLength = 1024L,
    )

    assertEquals(
      DownloadRequest(
        url = "https://example.com/report.pdf",
        suggestedFileName = "report.pdf",
        mimeType = "application/pdf",
        contentLength = 1024L,
      ),
      request,
    )
  }

  @Test
  fun `未知文件长度转换为空值且缺少地址时不派发下载`() {
    val unknownLengthRequest = IosWebViewExtensionEventMapper.downloadRequest(
      url = "https://example.com/file",
      suggestedFileName = null,
      mimeType = null,
      contentLength = -1L,
    )

    assertNull(unknownLengthRequest?.contentLength)
    assertNull(
      IosWebViewExtensionEventMapper.downloadRequest(
        url = null,
        suggestedFileName = "file",
        mimeType = "application/octet-stream",
        contentLength = 0L,
      ),
    )
    assertNull(
      IosWebViewExtensionEventMapper.downloadRequest(
        url = "   ",
        suggestedFileName = "file",
        mimeType = "application/octet-stream",
        contentLength = 0L,
      ),
    )
  }

  @Test
  fun `上下文菜单只将非空链接转换为链接长按事件`() {
    val action = IosWebViewExtensionEventMapper.contextAction("https://example.com/link")

    assertEquals("https://example.com/link", assertIs<WebContextAction.LinkLongPressed>(action).url)
    assertNull(IosWebViewExtensionEventMapper.contextAction(null))
    assertNull(IosWebViewExtensionEventMapper.contextAction(""))
  }
}
