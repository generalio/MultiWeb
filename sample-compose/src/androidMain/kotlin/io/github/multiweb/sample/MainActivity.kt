package io.github.multiweb.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.multiweb.android.AndroidWebViewController
import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.WebViewConfig

/** Android 平台示例入口，负责把系统 WebView 接入 Compose 生命周期。 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val controller = remember {
        AndroidWebViewController(
          context = applicationContext,
          config = WebViewConfig(javaScriptEnabled = true),
          navigationPolicy = DefaultNavigationPolicy,
        )
      }
      var canGoBack by remember(controller) { mutableStateOf(controller.state.canGoBack) }
      val lifecycleOwner = this

      // 仅在存在网页历史时拦截系统返回键；无历史时交回 Activity 的默认退出逻辑。
      BackHandler(enabled = canGoBack) {
        controller.goBack()
      }

      DisposableEffect(controller, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
          override fun onPause(owner: LifecycleOwner) {
            controller.onHostPause()
          }

          override fun onResume(owner: LifecycleOwner) {
            controller.onHostResume()
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
          lifecycleOwner.lifecycle.removeObserver(observer)
          controller.dispose()
        }
      }

      SampleWebViewScreen(
        controller = controller,
        onWebViewStateChanged = { state ->
          canGoBack = state.canGoBack
        },
      ) {
        AndroidView(
          factory = { controller.view },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}
