## 目的

建立可恢复、可验证的 MultiWeb Agent 工作流，并修复 Desktop 浏览器创建前关闭竞态与 iOS 文件选择器 URI 安全边界。

## 改动内容

- 增加项目级角色配置、状态账本验证器、运行样例和恢复文档。
- 将候选 SHA、审查报告、平台验证记录接入 PR 治理门禁。
- 将既有扩展与生命周期接口成员改为默认空实现，并同步 API 基线，以保持既有实现方兼容。
- 在 Desktop 创建已发起时延后关闭到 `onAfterCreated`；iOS 仅接受无 host、query、fragment 的绝对 `file://` URI。

## 平台影响

- 公共 API：受影响；验证证据：`./gradlew apiCheck --no-daemon` 通过。
- Android：受影响；验证证据：`./gradlew :webview-android:assembleRelease --no-daemon` 通过。
- iOS：受影响；验证证据：`./gradlew :webview-ios:compileTestKotlinIosSimulatorArm64 --no-daemon` 通过。
- Desktop：受影响；验证证据：`./gradlew :webview-desktop:test --no-daemon` 通过。
- JS/Wasm：受影响；验证证据：`./gradlew :webview-browser:jsBrowserTest :webview-browser:wasmJsBrowserTest --no-daemon` 的 JS 与 Wasm ChromeHeadless 报告各 3/3 通过。

## 验证命令与结果

- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.tests.test_agent_workflow -v`：40/40 通过。
- `git diff --check 645e0bf...b56d13c`：通过。
- 已通过 `apiCheck`、Android Release、Desktop 单测、iOS 测试编译、Compose JS/Wasm 编译，以及 JS/Wasm ChromeHeadless 报告各 3/3。
- iOS 文件面板和 Desktop 真实窗口运行时测试受本机工具条件限制，详见风险与兼容性。

## 风险与兼容性

- API 兼容性：Kotlin 2.4.10 的基线表示变化尚未做旧版二进制扩展消费者链接回归；公共 API 的规范验证记录见“平台影响”。
- 安全：未放宽 JS 桥、Cookie、文件访问、下载或外部导航默认权限；iOS 文件选择器仅收紧 URI 接受范围。
- 平台：iOS 18.4+ 文件面板需要在 Xcode 模拟器或真机确认；Desktop JCEF/Compose 首帧与关闭竞态需要真实窗口验收；账本 CLI 仅支持 Python 3.11+ 的 Unix 环境。
- JS：最终干净审查的浏览器重跑被 Yarn network mutex 阻塞，但集成候选的 JS 与 Wasm ChromeHeadless 报告各 3/3 通过。

## 文档变更

- 新增 Agent 工作流、账本和任务契约文档，更新贡献指南与 PR 模板。

## Agent 工作流记录

- 任务契约路径：`.codex/workflow/runs/MW-20260817-001/task-contract.md`
- 候选提交 SHA：`b56d13c3f02b4aaa2866c482f3fa8fe63920dfb0`
- 账本验证：`PASS`
- 审查裁决：`PASS`
