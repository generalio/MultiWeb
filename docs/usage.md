# 使用指南

## 添加依赖

正式版本位于 Maven Central：

```kotlin
repositories {
  mavenCentral()
}
```

以 Android 为例：

```kotlin
dependencies {
  implementation("io.github.generalio.multiweb:webview-api:<版本>")
  implementation("io.github.generalio.multiweb:webview-android:<版本>")
}
```

不要依赖 `webview-test-fixtures`。它是仓库内测试代码，不会发布到 Maven Central 或 GitHub Packages。

## 公共配置

控制器接收 `WebViewConfig` 和 `NavigationPolicy`。安全能力默认关闭，以下示例显式开启 JavaScript 并仅允许
指定站点：

```kotlin
val config = WebViewConfig(
  javaScriptEnabled = true,
  allowedHosts = setOf("example.com"),
)

val policy = NavigationPolicy { request ->
  when {
    request.url.startsWith("https://example.com") -> NavigationDecision.Allow
    request.url.startsWith("https://") -> NavigationDecision.OpenExternally
    else -> NavigationDecision.Cancel
  }
}
```

`DefaultNavigationPolicy` 只允许 HTTPS，不包含主机白名单。`allowedHosts` 为空时由导航策略决定；非空时平台
控制器还会执行精确主机匹配。它不接受 `*.example.com` 一类通配符。

## Android

在主线程创建 `AndroidWebViewController`，并将 `view` 放入 Android View 层级。Compose 可按如下方式接入：

```kotlin
val controller = remember {
  AndroidWebViewController(
    context = applicationContext,
    config = config,
    navigationPolicy = policy,
    onExternalNavigation = { request ->
      // 宿主按自己的 Intent 或路由策略处理外部导航。
    },
  )
}

DisposableEffect(controller) {
  onDispose { controller.dispose() }
}

AndroidView(
  factory = { controller.view },
  modifier = Modifier.fillMaxSize(),
)
```

`Activity` 或其他生命周期宿主在暂停、恢复时还应调用 `controller.onHostPause()` 和
`controller.onHostResume()`。需要自定义 WebView 子类时，用 `webViewFactory` 注入；不要自行设置
`WebViewClient`、`WebChromeClient`，也不要使用 `addJavascriptInterface`。

## iOS

在 KMP 的 `iosMain` 中添加实现模块：

```kotlin
iosMain.dependencies {
  implementation("io.github.generalio.multiweb:webview-ios:<版本>")
}
```

在主线程创建 `IosWebViewController`，把 `controller.view` 加入 UIKit 视图层级，并在视图销毁时调用
`dispose()`。Compose Multiplatform 可用 `UIKitView(factory = { controller.view })` 承载它。

`persistentSessionEnabled = false` 会使用 WebKit 的非持久化数据存储。第三方 Cookie 由 WebKit 的系统级
隐私策略管理，组件不会尝试绕过该保护。

## Desktop

桌面模块使用 JCEF。宿主必须先创建进程级 `CefApp`，随后在 Swing EDT 创建控制器。由于 JCEF 不能按单个
浏览器可靠关闭 JavaScript、第三方 Cookie 和持久化会话，创建 Desktop 控制器时这三项必须显式为 `true`：

```kotlin
val controller = DesktopWebViewController(
  cefApp = cefApp,
  config = WebViewConfig(
    javaScriptEnabled = true,
    thirdPartyCookiesEnabled = true,
    persistentSessionEnabled = true,
  ),
  navigationPolicy = DefaultNavigationPolicy,
  onBrowserClosed = {
    // 原生浏览器确认关闭后，宿主才可销毁 cefApp。
  },
)
```

使用 Compose Desktop 时可通过 `SwingPanel(factory = { controller.view })` 承载。窗口关闭时调用
`controller.dispose()`；不要立即销毁 `CefApp`，应在 `onBrowserClosed` 回调中完成。

## JS 与 Wasm

`webview-browser` 不提供嵌入式浏览器。`BrowserWebViewController.load()` 通过浏览器 API 打开新的窗口或
标签页；弹窗可能被用户浏览器阻止。该模块没有会话隔离能力，调用 `clearSession()` 会抛出
`UnsupportedOperationException`。

## 页面事件与 JS 桥

用 `WebViewExtension` 承载可扩展行为。桥仅应暴露少量、可序列化、可校验的方法：

```kotlin
val extension = object : WebViewExtension {
  override val scriptBridges = listOf(object : ScriptBridge {
    override val name = "appBridge"
    override val allowedHosts = setOf("example.com")

    override fun handle(call: ScriptBridgeCall): ScriptBridgeResponse? {
      return when (call.method) {
        "getAppVersion" -> ScriptBridgeResponse(isSuccess = true, payload = "1.0.0")
        else -> ScriptBridgeResponse(isSuccess = false, errorCode = "unknown_method")
      }
    }
  })

  override fun onPageError(event: PageErrorEvent) {
    // 记录或展示 event.error。
  }
}
```

将扩展通过 `extensions = listOf(extension)` 传给 Android、iOS 或 Desktop 控制器。桥名应使用业务命名空间；
调用参数和返回值必须由业务方自行做格式、权限和大小校验。

## 加载与释放

```kotlin
controller.load(WebRequest("https://example.com"))
controller.reload()
controller.goBack()
controller.clearSession()
controller.dispose()
```

`dispose()` 可以重复调用，但首次调用后其余操作会抛出 `IllegalStateException`。将控制器保存在与原生视图
相同的生命周期范围内，不要跨 Activity、UIViewController 或桌面窗口复用。
