# Agent Workflow 运行账本

每个中大型任务在 `runs/<task-id>/` 建立独立账本。账本只保存可公开的任务状态、证据相对路径和提交 SHA，禁止写入 Token、凭据或完整环境变量。

## 命令

在仓库根目录执行：

```shell
python3 tools/agent_workflow.py validate .codex/workflow/runs/<task-id>
python3 tools/agent_workflow.py transition .codex/workflow/runs/<task-id> <NEXT_STATUS> --actor <ROLE> --evidence <PATH> [--candidate-sha <SHA>] [--verdict PASS|REJECT]
python3 tools/agent_workflow.py resume .codex/workflow/runs/<task-id>
```

默认不得生成或要求 `handoffs/` 阶段交接文档。只有维护者明确指出上下文超出并要求时，
才允许创建并引用该目录中的文件。

在获取 `.workflow.lock` 前，`transition` 会先校验目标状态与 `--stop-reason`、`--next-action` 的组合。
目标为 `PAUSED` 或 `BLOCKED` 时，两项必须同时为非空单行；其他目标状态不得传入任一项。
无效组合会在创建或获取锁前失败。通过该校验的 `transition`、`validate` 和 `resume` 会获取
运行目录内 `.workflow.lock` 的非阻塞 `fcntl` 租约。该能力要求 Unix-like 系统和 Python 3.11+，
是当前的 P2 平台边界。租约被其他 Supervisor 持有时，命令会立即失败，
不会读取或改写账本；
`resume` 因此可确认不存在另一份有效租约。除这个运行时锁文件外，`resume` 完全只读，
输出当前状态、阻断原因、唯一恢复目标和唯一下一步。

`validate` 检查状态字段、Git 可解析的基线/候选 SHA、任务契约、计划、完整事件链和审查证据。`transition` 仅接受状态机允许的下一状态：它先追加并 `fsync` 对应事件，再以同目录临时文件原子替换 `state.json`。如果事件追加失败，状态保持不变；如果替换在事件成功后中断，下一次 `validate` 会因事件末状态不一致而拒绝继续。

`--evidence` 必须是运行目录内已存在的文件。绝对路径或符号链接解析后越出该目录会被拒绝。进入 `VALIDATING` 时必须同时传入 `--candidate-sha`，且该值必须与已生成的 `candidate.sha` 一致；进入 `REVIEWED` 时必须传入 `--verdict PASS|REJECT`，并与审查报告一致。关键账本文件不得是符号链接。

## 必需文件

```text
runs/<task-id>/
├── state.json
├── task-contract.md
├── plan.json
├── events.jsonl                 # 首次迁移时创建
├── .workflow.lock                # 运行时租约文件，不写入任务数据
├── candidate.sha                # 生成候选后必需
├── review-report.md             # REVIEWED 后必需
└── resume.md                    # 可选的人类可读恢复上下文
```

`state.json` 使用 schemaVersion `1`，必须包含 `taskId`、`baselineSha`、`status`、`mode`、`currentStage`、`attempts`、`candidateSha`、`verdict`、`nextAction`、`lastEventAt` 和 `stopReason`。`PAUSED` 时还必须有 `pausedFromStatus`，且只能恢复到该状态或迁移为 `BLOCKED`；恢复完成后必须移除该字段。

自动尝试次数上限为两次。初始规划计为一次；`PLANNED -> PAUSED -> PLANNED` 会增加 `attempts.PLANNING`，达到上限后必须保持 `PAUSED` 或迁移为 `BLOCKED`。实施、验证和审查阶段同样受各自计数限制。

除从未离开 `PLANNED` 的新账本外，`events.jsonl` 必须存在，首条事件的 `from` 必须为 `PLANNED`，每条事件连续且证据路径位于任务目录内，末条 `to` 必须等于 `state.json.status`。

## 审查门禁

从 `REVIEWED` 进入 `COMMIT_READY` 前，验证器要求：

- `state.json.verdict` 与审查报告均为 `PASS`；
- `candidateSha`、`candidate.sha`、审查报告中的候选 SHA 完全一致且可由 Git 解析；
- 审查报告没有未解决的 `P0` 或 `P1`；明确的“无 P1”“P1 已修复”等记录不阻断；
- 候选 SHA 自审查后没有变化。

任一项失败都会拒绝迁移，并在错误信息中给出任务目录和可执行修复建议。

`REJECT` 报告可以记录待返工的 P0/P1，并可从 `REVIEWED` 返回 `IMPLEMENTING`；只有 PASS 证据链或 `COMMIT_READY`/`PR_READY` 会被未解决的 P0/P1 阻断。

`REVIEWED -> IMPLEMENTING` 会清除旧的 `reviewedCandidateSha`。返工后的新候选必须再次经过 `VALIDATING -> REVIEWED`，并由新的报告和 verdict 重新建立审查绑定；未走返工路径时，候选变化仍会使既有 `REVIEWED`、`COMMIT_READY` 或 `PR_READY` 证据失效。
