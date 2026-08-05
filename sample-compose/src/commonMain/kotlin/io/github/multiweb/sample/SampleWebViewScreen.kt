package io.github.multiweb.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.multiweb.api.WebViewController
import io.github.multiweb.api.WebViewState
import io.github.multiweb.api.WebViewStateObservable
import io.github.multiweb.compose.WebView
import io.github.multiweb.compose.rememberWebViewController
import io.github.multiweb.extension.WebViewInitialization
import io.github.multiweb.sample.generated.resources.NotoSansCJKsc_Regular
import io.github.multiweb.sample.generated.resources.Res
import org.jetbrains.compose.resources.Font

/**
 * 示例界面使用的跨平台中文字体，避免 Wasm Skia 默认字体缺少中文字形。
 *
 * 字体资源采用 OFL 授权的 Noto Sans CJK 简体中文 Regular，桌面、Android、iOS
 * 与 Wasm 共用同一份资源，保证控件文案在各平台均能正常显示。
 */
@Composable
private fun sampleFontFamily(): FontFamily = FontFamily(
  Font(Res.font.NotoSansCJKsc_Regular),
)

/**
 * 创建并显示 MultiWeb 示例的公共 Compose 页面。
 *
 * Controller 创建和原生 WebView 渲染均通过 common 的 Compose API 完成。平台入口只注入系统能力，例如 Android
 * 返回键、iOS 状态栏、图片保存以及 Desktop JCEF 运行时；不得传入 Android View、UIKit 或 Swing 组件。
 */
@Composable
internal fun SampleWebViewApp(
  initialization: WebViewInitialization,
  isFullscreen: Boolean = false,
  pendingImageSaveUrl: String? = null,
  hostCapabilityNotice: String? = null,
  onImageSaveConfirmed: (String) -> Unit = {},
  onImageSaveDismissed: () -> Unit = {},
  onWebViewStateChanged: (WebViewState) -> Unit = {},
  onWebViewControllerReady: (WebViewController) -> Unit = {},
) {
  val controller = rememberWebViewController(initialization)

  DisposableEffect(controller) {
    onWebViewControllerReady(controller)
    onDispose {}
  }

  SampleWebViewScreen(
    controller = controller,
    isFullscreen = isFullscreen,
    pendingImageSaveUrl = pendingImageSaveUrl,
    hostCapabilityNotice = hostCapabilityNotice,
    onImageSaveConfirmed = onImageSaveConfirmed,
    onImageSaveDismissed = onImageSaveDismissed,
    onWebViewStateChanged = onWebViewStateChanged,
  )
}

/** 显示示例工具栏及由公共 Compose API 承载的 WebView。 */
@Composable
private fun SampleWebViewScreen(
  controller: WebViewController,
  isFullscreen: Boolean,
  pendingImageSaveUrl: String?,
  hostCapabilityNotice: String?,
  onImageSaveConfirmed: (String) -> Unit,
  onImageSaveDismissed: () -> Unit,
  onWebViewStateChanged: (WebViewState) -> Unit,
) {
  val sampleFontFamily = sampleFontFamily()
  val presenter = remember(controller) { SampleWebViewPresenter(controller) }
  var uiState by remember(controller) { mutableStateOf(presenter.uiState) }
  val stateFlow = (controller as? WebViewStateObservable)?.stateFlow
  val observedState = stateFlow?.collectAsState()
  val controllerState = observedState?.value ?: controller.state

  LaunchedEffect(presenter) {
    presenter.loadInitialPage()
    uiState = presenter.uiState
  }

  LaunchedEffect(controllerState) {
    presenter.updateWebViewState(controllerState)
    uiState = presenter.uiState
  }

  LaunchedEffect(uiState.webViewState) {
    onWebViewStateChanged(uiState.webViewState)
  }

  MaterialTheme(
    typography = MaterialTheme.typography.copy(
      bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = sampleFontFamily),
      bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = sampleFontFamily),
      bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = sampleFontFamily),
      labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = sampleFontFamily),
      labelMedium = MaterialTheme.typography.labelMedium.copy(fontFamily = sampleFontFamily),
      labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = sampleFontFamily),
      titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = sampleFontFamily),
      titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = sampleFontFamily),
      titleSmall = MaterialTheme.typography.titleSmall.copy(fontFamily = sampleFontFamily),
    ),
  ) {
    pendingImageSaveUrl?.let { imageUrl ->
      AlertDialog(
        onDismissRequest = onImageSaveDismissed,
        title = { Text("保存图片") },
        text = { Text("确认将受信任网页请求的图片保存到本机吗？") },
        confirmButton = {
          Button(onClick = { onImageSaveConfirmed(imageUrl) }) {
            Text("保存")
          }
        },
        dismissButton = {
          Button(onClick = onImageSaveDismissed) {
            Text("取消")
          }
        },
      )
    }
    if (isFullscreen) {
      Box(modifier = Modifier.fillMaxSize()) {
        WebView(controller, Modifier.fillMaxSize())
      }
    } else {
      Column(
        modifier = Modifier
          .fillMaxSize()
        .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            enabled = uiState.webViewState.canGoBack,
            onClick = {
              presenter.goBack()
              uiState = presenter.uiState
            },
          ) {
            Text("返回")
          }
          Text(
            text = samplePageTitle(uiState.webViewState),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        OutlinedTextField(
          value = uiState.urlInput,
          onValueChange = { url ->
            presenter.updateUrlInput(url)
            uiState = presenter.uiState
          },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("页面地址") },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            enabled = uiState.webViewState.canGoBack,
            onClick = {
              presenter.goBack()
              uiState = presenter.uiState
            },
          ) {
            Text("返回")
          }
          Button(
            enabled = uiState.webViewState.canGoForward,
            onClick = {
              presenter.goForward()
              uiState = presenter.uiState
            },
          ) {
            Text("前进")
          }
          Button(onClick = {
            presenter.reload()
            uiState = presenter.uiState
          }) {
            Text("刷新")
          }
          Button(onClick = {
            presenter.load()
            uiState = presenter.uiState
          }) {
            Text("加载")
          }
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            enabled = uiState.webViewState.isLoading,
            onClick = {
              presenter.stopLoading()
              uiState = presenter.uiState
            },
          ) {
            Text("停止")
          }
          Button(onClick = {
            presenter.clearSession()
            uiState = presenter.uiState
          }) {
            Text("清理会话")
          }
        }
        uiState.actionError?.let { error ->
          Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
          )
        }
        uiState.webViewState.error?.let { error ->
          Text(
            text = "页面错误：${error.description}",
            color = MaterialTheme.colorScheme.error,
          )
        }
        hostCapabilityNotice?.let { notice ->
          Text(
            text = notice,
            color = MaterialTheme.colorScheme.secondary,
          )
        }
        if (uiState.webViewState.isLoading) {
          LinearProgressIndicator(
            progress = { uiState.webViewState.loadingProgress },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        Text("当前地址：${uiState.webViewState.url ?: "未加载"}")
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        ) {
          WebView(controller, Modifier.fillMaxSize())
        }
      }
    }
  }
}
