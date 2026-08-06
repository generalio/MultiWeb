package io.github.multiweb.sample

import io.github.multiweb.api.WebRequest
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState

/** 示例项目首次打开的 HTTPS 页面，符合默认导航策略。 */
internal const val DefaultSampleUrl = "https://app.redrock.team"

/** 页面尚未提供标题时展示的样例标题。 */
internal const val DefaultSamplePageTitle = "网页"

/**
 * 计算样例标题栏应展示的标题。
 *
 * 平台可能在首帧或加载失败时尚未提供页面标题，空白标题也不适合作为窗口标题，因此统一回退到
 * [DefaultSamplePageTitle]。保留有效标题的原始内容，避免改变网页声明的名称。
 */
internal fun samplePageTitle(state: WebViewState): String {
  return state.title?.takeIf { title -> title.isNotBlank() } ?: DefaultSamplePageTitle
}

/**
 * 示例界面需要展示的浏览器状态。
 *
 * 将输入地址、控制器快照和操作异常集中在普通 Kotlin 对象中，使平台无关的命令逻辑可脱离
 * Compose 与原生 WebView 进行测试。
 */
internal data class SampleWebViewUiState(
  /** 地址栏当前值；加载操作会使用该值创建 [WebRequest]。 */
  val urlInput: String = DefaultSampleUrl,
  /** 最近一次操作后读取到的控制器状态快照。 */
  val webViewState: WebViewState = WebViewState(),
  /** 最近一次操作失败的中文错误信息；成功操作后清除。 */
  val actionError: String? = null,
)

/**
 * Compose 示例的平台无关操作协调器。
 *
 * 原生控制器的异步回调由 [WebViewStateObservable] 发布，并由 Compose 界面传给
 * [updateWebViewState]；每次用户命令后仍会读取一次快照，以兼容未实现该可选能力的第三方控制器。
 */
internal class SampleWebViewPresenter(
  private val controller: WebViewController,
) {
  /** 供界面渲染的最新状态。 */
  var uiState: SampleWebViewUiState = SampleWebViewUiState(webViewState = controller.state)
    private set

  /** 更新地址栏，不会立即触发页面加载。 */
  fun updateUrlInput(url: String) {
    uiState = uiState.copy(urlInput = url)
  }

  /** 使用地址栏内容加载页面。 */
  fun load() = runControllerAction {
    controller.load(WebRequest(uiState.urlInput.trim()))
  }

  /** 首次展示示例时加载预置地址，避免原生 WebView 因未收到导航请求而呈现空白内容。 */
  fun loadInitialPage() = load()

  /** 请求原生控制器刷新当前页面。 */
  fun reload() = runControllerAction(controller::reload)

  /** 请求原生控制器返回历史记录。 */
  fun goBack() = runControllerAction(controller::goBack)

  /** 请求原生控制器前进历史记录。 */
  fun goForward() = runControllerAction(controller::goForward)

  /** 请求原生控制器停止当前加载。 */
  fun stopLoading() = runControllerAction(controller::stopLoading)

  /** 请求原生控制器清理其支持范围内的会话数据。 */
  fun clearSession() = runControllerAction(controller::clearSession)

  /** 接收控制器发布的异步状态快照，不会改变地址栏输入或操作错误提示。 */
  fun updateWebViewState(state: WebViewState) {
    uiState = uiState.copy(webViewState = state)
  }

  private fun runControllerAction(action: () -> Unit) {
    try {
      action()
      uiState = uiState.copy(
        webViewState = controller.state,
        actionError = null,
      )
    } catch (error: UnsupportedOperationException) {
      uiState = uiState.copy(
        webViewState = controller.state,
        actionError = error.message ?: "当前平台不支持该操作。",
      )
    }
  }
}
