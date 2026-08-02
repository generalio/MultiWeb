package io.github.multiweb.testing

import io.github.multiweb.api.NavigationDecision
import io.github.multiweb.api.NavigationPolicy
import io.github.multiweb.api.NavigationRequest
import io.github.multiweb.api.WebError
import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState

/**
 * 用于契约测试的内存 WebView 控制器。
 *
 * 页面加载会立即完成，不模拟网络、渲染进程或原生视图生命周期。测试可通过 [operations]
 * 断言控制器接收到的命令，并通过 [state] 验证导航后的状态。
 */
class FakeWebViewController(
  /** 决定每次主框架导航的处理方式。默认允许所有地址，便于测试调用方逻辑。 */
  private val navigationPolicy: NavigationPolicy = NavigationPolicy { NavigationDecision.Allow },
) : WebViewController {
  private val history = mutableListOf<WebRequest>()
  private var currentHistoryIndex = -1

  /** 按调用顺序记录的控制器操作。 */
  val operations = mutableListOf<FakeWebViewOperation>()

  /** 是否已调用 [clearSession]。 */
  var sessionCleared: Boolean = false
    private set

  /** 是否已调用 [dispose]。销毁后执行其他命令会抛出 [IllegalStateException]。 */
  var isDisposed: Boolean = false
    private set

  override var state: WebViewState = WebViewState()
    private set

  override fun load(request: WebRequest) {
    ensureUsable()

    val decision = navigationPolicy.decide(
      NavigationRequest(
        url = request.url,
        isMainFrame = true,
        isUserInitiated = false,
      ),
    )
    operations += FakeWebViewOperation.Load(request, decision)

    if (decision != NavigationDecision.Allow) {
      return
    }

    while (history.lastIndex > currentHistoryIndex) {
      history.removeAt(history.lastIndex)
    }
    history += request
    currentHistoryIndex = history.lastIndex
    updateStateForCurrentPage()
  }

  override fun reload() {
    ensureUsable()
    operations += FakeWebViewOperation.Reload
    updateStateForCurrentPage()
  }

  override fun goBack() {
    ensureUsable()
    operations += FakeWebViewOperation.GoBack
    if (currentHistoryIndex > 0) {
      currentHistoryIndex -= 1
      updateStateForCurrentPage()
    }
  }

  override fun goForward() {
    ensureUsable()
    operations += FakeWebViewOperation.GoForward
    if (currentHistoryIndex < history.lastIndex) {
      currentHistoryIndex += 1
      updateStateForCurrentPage()
    }
  }

  override fun stopLoading() {
    ensureUsable()
    operations += FakeWebViewOperation.StopLoading
    state = state.copy(isLoading = false)
  }

  override fun clearSession() {
    ensureUsable()
    operations += FakeWebViewOperation.ClearSession
    sessionCleared = true
  }

  override fun dispose() {
    if (isDisposed) {
      return
    }
    operations += FakeWebViewOperation.Dispose
    isDisposed = true
  }

  /** 模拟当前页面加载失败，用于验证调用方的错误展示与恢复逻辑。 */
  fun failLoading(error: WebError) {
    ensureUsable()
    operations += FakeWebViewOperation.FailLoading(error)
    state = state.copy(
      isLoading = false,
      error = error,
    )
  }

  private fun updateStateForCurrentPage() {
    val currentRequest = history.getOrNull(currentHistoryIndex)
    state = WebViewState(
      url = currentRequest?.url,
      loadingProgress = if (currentRequest == null) 0f else 1f,
      isLoading = false,
      canGoBack = currentHistoryIndex > 0,
      canGoForward = currentHistoryIndex in 0 until history.lastIndex,
    )
  }

  private fun ensureUsable() {
    check(!isDisposed) { "FakeWebViewController 已销毁，不能继续执行操作。" }
  }
}

/** 用于断言 [FakeWebViewController] 接收命令的操作记录。 */
sealed interface FakeWebViewOperation {
  /** 请求加载页面及其对应的导航决策。 */
  data class Load(
    /** 原始页面请求。 */
    val request: WebRequest,
    /** 导航策略返回的处理方式。 */
    val decision: NavigationDecision,
  ) : FakeWebViewOperation

  /** 请求重新加载当前页面。 */
  data object Reload : FakeWebViewOperation

  /** 请求返回上一条历史记录。 */
  data object GoBack : FakeWebViewOperation

  /** 请求前进到下一条历史记录。 */
  data object GoForward : FakeWebViewOperation

  /** 请求停止页面加载。 */
  data object StopLoading : FakeWebViewOperation

  /** 请求清理会话数据。 */
  data object ClearSession : FakeWebViewOperation

  /** 请求释放控制器资源。 */
  data object Dispose : FakeWebViewOperation

  /** 模拟页面加载失败。 */
  data class FailLoading(
    /** 测试指定的错误信息。 */
    val error: WebError,
  ) : FakeWebViewOperation
}
