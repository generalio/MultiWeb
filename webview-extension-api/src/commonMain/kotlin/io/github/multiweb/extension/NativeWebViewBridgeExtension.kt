package io.github.multiweb.extension

/**
 * 旧 Android WebView 业务桥的跨平台宿主委托。
 *
 * 本接口不依赖账号、路由、权限或任何平台类型。应用仅为自己允许的受信任域名提供实现，并根据请求决定是否
 * 执行业务操作；未支持的能力应返回 [NativeWebViewBridgeResult.Failure]，而不是假装成功。
 */
fun interface NativeWebViewBridgeHost {
  /** 处理来自受信任网页的类型化请求。 */
  fun handle(request: NativeWebViewBridgeRequest): NativeWebViewBridgeResult
}

/** 旧网页桥可请求的业务能力。 */
sealed interface NativeWebViewBridgeRequest {
  /** 请求宿主保存图片；图片 URL 的可信性、下载和用户确认均由宿主决定。 */
  data class SaveImage(
    /** 网页提供的图片地址。 */
    val url: String,
  ) : NativeWebViewBridgeRequest

  /** 请求宿主在当前页面后续加载完成时执行脚本；是否支持及执行边界由宿主决定。 */
  data class SetPageLoadScript(
    /** 网页提供的 JavaScript 文本。 */
    val script: String,
  ) : NativeWebViewBridgeRequest

  /** 请求宿主开始监听指定的传感器类型。 */
  data class StartSensor(
    /** Android 兼容的传感器整数标识；非 Android 宿主可拒绝该请求。 */
    val sensorId: Int,
  ) : NativeWebViewBridgeRequest

  /** 请求宿主执行应用内路由。 */
  data class Navigate(
    /** 由业务定义的路由文本。 */
    val path: String,
  ) : NativeWebViewBridgeRequest

  /** 请求宿主在当前网页上下文执行脚本；宿主必须自行限制脚本来源和执行时机。 */
  data class ExecuteJavaScript(
    /** 网页提供的 JavaScript 文本。 */
    val script: String,
  ) : NativeWebViewBridgeRequest

  /** 请求宿主展示一条业务提示。 */
  data class ShowMessage(
    /** 待展示的文本。 */
    val message: String,
  ) : NativeWebViewBridgeRequest

  /** 请求当前登录用户的学号或其他业务标识。 */
  data object GetStudentId : NativeWebViewBridgeRequest

  /** 请求当前宿主是否处于深色主题。 */
  data object GetDarkThemeEnabled : NativeWebViewBridgeRequest

  /** 请求宿主进入或退出沉浸式全屏。 */
  data class SetFullscreen(
    /** `true` 表示进入全屏，`false` 表示恢复常规界面。 */
    val enabled: Boolean,
  ) : NativeWebViewBridgeRequest

  /** 请求当前系统栏安全间距，建议以 JSON 文本返回。 */
  data object GetSystemBarInsets : NativeWebViewBridgeRequest

  /** 请求当前用户的业务访问令牌。 */
  data object GetToken : NativeWebViewBridgeRequest
}

/** 宿主处理业务桥请求后的结果。 */
sealed interface NativeWebViewBridgeResult {
  /** 请求已完成；[payload] 会原样写入网页桥响应。 */
  data class Success(
    /** 面向网页的可序列化结果文本。 */
    val payload: String = "",
  ) : NativeWebViewBridgeResult

  /** 请求被拒绝或无法完成；[errorCode] 应使用稳定的机器可读错误码。 */
  data class Failure(
    /** 面向网页的错误码。 */
    val errorCode: String,
    /** 可选的错误补充信息。 */
    val payload: String = "",
  ) : NativeWebViewBridgeResult
}

/**
 * 兼容旧 `window.AndroidWebView` 方法名的受限跨平台扩展。
 *
 * 所有方法只能通过 [allowedHosts] 中的精确 HTTPS 主机调用，并统一返回 Promise。该扩展只负责把网页调用
 * 转换为 [NativeWebViewBridgeRequest]；账号、路由、权限、下载、传感器与脚本执行均由 [host] 决定。Promise
 * 成功时返回的对象包含 `isSuccess` 与 [NativeWebViewBridgeResult.Success.payload] 对应的 `payload` 字段。
 */
class NativeWebViewBridgeExtension(
  /** 允许使用该桥的精确 HTTPS 主机名集合。 */
  private val allowedHosts: Set<String>,
  /** 执行业务请求的应用宿主。 */
  private val host: NativeWebViewBridgeHost,
  /** 网页侧桥名称，默认兼容旧 Android 项目的 `AndroidWebView`。 */
  private val bridgeName: String = "AndroidWebView",
) : WebViewExtension {
  override val scriptBridges: List<ScriptBridge> = listOf(
    object : ScriptBridgeWithFacade {
      override val name: String = bridgeName
      override val transportName: String = "__multiweb_${bridgeName}_transport"
      override val allowedHosts: Set<String> = this@NativeWebViewBridgeExtension.allowedHosts
      override val facade: ScriptBridgeFacade = ScriptBridgeFacade(LegacyMethodNames)

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse {
        return handleCall(call)
      }
    },
  )

  private fun handleCall(call: ScriptBridgeCall): ScriptBridgeResponse {
    val request = call.toRequest()
      ?: return ScriptBridgeResponse(isSuccess = false, errorCode = "invalid_request")
    val result = runCatching { host.handle(request) }
      .getOrElse { NativeWebViewBridgeResult.Failure("host_exception") }
    return when (result) {
      is NativeWebViewBridgeResult.Success -> {
        ScriptBridgeResponse(isSuccess = true, payload = result.payload)
      }
      is NativeWebViewBridgeResult.Failure -> {
        ScriptBridgeResponse(
          isSuccess = false,
          payload = result.payload,
          errorCode = result.errorCode,
        )
      }
    }
  }

  private fun ScriptBridgeCall.toRequest(): NativeWebViewBridgeRequest? {
    return when (method) {
      "savePic" -> NativeWebViewBridgeRequest.SaveImage(payload)
      "onLoad" -> NativeWebViewBridgeRequest.SetPageLoadScript(payload)
      "initSensor" -> payload.toIntOrNull()?.let(NativeWebViewBridgeRequest::StartSensor)
      "jump" -> NativeWebViewBridgeRequest.Navigate(payload)
      "exeJs" -> NativeWebViewBridgeRequest.ExecuteJavaScript(payload)
      "toast" -> NativeWebViewBridgeRequest.ShowMessage(payload)
      "getStu" -> NativeWebViewBridgeRequest.GetStudentId
      "isDark" -> NativeWebViewBridgeRequest.GetDarkThemeEnabled
      "setFullscreen" -> payload.toBooleanStrictOrNull()?.let(NativeWebViewBridgeRequest::SetFullscreen)
      "getSystemBarInsets" -> NativeWebViewBridgeRequest.GetSystemBarInsets
      "getToken" -> NativeWebViewBridgeRequest.GetToken
      else -> null
    }
  }

  private companion object {
    val LegacyMethodNames = linkedSetOf(
      "savePic",
      "onLoad",
      "initSensor",
      "jump",
      "exeJs",
      "toast",
      "getStu",
      "isDark",
      "setFullscreen",
      "getSystemBarInsets",
      "getToken",
    )
  }
}
