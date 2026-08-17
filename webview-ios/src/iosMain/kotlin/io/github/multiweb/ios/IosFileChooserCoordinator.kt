package io.github.multiweb.ios

import io.github.multiweb.extension.WebFileChooserRequest
import io.github.multiweb.extension.WebFileChooserResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL

/**
 * 协调 WebKit 文件面板请求与宿主完成回调。
 *
 * WebKit 同时只允许一个未完成的文件面板请求。新请求、释放或无宿主处理器时必须取消旧请求；已被替换请求
 * 的迟到回调不能影响当前请求。该类型不负责线程切换，调用方必须在主线程调用 [start]、[complete] 与 [dispose]。
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileChooserCoordinator {
  private var nextRequestId = 0L
  private var activeRequest: ActiveRequest? = null

  /**
   * 开始一个新的文件选择请求并返回其标识。
   *
   * 没有宿主处理器时立即取消新请求，避免 WebKit 继续等待或回退到未受控的系统选择器。
   */
  fun start(
    request: WebFileChooserRequest,
    hasHandler: Boolean,
    onCompleted: (selectedUris: List<String>?) -> Unit,
  ): Long {
    cancelActive()
    val requestId = nextRequestId++
    activeRequest = ActiveRequest(requestId, request, onCompleted)
    if (!hasHandler) {
      complete(requestId, WebFileChooserResult.Cancelled)
    }
    return requestId
  }

  /** 完成当前请求；没有活动请求时忽略迟到回调。 */
  fun complete(result: WebFileChooserResult) {
    activeRequest?.let { request ->
      complete(request.id, result)
    }
  }

  /** 仅在 [requestId] 仍对应活动请求时完成，防止已替换请求的重复或迟到回调越权。 */
  fun complete(
    requestId: Long,
    result: WebFileChooserResult,
  ) {
    val request = activeRequest
    if (request == null || request.id != requestId) {
      return
    }
    activeRequest = null
    request.onCompleted(result.toValidatedUris(request.request))
  }

  /** 释放时显式取消尚未完成的 WebKit 请求。 */
  fun dispose() {
    cancelActive()
  }

  private fun cancelActive() {
    val request = activeRequest ?: return
    activeRequest = null
    request.onCompleted(null)
  }

  /** iOS 仅将无 host 的绝对 `file://` URI 回传给 WebKit，远程或多余文件都会取消整次请求。 */
  private fun WebFileChooserResult.toValidatedUris(request: WebFileChooserRequest): List<String>? {
    val selected = this as? WebFileChooserResult.Selected ?: return null
    if (!request.allowMultipleSelection && selected.uris.size > 1) {
      return null
    }
    if (selected.uris.any(::isNotAbsoluteLocalFileUri)) {
      return null
    }
    return selected.uris
  }

  private fun isNotAbsoluteLocalFileUri(uri: String): Boolean {
    val url = NSURL(string = uri)
    return !url.isFileURL() ||
      url.host != null ||
      url.path?.startsWith("/") != true ||
      url.query != null ||
      url.fragment != null
  }

  private class ActiveRequest(
    val id: Long,
    val request: WebFileChooserRequest,
    val onCompleted: (selectedUris: List<String>?) -> Unit,
  )
}
