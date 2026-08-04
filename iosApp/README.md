# iOS 宿主

`iosApp.xcodeproj` 是 MultiWeb Compose 示例的 iOS 运行宿主。它在构建时调用 Gradle 的
`embedAndSignAppleFrameworkForXcode` 任务，将 `sample-compose` 生成的 `MultiWebSample` Framework
嵌入应用。

## 运行

1. 在 Android Studio 中打开本工程后，使用 Xcode 打开 `iosApp/iosApp.xcodeproj`。
2. 选择 `iosApp` scheme 与一台 iPhone 模拟器。
3. 点击 Run；首次构建会下载或生成 Kotlin/Native Framework。

本机命令行若仍指向 Command Line Tools，可先执行：

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

也可以不用修改全局设置，直接运行：

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```
