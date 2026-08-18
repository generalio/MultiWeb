# MW-20260818-004 最小角色编排契约

- 需求来源：维护者确认按最小 Agent 模式执行，并授权收紧默认协作规则。
- 目标：将工作流协议、角色模板和回归测试统一为默认 `Planner -> 单一 Implementer -> Integrator -> Verify-Reviewer` 的顺序模式。
- 非目标：不修改产品模块、公共 API、API 基线、`tools/agent_workflow.py`、Gradle、发布配置、历史账本或历史 worktree；不生成 `handoffs/`。
- 基线提交 SHA：`8605c85e838507df677383bbf154ff6b665e21ea`。
- 模式：`FULL`。本任务影响共享治理协议，必须经过独立 Planner、单一 Implementer、Integrator 与固定候选审查。
- 账本路径：`.codex/workflow/runs/MW-20260818-004/`。
- Planner：`/root/minimal_role_planner`，结论：`APPROVED`，无 P0/P1。
- 第二个 Implementer：不批准。平台测试是同一 Implementer 的验证责任，不构成新增平台 Agent。

## 计划与所有权

| Agent | 独占文件或模块 | 前置依赖 | 返回的阶段信息 | 最小验证 |
| --- | --- | --- | --- | --- |
| Planner | 只读治理协议与角色配置 | 账本初始化 | 定稿契约、DAG、影响分析 | 契约完整性复核 |
| Implementer | `AGENTS.md`、`docs/agent-workflow.md`、`docs/agent-workflow-autonomous-handoff.md`、`docs/agent-workflow-task-template.md`、`.codex/agents/workflow-*.toml`、`.codex/workflow/README.md`、`tools/tests/test_agent_workflow.py` | Planner 批准 | 修改映射、RED/GREEN 证据、未验证范围 | 聚焦静态测试、完整 Python 测试、`git diff --check` |
| Integrator | Implementer 转交的唯一 diff | 阶段信息完整且范围未越界 | 范围检查、候选 SHA、`git diff --check` | 候选提交、账本验证 |
| Verify-Reviewer | 干净 detached worktree 中的固定候选 | 候选 SHA | 命令结果、P0/P1/P2、唯一裁决 | 完整 Python 测试、固定 SHA 审查 |

- 默认拓扑：仅启动 `Planner -> 单一 Implementer -> Integrator -> Verify-Reviewer`，严格顺序执行。不得因 Android、iOS、Desktop、JS/Wasm 或测试平台自动创建、拆分或并发专项 Agent。
- 第二个 Implementer 例外：仅可由 Planner 在任务契约中书面批准，且必须同时满足范围不重叠、验证独立、两者范围均不包含 `webview-api`、`webview-extension-api`、API 基线、Gradle 设置、发布配置或跨平台契约；同一任务最多两个 Implementer。即使例外获批，所有 Implementer 完成后才可进入 Integrator。
- 禁止修改范围：产品模块、公共 API、API 基线、`tools/agent_workflow.py`、Gradle、发布配置、历史账本和历史 worktree。
- 需退回 Planner/Implementer 的语义冲突：提议新增第三个 Implementer、平台专项 Agent，或第二个 Implementer 缺少任一书面条件；角色数量、默认值、安全策略或测试预期冲突。

## 验收与验证

- 四个永久 `workflow-*.toml` 配置恰好为 Planner、Implementer、Integrator、Verify-Reviewer。
- 五份治理文档与四份角色配置均表达默认拓扑、平台禁止拆分和第二个 Implementer 的唯一例外。
- 模板要求 Planner 显式记录角色编排、是否批准第二个 Implementer、范围/验证/敏感模块条件。
- 静态回归测试先 RED 后 GREEN，覆盖上述规则与 Planner 的书面审批职责。
- 实施阶段命令：

  ```sh
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
    tools.tests.test_agent_workflow.AgentWorkflowCliTest.test_workflow_documents_and_role_configs_enforce_minimal_role_topology -v
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.tests.test_agent_workflow -v
  git diff --check
  ```

- 候选版本命令：

  ```sh
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.tests.test_agent_workflow -v
  git diff --check
  python3 tools/agent_workflow.py validate .codex/workflow/runs/MW-20260818-004
  ```

- 公共 API：不受影响；验证证据：候选差异仅涉及治理文档、角色配置与 Python 静态测试，未修改 API 基线或公共接口。
- Android：不受影响；验证证据：候选未修改 Android 产品模块，仅收敛 Agent 编排规则。
- iOS：不受影响；验证证据：候选未修改 iOS 产品模块，仅收敛 Agent 编排规则。
- Desktop：不受影响；验证证据：候选未修改 Desktop 产品模块，仅收敛 Agent 编排规则。
- JS/Wasm：不受影响；验证证据：候选未修改 JS/Wasm 产品模块，仅收敛 Agent 编排规则。
- 安全默认值影响：无；不放宽导航、JS 桥、Cookie、文件访问或下载权限。
- 已知风险：静态规则只能约束仓库协议与角色提示词，实际调度仍由 Supervisor 按任务契约执行。
