package io.github.multiweb.api

/** Cross-platform browser contract. Platform modules own the native view lifecycle. */
interface WebViewController {
  val state: WebViewState

  fun load(request: WebRequest)

  fun reload()

  fun goBack()

  fun goForward()

  fun stopLoading()

  fun clearSession()

  fun dispose()
}

data class WebRequest(
  val url: String,
  val headers: Map<String, String> = emptyMap(),
)

data class WebViewState(
  val url: String? = null,
  val title: String? = null,
  val loadingProgress: Float = 0f,
  val isLoading: Boolean = false,
  val canGoBack: Boolean = false,
  val canGoForward: Boolean = false,
  val error: WebError? = null,
)

data class WebError(
  val category: WebErrorCategory,
  val description: String,
  val failingUrl: String? = null,
)

enum class WebErrorCategory {
  Navigation,
  Network,
  Ssl,
  RenderProcess,
  Unknown,
}

