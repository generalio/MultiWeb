package io.github.multiweb.api

/**
 * WebView 的安全配置。
 *
 * 所有高风险能力均采用最小权限默认值；平台不支持的选项必须由对应实现明确说明。
 */
data class WebViewConfig(
  /** 是否允许页面执行 JavaScript，默认关闭。 */
  val javaScriptEnabled: Boolean = false,
  /** 是否允许第三方 Cookie，默认关闭以降低跨站跟踪风险。 */
  val thirdPartyCookiesEnabled: Boolean = false,
  /** 是否允许页面访问本地文件，默认关闭。 */
  val fileAccessEnabled: Boolean = false,
  /** 是否持久化 Cookie、缓存等会话数据；关闭时使用临时会话。 */
  val persistentSessionEnabled: Boolean = true,
  /** 允许在内嵌浏览器中访问的主机名集合；空集合表示由导航策略决定。 */
  val allowedHosts: Set<String> = emptySet(),
)

/** 决定一次导航的处理方式。 */
fun interface NavigationPolicy {
  /** 根据导航请求返回允许、取消或交由外部应用打开的决定。 */
  fun decide(request: NavigationRequest): NavigationDecision
}

/** 即将发生的页面导航。 */
data class NavigationRequest(
  /** 目标 URL。 */
  val url: String,
  /** 是否为主框架导航；`false` 表示子框架或嵌入资源相关导航。 */
  val isMainFrame: Boolean,
  /** 是否由用户直接触发，例如点击链接或提交表单。 */
  val isUserInitiated: Boolean,
)

/** 导航策略的处理结果。 */
enum class NavigationDecision {
  /** 在内嵌 WebView 中继续导航。 */
  Allow,
  /** 阻止本次导航。 */
  Cancel,
  /** 交给宿主应用或系统浏览器处理。 */
  OpenExternally,
}

/**
 * 默认导航策略：仅允许 HTTPS 地址。
 *
 * 业务方需要支持自定义 Scheme、HTTP 或白名单时，应提供自己的 [NavigationPolicy] 实现。
 */
object DefaultNavigationPolicy : NavigationPolicy {
  override fun decide(request: NavigationRequest): NavigationDecision {
    return if (request.url.startsWith("https://")) {
      NavigationDecision.Allow
    } else {
      NavigationDecision.Cancel
    }
  }
}
