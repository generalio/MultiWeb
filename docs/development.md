# 开发指南

## 环境

- JDK 17：当前 Gradle Wrapper 为 8.10.2，使用 JDK 17 可避免 Gradle JVM 兼容性问题。
- Android SDK：`platforms;android-35` 与 `build-tools;35.0.0`。
- macOS + Xcode：iOS 编译与运行需要完整 Xcode；仅 Command Line Tools 不足以运行 iOS 宿主。
- Desktop：macOS 上 JCEF 与 Compose Swing 需要 JetBrains Runtime 21。示例构建脚本已为 `desktopRun` 配置。
- Node.js：JS/Wasm 浏览器任务由 Gradle 自动解析 Node 与 Yarn 分发。

不要把 Central Portal 令牌、GitHub Token 或 GPG 私钥写入项目文件。发布凭据只放在 GitHub Actions Secret
或本机 `~/.gradle/gradle.properties`。

## 常用命令

```shell
# 检查公共 API 兼容性。
./gradlew apiCheck

# Android 组件单元测试与 Release 编译。
./gradlew :webview-android:test :webview-android:assembleRelease

# Desktop 组件测试。
./gradlew :webview-desktop:test

# 示例的 Android 编译与 Desktop 公共测试。
./gradlew :sample-compose:assembleDebug :sample-compose:desktopTest

# iOS Framework 编译。
./gradlew :sample-compose:linkDebugFrameworkIosSimulatorArm64

# JS/Wasm 可执行文件编译。
./gradlew :sample-compose:compileKotlinJs :sample-compose:compileKotlinWasmJs
```

任务名称可能因 Kotlin 插件升级而变化。执行前可用 `./gradlew :sample-compose:tasks --all` 查询当前可用任务。

## 修改流程

1. 从最新 `main` 创建 `generalio/<类型>/<主题>` 分支，例如 `generalio/feature/script-bridge`；类型仅可为
   `feature`、`fix`、`docs`、`chore`、`refactor` 或 `test`。
2. 阅读根目录 `AGENTS.md`，确认模块边界、中文注释、缩进和测试要求。
3. 先在公共 API 或对应平台模块添加失败测试，复现要修复的问题。
4. 以最小范围实现修复；公共 API、关键字段和非显然安全约束同步添加或更新中文 KDoc。
5. 先运行受影响模块的测试，再运行 `apiCheck` 和受影响平台编译。
6. 检查 `git diff --check` 与 `git status --short`，确认未包含构建产物、凭据或无关改动。
7. 一个完成且验证过的阶段对应一个独立 commit；推送分支并创建合并到 `main` 的 PR。
8. 完整填写 PR 模板中的开发记录，等待所有必需 CI 检查通过和审查批准后合并；禁止直接推送 `main`。

创建开发分支的示例：

```shell
git switch main
git pull --ff-only origin main
git switch -c generalio/feature/script-bridge
```

## 格式与文档

Kotlin 和 Gradle Kotlin DSL 使用空格缩进：Tab size 为 2、Indent 为 2、Continuation indent 为 4。
`.editorconfig` 是唯一格式来源，不应在无关文件中做整文件格式化。

公共 API、枚举项、公共数据类字段和关键安全逻辑必须有中文注释。注释应说明调用约束、设计原因或平台差异，
不要重复代码字面语义。改动公开行为时，同步更新 README 和相关 `docs/` 页面。

## 测试策略

| 范围 | 首选验证 |
| --- | --- |
| 公共模型、导航策略、扩展契约 | `commonTest` 或对应模块测试。 |
| Android 原生行为 | `:webview-android:test`；真实 WebView 行为另在设备或模拟器验证。 |
| Desktop JCEF 行为 | `:webview-desktop:test`；真实 JCEF 生命周期另运行 Compose Desktop 示例。 |
| iOS WebKit 行为 | 编译 Framework 后，在 `iosApp` 中以模拟器或真机运行。 |
| JS/Wasm | 编译目标，并在浏览器中验证新窗口导航。 |
| 公共 API | `apiCheck`。 |

`webview-test-fixtures` 仅支持仓库内测试与示例，不能作为对外依赖或发布测试覆盖率的替代品。

GitHub Actions 会在以 `main` 为目标分支的 PR 和 `main` 推送时执行以下最低校验：

```shell
./gradlew apiCheck :webview-android:assembleRelease :webview-desktop:check \
  :sample-compose:linkDebugFrameworkIosSimulatorArm64 :sample-compose:compileKotlinJs \
  :sample-compose:compileKotlinWasmJs --no-parallel -Dorg.gradle.jvmargs=-Xmx2g
```

该校验不替代 Android/iOS 真机或模拟器运行时测试，也不替代 JS/Wasm 浏览器运行时验证。无法执行的运行时测试
必须写入 PR 的“验证命令与结果”章节。

## 版本与发布

日常开发版本保持为下一个 `-SNAPSHOT`。准备正式版本时，先完成构建与 API 校验，再创建并推送
`vX.Y.Z` 标签。GitHub Actions 从标签派生版本并发布正式组件。

对外发布的模块固定为 `webview-api`、`webview-extension-api`、`webview-android`、`webview-ios`、
`webview-desktop` 和 `webview-browser`。`webview-test-fixtures` 使用无发布约定插件，任何发布前的任务检查
都不应出现它的 publication。完整凭据和发布步骤见 [发布说明](publishing.md)。
