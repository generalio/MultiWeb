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
控制器还会执行精确主机匹配。它不接受 `*.example.com` 一类通配符；JS 桥只匹配 HTTPS 默认端口 `443`，
不会向 `https://example.com:8443` 一类非默认端口注入。

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

Android 会启用同源 DOM Storage，以兼容使用 `localStorage` 或 `sessionStorage` 初始化的现代 HTTPS 页面；
调用 `clearSession()` 会同时删除 Web Storage、缓存、历史记录和 Cookie。该兼容性设置不会放宽 HTTP 混合内容、
本地文件访问或无用户手势自动播放的默认限制。

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
`UnsupportedOperationException`。它不会安装 JS 桥，因此不支持由网页触发的原生全屏或图片保存能力。

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

### 公共初始化与业务桥

`WebViewInitialization` 将安全配置、导航策略和扩展列表集中定义。Android、iOS、Desktop 控制器均可接收它，
平台入口只保留原生视图、外部导航、系统权限等宿主代码：

```kotlin
val businessBridge = NativeWebViewBridgeExtension(
  allowedHosts = setOf("app.example.com"),
  host = NativeWebViewBridgeHost { request ->
    when (request) {
      NativeWebViewBridgeRequest.GetToken -> {
        // 调用方自行读取令牌；不要让库依赖具体账号服务。
        NativeWebViewBridgeResult.Success(payload = "")
      }
      is NativeWebViewBridgeRequest.SetFullscreen -> {
        // 调用方切换 Activity、UIViewController 或 Window 的全屏状态。
        NativeWebViewBridgeResult.Success()
      }
      else -> NativeWebViewBridgeResult.Failure("unsupported_operation")
    }
  },
)

val initialization = WebViewInitialization(
  webViewConfig = WebViewConfig(javaScriptEnabled = true),
  navigationPolicy = DefaultNavigationPolicy,
  extensions = listOf(businessBridge),
)

val controller = AndroidWebViewController(
  context = applicationContext,
  initialization = initialization,
)
```

该扩展兼容旧项目常用的 `AndroidWebView` 方法名：`savePic`、`onLoad`、`initSensor`、`jump`、`exeJs`、
`toast`、`getStu`、`isDark`、`setFullscreen`、`getSystemBarInsets`、`getToken`。它们只会转换为
`NativeWebViewBridgeRequest`，不包含账号、路由、权限、下载、传感器或脚本执行的业务实现。

三端统一使用 Promise；旧同步 getter 需要迁移为 `await`：

```javascript
const { payload: token } = await window.AndroidWebView.getToken();
await window.AndroidWebView.setFullscreen(true);
await window.AndroidWebView.savePic("https://cdn.example.com/poster.png");
```

桥只会向精确的 HTTPS 主机注入。内部消息通道是平台实现细节，不应由页面直接依赖；平台仍会在接收消息时
复核来源。JS/Wasm 的 `webview-browser` 不依赖扩展 API，因此不会安装该桥。

### Compose 示例桥

`sample-compose` 仅向 `app.redrock.team` 与 `m.app.redrock.team` 注入名为 `multiWebSample` 的桥。网页使用
同一条 JSON 文本协议调用：

```javascript
window.multiWebSample.postMessage(JSON.stringify({
  method: "setFullscreen",
  payload: "true",
}))

window.multiWebSample.postMessage(JSON.stringify({
  method: "saveImage",
  payload: "https://cdn.redrock.team/path/to/image.png",
}))
```

`setFullscreen` 由 Android、iOS 和 Desktop 宿主切换各自的沉浸式窗口状态。`saveImage` 只接受精确的
`https://cdn.redrock.team` 图片地址，拒绝端口、凭据、HTTP 与其他主机；收到请求后还必须由用户在 Compose
确认对话框中确认。Android 与 Desktop 会禁止重定向，并在校验响应 MIME 类型和 10 MiB 上限后分别保存到
公共图片目录和 `Downloads`；iOS 仅申请照片库“仅新增”权限，并对每一次重定向复核 CDN 主机。JS/Wasm
不注入此桥。

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
