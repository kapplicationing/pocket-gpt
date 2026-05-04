from __future__ import annotations

import argparse
import json
import os
import sys
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from typing import Any, Sequence

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tools.maestro_android.adb_serial import (
    AdbDeviceRecord,
    is_mdns_tls_serial,
    is_network_serial,
    merge_mdns_aliases,
    parse_adb_devices_output,
    parse_adb_mdns_services_output,
    resolve_requested_serial,
)
from tools.maestro_android.common import (
    REPO_ROOT,
    MaestroAndroidError,
    format_command,
    print_step,
    run_subprocess,
)
from tools.maestro_android.config import MaestroAndroidConfig, load_config
from tools.maestro_android.reporting import find_bundle, open_bundle, print_bundle

try:
    import yaml  # type: ignore
except ModuleNotFoundError:  # pragma: no cover - validated by config load
    yaml = None


_MAESTRO_TRANSIENT_FAILURE_MARKERS = (
    "Unable to launch app",
    "TimeoutException",
    "timed out while waiting for FUNCTIONFS_BIND",
    "TcpForwarder",
    "UNAVAILABLE: io exception",
    "Connection refused",
    "Connection reset",
)

_MAESTRO_IPV4_JAVA_OPTION = "-Djava.net.preferIPv4Stack=true"

_PRODUCT_UI_FAILURE_MARKERS = (
    "Element not found",
    "Assertion",
    "No view found",
    "Flow Failed",
    "failed to execute step",
)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="maestro-android")
    parser.add_argument("--config", type=Path, default=None, help="override config file path")
    subparsers = parser.add_subparsers(dest="command", required=True)

    doctor = subparsers.add_parser("doctor", help="run environment diagnostics")
    doctor.add_argument("--json", action="store_true", dest="as_json", help="emit JSON when supported")

    subparsers.add_parser("devices", help="list adb devices")

    start_device = subparsers.add_parser("start-device", help="start an Android emulator AVD")
    start_device.add_argument("name", nargs="?", help="AVD name")
    start_device.add_argument("--boot-timeout-seconds", type=int, default=180)

    test = subparsers.add_parser("test", help="run one or more Maestro flows with Android bootstrap")
    test.add_argument("flows", nargs="*", help="flow paths to run")
    test.add_argument("--flows", dest="flow_csv", default="", help="comma-separated flow paths")
    test.add_argument("--include-tags", default="", help="comma-separated tag filter")
    test.add_argument("--exclude-tags", default="", help="comma-separated tag filter")
    test.add_argument("--device", default="", help="device serial")
    test.add_argument("--no-build", action="store_true")
    test.add_argument("--no-install", action="store_true")
    test.add_argument("--format", choices=("junit", "html", "json"), default="junit")
    test.add_argument("--clear-state", action="store_true", help="pm clear app before each flow")

    lane = subparsers.add_parser("lane", help="run a configured Maestro lane")
    lane.add_argument("name", help="lane name")
    lane.add_argument("--device", default="", help="device serial")
    lane.add_argument("args", nargs=argparse.REMAINDER, help="extra lane args")

    scoped = subparsers.add_parser("scoped", help="run the scoped repro loop")
    scoped.add_argument("--flow", required=True, help="tmp flow path")
    scoped.add_argument("--device", default="", help="device serial")
    scoped.add_argument("--no-build", action="store_true")
    scoped.add_argument("--no-install", action="store_true")
    scoped.add_argument("--pattern", default="", help="override crash signature regex")
    scoped.add_argument("--app-context", default="", help="override app context regex")
    scoped.add_argument("extra_args", nargs=argparse.REMAINDER, help="extra args after --")

    report = subparsers.add_parser("report", help="inspect latest report artifacts")
    report.add_argument("kind", choices=("journey", "screenshot-pack", "smoke", "raw", "lifecycle", "latest"))
    report.add_argument("--open", action="store_true", dest="open_files")

    trace = subparsers.add_parser("trace", help="inspect latest trace-capable artifact bundle")
    trace.add_argument("kind", choices=("journey", "smoke", "raw", "latest"), default="latest", nargs="?")
    trace.add_argument("--open", action="store_true", dest="open_files")

    merge = subparsers.add_parser("merge-reports", help="merge run manifests and JUnit outputs")
    merge.add_argument("inputs", nargs="+", help="run directories or run-manifest.json files")
    merge.add_argument("--out", type=Path, required=True, help="output directory")

    clean = subparsers.add_parser("clean", help="remove maestro-android scratch artifacts")
    clean.add_argument("--include-repo-artifacts", action="store_true")

    cloud = subparsers.add_parser("cloud", help="pass through to maestro cloud")
    cloud.add_argument("args", nargs=argparse.REMAINDER, help="arguments for maestro cloud")

    return parser


def _load_yaml(path: Path) -> dict[str, Any]:
    if yaml is None:
        raise MaestroAndroidError(
            "ENVIRONMENT_ERROR",
            "PyYAML is required. Run: python3 -m pip install -r tools/devctl/requirements.txt",
        )
    document = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(document, dict):
        raise MaestroAndroidError("CONFIG_ERROR", f"Invalid flow metadata in {path}")
    return document


def _parse_csv(raw: str) -> list[str]:
    return [token.strip() for token in raw.split(",") if token.strip()]


def _resolve_serial(explicit: str) -> str:
    requested = explicit.strip()
    if not requested:
        requested = (os.environ.get("ADB_SERIAL") or os.environ.get("ANDROID_SERIAL") or "").strip()

    devices = _list_devices()
    authorized = [device for device in devices if device["state"] == "device"]
    if requested:
        matched = _resolve_serial_record(requested, authorized)
        if matched is not None:
            resolved = matched["serial"]
            if resolved != requested:
                print_step(f"Resolved requested serial {requested} to adb transport {resolved}")
            return resolved
        if is_network_serial(requested):
            run_subprocess(["adb", "connect", requested], check=False, capture_output=True)
            matched = _resolve_serial_record(requested, _list_devices())
            if matched is not None and matched["state"] == "device":
                resolved = matched["serial"]
                if resolved != requested:
                    print_step(f"Resolved requested serial {requested} to adb transport {resolved}")
                return resolved
        return requested

    device_serials = [device["serial"] for device in authorized]
    if not device_serials:
        raise MaestroAndroidError("DEVICE_ERROR", "No connected adb device detected.")
    if len(device_serials) > 1:
        raise MaestroAndroidError("DEVICE_ERROR", "Multiple adb devices detected; pass --device.")
    return device_serials[0]


def _list_devices() -> list[dict[str, str]]:
    return [
        {
            "serial": device.serial,
            "state": device.state,
            "details": device.details,
            "aliases": list(device.aliases),
        }
        for device in _collect_devices()
    ]


def _collect_devices() -> list[AdbDeviceRecord]:
    completed = run_subprocess(["adb", "devices", "-l"], capture_output=True, check=False)
    devices = parse_adb_devices_output(completed.stdout or "")
    mdns = run_subprocess(["adb", "mdns", "services"], capture_output=True, check=False)
    return merge_mdns_aliases(devices, parse_adb_mdns_services_output(mdns.stdout or ""))


def _resolve_serial_record(requested: str, devices: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    records = [
        AdbDeviceRecord(
            serial=str(device["serial"]),
            state=str(device["state"]),
            details=str(device.get("details", "")),
            aliases=tuple(str(alias) for alias in device.get("aliases", [])),
        )
        for device in devices
    ]
    matched = resolve_requested_serial(requested, records)
    if matched is None:
        return None
    return {
        "serial": matched.serial,
        "state": matched.state,
        "details": matched.details,
        "aliases": list(matched.aliases),
    }


def _resolve_apk(config: MaestroAndroidConfig) -> Path:
    candidates = sorted(REPO_ROOT.glob(config.project.apk_glob))
    if not candidates:
        raise MaestroAndroidError("CONFIG_ERROR", f"No APK matched {config.project.apk_glob}")
    return candidates[0]


def _device_env(serial: str) -> dict[str, str]:
    env = dict(os.environ)
    env["ANDROID_SERIAL"] = serial
    env["ADB_SERIAL"] = serial
    return env


def _with_gradle_serial_flag(command: Sequence[str], serial: str) -> list[str]:
    command_list = list(command)
    if not command_list or command_list[0] not in {"./gradlew", "gradle"}:
        return command_list
    if any(arg.startswith("-Pandroid.injected.device.serial=") for arg in command_list):
        return command_list
    return [command_list[0], f"-Pandroid.injected.device.serial={serial}", *command_list[1:]]


def _is_tcpip_serial(serial: str) -> bool:
    return is_network_serial(serial)


def _run_maestro_transport_recovery(serial: str, *, env: dict[str, str]) -> None:
    run_subprocess(["adb", "-s", serial, "forward", "--remove-all"], check=False, capture_output=True, env=env)
    run_subprocess(["adb", "-s", serial, "shell", "am", "force-stop", "dev.mobile.maestro"], check=False, capture_output=True, env=env)
    run_subprocess(["adb", "-s", serial, "shell", "am", "force-stop", "dev.mobile.maestro.test"], check=False, capture_output=True, env=env)
    if _is_tcpip_serial(serial):
        run_subprocess(["adb", "disconnect", serial], check=False, capture_output=True, env=env)
        run_subprocess(["adb", "connect", serial], check=False, capture_output=True, env=env)
    run_subprocess(["adb", "-s", serial, "wait-for-device"], check=False, capture_output=True, env=env)
    run_subprocess(["adb", "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False, capture_output=True, env=env)
    run_subprocess(["adb", "-s", serial, "shell", "wm", "dismiss-keyguard"], check=False, capture_output=True, env=env)


def _is_transient_maestro_failure(output: str) -> bool:
    return any(marker in output for marker in _MAESTRO_TRANSIENT_FAILURE_MARKERS)


def _latest_maestro_log_text(debug_dir: Path) -> str:
    logs = sorted(debug_dir.rglob("maestro.log"))
    if not logs:
        return ""
    return logs[-1].read_text(encoding="utf-8", errors="replace")


def _maestro_run_env(env: dict[str, str]) -> dict[str, str]:
    run_env = dict(env)
    current = run_env.get("JAVA_TOOL_OPTIONS", "").strip()
    if _MAESTRO_IPV4_JAVA_OPTION not in current.split():
        run_env["JAVA_TOOL_OPTIONS"] = (
            f"{current} {_MAESTRO_IPV4_JAVA_OPTION}".strip() if current else _MAESTRO_IPV4_JAVA_OPTION
        )
    return run_env


def _clear_stale_maestro_processes(serial: str, env: dict[str, str]) -> None:
    current_pid = os.getpid()
    parent_pid = os.getppid()
    killed_pids: set[int] = set()
    patterns = [
        f"maestro.cli.AppKt --device {serial}",
        f"tools/maestro_android/main.py test --device {serial}",
    ]
    for pattern in patterns:
        listing = run_subprocess(["pgrep", "-af", pattern], check=False, capture_output=True, env=env)
        for line in (listing.stdout or "").splitlines():
            parts = line.strip().split(maxsplit=1)
            if not parts:
                continue
            pid = parts[0]
            if not pid.isdigit():
                continue
            pid_value = int(pid)
            if pid_value in {current_pid, parent_pid}:
                continue
            if pid_value in killed_pids:
                continue
            killed_pids.add(pid_value)
            run_subprocess(["kill", pid], check=False, capture_output=True, env=env)


def _run_maestro_test_with_retry(
    *,
    serial: str,
    flow: Path,
    debug_dir: Path,
    output_format: str,
    env: dict[str, str],
    app_id: str,
) -> tuple[subprocess.CompletedProcess[str], str]:
    attempt_outputs: list[str] = []
    result: subprocess.CompletedProcess[str] | None = None
    run_env = _maestro_run_env(env)
    for attempt in range(2):
        _clear_stale_maestro_processes(serial, run_env)
        run_subprocess(["adb", "-s", serial, "forward", "--remove-all"], check=False, capture_output=True, env=env)
        if attempt > 0:
            run_subprocess(["adb", "-s", serial, "shell", "am", "force-stop", app_id], check=False, capture_output=True, env=env)
        maestro_args = ["--reinstall-driver"] if attempt > 0 else []
        cmd = [
            "maestro",
            "--device",
            serial,
            "test",
            *maestro_args,
            str(flow),
            "--debug-output",
            str(debug_dir),
            "--format",
            output_format,
        ]
        result = run_subprocess(cmd, capture_output=True, check=False, cwd=debug_dir, env=run_env)
        combined = ((result.stdout or "") + "\n" + (result.stderr or "")).strip()
        maestro_log = _latest_maestro_log_text(debug_dir)
        transient_source = combined if _is_transient_maestro_failure(combined) else maestro_log
        attempt_block = [f"=== attempt {attempt + 1} ===", combined]
        if maestro_log:
            attempt_block.extend(["--- maestro.log ---", maestro_log])
        attempt_outputs.append("\n".join(part for part in attempt_block if part).rstrip() + "\n")
        if result.returncode == 0:
            break
        if attempt == 0 and _is_transient_maestro_failure(transient_source):
            print_step(f"Flow {flow.stem} hit transient Maestro launch failure; retrying once.")
            _run_maestro_transport_recovery(serial, env=env)
            time.sleep(1.5)
            continue
        break
    assert result is not None
    return result, "".join(attempt_outputs)


def _probe_adb_transport(serial: str, *, env: dict[str, str]) -> dict[str, Any]:
    get_state = run_subprocess(["adb", "-s", serial, "get-state"], capture_output=True, check=False, env=env)
    shell = run_subprocess(["adb", "-s", serial, "shell", "echo", "maestro-android-ok"], capture_output=True, check=False, env=env)
    get_state_output = (get_state.stdout or get_state.stderr or "").strip()
    shell_output = ((shell.stdout or "") + (shell.stderr or "")).strip()
    return {
        "get_state_returncode": get_state.returncode,
        "get_state_output": get_state_output,
        "shell_returncode": shell.returncode,
        "shell_output": shell_output,
        "transport_ok": get_state.returncode == 0 and get_state_output == "device" and shell.returncode == 0,
    }


def _classify_failure(
    *,
    returncode: int,
    output_text: str,
    transport: dict[str, Any],
) -> str:
    if returncode == 0:
        return "passed"
    if not transport.get("transport_ok", False):
        return "adb_transport"
    if _is_transient_maestro_failure(output_text):
        return "maestro_server_bootstrap"
    lowered = output_text.lower()
    if any(marker.lower() in lowered for marker in _PRODUCT_UI_FAILURE_MARKERS):
        return "product_ui_or_flow"
    return "product_ui_or_flow"


def _failure_suggestion(phase: str) -> str:
    return {
        "passed": "No action needed.",
        "adb_transport": "Re-establish the adb transport first. For wireless devices, prefer a direct ip:port serial and verify `adb -s <serial> get-state` before rerunning Maestro.",
        "maestro_server_bootstrap": "The adb transport survived, but Maestro failed before product evidence. Retry after transport recovery or inspect the Maestro debug bundle for gRPC/bootstrap faults.",
        "product_ui_or_flow": "The transport was healthy and Maestro reached product steps. Treat this as a UI/flow issue until proven otherwise.",
    }[phase]


def _is_app_installed(serial: str, app_id: str) -> bool:
    completed = run_subprocess(
        ["adb", "-s", serial, "shell", "pm", "path", app_id],
        capture_output=True,
        check=False,
    )
    return completed.returncode == 0 and bool((completed.stdout or "").strip())


def _ensure_app_installed(serial: str, app_id: str, *, allow_install_hint: bool) -> None:
    if _is_app_installed(serial, app_id):
        return
    suffix = " Rerun without --no-install or install the app first." if allow_install_hint else ""
    raise MaestroAndroidError("DEVICE_ERROR", f"App {app_id} is not installed on {serial}.{suffix}")


def _normalize_artifact_root(base_root: Path, serial: str, label: str) -> Path:
    date_value = datetime.now().strftime("%Y-%m-%d")
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return base_root / date_value / serial / label / stamp


def _flow_metadata(path: Path) -> dict[str, Any]:
    return _load_yaml(path)


def _discover_flow_paths(config: MaestroAndroidConfig) -> list[Path]:
    paths: list[Path] = []
    for root in config.flows.roots:
        candidate_root = REPO_ROOT / root
        if not candidate_root.exists():
            continue
        paths.extend(sorted(candidate_root.rglob("*.yaml")))
    return paths


def _select_flows(
    config: MaestroAndroidConfig,
    *,
    explicit_flows: Sequence[str],
    include_tags: list[str],
    exclude_tags: list[str],
) -> list[Path]:
    if explicit_flows:
        resolved: list[Path] = []
        for flow in explicit_flows:
            path = (REPO_ROOT / flow).resolve() if not Path(flow).is_absolute() else Path(flow)
            if not path.exists():
                raise MaestroAndroidError("CONFIG_ERROR", f"Flow does not exist: {path}")
            resolved.append(path)
        return resolved
    selected: list[Path] = []
    for path in _discover_flow_paths(config):
        metadata = _flow_metadata(path)
        tags = {str(value).strip() for value in metadata.get("tags", []) if str(value).strip()}
        if include_tags and not tags.issuperset(include_tags) and not set(include_tags).issubset(tags):
            continue
        if exclude_tags and tags.intersection(exclude_tags):
            continue
        selected.append(path)
    if not selected:
        raise MaestroAndroidError("CONFIG_ERROR", "No flows matched the requested selection.")
    return selected


def _capture_logcat(serial: str, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    completed = run_subprocess(["adb", "-s", serial, "logcat", "-d"], capture_output=True, check=False)
    output_path.write_text(completed.stdout or "", encoding="utf-8")


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def _write_trace(path: Path, manifest: dict[str, Any]) -> None:
    steps = []
    for flow in manifest.get("flows", []):
        steps.append(
            {
                "flow": flow["flow"],
                "status": flow["status"],
                "junit": flow.get("junit"),
                "logcat": flow.get("logcat"),
                "debug_output": flow.get("debug_output"),
            }
        )
    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device": manifest.get("device"),
        "flows": steps,
    }
    _write_json(path, payload)


def _run_test(parsed: argparse.Namespace, config: MaestroAndroidConfig) -> None:
    requested_serial = parsed.device.strip()
    serial = _resolve_serial(requested_serial)
    device_env = _device_env(serial)
    explicit_flows = list(parsed.flows) + _parse_csv(parsed.flow_csv)
    include_tags = _parse_csv(parsed.include_tags)
    exclude_tags = _parse_csv(parsed.exclude_tags)
    flows = _select_flows(config, explicit_flows=explicit_flows, include_tags=include_tags, exclude_tags=exclude_tags)

    if not parsed.no_build:
        run_subprocess(config.project.build_command)
    if not parsed.no_install:
        run_subprocess(_with_gradle_serial_flag(config.project.install_command, serial), env=device_env)
    _ensure_app_installed(serial, config.project.app_id, allow_install_hint=parsed.no_install)
    apk_path = _resolve_apk(config)

    artifact_root = _normalize_artifact_root(REPO_ROOT / config.artifacts.scratch_root, serial, "raw")
    artifact_root.mkdir(parents=True, exist_ok=True)

    flow_results: list[dict[str, Any]] = []
    for flow in flows:
        flow_dir = artifact_root / "flows" / flow.stem
        debug_dir = flow_dir / "maestro-debug"
        debug_dir.mkdir(parents=True, exist_ok=True)
        run_subprocess(["adb", "-s", serial, "logcat", "-c"], check=False)
        if parsed.clear_state:
            run_subprocess(["adb", "-s", serial, "shell", "pm", "clear", config.project.app_id], check=False)
        completed, attempt_output = _run_maestro_test_with_retry(
            serial=serial,
            flow=flow,
            debug_dir=debug_dir,
            output_format=parsed.format,
            env=device_env,
            app_id=config.project.app_id,
        )

        junit_path = flow_dir / "junit.xml"
        stderr_path = flow_dir / "maestro-stderr.log"
        stdout_path = flow_dir / "maestro-stdout.log"
        output_path = flow_dir / "maestro-output.txt"
        diagnostics_path = flow_dir / "diagnostics.json"
        output_path.write_text(attempt_output, encoding="utf-8")
        if parsed.format == "junit":
            junit_path.write_text(completed.stdout or "", encoding="utf-8")
        else:
            stdout_path.write_text(completed.stdout or "", encoding="utf-8")
        stderr_path.write_text(completed.stderr or "", encoding="utf-8")
        logcat_path = flow_dir / "logcat.txt"
        _capture_logcat(serial, logcat_path)
        transport = _probe_adb_transport(serial, env=device_env)
        failure_output = "\n".join(
            part for part in [attempt_output, completed.stdout or "", completed.stderr or ""] if part
        )
        failure_phase = _classify_failure(
            returncode=completed.returncode,
            output_text=failure_output,
            transport=transport,
        )
        diagnostics = {
            "requested_serial": requested_serial or None,
            "resolved_serial": serial,
            "failure_phase": failure_phase,
            "returncode": completed.returncode,
            "transport": transport,
            "suggestion": _failure_suggestion(failure_phase),
        }
        _write_json(diagnostics_path, diagnostics)
        if completed.returncode != 0:
            print_step(
                f"Flow {flow.stem} failed as {failure_phase}; diagnostics: "
                f"{diagnostics_path.relative_to(artifact_root)}"
            )

        flow_results.append(
            {
                "flow": str(flow.relative_to(REPO_ROOT)) if flow.is_relative_to(REPO_ROOT) else str(flow),
                "status": "passed" if completed.returncode == 0 else "failed",
                "returncode": completed.returncode,
                "failure_phase": failure_phase,
                "junit": str(junit_path.relative_to(artifact_root)) if junit_path.exists() else None,
                "stderr": str(stderr_path.relative_to(artifact_root)),
                "stdout": str(stdout_path.relative_to(artifact_root)) if stdout_path.exists() else None,
                "logcat": str(logcat_path.relative_to(artifact_root)),
                "debug_output": str(debug_dir.relative_to(artifact_root)),
                "diagnostics": str(diagnostics_path.relative_to(artifact_root)),
            }
        )

    manifest = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device": serial,
        "apk": str(apk_path),
        "flows": flow_results,
    }
    _write_json(artifact_root / "run-manifest.json", manifest)
    _write_trace(artifact_root / "trace.json", manifest)
    print_step(f"Raw Maestro test artifacts: {artifact_root}")

    failed = [flow for flow in flow_results if flow["status"] != "passed"]
    if failed:
        names = ", ".join(f'{flow["flow"]} [{flow["failure_phase"]}]' for flow in failed)
        raise MaestroAndroidError("DEVICE_ERROR", f"Flow run failed: {names}")


def _run_lane(parsed: argparse.Namespace, config: MaestroAndroidConfig) -> None:
    lane = config.lanes.get(parsed.name)
    if lane is None:
        raise MaestroAndroidError("CONFIG_ERROR", f"Unknown lane '{parsed.name}'")
    extra_args = list(parsed.args)
    if extra_args and extra_args[0] == "--":
        extra_args = extra_args[1:]
    if lane.kind == "devctl-lane":
        if not lane.name:
            raise MaestroAndroidError("CONFIG_ERROR", f"Lane '{parsed.name}' is missing a devctl lane name")
        command = ["python3", "tools/devctl/main.py", "lane", lane.name, *extra_args]
    else:
        command = [*lane.argv, *extra_args]
    resolved_device = _resolve_serial(parsed.device) if parsed.device else ""
    lane_env = _device_env(resolved_device) if resolved_device else None
    run_subprocess(command, env=lane_env)


def _normalize_lane_args(parsed: argparse.Namespace) -> None:
    if parsed.command != "lane":
        return
    args = list(parsed.args)
    normalized: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--device":
            if index + 1 >= len(args):
                raise MaestroAndroidError("CONFIG_ERROR", "lane --device requires a serial value")
            if not parsed.device:
                parsed.device = args[index + 1]
            index += 2
            continue
        normalized.append(token)
        index += 1
    parsed.args = normalized


def _validate_scoped_flow(flow_path: Path, config: MaestroAndroidConfig) -> None:
    if not flow_path.exists():
        raise MaestroAndroidError("CONFIG_ERROR", f"Scoped flow does not exist: {flow_path}")
    if config.scoped.require_tmp_flow:
        try:
            rel = flow_path.relative_to(REPO_ROOT)
        except ValueError:
            rel = flow_path
        if not str(rel).startswith("tmp/"):
            raise MaestroAndroidError("CONFIG_ERROR", "Scoped flows must live under tmp/")
    if not config.scoped.require_title_description_comments:
        return
    lines = flow_path.read_text(encoding="utf-8").splitlines()
    if len(lines) < 2:
        raise MaestroAndroidError("CONFIG_ERROR", f"Scoped flow must start with title/description comments: {flow_path}")
    first = lines[0].strip().lower()
    second = lines[1].strip().lower()
    if not first.startswith("#") or "title" not in first:
        raise MaestroAndroidError("CONFIG_ERROR", f"First line must be a title comment in {flow_path}")
    if not second.startswith("#") or "description" not in second:
        raise MaestroAndroidError("CONFIG_ERROR", f"Second line must be a description comment in {flow_path}")


def _run_scoped(parsed: argparse.Namespace, config: MaestroAndroidConfig) -> None:
    flow_path = (REPO_ROOT / parsed.flow).resolve() if not Path(parsed.flow).is_absolute() else Path(parsed.flow)
    _validate_scoped_flow(flow_path, config)

    command = list(config.scoped.command_prefix)
    command.extend(["--flow", str(flow_path)])
    if parsed.device:
        command.extend(["--serial", _resolve_serial(parsed.device)])
    if parsed.no_build:
        command.append("--no-build")
    if parsed.no_install:
        command.append("--no-install")
    if parsed.pattern:
        command.extend(["--pattern", parsed.pattern])
    if parsed.app_context:
        command.extend(["--app-context", parsed.app_context])
    extra_args = list(parsed.extra_args)
    if extra_args and extra_args[0] == "--":
        command.append("--")
        command.extend(extra_args[1:])
    run_subprocess(command)


def _run_report(parsed: argparse.Namespace, config: MaestroAndroidConfig) -> None:
    bundle = find_bundle(parsed.kind, config=config, repo_root=REPO_ROOT)
    print_bundle(bundle)
    if parsed.open_files:
        open_bundle(bundle)


def _run_trace(parsed: argparse.Namespace, config: MaestroAndroidConfig) -> None:
    bundle = find_bundle(parsed.kind, config=config, repo_root=REPO_ROOT)
    print(f"{bundle.kind} trace bundle:")
    print(f"  Artifact root: {bundle.artifact_root}")
    trace_path = bundle.artifact_root / "trace.json"
    if trace_path.exists():
        print(f"  {trace_path}")
        if parsed.open_files:
            open_bundle(type(bundle)(kind=bundle.kind, artifact_root=bundle.artifact_root, report_files=(trace_path,)))
        return
    debug_dirs = sorted(path for path in bundle.artifact_root.rglob("maestro-debug") if path.is_dir())
    for path in debug_dirs:
        print(f"  {path}")


def _merge_junit(inputs: list[Path], output_path: Path) -> None:
    root = ET.Element("testsuites")
    for path in inputs:
        parsed = ET.parse(path)
        current_root = parsed.getroot()
        if current_root.tag == "testsuite":
            root.append(current_root)
        else:
            for child in list(current_root):
                root.append(child)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(output_path, encoding="utf-8", xml_declaration=True)


def _run_merge_reports(parsed: argparse.Namespace) -> None:
    manifests: list[dict[str, Any]] = []
    junit_paths: list[Path] = []
    for raw_input in parsed.inputs:
        candidate = Path(raw_input)
        manifest_path = candidate if candidate.name == "run-manifest.json" else candidate / "run-manifest.json"
        if not manifest_path.exists():
            raise MaestroAndroidError("CONFIG_ERROR", f"Missing run-manifest.json in {raw_input}")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifests.append(manifest)
        root_dir = manifest_path.parent
        for flow in manifest.get("flows", []):
            junit = flow.get("junit")
            if junit:
                junit_path = root_dir / junit
                if junit_path.exists():
                    junit_paths.append(junit_path)
    out_dir = parsed.out
    out_dir.mkdir(parents=True, exist_ok=True)
    merged_manifest = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "run_count": len(manifests),
        "runs": manifests,
    }
    _write_json(out_dir / "merged-run-manifest.json", merged_manifest)
    if junit_paths:
        _merge_junit(junit_paths, out_dir / "merged-junit.xml")
    print_step(f"Merged reports written to {out_dir}")


def _run_clean(parsed: argparse.Namespace, config: MaestroAndroidConfig) -> None:
    roots = [REPO_ROOT / config.artifacts.clean_roots[0]]
    if parsed.include_repo_artifacts:
        roots = [REPO_ROOT / root for root in config.artifacts.clean_roots]
    for root in roots:
        if root.exists():
            print_step(f"Removing {root}")
            shutil.rmtree(root)


def _run_cloud(parsed: argparse.Namespace) -> None:
    extra_args = list(parsed.args)
    if extra_args and extra_args[0] == "--":
        extra_args = extra_args[1:]
    run_subprocess(["maestro", "cloud", *extra_args])


def _run_doctor(parsed: argparse.Namespace, config: MaestroAndroidConfig) -> int:
    command = list(config.doctor.delegate_command)
    if parsed.as_json:
        command.append("--json")
    completed = run_subprocess(command, check=False)
    return completed.returncode


def _run_devices() -> None:
    devices = _list_devices()
    if not devices:
        print("No adb devices detected.")
        return
    for device in devices:
        detail = f" {device['details']}" if device["details"] else ""
        aliases = [alias for alias in device.get("aliases", []) if alias != device["serial"]]
        alias_detail = f" aliases={','.join(sorted(aliases))}" if aliases else ""
        print(f"{device['serial']} [{device['state']}] {detail}{alias_detail}".rstrip())


def _run_start_device(parsed: argparse.Namespace) -> None:
    emulator_bin = shutil.which("emulator")
    if emulator_bin is None:
        raise MaestroAndroidError("ENVIRONMENT_ERROR", "Android emulator binary not found in PATH.")
    avds = run_subprocess([emulator_bin, "-list-avds"], capture_output=True, check=False)
    names = [line.strip() for line in (avds.stdout or "").splitlines() if line.strip()]
    if not names:
        raise MaestroAndroidError("DEVICE_ERROR", "No AVDs are available.")
    avd_name = parsed.name or (names[0] if len(names) == 1 else "")
    if not avd_name:
        raise MaestroAndroidError("CONFIG_ERROR", "Multiple AVDs detected; pass an AVD name.")
    print_step(f"Starting AVD {avd_name}")
    subprocess.Popen([emulator_bin, "-avd", avd_name], cwd=str(REPO_ROOT))
    timeout = parsed.boot_timeout_seconds
    run_subprocess(["adb", "wait-for-device"], timeout_seconds=timeout)


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    parsed = parser.parse_args(list(argv) if argv is not None else None)
    _normalize_lane_args(parsed)

    try:
        config = load_config(explicit_path=parsed.config)
        if parsed.command == "doctor":
            return _run_doctor(parsed, config)
        if parsed.command == "devices":
            _run_devices()
            return 0
        if parsed.command == "start-device":
            _run_start_device(parsed)
            return 0
        if parsed.command == "test":
            _run_test(parsed, config)
            return 0
        if parsed.command == "lane":
            _run_lane(parsed, config)
            return 0
        if parsed.command == "scoped":
            _run_scoped(parsed, config)
            return 0
        if parsed.command == "report":
            _run_report(parsed, config)
            return 0
        if parsed.command == "trace":
            _run_trace(parsed, config)
            return 0
        if parsed.command == "merge-reports":
            _run_merge_reports(parsed)
            return 0
        if parsed.command == "clean":
            _run_clean(parsed, config)
            return 0
        if parsed.command == "cloud":
            _run_cloud(parsed)
            return 0
        raise MaestroAndroidError("CONFIG_ERROR", f"Unknown command '{parsed.command}'")
    except MaestroAndroidError as exc:
        print(f"{exc.code}: {exc.message}")
        return 1
