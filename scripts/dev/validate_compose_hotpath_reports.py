#!/usr/bin/env python3
"""Fail-closed validation for benchmark Compose compiler evidence."""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable, Sequence


REQUIRED_MODULE_METRICS = (
    "skippableComposables",
    "restartableComposables",
    "readonlyComposables",
    "totalComposables",
)


class ReportValidationError(ValueError):
    """Raised when report files cannot prove the benchmark Compose state."""


def _single_nonempty_file(directory: Path, pattern: str, label: str) -> Path:
    matches = sorted(path for path in directory.glob(pattern) if path.is_file())
    if len(matches) != 1:
        raise ReportValidationError(
            f"expected exactly one {label} in {directory}, found {len(matches)}"
        )
    path = matches[0]
    if path.stat().st_size <= 0:
        raise ReportValidationError(f"{label} is empty: {path}")
    return path


def _iter_input_files(paths: Iterable[Path]) -> Iterable[Path]:
    for path in paths:
        if path.is_file():
            yield path
        elif path.is_dir():
            yield from (child for child in path.rglob("*") if child.is_file())


def _require_fresh(
    generated_files: Sequence[Path],
    *,
    not_before_epoch: float,
    freshness_inputs: Sequence[Path],
) -> None:
    oldest_generated = min(path.stat().st_mtime for path in generated_files)
    if oldest_generated + 1.0 < not_before_epoch:
        stale = [str(path) for path in generated_files if path.stat().st_mtime + 1.0 < not_before_epoch]
        raise ReportValidationError(
            "Compose evidence is stale relative to the current build start: " + ", ".join(stale)
        )

    newer_inputs = [
        path
        for path in _iter_input_files(freshness_inputs)
        if path.stat().st_mtime > oldest_generated + 1.0
    ]
    if newer_inputs:
        preview = ", ".join(str(path) for path in newer_inputs[:5])
        raise ReportValidationError(
            f"Compose evidence is stale relative to source/config inputs: {preview}"
        )


def _required_nonnegative_integer(payload: dict[str, Any], field: str, path: Path) -> int:
    value = payload.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ReportValidationError(f"{path}: {field} must be a non-negative integer")
    return value


def validate_reports(
    *,
    report_dir: Path,
    metrics_dir: Path,
    variant: str,
    expected_composables: Sequence[str],
    not_before_epoch: float,
    freshness_inputs: Sequence[Path] = (),
) -> dict[str, Any]:
    """Validate a complete, fresh benchmark report bundle and return its metrics."""
    report_dir = Path(report_dir).resolve()
    metrics_dir = Path(metrics_dir).resolve()
    if variant != "benchmark":
        raise ReportValidationError(
            f"performance evidence must use the benchmark variant, got {variant!r}"
        )
    if report_dir.name != variant or metrics_dir.name != variant:
        raise ReportValidationError(
            "Compose report and metrics directories must be benchmark-specific"
        )
    if not report_dir.is_dir() or not metrics_dir.is_dir():
        raise ReportValidationError(
            f"Compose report bundle is incomplete: reports={report_dir} metrics={metrics_dir}"
        )

    composables_report = _single_nonempty_file(
        report_dir, "*-composables.txt", "composables report"
    )
    classes_report = _single_nonempty_file(report_dir, "*-classes.txt", "classes report")
    composables_csv = _single_nonempty_file(
        report_dir, "*-composables.csv", "composables CSV"
    )
    module_json = _single_nonempty_file(metrics_dir, "*-module.json", "module metrics")
    generated_files = (composables_report, classes_report, composables_csv, module_json)
    _require_fresh(
        generated_files,
        not_before_epoch=not_before_epoch,
        freshness_inputs=tuple(Path(path).resolve() for path in freshness_inputs),
    )

    try:
        module_payload = json.loads(module_json.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReportValidationError(f"invalid module metrics {module_json}: {error}") from error
    if not isinstance(module_payload, dict):
        raise ReportValidationError(f"{module_json}: module metrics must be a JSON object")
    module_metrics = {
        field: _required_nonnegative_integer(module_payload, field, module_json)
        for field in REQUIRED_MODULE_METRICS
    }
    if module_metrics["totalComposables"] <= 0:
        raise ReportValidationError(
            f"{module_json}: totalComposables must be greater than zero"
        )
    if any(
        module_metrics[field] > module_metrics["totalComposables"]
        for field in REQUIRED_MODULE_METRICS
        if field != "totalComposables"
    ):
        raise ReportValidationError(
            f"{module_json}: component metrics cannot exceed totalComposables"
        )

    try:
        with composables_csv.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
    except OSError as error:
        raise ReportValidationError(f"cannot read {composables_csv}: {error}") from error
    if not rows or not any(any(str(value).strip() for value in row.values()) for row in rows):
        raise ReportValidationError(f"composables CSV has no metric rows: {composables_csv}")

    report_text = composables_report.read_text(encoding="utf-8", errors="replace")
    missing = [
        name
        for name in expected_composables
        if not re.search(rf"\bfun\s+{re.escape(name)}\s*\(", report_text)
    ]
    if missing:
        raise ReportValidationError(
            "Compose report is missing expected hot-path composables: " + ", ".join(missing)
        )
    if not expected_composables:
        raise ReportValidationError("expected composable list must not be empty")

    return {
        "variant": variant,
        "total_composables": module_metrics["totalComposables"],
        "module_metrics": module_metrics,
        "expected_composables": list(expected_composables),
        "report_dir": str(report_dir),
        "metrics_dir": str(metrics_dir),
    }


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate fresh benchmark Compose compiler reports and metrics."
    )
    parser.add_argument("--report-dir", required=True, type=Path)
    parser.add_argument("--metrics-dir", required=True, type=Path)
    parser.add_argument("--variant", required=True)
    parser.add_argument("--not-before-epoch", required=True, type=float)
    parser.add_argument("--expected-composable", action="append", default=[])
    parser.add_argument("--freshness-input", action="append", default=[], type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    if not math.isfinite(args.not_before_epoch) or args.not_before_epoch < 0:
        print("Validation error: --not-before-epoch must be finite and non-negative", file=sys.stderr)
        return 1
    try:
        result = validate_reports(
            report_dir=args.report_dir,
            metrics_dir=args.metrics_dir,
            variant=args.variant,
            expected_composables=args.expected_composable,
            not_before_epoch=args.not_before_epoch,
            freshness_inputs=args.freshness_input,
        )
    except ReportValidationError as error:
        print(f"Validation error: {error}", file=sys.stderr)
        return 1
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"Compose report proof: variant={result['variant']} "
        f"totalComposables={result['total_composables']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
