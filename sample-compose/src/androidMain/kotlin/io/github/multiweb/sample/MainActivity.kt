package io.github.multiweb.sample

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.multiweb.android.AndroidWebViewController
import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.extension.HostUiRequest
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Android 平台示例入口，负责把系统 WebView 接入 Compose 生命周期。 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val context = applicationContext
      var isFullscreen by remember { mutableStateOf(false) }
      var pendingImageSaveUrl by remember { mutableStateOf<String?>(null) }
      var pendingLegacyPermissionUrl by remember { mutableStateOf<String?>(null) }
      var hostCapabilityNotice by remember { mutableStateOf<String?>(null) }
      val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
      ) { granted ->
        pendingLegacyPermissionUrl?.let { imageUrl ->
          if (granted) {
            context.saveSampleImage(imageUrl) { result ->
              hostCapabilityNotice = result
            }
          } else {
            hostCapabilityNotice = "未取得存储权限，图片未保存。"
          }
        }
        pendingLegacyPermissionUrl = null
      }
      val extension = remember {
        SampleWebViewExtension(
          hostUiRequestHandler = { request ->
            if (request is HostUiRequest.SetFullscreen) {
              runOnUiThread {
                isFullscreen = request.enabled
              }
            }
          },
          onImageSaveRequested = { imageUrl ->
            runOnUiThread {
              pendingImageSaveUrl = imageUrl
            }
          },
        )
      }
      val controller = remember(extension) {
        AndroidWebViewController(
          context = context,
          config = WebViewConfig(javaScriptEnabled = true),
          navigationPolicy = DefaultNavigationPolicy,
          extensions = listOf(extension),
        )
      }
      var canGoBack by remember(controller) { mutableStateOf(controller.state.canGoBack) }
      val lifecycleOwner = this

      // 仅在存在网页历史时拦截系统返回键；无历史时交回 Activity 的默认退出逻辑。
      BackHandler(enabled = canGoBack) {
        controller.goBack()
      }

      LaunchedEffect(isFullscreen) {
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullscreen) {
          insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
          insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
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
          WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
          controller.dispose()
        }
      }

      SampleWebViewScreen(
        controller = controller,
        isFullscreen = isFullscreen,
        pendingImageSaveUrl = pendingImageSaveUrl,
        hostCapabilityNotice = hostCapabilityNotice,
        onImageSaveConfirmed = { imageUrl ->
          pendingImageSaveUrl = null
          if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
              PackageManager.PERMISSION_GRANTED
          ) {
            pendingLegacyPermissionUrl = imageUrl
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
          } else {
            context.saveSampleImage(imageUrl) { result ->
              hostCapabilityNotice = result
            }
          }
        },
        onImageSaveDismissed = { pendingImageSaveUrl = null },
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

/** 单个图片保存请求允许下载的最大字节数，避免网页桥触发无上限的文件写入。 */
private const val MaxSampleImageBytes = 10L * 1024L * 1024L

/**
 * 将已由受限桥校验、并经用户确认的图片写入公共图片目录。
 *
 * 禁止 HTTP 重定向，避免受信任 CDN 将下载请求转交给未声明的第三方主机；响应 MIME 类型、声明长度和实际流式
 * 字节数都会在写入前或写入过程中校验。Android 10+ 使用 MediaStore，旧系统仅在用户授予存储权限后写文件。
 */
private fun Context.saveSampleImage(imageUrl: String, onResult: (String) -> Unit) {
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
        val fileName = sampleImageFileName(imageUrl, contentType)
        connection.inputStream.use { input ->
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageWithMediaStore(fileName, contentType, input)
          } else {
            saveImageWithLegacyStorage(fileName, input)
          }
        }
        "图片已保存到公共图片目录。"
      } finally {
        connection.disconnect()
      }
    }.getOrElse { error ->
      error.message ?: "图片保存失败。"
    }
    runOnMainThread { onResult(result) }
  }.apply {
    isDaemon = true
    start()
  }
}

/** Android 10+ 通过 MediaStore 原子公布文件，异常时删除未完成的内容条目。 */
private fun Context.saveImageWithMediaStore(
  fileName: String,
  contentType: String,
  input: java.io.InputStream,
) {
  val values = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
    put(MediaStore.MediaColumns.MIME_TYPE, contentType)
    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
    put(MediaStore.MediaColumns.IS_PENDING, 1)
  }
  val uri = requireNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
    "无法创建图片保存位置。"
  }
  try {
    requireNotNull(contentResolver.openOutputStream(uri)).use { output ->
      input.copySampleImageTo(output)
    }
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    contentResolver.update(uri, values, null, null)
  } catch (error: Throwable) {
    contentResolver.delete(uri, null, null)
    throw error
  }
}

/** Android 9 及以下在已授权时写入公共图片目录，异常时删除未完成文件。 */
private fun Context.saveImageWithLegacyStorage(fileName: String, input: java.io.InputStream) {
  val picturesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
  require(picturesDirectory.exists() || picturesDirectory.mkdirs()) { "无法创建公共图片目录。" }
  val targetFile = uniqueSampleImageFile(picturesDirectory, fileName)
  try {
    FileOutputStream(targetFile).use { output ->
      input.copySampleImageTo(output)
    }
  } catch (error: Throwable) {
    targetFile.delete()
    throw error
  }
}

/** 流式复制时执行实际大小上限，防止服务端省略或伪造 Content-Length。 */
private fun java.io.InputStream.copySampleImageTo(output: java.io.OutputStream) {
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  var total = 0L
  while (true) {
    val count = read(buffer)
    if (count < 0) {
      break
    }
    total += count
    require(total <= MaxSampleImageBytes) { "图片超过 10 MiB，未保存。" }
    output.write(buffer, 0, count)
  }
  require(total > 0L) { "图片内容为空，未保存。" }
}

/** 根据受信任 CDN 地址生成不能跨越目标目录的安全文件名。 */
private fun sampleImageFileName(imageUrl: String, contentType: String): String {
  val requestedName = Uri.parse(imageUrl).lastPathSegment
    ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
    ?.takeIf { name -> name.isNotBlank() && name != "." && name != ".." }
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

/** 返回公共图片目录内尚未占用的文件名，避免保存网页图片时覆盖用户已有文件。 */
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

/** 将后台下载结果切回主线程，确保 Compose 状态不会从工作线程更新。 */
private fun Context.runOnMainThread(action: () -> Unit) {
  mainLooper.thread.let { mainThread ->
    if (Thread.currentThread() === mainThread) {
      action()
    } else {
      android.os.Handler(mainLooper).post(action)
    }
  }
}
