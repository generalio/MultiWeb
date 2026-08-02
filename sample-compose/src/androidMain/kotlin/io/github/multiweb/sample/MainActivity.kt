package io.github.multiweb.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
      val lifecycleOwner = this

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

      SampleWebViewScreen(controller) {
        AndroidView(
          factory = { controller.view },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}
