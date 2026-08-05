package io.github.multiweb.ios

import io.github.multiweb.extension.DownloadRequest
import io.github.multiweb.extension.WebContextAction

/**
 * 将 WebKit 可提供的下载与上下文信息转换为公共扩展事件。
 *
 * 该映射不执行文件下载、权限申请或文件保存；这些需要业务宿主根据 [DownloadRequest] 自行完成。
 * `WKContextMenuElementInfo` 仅公开链接地址，因此 iOS 不会伪造图片长按事件。
 */
internal object IosWebViewExtensionEventMapper {
  /** 将不可渲染响应的元数据转换为下载请求；缺少有效地址时不派发事件。 */
  fun downloadRequest(
    url: String?,
    suggestedFileName: String?,
    mimeType: String?,
    contentLength: Long,
  ): DownloadRequest? {
    val downloadUrl = url?.takeIf(String::isNotBlank) ?: return null
    return DownloadRequest(
      url = downloadUrl,
      suggestedFileName = suggestedFileName,
      mimeType = mimeType,
      contentLength = contentLength.takeIf { it >= 0L },
    )
  }

  /** 将 WebKit 原生上下文菜单公开的非空链接转换为长按事件。 */
  fun contextAction(linkUrl: String?): WebContextAction? {
    return linkUrl
      ?.takeIf(String::isNotBlank)
      ?.let(WebContextAction::LinkLongPressed)
  }
}
