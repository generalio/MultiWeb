package io.github.multiweb.extension

/**
 * 网页请求宿主选择文件时的跨平台描述。
 *
 * 该对象只描述页面声明的选择范围，不代表组件已经获取任何文件权限。[acceptTypes] 是网页提供的 MIME 类型或
 * 文件扩展名提示，宿主仍应使用系统选择器并自行决定是否接受。iOS 当前无法从 WebKit 读取该字段，因此可能为空。
 */
data class WebFileChooserRequest(
  /** 网页声明的可接受 MIME 类型或扩展名提示；为空表示未声明限制。 */
  val acceptTypes: List<String> = emptyList(),
  /** 选择已有文件还是请求保存目标。 */
  val mode: WebFileChooserMode = WebFileChooserMode.Open,
  /** 网页是否允许一次返回多个文件。 */
  val allowMultipleSelection: Boolean = false,
  /** 网页是否请求选择目录；宿主可因自身能力限制而取消。 */
  val allowDirectories: Boolean = false,
)

/** 网页文件对话框的操作类型。 */
enum class WebFileChooserMode {
  /** 选择已有文件或目录。 */
  Open,

  /** 选择新文件的保存位置。 */
  Save,
}

/** 宿主对网页文件选择请求的处理结果。 */
sealed interface WebFileChooserResult {
  /** 宿主拒绝或用户取消选择；平台会将原生请求显式取消。 */
  data object Cancelled : WebFileChooserResult

  /**
   * 宿主选定的文件地址。
   *
   * 地址必须来自系统文件选择器，且必须符合目标平台格式：Android 仅接受带非空 authority 的 `content://` URI；
   * iOS 与 Desktop 仅接受绝对 `file://` URI。平台会在回传网页前再次校验，任何无效地址都会使整次请求取消。
   */
  data class Selected(
    /** 用户显式选定的文件 URI 列表，不能为空且不包含空白项。 */
    val uris: List<String>,
  ) : WebFileChooserResult {
    init {
      require(uris.isNotEmpty()) { "已选择文件时必须至少提供一个 URI。" }
      require(uris.all { uri -> uri.containsNonWhitespace() }) { "已选择文件 URI 不能包含空白项。" }
    }
  }
}

/**
 * 由宿主实现的文件选择入口。
 *
 * 控制器不会自行申请存储、相册、相机或媒体权限。将本处理器放入 [WebViewInitialization.extensions] 后，网页发起
 * 选择才会通知宿主；每个控制器最多允许一个处理器。未配置、抛出异常或回传无效结果都会取消请求。实现可异步打开
 * 系统文件选择器，但对每次请求必须至多调用一次 [complete]。
 */
fun interface WebFileChooserHandler : WebViewExtension {
  /** 接收网页文件选择请求，并在宿主完成或取消后调用 [complete]。 */
  fun onFileChooserRequested(
    request: WebFileChooserRequest,
    complete: (WebFileChooserResult) -> Unit,
  )
}

/** 使用普通循环避免 Wasm 后端在数据类初始化块内联 `String.isNotBlank()` 的编译缺陷。 */
private fun String.containsNonWhitespace(): Boolean {
  for (character in this) {
    if (!character.isWhitespace()) {
      return true
    }
  }
  return false
}
