@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.multiweb.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import io.github.multiweb.api.DefaultNavigationPolicy
import io.github.multiweb.api.WebViewConfig
import io.github.multiweb.extension.HostUiRequest
import io.github.multiweb.ios.IosWebViewController
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableData
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLResponse
import platform.Foundation.appendData
import platform.Foundation.dataTaskWithURL
import platform.Foundation.length
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.bounds
import platform.UIKit.didMoveToParentViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** 单个图片保存请求允许下载的最大字节数，避免网页桥触发无上限的内存分配。 */
private const val MaxSampleImageBytes = 10L * 1024L * 1024L

/** iOS 宿主可调用此函数获取包含 WKWebView 示例的 UIViewController。 */
fun MainViewController(): UIViewController = SampleIosWebViewHostViewController()

/**
 * 为 Compose WebView 示例补充 UIKit 宿主能力。
 *
 * `UIViewController` 负责全屏状态栏外观，Compose 内容只决定是否显示样例工具栏；网页桥的回调统一切回主线程，
 * 防止 WebKit 消息处理线程直接修改 Compose 状态。
 */
private class SampleIosWebViewHostViewController : UIViewController(nibName = null, bundle = null) {
  private var isFullscreen by mutableStateOf(false)
  private var pendingImageSaveUrl by mutableStateOf<String?>(null)
  private var hostCapabilityNotice by mutableStateOf<String?>(null)
  private val composeController = ComposeUIViewController {
    val extension = remember {
      SampleWebViewExtension(
        hostUiRequestHandler = { request ->
          if (request is HostUiRequest.SetFullscreen) {
            dispatch_async(dispatch_get_main_queue()) {
              isFullscreen = request.enabled
              setNeedsStatusBarAppearanceUpdate()
            }
          }
        },
        onImageSaveRequested = { imageUrl ->
          dispatch_async(dispatch_get_main_queue()) {
            pendingImageSaveUrl = imageUrl
          }
        },
      )
    }
    val controller = remember(extension) {
      IosWebViewController(
        config = WebViewConfig(javaScriptEnabled = true),
        navigationPolicy = DefaultNavigationPolicy,
        extensions = listOf(extension),
      )
    }

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
        saveSampleImageToPhotoLibrary(imageUrl) { result ->
          hostCapabilityNotice = result
        }
      },
      onImageSaveDismissed = { pendingImageSaveUrl = null },
    ) {
      UIKitView(
        factory = { controller.view },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }

  override fun viewDidLoad() {
    super.viewDidLoad()
    addChildViewController(composeController)
    composeController.view.setFrame(view.bounds)
    composeController.view.autoresizingMask =
      UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    view.addSubview(composeController.view)
    composeController.didMoveToParentViewController(this)
  }

  override fun prefersStatusBarHidden(): Boolean = isFullscreen

  override fun viewWillDisappear(animated: Boolean) {
    if (isFullscreen) {
      isFullscreen = false
      setNeedsStatusBarAppearanceUpdate()
    }
    super.viewWillDisappear(animated)
  }
}

/** 启动流式下载，避免在校验 10 MiB 限制前把完整响应读入内存。 */
private fun saveSampleImageToPhotoLibrary(imageUrl: String, onResult: (String) -> Unit) {
  SampleIosImageDownload(onResult).start(NSURL(string = imageUrl))
}

/**
 * 仅缓存经大小和 MIME 校验的图片数据，再请求照片库的“仅新增”权限写入。
 *
 * `NSURLSession` 会在下载期间强引用 delegate；完成、拒绝或超限后立即终止 session，避免宿主保留已完成请求。
 */
private class SampleIosImageDownload(
  private val onResult: (String) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
  private val receivedData = NSMutableData()
  private val session = NSURLSession.sessionWithConfiguration(
    configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration,
    delegate = this,
    delegateQueue = null,
  )
  private var hasCompleted = false

  /** 使用独立的临时会话请求已被桥白名单校验的 HTTPS 图片。 */
  fun start(url: NSURL) {
    session.dataTaskWithURL(url).resume()
  }

  @ObjCSignatureOverride
  override fun URLSession(
    session: NSURLSession,
    dataTask: NSURLSessionDataTask,
    didReceiveResponse: NSURLResponse,
    completionHandler: (Long) -> Unit,
  ) {
    val expectedLength = didReceiveResponse.expectedContentLength()
    val isImage = didReceiveResponse.MIMEType()?.lowercase()?.startsWith("image/") == true
    if (!isImage) {
      finish("下载内容不是图片，未保存。")
      completionHandler(NSURLSessionResponseCancel)
    } else if (expectedLength > MaxSampleImageBytes) {
      finish("图片超过 10 MiB，未保存。")
      completionHandler(NSURLSessionResponseCancel)
    } else {
      completionHandler(NSURLSessionResponseAllow)
    }
  }

  /** 每次重定向均复核目标仍为精确 HTTPS CDN，避免初始白名单 URL 被转交到第三方来源。 */
  @ObjCSignatureOverride
  override fun URLSession(
    session: NSURLSession,
    task: NSURLSessionTask,
    willPerformHTTPRedirection: platform.Foundation.NSHTTPURLResponse,
    newRequest: NSURLRequest,
    completionHandler: (NSURLRequest?) -> Unit,
  ) {
    if (newRequest.URL()?.absoluteString?.isTrustedSampleImageUrl() == true) {
      completionHandler(newRequest)
    } else {
      finish("图片重定向到未受信任来源，未保存。")
      completionHandler(null)
    }
  }

  @ObjCSignatureOverride
  override fun URLSession(
    session: NSURLSession,
    dataTask: NSURLSessionDataTask,
    didReceiveData: NSData,
  ) {
    if (hasCompleted) {
      return
    }
    if (receivedData.length + didReceiveData.length > MaxSampleImageBytes.toULong()) {
      dataTask.cancel()
      finish("图片超过 10 MiB，未保存。")
      return
    }
    receivedData.appendData(didReceiveData)
  }

  @ObjCSignatureOverride
  override fun URLSession(
    session: NSURLSession,
    task: NSURLSessionTask,
    didCompleteWithError: NSError?,
  ) {
    if (hasCompleted) {
      return
    }
    if (didCompleteWithError != null || receivedData.length == 0UL) {
      finish("图片下载失败或内容为空，未保存。")
      return
    }
    hasCompleted = true
    session.invalidateAndCancel()
    saveImageToPhotoLibrary(UIImage(data = receivedData), onResult)
  }

  /** 回到主线程通知 Compose，并结束当前临时网络会话。 */
  private fun finish(message: String) {
    if (hasCompleted) {
      return
    }
    hasCompleted = true
    session.invalidateAndCancel()
    dispatch_async(dispatch_get_main_queue()) {
      onResult(message)
    }
  }
}

/** 仅在用户确认且已取得“仅新增”权限后，把已校验图片写入系统照片库。 */
private fun saveImageToPhotoLibrary(image: UIImage, onResult: (String) -> Unit) {
  PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
    if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
      dispatch_async(dispatch_get_main_queue()) {
        onResult("未取得照片库写入权限，图片未保存。")
      }
      return@requestAuthorizationForAccessLevel
    }
    PHPhotoLibrary.sharedPhotoLibrary().performChanges(
      changeBlock = {
        PHAssetCreationRequest.creationRequestForAssetFromImage(image)
      },
      completionHandler = { saved, writeError ->
        dispatch_async(dispatch_get_main_queue()) {
          onResult(if (saved && writeError == null) "图片已保存到照片库。" else "图片保存失败。")
        }
      },
    )
  }
}
