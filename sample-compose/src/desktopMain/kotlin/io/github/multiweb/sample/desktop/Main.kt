package io.github.multiweb.sample.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.desktop.DesktopWebViewController
import io.github.multiweb.sample.SampleWebViewScreen
import java.io.File
import me.friwi.jcefmaven.CefAppBuilder

/** 桌面示例入口；JCEF 首次启动会按 jcefmaven 配置准备原生运行时。 */
fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "MultiWeb Compose 示例",
  ) {
    val cefApp = remember {
      CefAppBuilder().apply {
        // JCEF 原生运行时体积较大，放入用户目录以便多次启动复用，避免污染项目工作区。
        setInstallDir(jcefInstallDirectory())
        configureCefUserDataDirectory()
      }.build()
    }
    val controller = remember {
      DesktopWebViewController(
        cefApp = cefApp,
        config = WebViewConfig(
          javaScriptEnabled = true,
          thirdPartyCookiesEnabled = true,
          persistentSessionEnabled = true,
        ),
        navigationPolicy = DefaultNavigationPolicy,
      )
    }

    DisposableEffect(controller) {
      onDispose(controller::dispose)
    }

    SampleWebViewScreen(controller) {
      SwingPanel(
        factory = { controller.view },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

/** 返回示例专用的 JCEF 原生运行时缓存目录；版本升级时由 jcefmaven 自动校验并重新安装。 */
private fun jcefInstallDirectory(): File {
  return File(System.getProperty("user.home"), ".multiweb/jcef")
}

/** 配置示例独立的 CEF 用户数据目录，避免与其他 CEF 应用竞争默认进程锁。 */
private fun CefAppBuilder.configureCefUserDataDirectory() {
  val rootDirectory = File(System.getProperty("user.home"), ".multiweb/cef-user-data")
  cefSettings.root_cache_path = rootDirectory.absolutePath
  // 持久化 Cookie 依赖磁盘缓存路径，且 profile 目录必须位于根目录内。
  cefSettings.cache_path = File(rootDirectory, "default-profile").absolutePath
  cefSettings.persist_session_cookies = true
}
