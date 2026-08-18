# MultiWeb 多 Agent 开发工作流

## 触发与角色

中大型需求默认执行完整工作流。Supervisor 创建任务账本并推进状态；Planner 只读生成契约和 DAG；Implementer 仅在
独占 worktree 修改契约授权范围；Integrator 只处理机械冲突并固定候选提交；Verify-Reviewer 只读审查固定候选。
角色配置位于 `.codex/agents/`，不得覆盖用户全局 Codex 配置或扩大父会话权限。

Planner 判定 `TRIVIAL` 时必须在任务契约中说明理由、独占范围和快速路径验证命令。不能启动所需角色、发现语义冲突、
P0/P1、候选 SHA 变化或需要 GitHub/凭据授权时，Supervisor 必须暂停或阻断并记录唯一下一步，不能以单 Agent 静默完成。

## 账本与状态

每个任务使用 `.codex/workflow/runs/<task-id>/`，只保存公开的状态、相对证据路径和 Git SHA：

```text
state.json  events.jsonl  task-contract.md  plan.json
candidate.sha  review-report.md  resume.md
```

`state.json` 使用 schemaVersion `1`，包含 `taskId`、`baselineSha`、`status`、`mode`、`currentStage`、`attempts`、
`candidateSha`、`verdict`、`nextAction`、`lastEventAt` 和 `stopReason`；`PAUSED` 额外记录 `pausedFromStatus`。事件必须
连续、时间严格递增，末事件时间必须等于 `lastEventAt`。

默认不得生成或要求 `handoffs/` 阶段交接文档。只有维护者明确指出上下文超出并要求时，
才允许创建并引用该目录中的文件。历史账本中的交接文件保留不删。任务契约、计划、
候选 SHA
和审查报告是账本或门禁证据，不属于交接文档。

| 状态 | 可进入的下一状态 |
| --- | --- |
| `PLANNED` | `IMPLEMENTING`、`PAUSED` |
| `IMPLEMENTING` | `READY_TO_INTEGRATE`、`PAUSED`、`BLOCKED` |
| `READY_TO_INTEGRATE` | `VALIDATING`、`BLOCKED` |
| `VALIDATING` | `REVIEWED`、`BLOCKED` |
| `REVIEWED` | `COMMIT_READY`、`IMPLEMENTING`、`BLOCKED` |
| `COMMIT_READY` | `PR_READY` |
| `PAUSED` | 中断前状态、`BLOCKED` |

每个阶段最多自动尝试两次。返工必须从 `REVIEWED` 回到 `IMPLEMENTING`，清除旧审查绑定后重新集成和审查。

## 命令与证据

```shell
python3 tools/agent_workflow.py validate .codex/workflow/runs/<task-id>
python3 tools/agent_workflow.py transition .codex/workflow/runs/<task-id> <NEXT_STATUS> --actor <ROLE> --evidence <PATH>
python3 tools/agent_workflow.py transition .codex/workflow/runs/<task-id> VALIDATING --actor INTEGRATOR --evidence candidate.sha --candidate-sha <SHA>
python3 tools/agent_workflow.py transition .codex/workflow/runs/<task-id> REVIEWED --actor VERIFY_REVIEWER --evidence review-report.md --verdict PASS
python3 tools/agent_workflow.py transition .codex/workflow/runs/<task-id> PAUSED \
  --actor SUPERVISOR --evidence task-contract.md \
  --stop-reason "<原因>" --next-action "<唯一下一步>"
python3 tools/agent_workflow.py resume .codex/workflow/runs/<task-id>
```

普通阶段迁移使用既有 `task-contract.md`；`VALIDATING` 使用 `candidate.sha`；`REVIEWED`、
`COMMIT_READY`、`PR_READY` 和返工使用 `review-report.md`。进入 `PAUSED` 或 `BLOCKED` 时，
`--stop-reason` 与 `--next-action` 必须同时为非空单行；其他目标状态携带任一停止参数
必须失败，且 `state.json` 与 `events.jsonl` 不得变更。成功停止会把两项写入
现有状态字段，并在事件摘要中记录。

验证器只接受任务目录内常规证据文件，拒绝关键账本符号链接。它使用 Unix `fcntl` 非阻塞租约，要求 Python `>=3.11`；
Windows 或不具备 `fcntl` 的环境应记录为验证限制。进入 `COMMIT_READY` 前必须存在同一候选 SHA 的 `candidate.sha`、
`review-report.md`、`PASS` 及无未解决 P0/P1。

## 阶段信息与裁决

Implementer 必须向 Supervisor 返回任务/基线、实际修改文件与所有权映射、
可应用 diff 或 worktree、命令退出结果、未验证范围，以及公共 API、平台差异和
安全默认值影响。
Integrator 必须检查改动范围
不重叠并记录 `git diff --check`。

Verify-Reviewer 先运行契约的最小验证，再检查公共 API、跨平台一致性、JS 桥、安全默认值和受影响测试。报告必须写入
候选 SHA、命令与退出结果、未验证范围、P0/P1/P2 和唯一的 `PASS` 或 `REJECT`。P0/P1 必须 `REJECT`；P2 不阻断但
必须进入 PR 风险说明。PR 正文的工作流记录固定填写“任务契约路径”“候选提交 SHA”“账本验证”和“审查裁决”，后两项
均为 `PASS`；每个平台和公共 API 使用“受影响/不受影响；验证证据：...”格式，CI 会校验其与契约一致。
