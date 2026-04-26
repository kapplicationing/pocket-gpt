from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Mapping, Sequence

from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT, print_step, run_subprocess

_RISK_LABELS = {"risk:e2e-lifecycle", "risk:runtime", "risk:provisioning"}
_LIFECYCLE_HIGH_RISK_PATTERNS = (
    "apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/**",
    "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/**",
    "packages/app-runtime/**",
    "packages/native-bridge/**",
    "tests/maestro/scenario-first-run-download-chat.yaml",
    ".github/workflows/ci.yml",
)
_STAGE2_QUICK_OPTIMIZATION_PATTERNS = (
    "apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/**",
    "packages/app-runtime/**",
    "packages/inference-adapters/**",
    "packages/native-bridge/**",
    "scripts/android/run_stage2_native.sh",
    "scripts/benchmarks/**",
    "config/devctl/stage2.yaml",
    "tools/devctl/lanes.py",
    "tools/devctl/lanes_modules/stage2.py",
)
_SCREENSHOT_PACK_HARNESS_NOISE_MARKERS = (
    "No compose hierarchies found in the app",
    "Command timed out after",
)
_JOURNEY_KICKOFF_HARNESS_FAILURE_SIGNATURE = "send-capture-kickoff"
_REPORT_SCHEMA = "devctl-gate-report-v1"
_REPORT_DIR = REPO_ROOT / "build/devctl/gates"
_LIFECYCLE_FLOW_PATH = REPO_ROOT / "tests/maestro/scenario-first-run-download-chat.yaml"


@dataclass
class GateStepResult:
    name: str
    command: list[str]
    started_at: str
    duration_seconds: float
    status: str
    correctness: str
    blocking: bool
    reason: str | None = None

    def as_dict(self) -> dict[str, object]:
        return {
            "name": self.name,
            "command": self.command,
            "started_at": self.started_at,
            "duration_seconds": round(self.duration_seconds, 3),
            "status": self.status,
            "correctness": self.correctness,
            "blocking": self.blocking,
            "reason": self.reason,
        }


def _parse_gate_args(raw_args: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="devctl gate")
    sub = parser.add_subparsers(dest="name", required=True)

    merge_unblock = sub.add_parser("merge-unblock", help="run merge-unblock contract")
    _add_common_gate_args(merge_unblock)
    merge_unblock.add_argument("--report-path", type=Path, default=None)
    merge_unblock.add_argument(
        "--risk-label",
        action="append",
        default=[],
        help="Risk labels (repeatable). Triggers lifecycle flow when matching CI policy labels.",
    )
    merge_unblock.add_argument("--event-name", default=os.environ.get("GITHUB_EVENT_NAME", "local"))
    merge_unblock.add_argument("--ref-name", default=os.environ.get("GITHUB_REF_NAME", "local"))
    merge_unblock.add_argument("--force-lifecycle", action="store_true")
    merge_unblock.add_argument("--skip-lifecycle", action="store_true")

    promotion = sub.add_parser("promotion", help="run promotion gate with product-signal filtering")
    _add_common_gate_args(promotion)
    promotion.add_argument("--report-path", type=Path, default=None)
    promotion.add_argument("--include-screenshot-pack", action="store_true")
    promotion.add_argument(
        "--risk-label",
        action="append",
        default=[],
        help="Risk labels (repeatable). Stage2 quick is required when matching runtime risk labels.",
    )

    return parser.parse_args(list(raw_args))


def _add_common_gate_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--serial", default=os.environ.get("ADB_SERIAL", ""))


def _resolve_report_path(report_path: Path | None, gate_name: str) -> Path:
    if report_path is not None:
        resolved = report_path if report_path.is_absolute() else REPO_ROOT / report_path
        resolved.parent.mkdir(parents=True, exist_ok=True)
        return resolved
    _REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return _REPORT_DIR / f"{gate_name}-{stamp}.json"


def _collect_changed_files() -> list[str]:
    changed: set[str] = set()
    for command in (
        ["git", "diff", "--name-only"],
        ["git", "diff", "--cached", "--name-only"],
        ["git", "ls-files", "--others", "--exclude-standard"],
    ):
        result = run_subprocess(command, check=False, capture_output=True, env=os.environ)
        if result.returncode != 0:
            continue
        for line in (result.stdout or "").splitlines():
            item = line.strip()
            if item:
                changed.add(item)
    return sorted(changed)


def _has_high_risk_path(changed_files: Sequence[str]) -> bool:
    return any(
        fnmatch.fnmatch(path, pattern)
        for path in changed_files
        for pattern in _LIFECYCLE_HIGH_RISK_PATTERNS
    )


def _has_stage2_optimization_path(changed_files: Sequence[str]) -> bool:
    return any(
        fnmatch.fnmatch(path, pattern)
        for path in changed_files
        for pattern in _STAGE2_QUICK_OPTIMIZATION_PATTERNS
    )


def _should_run_lifecycle(
    *,
    event_name: str,
    ref_name: str,
    risk_labels: Sequence[str],
    changed_files: Sequence[str],
    force: bool,
    skip: bool,
) -> tuple[bool, str]:
    if force:
        return True, "forced"
    if skip:
        return False, "skipped-by-flag"

    reasons: list[str] = []
    should_run = False

    if event_name == "push" and ref_name == "main":
        should_run = True
        reasons.append("main-push")

    if _has_high_risk_path(changed_files):
        should_run = True
        reasons.append("high-risk-path")

    normalized_labels = {label.strip() for label in risk_labels if label.strip()}
    if normalized_labels & _RISK_LABELS:
        should_run = True
        reasons.append("risk-label")

    if not reasons:
        reasons.append("low-risk-change")
    return should_run, ",".join(reasons)


def _should_run_stage2_quick(*, risk_labels: Sequence[str], changed_files: Sequence[str]) -> tuple[bool, str]:
    reasons: list[str] = []
    if _has_stage2_optimization_path(changed_files):
        reasons.append("optimization-sensitive-path")
    normalized_labels = {label.strip() for label in risk_labels if label.strip()}
    if normalized_labels & _RISK_LABELS:
        reasons.append("risk-label")
    if not reasons:
        return False, "low-risk-change"
    return True, ",".join(reasons)


def _lifecycle_lane_command() -> list[str]:
    return [
        "python3",
        "tools/devctl/main.py",
        "lane",
        "maestro",
        "--flows",
        str(_LIFECYCLE_FLOW_PATH),
    ]


def _classify_journey_failure(output: str) -> tuple[str, str | None]:
    report_match = re.search(r"See\s+([^\s]+journey-report\.json)", output)
    if report_match is None:
        return "product_signal_fail", None
    report_candidate = Path(report_match.group(1))
    report_path = report_candidate if report_candidate.is_absolute() else REPO_ROOT / report_candidate
    if not report_path.exists():
        return "product_signal_fail", None
    try:
        payload = json.loads(report_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return "product_signal_fail", None

    for step in payload.get("steps", []):
        if not isinstance(step, dict):
            continue
        if str(step.get("name", "")).endswith("send-capture") and str(step.get("status")) == "failed":
            phase = str(step.get("phase") or "").strip().lower()
            runtime_status = step.get("runtime_status")
            backend = step.get("backend")
            active_model_id = step.get("active_model_id")
            placeholder_visible = step.get("placeholder_visible")
            failure_signature = str(step.get("failure_signature") or "")
            runtime_unknown = runtime_status is None or str(runtime_status).strip().lower() in {"", "unknown"}
            backend_unknown = backend is None or str(backend).strip().lower() in {"", "unknown"}
            model_unknown = active_model_id is None or str(active_model_id).strip().lower() in {"", "unknown"}
            placeholder_neutral = placeholder_visible in {None, False}
            if (
                phase == "error"
                and runtime_unknown
                and backend_unknown
                and model_unknown
                and placeholder_neutral
                and _JOURNEY_KICKOFF_HARNESS_FAILURE_SIGNATURE in failure_signature
            ):
                return "harness_noise_fail", "journey-send-kickoff-harness"
    return "product_signal_fail", None


def _classify_failure(step_name: str, output: str) -> tuple[str, str | None]:
    lowered = output.lower()
    if step_name == "screenshot-pack":
        has_marker = any(marker.lower() in lowered for marker in _SCREENSHOT_PACK_HARNESS_NOISE_MARKERS)
        if has_marker and "mainactivityuismoketest" in lowered:
            return "harness_noise_fail", "screenshot-pack-compose-harness"
    if step_name == "journey":
        return _classify_journey_failure(output)
    return "product_signal_fail", None


def _extract_failure_reason(output: str, exit_code: int | None = None) -> str:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines:
        return f"exit={exit_code}" if exit_code is not None else "command failed"

    error_prefix_pattern = re.compile(r"^(CONFIG_ERROR|ENVIRONMENT_ERROR|DEVICE_ERROR|SCHEMA_ERROR|THRESHOLD_FAIL):")
    for line in reversed(lines):
        if error_prefix_pattern.match(line):
            return line

    for line in reversed(lines):
        if line.startswith("[devctl] $"):
            continue
        if line.startswith("INSTRUMENTATION_"):
            continue
        return line

    return lines[-1]


def _run_gate_command(
    *,
    name: str,
    command: Sequence[str],
    env: Mapping[str, str],
    allow_harness_noise: bool,
) -> GateStepResult:
    started_at = datetime.now().isoformat(timespec="seconds")
    started = time.monotonic()
    completed: subprocess.CompletedProcess[str] | None = None
    reason: str | None = None
    status = "passed"
    correctness = "pass"
    blocking = False
    try:
        completed = run_subprocess(command, check=False, capture_output=True, env=env)
    except DevctlError as exc:
        status = "failed"
        correctness = "infra_fail"
        reason = exc.message
        blocking = True
    else:
        if completed.returncode != 0:
            output = ((completed.stdout or "") + "\n" + (completed.stderr or "")).strip()
            failure_kind, failure_reason = _classify_failure(name, output)
            if allow_harness_noise and failure_kind == "harness_noise_fail":
                status = "caveat"
                correctness = "harness_noise_fail"
                reason = failure_reason or "harness-noise"
                blocking = False
            else:
                status = "failed"
                correctness = failure_kind
                reason = failure_reason
                blocking = True
    duration = time.monotonic() - started
    if completed is not None and status != "passed" and reason is None:
        raw = ((completed.stdout or "") + "\n" + (completed.stderr or "")).strip()
        reason = _extract_failure_reason(raw, completed.returncode)
    return GateStepResult(
        name=name,
        command=list(command),
        started_at=started_at,
        duration_seconds=duration,
        status=status,
        correctness=correctness,
        blocking=blocking,
        reason=reason,
    )


def _write_report(*, gate_name: str, steps: Sequence[GateStepResult], report_path: Path, metadata: dict[str, object]) -> None:
    total_duration = sum(step.duration_seconds for step in steps)
    payload: dict[str, object] = {
        "schema": _REPORT_SCHEMA,
        "gate": gate_name,
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "total_duration_seconds": round(total_duration, 3),
        "status": "pass" if not any(step.blocking for step in steps) else "fail",
        "metadata": metadata,
        "steps": [step.as_dict() for step in steps],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print_step(f"Gate report: {report_path}")


def _run_merge_unblock(parsed: argparse.Namespace) -> None:
    gate_name = "merge-unblock"
    report_path = _resolve_report_path(parsed.report_path, gate_name)
    env = dict(os.environ)
    if parsed.serial:
        env["ADB_SERIAL"] = parsed.serial
    env.setdefault("ADB_MDNS_AUTO_CONNECT", "0")

    changed_files = _collect_changed_files()
    lifecycle_required, lifecycle_reason = _should_run_lifecycle(
        event_name=parsed.event_name,
        ref_name=parsed.ref_name,
        risk_labels=parsed.risk_label,
        changed_files=changed_files,
        force=parsed.force_lifecycle,
        skip=parsed.skip_lifecycle,
    )

    steps: list[GateStepResult] = []
    steps.append(
        _run_gate_command(
            name="merge",
            command=["bash", "scripts/dev/test.sh", "merge"],
            env=env,
            allow_harness_noise=False,
        )
    )
    steps.append(
        _run_gate_command(
            name="doctor",
            command=["python3", "tools/devctl/main.py", "doctor"],
            env=env,
            allow_harness_noise=False,
        )
    )
    steps.append(
        _run_gate_command(
            name="android-instrumented",
            command=["python3", "tools/devctl/main.py", "lane", "android-instrumented"],
            env=env,
            allow_harness_noise=False,
        )
    )

    lifecycle_command = _lifecycle_lane_command()
    if lifecycle_required:
        steps.append(
            _run_gate_command(
                name="lifecycle-e2e-first-run",
                command=lifecycle_command,
                env=env,
                allow_harness_noise=False,
            )
        )
    else:
        steps.append(
            GateStepResult(
                name="lifecycle-e2e-first-run",
                command=lifecycle_command,
                started_at=datetime.now().isoformat(timespec="seconds"),
                duration_seconds=0.0,
                status="skipped",
                correctness="skipped",
                blocking=False,
                reason=lifecycle_reason,
            )
        )

    _write_report(
        gate_name=gate_name,
        steps=steps,
        report_path=report_path,
        metadata={
            "event_name": parsed.event_name,
            "ref_name": parsed.ref_name,
            "risk_labels": parsed.risk_label,
            "lifecycle_required": lifecycle_required,
            "lifecycle_reason": lifecycle_reason,
            "lifecycle_execution_path": "devctl lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml",
            "changed_files_count": len(changed_files),
        },
    )

    blocking = [step for step in steps if step.blocking]
    if blocking:
        summary = ", ".join(f"{step.name}({step.correctness})" for step in blocking)
        raise DevctlError("DEVICE_ERROR", f"merge-unblock gate failed: {summary}")


def _run_promotion(parsed: argparse.Namespace) -> None:
    gate_name = "promotion"
    report_path = _resolve_report_path(parsed.report_path, gate_name)
    env = dict(os.environ)
    if parsed.serial:
        env["ADB_SERIAL"] = parsed.serial
    env.setdefault("ADB_MDNS_AUTO_CONNECT", "0")
    changed_files = _collect_changed_files()
    stage2_quick_required, stage2_quick_reason = _should_run_stage2_quick(
        risk_labels=parsed.risk_label,
        changed_files=changed_files,
    )

    steps: list[GateStepResult] = []
    steps.append(
        _run_gate_command(
            name="merge",
            command=["bash", "scripts/dev/test.sh", "merge"],
            env=env,
            allow_harness_noise=False,
        )
    )
    steps.append(
        _run_gate_command(
            name="doctor",
            command=["python3", "tools/devctl/main.py", "doctor"],
            env=env,
            allow_harness_noise=False,
        )
    )
    steps.append(
        _run_gate_command(
            name="android-instrumented",
            command=["python3", "tools/devctl/main.py", "lane", "android-instrumented"],
            env=env,
            allow_harness_noise=False,
        )
    )
    steps.append(
        _run_gate_command(
            name="maestro",
            command=["python3", "tools/devctl/main.py", "lane", "maestro"],
            env=env,
            allow_harness_noise=False,
        )
    )
    steps.append(
        _run_gate_command(
            name="journey",
            command=[
                "python3",
                "tools/devctl/main.py",
                "lane",
                "journey",
                "--mode",
                "strict",
                "--repeats",
                "1",
                "--reply-timeout-seconds",
                "90",
            ],
            env=env,
            allow_harness_noise=True,
        )
    )

    if parsed.include_screenshot_pack:
        steps.append(
            _run_gate_command(
                name="screenshot-pack",
                command=[
                    "python3",
                    "tools/devctl/main.py",
                    "lane",
                    "screenshot-pack",
                    "--product-signal-only",
                ],
                env=env,
                allow_harness_noise=True,
            )
        )

    if stage2_quick_required:
        steps.append(
            _run_gate_command(
                name="stage2-quick",
                command=[
                    "python3",
                    "tools/devctl/main.py",
                    "lane",
                    "stage2",
                    "--profile",
                    "quick",
                ],
                env=env,
                allow_harness_noise=False,
            )
        )
    else:
        steps.append(
            GateStepResult(
                name="stage2-quick",
                command=[
                    "python3",
                    "tools/devctl/main.py",
                    "lane",
                    "stage2",
                    "--profile",
                    "quick",
                ],
                started_at=datetime.now().isoformat(timespec="seconds"),
                duration_seconds=0.0,
                status="skipped",
                correctness="skipped",
                blocking=False,
                reason=stage2_quick_reason,
            )
        )

    _write_report(
        gate_name=gate_name,
        steps=steps,
        report_path=report_path,
        metadata={
            "include_screenshot_pack": parsed.include_screenshot_pack,
            "risk_labels": parsed.risk_label,
            "stage2_quick_required": stage2_quick_required,
            "stage2_quick_reason": stage2_quick_reason,
            "changed_files_count": len(changed_files),
        },
    )

    blocking = [step for step in steps if step.blocking]
    if blocking:
        summary = ", ".join(f"{step.name}({step.correctness})" for step in blocking)
        raise DevctlError("DEVICE_ERROR", f"promotion gate failed: {summary}")


def dispatch_gate(raw_args: Sequence[str]) -> None:
    parsed = _parse_gate_args(raw_args)
    if parsed.name == "merge-unblock":
        _run_merge_unblock(parsed)
        return
    if parsed.name == "promotion":
        _run_promotion(parsed)
        return
    raise DevctlError("CONFIG_ERROR", f"Unknown gate '{parsed.name}'.")
