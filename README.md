# MultiWeb

面向 Kotlin Multiplatform 的原生 WebView 组件库。目前包含 Android 系统 WebView、iOS
WKWebView 与桌面 JCEF 实现；跨平台模型和导航策略位于 `webview-api`。

## 模块

| 模块 | 用途 |
| --- | --- |
| `webview-api` | 跨平台控制器、状态、请求和安全策略契约。 |
| `webview-extension-api` | 跨平台页面事件、受信任域名 JS 桥、下载、上下文操作与宿主 UI 扩展契约。 |
| `webview-android` | Android 系统 WebView 实现。 |
| `webview-ios` | iOS WKWebView 实现。 |
| `webview-desktop` | Swing/AWT JCEF 实现。 |
| `webview-browser` | JS/Wasm 浏览器新窗口导航实现。 |
| `webview-test-fixtures` | 面向使用方的契约测试夹具。 |
| `sample-compose` | 不发布的 Compose Multiplatform 集成示例。 |

## 依赖

发布后，在使用方的仓库中加入已发布仓库，再按平台添加对应模块：

```kotlin
dependencies {
  implementation("io.github.generalio.multiweb:webview-api:<version>")
  implementation("io.github.generalio.multiweb:webview-extension-api:<version>")
  implementation("io.github.generalio.multiweb:webview-android:<version>")
  implementation("io.github.generalio.multiweb:webview-desktop:<version>")
}
```

iOS 模块应添加到 KMP 的 `iosMain` source set。桌面模块依赖 JCEF 原生运行时，宿主必须先初始化
进程级 `CefApp`，再在 Swing EDT 中创建 `DesktopWebViewController`。

JS/Wasm 使用 `webview-browser`。该模块按导航策略在浏览器新标签页或新窗口中打开 URL，不提供嵌入式
WebView 或浏览器全局会话清理能力。

Android 与 Desktop 控制器均可通过 `extensions` 安装页面事件、下载、上下文操作与受信任域名 JS 桥。
Desktop 使用 JCEF `CefMessageRouter`，桥对象仅注入 HTTPS 精确主机名页面，并在原生回调中再次校验来源；
释放控制器时会先注销桥路由，再关闭浏览器。Android 可额外通过 `webViewFactory` 注入业务自定义 `WebView`
子类。控制器始终自行组合安全导航与原生 Client，业务代码不得覆盖 `WebViewClient`、`WebChromeClient` 或使用
`addJavascriptInterface` 暴露桥对象。

发布和仓库配置见 [发布说明](docs/publishing.md)。

GitHub Packages 的正式版本由 `vX.Y.Z` Git 标签自动发布，详细版本管理流程同样见发布说明。

## Compose 示例

`sample-compose` 以同一套 Compose 界面分别接入 Android 系统 WebView、iOS WKWebView、桌面
JCEF 与 JS/Wasm 浏览器新窗口实现。其公共命令逻辑使用 `FakeWebViewController` 测试；原生运行时
集成测试仍需要对应平台设备或宿主环境。常用构建命令：

```shell
./gradlew :sample-compose:assembleDebug :sample-compose:desktopTest
```
