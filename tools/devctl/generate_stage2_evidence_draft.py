#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import statistics
from pathlib import Path


def _median(values: list[float]) -> str:
    if not values:
        return "n/a"
    return f"{statistics.median(values):.2f}"


def _as_float(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _read_rows(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        return [dict(row) for row in reader]


def _metrics_summary(rows: list[dict[str, str]]) -> tuple[str, str, int]:
    first_token = [_as_float(row.get("first_token_ms")) for row in rows]
    decode_tps = [_as_float(row.get("decode_tps")) for row in rows]
    first_token_clean = [value for value in first_token if value is not None and value > 0]
    decode_tps_clean = [value for value in decode_tps if value is not None and value > 0]
    return _median(first_token_clean), _median(decode_tps_clean), len(rows)


def _rel(path: Path, base: Path) -> str:
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


def build_draft(run_dir: Path) -> str:
    summary_path = run_dir / "summary.json"
    threshold_report = run_dir / "threshold-report.txt"
    runtime_report = run_dir / "runtime-evidence-validation.txt"
    scenario_a = run_dir / "scenario-a.csv"
    scenario_b = run_dir / "scenario-b.csv"
    model_2b = run_dir / "model-1.7b-metrics.csv"
    logcat = run_dir / "logcat.txt"
    notes = run_dir / "notes.md"

    summary_data: dict[str, object] = {}
    if summary_path.exists():
        summary_data = json.loads(summary_path.read_text(encoding="utf-8"))

    a_rows = _read_rows(scenario_a)
    b_rows = _read_rows(scenario_b)
    model_2b_rows = _read_rows(model_2b)

    a_ft, a_tps, a_count = _metrics_summary(a_rows)
    b_ft, b_tps, b_count = _metrics_summary(b_rows)
    m2_ft, m2_tps, m2_count = _metrics_summary(model_2b_rows)

    meminfo_files = sorted(run_dir.glob("meminfo-*.txt"))

    lines = [
        "# Stage-2 Evidence Draft",
        "",
        "## Run Context",
        f"- Date: {summary_data.get('run_date', 'unknown')}",
        f"- Device: {summary_data.get('device_id', 'unknown')}",
        f"- Profile: {summary_data.get('profile', 'unknown')}",
        f"- Models: {summary_data.get('models', 'unknown')}",
        f"- Scenarios: {summary_data.get('scenarios', 'unknown')}",
        f"- Resume used: {summary_data.get('resume_used', 'unknown')}",
        f"- Install mode: {summary_data.get('install_mode', 'unknown')}",
        f"- Strict thresholds: {summary_data.get('strict_thresholds', 'unknown')}",
        f"- Model load mode: {summary_data.get('model_load_mode', 'unknown')}",
        f"- Prefix cache enabled: {summary_data.get('prefix_cache_enabled', 'unknown')}",
        f"- Prefix cache hits: {summary_data.get('prefix_cache_hits', 'unknown')}",
        f"- Prefix cache misses: {summary_data.get('prefix_cache_misses', 'unknown')}",
        f"- Prefill tokens reused: {summary_data.get('prefill_tokens_reused', 'unknown')}",
        "",
        "## Artifact Paths",
        f"- Run dir: {run_dir}",
        f"- Scenario A CSV: {_rel(scenario_a, run_dir)}",
        f"- Scenario B CSV: {_rel(scenario_b, run_dir)}",
        f"- 1.7B metrics CSV: {_rel(model_2b, run_dir)}",
        f"- Threshold report: {_rel(threshold_report, run_dir)}",
        f"- Runtime validator report: {_rel(runtime_report, run_dir)}",
        f"- Logcat: {_rel(logcat, run_dir)}",
        f"- Notes: {_rel(notes, run_dir)}",
        "",
        "## Metrics Snapshot",
        f"- Scenario A rows: {a_count} | median first-token ms: {a_ft} | median decode tok/s: {a_tps}",
        f"- Scenario B rows: {b_count} | median first-token ms: {b_ft} | median decode tok/s: {b_tps}",
        f"- 1.7B rows: {m2_count} | median first-token ms: {m2_ft} | median decode tok/s: {m2_tps}",
        "",
        "## Meminfo Artifacts",
    ]

    if meminfo_files:
        lines.extend([f"- {_rel(path, run_dir)}" for path in meminfo_files])
    else:
        lines.append("- (none found)")

    lines.extend(
        [
            "",
            "## Gate Status",
            f"- Threshold exit code: {summary_data.get('threshold_exit_code', 'unknown')}",
            f"- Runtime evidence exit code: {summary_data.get('runtime_evidence_exit_code', 'unknown')}",
            f"- Warm-vs-cold first token delta ms: {summary_data.get('warm_vs_cold_first_token_delta_ms', 'unknown')}",
            "",
            "## Notes",
            "- Replace this draft heading and add ticket-specific acceptance mapping before publishing.",
        ]
    )

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a stage2 evidence draft from run artifacts")
    parser.add_argument("run_dir", help="Path to scripts/benchmarks/runs/YYYY-MM-DD/<device>")
    parser.add_argument("--output", help="Output markdown path", default=None)
    parser.add_argument("--evidence-note-path", help="Optional copy target for ops evidence note", default=None)
    args = parser.parse_args()

    run_dir = Path(args.run_dir).resolve()
    if not run_dir.exists() or not run_dir.is_dir():
        raise SystemExit(f"Run directory not found: {run_dir}")

    output_path = Path(args.output).resolve() if args.output else (run_dir / "evidence-draft.md")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    draft = build_draft(run_dir)
    output_path.write_text(draft, encoding="utf-8")

    if args.evidence_note_path:
        note_path = Path(args.evidence_note_path).resolve()
        note_path.parent.mkdir(parents=True, exist_ok=True)
        note_path.write_text(draft, encoding="utf-8")

    print(f"Evidence draft written to {output_path}")
    if args.evidence_note_path:
        print(f"Evidence note copy written to {Path(args.evidence_note_path).resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
