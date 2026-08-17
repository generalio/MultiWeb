# MW-20260817-001 任务契约

## 基本信息

- 需求来源：`docs/agent-workflow-autonomous-handoff.md`。
- 目标：交付可恢复、可验证的 MultiWeb Agent 工作流，并修复审查发现的 Desktop 关闭竞态和 iOS 文件选择器安全边界。
- 非目标：不推送分支、不创建远程 PR、不修改产品功能以外的行为。
- 基线提交 SHA：`645e0bfe043b17a3db22cfaef080914e9e1fe6d8`。
- 模式：`FULL`；理由：涉及公共 API 基线、CI 门禁、多个平台实现与安全默认值。
- 账本路径：`.codex/workflow/runs/MW-20260817-001/`。
- Planner：`workflow-planner`。

## 计划与所有权

| Agent | 独占范围 | 前置依赖 | 交接产物 | 最小验证 |
| --- | --- | --- | --- | --- |
| Workflow Implementers | `.codex/agents/`、`.codex/workflow/`、`tools/`、工作流文档与 PR 门禁 | 无 | 六个候选提交中的工作流部分 | Python 账本测试、YAML 与门禁正反例 |
| Desktop Implementer | `webview-desktop` | 无 | `d11e8b8` | `:webview-desktop:test` |
| iOS Implementer | `webview-ios` | 无 | `b56d13c` | `:webview-ios:compileTestKotlinIosSimulatorArm64` |
| Verify-Reviewer | 固定候选，只读 | 全部实施交接 | `review-report.md` | 契约命令与跨平台审查 |

- 禁止修改范围：主工作区既有未提交文件、远程仓库与发布配置。
- 公共 API：受影响；验证证据：`./gradlew apiCheck --no-daemon` 通过。
- Android：受影响；验证证据：`./gradlew :webview-android:assembleRelease --no-daemon` 通过。
- iOS：受影响；验证证据：`./gradlew :webview-ios:compileTestKotlinIosSimulatorArm64 --no-daemon` 通过。
- Desktop：受影响；验证证据：`./gradlew :webview-desktop:test --no-daemon` 通过。
- JS/Wasm：受影响；验证证据：`./gradlew :webview-browser:jsBrowserTest :webview-browser:wasmJsBrowserTest --no-daemon` 的 JS 与 Wasm ChromeHeadless 报告各 3/3 通过。
- 安全默认值影响：iOS 文件选择器仅接受绝对、无 host、无 query/fragment 的 `file://` URI；未放宽 JS 桥、Cookie、文件访问或下载默认权限。
- 需退回 Planner/Implementer 的语义冲突：无。

## 验收与验证

- 候选 SHA：`b56d13c3f02b4aaa2866c482f3fa8fe63920dfb0`。
- 实施与候选验证：Python 账本单测 40/40、Desktop 单测、iOS 测试编译、`apiCheck`、Android Release、Compose JS/Wasm 编译均通过。
- 无法执行的运行时范围：iOS 18.4+ 文件面板需要带 Xcode 的模拟器或真机；Desktop JCEF/Compose 首帧和退出竞态需要真实窗口；最终复审的 JS 浏览器重跑受 Yarn network mutex 阻塞。

## 审查与 PR

- Integrator：固定 `b56d13c3f02b4aaa2866c482f3fa8fe63920dfb0`，并通过 `git diff --check 645e0bf...b56d13c`。
- Verify-Reviewer：裁决 `PASS`，详见 `review-report.md`。
- PR 记录：使用 `pr-body.md`；候选 SHA、账本验证与审查裁决均在其中固定填写。
