#!/usr/bin/env python3
"""MultiWeb 自动化 Agent 运行账本命令行验证器。"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import UTC, datetime, timedelta
from contextlib import contextmanager
from pathlib import Path
from typing import Any


STATUSES = frozenset(
    {
        "PLANNED",
        "IMPLEMENTING",
        "READY_TO_INTEGRATE",
        "VALIDATING",
        "REVIEWED",
        "COMMIT_READY",
        "PR_READY",
        "PAUSED",
        "BLOCKED",
    }
)

ALLOWED_TRANSITIONS = {
    "PLANNED": frozenset({"IMPLEMENTING", "PAUSED"}),
    "IMPLEMENTING": frozenset({"READY_TO_INTEGRATE", "BLOCKED", "PAUSED"}),
    "READY_TO_INTEGRATE": frozenset({"VALIDATING", "BLOCKED"}),
    "VALIDATING": frozenset({"REVIEWED", "BLOCKED"}),
    "REVIEWED": frozenset({"COMMIT_READY", "IMPLEMENTING", "BLOCKED"}),
    "COMMIT_READY": frozenset({"PR_READY"}),
    "PR_READY": frozenset(),
    "PAUSED": frozenset(),
    "BLOCKED": frozenset(),
}

STAGE_FOR_STATUS = {
    "PLANNED": "PLANNING",
    "IMPLEMENTING": "IMPLEMENTING",
    "READY_TO_INTEGRATE": "INTEGRATING",
    "VALIDATING": "VALIDATING",
    "REVIEWED": "REVIEWING",
    "COMMIT_READY": "COMMITTING",
    "PR_READY": "PR",
    "BLOCKED": "BLOCKED",
}

ATTEMPT_FIELD_FOR_STATUS = {
    "PLANNED": "PLANNING",
    "IMPLEMENTING": "IMPLEMENTING",
    "VALIDATING": "VALIDATING",
    "REVIEWED": "REVIEWING",
}

REQUIRED_STATE_FIELDS = (
    "schemaVersion",
    "taskId",
    "baselineSha",
    "status",
    "mode",
    "currentStage",
    "attempts",
    "candidateSha",
    "verdict",
    "nextAction",
    "lastEventAt",
    "stopReason",
)

ATTEMPT_FIELDS = ("PLANNING", "IMPLEMENTING", "VALIDATING", "REVIEWING")
STOPPED_STATUSES = frozenset({"PAUSED", "BLOCKED"})

EVENT_FIELDS = ("at", "actor", "from", "to", "evidence", "summary")

LEDGER_FILENAMES = (
    "state.json",
    "task-contract.md",
    "plan.json",
    "candidate.sha",
    "review-report.md",
    "events.jsonl",
    ".workflow.lock",
)

STATUS_ORDER = {
    "PLANNED": 0,
    "IMPLEMENTING": 1,
    "READY_TO_INTEGRATE": 2,
    "VALIDATING": 3,
    "REVIEWED": 4,
    "COMMIT_READY": 5,
    "PR_READY": 6,
}


def reject_ledger_symlink(run_directory: Path, filename: str) -> None:
    """拒绝关键账本文件使用符号链接，避免账本读写逃逸任务目录。"""
    if (run_directory / filename).is_symlink():
        raise ValueError(
            f"任务目录 {run_directory} 的关键账本文件 {filename} 不能是符号链接；请替换为任务目录内的常规文件后重试。"
        )


def reject_ledger_symlinks(run_directory: Path) -> None:
    """确认所有已知关键账本文件都不是符号链接。"""
    for filename in LEDGER_FILENAMES:
        reject_ledger_symlink(run_directory, filename)


def validate_run(run_directory: Path) -> dict[str, Any]:
    """确认运行目录包含当前状态账本。"""
    if not run_directory.is_dir():
        raise ValueError(f"任务目录 {run_directory} 不存在；请传入 .codex/workflow/runs/<task-id>。")
    reject_ledger_symlinks(run_directory)
    if not (run_directory / "state.json").is_file():
        raise ValueError(f"任务目录 {run_directory} 缺少 state.json；请初始化状态账本后重试。")
    try:
        state = json.loads((run_directory / "state.json").read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError(f"任务目录 {run_directory} 的 state.json 不是合法 JSON；请修复账本。") from error
    validate_state_schema(run_directory, state)
    validate_git_sha(run_directory, state["baselineSha"], "baselineSha")
    validate_required_evidence(run_directory)
    validate_event_log(run_directory, state)
    if state["status"] in {"REVIEWED", "COMMIT_READY", "PR_READY"}:
        validate_review_evidence(
            run_directory,
            state,
            require_pass=state["status"] in {"COMMIT_READY", "PR_READY"},
        )
    elif state["candidateSha"] is not None:
        validate_git_sha(run_directory, state["candidateSha"], "candidateSha")
    return state


@contextmanager
def workflow_lock(run_directory: Path):
    """以非阻塞进程租约串行化同一任务目录的工作流命令。"""
    if not run_directory.is_dir():
        raise ValueError(f"任务目录 {run_directory} 不存在；请传入 .codex/workflow/runs/<task-id>。")
    lock_path = run_directory / ".workflow.lock"
    reject_ledger_symlink(run_directory, ".workflow.lock")
    try:
        lock_file = lock_path.open("a+", encoding="utf-8")
    except OSError as error:
        raise ValueError(f"任务目录 {run_directory} 无法获取工作流租约；请检查目录权限后重试。") from error
    with lock_file:
        try:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError(
                f"任务目录 {run_directory} 已有有效租约；请等待当前 Supervisor 完成或确认其已退出后重试。"
            ) from error
        except OSError as error:
            raise ValueError(f"任务目录 {run_directory} 无法获取工作流租约；请检查目录权限后重试。") from error
        try:
            yield
        finally:
            try:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
            except OSError as error:
                raise ValueError(f"任务目录 {run_directory} 无法释放工作流租约；请检查目录权限后重试。") from error


def validate_state_schema(run_directory: Path, state: object) -> None:
    """校验状态账本的固定字段、类型和阶段约束。"""
    if not isinstance(state, dict):
        raise ValueError(f"任务目录 {run_directory} 的 state.json 必须是对象；请按运行账本 schema 修复。")
    missing_fields = [field for field in REQUIRED_STATE_FIELDS if field not in state]
    if missing_fields:
        raise ValueError(
            f"任务目录 {run_directory} 缺少 state.json.{', '.join(missing_fields)}；请补齐必填字段。"
        )
    if state["schemaVersion"] != 1 or isinstance(state["schemaVersion"], bool):
        raise ValueError(f"任务目录 {run_directory} 的 schemaVersion 不受支持；请使用 schemaVersion 1。")
    task_id = state["taskId"]
    if not isinstance(task_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{2,127}", task_id):
        raise ValueError(f"任务目录 {run_directory} 的 taskId 非法；请使用 3-128 位的字母数字任务标识。")
    status = state["status"]
    if not isinstance(status, str) or status not in STATUSES:
        raise ValueError(f"任务目录 {run_directory} 的 state.json.status 非法；请使用受支持的工作流状态。")
    if not isinstance(state["mode"], str) or state["mode"] not in {"FULL", "TRIVIAL"}:
        raise ValueError(f"任务目录 {run_directory} 的 mode 非法；请使用 FULL 或 TRIVIAL。")
    current_stage = state["currentStage"]
    if not isinstance(current_stage, str):
        raise ValueError(f"任务目录 {run_directory} 的 currentStage 非法；请记录当前工作流阶段。")
    expected_stage = STAGE_FOR_STATUS.get(status)
    if expected_stage is not None and current_stage != expected_stage:
        raise ValueError(
            f"任务目录 {run_directory} 的 currentStage 与 {state['status']} 不一致；请恢复到对应阶段。"
        )
    paused_from_status = state.get("pausedFromStatus")
    if status == "PAUSED":
        if not isinstance(paused_from_status, str) or paused_from_status not in STATUSES:
            raise ValueError(f"任务目录 {run_directory} 的 PAUSED 缺少有效 pausedFromStatus；请记录中断前状态。")
        if "PAUSED" not in ALLOWED_TRANSITIONS[paused_from_status]:
            raise ValueError(f"任务目录 {run_directory} 的 pausedFromStatus 不可暂停；请记录允许中断的前序状态。")
        if current_stage != STAGE_FOR_STATUS[paused_from_status]:
            raise ValueError(f"任务目录 {run_directory} 的 PAUSED.currentStage 与 pausedFromStatus 不一致；请保留中断前阶段。")
    elif "pausedFromStatus" in state:
        raise ValueError(f"任务目录 {run_directory} 的 pausedFromStatus 仅可用于 PAUSED；请移除过期恢复目标。")
    attempts = state["attempts"]
    if not isinstance(attempts, dict):
        raise ValueError(f"任务目录 {run_directory} 的 attempts 必须是对象；请记录各阶段尝试次数。")
    missing_attempts = [field for field in ATTEMPT_FIELDS if field not in attempts]
    if missing_attempts:
        raise ValueError(
            f"任务目录 {run_directory} 的 attempts 缺少 {', '.join(missing_attempts)}；请补齐阶段次数。"
        )
    for field in ATTEMPT_FIELDS:
        value = attempts[field]
        if not isinstance(value, int) or isinstance(value, bool) or not 0 <= value <= 2:
            raise ValueError(
                f"任务目录 {run_directory} 的 attempts.{field} 非法；请记录不超过 2 的非负整数。"
            )
    candidate_sha = state["candidateSha"]
    if candidate_sha is not None and (not isinstance(candidate_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{7,64}", candidate_sha)):
        raise ValueError(f"任务目录 {run_directory} 的 candidateSha 非法；请记录 7-64 位十六进制 SHA 或 null。")
    if status in {"VALIDATING", "REVIEWED", "COMMIT_READY", "PR_READY"} and candidate_sha is None:
        raise ValueError(
            f"任务目录 {run_directory} 的 {state['status']} 缺少 candidateSha；请由 Integrator 固定候选提交后继续。"
        )
    if not isinstance(state["verdict"], str | type(None)) or state["verdict"] not in {None, "PASS", "REJECT"}:
        raise ValueError(f"任务目录 {run_directory} 的 verdict 非法；请使用 PASS、REJECT 或 null。")
    for field in ("nextAction", "lastEventAt"):
        value = state[field]
        if not isinstance(value, str) or not value.strip() or "\n" in value:
            raise ValueError(f"任务目录 {run_directory} 的 {field} 非法；请记录非空单行值。")
    try:
        parsed_event_time = datetime.fromisoformat(state["lastEventAt"])
    except ValueError as error:
        raise ValueError(f"任务目录 {run_directory} 的 lastEventAt 不是 ISO 8601 时间；请修复时间戳。") from error
    if parsed_event_time.tzinfo is None:
        raise ValueError(f"任务目录 {run_directory} 的 lastEventAt 缺少时区；请记录带时区的 ISO 8601 时间。")
    stop_reason = state["stopReason"]
    if stop_reason is not None and (not isinstance(stop_reason, str) or "\n" in stop_reason):
        raise ValueError(f"任务目录 {run_directory} 的 stopReason 非法；请记录单行原因或 null。")


def validate_required_evidence(run_directory: Path) -> None:
    """确认规划阶段的任务契约和 DAG 计划可供恢复流程读取。"""
    contract_path = run_directory / "task-contract.md"
    if not contract_path.is_file():
        raise ValueError(f"任务目录 {run_directory} 缺少 task-contract.md；请由 Planner 生成任务契约。")
    plan_path = run_directory / "plan.json"
    if not plan_path.is_file():
        raise ValueError(f"任务目录 {run_directory} 缺少 plan.json；请由 Planner 生成任务计划。")
    try:
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError(f"任务目录 {run_directory} 的 plan.json 不是合法 JSON；请修复任务计划。") from error
    if not isinstance(plan, dict) or plan.get("schemaVersion") != 1:
        raise ValueError(f"任务目录 {run_directory} 的 plan.json schemaVersion 不受支持；请使用版本 1。")


def validate_event_log(run_directory: Path, state: dict[str, Any]) -> None:
    """校验追加事件不会绕过状态机、丢失证据或回退阶段。"""
    events_path = run_directory / "events.jsonl"
    reject_ledger_symlink(run_directory, "events.jsonl")
    if not events_path.exists():
        if state["status"] != "PLANNED":
            raise ValueError(
                f"任务目录 {run_directory} 的 {state['status']} 缺少 events.jsonl；请恢复从 PLANNED 开始的完整事件链。"
            )
        return
    if not events_path.is_file():
        raise ValueError(f"任务目录 {run_directory} 的 events.jsonl 不是文件；请恢复追加式事件日志。")
    previous_status: str | None = None
    previous_event_time: datetime | None = None
    last_status: str | None = None
    last_event_at: str | None = None
    paused_from_history: str | None = None
    for line_number, line in enumerate(events_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            raise ValueError(f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行为空；请移除无效事件。")
        try:
            event = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行不是合法 JSON；请修复审计事件。"
            ) from error
        if not isinstance(event, dict) or any(field not in event for field in EVENT_FIELDS):
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行缺少事件字段；请补齐审计记录。"
            )
        if not all(isinstance(event[field], str) and event[field] for field in EVENT_FIELDS):
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行字段非法；请使用非空字符串。"
            )
        try:
            event_time = datetime.fromisoformat(event["at"])
        except ValueError as error:
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行时间非法；请使用带时区的 ISO 8601 时间。"
            ) from error
        if event_time.tzinfo is None:
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行时间缺少时区；请修复审计时间。"
            )
        if previous_event_time is not None and event_time <= previous_event_time:
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行时间未严格递增；请按严格递增顺序修复审计时间。"
            )
        from_status = event["from"]
        to_status = event["to"]
        if from_status not in STATUSES or to_status not in STATUSES:
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行状态非法；请使用受支持的状态。"
            )
        if line_number == 1 and from_status != "PLANNED":
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 首条事件必须从 PLANNED 开始；请恢复完整状态链。"
            )
        if previous_status is not None and from_status != previous_status:
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行状态不连续；请修复事件顺序。"
            )
        if (
            from_status in STATUS_ORDER
            and to_status in STATUS_ORDER
            and STATUS_ORDER[to_status] < STATUS_ORDER[from_status]
            and (from_status, to_status) != ("REVIEWED", "IMPLEMENTING")
        ):
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行发生状态倒退；请重新初始化后续证据。"
            )
        if not is_event_transition_allowed(from_status, to_status, paused_from_history):
            raise ValueError(
                f"任务目录 {run_directory} 的 events.jsonl 第 {line_number} 行违反状态机；请记录合法迁移。"
            )
        if to_status == "PAUSED":
            paused_from_history = from_status
        elif from_status == "PAUSED":
            paused_from_history = None
        resolve_evidence_path(run_directory, Path(event["evidence"]))
        previous_status = to_status
        previous_event_time = event_time
        last_status = to_status
        last_event_at = event["at"]
    if last_status is not None and last_status != state["status"]:
        raise ValueError(
            f"任务目录 {run_directory} 的 events.jsonl 末状态与 state.json 不一致；请恢复一致的账本与审计记录。"
        )
    if last_event_at is not None and last_event_at != state["lastEventAt"]:
        raise ValueError(
            f"任务目录 {run_directory} 的 events.jsonl 末条 at 与 state.json.lastEventAt 不一致；请恢复一致的时间戳。"
        )
    if state["status"] == "PAUSED" and paused_from_history != state["pausedFromStatus"]:
        raise ValueError(
            f"任务目录 {run_directory} 的 pausedFromStatus 与 events.jsonl 不一致；请恢复中断前状态证据。"
        )


def is_event_transition_allowed(
    from_status: str,
    to_status: str,
    paused_from_history: str | None,
) -> bool:
    """校验暂停事件只能回到最近一次中断前状态或转为阻断。"""
    if from_status == "PAUSED":
        return paused_from_history is not None and to_status in {paused_from_history, "BLOCKED"}
    return to_status in ALLOWED_TRANSITIONS[from_status]


def validate_git_sha(run_directory: Path, value: object, field_name: str) -> None:
    """确认账本中的提交标识可在当前仓库解析。"""
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{7,64}", value):
        raise ValueError(
            f"任务目录 {run_directory} 的 state.json.{field_name} 不是 7-64 位十六进制 SHA；请记录有效提交。"
        )
    result = subprocess.run(
        ["git", "-C", str(run_directory), "rev-parse", "--verify", f"{value}^{{commit}}"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(
            f"任务目录 {run_directory} 的 state.json.{field_name} 无法由 Git 解析；请使用当前仓库存在的提交 SHA。"
        )


def resolve_evidence_path(run_directory: Path, evidence: Path) -> Path:
    """只接受运行目录内已有的常规证据文件。"""
    evidence_path = evidence.resolve() if evidence.is_absolute() else (run_directory / evidence).resolve()
    if not evidence_path.is_relative_to(run_directory):
        raise ValueError(f"任务目录 {run_directory} 的证据路径越界；请提供该任务目录内的证据文件。")
    if not evidence_path.is_file():
        raise ValueError(f"任务目录 {run_directory} 缺少证据文件 {evidence}；请先生成证据后再迁移状态。")
    return evidence_path


def is_nonempty_single_line(value: object) -> bool:
    """确认停止原因和下一步可直接写入单行账本字段。"""
    return isinstance(value, str) and bool(value.strip()) and value.splitlines() == [value]


def validate_stop_options(
    run_directory: Path,
    next_status: str,
    stop_reason: str | None,
    next_action: str | None,
) -> None:
    """限制停止参数只能用于需要停止上下文的状态。"""
    if next_status in STOPPED_STATUSES:
        if not is_nonempty_single_line(stop_reason) or not is_nonempty_single_line(next_action):
            raise ValueError(
                f"任务目录 {run_directory} 进入 PAUSED 或 BLOCKED 必须同时提供 "
                "--stop-reason 和 --next-action，且均为非空单行。"
            )
    elif stop_reason is not None or next_action is not None:
        raise ValueError(
            f"任务目录 {run_directory} 仅在进入 PAUSED 或 BLOCKED 时接受 --stop-reason 和 --next-action。"
        )


def transition_run(
    run_directory: Path,
    next_status: str,
    actor: str,
    evidence: Path,
    candidate_sha: str | None,
    verdict: str | None,
    stop_reason: str | None,
    next_action: str | None,
) -> None:
    """验证状态迁移并原子更新当前账本，再追加审计事件。"""
    state = validate_run(run_directory)
    old_status = state["status"]
    if next_status not in STATUSES:
        raise ValueError(f"任务目录 {run_directory} 的目标状态 {next_status} 非法；请使用受支持的工作流状态。")
    validate_stop_options(run_directory, next_status, stop_reason, next_action)
    if not is_allowed_transition(state, next_status):
        raise ValueError(
            f"任务目录 {run_directory} 不允许从 {old_status} 迁移到 {next_status}；请迁移到该状态允许的下一阶段。"
        )
    if next_status == "VALIDATING":
        if candidate_sha is None:
            raise ValueError(
                f"任务目录 {run_directory} 进入 VALIDATING 必须提供 --candidate-sha；请先固定 candidate.sha 后重试。"
            )
        validate_candidate_evidence(run_directory, candidate_sha)
        state["candidateSha"] = candidate_sha
    elif candidate_sha is not None:
        raise ValueError(
            f"任务目录 {run_directory} 仅在进入 VALIDATING 时接受 --candidate-sha；请在固定候选时提供该参数。"
        )
    if next_status == "REVIEWED":
        if verdict is None:
            raise ValueError(
                f"任务目录 {run_directory} 进入 REVIEWED 必须提供 --verdict PASS 或 REJECT；请记录审查裁决后重试。"
            )
        state["verdict"] = verdict
    elif verdict is not None:
        raise ValueError(
            f"任务目录 {run_directory} 仅在进入 REVIEWED 时接受 --verdict；请在记录审查结论时提供该参数。"
        )
    if old_status == "REVIEWED" and next_status == "COMMIT_READY":
        validate_commit_ready_gate(run_directory, state)
    if next_status == "REVIEWED":
        validate_review_evidence(run_directory, state, require_pass=False)
        state["reviewedCandidateSha"] = state["candidateSha"]
    if old_status == "REVIEWED" and next_status == "IMPLEMENTING":
        state.pop("reviewedCandidateSha", None)
    if not re.fullmatch(r"[A-Z][A-Z0-9_-]{1,63}", actor):
        raise ValueError(f"任务目录 {run_directory} 的操作者角色非法；请使用大写角色标识。")
    evidence_path = resolve_evidence_path(run_directory, evidence)
    attempt_field = ATTEMPT_FIELD_FOR_STATUS.get(next_status)
    if attempt_field is not None:
        attempts = state.get("attempts")
        attempt_count = attempts.get(attempt_field) if isinstance(attempts, dict) else None
        if not isinstance(attempt_count, int) or isinstance(attempt_count, bool) or attempt_count < 0:
            raise ValueError(
                f"任务目录 {run_directory} 的 attempts.{attempt_field} 非法；请记录非负整数尝试次数。"
            )
        if attempt_count >= 2:
            raise ValueError(
                f"任务目录 {run_directory} 的 {attempt_field} 自动重试已达上限；请迁移到 PAUSED 或 BLOCKED 并人工处理。"
            )
        attempts[attempt_field] = attempt_count + 1
    if next_status == "PAUSED":
        state["pausedFromStatus"] = old_status
        state["currentStage"] = STAGE_FOR_STATUS[old_status]
    else:
        state.pop("pausedFromStatus", None)
        state["currentStage"] = STAGE_FOR_STATUS[next_status]
    if next_status in STOPPED_STATUSES:
        state["stopReason"] = stop_reason
        state["nextAction"] = next_action
    state["status"] = next_status
    state["lastEventAt"] = next_event_time(state["lastEventAt"])
    summary = "状态迁移已验证"
    if next_status in STOPPED_STATUSES:
        summary = f"停止原因：{stop_reason}；唯一下一步：{next_action}"
    append_event(
        run_directory,
        {
            "at": state["lastEventAt"],
            "actor": actor,
            "from": old_status,
            "to": next_status,
            "evidence": evidence_path.relative_to(run_directory).as_posix(),
            "summary": summary,
        },
    )
    atomic_write_json(run_directory / "state.json", state)


def is_allowed_transition(state: dict[str, Any], next_status: str) -> bool:
    """暂停账本只能恢复到记录的前序状态，或转为阻断。"""
    old_status = state["status"]
    if old_status == "PAUSED":
        return next_status in {state["pausedFromStatus"], "BLOCKED"}
    return next_status in ALLOWED_TRANSITIONS[old_status]


def next_event_time(previous_event_at: str) -> str:
    """生成严格晚于当前账本末事件的带时区时间戳。"""
    now = datetime.now(UTC)
    previous_event_time = datetime.fromisoformat(previous_event_at)
    if now <= previous_event_time:
        now = previous_event_time + timedelta(microseconds=1)
    return now.isoformat(timespec="microseconds")


def resume_run(run_directory: Path) -> tuple[str, str, str, str]:
    """读取经过验证的账本，并返回恢复所需的最小上下文。"""
    state = validate_run(run_directory)
    next_action = state.get("nextAction")
    if not isinstance(next_action, str) or not next_action.strip() or "\n" in next_action:
        raise ValueError(f"任务目录 {run_directory} 的 nextAction 不是唯一可执行步骤；请记录一条非空单行恢复指令。")
    stop_reason = state.get("stopReason")
    if stop_reason is None:
        stop_reason = "无"
    if not isinstance(stop_reason, str) or "\n" in stop_reason:
        raise ValueError(f"任务目录 {run_directory} 的 stopReason 非法；请记录单行阻断原因或 null。")
    resume_target = state["pausedFromStatus"] if state["status"] == "PAUSED" else state["status"]
    return state["status"], stop_reason, next_action, resume_target


def atomic_write_json(destination: Path, payload: dict[str, Any]) -> None:
    """以同目录临时文件加替换的方式写入状态，避免半写入账本。"""
    temporary_path: Path | None = None
    reject_ledger_symlink(destination.parent, destination.name)
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=destination.parent,
            prefix=".state.",
            suffix=".tmp",
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            json.dump(payload, temporary_file, ensure_ascii=False, indent=2)
            temporary_file.write("\n")
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
        os.replace(temporary_path, destination)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def append_event(run_directory: Path, event: dict[str, str]) -> None:
    """以 JSON Lines 追加状态审计记录。"""
    reject_ledger_symlink(run_directory, "events.jsonl")
    try:
        with (run_directory / "events.jsonl").open("a", encoding="utf-8") as event_file:
            event_file.write(json.dumps(event, ensure_ascii=False, sort_keys=True))
            event_file.write("\n")
            event_file.flush()
            os.fsync(event_file.fileno())
    except OSError as error:
        raise ValueError(
            f"任务目录 {run_directory} 无法追加 events.jsonl；请恢复事件日志写入权限后重试。"
        ) from error


def validate_commit_ready_gate(run_directory: Path, state: dict[str, Any]) -> None:
    """只有固定候选版本的无阻断审查才可进入可提交状态。"""
    validate_review_evidence(run_directory, state, require_pass=True)


def validate_review_evidence(run_directory: Path, state: dict[str, Any], require_pass: bool) -> None:
    """核验候选版本、审查裁决及阻断级别处于同一证据链中。"""
    verdict = state.get("verdict")
    if verdict not in {"PASS", "REJECT"}:
        raise ValueError(f"任务目录 {run_directory} 的 state.json.verdict 非法；请记录 PASS 或 REJECT。")
    if require_pass and verdict != "PASS":
        raise ValueError(
            f"任务目录 {run_directory} 的审查裁决为 {verdict!r}，不是 PASS；请修复问题后重新审查，不能进入 COMMIT_READY。"
        )
    candidate_sha = state.get("candidateSha")
    reviewed_candidate_sha = state.get("reviewedCandidateSha")
    if reviewed_candidate_sha is not None and reviewed_candidate_sha != candidate_sha:
        raise ValueError(
            f"任务目录 {run_directory} 发生候选版本变化，reviewedCandidateSha 已失效；请重新集成并审查当前 candidateSha。"
        )
    validate_candidate_evidence(run_directory, candidate_sha)
    report_path = run_directory / "review-report.md"
    if not report_path.is_file():
        raise ValueError(f"任务目录 {run_directory} 缺少 review-report.md；请完成 Verify-Reviewer 审查。")
    report = report_path.read_text(encoding="utf-8")
    reviewed_candidates = re.findall(
        r"(?im)^\s*(?:候选\s*SHA|candidate\s*sha)\s*[:：]\s*([0-9a-f]{7,64})\s*$",
        report,
    )
    if len(reviewed_candidates) != 1 or reviewed_candidates[0].lower() != candidate_sha.lower():
        raise ValueError(
            f"任务目录 {run_directory} 的 review-report.md 候选 SHA 与当前候选不一致；请重新审查固定候选版本。"
        )
    report_verdicts = re.findall(r"(?im)^\s*(?:裁决|verdict)\s*[:：]\s*(PASS|REJECT)\s*$", report)
    if report_verdicts != [verdict]:
        raise ValueError(f"任务目录 {run_directory} 的 review-report.md 裁决与 state.json 不一致；请重新记录审查结论。")
    unresolved_findings = find_unresolved_blocking_findings(report)
    if verdict == "PASS" and unresolved_findings:
        raise ValueError(
            f"任务目录 {run_directory} 的 review-report.md 含未解决 {'、'.join(unresolved_findings)}；请先修复阻断问题。"
        )


def validate_candidate_evidence(run_directory: Path, candidate_sha: object) -> None:
    """核验候选提交与候选文件一致，作为验证和审查的共同前置条件。"""
    validate_git_sha(run_directory, candidate_sha, "candidateSha")
    reject_ledger_symlink(run_directory, "candidate.sha")
    candidate_path = run_directory / "candidate.sha"
    if not candidate_path.is_file():
        raise ValueError(f"任务目录 {run_directory} 缺少 candidate.sha；请由 Integrator 固定候选提交后再审查。")
    if candidate_path.read_text(encoding="utf-8").strip() != candidate_sha:
        raise ValueError(f"任务目录 {run_directory} 的 candidate.sha 与 state.json.candidateSha 不一致；请重新集成和审查。")


def find_unresolved_blocking_findings(report: str) -> list[str]:
    """按每个 P0/P1 的局部子句判断是否仍为提交阻断项。"""
    findings: list[str] = []
    finding_pattern = re.compile(r"\b(P[01])\b", flags=re.IGNORECASE)
    clause_boundaries = [match.start() for match in re.finditer(r"[,，、;；。\n]", report)]
    clause_boundaries.append(len(report))
    clause_start = 0
    for clause_end in clause_boundaries:
        clause = report[clause_start:clause_end]
        for match in finding_pattern.finditer(clause):
            finding_name = match.group(1).upper()
            finding_pattern_text = re.escape(finding_name)
            is_negated = re.search(
                rf"(?:无|没有|no|none)\s*(?:未解决的?\s*)?{finding_pattern_text}\b",
                clause,
                flags=re.IGNORECASE,
            )
            is_resolved = re.search(
                rf"\b{finding_pattern_text}\b\s*[:：-]?\s*(?:已(?:修复|解决|关闭)|resolved|fixed|closed)\b",
                clause,
                flags=re.IGNORECASE,
            )
            if not is_negated and not is_resolved and finding_name not in findings:
                findings.append(finding_name)
        clause_start = clause_end + 1
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="验证 MultiWeb Agent 工作流账本")
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("run_directory", type=Path)
    transition_parser = subparsers.add_parser("transition")
    transition_parser.add_argument("run_directory", type=Path)
    transition_parser.add_argument("next_status")
    transition_parser.add_argument("--actor", required=True)
    transition_parser.add_argument("--evidence", type=Path, required=True)
    transition_parser.add_argument("--candidate-sha")
    transition_parser.add_argument("--verdict", choices=("PASS", "REJECT"))
    transition_parser.add_argument("--stop-reason")
    transition_parser.add_argument("--next-action")
    resume_parser = subparsers.add_parser("resume")
    resume_parser.add_argument("run_directory", type=Path)
    arguments = parser.parse_args()

    run_directory = arguments.run_directory
    try:
        if arguments.command == "validate":
            run_directory = arguments.run_directory.resolve()
            with workflow_lock(run_directory):
                validate_run(run_directory)
            print(f"验证通过：{run_directory}")
            return 0
        if arguments.command == "transition":
            run_directory = arguments.run_directory.resolve()
            validate_stop_options(
                run_directory,
                arguments.next_status,
                arguments.stop_reason,
                arguments.next_action,
            )
            with workflow_lock(run_directory):
                transition_run(
                    run_directory,
                    arguments.next_status,
                    arguments.actor,
                    arguments.evidence,
                    arguments.candidate_sha,
                    arguments.verdict,
                    arguments.stop_reason,
                    arguments.next_action,
                )
            print(f"状态迁移成功：{run_directory} -> {arguments.next_status}")
            return 0
        if arguments.command == "resume":
            run_directory = arguments.run_directory.resolve()
            with workflow_lock(run_directory):
                status, stop_reason, next_action, resume_target = resume_run(run_directory)
            print(f"当前状态: {status}")
            print(f"阻断原因: {stop_reason}")
            print(f"唯一恢复目标: {resume_target}")
            print(f"唯一下一步: {next_action}")
            return 0
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    except OSError:
        print(
            f"任务目录 {run_directory} 发生文件系统错误；请检查目录、权限和关键账本文件类型后重试。",
            file=sys.stderr,
        )
        return 1
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
