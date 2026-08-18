# MultiWeb 自主工作流交接协议

本文档说明既有多 Agent 工作流的交接约定，不增加角色、状态、权限或审批能力。出现冲突时，文件优先级严格为
`AGENTS.md` > `docs/agent-workflow.md` > 本文档。

## 角色与最小交接

Supervisor 负责创建 `.codex/workflow/runs/<task-id>/` 账本、按既有状态机推进状态、启动下一角色，并保留公开的
相对证据路径和 Git SHA。进入下一状态前，先使用 `tools/agent_workflow.py transition` 记录状态变化和证据。

Planner 仅做只读分析，向 Supervisor 返回任务契约、基线 SHA、任务 DAG、独占文件或模块、依赖、最小验证命令、验收
标准，以及公共 API、平台和安全默认值影响。`TRIVIAL` 契约必须写明理由、独占范围和快速路径验证命令。

Implementer 只能在独立 worktree 内修改任务契约明确授权且由其独占的范围。交接至少包含：任务和基线、实际修改文件
与所有权映射、可应用 diff 或 worktree、执行命令及退出结果、未验证范围，以及公共 API、跨平台差异和安全默认值影响。

Integrator 只能应用完整交接并处理不改变语义的机械冲突。交接至少包含：已合并的交接项、范围不重叠检查、机械冲突
处理说明、`git diff --check` 结果，以及固定候选提交所需的候选 SHA 证据。是否创建提交仍须遵守 `AGENTS.md` 和维护者
的明确授权。

Verify-Reviewer 只在干净 worktree 对固定候选 SHA 做只读验证和审查。报告至少包含：候选 SHA、实际执行命令及退出
结果、未验证范围、公共 API、跨平台一致性、JS 桥与安全默认值检查、按 P0/P1/P2 分类且带文件位置的问题，以及唯一的
`PASS` 或 `REJECT` 裁决。审查报告只交给 Supervisor 记录。

## 账本、状态与证据

账本目录只保存公开状态、相对证据路径和 Git SHA；既有文件包括 `state.json`、`events.jsonl`、`task-contract.md`、
`plan.json`、`handoffs/`、`candidate.sha`、`review-report.md` 与 `resume.md`。事件必须连续且时间严格递增，最后一条
事件时间必须与 `state.json` 的 `lastEventAt` 相同。

状态仅按既有状态机迁移：`PLANNED` 可进入 `IMPLEMENTING` 或 `PAUSED`；`IMPLEMENTING` 可进入
`READY_TO_INTEGRATE`、`PAUSED` 或 `BLOCKED`；`READY_TO_INTEGRATE` 可进入 `VALIDATING` 或 `BLOCKED`；
`VALIDATING` 可进入 `REVIEWED` 或 `BLOCKED`；`REVIEWED` 可进入 `COMMIT_READY`、`IMPLEMENTING` 或 `BLOCKED`；
`COMMIT_READY` 可进入 `PR_READY`；`PAUSED` 只能回到中断前状态或进入 `BLOCKED`。每个阶段最多自动尝试两次。

每次状态变化必须由 Supervisor 先执行 `transition` 并提供任务目录内的常规证据文件。进入 `VALIDATING` 必须提供
`--candidate-sha <SHA>` 和 `candidate.sha` 证据；进入 `REVIEWED` 必须提供 `--verdict PASS|REJECT` 和
`review-report.md` 证据。进入 `COMMIT_READY` 前，`candidate.sha` 与 `review-report.md` 必须绑定同一候选 SHA，裁决为
`PASS`，且不存在未解决 P0/P1。

## 退回、阻断与恢复

接口签名、默认值、导航策略、JS 桥、安全设置或测试预期发生语义冲突时，Integrator 不得自行解决，必须退回原
Implementer 或 Planner。交接不完整、需要维护者或凭据授权、无法启动所需角色、发现 P0/P1 时，Supervisor 必须暂停或
阻断，记录原因和唯一下一步，不能静默以单 Agent 完成。

P0/P1 必须使 Verify-Reviewer 给出 `REJECT`，并阻断提交或 PR；P2 不阻断，但必须写入 PR 风险与兼容性说明。候选 SHA
一旦变化，之前的审查裁决立即失效；返工必须从 `REVIEWED` 回到 `IMPLEMENTING`，清除旧审查绑定后重新集成和审查。

恢复任务前，Supervisor 必须先执行：

```shell
python3 tools/agent_workflow.py validate .codex/workflow/runs/<task-id>
python3 tools/agent_workflow.py resume .codex/workflow/runs/<task-id>
```

只有 `validate` 成功后才能读取 `resume` 给出的唯一下一步并继续推进。
