package io.github.multiweb.desktop

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 保护 JCEF 文件对话框的一次性完成语义。
 *
 * 宿主选择器可能在任意线程完成，而控制器销毁也会同时取消请求。该守卫确保同一个原生回调只会收到一次继续或
 * 取消通知，避免 JCEF 在关闭浏览器后继续使用失效的回调。
 */
internal class DesktopFileChooserCallbackGuard(
  private val onCancelled: () -> Unit,
  private val onSelected: (List<String>) -> Unit,
) {
  private val completed = AtomicBoolean(false)

  /** 显式取消当前请求；已完成的请求不会重复通知原生回调。 */
  fun cancel() {
    if (completed.compareAndSet(false, true)) {
      onCancelled()
    }
  }

  /** 回传已校验的本地路径；`null` 表示当前结果无效或用户取消。 */
  fun complete(selectedPaths: List<String>?) {
    if (!completed.compareAndSet(false, true)) {
      return
    }
    if (selectedPaths == null) {
      onCancelled()
    } else {
      onSelected(selectedPaths)
    }
  }
}
