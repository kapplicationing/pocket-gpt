from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


REPO_ROOT = Path(__file__).resolve().parents[2]
REQUIRED_FIELDS = (
    "phase",
    "elapsed_ms",
    "runtime_status",
    "backend",
    "active_model_id",
    "placeholder_visible",
)


def _load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return payload


def _resolve_report_path(*, explicit_path: Path | None, journey_log: Path) -> Path | None:
    if explicit_path is not None:
        candidate = explicit_path if explicit_path.is_absolute() else REPO_ROOT / explicit_path
        return candidate.resolve() if candidate.exists() else None

    if not journey_log.exists():
        return None
    matches = re.findall(r"([^\s`'\"]*journey-report\.json)", journey_log.read_text(encoding="utf-8", errors="replace"))
    for raw_match in reversed(matches):
        candidate = Path(raw_match.rstrip(".,;:)"))
        if not candidate.is_absolute():
            candidate = REPO_ROOT / candidate
        if candidate.exists():
            return candidate.resolve()
    return None


def _send_capture_step(journey_payload: Mapping[str, Any]) -> dict[str, Any] | None:
    steps = journey_payload.get("steps")
    if not isinstance(steps, list):
        return None
    for raw_step in reversed(steps):
        if not isinstance(raw_step, dict):
            continue
        name = str(raw_step.get("name") or "")
        if name == "send-capture" or name.endswith(":send-capture"):
            return dict(raw_step)
    return None


def _field_is_present(field: str, value: Any) -> bool:
    if value is None:
        return False
    if field in {"runtime_status", "backend", "active_model_id", "phase"}:
        return bool(str(value).strip())
    if field == "placeholder_visible":
        return isinstance(value, bool)
    if field == "elapsed_ms":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return True


def _delta(current_status: str, previous_payload: Mapping[str, Any] | None) -> tuple[str, str | None]:
    if previous_payload is None:
        return "baseline", None
    previous_status = str(previous_payload.get("status") or "").strip().upper() or None
    if previous_status == "PASS" and current_status == "FAIL":
        return "regressed", previous_status
    if previous_status == "FAIL" and current_status == "PASS":
        return "resolved", previous_status
    if previous_status == current_status == "PASS":
        return "no change", previous_status
    if previous_status == current_status == "FAIL":
        return "still failing", previous_status
    return "changed", previous_status


def build_weekly_packet(
    *,
    journey_payload: Mapping[str, Any] | None,
    journey_report: Path | None,
    journey_log: Path,
    lane_exit_code: int,
    previous_payload: Mapping[str, Any] | None = None,
    evidence_errors: Sequence[str] = (),
    warnings: Sequence[str] = (),
) -> dict[str, Any]:
    step = _send_capture_step(journey_payload or {})
    values = {field: step.get(field) if step is not None else None for field in REQUIRED_FIELDS}
    missing_fields = [field for field, value in values.items() if not _field_is_present(field, value)]
    passing_contract = (
        lane_exit_code == 0
        and step is not None
        and str(step.get("status") or "").strip().lower() == "passed"
        and values["phase"] == "completed"
        and values["placeholder_visible"] is False
        and not missing_fields
        and not evidence_errors
    )
    status = "PASS" if passing_contract else "FAIL"
    delta, previous_status = _delta(status, previous_payload)

    failure_reasons: list[str] = []
    failure_reasons.extend(evidence_errors)
    if lane_exit_code != 0:
        failure_reasons.append(f"journey lane exited {lane_exit_code}")
    if journey_report is None:
        failure_reasons.append("journey report missing")
    if step is None:
        failure_reasons.append("send-capture step missing")
    if missing_fields:
        failure_reasons.append(f"required fields missing or invalid: {', '.join(missing_fields)}")
    if step is not None and str(step.get("status") or "").strip().lower() != "passed":
        failure_reasons.append(f"send-capture status={step.get('status') or 'unknown'}")
    if step is not None and values["phase"] != "completed":
        failure_reasons.append(f"phase={values['phase'] or 'unknown'}")
    if step is not None and values["placeholder_visible"] is not False:
        failure_reasons.append(f"placeholder_visible={values['placeholder_visible']}")

    return {
        "schema": "qa13-weekly-send-capture-v1",
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "status": status,
        "delta_vs_prior_week": delta,
        "previous_status": previous_status,
        "severity": None if status == "PASS" else "UX-S1",
        "owner": "QA + Engineering",
        "blocking_issue_required": status == "FAIL",
        "journey_exit_code": lane_exit_code,
        "journey_report": str(journey_report) if journey_report is not None else None,
        "journey_log": str(journey_log),
        "device": (journey_payload or {}).get("serial") or (journey_payload or {}).get("device"),
        "send_capture": {
            **values,
            "status": step.get("status") if step is not None else None,
            "failure_signature": step.get("failure_signature") if step is not None else None,
            "details": step.get("details") if step is not None else None,
        },
        "missing_required_fields": missing_fields,
        "failure_reasons": failure_reasons,
        "warnings": list(warnings),
    }


def _markdown(packet: Mapping[str, Any]) -> str:
    capture = packet["send_capture"]
    severity = packet.get("severity") or "-"
    evidence = packet.get("journey_report") or packet.get("journey_log") or "missing"
    lines = [
        "# QA-13 Weekly Send-Capture Gate",
        "",
        f"- Generated: `{packet['generated_at']}`",
        f"- Device: `{packet.get('device') or 'unknown'}`",
        f"- Blocking issue required: `{str(packet['blocking_issue_required']).lower()}`",
        "",
        "| Area | Status | Delta vs Prior Week | Severity | Owner | Evidence |",
        "|---|---|---|---|---|---|",
        f"| Recovery + timeout semantics | {packet['status']} | {packet['delta_vs_prior_week']} | "
        f"{severity} | {packet['owner']} | `{evidence}` |",
        "",
        "| Required field | Value |",
        "|---|---|",
    ]
    for field in REQUIRED_FIELDS:
        value = capture.get(field)
        rendered = str(value).lower() if isinstance(value, bool) else (str(value) if value is not None else "missing")
        lines.append(f"| `{field}` | `{rendered}` |")
    lines.extend(
        [
            "",
            f"- Send-capture step status: `{capture.get('status') or 'missing'}`",
            f"- Failure signature: `{capture.get('failure_signature') or '-'}`",
        ],
    )
    failure_reasons = packet.get("failure_reasons") or []
    if failure_reasons:
        lines.extend(["", "## Blocking reasons", ""])
        lines.extend(f"- {reason}" for reason in failure_reasons)
    warnings = packet.get("warnings") or []
    if warnings:
        lines.extend(["", "## Warnings", ""])
        lines.extend(f"- {warning}" for warning in warnings)
    return "\n".join(lines).rstrip() + "\n"


def write_weekly_packet(*, packet: Mapping[str, Any], output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "qa-13-weekly-report.json"
    markdown_path = output_dir / "qa-13-weekly-summary.md"
    json_path.write_text(json.dumps(packet, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(_markdown(packet), encoding="utf-8")
    return json_path, markdown_path


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Create the QA-13 weekly packet from a strict journey report.")
    parser.add_argument("--journey-log", type=Path, required=True)
    parser.add_argument("--journey-report", type=Path)
    parser.add_argument("--previous-report", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--lane-exit-code", type=int, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    journey_report = _resolve_report_path(explicit_path=args.journey_report, journey_log=args.journey_log)
    journey_payload = None
    evidence_errors: list[str] = []
    if journey_report is not None:
        try:
            journey_payload = _load_json(journey_report)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            evidence_errors.append(f"journey report invalid: {exc}")
    previous_payload = None
    warnings: list[str] = []
    if args.previous_report is not None and args.previous_report.exists():
        try:
            previous_payload = _load_json(args.previous_report)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            warnings.append(f"prior weekly report ignored: {exc}")
    packet = build_weekly_packet(
        journey_payload=journey_payload,
        journey_report=journey_report,
        journey_log=args.journey_log,
        lane_exit_code=args.lane_exit_code,
        previous_payload=previous_payload,
        evidence_errors=evidence_errors,
        warnings=warnings,
    )
    json_path, markdown_path = write_weekly_packet(packet=packet, output_dir=args.output_dir)
    print(f"QA-13 weekly report: {json_path}")
    print(f"QA-13 weekly summary: {markdown_path}")
    return 0 if packet["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
