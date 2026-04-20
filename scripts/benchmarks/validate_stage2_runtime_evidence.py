#!/usr/bin/env python3
"""Validate Stage-2 runtime artifacts for ENG-13 closure readiness."""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

REQUIRED_FILES = (
    "scenario-a.csv",
    "scenario-b.csv",
    "stage-2-threshold-input.csv",
    "model-1.7b-metrics.csv",
    "logcat.txt",
)


class ValidationError(Exception):
    pass


def _read_csv_rows(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        header = list(reader.fieldnames or [])
        rows = [dict(row) for row in reader]
    if not header:
        raise ValidationError(f"CSV has no header: {path}")
    if not rows:
        raise ValidationError(f"CSV has no data rows: {path}")
    return header, rows


def _as_float(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _validate_metrics_csv(path: Path, expect_scenarios: set[str] | None = None) -> None:
    header, rows = _read_csv_rows(path)
    header_set = set(header)

    if "backend" not in header_set:
        raise ValidationError(f"Missing required column 'backend' in {path}")

    if expect_scenarios and "scenario" not in header_set:
        raise ValidationError(f"Missing required column 'scenario' in {path}")

    seen_scenarios: set[str] = set()
    for idx, row in enumerate(rows, start=2):
        if any((value or "").strip().lower() == "not_run" for value in row.values()):
            raise ValidationError(f"Placeholder value 'not_run' found in {path}:{idx}")

        backend = (row.get("backend") or "").strip()
        if backend != "NATIVE_JNI":
            raise ValidationError(
                f"backend must be NATIVE_JNI in {path}:{idx}; found '{backend or 'EMPTY'}'"
            )

        if expect_scenarios:
            scenario = (row.get("scenario") or "").strip().upper()
            if scenario:
                seen_scenarios.add(scenario)

        ft = _as_float(row.get("first_token_ms"))
        tps = _as_float(row.get("decode_tps"))
        if ft is not None and ft <= 0:
            raise ValidationError(f"first_token_ms must be > 0 in {path}:{idx}")
        if tps is not None and tps <= 0:
            raise ValidationError(f"decode_tps must be > 0 in {path}:{idx}")

    if expect_scenarios:
        missing = sorted(expect_scenarios - seen_scenarios)
        if missing:
            raise ValidationError(f"Missing required scenario rows in {path}: {', '.join(missing)}")


def _validate_model_1_7b(path: Path) -> None:
    _, rows = _read_csv_rows(path)
    seen_scenarios: set[str] = set()
    for idx, row in enumerate(rows, start=2):
        model = (row.get("model") or row.get("model_id") or "").strip().lower()
        if "1.7b" not in model:
            raise ValidationError(f"model-1.7b-metrics row is not a 1.7B model in {path}:{idx}")
        scenario = (row.get("scenario") or "").strip().upper()
        if scenario:
            seen_scenarios.add(scenario)
    missing = sorted({"A", "B"} - seen_scenarios)
    if missing:
        raise ValidationError(f"model-1.7b-metrics.csv missing required scenario rows: {', '.join(missing)}")


def _validate_logcat(path: Path) -> None:
    text = path.read_text(encoding="utf-8", errors="replace")
    if "NATIVE_JNI" not in text:
        raise ValidationError("logcat does not contain NATIVE_JNI evidence")
    if "ADB_FALLBACK" in text:
        raise ValidationError("logcat contains ADB_FALLBACK; closure evidence must be native JNI")


def validate_run_dir(run_dir: Path) -> None:
    if not run_dir.exists() or not run_dir.is_dir():
        raise ValidationError(f"Run directory not found: {run_dir}")

    missing_files = [name for name in REQUIRED_FILES if not (run_dir / name).exists()]
    if missing_files:
        raise ValidationError(f"Missing required artifact files: {', '.join(missing_files)}")
    meminfo_files = sorted(run_dir.glob("meminfo-*.txt"))
    if not meminfo_files:
        raise ValidationError("Missing required meminfo snapshots (expected meminfo-*.txt)")

    _validate_metrics_csv(run_dir / "scenario-a.csv", expect_scenarios={"A"})
    _validate_metrics_csv(run_dir / "scenario-b.csv", expect_scenarios={"B"})
    _validate_metrics_csv(run_dir / "stage-2-threshold-input.csv", expect_scenarios={"A", "B"})
    _validate_metrics_csv(run_dir / "model-1.7b-metrics.csv")
    _validate_model_1_7b(run_dir / "model-1.7b-metrics.csv")
    _validate_logcat(run_dir / "logcat.txt")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate Stage-2 benchmark artifacts for native runtime closure evidence"
    )
    parser.add_argument("run_dir", help="Path to scripts/benchmarks/runs/YYYY-MM-DD/your-device-id")
    args = parser.parse_args()

    run_dir = Path(args.run_dir)
    try:
        validate_run_dir(run_dir)
    except ValidationError as exc:
        print("Stage-2 Runtime Evidence Validation: FAIL")
        print(f"Reason: {exc}")
        return 1

    print("Stage-2 Runtime Evidence Validation: PASS")
    print(f"Run directory: {run_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
