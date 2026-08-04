package io.github.multiweb.sample

import io.github.multiweb.extension.HostUiRequest
import io.github.multiweb.extension.ScriptBridge
import io.github.multiweb.extension.ScriptBridgeCall
import io.github.multiweb.extension.ScriptBridgeResponse
import io.github.multiweb.extension.WebViewExtension

/** 示例向受信任网页暴露的桥名称。 */
internal const val SampleScriptBridgeName = "multiWebSample"

/** 示例页面的精确 HTTPS 主机名；页面跳转到移动站点后仍需保留桥能力。 */
internal val SampleScriptBridgeHosts = setOf("app.redrock.team", "m.app.redrock.team")

/** 允许由示例保存图片的精确 CDN 主机名。 */
internal val SampleImageHosts = setOf("cdn.redrock.team")

/**
 * 示例页面使用的受限 JS 桥。
 *
 * 网页调用统一使用 `window.multiWebSample.postMessage(JSON.stringify({ method, payload }))`。桥的来源由平台
 * 控制器校验，本类仍会校验图片 URL，避免受信任页面借由保存能力下载任意主机、明文 HTTP、带端口或凭据的地址。
 * `saveImage` 成功响应只表示宿主已收到请求，实际保存前仍必须由用户确认。
 */
internal class SampleWebViewExtension(
  private val hostUiRequestHandler: (HostUiRequest) -> Unit = {},
  private val onImageSaveRequested: (String) -> Unit = {},
) : WebViewExtension {
  override val scriptBridges: List<ScriptBridge> = listOf(
    object : ScriptBridge {
      override val name: String = SampleScriptBridgeName
      override val allowedHosts: Set<String> = SampleScriptBridgeHosts

      override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse {
        return when (call.method) {
          "setFullscreen" -> handleSetFullscreen(call.payload)
          "saveImage" -> handleSaveImage(call.payload)
          else -> ScriptBridgeResponse(isSuccess = false, errorCode = "unknown_method")
        }
      }
    },
  )

  override fun onHostUiRequest(request: HostUiRequest) {
    hostUiRequestHandler(request)
  }

  private fun handleSetFullscreen(payload: String): ScriptBridgeResponse {
    val enabled = payload.toBooleanStrictOrNull()
      ?: return ScriptBridgeResponse(isSuccess = false, errorCode = "invalid_fullscreen_value")
    onHostUiRequest(HostUiRequest.SetFullscreen(enabled))
    return ScriptBridgeResponse(isSuccess = true)
  }

  private fun handleSaveImage(payload: String): ScriptBridgeResponse {
    if (!payload.isTrustedSampleImageUrl()) {
      return ScriptBridgeResponse(isSuccess = false, errorCode = "untrusted_image_url")
    }
    onImageSaveRequested(payload)
    return ScriptBridgeResponse(isSuccess = true, payload = "pending_user_confirmation")
  }
}

/** 校验图片地址是否使用声明的 HTTPS CDN 主机，拒绝端口、凭据、控制字符和非 HTTPS 来源。 */
internal fun String.isTrustedSampleImageUrl(): Boolean {
  if (any { character ->
      character.isWhitespace() || character.code in 0x00..0x1f || character.code in 0x7f..0x9f
    }
  ) {
    return false
  }
  val schemePrefix = "https://"
  if (!startsWith(schemePrefix, ignoreCase = true)) {
    return false
  }
  val authorityStart = schemePrefix.length
  val authorityEnd = indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
    .let { index -> if (index == -1) length else index }
  val authority = substring(authorityStart, authorityEnd)
  if (authority.isBlank() || authority.any { character -> character == '@' || character == ':' }) {
    return false
  }
  return authority.lowercase() in SampleImageHosts
}
