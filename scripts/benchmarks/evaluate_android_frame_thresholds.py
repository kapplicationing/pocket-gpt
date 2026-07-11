#!/usr/bin/env python3
"""Validate frame samples and consistency-check declared workload conditions."""

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
MINIMUM_FRAME_COUNT = 20
DEFAULT_PACKAGE = "com.pocketagent.android.benchmark"
DECLARED_CONDITION_SOURCE = "operator-declared-not-observed"
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
ALLOWED_DECLARED_RUNTIME_CONDITIONS = {"unloaded", "loading", "loaded-idle"}
ALLOWED_DECLARED_DOWNLOAD_CONDITIONS = {"idle", "active"}
ALLOWED_DECLARED_VOICE_CONDITIONS = {"inactive", "active"}
DEVICE_IDENTITY_FIELDS = ("manufacturer", "model", "android_release", "api_level")
DEVICE_CONDITION_FIELDS = (
    "refresh_rate_hz_before",
    "refresh_rate_source_before",
    "refresh_rate_evidence_available_before",
    "refresh_rate_hz_after",
    "refresh_rate_source_after",
    "refresh_rate_evidence_available_after",
    "compilation_filter",
    "compilation_reason",
    "compilation_evidence_available",
    "workload_condition_source",
    "declared_runtime_condition",
    "declared_download_condition",
    "declared_voice_condition",
)


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


def _parse_timestamp(value: str, field: str, path: Path) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValidationError(f"{path}: {field} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValidationError(f"{path}: {field} must include a timezone")
    return parsed


def _required_object(sample: dict[str, Any], field: str, path: Path) -> dict[str, Any]:
    value = sample.get(field)
    if not isinstance(value, dict):
        raise ValidationError(f"{path}: {field} must be a JSON object")
    return value


def _required_boolean(sample: dict[str, Any], field: str, path: Path) -> bool:
    value = sample.get(field)
    if not isinstance(value, bool):
        raise ValidationError(f"{path}: {field} must be a boolean")
    return value


def _required_positive_integer(
    sample: dict[str, Any],
    field: str,
    path: Path,
    *,
    minimum: int = 1,
) -> int:
    value = sample.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValidationError(f"{path}: {field} must be an integer >= {minimum}")
    return value


def _validate_device_state(state: dict[str, Any], path: Path) -> dict[str, Any]:
    validated: dict[str, Any] = {
        "manufacturer": _required_text(state, "manufacturer", path),
        "model": _required_text(state, "model", path),
        "android_release": _required_text(state, "android_release", path),
        "api_level": _required_positive_integer(state, "api_level", path),
    }

    for phase in ("before", "after"):
        available_field = f"refresh_rate_evidence_available_{phase}"
        rate_field = f"refresh_rate_hz_{phase}"
        source_field = f"refresh_rate_source_{phase}"
        refresh_available = _required_boolean(state, available_field, path)
        if not refresh_available:
            raise ValidationError(
                f"{path}: {phase} refresh-rate evidence is required for acceptance"
            )
        refresh_rate = _required_number(state, rate_field, path)
        if not 1.0 <= refresh_rate <= 500.0:
            raise ValidationError(f"{path}: {rate_field} is outside a plausible range")
        validated[rate_field] = refresh_rate
        validated[source_field] = _required_text(state, source_field, path)
        if validated[source_field] != "dumpsys-display-active-mode":
            raise ValidationError(
                f"{path}: {source_field} must prove the active display mode, "
                f"observed {validated[source_field]!r}"
            )
        validated[available_field] = refresh_available
    if not math.isclose(
        validated["refresh_rate_hz_before"],
        validated["refresh_rate_hz_after"],
        abs_tol=0.1,
    ):
        raise ValidationError(
            f"{path}: refresh-rate drift is not valid acceptance evidence "
            f"({validated['refresh_rate_hz_before']} -> {validated['refresh_rate_hz_after']} Hz)"
        )

    for field in ("thermal_status_before", "thermal_status_after"):
        value = _required_number(state, field, path)
        if value != int(value) or not 0 <= value <= 6:
            raise ValidationError(f"{path}: {field} must be an Android thermal status from 0 to 6")
        validated[field] = int(value)
        if validated[field] != 0:
            raise ValidationError(f"{path}: {field} must remain 0 for acceptance evidence")
    for field in ("battery_temperature_c_before", "battery_temperature_c_after"):
        value = _required_number(state, field, path)
        if not -20.0 <= value <= 100.0:
            raise ValidationError(f"{path}: {field} is outside a plausible range")
        validated[field] = value

    compilation_available = _required_boolean(state, "compilation_evidence_available", path)
    if not compilation_available:
        raise ValidationError(f"{path}: package compilation evidence is required for acceptance")
    compilation_filter = _required_text(state, "compilation_filter", path)
    compilation_reason = _required_text(state, "compilation_reason", path)
    if compilation_filter != "speed-profile":
        raise ValidationError(
            f"{path}: compilation filter must be exactly 'speed-profile', "
            f"observed {compilation_filter!r}"
        )
    if compilation_reason != "cmdline":
        raise ValidationError(
            f"{path}: compilation reason must be exactly 'cmdline', "
            f"observed {compilation_reason!r}"
        )
    validated["compilation_filter"] = compilation_filter
    validated["compilation_reason"] = compilation_reason
    validated["compilation_evidence_available"] = compilation_available

    condition_source = _required_text(state, "workload_condition_source", path)
    if condition_source != DECLARED_CONDITION_SOURCE:
        raise ValidationError(
            f"{path}: workload conditions must be labeled as operator declarations, "
            f"observed {condition_source!r}"
        )
    validated["workload_condition_source"] = condition_source

    allowed_conditions = {
        "declared_runtime_condition": ALLOWED_DECLARED_RUNTIME_CONDITIONS,
        "declared_download_condition": ALLOWED_DECLARED_DOWNLOAD_CONDITIONS,
        "declared_voice_condition": ALLOWED_DECLARED_VOICE_CONDITIONS,
    }
    for field, allowed in allowed_conditions.items():
        value = _required_text(state, field, path)
        if value not in allowed:
            raise ValidationError(f"{path}: unsupported {field} {value!r}")
        validated[field] = value
    return validated


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
    device_states: list[dict[str, Any]] = []
    sample_started_at: list[datetime] = []
    total_frames: list[int] = []
    for sample, path in zip(samples, paths):
        if sample.get("debuggable") is not False:
            raise ValidationError(f"{path}: debuggable must be exactly false")

        build_source = _required_text(sample, "build_source", path)
        if build_source not in ALLOWED_BUILD_SOURCES:
            raise ValidationError(f"{path}: unsupported build_source {build_source!r}")
        build_variant = _required_text(sample, "build_variant", path)
        native_packaged = sample.get("native_runtime_packaged")
        baseline_packaged = sample.get("baseline_profile_packaged")
        if build_source == "assembled-native-benchmark":
            benchmark_anchors += 1
            if (
                build_variant != "benchmark"
                or native_packaged is not True
                or baseline_packaged is not True
            ):
                raise ValidationError(
                    f"{path}: assembled benchmark must declare build_variant='benchmark' "
                    "and native_runtime_packaged=true and baseline_profile_packaged=true"
                )
        elif (
            build_variant != "unverified-nondebuggable"
            or native_packaged is not None
            or baseline_packaged is not None
        ):
            raise ValidationError(
                f"{path}: preinstalled sample must keep variant/native provenance unverified"
            )

        started_at = _required_text(sample, "started_at_utc", path)
        completed_at = _required_text(sample, "completed_at_utc", path)
        started_timestamp = _parse_timestamp(started_at, "started_at_utc", path)
        completed_timestamp = _parse_timestamp(completed_at, "completed_at_utc", path)
        if completed_timestamp < started_timestamp:
            raise ValidationError(f"{path}: completed_at_utc must not precede started_at_utc")
        if (completed_timestamp - started_timestamp).total_seconds() > 900:
            raise ValidationError(f"{path}: sample duration exceeds 15 minutes")
        sample_started_at.append(started_timestamp)
        total_frames.append(
            _required_positive_integer(
                sample,
                "total_frames",
                path,
                minimum=MINIMUM_FRAME_COUNT,
            )
        )
        device_states.append(
            _validate_device_state(_required_object(sample, "device_state", path), path)
        )

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

    if sample_started_at != sorted(sample_started_at) or len(set(sample_started_at)) != len(sample_started_at):
        raise ValidationError("sample started_at_utc values must be unique and chronological")

    for field in (*DEVICE_IDENTITY_FIELDS, *DEVICE_CONDITION_FIELDS):
        values = [state[field] for state in device_states]
        if len(set(values)) != 1:
            raise ValidationError(f"samples have mixed {field}: {values}")

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
        "baseline_profile_packaged": True,
        "build_identity": build_identity,
        "thresholds": METRIC_THRESHOLDS,
        "medians": medians,
        "checks": checks,
        "total_frames": total_frames,
        "device_state": {
            field: device_states[0][field]
            for field in (*DEVICE_IDENTITY_FIELDS, *DEVICE_CONDITION_FIELDS)
        },
        "sample_thermal_state": [
            {
                field: state[field]
                for field in (
                    "thermal_status_before",
                    "thermal_status_after",
                    "battery_temperature_c_before",
                    "battery_temperature_c_after",
                )
            }
            for state in device_states
        ],
        "summaries": [str(path) for path in paths],
    }


def _print_report(report: dict[str, Any]) -> None:
    print("Android Frame Threshold Evaluation")
    print("==================================")
    print(
        f"scenario={report['scenario']} serial={report['serial']} "
        f"samples={report['sample_count']} build={report['build_variant']}"
    )
    print(
        "workload_conditions="
        f"{report['device_state']['workload_condition_source']}"
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
