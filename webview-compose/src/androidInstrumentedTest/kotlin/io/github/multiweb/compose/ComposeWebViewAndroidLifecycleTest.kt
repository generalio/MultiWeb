package io.github.multiweb.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.multiweb.api.WebViewController
import io.github.multiweb.extension.WebViewInitialization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ComposeWebViewAndroidLifecycleTest {
  @get:org.junit.Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `LifecycleOwner 更换时会创建新的控制器`() {
    var lifecycleOwner by mutableStateOf<LifecycleOwner?>(null)
    val controllers = mutableListOf<WebViewController>()

    composeRule.setContent {
      lifecycleOwner?.let { owner ->
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
          val controller = rememberWebViewController(WebViewInitialization())
          SideEffect {
            if (controllers.lastOrNull() !== controller) {
              controllers += controller
            }
          }
        }
      }
    }

    composeRule.runOnIdle {
      lifecycleOwner = TestLifecycleOwner()
    }
    composeRule.waitForIdle()

    composeRule.runOnIdle {
      lifecycleOwner = TestLifecycleOwner()
    }
    composeRule.waitForIdle()

    assertEquals(2, controllers.size)
    assertNotSame(controllers[0], controllers[1])
  }
}

/** 为 Compose 重组提供可替换的已恢复生命周期宿主。 */
private class TestLifecycleOwner : LifecycleOwner {
  override val lifecycle = LifecycleRegistry(this).apply {
    currentState = Lifecycle.State.RESUMED
  }
}
