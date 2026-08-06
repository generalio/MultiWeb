package io.github.multiweb.extension

import io.github.multiweb.api.JavaScriptExecutor
import io.github.multiweb.api.WebViewController

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

  /**
   * 请求宿主在当前页面后续加载完成时执行脚本。
   *
   * 默认由宿主决定是否支持；当 [NativeWebViewBridgeExtension] 显式开启受控脚本执行时，扩展会保存最新脚本，
   * 并仅在受信任页面完成加载后通过控制器执行。
   */
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

  /**
   * 请求宿主在当前网页上下文执行脚本。
   *
   * 默认由宿主决定是否支持；当 [NativeWebViewBridgeExtension] 显式开启受控脚本执行时，扩展会通过控制器再次
   * 校验当前主文档来源，不能借此向未受信任页面提交脚本。
   */
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
 * 转换为 [NativeWebViewBridgeRequest]；账号、路由、权限、下载与传感器均由 [host] 决定。默认情况下脚本执行也
 * 交由 [host] 处理；仅显式开启后才会由扩展使用 [JavaScriptExecutor] 受控执行。Promise 成功时返回的对象包含
 * `isSuccess` 与 [NativeWebViewBridgeResult.Success.payload] 对应的 `payload` 字段。
 */
class NativeWebViewBridgeExtension(
  /** 允许使用该桥的精确 HTTPS 主机名集合。 */
  private val allowedHosts: Set<String>,
  /** 执行业务请求的应用宿主。 */
  private val host: NativeWebViewBridgeHost,
  /** 网页侧桥名称，默认兼容旧 Android 项目的 `AndroidWebView`。 */
  private val bridgeName: String = "AndroidWebView",
) : WebViewControllerLifecycleExtension {
  /**
   * 创建启用受控旧脚本执行能力的桥。
   *
   * 关闭时 [NativeWebViewBridgeRequest.SetPageLoadScript] 与 [NativeWebViewBridgeRequest.ExecuteJavaScript] 继续交由
   * [host] 处理，保持既有行为。开启后这两个请求不再进入 [host]：最新页面脚本会在受信任主文档完成加载后执行，
   * 即时脚本会在收到请求时执行。控制器未提供或已释放时返回 `javascript_executor_unavailable`；当前主文档来源、
   * 线程或平台状态不满足执行条件时返回 `javascript_execution_rejected`。
   */
  constructor(
    allowedHosts: Set<String>,
    host: NativeWebViewBridgeHost,
    enableLegacyJavaScriptExecution: Boolean,
    bridgeName: String = "AndroidWebView",
  ) : this(
    allowedHosts = allowedHosts,
    host = host,
    bridgeName = bridgeName,
  ) {
    this.enableLegacyJavaScriptExecution = enableLegacyJavaScriptExecution
  }

  /** 是否启用由框架执行旧桥脚本；默认关闭以维持最小权限和既有宿主语义。 */
  private var enableLegacyJavaScriptExecution: Boolean = false
  /** 生命周期绑定的脚本执行器；控制器释放后必须清空。 */
  private var javaScriptExecutor: JavaScriptExecutor? = null
  /** 网页最后声明的页面完成脚本；新值会覆盖旧值，避免多次加载累积执行。 */
  private var pageLoadScript: String? = null

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

  override fun onControllerAttached(controller: WebViewController) {
    javaScriptExecutor = controller as? JavaScriptExecutor
  }

  override fun onControllerDisposed() {
    javaScriptExecutor = null
    pageLoadScript = null
  }

  override fun onPageFinished(event: PageFinishedEvent) {
    if (!enableLegacyJavaScriptExecution) {
      return
    }
    val script = pageLoadScript ?: return
    javaScriptExecutor?.executeJavaScript(script, allowedHosts)
  }

  private fun handleCall(call: ScriptBridgeCall): ScriptBridgeResponse {
    if (enableLegacyJavaScriptExecution) {
      when (call.method) {
        "onLoad" -> return savePageLoadScript(call.payload)
        "exeJs" -> return executeJavaScript(call.payload)
      }
    }
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

  /** 保存最新页面脚本；只有已绑定可用执行器时才接受，避免向网页错误承诺未支持能力。 */
  private fun savePageLoadScript(script: String): ScriptBridgeResponse {
    if (javaScriptExecutor == null) {
      return ScriptBridgeResponse(isSuccess = false, errorCode = JavaScriptExecutorUnavailable)
    }
    pageLoadScript = script
    return ScriptBridgeResponse(isSuccess = true)
  }

  /** 立即提交网页请求的脚本；执行器会在原生侧再次复核可信来源。 */
  private fun executeJavaScript(script: String): ScriptBridgeResponse {
    val executor = javaScriptExecutor
      ?: return ScriptBridgeResponse(isSuccess = false, errorCode = JavaScriptExecutorUnavailable)
    return if (executor.executeJavaScript(script, allowedHosts)) {
      ScriptBridgeResponse(isSuccess = true)
    } else {
      ScriptBridgeResponse(isSuccess = false, errorCode = JavaScriptExecutionRejected)
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
    /** 控制器未实现脚本执行器或已释放时返回的稳定错误码。 */
    val JavaScriptExecutorUnavailable = "javascript_executor_unavailable"
    /** 原生侧因来源、线程或平台状态拒绝脚本时返回的稳定错误码。 */
    val JavaScriptExecutionRejected = "javascript_execution_rejected"

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
