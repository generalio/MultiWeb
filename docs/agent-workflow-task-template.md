# MultiWeb 多 Agent 任务契约

> 未填写字段不得以“默认”或口头说明替代；契约完整并初始化账本后才能进入 `IMPLEMENTING`。

> 默认不得生成或要求 `handoffs/` 阶段交接文档。只有维护者明确指出上下文超出并要求时，
> 才允许创建并引用该目录中的文件。任务契约、计划、候选 SHA 和审查报告
> 属于账本或门禁证据，
> 不属于交接文档。

## 基本信息

- 任务编号：
- 需求来源：
- 目标与非目标：
- 基线提交 SHA：
- 模式：`FULL` / `TRIVIAL`；`TRIVIAL` 理由：
- 账本路径：`.codex/workflow/runs/<task-id>/`
- Planner：
- 角色编排：默认仅启动 `Planner -> 单一 Implementer -> Integrator -> Verify-Reviewer` 四个角色，并严格顺序执行。
- 不得因 Android、iOS、Desktop、JS/Wasm 或测试平台自动创建、拆分或并发专项 Agent。
- 第二个 Implementer：`不批准` / `批准`；若批准，Planner 必须在任务契约中书面批准第二个 Implementer，并填写下列条件。
- 第二个 Implementer 仅可由 Planner 在任务契约中书面批准，且必须同时满足范围不重叠、验证独立、两个范围均不含 `webview-api`、`webview-extension-api`、API 基线、Gradle 设置、发布配置或跨平台契约；同一任务最多两个 Implementer。
- 即使例外获批，也必须全体 Implementer 完成后才进入 Integrator。

## 计划与所有权

| Agent | 独占文件或模块 | 前置依赖 | 返回的阶段信息 | 最小验证 |
| --- | --- | --- | --- | --- |
|  |  |  |  |  |

- 禁止修改范围：
- 公共 API：`受影响` / `不受影响`；验证证据：
- Android：`受影响` / `不受影响`；验证证据：
- iOS：`受影响` / `不受影响`；验证证据：
- Desktop：`受影响` / `不受影响`；验证证据：
- JS/Wasm：`受影响` / `不受影响`；验证证据：
- 安全默认值影响：导航 / JS 桥 / Cookie / 文件访问 / 下载 / 无：
- 需退回 Planner/Implementer 的语义冲突：

## 账本初始化

`state.json`：

```json
{
  "schemaVersion": 1,
  "taskId": "MW-YYYYMMDD-001",
  "baselineSha": "<40 位 SHA>",
  "status": "PLANNED",
  "mode": "FULL",
  "currentStage": "PLANNING",
  "attempts": { "PLANNING": 1, "IMPLEMENTING": 0, "VALIDATING": 0, "REVIEWING": 0 },
  "candidateSha": null,
  "verdict": null,
  "nextAction": "启动 Implementer",
  "lastEventAt": "<ISO 8601 时间>",
  "stopReason": null
}
```

`plan.json` 至少包含 `schemaVersion`、`taskId`、`baselineSha`、任务 DAG、独占范围、依赖、验证命令和验收标准。

进入 `PAUSED` 或 `BLOCKED` 时，Supervisor 必须同时传入非空单行的 `--stop-reason` 和
`--next-action`；其他状态传入任一停止参数必须失败且不写账本。普通阶段
使用 `task-contract.md`，
`VALIDATING` 使用 `candidate.sha`，`REVIEWED`、`COMMIT_READY`、`PR_READY` 和返工使用
`review-report.md`。

## 验收与验证

- 实施阶段命令：
- 候选版本命令：
- 无法执行的运行时范围、原因和替代证据：
- 公共 API 变更时的 `./gradlew apiCheck` 结果：

## 阶段信息与裁决

- 实施 Agent 返回：修改文件、worktree/diff、命令结果、风险：
- Integrator 返回：合并改动、机械冲突、候选 SHA、`git diff --check`：
- Verify-Reviewer：审查 SHA、命令结果、未验证范围、P0/P1/P2、`PASS` / `REJECT`：
- PR 记录：任务契约路径、候选提交 SHA、账本验证 `PASS`、审查裁决 `PASS` 及平台验证证据：
