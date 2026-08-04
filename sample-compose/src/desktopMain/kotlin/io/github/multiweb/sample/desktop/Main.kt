package io.github.multiweb.sample.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.multiweb.desktop.DesktopWebViewController
import io.github.multiweb.extension.HostUiRequest
import io.github.multiweb.sample.SampleWebViewScreen
import io.github.multiweb.sample.SampleWebViewExtension
import io.github.multiweb.sample.sampleWebViewInitialization
import io.github.multiweb.sample.sampleNativeWebViewBridgeExtension
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import javax.swing.SwingUtilities
import me.friwi.jcefmaven.CefAppBuilder

/** 单个图片保存请求允许下载的最大字节数，避免网页桥触发无上限的文件写入。 */
private const val MaxSampleImageBytes = 10L * 1024L * 1024L

/** 桌面示例入口；JCEF 首次启动会按 jcefmaven 配置准备原生运行时。 */
fun main() = application {
  var isFullscreen by remember { mutableStateOf(false) }
  var pendingImageSaveUrl by remember { mutableStateOf<String?>(null) }
  var hostCapabilityNotice by remember { mutableStateOf<String?>(null) }
  val windowState = rememberWindowState()
  val extension = remember {
    SampleWebViewExtension(
      hostUiRequestHandler = { request ->
        if (request is HostUiRequest.SetFullscreen) {
          SwingUtilities.invokeLater {
            isFullscreen = request.enabled
            windowState.placement = if (request.enabled) {
              WindowPlacement.Fullscreen
            } else {
              WindowPlacement.Floating
            }
          }
        }
      },
      onImageSaveRequested = { imageUrl ->
        SwingUtilities.invokeLater {
          pendingImageSaveUrl = imageUrl
        }
      },
    )
  }
  val nativeBridgeExtension = remember {
    sampleNativeWebViewBridgeExtension(
      hostUiRequestHandler = { request ->
        if (request is HostUiRequest.SetFullscreen) {
          SwingUtilities.invokeLater {
            isFullscreen = request.enabled
            windowState.placement = if (request.enabled) {
              WindowPlacement.Fullscreen
            } else {
              WindowPlacement.Floating
            }
          }
        }
      },
      onImageSaveRequested = { imageUrl ->
        SwingUtilities.invokeLater {
          pendingImageSaveUrl = imageUrl
        }
      },
    )
  }
  val cefApp = remember {
    CefAppBuilder().apply {
      // JCEF 原生运行时体积较大，放入用户目录以便多次启动复用，避免污染项目工作区。
      setInstallDir(jcefInstallDirectory())
      configureCefUserDataDirectory()
    }.build()
  }
  val controller = remember {
    val initialization = sampleWebViewInitialization(
      extensions = listOf(extension, nativeBridgeExtension),
    )
    DesktopWebViewController(
      cefApp = cefApp,
      initialization = initialization.copy(
        webViewConfig = initialization.webViewConfig.copy(
          thirdPartyCookiesEnabled = true,
          persistentSessionEnabled = true,
        ),
      ),
      onBrowserClosed = {
        // JCEF 在原生关闭回调后才允许销毁进程级 CefApp，随后再结束 Compose 事件循环。
        SwingUtilities.invokeLater {
          cefApp.dispose()
          exitApplication()
        }
      },
    )
  }

  Window(
    // 不直接退出 Compose；先等待 JCEF 确认浏览器关闭，避免 macOS AppKit 访问已释放的原生对象。
    onCloseRequest = controller::dispose,
    state = windowState,
    title = "MultiWeb Compose 示例",
  ) {
    DisposableEffect(controller) {
      onDispose(controller::dispose)
    }

    SampleWebViewScreen(
      controller = controller,
      isFullscreen = isFullscreen,
      pendingImageSaveUrl = pendingImageSaveUrl,
      hostCapabilityNotice = hostCapabilityNotice,
      onImageSaveConfirmed = { imageUrl ->
        pendingImageSaveUrl = null
        saveSampleImageToDownloads(imageUrl) { result ->
          hostCapabilityNotice = result
        }
      },
      onImageSaveDismissed = { pendingImageSaveUrl = null },
    ) {
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

/**
 * 在后台下载已由桥校验、且经用户确认的图片到用户下载目录。
 *
 * 除了桥侧的 URL 白名单外，这里继续校验响应 MIME 类型与声明长度，并在流式写入时限制总字节数；这样页面不能
 * 通过图片保存请求写入任意大文件。最终状态必须回到 Swing 事件线程更新 Compose 状态。
 */
private fun saveSampleImageToDownloads(imageUrl: String, onResult: (String) -> Unit) {
  Thread {
    val result = runCatching {
      val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        requestMethod = "GET"
      }
      try {
        connection.connect()
        require(connection.responseCode in 200..299) { "图片服务器返回了错误状态。" }
        val contentType = connection.contentType?.substringBefore(';')?.lowercase()
        require(contentType?.startsWith("image/") == true) { "下载内容不是图片，未保存。" }
        require(connection.contentLengthLong !in (MaxSampleImageBytes + 1)..Long.MAX_VALUE) {
          "图片超过 10 MiB，未保存。"
        }

        val downloadsDirectory = File(System.getProperty("user.home"), "Downloads")
        Files.createDirectories(downloadsDirectory.toPath())
        val temporaryFile = Files.createTempFile(downloadsDirectory.toPath(), ".multiweb-", ".download")
        try {
          val bytesWritten = connection.inputStream.use { input ->
            Files.newOutputStream(temporaryFile).use { output ->
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              var total = 0L
              while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                  break
                }
                total += count
                require(total <= MaxSampleImageBytes) { "图片超过 10 MiB，未保存。" }
                output.write(buffer, 0, count)
              }
              total
            }
          }
          require(bytesWritten > 0L) { "图片内容为空，未保存。" }
          val targetFile = uniqueSampleImageFile(
            directory = downloadsDirectory,
            requestedName = sampleImageFileName(imageUrl, contentType),
          )
          Files.move(temporaryFile, targetFile.toPath())
          "图片已保存到 Downloads/${targetFile.name}。"
        } catch (error: Throwable) {
          Files.deleteIfExists(temporaryFile)
          throw error
        }
      } finally {
        connection.disconnect()
      }
    }.getOrElse { error ->
      error.message ?: "图片保存失败。"
    }
    SwingUtilities.invokeLater { onResult(result) }
  }.apply {
    isDaemon = true
    start()
  }
}

/** 根据图片地址生成不会跨越下载目录的文件名；无扩展名时按已校验的 MIME 类型补齐。 */
private fun sampleImageFileName(imageUrl: String, contentType: String): String {
  val requestedName = URL(imageUrl).path.substringAfterLast('/')
    .replace(Regex("[^A-Za-z0-9._-]"), "_")
    .takeIf { name -> name.isNotBlank() && name != "." && name != ".." }
  if (requestedName != null && '.' in requestedName) {
    return requestedName
  }
  val extension = when (contentType) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "jpg"
  }
  return "multiweb-${System.currentTimeMillis()}.$extension"
}

/** 返回下载目录内尚未占用的文件名，避免网页重复请求覆盖用户已有文件。 */
private fun uniqueSampleImageFile(directory: File, requestedName: String): File {
  val baseName = requestedName.substringBeforeLast('.', missingDelimiterValue = requestedName)
  val extension = requestedName.substringAfterLast('.', missingDelimiterValue = "")
  var index = 0
  while (true) {
    val suffix = if (index == 0) "" else " ($index)"
    val candidate = File(directory, "$baseName$suffix${if (extension.isEmpty()) "" else ".$extension"}")
    if (!candidate.exists()) {
      return candidate
    }
    index += 1
  }
}
