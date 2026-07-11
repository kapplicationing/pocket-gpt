#!/usr/bin/env python3
"""Build one truthful Android frame-sample summary from raw device artifacts."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Sequence


class MetadataError(ValueError):
    """Raised when required device metadata cannot be recovered."""


def _read(artifact_dir: Path, name: str) -> str:
    path = artifact_dir / name
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise MetadataError(f"cannot read {path}: {error}") from error


def _property(source: str, key: str) -> str:
    match = re.search(rf"^\[{re.escape(key)}\]: \[(.*)]$", source, re.MULTILINE)
    if not match or not match.group(1).strip():
        raise MetadataError(f"device property {key} is missing")
    return match.group(1).strip()


def _thermal_status(source: str, label: str) -> int:
    patterns = (
        r"(?i)thermal status\s*:\s*([0-6])",
        r"(?i)status\s*=\s*([0-6])",
        r"(?m)^\s*([0-6])\s*$",
    )
    for pattern in patterns:
        match = re.search(pattern, source)
        if match:
            return int(match.group(1))
    raise MetadataError(f"could not parse {label} thermal status")


def _battery_temperature(source: str, label: str) -> float:
    match = re.search(r"(?m)^\s*temperature:\s*(-?\d+)\s*$", source)
    if not match:
        raise MetadataError(f"could not parse {label} battery temperature")
    return int(match.group(1)) / 10.0


def _refresh_rate(display_source: str, settings_source: str) -> tuple[float | None, str]:
    display_patterns = (
        r"(?i)mActiveSfDisplayMode[^\n]*?(?:refreshRate|fps)=([0-9]+(?:\.[0-9]+)?)",
        r"(?i)activeMode[^\n]*?(?:refreshRate|fps)[= ]+([0-9]+(?:\.[0-9]+)?)",
        r"(?i)mRefreshRate=([0-9]+(?:\.[0-9]+)?)",
        r"(?i)refreshRate[= ]+([0-9]+(?:\.[0-9]+)?)",
    )
    for pattern in display_patterns:
        match = re.search(pattern, display_source)
        if match and float(match.group(1)) > 0:
            return float(match.group(1)), "dumpsys-display-active-mode"
    match = re.search(r"(?m)^peak_refresh_rate=([0-9]+(?:\.[0-9]+)?)\s*$", settings_source)
    if match and float(match.group(1)) > 0:
        return float(match.group(1)), "system-peak-refresh-setting"
    return None, "unavailable"


def _compilation_state(package_source: str) -> tuple[str, str, bool]:
    match = re.search(
        r"\[status=([^]]+)](?:\s*\[reason=([^]]+)])?",
        package_source,
    )
    if not match:
        return "unavailable", "unavailable", False
    return match.group(1).strip(), (match.group(2) or "unspecified").strip(), True


def collect_device_state(
    artifact_dir: Path,
    *,
    runtime_condition: str,
    download_condition: str,
    voice_condition: str,
) -> dict[str, Any]:
    artifact_dir = Path(artifact_dir)
    properties = _read(artifact_dir, "device-properties.txt")
    refresh_rate, refresh_source = _refresh_rate(
        _read(artifact_dir, "display-before.txt"),
        _read(artifact_dir, "refresh-settings-before.txt"),
    )
    compilation_filter, compilation_reason, compilation_available = _compilation_state(
        _read(artifact_dir, "package-dump.txt")
    )
    return {
        "manufacturer": _property(properties, "ro.product.manufacturer"),
        "model": _property(properties, "ro.product.model"),
        "android_release": _property(properties, "ro.build.version.release"),
        "api_level": int(_property(properties, "ro.build.version.sdk")),
        "refresh_rate_hz": refresh_rate,
        "refresh_rate_source": refresh_source,
        "refresh_rate_evidence_available": refresh_rate is not None,
        "thermal_status_before": _thermal_status(
            _read(artifact_dir, "thermal-before.txt"), "before"
        ),
        "thermal_status_after": _thermal_status(
            _read(artifact_dir, "thermal-after.txt"), "after"
        ),
        "battery_temperature_c_before": _battery_temperature(
            _read(artifact_dir, "battery-before.txt"), "before"
        ),
        "battery_temperature_c_after": _battery_temperature(
            _read(artifact_dir, "battery-after.txt"), "after"
        ),
        "compilation_filter": compilation_filter,
        "compilation_reason": compilation_reason,
        "compilation_evidence_available": compilation_available,
        "runtime_condition": runtime_condition,
        "download_condition": download_condition,
        "voice_condition": voice_condition,
    }


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    for field in (
        "scenario",
        "serial",
        "package",
        "build-source",
        "build-variant",
        "version-code",
        "version-name",
        "last-update-time",
        "installed-apk-path",
        "started-at-utc",
        "completed-at-utc",
        "runtime-condition",
        "download-condition",
        "voice-condition",
    ):
        parser.add_argument(f"--{field}", required=True)
    parser.add_argument("--native-runtime-packaged", choices=("true", "null"), required=True)
    parser.add_argument("--debuggable", choices=("true", "false"), required=True)
    parser.add_argument("--total-frames", required=True, type=int)
    parser.add_argument("--janky-pct", required=True, type=float)
    parser.add_argument("--p50-ms", required=True, type=float)
    parser.add_argument("--p90-ms", required=True, type=float)
    parser.add_argument("--p99-ms", required=True, type=float)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        device_state = collect_device_state(
            args.artifact_dir,
            runtime_condition=args.runtime_condition,
            download_condition=args.download_condition,
            voice_condition=args.voice_condition,
        )
    except (MetadataError, ValueError) as error:
        print(f"Metadata error: {error}", file=sys.stderr)
        return 1
    payload = {
        "scenario": args.scenario,
        "serial": args.serial,
        "package": args.package,
        "build_source": args.build_source,
        "build_variant": args.build_variant,
        "native_runtime_packaged": True if args.native_runtime_packaged == "true" else None,
        "debuggable": args.debuggable == "true",
        "version_code": args.version_code,
        "version_name": args.version_name,
        "last_update_time": args.last_update_time,
        "installed_apk_path": args.installed_apk_path,
        "started_at_utc": args.started_at_utc,
        "completed_at_utc": args.completed_at_utc,
        "total_frames": args.total_frames,
        "janky_pct": args.janky_pct,
        "p50_ms": args.p50_ms,
        "p90_ms": args.p90_ms,
        "p99_ms": args.p99_ms,
        "device_state": device_state,
        "artifact_dir": str(args.artifact_dir.resolve()),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
