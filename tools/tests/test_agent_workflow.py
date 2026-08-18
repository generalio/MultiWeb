"""agent_workflow 命令行工具的行为测试。"""

from __future__ import annotations

import json
import fcntl
import shutil
import subprocess
import tempfile
import unittest
import os
from datetime import datetime
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "tools" / "agent_workflow.py"
FIXTURE_ROOT = Path(__file__).parent / "fixtures" / "agent-workflow"


class AgentWorkflowCliTest(unittest.TestCase):
    """通过真实 CLI 验证运行账本的外部行为。"""

    def setUp(self) -> None:
        self._temporary_directory = tempfile.TemporaryDirectory(
            dir=REPOSITORY_ROOT,
            prefix="agent-workflow-test-",
        )
        self.workspace = Path(self._temporary_directory.name)

    def tearDown(self) -> None:
        self._temporary_directory.cleanup()

    def copy_fixture(self, name: str, destination_name: str | None = None) -> Path:
        destination = self.workspace / (destination_name or name)
        shutil.copytree(FIXTURE_ROOT / name, destination)
        return destination

    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(SCRIPT), *arguments],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def snapshot_run_directory(self, run_directory: Path) -> dict[str, bytes | None]:
        """记录运行目录的文件集合、空目录和文件字节，用于验证失败路径无副作用。"""
        return {
            path.relative_to(run_directory).as_posix(): path.read_bytes() if path.is_file() else None
            for path in sorted(run_directory.rglob("*"), key=lambda item: item.as_posix())
        }

    def test_all_workflow_role_configs_require_the_autonomous_handoff_protocol(self) -> None:
        protocol_path = REPOSITORY_ROOT / "docs" / "agent-workflow-autonomous-handoff.md"
        protocol_relative_path = protocol_path.relative_to(REPOSITORY_ROOT).as_posix()
        role_configuration_paths = (
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-implementer.toml",
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-integrator.toml",
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-planner.toml",
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-verify-reviewer.toml",
        )

        self.assertTrue(protocol_path.is_file(), f"缺少工作流协议文件：{protocol_relative_path}")
        for role_configuration_path in role_configuration_paths:
            self.assertIn(
                protocol_relative_path,
                role_configuration_path.read_text(encoding="utf-8"),
                f"{role_configuration_path.relative_to(REPOSITORY_ROOT)} 必须声明工作流协议文件",
            )

    def test_workflow_documents_and_role_configs_forbid_default_handoff_documents(self) -> None:
        default_handoff_policy = "默认不得生成或要求 `handoffs/` 阶段交接文档"
        workflow_document_paths = (
            REPOSITORY_ROOT / "AGENTS.md",
            REPOSITORY_ROOT / "docs" / "agent-workflow.md",
            REPOSITORY_ROOT / "docs" / "agent-workflow-autonomous-handoff.md",
            REPOSITORY_ROOT / "docs" / "agent-workflow-task-template.md",
            REPOSITORY_ROOT / ".codex" / "workflow" / "README.md",
        )
        role_configuration_paths = (
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-implementer.toml",
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-integrator.toml",
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-planner.toml",
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-verify-reviewer.toml",
        )

        for workflow_document_path in workflow_document_paths:
            self.assertIn(
                default_handoff_policy,
                workflow_document_path.read_text(encoding="utf-8"),
                f"{workflow_document_path.relative_to(REPOSITORY_ROOT)} 必须禁止默认阶段交接文档",
            )
        for role_configuration_path in role_configuration_paths:
            self.assertIn(
                default_handoff_policy,
                role_configuration_path.read_text(encoding="utf-8"),
                f"{role_configuration_path.relative_to(REPOSITORY_ROOT)} 必须禁止默认阶段交接文档",
            )

    def test_workflow_documents_and_role_configs_enforce_minimal_role_topology(self) -> None:
        default_topology = "默认仅启动 `Planner -> 单一 Implementer -> Integrator -> Verify-Reviewer` 四个角色，并严格顺序执行。"
        platform_agent_prohibition = "不得因 Android、iOS、Desktop、JS/Wasm 或测试平台自动创建、拆分或并发专项 Agent。"
        second_implementer_approval = (
            "第二个 Implementer 仅可由 Planner 在任务契约中书面批准，且必须同时满足范围不重叠、验证独立、"
            "两个范围均不含 `webview-api`、`webview-extension-api`、API 基线、Gradle 设置、发布配置或跨平台契约；"
            "同一任务最多两个 Implementer。"
        )
        integration_gate = "即使例外获批，也必须全体 Implementer 完成后才进入 Integrator。"
        workflow_document_paths = (
            REPOSITORY_ROOT / "AGENTS.md",
            REPOSITORY_ROOT / "docs" / "agent-workflow.md",
            REPOSITORY_ROOT / "docs" / "agent-workflow-autonomous-handoff.md",
            REPOSITORY_ROOT / "docs" / "agent-workflow-task-template.md",
            REPOSITORY_ROOT / ".codex" / "workflow" / "README.md",
        )
        role_configuration_paths = tuple(
            sorted((REPOSITORY_ROOT / ".codex" / "agents").glob("workflow-*.toml"))
        )

        self.assertEqual(
            tuple(path.name for path in role_configuration_paths),
            (
                "workflow-implementer.toml",
                "workflow-integrator.toml",
                "workflow-planner.toml",
                "workflow-verify-reviewer.toml",
            ),
            "永久工作流角色配置必须精确为四个最小角色",
        )
        for policy in (
            default_topology,
            platform_agent_prohibition,
            second_implementer_approval,
            integration_gate,
        ):
            for path in (*workflow_document_paths, *role_configuration_paths):
                self.assertIn(
                    policy,
                    path.read_text(encoding="utf-8"),
                    f"{path.relative_to(REPOSITORY_ROOT)} 必须声明最小角色编排规则：{policy}",
                )

        task_template = (REPOSITORY_ROOT / "docs" / "agent-workflow-task-template.md").read_text(
            encoding="utf-8"
        )
        planner_configuration = (
            REPOSITORY_ROOT / ".codex" / "agents" / "workflow-planner.toml"
        ).read_text(encoding="utf-8")
        self.assertIn("- 角色编排：", task_template)
        self.assertIn("- 第二个 Implementer：`不批准` / `批准`；", task_template)
        self.assertIn("Planner 必须在任务契约中书面批准第二个 Implementer", planner_configuration)

    def test_workflow_readme_describes_stop_option_validation_before_lock_acquisition(self) -> None:
        workflow_readme = REPOSITORY_ROOT / ".codex" / "workflow" / "README.md"
        content = workflow_readme.read_text(encoding="utf-8")

        self.assertIn(
            "在获取 `.workflow.lock` 前，`transition` 会先校验目标状态与 "
            "`--stop-reason`、`--next-action` 的组合。",
            content,
        )
        self.assertNotIn("三个命令都会先获取", content)
        self.assertNotIn("├── handoffs/", content)

    def test_validate_accepts_a_planned_run(self) -> None:
        run_directory = self.copy_fixture("valid")

        result = self.run_cli("validate", str(run_directory))

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("验证通过", result.stdout)

    def test_validate_rejects_a_missing_baseline_sha(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        del state["baselineSha"]
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("baselineSha", result.stderr)
        self.assertIn("请", result.stderr)

    def test_validate_rejects_an_unresolvable_baseline_sha(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["baselineSha"] = "deadbeef"
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("Git", result.stderr)

    def test_validate_rejects_an_unknown_status(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["status"] = "UNKNOWN"
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("status", result.stderr)

    def test_transition_rejects_an_illegal_status_change(self) -> None:
        run_directory = self.copy_fixture("valid")

        result = self.run_cli(
            "transition",
            str(run_directory),
            "PR_READY",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("PLANNED", result.stderr)
        self.assertIn("允许", result.stderr)

    def test_transition_to_stopped_states_requires_both_stop_options_without_mutating_ledger(self) -> None:
        option_sets = (
            ("neither", ()),
            ("reason-only", ("--stop-reason", "等待维护者授权")),
            ("action-only", ("--next-action", "恢复实施阶段")),
        )
        for next_status in ("PAUSED", "BLOCKED"):
            for label, stop_options in option_sets:
                with self.subTest(next_status=next_status, label=label):
                    run_directory = self.copy_fixture("valid", f"stop-options-{next_status}-{label}")
                    if next_status == "BLOCKED":
                        implementing = self.run_cli(
                            "transition",
                            str(run_directory),
                            "IMPLEMENTING",
                            "--actor",
                            "SUPERVISOR",
                            "--evidence",
                            "task-contract.md",
                        )
                        self.assertEqual(implementing.returncode, 0, implementing.stderr)
                        (run_directory / ".workflow.lock").unlink()
                    before_run_directory = self.snapshot_run_directory(run_directory)
                    self.assertNotIn(".workflow.lock", before_run_directory)

                    result = self.run_cli(
                        "transition",
                        str(run_directory),
                        next_status,
                        "--actor",
                        "SUPERVISOR",
                        "--evidence",
                        "task-contract.md",
                        *stop_options,
                    )

                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("--stop-reason", result.stderr)
                    self.assertIn("--next-action", result.stderr)
                    self.assertEqual(self.snapshot_run_directory(run_directory), before_run_directory)

    def test_transition_to_stopped_states_rejects_empty_or_multiline_stop_options(self) -> None:
        invalid_stop_options = (
            ("empty-reason", "", "恢复实施阶段"),
            ("blank-reason", "  ", "恢复实施阶段"),
            ("multiline-reason", "等待维护者\n授权", "恢复实施阶段"),
            ("empty-action", "等待维护者授权", ""),
            ("blank-action", "等待维护者授权", "  "),
            ("multiline-action", "等待维护者授权", "恢复\n实施阶段"),
        )
        for label, stop_reason, next_action in invalid_stop_options:
            with self.subTest(label=label):
                run_directory = self.copy_fixture("valid", f"invalid-stop-options-{label}")
                before_run_directory = self.snapshot_run_directory(run_directory)
                self.assertNotIn(".workflow.lock", before_run_directory)

                result = self.run_cli(
                    "transition",
                    str(run_directory),
                    "PAUSED",
                    "--actor",
                    "SUPERVISOR",
                    "--evidence",
                    "task-contract.md",
                    "--stop-reason",
                    stop_reason,
                    "--next-action",
                    next_action,
                )

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("非空单行", result.stderr)
                self.assertEqual(self.snapshot_run_directory(run_directory), before_run_directory)

    def test_transition_rejects_stop_options_for_non_stopped_states_without_mutating_ledger(self) -> None:
        option_sets = (
            ("reason-only", ("--stop-reason", "等待维护者授权")),
            ("action-only", ("--next-action", "恢复实施阶段")),
            (
                "both",
                ("--stop-reason", "等待维护者授权", "--next-action", "恢复实施阶段"),
            ),
        )
        for label, stop_options in option_sets:
            with self.subTest(label=label):
                run_directory = self.copy_fixture("valid", f"non-stop-options-{label}")
                before_run_directory = self.snapshot_run_directory(run_directory)
                self.assertNotIn(".workflow.lock", before_run_directory)

                result = self.run_cli(
                    "transition",
                    str(run_directory),
                    "IMPLEMENTING",
                    "--actor",
                    "SUPERVISOR",
                    "--evidence",
                    "task-contract.md",
                    *stop_options,
                )

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("PAUSED 或 BLOCKED", result.stderr)
                self.assertEqual(self.snapshot_run_directory(run_directory), before_run_directory)

    def test_transition_to_stopped_states_records_stop_context_in_state_event_and_resume(self) -> None:
        for next_status in ("PAUSED", "BLOCKED"):
            with self.subTest(next_status=next_status):
                run_directory = self.copy_fixture("valid", f"stopped-state-{next_status}")
                if next_status == "BLOCKED":
                    implementing = self.run_cli(
                        "transition",
                        str(run_directory),
                        "IMPLEMENTING",
                        "--actor",
                        "SUPERVISOR",
                        "--evidence",
                        "task-contract.md",
                    )
                    self.assertEqual(implementing.returncode, 0, implementing.stderr)
                stop_reason = f"{next_status} 等待维护者授权"
                next_action = f"{next_status} 恢复实施阶段"

                transition = self.run_cli(
                    "transition",
                    str(run_directory),
                    next_status,
                    "--actor",
                    "SUPERVISOR",
                    "--evidence",
                    "task-contract.md",
                    "--stop-reason",
                    stop_reason,
                    "--next-action",
                    next_action,
                )

                self.assertEqual(transition.returncode, 0, transition.stderr)
                state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
                event = json.loads((run_directory / "events.jsonl").read_text(encoding="utf-8").splitlines()[-1])
                resume = self.run_cli("resume", str(run_directory))
                self.assertEqual(state["status"], next_status)
                self.assertEqual(state["stopReason"], stop_reason)
                self.assertEqual(state["nextAction"], next_action)
                self.assertIn(stop_reason, event["summary"])
                self.assertIn(next_action, event["summary"])
                self.assertEqual(resume.returncode, 0, resume.stderr)
                self.assertIn(stop_reason, resume.stdout)
                self.assertIn(next_action, resume.stdout)

    def test_transition_records_a_legal_status_chain(self) -> None:
        run_directory = self.copy_fixture("valid")

        implementing = self.run_cli(
            "transition",
            str(run_directory),
            "IMPLEMENTING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
        )
        integrating = self.run_cli(
            "transition",
            str(run_directory),
            "READY_TO_INTEGRATE",
            "--actor",
            "IMPLEMENTER",
            "--evidence",
            "task-contract.md",
        )

        state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
        events = (run_directory / "events.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(implementing.returncode, 0, implementing.stderr)
        self.assertEqual(integrating.returncode, 0, integrating.stderr)
        self.assertEqual(state["status"], "READY_TO_INTEGRATE")
        self.assertEqual(state["currentStage"], "INTEGRATING")
        self.assertEqual(state["attempts"]["IMPLEMENTING"], 1)
        self.assertEqual(len(events), 2)
        self.assertFalse((run_directory / "handoffs").exists())

    def test_transition_cli_completes_a_full_passed_chain_without_editing_state(self) -> None:
        run_directory = self.copy_fixture("valid")
        candidate_sha = "b777d0fba4f598028469ecc00780530555aa7003"

        results = [
            self.run_cli(
                "transition",
                str(run_directory),
                "IMPLEMENTING",
                "--actor",
                "SUPERVISOR",
                "--evidence",
                "task-contract.md",
            )
        ]
        results.append(
            self.run_cli(
                "transition",
                str(run_directory),
                "READY_TO_INTEGRATE",
                "--actor",
                "IMPLEMENTER",
                "--evidence",
                "task-contract.md",
            )
        )
        (run_directory / "candidate.sha").write_text(f"{candidate_sha}\n", encoding="utf-8")
        results.append(
            self.run_cli(
                "transition",
                str(run_directory),
                "VALIDATING",
                "--candidate-sha",
                candidate_sha,
                "--actor",
                "INTEGRATOR",
                "--evidence",
                "candidate.sha",
            )
        )
        (run_directory / "review-report.md").write_text(
            f"候选 SHA: {candidate_sha}\n裁决: PASS\n无 P1\n",
            encoding="utf-8",
        )
        results.append(
            self.run_cli(
                "transition",
                str(run_directory),
                "REVIEWED",
                "--verdict",
                "PASS",
                "--actor",
                "VERIFY_REVIEWER",
                "--evidence",
                "review-report.md",
            )
        )
        results.append(
            self.run_cli(
                "transition",
                str(run_directory),
                "COMMIT_READY",
                "--actor",
                "SUPERVISOR",
                "--evidence",
                "review-report.md",
            )
        )
        results.append(
            self.run_cli(
                "transition",
                str(run_directory),
                "PR_READY",
                "--actor",
                "SUPERVISOR",
                "--evidence",
                "review-report.md",
            )
        )
        validation = self.run_cli("validate", str(run_directory))

        for result in results:
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(validation.returncode, 0, validation.stderr)
        state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(state["status"], "PR_READY")
        self.assertEqual(state["candidateSha"], candidate_sha)
        self.assertEqual(state["verdict"], "PASS")
        self.assertFalse((run_directory / "handoffs").exists())
        events = [
            json.loads(line)
            for line in (run_directory / "events.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        event_times = [datetime.fromisoformat(event["at"]) for event in events]
        self.assertTrue(all(previous < current for previous, current in zip(event_times, event_times[1:])))
        self.assertEqual(state["lastEventAt"], events[-1]["at"])

    def test_transition_to_validating_requires_candidate_sha_option(self) -> None:
        run_directory = self.copy_fixture("valid")
        self.assertEqual(
            self.run_cli(
                "transition",
                str(run_directory),
                "IMPLEMENTING",
                "--actor",
                "SUPERVISOR",
                "--evidence",
                "task-contract.md",
            ).returncode,
            0,
        )
        self.assertEqual(
            self.run_cli(
                "transition",
                str(run_directory),
                "READY_TO_INTEGRATE",
                "--actor",
                "IMPLEMENTER",
                "--evidence",
                "task-contract.md",
            ).returncode,
            0,
        )
        (run_directory / "candidate.sha").write_text(
            "b777d0fba4f598028469ecc00780530555aa7003\n",
            encoding="utf-8",
        )

        result = self.run_cli(
            "transition",
            str(run_directory),
            "VALIDATING",
            "--actor",
            "INTEGRATOR",
            "--evidence",
            "candidate.sha",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("--candidate-sha", result.stderr)

    def test_transition_to_reviewed_requires_verdict_option(self) -> None:
        run_directory = self.copy_fixture("validating")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n裁决: PASS\n",
            encoding="utf-8",
        )

        result = self.run_cli(
            "transition",
            str(run_directory),
            "REVIEWED",
            "--actor",
            "VERIFY_REVIEWER",
            "--evidence",
            "review-report.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("--verdict", result.stderr)

    def test_transition_rejects_commit_ready_after_a_reject_verdict(self) -> None:
        run_directory = self.copy_fixture("rejected")

        result = self.run_cli(
            "transition",
            str(run_directory),
            "COMMIT_READY",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("REJECT", result.stderr)
        self.assertIn("COMMIT_READY", result.stderr)

    def test_transition_rejects_a_pass_report_with_a_p1_finding(self) -> None:
        run_directory = self.copy_fixture("rejected")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["verdict"] = "PASS"
        state_path.write_text(json.dumps(state), encoding="utf-8")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n裁决: PASS\nP1: 未解决问题\n",
            encoding="utf-8",
        )

        result = self.run_cli(
            "transition",
            str(run_directory),
            "COMMIT_READY",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("P1", result.stderr)

    def test_validate_rejects_a_review_report_with_a_p1_finding(self) -> None:
        run_directory = self.copy_fixture("rejected")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["verdict"] = "PASS"
        state_path.write_text(json.dumps(state), encoding="utf-8")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n裁决: PASS\nP1: 未解决问题\n",
            encoding="utf-8",
        )

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("P1", result.stderr)

    def test_validate_invalidates_a_review_when_its_candidate_changes(self) -> None:
        run_directory = self.copy_fixture("candidate-changed")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["candidateSha"] = "deadbeef"
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("候选版本变化", result.stderr)

    def test_resume_reports_the_single_next_step_for_a_paused_run(self) -> None:
        run_directory = self.copy_fixture("paused")
        before_state = (run_directory / "state.json").read_bytes()
        before_events = (run_directory / "events.jsonl").read_bytes()

        result = self.run_cli("resume", str(run_directory))

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("当前状态: PAUSED", result.stdout)
        self.assertIn("阻断原因: 等待维护者授权", result.stdout)
        self.assertIn("唯一下一步: 恢复 IMPLEMENTING 阶段并等待维护者授权", result.stdout)
        self.assertEqual((run_directory / "state.json").read_bytes(), before_state)
        self.assertEqual((run_directory / "events.jsonl").read_bytes(), before_events)

    def test_validate_rejects_a_missing_required_state_field(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        del state["candidateSha"]
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("candidateSha", result.stderr)

    def test_validate_rejects_a_regressing_event_history(self) -> None:
        run_directory = self.copy_fixture("valid")
        events = (
            {
                "at": "2026-08-17T09:00:00+08:00",
                "actor": "SUPERVISOR",
                "from": "PLANNED",
                "to": "IMPLEMENTING",
                "evidence": "task-contract.md",
                "summary": "开始实施",
            },
            {
                "at": "2026-08-17T10:00:00+08:00",
                "actor": "SUPERVISOR",
                "from": "IMPLEMENTING",
                "to": "PLANNED",
                "evidence": "task-contract.md",
                "summary": "错误回退",
            },
        )
        (run_directory / "events.jsonl").write_text(
            "\n".join(json.dumps(event) for event in events) + "\n",
            encoding="utf-8",
        )

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("状态倒退", result.stderr)

    def test_validate_rejects_out_of_order_event_times(self) -> None:
        run_directory = self.copy_fixture("validating")
        events_path = run_directory / "events.jsonl"
        events = [json.loads(line) for line in events_path.read_text(encoding="utf-8").splitlines()]
        events[-1]["at"] = "2026-08-17T05:00:00+08:00"
        events_path.write_text("\n".join(json.dumps(event) for event in events) + "\n", encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("时间", result.stderr)

    def test_validate_rejects_equal_event_times(self) -> None:
        run_directory = self.copy_fixture("validating")
        events_path = run_directory / "events.jsonl"
        events = [json.loads(line) for line in events_path.read_text(encoding="utf-8").splitlines()]
        events[1]["at"] = events[0]["at"]
        events_path.write_text("\n".join(json.dumps(event) for event in events) + "\n", encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("严格", result.stderr)

    def test_validate_rejects_a_last_event_time_mismatch(self) -> None:
        run_directory = self.copy_fixture("validating")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["lastEventAt"] = "2026-08-17T09:00:00+08:00"
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("lastEventAt", result.stderr)

    def test_validate_requires_a_candidate_before_validating(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["status"] = "VALIDATING"
        state["currentStage"] = "VALIDATING"
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("candidateSha", result.stderr)

    def test_validate_requires_candidate_evidence_before_validating(self) -> None:
        run_directory = self.copy_fixture("validating")
        (run_directory / "candidate.sha").unlink()

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("candidate.sha", result.stderr)

    def test_transition_stops_after_two_automatic_attempts(self) -> None:
        run_directory = self.copy_fixture("candidate-changed")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["attempts"]["IMPLEMENTING"] = 2
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli(
            "transition",
            str(run_directory),
            "IMPLEMENTING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("PAUSED 或 BLOCKED", result.stderr)

    def test_transition_allows_a_fixed_passed_candidate_to_be_commit_ready(self) -> None:
        run_directory = self.copy_fixture("candidate-changed")

        transition = self.run_cli(
            "transition",
            str(run_directory),
            "COMMIT_READY",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )
        validation = self.run_cli("validate", str(run_directory))

        state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(transition.returncode, 0, transition.stderr)
        self.assertEqual(validation.returncode, 0, validation.stderr)
        self.assertEqual(state["status"], "COMMIT_READY")

    def test_transition_leaves_no_temporary_state_file(self) -> None:
        run_directory = self.copy_fixture("valid")

        result = self.run_cli(
            "transition",
            str(run_directory),
            "IMPLEMENTING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(list(run_directory.glob(".state.*.tmp")), [])

    def test_transition_rejects_evidence_outside_the_run_directory(self) -> None:
        run_directory = self.copy_fixture("valid")

        result = self.run_cli(
            "transition",
            str(run_directory),
            "IMPLEMENTING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "../../AGENTS.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("越界", result.stderr)

    def test_paused_run_can_only_resume_its_interrupted_status(self) -> None:
        run_directory = self.copy_fixture("paused")

        resume = self.run_cli("resume", str(run_directory))
        resumed = self.run_cli(
            "transition",
            str(run_directory),
            "IMPLEMENTING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
        )
        invalid_directory = self.copy_fixture("paused", "paused-invalid")
        invalid = self.run_cli(
            "transition",
            str(invalid_directory),
            "VALIDATING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
        )

        resumed_state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(resume.returncode, 0, resume.stderr)
        self.assertIn("唯一恢复目标: IMPLEMENTING", resume.stdout)
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        self.assertEqual(resumed_state["status"], "IMPLEMENTING")
        self.assertNotIn("pausedFromStatus", resumed_state)
        self.assertNotEqual(invalid.returncode, 0)
        self.assertIn("PAUSED", invalid.stderr)

    def test_reject_with_p1_can_be_validated_and_returned_to_implementation(self) -> None:
        run_directory = self.copy_fixture("rejected")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n裁决: REJECT\nP1: 需要返工\n",
            encoding="utf-8",
        )

        validation = self.run_cli("validate", str(run_directory))
        transition = self.run_cli(
            "transition",
            str(run_directory),
            "IMPLEMENTING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )

        self.assertEqual(validation.returncode, 0, validation.stderr)
        self.assertEqual(transition.returncode, 0, transition.stderr)

    def test_validate_requires_a_complete_event_chain_after_planning(self) -> None:
        missing_events = self.copy_fixture("candidate-changed")
        (missing_events / "events.jsonl").unlink()
        broken_start = self.copy_fixture("candidate-changed", "candidate-broken-start")
        broken_events = (broken_start / "events.jsonl").read_text(encoding="utf-8").splitlines()
        broken_first = json.loads(broken_events[0])
        broken_first["from"] = "IMPLEMENTING"
        broken_events[0] = json.dumps(broken_first)
        (broken_start / "events.jsonl").write_text("\n".join(broken_events) + "\n", encoding="utf-8")
        stale_state = self.copy_fixture("candidate-changed", "candidate-stale-state")
        stale_path = stale_state / "state.json"
        stale = json.loads(stale_path.read_text(encoding="utf-8"))
        stale["status"] = "VALIDATING"
        stale["currentStage"] = "VALIDATING"
        stale["verdict"] = None
        stale_path.write_text(json.dumps(stale), encoding="utf-8")

        for run_directory, expected in (
            (missing_events, "events.jsonl"),
            (broken_start, "首条"),
            (stale_state, "末状态"),
        ):
            with self.subTest(run_directory=run_directory.name):
                result = self.run_cli("validate", str(run_directory))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(str(run_directory), result.stderr)
                self.assertIn(expected, result.stderr)

    def test_transition_does_not_change_state_when_event_append_fails(self) -> None:
        run_directory = self.copy_fixture("valid")
        events_path = run_directory / "events.jsonl"
        events_path.write_text("", encoding="utf-8")
        events_path.chmod(0o444)
        try:
            result = self.run_cli(
                "transition",
                str(run_directory),
                "IMPLEMENTING",
                "--actor",
                "SUPERVISOR",
                "--evidence",
                "task-contract.md",
            )
        finally:
            events_path.chmod(0o644)

        state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("events.jsonl", result.stderr)
        self.assertEqual(state["status"], "PLANNED")

    def test_validate_rejects_symbolic_links_for_every_critical_ledger_file(self) -> None:
        for index, filename in enumerate(
            (
                "state.json",
                "task-contract.md",
                "plan.json",
                "candidate.sha",
                "review-report.md",
                "events.jsonl",
                ".workflow.lock",
            )
        ):
            with self.subTest(filename=filename):
                run_directory = self.copy_fixture("valid", f"symlink-{index}")
                ledger_path = run_directory / filename
                if ledger_path.exists() or ledger_path.is_symlink():
                    ledger_path.unlink()
                outside_path = self.workspace / f"outside-{index}"
                outside_path.write_text("", encoding="utf-8")
                os.symlink(outside_path, ledger_path)

                result = self.run_cli("validate", str(run_directory))

                self.assertNotEqual(result.returncode, 0)
                self.assertIn(str(run_directory), result.stderr)
                self.assertIn(filename, result.stderr)
                self.assertIn("符号链接", result.stderr)

    def test_validate_reports_filesystem_io_errors_as_task_errors(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state_path.chmod(0o000)
        try:
            result = self.run_cli("validate", str(run_directory))
        finally:
            state_path.chmod(0o644)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("文件系统错误", result.stderr)
        self.assertIn("检查", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_commands_reject_an_active_workflow_lock(self) -> None:
        valid_directory = self.copy_fixture("valid")
        paused_directory = self.copy_fixture("paused")
        lock_path = valid_directory / ".workflow.lock"
        with lock_path.open("a+", encoding="utf-8") as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            commands = (
                ("validate", str(valid_directory)),
                (
                    "transition",
                    str(valid_directory),
                    "IMPLEMENTING",
                    "--actor",
                    "SUPERVISOR",
                    "--evidence",
                    "task-contract.md",
                ),
            )
            for command in commands:
                with self.subTest(command=command[0]):
                    result = self.run_cli(*command)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(str(valid_directory), result.stderr)
                    self.assertIn("租约", result.stderr)
            paused_lock_path = paused_directory / ".workflow.lock"
            with paused_lock_path.open("a+", encoding="utf-8") as paused_lock_file:
                fcntl.flock(paused_lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                resume = self.run_cli("resume", str(paused_directory))
                self.assertNotEqual(resume.returncode, 0)
                self.assertIn(str(paused_directory), resume.stderr)
                self.assertIn("租约", resume.stderr)

    def test_transition_to_reviewed_requires_target_review_evidence(self) -> None:
        run_directory = self.copy_fixture("validating")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n裁决: PASS\n",
            encoding="utf-8",
        )

        result = self.run_cli(
            "transition",
            str(run_directory),
            "REVIEWED",
            "--actor",
            "VERIFY_REVIEWER",
            "--evidence",
            "review-report.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("verdict", result.stderr)

    def test_pass_report_ignores_explicitly_resolved_p1_text(self) -> None:
        run_directory = self.copy_fixture("candidate-changed")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n裁决: PASS\n无 P1。\nP1 已修复。\n",
            encoding="utf-8",
        )

        result = self.run_cli(
            "transition",
            str(run_directory),
            "COMMIT_READY",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_validate_reports_malformed_state_types_as_task_errors(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["status"] = []
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("status", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_planning_retry_is_limited_when_resuming_a_paused_plan(self) -> None:
        run_directory = self.copy_fixture("valid")

        first_pause = self.run_cli(
            "transition",
            str(run_directory),
            "PAUSED",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
            "--stop-reason",
            "等待维护者授权",
            "--next-action",
            "恢复 PLANNED 阶段并等待维护者授权",
        )
        first_resume = self.run_cli(
            "transition",
            str(run_directory),
            "PLANNED",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
        )
        second_pause = self.run_cli(
            "transition",
            str(run_directory),
            "PAUSED",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
            "--stop-reason",
            "等待维护者授权",
            "--next-action",
            "恢复 PLANNED 阶段并等待维护者授权",
        )
        exhausted_resume = self.run_cli(
            "transition",
            str(run_directory),
            "PLANNED",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "task-contract.md",
        )

        state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(first_pause.returncode, 0, first_pause.stderr)
        self.assertEqual(first_resume.returncode, 0, first_resume.stderr)
        self.assertEqual(second_pause.returncode, 0, second_pause.stderr)
        self.assertNotEqual(exhausted_resume.returncode, 0)
        self.assertIn("PLANNING", exhausted_resume.stderr)
        self.assertEqual(state["status"], "PAUSED")

    def test_validate_rejects_paused_from_status_outside_paused_state(self) -> None:
        run_directory = self.copy_fixture("valid")
        state_path = run_directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["pausedFromStatus"] = None
        state_path.write_text(json.dumps(state), encoding="utf-8")

        result = self.run_cli("validate", str(run_directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("pausedFromStatus", result.stderr)

    def test_rework_can_review_a_new_candidate_after_a_reject(self) -> None:
        run_directory = self.copy_fixture("rejected")
        candidate_sha = "5ea3b5e18a9d3a31a9125b41b7519e1fa5321047"
        return_to_implementation = self.run_cli(
            "transition",
            str(run_directory),
            "IMPLEMENTING",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )
        (run_directory / "candidate.sha").write_text(f"{candidate_sha}\n", encoding="utf-8")
        (run_directory / "review-report.md").write_text(
            f"候选 SHA: {candidate_sha}\n裁决: PASS\n无 P1\n",
            encoding="utf-8",
        )
        ready_to_integrate = self.run_cli(
            "transition",
            str(run_directory),
            "READY_TO_INTEGRATE",
            "--actor",
            "IMPLEMENTER",
            "--evidence",
            "review-report.md",
        )
        validating = self.run_cli(
            "transition",
            str(run_directory),
            "VALIDATING",
            "--candidate-sha",
            candidate_sha,
            "--actor",
            "INTEGRATOR",
            "--evidence",
            "candidate.sha",
        )
        reviewed = self.run_cli(
            "transition",
            str(run_directory),
            "REVIEWED",
            "--verdict",
            "PASS",
            "--actor",
            "VERIFY_REVIEWER",
            "--evidence",
            "review-report.md",
        )

        final_state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(return_to_implementation.returncode, 0, return_to_implementation.stderr)
        self.assertEqual(ready_to_integrate.returncode, 0, ready_to_integrate.stderr)
        self.assertEqual(validating.returncode, 0, validating.stderr)
        self.assertEqual(reviewed.returncode, 0, reviewed.stderr)
        self.assertEqual(final_state["status"], "REVIEWED")
        self.assertEqual(final_state["candidateSha"], candidate_sha)
        self.assertEqual(final_state["verdict"], "PASS")
        self.assertEqual(final_state["reviewedCandidateSha"], candidate_sha)

    def test_resolved_p1_does_not_mask_unresolved_p0_on_the_same_line(self) -> None:
        run_directory = self.copy_fixture("candidate-changed")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n"
            "裁决: PASS\n"
            "P1 已修复；P0: 未解决\n",
            encoding="utf-8",
        )

        result = self.run_cli(
            "transition",
            str(run_directory),
            "COMMIT_READY",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("P0", result.stderr)

    def test_resolved_p1_does_not_mask_unresolved_p1_after_a_comma(self) -> None:
        run_directory = self.copy_fixture("candidate-changed")
        (run_directory / "review-report.md").write_text(
            "候选 SHA: b777d0fba4f598028469ecc00780530555aa7003\n"
            "裁决: PASS\n"
            "P1 已修复，P1：未解决\n",
            encoding="utf-8",
        )

        result = self.run_cli(
            "transition",
            str(run_directory),
            "COMMIT_READY",
            "--actor",
            "SUPERVISOR",
            "--evidence",
            "review-report.md",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(run_directory), result.stderr)
        self.assertIn("P1", result.stderr)


if __name__ == "__main__":
    unittest.main()
