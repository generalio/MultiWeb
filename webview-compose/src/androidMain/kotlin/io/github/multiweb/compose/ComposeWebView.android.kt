package io.github.multiweb.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.multiweb.android.AndroidWebViewController
import io.github.multiweb.api.WebViewController
import io.github.multiweb.extension.WebViewInitialization

/**
 * 创建并绑定 Android WebView 的 Compose 生命周期。
 *
 * 控制器要求主线程调用；Compose Android 的组合与 Lifecycle 回调均位于主线程。页面离开组合时会自动
 * 释放 WebView，宿主无需再手动调用 `onHostPause`、`onHostResume` 或 `dispose`。
 */
@Composable
actual fun rememberWebViewController(
  initialization: WebViewInitialization,
  hostCallbacks: WebViewHostCallbacks,
): WebViewController {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentCallbacks by rememberUpdatedState(hostCallbacks)
  val controller = remember(context, initialization, lifecycleOwner) {
    AndroidWebViewController(
      context = context,
      initialization = initialization,
      onExternalNavigation = { request -> currentCallbacks.onExternalNavigation(request) },
    )
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
  return controller
}

/** 将公共控制器持有的系统 WebView 嵌入当前 Compose 布局。 */
@Composable
actual fun WebView(
  controller: WebViewController,
  modifier: Modifier,
) {
  val androidController = controller as? AndroidWebViewController
    ?: error("WebView 必须接收 rememberWebViewController 创建的 Android 控制器。")
  AndroidView(
    factory = { androidController.view },
    modifier = modifier,
  )
}
