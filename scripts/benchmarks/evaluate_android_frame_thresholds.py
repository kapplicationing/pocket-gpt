#!/usr/bin/env python3
"""Validate and evaluate three Android frame-performance samples."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Sequence


EXPECTED_SAMPLE_COUNT = 3
DEFAULT_PACKAGE = "com.pocketagent.android"
SUPPORTED_SCENARIOS = {"settings-nav", "model-sheet", "drawer-search"}
METRIC_THRESHOLDS = {
    "janky_pct": 20.0,
    "p50_ms": 14.0,
    "p90_ms": 25.0,
    "p99_ms": 32.0,
}
BUILD_IDENTITY_FIELDS = (
    "version_code",
    "version_name",
    "last_update_time",
    "installed_apk_path",
)
ALLOWED_BUILD_SOURCES = {"assembled-native-benchmark", "preinstalled-nondebuggable"}


class ValidationError(ValueError):
    """Raised when samples cannot truthfully represent one benchmark run group."""


def _load_json_object(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        raise ValidationError(f"cannot read {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise ValidationError(f"invalid JSON in {path}: {error}") from error
    if not isinstance(payload, dict):
        raise ValidationError(f"{path} must contain a JSON object")
    return payload


def _required_text(sample: dict[str, Any], field: str, path: Path) -> str:
    value = sample.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{path}: {field} must be a non-empty string")
    return value.strip()


def _required_number(sample: dict[str, Any], field: str, path: Path) -> float:
    value = sample.get(field)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError(f"{path}: {field} must be a number")
    numeric = float(value)
    if not math.isfinite(numeric):
        raise ValidationError(f"{path}: {field} must be finite")
    return numeric


def _validate_timestamp(value: str, field: str, path: Path) -> None:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValidationError(f"{path}: {field} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValidationError(f"{path}: {field} must include a timezone")


def _require_same(samples: Sequence[dict[str, Any]], field: str, paths: Sequence[Path]) -> str:
    values = [_required_text(sample, field, path) for sample, path in zip(samples, paths)]
    if len(set(values)) != 1:
        detail = ", ".join(f"{path.name}={value!r}" for path, value in zip(paths, values))
        raise ValidationError(f"samples have mixed {field}: {detail}")
    return values[0]


def evaluate_samples(
    summary_paths: Sequence[Path],
    *,
    expected_scenario: str | None = None,
    expected_package: str = DEFAULT_PACKAGE,
) -> dict[str, Any]:
    paths = [Path(path).resolve() for path in summary_paths]
    if len(paths) != EXPECTED_SAMPLE_COUNT:
        raise ValidationError(f"expected exactly {EXPECTED_SAMPLE_COUNT} summaries, got {len(paths)}")
    if len(set(paths)) != EXPECTED_SAMPLE_COUNT:
        raise ValidationError("summary paths must be unique")

    samples = [_load_json_object(path) for path in paths]
    scenario = _require_same(samples, "scenario", paths)
    serial = _require_same(samples, "serial", paths)
    package = _require_same(samples, "package", paths)
    build_identity = {field: _require_same(samples, field, paths) for field in BUILD_IDENTITY_FIELDS}

    if scenario not in SUPPORTED_SCENARIOS:
        raise ValidationError(f"unsupported scenario {scenario!r}")
    if expected_scenario is not None and scenario != expected_scenario:
        raise ValidationError(f"expected scenario {expected_scenario!r}, got {scenario!r}")
    if package != expected_package:
        raise ValidationError(f"expected package {expected_package!r}, got {package!r}")
    if not build_identity["version_code"].isdigit():
        raise ValidationError("version_code must contain only decimal digits")
    try:
        datetime.strptime(build_identity["last_update_time"], "%Y-%m-%d %H:%M:%S")
    except ValueError as error:
        raise ValidationError("last_update_time must use YYYY-MM-DD HH:MM:SS") from error
    installed_apk_path = build_identity["installed_apk_path"]
    if not installed_apk_path.startswith("/") or not installed_apk_path.endswith(".apk"):
        raise ValidationError("installed_apk_path must be an absolute Android APK path")

    metrics: dict[str, list[float]] = {field: [] for field in METRIC_THRESHOLDS}
    benchmark_anchors = 0
    for sample, path in zip(samples, paths):
        if sample.get("debuggable") is not False:
            raise ValidationError(f"{path}: debuggable must be exactly false")

        build_source = _required_text(sample, "build_source", path)
        if build_source not in ALLOWED_BUILD_SOURCES:
            raise ValidationError(f"{path}: unsupported build_source {build_source!r}")
        build_variant = _required_text(sample, "build_variant", path)
        native_packaged = sample.get("native_runtime_packaged")
        if build_source == "assembled-native-benchmark":
            benchmark_anchors += 1
            if build_variant != "benchmark" or native_packaged is not True:
                raise ValidationError(
                    f"{path}: assembled benchmark must declare build_variant='benchmark' "
                    "and native_runtime_packaged=true"
                )
        elif build_variant != "unverified-nondebuggable" or native_packaged is not None:
            raise ValidationError(
                f"{path}: preinstalled sample must keep variant/native provenance unverified"
            )

        started_at = _required_text(sample, "started_at_utc", path)
        _validate_timestamp(started_at, "started_at_utc", path)

        artifact_dir = Path(_required_text(sample, "artifact_dir", path)).resolve()
        if artifact_dir != path.parent:
            raise ValidationError(
                f"{path}: artifact_dir must resolve to the summary's parent directory"
            )

        sample_metrics = {field: _required_number(sample, field, path) for field in METRIC_THRESHOLDS}
        if not 0.0 <= sample_metrics["janky_pct"] <= 100.0:
            raise ValidationError(f"{path}: janky_pct must be between 0 and 100")
        if any(sample_metrics[field] < 0.0 for field in ("p50_ms", "p90_ms", "p99_ms")):
            raise ValidationError(f"{path}: frame percentiles cannot be negative")
        if not sample_metrics["p50_ms"] <= sample_metrics["p90_ms"] <= sample_metrics["p99_ms"]:
            raise ValidationError(f"{path}: frame percentiles must satisfy p50 <= p90 <= p99")
        for field, value in sample_metrics.items():
            metrics[field].append(value)

    if benchmark_anchors != 1:
        raise ValidationError(
            "run group must contain exactly one assembled native benchmark sample "
            f"but found {benchmark_anchors}"
        )

    medians = {field: float(statistics.median(values)) for field, values in metrics.items()}
    checks = [
        {
            "metric": field,
            "median": medians[field],
            "maximum": maximum,
            "status": "PASS" if medians[field] <= maximum else "FAIL",
        }
        for field, maximum in METRIC_THRESHOLDS.items()
    ]
    status = "PASS" if all(check["status"] == "PASS" for check in checks) else "FAIL"
    return {
        "status": status,
        "sample_count": EXPECTED_SAMPLE_COUNT,
        "scenario": scenario,
        "serial": serial,
        "package": package,
        "build_variant": "benchmark",
        "native_runtime_packaged": True,
        "build_identity": build_identity,
        "thresholds": METRIC_THRESHOLDS,
        "medians": medians,
        "checks": checks,
        "summaries": [str(path) for path in paths],
    }


def _print_report(report: dict[str, Any]) -> None:
    print("Android Frame Threshold Evaluation")
    print("==================================")
    print(
        f"scenario={report['scenario']} serial={report['serial']} "
        f"samples={report['sample_count']} build={report['build_variant']}"
    )
    for check in report["checks"]:
        print(
            f"- {check['metric']}: median={check['median']:.2f} "
            f"maximum={check['maximum']:.2f} {check['status']}"
        )
    print(f"Overall: {report['status']}")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate exactly three benchmark frame summaries and enforce median thresholds."
    )
    parser.add_argument("summaries", nargs="+", type=Path, help="Three summary.json paths")
    parser.add_argument("--scenario", help="Expected scenario name")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="Expected Android package")
    parser.add_argument("--output", type=Path, help="Write the evaluation report as JSON")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        report = evaluate_samples(
            args.summaries,
            expected_scenario=args.scenario,
            expected_package=args.package,
        )
    except ValidationError as error:
        print(f"Validation error: {error}", file=sys.stderr)
        return 1

    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    _print_report(report)
    return 0 if report["status"] == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
