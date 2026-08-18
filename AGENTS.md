# MultiWeb 工程约束

## 格式

- Kotlin 与 Gradle Kotlin DSL 统一使用空格缩进：Tab size 2、Indent 2、Continuation indent 4。
- 所有改动必须遵循 `.editorconfig`；提交前不得混入无关格式化。

## 架构

- `webview-api` 只能包含跨平台模型、策略和接口，禁止引用 Android、UIKit、WebKit、JCEF 或 Compose 类型。
- 平台实现必须位于独立模块；任何跨平台可观察行为变更都要补充或更新公共层契约测试。
- 安全相关能力保持最小权限默认值。新增放宽权限的开关时，必须写明默认值、作用平台与安全影响。
- `webview-desktop` 的 JCEF 应用实例属于进程级资源，只能由宿主初始化和销毁；控制器只能释放自身的浏览器与客户端。
- JCEF 无法按单个浏览器可靠控制的安全配置必须显式拒绝，禁止静默降级为运行时默认行为。
- JS/Wasm 平台只能以浏览器新窗口处理 URL；不得将浏览器全局 Cookie、缓存或 JavaScript 权限伪装为组件可控能力。

## 多 Agent 协作

- 中大型需求必须由 Supervisor 创建任务 ID、任务契约和 `.codex/workflow/runs/<task-id>/` 账本，再依次启动
  Planner、独占 worktree 的 Implementer、Integrator 和固定候选 SHA 的 Verify-Reviewer。
  没有任务契约、候选
  SHA 或 `PASS` 裁决不得进入下一状态。若当前会话无法启动所需子 Agent，必须记录阻断原因，不得静默降级为单 Agent。
- 默认不得生成或要求 `handoffs/` 阶段交接文档。只有维护者明确指出上下文超出并要求时，
  才允许创建并引用该目录中的文件。任务契约、计划、候选 SHA 与审查报告
  属于账本或门禁证据，
  不属于交接文档。
- Planner 必须记录基线 SHA、依赖、独占文件/模块范围、最小验证命令及公共 API、跨平台和安全影响。只有在契约明确
  `TRIVIAL` 理由和快速路径验证命令时，才可不走完整并行流程。
- Supervisor 每次状态变化先调用 `tools/agent_workflow.py transition` 写入账本，再执行下一动作；进入 `VALIDATING`
  必须传 `--candidate-sha`，进入 `REVIEWED` 必须传 `--verdict PASS|REJECT`。恢复前先 `validate`，再用 `resume`
  读取唯一下一步。进入 `PAUSED` 或 `BLOCKED` 必须同时传入非空单行的 `--stop-reason` 和
  `--next-action`；其他状态传入任一停止参数必须失败且不写账本。任务目录
  不得保存凭据、Token
  或完整环境变量。
- 并行实施必须使用相互隔离的 worktree；同一文件或模块同一时刻只能有一个 Implementer 写入。`webview-api`、
  `webview-extension-api`、API 基线、Gradle 设置、发布配置和跨平台契约必须串行处理。
- Integrator 只能应用任务契约授权且由 Supervisor 转交的改动，并解决不改变语义的
  机械冲突。接口签名、默认值、导航策略、JS 桥、安全设置或测试预期冲突必须退回原
  Implementer 或 Planner，
  不得在集成阶段自行修复。
- Verify-Reviewer 只能在干净 worktree 对固定候选 SHA 运行验证和审查，禁止修改候选代码、测试或 API 基线。候选
  SHA 变化后，先前裁决立即失效。P0/P1 必须 `REJECT` 并阻断提交/PR；P2 必须写入风险与兼容性说明。
- 项目角色配置位于 `.codex/agents/`。角色须先阅读本文件、`docs/agent-workflow.md` 和运行协议；
  优先级为 `AGENTS.md`、`docs/agent-workflow.md`、运行协议；维护者明确要求的额外交接文档
  仅作为补充信息。

## 注释与文档

- 关键业务代码、公共 API、枚举项以及公共数据类字段必须使用中文 KDoc 或行注释说明其职责、语义和约束。
- 注释应说明设计原因、边界条件或调用方需要关注的信息；禁止添加与代码字面含义重复的无效注释。
- 修改既有关键代码或公共 API 时，同步检查并更新其中文注释。新增英文注释前必须确认其为外部术语、协议名称或原文引用等必要场景。

## 测试与缺陷流程

- 每次改动后运行最小相关验证；验证命令和结果必须记录在交付说明中。
- 发现缺陷时，先增加可复现的失败测试，再修复并运行该测试与受影响回归测试。测试仍失败时继续定位和修复，不得以跳过测试作为完成标准。
- 公共 API 变更必须运行 `apiCheck`；修改 API 基线只能在确认兼容性策略后进行。
- `webview-test-fixtures` 仅供工程内测试和示例使用，禁止应用发布约定插件、生成 Maven publication 或上传到任何远程仓库。
- 新增或修改 KMP target 时，必须分别编译 Android、iOS、JVM、JS 与 Wasm，并记录无法执行的运行时测试范围。
- Compose 示例的公共操作逻辑必须与原生视图嵌入代码分离，并使用 `webview-test-fixtures` 的
  `FakeWebViewController` 编写非设备测试；Android、iOS 与 JCEF 的真实运行时测试不可由该替身代替。

## 提交

- 每个完成且已验证的改动阶段都必须创建一个单独 Git commit。
- 提交前检查 `git status` 与 `git diff --check`，不得提交构建产物、凭据或无关文件。
- 创建或更新 PR 前必须完成一次 MultiWeb 代码审查，复查最终 diff、公共 API 兼容性、跨平台一致性、JS 桥安全边界与受影响测试；存在未解决的 P0/P1 问题时禁止提交或推送。

## 发布

- 发布凭据只能从 Gradle 属性或环境变量读取，禁止写入 `gradle.properties`、源码、文档示例或 Git 历史。
- POM 的仓库地址、许可证和开发者信息未确认时必须参数化配置，禁止填入推测值；发布前由仓库所有者提供实际信息。
- Maven Central 正式发布必须经过已验证的 Central Portal 命名空间与 GPG 签名；普通构建和 GitHub Packages 发布不得隐式触发 Central 上传。

## GitHub Actions

- Pull Request 与主分支工作流必须至少运行公共 API 校验和受影响平台的构建验证。
- GitHub Packages 发布仅可由 `vX.Y.Z` 形式的标签触发；工作流发布版本必须由标签派生，禁止使用 `-SNAPSHOT` 版本。
- 发布工作流必须显式声明最小权限，并只使用 GitHub Actions 提供的短期 `GITHUB_TOKEN`。
