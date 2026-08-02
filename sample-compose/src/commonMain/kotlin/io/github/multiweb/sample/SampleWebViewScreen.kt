package io.github.multiweb.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.multiweb.api.WebViewController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 用于手动验证 MultiWeb 各平台实现的 Compose 示例界面。
 *
 * [webViewContent] 由平台入口提供，确保公共界面不依赖 Android View、UIKit 或 Swing 类型。
 */
@Composable
internal fun SampleWebViewScreen(
  controller: WebViewController,
  webViewContent: @Composable () -> Unit,
) {
  val presenter = remember(controller) { SampleWebViewPresenter(controller) }
  var uiState by remember(controller) { mutableStateOf(presenter.uiState) }

  LaunchedEffect(presenter) {
    while (isActive) {
      delay(250)
      if (presenter.refreshState()) {
        uiState = presenter.uiState
      }
    }
  }

  MaterialTheme {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        Button(onClick = {
          presenter.goBack()
          uiState = presenter.uiState
        }) {
          Text("返回")
        }
        Button(onClick = {
          presenter.goForward()
          uiState = presenter.uiState
        }) {
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
        Button(onClick = {
          presenter.stopLoading()
          uiState = presenter.uiState
        }) {
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
      Text("当前地址：${uiState.webViewState.url ?: "未加载"}")
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
      ) {
        webViewContent()
      }
    }
  }
}
