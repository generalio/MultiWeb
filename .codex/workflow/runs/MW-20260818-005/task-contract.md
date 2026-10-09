# MW-20260818-005 PR 全量汇总证据契约

- 需求来源：PR #25 治理门禁要求 PR 正文的唯一账本记录与完整 PR 差异一致。
- 目标：为完整差异 `4320a062d641c84c9ed5ec67e1af09551f62d430..3ba4d6d41c327650ace3555aebc832cce1e71275` 建立唯一、可复核的汇总证据；固定候选保持不变，PR 正文只引用本任务账本。
- 非目标：不修改候选产品代码、API 基线、Gradle、发布配置、既有任务账本或旧远端分支；不创建 `handoffs/`。
- 基线提交 SHA：`4320a062d641c84c9ed5ec67e1af09551f62d430`。
- 固定候选 SHA：`3ba4d6d41c327650ace3555aebc832cce1e71275`。
- 模式：`FULL`。完整范围涉及公共扩展 API、跨平台 JS 桥安全行为与共享 KMP 代码，不能使用 `TRIVIAL` 快速路径。
- 账本路径：`.codex/workflow/runs/MW-20260818-005/`。
- Planner：`/root/pr_evidence_planner`；不批准第二个 Implementer，不创建平台专项 Agent。

## DAG 与所有权

`Planner -> 单一 Implementer -> Integrator -> Verify-Reviewer -> Supervisor 更新 PR 正文`

| 角色 | 独占范围或职责 | 依赖 | 最小验证 |
| --- | --- | --- | --- |
| Planner | 只读完整差异、既有账本与 PR 门禁，输出汇总契约 | 无 | 契约完整性与影响映射 |
| 单一 Implementer | 仅生成账本文本、候选 SHA、审查报告和 PR 正文内容建议 | Planner | SHA、平台标签和证据一致性自检 |
| Supervisor | 唯一可落盘本任务账本并迁移状态 | Implementer 建议 | `agent_workflow.py validate` |
| Integrator | 仅核对建议、既有候选和账本状态，创建账本记录提交 | Supervisor 已落盘的证据 | `git diff --check`、候选祖先关系、账本验证 |
| Verify-Reviewer | 干净 worktree 对固定候选做只读完整差异审查 | Integrator | 固定 SHA 审查与契约命令 |

禁止修改范围：所有产品模块、API 基线、Gradle、发布配置、既有任务账本和候选提交。`MW-20260818-005` 账本文件仅由 Supervisor 落盘。

## 完整差异与影响

- 公共 API：受影响；验证证据：公开 `ScriptBridgeOriginPolicy.ExactHttpsHosts` 收紧输入与集合快照语义，`WebViewConfig.persistentSessionEnabled` 的跨平台默认值说明更新；需检查公开构造、`copy`、`component1`、相等性和 API 兼容性。
- Android：受影响；验证证据：精确 HTTPS JS 桥只向顶层窗口注入，并有同源 `srcdoc` 子框架回归；既有 Android unit、androidTest Kotlin 编译和 release assemble 通过。
- iOS：受影响；验证证据：空允许主机集合在可信 JS URL 入口显式拒绝；既有 iOS Simulator 测试 Kotlin 编译通过。
- Desktop：受影响；验证证据：空允许主机集合在可信 JS URL 入口显式拒绝；既有 Desktop JVM 测试通过。
- JS/Wasm：受影响；验证证据：`webview-extension-api` 是共享 KMP 公共代码，`ExactHttpsHosts` 构造与拒绝语义进入 JS/Wasm 消费端；既有示例 JS/Wasm 编译通过。
- 安全默认值：仅收紧 JS 桥来源边界。精确 HTTPS 主机必须为非空精确 ASCII 主机且使用 443 默认端口；Android 门面只向顶层窗口暴露；iOS/Desktop 空白名单拒绝。未放宽 Cookie、文件访问、下载或外部导航权限。
- 文档与治理：默认不生成 `handoffs/`，停止状态必须带原因和唯一下一步，默认角色链路固定为 Planner、单一 Implementer、Integrator、Verify-Reviewer。

## 验收与验证

- PR 正文与本契约的公共 API、Android、iOS、Desktop、JS/Wasm 均标记为 `受影响`，每项都有实际通过命令或既有验证证据。
- `candidate.sha`、`state.json`、`review-report.md` 与 PR 正文使用同一 `3ba4d6d41c327650ace3555aebc832cce1e71275`；候选必须是 PR 头祖先。
- Verify-Reviewer 必须运行：

  ```sh
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.tests.test_agent_workflow -v
  ./gradlew apiCheck :webview-android:assembleRelease :webview-android:compileDebugAndroidTestKotlin :webview-desktop:check :webview-ios:compileTestKotlinIosSimulatorArm64 :sample-compose:compileKotlinJs :sample-compose:compileKotlinWasmJs --no-parallel -Dorg.gradle.jvmargs=-Xmx2g
  git diff --check 4320a062d641c84c9ed5ec67e1af09551f62d430 3ba4d6d41c327650ace3555aebc832cce1e71275
  git status --short
  git rev-parse HEAD
  ```

- iOS 的最低编译验证为 `:webview-ios:compileTestKotlinIosSimulatorArm64`，不等价于 iOS/WKWebView 运行时测试；运行时未验证必须保留为未验证范围，不能标为通过。
- 账本验证必须在包含候选后 `MW-20260818-005` 记录的 PR HEAD worktree 执行：

  ```sh
  git merge-base --is-ancestor 3ba4d6d41c327650ace3555aebc832cce1e71275 HEAD
  python3 tools/agent_workflow.py validate .codex/workflow/runs/MW-20260818-005
  ```

- 未验证范围：Android System WebView instrumentation runtime、WKWebView runtime、JS/Wasm runtime 必须记录原因和替代编译证据。
- P1 退回条件：五个平台标签不一致、任一 SHA 不同或不为 PR 头祖先、账本不是 `PR_READY/PASS`、`apiCheck` 或受影响平台构建失败、来源校验被放宽、缺少实际通过证据或 PR 正文仍引用旧账本。
- P0：发现凭据泄露或可绕过来源限制向不可信文档暴露桥能力时立即阻断 PR。
