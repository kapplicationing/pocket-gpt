#!/usr/bin/env python3
"""Merge tmp/qa-agents/<tester>/<stamp>/trip-report.skeleton.json into _inputs/<tester>.json.

The current runner emits a small-discovery artifact pack instead of the old
eight-flow raw Maestro matrix. This script seeds the human-proxy report from
those structured step results and falls back to the older flow format when
needed.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TMP = REPO / "tmp/qa-agents"
OUT_DIR = REPO / "tools/qa-agents/_inputs"

FAIL_LINE = re.compile(
    r"\[Failed\]\s+([^\s(]+)\s+\(([^)]+)\)\s+\((.+)\)\s*$",
    re.MULTILINE,
)


def _parse_duration_seconds(s: str) -> float:
    s = s.strip()
    total = 0.0
    if "m" in s:
        parts = s.split("m", 1)
        total += float(parts[0].strip()) * 60.0
        s = parts[1]
    s = s.replace("s", "").strip()
    if s:
        total += float(s)
    return total


def _first_fail(log_text: str) -> tuple[float, str] | None:
    m = FAIL_LINE.search(log_text)
    if not m:
        return None
    dur_s = _parse_duration_seconds(m.group(2))
    msg = m.group(3).strip()
    return dur_s, msg


def _latest_stamp(tester: str) -> Path:
    base = TMP / tester
    if not base.is_dir():
        raise SystemExit(f"no runs under {base}")
    stamps = sorted(p for p in base.iterdir() if p.is_dir())
    if not stamps:
        raise SystemExit(f"no stamp dirs under {base}")
    return stamps[-1]


def merge_one(tester: str, stamp: Path | None) -> Path:
    root = stamp or _latest_stamp(tester)
    skel_path = root / "trip-report.skeleton.json"
    if not skel_path.is_file():
        raise SystemExit(f"missing {skel_path}")
    data = json.loads(skel_path.read_text(encoding="utf-8"))
    artifacts = data.pop("_runner_artifacts", None)
    step_results: dict = (artifacts or {}).get("step_results") or {}
    flows: dict = (artifacts or {}).get("flow_results") or {}

    if step_results:
        return _merge_from_step_results(tester, root, data, step_results)

    def flow_log(name: str) -> str:
        rel = flows.get(name, {}).get("log", "")
        p = REPO / rel if rel else None
        return p.read_text(encoding="utf-8", errors="ignore") if p and p.is_file() else ""

    def flow_ok(name: str) -> bool:
        return flows.get(name, {}).get("exit_code") == 0

    # Workflows A/B/C
    for key, flow in (("A", "workflow-A-send"), ("B", "workflow-B-tools"), ("C", "workflow-C-image")):
        log_t = flow_log(flow)
        fail = _first_fail(log_t)
        ok = flow_ok(flow)
        data["workflows"][key]["completed"] = ok
        if fail:
            data["workflows"][key]["duration_seconds"] = round(fail[0], 3)
            data["workflows"][key]["blocker"] = f"Maestro: {fail[1]} ({flow})"
            data["workflows"][key]["logcat_excerpts"] = [
                f"{flows.get(flow, {}).get('log', '')}: [Failed] ... ({fail[1]})"
            ]
        else:
            data["workflows"][key]["duration_seconds"] = 0.0
            data["workflows"][key]["blocker"] = None
            data["workflows"][key]["logcat_excerpts"] = []

    # Failure states
    mapping = (
        ("recovery_not_ready", "recovery-notready"),
        ("stuck_send", "stuck-send"),
        ("manifest_outage", "manifest-outage"),
    )
    for fs_key, flow in mapping:
        log_t = flow_log(flow)
        fail = _first_fail(log_t)
        ok = flow_ok(flow)
        slot = data["failure_states"][fs_key]
        slot["recovered"] = ok
        if fail:
            slot["duration_seconds"] = round(fail[0], 3)
            slot["notes"] = f"{flow}: {fail[1]} (exit {flows.get(flow, {}).get('exit_code')})"
        else:
            slot["duration_seconds"] = 0.0
            slot["notes"] = "Flow completed (exit 0)." if ok else ""

    blockers: list[str] = []
    for name, info in sorted(flows.items()):
        if info.get("exit_code"):
            fail = _first_fail(flow_log(name))
            detail = fail[1] if fail else "see log"
            blockers.append(f"{name}: exit {info.get('exit_code')} — {detail}")

    all_ok = all(info.get("exit_code") == 0 for info in flows.values())
    data["summary"]["blockers"] = blockers
    data["summary"]["s0_count"] = 0 if all_ok else 2
    data["summary"]["s1_count"] = 0 if all_ok else 1
    data["summary"]["confusion_runtime_pct"] = 0.0 if all_ok else 70.0
    data["summary"]["confusion_privacy_pct"] = 0.0
    data["recommendation"] = "promote" if all_ok else "hold"

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = OUT_DIR / f"{tester}.json"
    out_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return out_path


def _merge_from_step_results(tester: str, root: Path, data: dict, step_results: dict) -> Path:
    runtime_step = step_results.get("runtime-ready", {})
    send_step = step_results.get("send-after-ready", {})
    android_step = step_results.get("android-instrumented", {})

    def _status(step: dict) -> str:
        return str(step.get("status", "failed")) if isinstance(step, dict) else "failed"

    def _message(step: dict, default: str) -> str:
        if not isinstance(step, dict):
            return default
        if step.get("blocker_message"):
            return str(step["blocker_message"])
        first_failed = step.get("first_failed_flow")
        if isinstance(first_failed, dict):
            name = first_failed.get("name") or "unknown-flow"
            message = first_failed.get("message") or "see status.json"
            return f"{name}: {message}"
        return default

    def _evidence_lines(step: dict) -> list[str]:
        if not isinstance(step, dict):
            return []
        lines: list[str] = []
        for key in ("status_json", "report_json", "artifact_root", "run_root", "log"):
            value = step.get(key)
            if value:
                lines.append(f"{key}: {value}")
        return lines

    send_passed = _status(send_step) == "passed"
    runtime_passed = _status(runtime_step) == "passed"
    android_passed = _status(android_step) == "passed"

    data["workflows"]["A"]["completed"] = send_passed
    data["workflows"]["A"]["duration_seconds"] = 0.0
    data["workflows"]["A"]["blocker"] = (
        None
        if send_passed
        else f"send-after-ready: {_message(send_step, 'did not produce a passing hosted verdict in the small discovery path.')}"
    )
    data["workflows"]["A"]["logcat_excerpts"] = _evidence_lines(send_step)

    data["workflows"]["B"]["completed"] = False
    data["workflows"]["B"]["duration_seconds"] = 0.0
    data["workflows"]["B"]["blocker"] = (
        "Small discovery path did not exercise Workflow B; use the current device/cloud artifacts to judge whether this remains open."
    )
    data["workflows"]["B"]["logcat_excerpts"] = _evidence_lines(android_step)

    data["workflows"]["C"]["completed"] = False
    data["workflows"]["C"]["duration_seconds"] = 0.0
    data["workflows"]["C"]["blocker"] = (
        "Small discovery path did not exercise Workflow C; the packet should stay conservative unless separate current evidence closes it."
    )
    data["workflows"]["C"]["logcat_excerpts"] = []

    data["failure_states"]["recovery_not_ready"]["recovered"] = runtime_passed or android_passed
    data["failure_states"]["recovery_not_ready"]["duration_seconds"] = 0.0
    data["failure_states"]["recovery_not_ready"]["notes"] = (
        "Runtime-ready discovery step passed."
        if runtime_passed
        else _message(runtime_step, "Runtime-ready discovery step did not reach a passing ready-state verdict.")
    )

    data["failure_states"]["stuck_send"]["recovered"] = False
    data["failure_states"]["stuck_send"]["duration_seconds"] = 0.0
    data["failure_states"]["stuck_send"]["notes"] = (
        "Small discovery path does not validate stuck-send recovery directly; keep this row open unless separate current evidence closes it."
    )

    data["failure_states"]["manifest_outage"]["recovered"] = False
    data["failure_states"]["manifest_outage"]["duration_seconds"] = 0.0
    data["failure_states"]["manifest_outage"]["notes"] = (
        "Small discovery path does not validate manifest-outage recovery directly; keep this row open unless separate current evidence closes it."
    )

    blockers: list[str] = []
    for name, step in step_results.items():
        if _status(step) != "passed":
            blockers.append(f"{name}: {_message(step, 'see step artifacts')}")
    blockers.append(
        "Small discovery path intentionally leaves some workflow and recovery rows open; reviewer judgment should stay tied to current artifacts only."
    )

    all_steps_passed = all(_status(step) == "passed" for step in step_results.values())
    data["summary"]["blockers"] = blockers
    data["summary"]["s0_count"] = 0
    data["summary"]["s1_count"] = 0 if all_steps_passed else 1
    data["summary"]["confusion_runtime_pct"] = 0.0
    data["summary"]["confusion_privacy_pct"] = 0.0
    data["recommendation"] = "iterate" if all_steps_passed else "hold"

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = OUT_DIR / f"{tester}.json"
    out_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return out_path


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--tester", action="append", help="cloud-1, cloud-2, device-s22, device-a51 (repeatable)")
    p.add_argument("--stamp", help="optional path to stamp dir (single tester only)")
    args = p.parse_args()
    testers = args.tester or ["cloud-1", "cloud-2", "device-s22", "device-a51"]
    stamp = Path(args.stamp) if args.stamp else None
    if stamp and len(testers) != 1:
        print("--stamp requires exactly one --tester", file=sys.stderr)
        return 2
    for t in testers:
        path = merge_one(t, stamp if len(testers) == 1 else None)
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
