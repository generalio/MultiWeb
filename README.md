# MultiWeb

MultiWeb 是面向 Kotlin Multiplatform 的原生 WebView 组件库。它以统一的控制器、导航策略和安全配置，
封装 Android 系统 WebView、iOS WKWebView、Desktop JCEF 与 JS/Wasm 浏览器跳转能力。

## 能力概览

| 平台 | 渲染方式 | 统一 Compose 入口 | 说明 |
| --- | --- | --- | --- |
| Android | 系统 WebView | 支持 | 自动转发生命周期，可使用受限 JS 桥。 |
| iOS | WKWebView | 支持 | 使用 UIKit 嵌入，可使用受限 JS 桥。 |
| Desktop | JCEF | 支持 | 宿主负责初始化和销毁进程级 JCEF。 |
| JS / Wasm | 浏览器新窗口或标签页 | 不嵌入渲染 | 不持有浏览器 Cookie、缓存或 JS 桥。 |

- 默认仅允许 HTTPS 导航，JavaScript、第三方 Cookie 与文件访问默认关闭。
- 通过 `WebViewExtension` 监听页面事件、下载、上下文操作和宿主 UI 请求。
- 通过精确 HTTPS 域名白名单向可信页面提供受限 JS 桥。
- Compose Multiplatform 可在 `commonMain` 使用同一份控制器和视图代码。

## 快速接入

在 KMP 项目的 `commonMain` 添加统一入口模块：

```kotlin
commonMain.dependencies {
  implementation("io.github.generalio.multiweb:webview-compose:<版本>")
}
```

下面的页面代码可同时用于 Android、iOS 与 Desktop。JS/Wasm 会沿用同一控制器调用，但以浏览器新窗口或标签页
打开地址，不会渲染内嵌视图。

```kotlin
@Composable
fun HelpPage() {
  val initialization = remember {
    WebViewInitialization(
      webViewConfig = WebViewConfig(
        javaScriptEnabled = true,
        // Desktop JCEF 无法按单个浏览器关闭这两项，跨平台配置需要显式确认。
        thirdPartyCookiesEnabled = true,
        persistentSessionEnabled = true,
        allowedHosts = setOf("example.com"),
      ),
      navigationPolicy = DefaultNavigationPolicy,
    )
  }
  val controller = rememberWebViewController(
    initialization = initialization,
    hostCallbacks = WebViewHostCallbacks(
      onExternalNavigation = { request ->
        // 按应用自身的路由或系统能力处理外部链接。
      },
    ),
  )

  LaunchedEffect(controller) {
    controller.load(WebRequest("https://example.com"))
  }

  WebView(
    controller = controller,
    modifier = Modifier.fillMaxSize(),
  )
}
```

Desktop 在首次组合上述页面前，还需要由应用入口创建一次 JCEF 并注入运行时。`CefApp` 是进程级资源，
MultiWeb 不会替宿主销毁它：

```kotlin
val cefApp = CefAppBuilder().build()

DesktopWebViewRuntime.initialize(
  cefApp = cefApp,
  onBrowserClosed = {
    // 确认没有其他浏览器后，由宿主调用 cefApp.dispose()。
  },
)
```

完整的依赖、初始化与各平台接入方式见[使用指南](docs/使用指南.md)。

## 模块

| 模块 | 用途 |
| --- | --- |
| `webview-compose` | KMP Compose 的统一 Controller 创建与原生视图入口。 |
| `webview-api` | 跨平台控制器、状态、请求、安全配置与导航策略。 |
| `webview-extension-api` | 页面事件、下载、上下文操作、宿主 UI 请求和受限 JS 桥。 |
| `webview-android` | Android 系统 WebView 实现。 |
| `webview-ios` | iOS WKWebView 实现。 |
| `webview-desktop` | Desktop JCEF 实现。 |
| `webview-browser` | JS/Wasm 浏览器新窗口或标签页实现。 |

大多数 Compose Multiplatform 项目只需要依赖 `webview-compose`。若应用不使用 Compose，按目标平台选择
`webview-api`、`webview-extension-api` 与对应的平台实现模块即可。

## 文档

- [使用指南](docs/使用指南.md)：统一接入、各平台嵌入方式和 API 参考。
- [架构说明](docs/架构说明.md)：模块边界、安全模型和生命周期。
- [开发指南](docs/开发指南.md)：本地构建、测试和开发流程。
- [贡献指南](docs/贡献指南.md)：分支、提交、PR 与代码审查要求。
- [发布说明](docs/发布说明.md)：维护者的版本与发布流程。
