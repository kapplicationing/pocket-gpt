from __future__ import annotations

import argparse
import csv
import fnmatch
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, Mapping, MutableMapping, Sequence

try:
    import fcntl
except ImportError:  # pragma: no cover - fcntl is unavailable on non-POSIX platforms.
    fcntl = None

try:
    import yaml  # type: ignore
except ModuleNotFoundError:  # pragma: no cover - dependency guard is handled at runtime.
    yaml = None

from tools.devctl.runtime_log_signals import write_runtime_log_signal_reports
from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT, format_command, print_step, run_subprocess

if TYPE_CHECKING:
    from tools.devctl.config_models import DevctlConfigs
else:
    DevctlConfigs = Any


@dataclass
class RuntimeContext:
    repo_root: Path
    configs: DevctlConfigs
    env: MutableMapping[str, str]
    run: Callable[..., subprocess.CompletedProcess[str]]


@dataclass
class DeviceLaneArgs:
    runs: int
    label: str
    framework: str
    framework_explicit: bool
    scenario_command: list[str]


@dataclass
class MaestroLaneArgs:
    flows: list[str]
    include_tags: list[str]
    exclude_tags: list[str]


@dataclass
class RealRuntimePreparedEnv:
    serial: str
    model_device_paths_by_id: dict[str, str]
    model_host_paths_by_id: dict[str, str]
    instrumentation_runner: str | None = None


@dataclass
class JourneyStepResult:
    name: str
    status: str
    duration_seconds: float
    details: str | None = None
    screenshots: list[str] | None = None
    failure_signature: str | None = None
    logcat: str | None = None
    phase: str | None = None
    elapsed_ms: int | None = None
    runtime_status: str | None = None
    backend: str | None = None
    active_model_id: str | None = None
    placeholder_visible: bool | None = None
    response_visible: bool | None = None
    response_role: str | None = None
    response_non_empty: bool | None = None
    first_token_seen: bool | None = None
    request_id: str | None = None
    finish_reason: str | None = None
    terminal_event_seen: bool | None = None
    first_token_ms: int | None = None
    completion_ms: int | None = None
    mode: str | None = None
    first_session_stage: str | None = None
    advanced_unlocked: bool | None = None
    first_answer_completed: bool | None = None
    follow_up_completed: bool | None = None


@dataclass
class SendCaptureSnapshot:
    second: int
    screenshot: str | None
    window_dump: str | None
    chat_state_snapshot: str | None
    runtime_status: str | None
    backend: str | None
    active_model_id: str | None
    placeholder_visible: bool
    runtime_error_visible: bool
    timeout_message_visible: bool
    streaming_text_visible: bool
    response_visible: bool
    response_role: str | None
    response_non_empty: bool
    first_token_seen: bool
    request_id: str | None
    finish_reason: str | None
    terminal_event_seen: bool
    first_session_stage: str | None = None
    advanced_unlocked: bool | None = None
    first_answer_completed: bool | None = None
    follow_up_completed: bool | None = None


@dataclass(frozen=True)
class ScreenshotInventoryItem:
    id: str
    filename: str
    candidates: tuple[str, ...]


def _now_stamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


_DEFAULT_HEALTH_APP_PACKAGE = "com.pocketagent.android"
_DEFAULT_HEALTH_TEST_PACKAGE = "com.pocketagent.android.test"
_REMOTE_DIR_RETRY_ATTEMPTS = 3
_REMOTE_DIR_RETRY_BACKOFF_SECONDS = 0.5
_SEND_CAPTURE_READY_GRACE_SECONDS = 5
_SEND_CAPTURE_POST_TERMINAL_GRACE_SECONDS = 30
_MODEL_SYNC_MANIFEST_FILE = "model-sync-v1.json"
_MODEL_SYNC_MANIFEST_SCHEMA = "model-sync-v1"
_FORCE_MODEL_SYNC_ENV = "POCKETGPT_FORCE_MODEL_SYNC"
_SCREENSHOT_INVENTORY_SCHEMA = "ui-screenshot-inventory-v1"
_SCREENSHOT_INVENTORY_PATH = REPO_ROOT / "tests/ui-screenshots/inventory.yaml"
_SCREENSHOT_REFERENCE_DIR = REPO_ROOT / "tests/ui-screenshots/reference/sm-a515f-android13"
_SCREENSHOT_REPORT_SCHEMA = "ui-screenshot-inventory-report-v2"
_QA13_UNKNOWN_VALUE = "unknown"
_MAESTRO_TRANSIENT_FAILURE_MARKERS = (
    "Unable to launch app",
    "TimeoutException",
    "timed out while waiting for FUNCTIONFS_BIND",
    "TcpForwarder",
)
_SCREENSHOT_PACK_HARNESS_NOISE_MARKERS = (
    "No compose hierarchies found in the app",
    "Command timed out after",
)
_SCREENSHOT_PACK_BASE_TEST_CLASS = "com.pocketagent.android.MainActivityUiSmokeTest"
_SCREENSHOT_PACK_DETERMINISTIC_METHODS: tuple[str, ...] = (
    "onboardingFlowCanProgressAndComplete",
    "launchShowsComposerAndOfflineIndicator",
    "sendMessageShowsUserAndAssistantBubbles",
    "sessionDrawerOpensFromTopBar",
    "privacySheetOpensWithExpectedCopy",
    "toolAndDiagnosticsActionsRenderResults",
    "advancedControlsAreVisibleWithoutFollowUp",
    "runtimeLoadingAndRuntimeErrorStatesRenderExpectedRecovery",
)

_SERIALIZED_GRADLE_INSTALLS: dict[str, tuple[str, str, bool]] = {
    ":apps:mobile-android:installDebug": (
        ":apps:mobile-android:assembleDebug",
        "apps/mobile-android/build/outputs/apk/debug/mobile-android-debug.apk",
        False,
    ),
    ":apps:mobile-android:installDebugAndroidTest": (
        ":apps:mobile-android:assembleDebugAndroidTest",
        "apps/mobile-android/build/outputs/apk/androidTest/debug/mobile-android-debug-androidTest.apk",
        True,
    ),
}


def _append_native_build_flag(
    command: Sequence[str],
    *,
    env: Mapping[str, str] | None = None,
) -> list[str]:
    if not command:
        return list(command)
    first = command[0]
    if first not in {"./gradlew", "gradle"}:
        return list(command)
    native_flag = "-Ppocketgpt.enableNativeBuild=true"
    serial_flag = None
    source_env: Mapping[str, str] = env if env is not None else os.environ
    serial = source_env.get("ADB_SERIAL") or source_env.get("ANDROID_SERIAL")
    if serial and not any(arg.startswith("-Pandroid.injected.device.serial=") for arg in command):
        serial_flag = f"-Pandroid.injected.device.serial={serial}"

    if any(arg.startswith("-Ppocketgpt.enableNativeBuild=") for arg in command):
        if serial_flag is None:
            return list(command)
        return [command[0], serial_flag, *command[1:]]

    if serial_flag is None:
        return [command[0], native_flag, *command[1:]]
    return [command[0], native_flag, serial_flag, *command[1:]]


def _instrumentation_args_from_model_paths(model_paths_by_id: Mapping[str, str]) -> dict[str, str]:
    arg_map = {
        "qwen3.5-0.8b-q4": "stage2_model_0_8b_path",
        "qwen3-1.7b-q4_k_m": "stage2_model_1_7b_path",
        "bonsai-8b-q1_0_g128": "stage2_model_bonsai_path",
    }
    runner_args: dict[str, str] = {}
    for model_id, arg_key in arg_map.items():
        value = model_paths_by_id.get(model_id)
        if value:
            runner_args[arg_key] = value
    return runner_args


def _append_gradle_instrumentation_args(command: Sequence[str], runner_args: Mapping[str, str]) -> list[str]:
    if not command or command[0] not in {"./gradlew", "gradle"}:
        return list(command)
    if not runner_args:
        return list(command)
    appended = list(command)
    for key, value in runner_args.items():
        appended.append(f"-Pandroid.testInstrumentationRunnerArguments.{key}={value}")
    return appended


def _run_serialized_gradle_install_step(
    *,
    context: RuntimeContext,
    command: Sequence[str],
    serial: str,
    env: Mapping[str, str],
) -> bool:
    if not command or command[0] not in {"./gradlew", "gradle"}:
        return False

    gradle_tasks = [token for token in command[1:] if token.startswith(":")]
    if not gradle_tasks:
        return False

    if any(task not in _SERIALIZED_GRADLE_INSTALLS for task in gradle_tasks):
        return False

    gradle_flags = [token for token in command[1:] if not token.startswith(":")]
    for install_task in gradle_tasks:
        assemble_task, apk_rel_path, requires_test_install = _SERIALIZED_GRADLE_INSTALLS[install_task]
        context.run(
            [command[0], *gradle_flags, assemble_task],
            check=True,
            env=env,
        )

        apk_path = Path(apk_rel_path)
        if not apk_path.is_absolute():
            apk_path = REPO_ROOT / apk_path
        apk_path = apk_path.resolve()
        if not apk_path.exists():
            raise DevctlError(
                "DEVICE_ERROR",
                f"Expected APK output for {install_task} was not found: {apk_path}",
            )

        adb_install = ["adb", "-s", serial, "install", "-r"]
        if requires_test_install:
            adb_install.append("-t")
        adb_install.append(str(apk_path))
        install_result = context.run(
            adb_install,
            check=False,
            capture_output=True,
            env=env,
        )
        if install_result.returncode != 0:
            stdout = (install_result.stdout or "").strip()
            stderr = (install_result.stderr or "").strip()
            detail = "\n".join(part for part in [stdout, stderr] if part).strip()
            if not detail:
                detail = "adb install failed"
            raise DevctlError(
                "DEVICE_ERROR",
                f"Failed to install {apk_path} to {serial} for task {install_task}.\n{detail}",
            )

    return True


def _sanitize_file_token(name: str) -> str:
    return re.sub(r"[^a-zA-Z0-9._-]+", "-", name).strip("-") or "artifact"


_DEVICE_LOCKS_DIR = REPO_ROOT / "scripts/benchmarks/device-env/locks"
_HELD_DEVICE_LOCKS: dict[str, tuple[Path, Any]] = {}


def _device_lock_path(serial: str) -> Path:
    return _DEVICE_LOCKS_DIR / f"{_sanitize_file_token(serial)}.lock"


def _write_device_lock_metadata(lock_handle: Any, *, serial: str, owner: str) -> None:
    lock_handle.seek(0)
    lock_handle.truncate(0)
    lock_handle.write(
        "\n".join(
            [
                f"serial={serial}",
                f"owner={owner}",
                f"pid={os.getpid()}",
                f"started={datetime.now().isoformat(timespec='seconds')}",
            ]
        )
        + "\n"
    )
    lock_handle.flush()


@contextmanager
def _device_lock(serial: str, *, owner: str, timeout_seconds: int = 120) -> Any:
    if os.environ.get("POCKETGPT_SKIP_DEVICE_LOCK") == "1":
        yield
        return

    if serial in _HELD_DEVICE_LOCKS:
        yield
        return

    lock_path = _device_lock_path(serial)
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    lock_handle = lock_path.open("a+", encoding="utf-8")

    if fcntl is None:
        print_step("fcntl is unavailable; skipping device lock enforcement.")
        try:
            yield
        finally:
            lock_handle.close()
        return

    start = time.monotonic()
    while True:
        try:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except BlockingIOError:
            waited = time.monotonic() - start
            if waited >= timeout_seconds:
                lock_handle.close()
                raise DevctlError(
                    "DEVICE_ERROR",
                    "Timed out waiting for device lock "
                    f"{lock_path} after {int(waited)}s. "
                    "Another run may still be using this device.",
                )
            time.sleep(1.0)

    _write_device_lock_metadata(lock_handle, serial=serial, owner=owner)
    _HELD_DEVICE_LOCKS[serial] = (lock_path, lock_handle)
    print_step(f"Acquired device lock: {lock_path}")
    try:
        yield
    finally:
        try:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)
        except OSError:
            pass
        lock_handle.close()
        _HELD_DEVICE_LOCKS.pop(serial, None)
        print_step(f"Released device lock: {lock_path}")


def build_artifact_dir(template: str, date: str, device: str, label: str, stamp: str) -> Path:
    rendered = template.format(date=date, device=device, label=label, stamp=stamp)
    artifact_root = Path(os.environ.get("POCKET_GPT_DEVCTL_ARTIFACT_ROOT", "tmp/devctl-artifacts")).expanduser()
    if not artifact_root.is_absolute():
        artifact_root = REPO_ROOT / artifact_root
    legacy_prefix = "scripts/benchmarks/runs/"
    if rendered.startswith(legacy_prefix):
        return artifact_root / rendered.removeprefix(legacy_prefix)
    return REPO_ROOT / rendered


def build_gradle_test_command(
    *,
    gradle_binary: str,
    gradle_flags: Sequence[str],
    gradle_tasks: Sequence[str],
    clean: bool,
) -> list[str]:
    command = [gradle_binary, *gradle_flags]
    if clean:
        command.append("clean")
    command.extend(list(gradle_tasks))
    return command


def validate_threshold_columns(csv_path: Path, required_columns: Sequence[str]) -> None:
    with csv_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        header = next(reader, None)

    if header is None:
        raise DevctlError("SCHEMA_ERROR", f"CSV is empty: {csv_path}")

    missing = [column for column in required_columns if column not in header]
    if missing:
        raise DevctlError(
            "SCHEMA_ERROR",
            f"Missing threshold column(s) in {csv_path}: {', '.join(missing)}",
        )


def _resolve_android_env(env: MutableMapping[str, str]) -> tuple[bool, MutableMapping[str, str]]:
    resolved = dict(env)
    android_home = resolved.get("ANDROID_HOME")
    if not android_home and resolved.get("ANDROID_SDK_ROOT"):
        android_home = resolved["ANDROID_SDK_ROOT"]
        resolved["ANDROID_HOME"] = android_home

    configured = bool(android_home and Path(android_home).is_dir())
    return configured, resolved


def _with_target_device_env(env: Mapping[str, str], serial: str) -> MutableMapping[str, str]:
    resolved = dict(env)
    resolved["ADB_SERIAL"] = serial
    resolved["ANDROID_SERIAL"] = serial
    resolved.setdefault("ADB_MDNS_AUTO_CONNECT", "0")
    return resolved


def _ensure_serial(context: RuntimeContext) -> str:
    ensure_command = context.configs.device.preflight.ensure_device_command
    result = context.run(ensure_command, check=False, capture_output=True, env=context.env)
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        raise DevctlError(
            "DEVICE_ERROR",
            f"Device preflight failed. Command: {format_command(ensure_command)}\n{stderr}",
        )

    serial_lines = (result.stdout or "").strip().splitlines()
    if not serial_lines:
        raise DevctlError("DEVICE_ERROR", "Device preflight returned empty serial output.")
    return serial_lines[-1].strip()


def _validate_required_device_props(context: RuntimeContext, serial: str) -> None:
    missing: list[str] = []
    for prop in context.configs.device.preflight.required_props:
        result = context.run(
            ["adb", "-s", serial, "shell", "getprop", prop],
            check=False,
            capture_output=True,
            env=context.env,
        )
        if result.returncode != 0 or not (result.stdout or "").strip():
            missing.append(prop)

    if missing:
        raise DevctlError("DEVICE_ERROR", f"Required device properties are unavailable: {', '.join(missing)}")


def _parse_package_uid(dumpsys_text: str) -> int | None:
    for pattern in (
        r"\buserId=(\d+)\b",
        r"\bappId=(\d+)\b",
        r"\buid=(\d+)\b",
    ):
        match = re.search(pattern, dumpsys_text)
        if match is not None:
            return int(match.group(1))
    return None


def _run_device_health_preflight(
    context: RuntimeContext,
    serial: str,
    *,
    app_package: str = _DEFAULT_HEALTH_APP_PACKAGE,
    test_package: str = _DEFAULT_HEALTH_TEST_PACKAGE,
) -> None:
    print_step("Running device health preflight (wake/unlock/storage/package checks).")

    context.run(["adb", "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False, env=context.env)
    context.run(["adb", "-s", serial, "shell", "wm", "dismiss-keyguard"], check=False, env=context.env)

    storage = context.run(
        ["adb", "-s", serial, "shell", "df", "/data"],
        check=False,
        capture_output=True,
        env=context.env,
    )
    if storage.returncode != 0:
        stderr = (storage.stderr or "").strip()
        raise DevctlError("DEVICE_ERROR", f"Failed to query device storage health via df /data.\n{stderr}")

    usage_values = [int(match.group(1)) for match in re.finditer(r"(\d+)%", storage.stdout or "")]
    if not usage_values:
        raise DevctlError(
            "DEVICE_ERROR",
            "Unable to parse /data usage from `adb shell df /data` output during preflight.",
        )
    usage = usage_values[-1]
    if usage >= 98:
        raise DevctlError(
            "DEVICE_ERROR",
            f"Device /data usage is too high ({usage}%). Free up space before running lanes.",
        )

    primary_health_dir = f"/sdcard/Android/media/{app_package}/devctl-health"
    fallback_health_dir = f"/sdcard/Download/{app_package}/devctl-health"
    health_dir = _ensure_remote_dir(
        context=context,
        serial=serial,
        path=primary_health_dir,
        fallback_paths=[fallback_health_dir],
        failure_label="Device external storage preflight failed while creating runtime media path.",
    )

    probe_local = Path(tempfile.gettempdir()) / f"devctl-probe-{os.getpid()}.txt"
    probe_remote = f"{health_dir}/probe.txt"
    probe_local.write_text("preflight\n", encoding="utf-8")
    try:
        push_result = context.run(
            ["adb", "-s", serial, "push", str(probe_local), probe_remote],
            check=False,
            capture_output=True,
            env=context.env,
        )
        if push_result.returncode != 0:
            detail = "\n".join(part for part in [(push_result.stdout or "").strip(), (push_result.stderr or "").strip()] if part)
            raise DevctlError(
                "DEVICE_ERROR",
                "Device external storage preflight failed while writing probe file.\n"
                f"{detail}",
            )
        context.run(
            ["adb", "-s", serial, "shell", "rm", "-f", probe_remote],
            check=False,
            env=context.env,
        )
    finally:
        probe_local.unlink(missing_ok=True)

    for package_name in (app_package, test_package):
        package_result = context.run(
            ["adb", "-s", serial, "shell", "pm", "list", "packages", "--user", "0", package_name],
            check=False,
            capture_output=True,
            env=context.env,
        )
        if package_result.returncode != 0:
            stderr = (package_result.stderr or "").strip()
            raise DevctlError(
                "DEVICE_ERROR",
                f"Failed package visibility preflight for {package_name}.\n{stderr}",
            )
        listed = f"package:{package_name}" in (package_result.stdout or "")
        if not listed:
            continue
        dumpsys = context.run(
            ["adb", "-s", serial, "shell", "dumpsys", "package", package_name],
            check=False,
            capture_output=True,
            env=context.env,
        )
        if dumpsys.returncode != 0:
            stderr = (dumpsys.stderr or "").strip()
            raise DevctlError(
                "DEVICE_ERROR",
                f"Failed to inspect installed package owner metadata for {package_name}.\n{stderr}",
            )
        uid = _parse_package_uid(dumpsys.stdout or "")
        if uid is None:
            raise DevctlError(
                "DEVICE_ERROR",
                (
                    f"Package {package_name} is installed but package UID metadata "
                    "(userId/appId/uid) could not be resolved from dumpsys package output."
                ),
            )


def _resolve_real_runtime_model_paths(context: RuntimeContext) -> dict[str, str]:
    real_runtime_cfg = context.configs.lanes.lanes.real_runtime
    roots = [Path(root).expanduser() for root in real_runtime_cfg.cache_root_candidates]
    resolved: dict[str, str] = {}
    missing_models: list[str] = []

    for model in real_runtime_cfg.models:
        selected: Path | None = None
        for pattern in model.cache_patterns:
            matches: list[Path] = []
            for root in roots:
                if not root.exists():
                    continue
                if pattern.startswith("**/"):
                    matches.extend(sorted(root.glob(pattern)))
                else:
                    direct = root / pattern
                    if direct.exists():
                        matches.append(direct)
                    matches.extend(sorted(root.glob(pattern)))
            if matches:
                selected = matches[0]
                break
        if selected is None:
            missing_models.append(f"{model.model_id} ({', '.join(model.cache_patterns)})")
            continue
        resolved[model.model_id] = str(selected.resolve())

    if missing_models:
        searched = ", ".join(str(root) for root in roots)
        raise DevctlError(
            "DEVICE_ERROR",
            "Real-runtime model cache preflight failed. Missing model files: "
            f"{'; '.join(missing_models)}. Searched roots: {searched}",
        )
    return resolved


def _run_instrumentation_class(
    *,
    context: RuntimeContext,
    serial: str,
    test_class: str,
    runner: str,
    args: Mapping[str, str],
    timeout_seconds: float | None = None,
) -> subprocess.CompletedProcess[str]:
    command = ["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", test_class]
    for key, value in args.items():
        command.extend(["-e", key, value])
    command.append(runner)

    result = context.run(
        command,
        check=False,
        capture_output=True,
        env=context.env,
        timeout_seconds=timeout_seconds,
    )
    output = ((result.stdout or "") + "\n" + (result.stderr or "")).strip()
    if result.returncode != 0:
        raise DevctlError(
            "DEVICE_ERROR",
            f"Instrumentation failed for {test_class} (exit={result.returncode}).\n{output}",
        )
    failure_reason = _extract_instrumentation_failure(output)
    if failure_reason is not None:
        raise DevctlError(
            "DEVICE_ERROR",
            f"Instrumentation failed for {test_class} (reported failure: {failure_reason}).\n{output}",
        )
    return result


def _extract_instrumentation_failure(output: str) -> str | None:
    if not output.strip():
        return None

    short_msg_match = re.search(r"INSTRUMENTATION_RESULT:\s*shortMsg=(.+)", output)
    if short_msg_match is not None:
        short_msg = short_msg_match.group(1).strip()
        if short_msg and short_msg.lower() != "ok":
            return short_msg

    failed_match = re.search(r"INSTRUMENTATION_FAILED:\s*(.+)", output)
    if failed_match is not None:
        return failed_match.group(1).strip() or "instrumentation_failed"

    for marker in (
        "Process crashed.",
        "FAILURES!!!",
        "INSTRUMENTATION_STATUS: Error",
        "INSTRUMENTATION_RESULT: stream=Error",
    ):
        if marker in output:
            return marker
    return None


def _remote_dir_exists(
    *,
    context: RuntimeContext,
    serial: str,
    path: str,
) -> bool:
    exists_result = context.run(
        ["adb", "-s", serial, "shell", "ls", "-ld", path],
        check=False,
        capture_output=True,
        env=context.env,
    )
    return exists_result.returncode == 0


def _media_path_fallbacks(path: str) -> list[str]:
    # Fallback from MediaProvider-backed locations to a more stable shared-storage path.
    match = re.match(r"^/sdcard/Android/media/([^/]+)/(.+)$", path.strip())
    if match is None:
        return []
    app_package, rest = match.groups()
    return [f"/sdcard/Download/{app_package}/{rest}"]


def _ensure_remote_dir(
    *,
    context: RuntimeContext,
    serial: str,
    path: str,
    failure_label: str,
    fallback_paths: Sequence[str] | None = None,
) -> str:
    candidate_paths = [path, *(fallback_paths or ())]
    errors: list[str] = []

    for idx, candidate in enumerate(candidate_paths):
        candidate_errors: list[str] = []
        for attempt in range(1, _REMOTE_DIR_RETRY_ATTEMPTS + 1):
            mkdir_result = context.run(
                ["adb", "-s", serial, "shell", "mkdir", "-p", candidate],
                check=False,
                capture_output=True,
                env=context.env,
            )
            if mkdir_result.returncode == 0:
                if idx > 0:
                    print_step(f"Using fallback remote directory: {candidate}")
                return candidate

            # Some devices intermittently return EBUSY on MediaProvider-backed paths
            # even though the directory exists and remains writable.
            if _remote_dir_exists(context=context, serial=serial, path=candidate):
                if idx > 0:
                    print_step(f"Using fallback remote directory: {candidate}")
                return candidate

            detail = "\n".join(
                part
                for part in [
                    (mkdir_result.stdout or "").strip(),
                    (mkdir_result.stderr or "").strip(),
                ]
                if part
            )
            candidate_errors.append(
                f"path={candidate} attempt={attempt}/{_REMOTE_DIR_RETRY_ATTEMPTS} detail={detail or '<no-output>'}"
            )
            if attempt < _REMOTE_DIR_RETRY_ATTEMPTS:
                time.sleep(_REMOTE_DIR_RETRY_BACKOFF_SECONDS * attempt)

        errors.extend(candidate_errors)

    detail_block = "\n".join(errors)
    raise DevctlError("DEVICE_ERROR", f"{failure_label}\n{detail_block}")


def _shell_single_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def _run_remote_shell_script(context: RuntimeContext, serial: str, script: str):
    # Use a single adb shell script argument instead of `sh -c ...`.
    # Some device shells mis-handle tokenized `sh -c` argv and return syntax errors.
    return context.run(
        ["adb", "-s", serial, "shell", script],
        check=False,
        capture_output=True,
        env=context.env,
    )


def _is_tcpip_serial(serial: str) -> bool:
    # Wireless ADB serials are typically host:port. Emulator serials use the same separator,
    # but do not need adb connect/disconnect recovery.
    return ":" in serial and not serial.startswith("emulator-")


def _run_maestro_transport_recovery(
    *,
    context: RuntimeContext,
    serial: str,
    env: Mapping[str, str] | None = None,
) -> None:
    run_env = env or context.env
    context.run(
        ["adb", "-s", serial, "forward", "--remove-all"],
        check=False,
        capture_output=True,
        env=run_env,
    )
    if _is_tcpip_serial(serial):
        context.run(
            ["adb", "disconnect", serial],
            check=False,
            capture_output=True,
            env=run_env,
        )
        context.run(
            ["adb", "connect", serial],
            check=False,
            capture_output=True,
            env=run_env,
        )
    context.run(
        ["adb", "-s", serial, "wait-for-device"],
        check=False,
        capture_output=True,
        env=run_env,
    )
    context.run(
        ["adb", "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"],
        check=False,
        capture_output=True,
        env=run_env,
    )
    context.run(
        ["adb", "-s", serial, "shell", "wm", "dismiss-keyguard"],
        check=False,
        capture_output=True,
        env=run_env,
    )


def _remote_file_size_bytes(context: RuntimeContext, serial: str, path: str) -> int | None:
    # Use sh+wc to avoid dependence on toybox stat variants across devices.
    quoted = _shell_single_quote(path)
    probe = _run_remote_shell_script(
        context=context,
        serial=serial,
        script=f"if [ -f {quoted} ]; then wc -c < {quoted}; fi",
    )
    if probe.returncode != 0:
        return None

    output = (probe.stdout or "").strip()
    if not output:
        return None

    last_line = output.splitlines()[-1].strip()
    try:
        return int(last_line)
    except ValueError:
        return None


def _copy_remote_file(
    *,
    context: RuntimeContext,
    serial: str,
    source_path: str,
    destination_path: str,
    failure_label: str,
) -> None:
    quoted_source = _shell_single_quote(source_path)
    quoted_destination = _shell_single_quote(destination_path)
    copy_result = _run_remote_shell_script(
        context=context,
        serial=serial,
        script=(
            f"if [ -f {quoted_source} ]; then "
            f"(cp {quoted_source} {quoted_destination} 2>/dev/null || cat {quoted_source} > {quoted_destination}); "
            "fi"
        ),
    )
    if copy_result.returncode != 0:
        detail = "\n".join(
            part
            for part in [
                (copy_result.stdout or "").strip(),
                (copy_result.stderr or "").strip(),
            ]
            if part
        )
        raise DevctlError("DEVICE_ERROR", f"{failure_label}\n{detail or '<no-output>'}")


def _remote_path_exists(context: RuntimeContext, serial: str, path: str) -> bool:
    quoted_path = path.replace("'", "'\"'\"'")
    result = _run_remote_shell_script(
        context=context,
        serial=serial,
        script=f"[ -e '{quoted_path}' ] && echo 1 || echo 0",
    )
    if result.returncode != 0:
        return False
    return (result.stdout or "").strip() == "1"


def _compute_file_sha256(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _remote_file_sha256(context: RuntimeContext, serial: str, path: str) -> str | None:
    quoted = _shell_single_quote(path)
    probe = _run_remote_shell_script(
        context=context,
        serial=serial,
        script=(
            f"if [ -f {quoted} ]; then "
            f"(sha256sum {quoted} 2>/dev/null || toybox sha256sum {quoted} 2>/dev/null); "
            "fi"
        ),
    )
    if probe.returncode != 0:
        return None

    output = (probe.stdout or "").strip()
    if not output:
        return None
    line = output.splitlines()[-1].strip()
    match = re.match(r"^([a-fA-F0-9]{64})\b", line)
    return match.group(1).lower() if match is not None else None


def _remote_read_text_file(context: RuntimeContext, serial: str, path: str) -> str | None:
    quoted = _shell_single_quote(path)
    probe = _run_remote_shell_script(
        context=context,
        serial=serial,
        script=f"if [ -f {quoted} ]; then cat {quoted}; fi",
    )
    if probe.returncode != 0:
        return None
    text = probe.stdout or ""
    return text if text.strip() else None


def _model_sync_cache_dir(model_dir: str) -> str:
    normalized = model_dir.rstrip("/")
    if normalized.endswith("/models"):
        return f"{normalized[:-len('/models')]}/devctl-cache"
    return f"{normalized}/devctl-cache"


def _build_model_sync_manifest_payload(
    *,
    app_package: str,
    selected_model_dir: str,
    models: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    return {
        "schema": _MODEL_SYNC_MANIFEST_SCHEMA,
        "app_package": app_package,
        "selected_model_dir": selected_model_dir,
        "updated_at": datetime.now().isoformat(timespec="seconds"),
        "models": dict(models),
    }


def _parse_model_sync_manifest(raw: str) -> dict[str, Any]:
    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise ValueError("manifest root is not an object")
    if parsed.get("schema") != _MODEL_SYNC_MANIFEST_SCHEMA:
        raise ValueError("unsupported schema")
    models = parsed.get("models")
    if models is None:
        parsed["models"] = {}
    elif not isinstance(models, dict):
        raise ValueError("models section is not an object")
    selected = parsed.get("selected_model_dir")
    if selected is not None and not isinstance(selected, str):
        raise ValueError("selected_model_dir must be string")
    return parsed


def _load_model_sync_manifest(
    *,
    context: RuntimeContext,
    serial: str,
    cache_dirs: Sequence[str],
) -> tuple[dict[str, Any], str | None, str | None]:
    load_error: str | None = None
    for cache_dir in cache_dirs:
        manifest_path = f"{cache_dir.rstrip('/')}/{_MODEL_SYNC_MANIFEST_FILE}"
        raw = _remote_read_text_file(context, serial, manifest_path)
        if raw is None:
            continue
        try:
            parsed = _parse_model_sync_manifest(raw)
            return parsed, manifest_path, None
        except Exception as exc:  # noqa: BLE001 - malformed on-device cache should self-heal.
            load_error = f"{manifest_path}: {exc}"
            print_step(f"Ignoring corrupt model-sync cache manifest at {manifest_path}; self-healing on save.")
    return {"schema": _MODEL_SYNC_MANIFEST_SCHEMA, "models": {}}, None, load_error


def _write_model_sync_manifest(
    *,
    context: RuntimeContext,
    serial: str,
    cache_dir: str,
    payload: Mapping[str, Any],
) -> str:
    ensured_cache_dir = _ensure_remote_dir(
        context=context,
        serial=serial,
        path=cache_dir,
        failure_label="Failed to ensure remote cache directory for model sync manifest.",
    )
    manifest_path = f"{ensured_cache_dir.rstrip('/')}/{_MODEL_SYNC_MANIFEST_FILE}"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
        handle.write(json.dumps(payload, indent=2) + "\n")
        temp_manifest_path = Path(handle.name)
    try:
        push = context.run(
            ["adb", "-s", serial, "push", str(temp_manifest_path), manifest_path],
            check=False,
            capture_output=True,
            env=context.env,
        )
    finally:
        temp_manifest_path.unlink(missing_ok=True)
    if push.returncode != 0:
        detail = "\n".join(
            part
            for part in [(push.stdout or "").strip(), (push.stderr or "").strip()]
            if part
        ).strip()
        raise DevctlError(
            "DEVICE_ERROR",
            "Failed to persist model sync manifest.\n" + (detail or "<no-output>"),
        )
    return manifest_path


def _resolve_available_instrumentation_runner(
    *,
    context: RuntimeContext,
    serial: str,
    preferred_runner: str,
    app_package: str,
) -> str:
    listings = context.run(
        ["adb", "-s", serial, "shell", "pm", "list", "instrumentation"],
        check=False,
        capture_output=True,
        env=context.env,
    )
    if listings.returncode != 0:
        return preferred_runner

    parsed: list[tuple[str, str]] = []
    for raw_line in (listings.stdout or "").splitlines():
        line = raw_line.strip()
        if not line.startswith("instrumentation:"):
            continue
        runner_match = re.search(r"^instrumentation:([^\s]+)", line)
        if runner_match is None:
            continue
        runner = runner_match.group(1)
        target_match = re.search(r"\(target=([^)]+)\)", line)
        target = target_match.group(1) if target_match is not None else ""
        parsed.append((runner, target))

    if not parsed:
        return preferred_runner

    if any(runner == preferred_runner for runner, _ in parsed):
        return preferred_runner

    for runner, target in parsed:
        if target == app_package or target.startswith(f"{app_package}."):
            return runner

    for runner, _ in parsed:
        if runner.startswith(f"{app_package}."):
            return runner

    return parsed[0][0]


def prepare_real_runtime_env(
    context: RuntimeContext,
    device_serial: str,
    artifact_root: Path | None = None,
) -> RealRuntimePreparedEnv:
    real_runtime_cfg = context.configs.lanes.lanes.real_runtime
    model_host_paths_by_id = _resolve_real_runtime_model_paths(context)
    model_device_paths_by_id: dict[str, str] = {}

    candidate_model_dirs = [real_runtime_cfg.device_model_dir, *_media_path_fallbacks(real_runtime_cfg.device_model_dir)]
    resolved_model_dir = _ensure_remote_dir(
        context=context,
        serial=device_serial,
        path=candidate_model_dirs[0],
        fallback_paths=candidate_model_dirs[1:],
        failure_label="Failed to prepare remote model directory.",
    )

    preferred_model_dirs = [resolved_model_dir, *[path for path in candidate_model_dirs if path != resolved_model_dir]]
    candidate_cache_dirs = list(dict.fromkeys([_model_sync_cache_dir(path) for path in preferred_model_dirs]))
    model_sync_manifest, loaded_manifest_path, manifest_load_error = _load_model_sync_manifest(
        context=context,
        serial=device_serial,
        cache_dirs=candidate_cache_dirs,
    )
    manifest_models = model_sync_manifest.get("models")
    if not isinstance(manifest_models, dict):
        manifest_models = {}
    force_model_sync = context.env.get(_FORCE_MODEL_SYNC_ENV, "").strip() == "1"
    model_sync_records: dict[str, dict[str, Any]] = {}
    model_sync_decisions: list[dict[str, Any]] = []

    selected_model_dir = model_sync_manifest.get("selected_model_dir")
    if isinstance(selected_model_dir, str) and selected_model_dir.strip():
        preferred_model_dirs = [
            selected_model_dir.strip(),
            *[path for path in preferred_model_dirs if path != selected_model_dir.strip()],
        ]
    if resolved_model_dir in preferred_model_dirs and preferred_model_dirs[0] != resolved_model_dir:
        preferred_model_dirs = [resolved_model_dir, *[path for path in preferred_model_dirs if path != resolved_model_dir]]
    selected_model_dir_for_cache = preferred_model_dirs[0]

    for model in real_runtime_cfg.models:
        host_path = model_host_paths_by_id[model.model_id]
        host_size = Path(host_path).stat().st_size
        host_sha256 = _compute_file_sha256(host_path)
        manifest_entry = manifest_models.get(model.model_id)
        manifest_host_size = None
        if isinstance(manifest_entry, dict):
            try:
                manifest_host_size = int(manifest_entry.get("host_size", -1))
            except (TypeError, ValueError):
                manifest_host_size = None
        decision = "push_required"
        decision_reason = "no reusable device artifact found"
        push_failures: list[str] = []
        resolved_device_path: str | None = None
        resolved_device_size: int | None = None

        if (
            not force_model_sync and
            isinstance(manifest_entry, dict) and
            isinstance(manifest_entry.get("device_path"), str) and
            manifest_host_size == host_size and
            str(manifest_entry.get("host_sha256", "")).lower() == host_sha256
        ):
            candidate_path = manifest_entry["device_path"].strip()
            if candidate_path:
                remote_size = _remote_file_size_bytes(context, device_serial, candidate_path)
                if remote_size == host_size:
                    remote_sha = _remote_file_sha256(context, device_serial, candidate_path)
                    if remote_sha is None or remote_sha == host_sha256:
                        resolved_path = candidate_path
                        resolved_size = remote_size
                        resolved_reason = "model-sync manifest fingerprint matched"
                        candidate_parent = str(Path(candidate_path).parent)
                        if candidate_parent != resolved_model_dir:
                            primary_device_path = f"{resolved_model_dir.rstrip('/')}/{model.device_file_name}"
                            print_step(
                                f"Restoring {model.model_id} from cached path {candidate_path} to primary path {primary_device_path}"
                            )
                            _ensure_remote_dir(
                                context=context,
                                serial=device_serial,
                                path=resolved_model_dir,
                                failure_label="Failed to ensure primary model directory for manifest cache restore.",
                            )
                            _copy_remote_file(
                                context=context,
                                serial=device_serial,
                                source_path=candidate_path,
                                destination_path=primary_device_path,
                                failure_label=(
                                    f"Failed to restore {model.model_id} from manifest cache path to primary model path."
                                ),
                            )
                            restored_size = _remote_file_size_bytes(context, device_serial, primary_device_path)
                            if restored_size == host_size:
                                resolved_path = primary_device_path
                                resolved_size = restored_size
                                resolved_reason = "restored from manifest cache path to primary model path"
                            else:
                                print_step(
                                    f"Manifest cache restore verification failed for {primary_device_path}; using cached path {candidate_path}."
                                )

                        resolved_device_path = resolved_path
                        resolved_device_size = resolved_size
                        decision = "cache_hit"
                        decision_reason = resolved_reason
                        selected_model_dir_for_cache = str(Path(resolved_path).parent)
                    else:
                        decision_reason = "manifest fingerprint matched but remote hash mismatched"
                elif remote_size is None:
                    decision_reason = "manifest fingerprint matched but remote size probe was unavailable"
                else:
                    decision_reason = "manifest fingerprint matched but cached device path is stale"

        ensured_model_dirs: list[str] = []
        for dir_index, model_dir in enumerate(preferred_model_dirs):
            if resolved_device_path is not None:
                break
            try:
                ensured_dir = _ensure_remote_dir(
                    context=context,
                    serial=device_serial,
                    path=model_dir,
                    failure_label="Failed to ensure remote model directory before push.",
                )
            except DevctlError as exc:
                push_failures.append(f"{model_dir}: {exc.message}")
                if dir_index + 1 < len(preferred_model_dirs):
                    print_step(
                        f"Model directory unavailable at {model_dir}; "
                        "trying fallback model directory."
                    )
                    continue
                raise
            ensured_model_dirs.append(ensured_dir)
            device_path = f"{ensured_dir.rstrip('/')}/{model.device_file_name}"
            selected_model_dir_for_cache = ensured_dir
            if force_model_sync:
                continue

            remote_size = _remote_file_size_bytes(context, device_serial, device_path)
            if remote_size == host_size:
                remote_sha = _remote_file_sha256(context, device_serial, device_path)
                if remote_sha is None or remote_sha == host_sha256:
                    chosen_device_path = device_path
                    chosen_device_size = remote_size
                    chosen_reason = (
                        "device artifact matched size and hash"
                        if remote_sha is not None
                        else "device artifact matched size (device hash unavailable)"
                    )

                    if dir_index > 0 and preferred_model_dirs:
                        primary_model_dir = preferred_model_dirs[0]
                        if primary_model_dir != ensured_dir:
                            primary_ensured_dir = _ensure_remote_dir(
                                context=context,
                                serial=device_serial,
                                path=primary_model_dir,
                                failure_label="Failed to ensure primary model directory for fallback restore.",
                            )
                            primary_device_path = f"{primary_ensured_dir.rstrip('/')}/{model.device_file_name}"
                            print_step(
                                f"Restoring {model.model_id} from fallback cache to primary path {primary_device_path}"
                            )
                            _copy_remote_file(
                                context=context,
                                serial=device_serial,
                                source_path=device_path,
                                destination_path=primary_device_path,
                                failure_label=(
                                    f"Failed to restore {model.model_id} from fallback cache to primary model path."
                                ),
                            )
                            restored_size = _remote_file_size_bytes(context, device_serial, primary_device_path)
                            if restored_size == host_size:
                                chosen_device_path = primary_device_path
                                chosen_device_size = restored_size
                                chosen_reason = "restored from persistent cache to primary model path"
                            else:
                                print_step(
                                    f"Fallback restore verification failed for {primary_device_path}; continuing with {device_path}."
                                )

                    print_step(
                        f"Model {model.model_id} already present at {chosen_device_path} with matching size; skipping push."
                    )
                    resolved_device_path = chosen_device_path
                    resolved_device_size = chosen_device_size
                    if decision != "cache_hit":
                        decision = "size_probe_hit"
                        decision_reason = chosen_reason
                    resolved_model_dir = str(Path(chosen_device_path).parent)
                    if resolved_model_dir != preferred_model_dirs[0]:
                        preferred_model_dirs = [resolved_model_dir, *[path for path in preferred_model_dirs if path != resolved_model_dir]]
                    break
                decision_reason = "device artifact size matched but hash mismatched; forcing push"
            elif remote_size is None:
                decision_reason = "remote size probe unavailable; pushing model to guarantee consistency"

        if resolved_device_path is None:
            if force_model_sync:
                decision = "forced_sync"
                decision_reason = f"{_FORCE_MODEL_SYNC_ENV}=1"

            for dir_index, ensured_dir in enumerate(ensured_model_dirs):
                device_path = f"{ensured_dir.rstrip('/')}/{model.device_file_name}"
                selected_model_dir_for_cache = ensured_dir
                print_step(f"Pushing {model.model_id} to device path {device_path}")
                push_result = None
                for attempt in range(2):
                    if attempt > 0:
                        print_step(
                            f"Retrying push for {model.model_id} after ensuring remote directory exists "
                            f"(attempt {attempt + 1}/2)."
                        )
                    _ensure_remote_dir(
                        context=context,
                        serial=device_serial,
                        path=ensured_dir,
                        failure_label="Failed to ensure remote model directory before push retry.",
                    )
                    push_result = context.run(
                        ["adb", "-s", device_serial, "push", host_path, device_path],
                        check=False,
                        capture_output=True,
                        env=context.env,
                    )
                    if push_result.returncode == 0:
                        resolved_device_path = device_path
                        resolved_device_size = _remote_file_size_bytes(context, device_serial, device_path) or host_size
                        if dir_index > 0:
                            preferred_model_dirs = [ensured_dir, *[path for path in preferred_model_dirs if path != ensured_dir]]
                        break
                    # Small delay helps stabilize scoped-storage media directory writes on some devices.
                    time.sleep(1.0)

                if resolved_device_path is not None:
                    break

                stderr = (push_result.stderr or "").strip() if push_result is not None else ""
                stdout = (push_result.stdout or "").strip() if push_result is not None else ""
                detail = "\n".join(part for part in [stdout, stderr] if part).strip()
                push_failures.append(f"{device_path}: {detail or 'push failed'}")
                if dir_index + 1 < len(ensured_model_dirs):
                    print_step(
                        f"Push failed for {model.model_id} at {device_path}; "
                        "trying fallback model directory."
                    )

        if resolved_device_path is None:
            detail = "\n".join(push_failures) if push_failures else "no push attempts recorded"
            raise DevctlError(
                "DEVICE_ERROR",
                f"Failed to push model artifact {host_path} after trying candidate device paths.\n{detail}",
            )

        persistent_dirs = _media_path_fallbacks(real_runtime_cfg.device_model_dir)
        if persistent_dirs:
            persistent_dir = persistent_dirs[0]
            persistent_path = f"{persistent_dir.rstrip('/')}/{model.device_file_name}"
            if persistent_path != resolved_device_path:
                try:
                    _ensure_remote_dir(
                        context=context,
                        serial=device_serial,
                        path=persistent_dir,
                        failure_label="Failed to prepare persistent model cache directory.",
                    )
                    persistent_size = _remote_file_size_bytes(context, device_serial, persistent_path)
                    if persistent_size != host_size:
                        print_step(
                            f"Mirroring {model.model_id} to persistent cache path {persistent_path}"
                        )
                        _copy_remote_file(
                            context=context,
                            serial=device_serial,
                            source_path=resolved_device_path,
                            destination_path=persistent_path,
                            failure_label=f"Failed to mirror {model.model_id} to persistent cache path.",
                        )
                        verified_persistent_size = _remote_file_size_bytes(context, device_serial, persistent_path)
                        if verified_persistent_size != host_size:
                            print_step(
                                f"Persistent cache mirror verification failed for {persistent_path}; keeping primary artifact."
                            )
                except DevctlError as exc:
                    print_step(f"Persistent cache mirror skipped for {model.model_id}: {exc.message}")

        model_device_paths_by_id[model.model_id] = resolved_device_path
        model_sync_records[model.model_id] = {
            "host_sha256": host_sha256,
            "host_size": host_size,
            "device_path": resolved_device_path,
            "device_size": resolved_device_size if resolved_device_size is not None else host_size,
            "last_verified_at": datetime.now().isoformat(timespec="seconds"),
        }
        model_sync_decisions.append(
            {
                "model_id": model.model_id,
                "decision": decision,
                "reason": decision_reason,
                "host_size": host_size,
                "host_sha256": host_sha256,
                "device_path": resolved_device_path,
                "device_size": resolved_device_size if resolved_device_size is not None else host_size,
            }
        )

    model_sync_manifest_path: str | None = None
    if model_sync_records:
        selected_model_dir_for_cache = selected_model_dir_for_cache.rstrip("/")
        selected_cache_dir = _model_sync_cache_dir(selected_model_dir_for_cache)
        payload = _build_model_sync_manifest_payload(
            app_package=real_runtime_cfg.app_package,
            selected_model_dir=selected_model_dir_for_cache,
            models=model_sync_records,
        )
        try:
            model_sync_manifest_path = _write_model_sync_manifest(
                context=context,
                serial=device_serial,
                cache_dir=selected_cache_dir,
                payload=payload,
            )
        except DevctlError as exc:
            print_step(f"Model sync cache manifest write skipped: {exc.message}")
            manifest_load_error = manifest_load_error or exc.message

    instrumentation_runner = _resolve_available_instrumentation_runner(
        context=context,
        serial=device_serial,
        preferred_runner=real_runtime_cfg.instrumentation_runner,
        app_package=real_runtime_cfg.app_package,
    )
    runner_args = _instrumentation_args_from_model_paths(model_device_paths_by_id)
    provisioning_args = dict(runner_args)
    provisioning_args["stage2_enable_provisioning_test"] = "true"

    def _ensure_synced_model_files() -> None:
        for model in real_runtime_cfg.models:
            host_path = model_host_paths_by_id[model.model_id]
            host_size = Path(host_path).stat().st_size
            device_path = model_device_paths_by_id[model.model_id]
            remote_size = _remote_file_size_bytes(context, device_serial, device_path)
            if remote_size == host_size:
                continue

            print_step(
                f"Model {model.model_id} missing/stale at {device_path}; re-pushing before provisioning probe."
            )
            remote_parent = device_path.rsplit("/", 1)[0]
            _ensure_remote_dir(
                context=context,
                serial=device_serial,
                path=remote_parent,
                failure_label="Failed to ensure remote model directory before provisioning probe.",
            )
            context.run(
                ["adb", "-s", device_serial, "push", host_path, device_path],
                check=True,
                env=context.env,
            )
            verified_size = _remote_file_size_bytes(context, device_serial, device_path)
            if verified_size != host_size:
                raise DevctlError(
                    "DEVICE_ERROR",
                    f"Model {model.model_id} is unavailable after pre-probe re-push: {device_path}",
                )

    def _run_provisioning_probe() -> None:
        _run_instrumentation_class(
            context=context,
            serial=device_serial,
            test_class=real_runtime_cfg.provisioning_test_class,
            runner=instrumentation_runner,
            args=provisioning_args,
            timeout_seconds=real_runtime_cfg.startup_probe_timeout_seconds,
        )

    _ensure_synced_model_files()
    try:
        _run_provisioning_probe()
    except DevctlError as exc:
        retry_reason: str | None = None
        if "Model path does not exist" in exc.message:
            retry_reason = "missing model path"
        elif "Process crashed" in exc.message:
            retry_reason = "instrumentation process crash"
        if retry_reason is None:
            raise
        print_step(
            f"Provisioning probe hit {retry_reason}; re-syncing model artifacts and retrying once."
        )
        _ensure_synced_model_files()
        _run_provisioning_probe()

    prepared = RealRuntimePreparedEnv(
        serial=device_serial,
        model_device_paths_by_id=model_device_paths_by_id,
        model_host_paths_by_id=model_host_paths_by_id,
        instrumentation_runner=instrumentation_runner,
    )

    if artifact_root is not None:
        artifact_root.mkdir(parents=True, exist_ok=True)
        metadata_path = artifact_root / "real-runtime-preflight.json"
        metadata_path.write_text(
            json.dumps(
                {
                    "serial": device_serial,
                    "prepared_at": datetime.now().isoformat(timespec="seconds"),
                    "model_host_paths_by_id": model_host_paths_by_id,
                    "model_device_paths_by_id": model_device_paths_by_id,
                    "instrumentation_runner": instrumentation_runner,
                    "model_sync": {
                        "schema": _MODEL_SYNC_MANIFEST_SCHEMA,
                        "manifest_path": model_sync_manifest_path,
                        "loaded_manifest_path": loaded_manifest_path,
                        "load_error": manifest_load_error,
                        "force_sync": force_model_sync,
                        "selected_model_dir": selected_model_dir_for_cache.rstrip("/"),
                        "decisions": model_sync_decisions,
                    },
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )

    return prepared


def _capture_logcat(context: RuntimeContext, serial: str, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    result = context.run(
        ["adb", "-s", serial, "logcat", "-d"],
        check=False,
        capture_output=True,
        env=context.env,
    )
    text = (result.stdout or "") + (result.stderr or "")
    output_path.write_text(text, encoding="utf-8")


def _resolve_lane_artifact_dir(template: str, serial: str, label: str) -> Path:
    date_value = datetime.now().strftime("%Y-%m-%d")
    stamp = _now_stamp()
    return build_artifact_dir(template, date_value, serial, label, stamp)


def _collect_maestro_screenshots(debug_dir: Path) -> list[str]:
    if not debug_dir.exists():
        return []
    screenshots: list[str] = []
    for path in sorted(debug_dir.rglob("*.png")):
        try:
            screenshots.append(str(path.relative_to(REPO_ROOT)))
        except ValueError:
            screenshots.append(str(path))
    return screenshots


def _copy_pngs_flat(source_dir: Path, target_dir: Path) -> list[Path]:
    if not source_dir.exists():
        return []
    target_dir.mkdir(parents=True, exist_ok=True)
    copied: list[Path] = []
    used_names: set[str] = {path.name for path in target_dir.glob("*.png")}
    for source in sorted(source_dir.rglob("*.png")):
        candidate_name = source.name
        stem = source.stem
        suffix = source.suffix
        counter = 2
        while candidate_name in used_names:
            candidate_name = f"{stem}-{counter}{suffix}"
            counter += 1
        destination = target_dir / candidate_name
        shutil.copy2(source, destination)
        used_names.add(candidate_name)
        copied.append(destination)
    return copied


def _parse_screenshot_pack_args(raw_args: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="devctl lane screenshot-pack")
    parser.add_argument("--update-reference", action="store_true")
    parser.add_argument(
        "--product-signal-only",
        action="store_true",
        help="Downgrade known harness-noise failures to warnings for screenshot-pack orchestration lanes.",
    )
    parser.add_argument(
        "--deterministic-only",
        action="store_true",
        help="Run deterministic screenshot subset only (skip full instrumentation expansion).",
    )
    return parser.parse_args(list(raw_args))


def _is_screenshot_pack_harness_noise(message: str) -> bool:
    lowered = message.lower()
    return any(marker.lower() in lowered for marker in _SCREENSHOT_PACK_HARNESS_NOISE_MARKERS)


def _screenshot_pack_selector(*, deterministic: bool) -> str:
    if not deterministic:
        return _SCREENSHOT_PACK_BASE_TEST_CLASS
    return ",".join(
        f"{_SCREENSHOT_PACK_BASE_TEST_CLASS}#{method_name}"
        for method_name in _SCREENSHOT_PACK_DETERMINISTIC_METHODS
    )


def _load_screenshot_inventory(path: Path = _SCREENSHOT_INVENTORY_PATH) -> list[ScreenshotInventoryItem]:
    if yaml is None:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            "PyYAML is required. Install dependencies with: python3 -m pip install -r tools/devctl/requirements.txt",
        )
    if not path.exists():
        raise DevctlError("CONFIG_ERROR", f"Missing screenshot inventory file: {path}")

    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise DevctlError("CONFIG_ERROR", f"Failed to parse screenshot inventory {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise DevctlError("CONFIG_ERROR", f"Invalid screenshot inventory root in {path}; expected object.")
    if data.get("schema") != _SCREENSHOT_INVENTORY_SCHEMA:
        raise DevctlError(
            "CONFIG_ERROR",
            f"Unsupported screenshot inventory schema in {path}. Expected '{_SCREENSHOT_INVENTORY_SCHEMA}'.",
        )
    raw_entries = data.get("screenshots")
    if not isinstance(raw_entries, list) or not raw_entries:
        raise DevctlError("CONFIG_ERROR", f"Screenshot inventory {path} must define a non-empty screenshots list.")

    entries: list[ScreenshotInventoryItem] = []
    seen_ids: set[str] = set()
    seen_filenames: set[str] = set()
    id_pattern = re.compile(r"^ui-\d{2}-[a-z0-9-]+$")
    for index, raw in enumerate(raw_entries, start=1):
        if not isinstance(raw, dict):
            raise DevctlError("CONFIG_ERROR", f"Invalid screenshot entry #{index} in {path}; expected object.")
        shot_id = str(raw.get("id", "")).strip()
        filename = str(raw.get("filename", "")).strip()
        candidates_raw = raw.get("candidates")
        if not shot_id or not filename:
            raise DevctlError("CONFIG_ERROR", f"Screenshot entry #{index} in {path} requires id and filename.")
        if not id_pattern.match(shot_id):
            raise DevctlError("CONFIG_ERROR", f"Invalid screenshot id '{shot_id}' in {path}.")
        if not filename.endswith(".png"):
            raise DevctlError("CONFIG_ERROR", f"Screenshot filename must end with .png in {path}: {filename}")
        if shot_id in seen_ids:
            raise DevctlError("CONFIG_ERROR", f"Duplicate screenshot id in {path}: {shot_id}")
        if filename in seen_filenames:
            raise DevctlError("CONFIG_ERROR", f"Duplicate screenshot filename in {path}: {filename}")
        if not isinstance(candidates_raw, list) or not candidates_raw:
            raise DevctlError(
                "CONFIG_ERROR",
                f"Screenshot entry '{shot_id}' in {path} must define a non-empty candidates list.",
            )
        candidates = tuple(str(value).strip() for value in candidates_raw if str(value).strip())
        if not candidates:
            raise DevctlError(
                "CONFIG_ERROR",
                f"Screenshot entry '{shot_id}' in {path} must define at least one candidate value.",
            )
        entries.append(ScreenshotInventoryItem(id=shot_id, filename=filename, candidates=candidates))
        seen_ids.add(shot_id)
        seen_filenames.add(filename)
    return entries


def _resolve_inventory_candidate_path(
    candidate: str,
    *,
    artifact_root: Path,
    instrumented_dir: Path,
    maestro_dir: Path,
    combined_dir: Path,
) -> Path:
    value = candidate.strip()
    if value.startswith("instrumented/"):
        return instrumented_dir / value.removeprefix("instrumented/")
    if value.startswith("maestro/"):
        return maestro_dir / value.removeprefix("maestro/")
    if value.startswith("combined/"):
        return combined_dir / value.removeprefix("combined/")
    if value.startswith("/"):
        return Path(value)
    return artifact_root / value


def _build_screenshot_inventory_report(
    *,
    inventory: Sequence[ScreenshotInventoryItem],
    serial: str,
    artifact_root: Path,
    instrumented_dir: Path,
    maestro_dir: Path,
    combined_dir: Path,
    report_json_path: Path,
    report_md_path: Path,
) -> dict[str, Any]:
    combined_dir.mkdir(parents=True, exist_ok=True)
    entries: list[dict[str, Any]] = []
    missing_ids: list[str] = []
    inventory_digest = hashlib.sha256(
        json.dumps(
            [
                {
                    "id": item.id,
                    "filename": item.filename,
                    "candidates": list(item.candidates),
                }
                for item in inventory
            ],
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    generated_at = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    run_id = artifact_root.name

    for item in inventory:
        selected_path: Path | None = None
        matched_candidate: str | None = None
        for candidate in item.candidates:
            candidate_path = _resolve_inventory_candidate_path(
                candidate,
                artifact_root=artifact_root,
                instrumented_dir=instrumented_dir,
                maestro_dir=maestro_dir,
                combined_dir=combined_dir,
            )
            if candidate_path.exists() and candidate_path.is_file():
                selected_path = candidate_path
                matched_candidate = candidate
                break

        combined_target = combined_dir / item.filename
        if selected_path is not None:
            shutil.copy2(selected_path, combined_target)
            status = "PASS"
        else:
            status = "MISSING"
            missing_ids.append(item.id)

        entries.append(
            {
                "id": item.id,
                "filename": item.filename,
                "status": status,
                "matched_candidate": matched_candidate,
                "resolved_path": _report_path(selected_path) if selected_path is not None else None,
                "combined_path": _report_path(combined_target) if combined_target.exists() else None,
            }
        )

    payload = {
        "schema": _SCREENSHOT_REPORT_SCHEMA,
        "inventory_schema": _SCREENSHOT_INVENTORY_SCHEMA,
        "inventory_digest": inventory_digest,
        "generated_at_utc": generated_at,
        "generated_at": generated_at,
        "run_id": run_id,
        "device_serial": serial,
        "serial": serial,
        "artifact_root": _report_path(artifact_root),
        "missing_ids": missing_ids,
        "summary": {
            "total_required": len(inventory),
            "passed": len(inventory) - len(missing_ids),
            "missing": len(missing_ids),
        },
        "entries": entries,
    }

    report_json_path.parent.mkdir(parents=True, exist_ok=True)
    report_json_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Screenshot Inventory Report",
        "",
        f"- Device: `{serial}`",
        f"- Artifact root: `{_report_path(artifact_root)}`",
        f"- Generated: `{generated_at}`",
        f"- Required: `{payload['summary']['total_required']}`",
        f"- Passed: `{payload['summary']['passed']}`",
        f"- Missing: `{payload['summary']['missing']}`",
        "",
        "| Screenshot ID | Status | Normalized File | Source |",
        "|---|---|---|---|",
    ]
    for entry in entries:
        lines.append(
            "| "
            f"{entry['id']} | {entry['status']} | {entry['filename']} | "
            f"{entry['resolved_path'] or '-'} |"
        )
    report_md_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    return payload


def _promote_screenshot_reference_set(
    *,
    combined_dir: Path,
    inventory: Sequence[ScreenshotInventoryItem],
    reference_dir: Path = _SCREENSHOT_REFERENCE_DIR,
) -> None:
    reference_dir.mkdir(parents=True, exist_ok=True)
    for existing in sorted(reference_dir.glob("*.png")):
        existing.unlink()

    missing: list[str] = []
    for item in inventory:
        source = combined_dir / item.filename
        if not source.exists():
            missing.append(item.id)
            continue
        shutil.copy2(source, reference_dir / item.filename)
    if missing:
        raise DevctlError(
            "CONFIG_ERROR",
            "Cannot update screenshot references; combined set is missing required IDs: " + ", ".join(missing),
        )

    index_path = reference_dir / "index.md"
    lines = [
        "# Screenshot Reference Gallery (`sm-a515f-android13`)",
        "",
        f"- Updated: `{datetime.now().isoformat(timespec='seconds')}`",
        f"- Source: `{_report_path(combined_dir)}`",
        "",
    ]
    for item in inventory:
        lines.append(f"## {item.id}")
        lines.append("")
        lines.append(f"![{item.id}]({item.filename})")
        lines.append("")
    index_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def _report_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def _capture_device_screenshot(
    *,
    context: RuntimeContext,
    serial: str,
    local_path: Path,
) -> bool:
    local_path.parent.mkdir(parents=True, exist_ok=True)
    remote_path = f"/sdcard/{_sanitize_file_token(local_path.stem)}-{os.getpid()}.png"
    screencap = context.run(
        ["adb", "-s", serial, "shell", "screencap", "-p", remote_path],
        check=False,
        capture_output=True,
        env=context.env,
    )
    if screencap.returncode != 0:
        return False

    pull = context.run(
        ["adb", "-s", serial, "pull", remote_path, str(local_path)],
        check=False,
        capture_output=True,
        env=context.env,
    )
    context.run(["adb", "-s", serial, "shell", "rm", "-f", remote_path], check=False, env=context.env)
    return pull.returncode == 0 and local_path.exists()


def _capture_window_dump(
    *,
    context: RuntimeContext,
    serial: str,
    local_path: Path,
) -> bool:
    local_path.parent.mkdir(parents=True, exist_ok=True)
    remote_path = f"/sdcard/{_sanitize_file_token(local_path.stem)}-{os.getpid()}.xml"
    dump_result = context.run(
        ["adb", "-s", serial, "shell", "uiautomator", "dump", remote_path],
        check=False,
        capture_output=True,
        env=context.env,
    )
    if dump_result.returncode != 0:
        return False

    pull = context.run(
        ["adb", "-s", serial, "pull", remote_path, str(local_path)],
        check=False,
        capture_output=True,
        env=context.env,
    )
    context.run(["adb", "-s", serial, "shell", "rm", "-f", remote_path], check=False, env=context.env)
    return pull.returncode == 0 and local_path.exists()


def _capture_shared_pref_snapshots(
    *,
    context: RuntimeContext,
    serial: str,
    app_package: str,
    snapshot_dir: Path,
    suffix: str,
) -> dict[str, Path]:
    snapshot_dir.mkdir(parents=True, exist_ok=True)
    pref_files = {
        "chat": "pocketagent_chat_state.xml",
        "runtime_models": "pocketagent_runtime_models.xml",
        "model_downloads": "pocketagent_model_downloads.xml",
    }
    outputs: dict[str, Path] = {}

    for key, pref_file in pref_files.items():
        result = context.run(
            ["adb", "-s", serial, "shell", "run-as", app_package, "cat", f"shared_prefs/{pref_file}"],
            check=False,
            capture_output=True,
            env=context.env,
        )
        output_path = snapshot_dir / f"{key}_{suffix}.xml"
        if result.returncode == 0 and (result.stdout or "").strip():
            output_path.write_text(result.stdout or "", encoding="utf-8")
            outputs[key] = output_path
            continue

        error_path = snapshot_dir / f"{key}_{suffix}.error.txt"
        detail = "\n".join(
            part for part in [(result.stdout or "").strip(), (result.stderr or "").strip()] if part
        )
        error_path.write_text(detail or f"run-as failed with exit code {result.returncode}\n", encoding="utf-8")
        outputs[f"{key}_error"] = error_path

    return outputs


def _extract_ui_response_visible(
    texts: Sequence[str],
    *,
    prompt: str,
) -> bool:
    prompt_folded = prompt.strip().lower()
    ignored_exact = {
        "pocket gpt",
        "offline-first",
        "message",
        "send",
        "cancel",
        "advanced",
        "privacy",
        "tools",
        "sessions",
        "copy message",
        "attach image",
    }

    for token in texts:
        value = token.strip()
        if not value:
            continue
        folded = value.lower()
        if prompt_folded and folded == prompt_folded:
            continue
        if folded in ignored_exact:
            continue
        if (
            folded == "..."
            or folded.startswith("runtime:")
            or folded.startswith("backend:")
            or folded.startswith("model:")
            or folded.startswith("routing mode")
            or folded.startswith("ui-")
            or folded.startswith("request timed out")
            or folded.startswith("speed & battery:")
            or folded.startswith("prefill")
            or folded.startswith("generating")
            or folded.startswith("loading model")
            or folded.startswith("warming model")
            or folded.startswith("still working on this device")
        ):
            continue
        return True
    return False


def _extract_ui_runtime_fields(
    window_dump: str,
    *,
    prompt: str,
) -> tuple[str | None, str | None, str | None, bool, bool, bool, bool]:
    texts = [html.unescape(token).strip() for token in re.findall(r'text="([^"]*)"', window_dump)]
    runtime_status = next((token.split(":", 1)[1].strip() for token in texts if token.startswith("Runtime:")), None)
    backend = next((token.split(":", 1)[1].strip() for token in texts if token.startswith("Backend:")), None)
    active_model_id = next((token.split(":", 1)[1].strip() for token in texts if token.startswith("Model:")), None)
    placeholder_visible = any(token == "..." for token in texts)
    runtime_error_visible = any("UI-RUNTIME-001" in token for token in texts)
    timeout_message_visible = any("Request timed out" in token for token in texts)
    ui_response_visible = _extract_ui_response_visible(texts, prompt=prompt)
    return (
        runtime_status,
        backend,
        active_model_id,
        placeholder_visible,
        runtime_error_visible,
        timeout_message_visible,
        ui_response_visible,
    )


def _extract_chat_response_state(
    chat_snapshot: str,
    *,
    prompt: str | None = None,
) -> tuple[bool, bool, bool, str | None, bool, bool, str | None, str | None, bool]:
    if not chat_snapshot:
        return False, False, False, None, False, False, None, None, False

    match = re.search(r'<string name="chat_state_v2">(.*?)</string>', chat_snapshot, flags=re.DOTALL)
    if match is None:
        return False, False, False, None, False, False, None, None, False

    raw_json = html.unescape(match.group(1))
    try:
        payload = json.loads(raw_json)
    except json.JSONDecodeError:
        return False, False, False, None, False, False, None, None, False

    sessions = payload.get("sessions")
    if not isinstance(sessions, list) or not sessions:
        return False, False, False, None, False, False, None, None, False

    active_session_id = payload.get("activeSessionId")
    active = None
    if isinstance(active_session_id, str):
        active = next(
            (session for session in sessions if isinstance(session, dict) and session.get("id") == active_session_id),
            None,
        )
    if active is None and isinstance(sessions[-1], dict):
        active = sessions[-1]
    if not isinstance(active, dict):
        return False, False, False, None, False, False, None, None, False

    messages = active.get("messages")
    if not isinstance(messages, list) or not messages:
        return False, False, False, None, False, False, None, None, False

    typed_messages = [item for item in messages if isinstance(item, dict)]
    if not typed_messages:
        return False, False, False, None, False, False, None, None, False

    if prompt:
        prompt_folded = prompt.strip().lower()
        if prompt_folded:
            for index, message in enumerate(reversed(typed_messages)):
                role = str(message.get("role", "")).strip().lower()
                content = str(message.get("content", "")).strip().lower()
                if role == "user" and content == prompt_folded:
                    anchor = len(typed_messages) - index - 1
                    typed_messages = typed_messages[anchor:]
                    break

    if not typed_messages:
        return False, False, False, None, False, False, None, None, False

    latest = typed_messages[-1]
    latest_role = str(latest.get("role", "")).strip().lower()
    latest_content = str(latest.get("content", ""))
    latest_streaming = bool(latest.get("isStreaming"))

    placeholder_visible = latest_role == "assistant" and latest_streaming and latest_content.strip() == ""

    assistant_messages = [
        item
        for item in typed_messages
        if str(item.get("role", "")).strip().lower() == "assistant"
    ]
    first_token_seen = any(str(item.get("content", "")).strip() for item in assistant_messages)
    streaming_text_visible = any(
        bool(item.get("isStreaming")) and str(item.get("content", "")).strip()
        for item in assistant_messages
    )

    final_response = next(
        (
            item
            for item in reversed(typed_messages)
            if str(item.get("role", "")).strip().lower() in {"assistant", "system"}
            and not bool(item.get("isStreaming"))
            and str(item.get("content", "")).strip()
        ),
        None,
    )

    response_visible = final_response is not None
    response_non_empty = response_visible
    response_role = (
        str(final_response.get("role", "")).strip().lower()
        if isinstance(final_response, dict)
        else None
    )
    request_id = (
        str(final_response.get("requestId", "")).strip() or None
        if isinstance(final_response, dict)
        else None
    )
    finish_reason = (
        str(final_response.get("finishReason", "")).strip() or None
        if isinstance(final_response, dict)
        else None
    )
    terminal_event_seen = (
        bool(final_response.get("terminalEventSeen", False))
        if isinstance(final_response, dict)
        else False
    )
    return (
        placeholder_visible,
        streaming_text_visible,
        response_visible,
        response_role,
        response_non_empty,
        first_token_seen,
        request_id,
        finish_reason,
        terminal_event_seen,
    )


def _extract_first_session_progress(chat_snapshot: str) -> tuple[str | None, bool | None, bool | None, bool | None]:
    if not chat_snapshot:
        return None, None, None, None

    match = re.search(r'<string name="chat_state_v2">(.*?)</string>', chat_snapshot, flags=re.DOTALL)
    if match is None:
        return None, None, None, None

    raw_json = html.unescape(match.group(1))
    try:
        payload = json.loads(raw_json)
    except json.JSONDecodeError:
        return None, None, None, None

    first_session_stage = payload.get("firstSessionStage")
    stage_value = first_session_stage.strip() if isinstance(first_session_stage, str) and first_session_stage.strip() else None

    advanced_unlocked_raw = payload.get("advancedUnlocked")
    first_answer_completed_raw = payload.get("firstAnswerCompleted")
    follow_up_completed_raw = payload.get("followUpCompleted")

    advanced_unlocked = advanced_unlocked_raw if isinstance(advanced_unlocked_raw, bool) else None
    first_answer_completed = first_answer_completed_raw if isinstance(first_answer_completed_raw, bool) else None
    follow_up_completed = follow_up_completed_raw if isinstance(follow_up_completed_raw, bool) else None

    return stage_value, advanced_unlocked, first_answer_completed, follow_up_completed


def _capture_send_snapshot(
    *,
    context: RuntimeContext,
    serial: str,
    app_package: str,
    capture_root: Path,
    second: int,
    prompt: str,
) -> SendCaptureSnapshot:
    suffix = f"t{second:03d}"
    screenshot_path = capture_root / "screenshots" / f"screenshot-{suffix}.png"
    window_path = capture_root / "window-dumps" / f"window_dump_{suffix}.xml"
    state_dir = capture_root / "state"

    screenshot_ok = _capture_device_screenshot(context=context, serial=serial, local_path=screenshot_path)
    window_ok = _capture_window_dump(context=context, serial=serial, local_path=window_path)
    state_outputs = _capture_shared_pref_snapshots(
        context=context,
        serial=serial,
        app_package=app_package,
        snapshot_dir=state_dir,
        suffix=suffix,
    )

    runtime_status: str | None = None
    backend: str | None = None
    active_model_id: str | None = None
    placeholder_from_ui = False
    runtime_error_visible = False
    timeout_message_visible = False
    ui_response_visible = False
    if window_ok:
        window_text = window_path.read_text(encoding="utf-8", errors="replace")
        (
            runtime_status,
            backend,
            active_model_id,
            placeholder_from_ui,
            runtime_error_visible,
            timeout_message_visible,
            ui_response_visible,
        ) = _extract_ui_runtime_fields(
            window_text,
            prompt=prompt,
        )

    chat_snapshot_path = state_outputs.get("chat")
    placeholder_from_state = False
    streaming_text_visible = False
    response_visible = False
    response_role: str | None = None
    response_non_empty = False
    first_token_seen = False
    request_id: str | None = None
    finish_reason: str | None = None
    terminal_event_seen = False
    first_session_stage: str | None = None
    advanced_unlocked: bool | None = None
    first_answer_completed: bool | None = None
    follow_up_completed: bool | None = None
    if chat_snapshot_path is not None and chat_snapshot_path.exists():
        chat_text = chat_snapshot_path.read_text(encoding="utf-8", errors="replace")
        (
            placeholder_from_state,
            streaming_text_visible,
            response_visible,
            response_role,
            response_non_empty,
            first_token_seen,
            request_id,
            finish_reason,
            terminal_event_seen,
        ) = _extract_chat_response_state(
            chat_text,
            prompt=prompt,
        )
        (
            first_session_stage,
            advanced_unlocked,
            first_answer_completed,
            follow_up_completed,
        ) = _extract_first_session_progress(chat_text)

    ui_non_placeholder_response = ui_response_visible and not placeholder_from_ui
    response_visible = response_visible or ui_response_visible
    response_non_empty = response_non_empty or ui_non_placeholder_response
    first_token_seen = first_token_seen or ui_non_placeholder_response
    if response_visible and response_role is None:
        response_role = "assistant"

    return SendCaptureSnapshot(
        second=second,
        screenshot=_report_path(screenshot_path) if screenshot_ok else None,
        window_dump=_report_path(window_path) if window_ok else None,
        chat_state_snapshot=_report_path(chat_snapshot_path) if chat_snapshot_path else None,
        runtime_status=runtime_status,
        backend=backend,
        active_model_id=active_model_id,
        placeholder_visible=placeholder_from_ui or placeholder_from_state,
        runtime_error_visible=runtime_error_visible,
        timeout_message_visible=timeout_message_visible,
        streaming_text_visible=streaming_text_visible,
        response_visible=response_visible,
        response_role=response_role,
        response_non_empty=response_non_empty,
        first_token_seen=first_token_seen,
        request_id=request_id,
        finish_reason=finish_reason,
        terminal_event_seen=terminal_event_seen,
        first_session_stage=first_session_stage,
        advanced_unlocked=advanced_unlocked,
        first_answer_completed=first_answer_completed,
        follow_up_completed=follow_up_completed,
    )


def _normalize_qa13_text(value: str | None) -> str:
    normalized = (value or "").strip()
    return normalized if normalized else _QA13_UNKNOWN_VALUE


def _build_qa13_defaults_from_snapshot(snapshot: SendCaptureSnapshot | None) -> tuple[str, str, str, bool]:
    if snapshot is None:
        return (_QA13_UNKNOWN_VALUE, _QA13_UNKNOWN_VALUE, _QA13_UNKNOWN_VALUE, False)
    return (
        _normalize_qa13_text(snapshot.runtime_status),
        _normalize_qa13_text(snapshot.backend),
        _normalize_qa13_text(snapshot.active_model_id),
        bool(snapshot.placeholder_visible),
    )


def _run_send_capture_stage(
    *,
    context: RuntimeContext,
    maestro_bin: str,
    serial: str,
    app_package: str,
    run_root: Path,
    prompt: str,
    reply_timeout_seconds: int,
    capture_intervals: Sequence[int],
    mode: str,
    env: Mapping[str, str] | None = None,
) -> JourneyStepResult:
    effective_env = dict(env or context.env)
    capture_root = run_root / "send-capture"
    capture_root.mkdir(parents=True, exist_ok=True)
    debug_output_dir = run_root / "maestro-debug" / "send-capture-kickoff"
    debug_output_dir.mkdir(parents=True, exist_ok=True)

    kickoff_flow = capture_root / "send-kickoff.yaml"
    strict_mode = mode == "strict"
    fast_smoke_mode = mode == "fast-smoke"
    ready_wait_lines = [
        "- extendedWaitUntil:",
        "    visible: \"Runtime: Ready\"",
        f"    timeout: {reply_timeout_seconds * 1000}",
    ] if strict_mode else []
    runtime_clean_assert_lines = [
        "- assertNotVisible: \"UI-RUNTIME-001\"",
    ] if strict_mode else []
    kickoff_flow.write_text(
        "\n".join(
            [
                f"appId: {app_package}",
                "---",
                "- launchApp",
                "- takeScreenshot: \"send-kickoff-01-launch\"",
                "- runFlow:",
                "    when:",
                "      visible: \"Skip\"",
                "    commands:",
                "      - tapOn: \"Skip\"",
                "- runFlow:",
                "    when:",
                "      visible: \"Pocket GPT\"",
                "    commands:",
                "      - tapOn: \"Pocket GPT\"",
                "- runFlow:",
                "    when:",
                "      visible: \"PocketAgent\"",
                "    commands:",
                "      - tapOn: \"PocketAgent\"",
                "- runFlow:",
                "    when:",
                "      visible: \"Get ready\"",
                "    commands:",
                "      - tapOn: \"Get ready\"",
                "- runFlow:",
                "    when:",
                "      visible: \"Model provisioning\"",
                "    commands:",
                "      - back",
                "- runFlow:",
                "    when:",
                "      visible: \"Runtime: Error\"",
                "    commands:",
                "      - tapOn: \"Refresh runtime checks\"",
                "- runFlow:",
                "    when:",
                "      visible: \"Refresh runtime checks\"",
                "    commands:",
                "      - tapOn: \"Refresh runtime checks\"",
                *ready_wait_lines,
                *runtime_clean_assert_lines,
                "- runFlow:",
                "    when:",
                "      visible: \"Advanced\"",
                "    commands:",
                "      - tapOn: \"Advanced\"",
                "- runFlow:",
                "    when:",
                "      visible: \"Advanced controls\"",
                "    commands:",
                "      - tapOn: \"QWEN_0_8B\"",
                "      - back",
                "- takeScreenshot: \"send-kickoff-02-ready\"",
                "- tapOn: \"Message\"",
                "- takeScreenshot: \"send-kickoff-03-message-tab\"",
                "- eraseText",
                f"- inputText: {json.dumps(prompt)}",
                "- takeScreenshot: \"send-kickoff-04-typed\"",
                "- runFlow:",
                "    when:",
                "      notVisible: \"Send\"",
                "    commands:",
                "      - back",
                "- runFlow:",
                "    when:",
                "      visible: \"Pocket GPT\"",
                "    commands:",
                "      - tapOn: \"Pocket GPT\"",
                "      - tapOn: \"Message\"",
                "      - eraseText",
                f"      - inputText: {json.dumps(prompt)}",
                "- runFlow:",
                "    when:",
                "      visible: \"PocketAgent\"",
                "    commands:",
                "      - tapOn: \"PocketAgent\"",
                "      - tapOn: \"Message\"",
                "      - eraseText",
                f"      - inputText: {json.dumps(prompt)}",
                "- takeScreenshot: \"send-kickoff-05-before-send\"",
                "- tapOn: \"Send\"",
                f"- assertVisible: {json.dumps(prompt)}",
                "- takeScreenshot: \"send-kickoff-06-after-send\"",
            ]
        )
        + "\n",
        encoding="utf-8",
    )

    # Keep a bounded logcat slice focused on the send window.
    context.run(["adb", "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False, env=effective_env)
    context.run(["adb", "-s", serial, "shell", "wm", "dismiss-keyguard"], check=False, env=effective_env)
    context.run(["adb", "-s", serial, "shell", "am", "force-stop", app_package], check=False, env=effective_env)
    context.run(
        ["adb", "-s", serial, "shell", "run-as", app_package, "rm", "-f", "shared_prefs/pocketagent_chat_state.xml"],
        check=False,
        env=effective_env,
    )
    context.run(["adb", "-s", serial, "logcat", "-c"], check=False, env=effective_env)
    started = time.monotonic()
    kickoff_outputs: list[str] = []
    kickoff_result = None
    for attempt in range(2):
        context.run(["adb", "-s", serial, "forward", "--remove-all"], check=False, env=effective_env)
        context.run(["adb", "-s", serial, "shell", "am", "force-stop", app_package], check=False, env=effective_env)
        result = context.run(
            [maestro_bin, "--device", serial, "test", str(kickoff_flow), "--debug-output", str(debug_output_dir)],
            check=False,
            capture_output=True,
            env=effective_env,
            cwd=debug_output_dir,
        )
        combined = ((result.stdout or "") + "\n" + (result.stderr or "")).strip()
        kickoff_outputs.append(f"=== attempt {attempt + 1} ===\n{combined}\n")
        kickoff_result = result
        if result.returncode == 0:
            break
        if attempt == 0 and any(marker in combined for marker in _MAESTRO_TRANSIENT_FAILURE_MARKERS):
            print_step("send-capture kickoff hit transient Maestro launch failure; retrying once.")
            _run_maestro_transport_recovery(context=context, serial=serial, env=effective_env)
            time.sleep(1.5)
            continue
        break
    assert kickoff_result is not None
    kickoff_output = debug_output_dir / "maestro-output.txt"
    kickoff_output.write_text("".join(kickoff_outputs), encoding="utf-8")

    screenshots = _collect_maestro_screenshots(debug_output_dir)
    logcat_path = capture_root / "send-window-logcat.txt"

    if kickoff_result.returncode != 0:
        failure_capture = capture_root / "screenshots" / "send-kickoff-failure.png"
        if _capture_device_screenshot(context=context, serial=serial, local_path=failure_capture):
            screenshots.append(_report_path(failure_capture))
        kickoff_snapshot: SendCaptureSnapshot | None = None
        try:
            kickoff_snapshot = _capture_send_snapshot(
                context=context,
                serial=serial,
                app_package=app_package,
                capture_root=capture_root,
                second=0,
                prompt=prompt,
            )
            if kickoff_snapshot.screenshot:
                screenshots.append(kickoff_snapshot.screenshot)
        except Exception:  # noqa: BLE001 - kickoff diagnostics should not mask the original failure.
            kickoff_snapshot = None
        _capture_logcat(context, serial, logcat_path)
        combined = ((kickoff_result.stdout or "") + "\n" + (kickoff_result.stderr or "")).strip()
        failure_signature = combined.splitlines()[-1] if combined else f"kickoff_exit={kickoff_result.returncode}"
        elapsed_ms = int((time.monotonic() - started) * 1000)
        qa13_runtime_status, qa13_backend, qa13_model_id, qa13_placeholder = _build_qa13_defaults_from_snapshot(
            kickoff_snapshot
        )
        return JourneyStepResult(
            name="send-capture",
            status="failed",
            duration_seconds=elapsed_ms / 1000.0,
            details=f"Kickoff flow failed: {_report_path(kickoff_output)}",
            screenshots=screenshots,
            failure_signature=failure_signature,
            logcat=_report_path(logcat_path),
            phase="error",
            elapsed_ms=elapsed_ms,
            runtime_status=qa13_runtime_status,
            backend=qa13_backend,
            active_model_id=qa13_model_id,
            placeholder_visible=qa13_placeholder,
            mode=mode,
        )

    snapshots: list[SendCaptureSnapshot] = []
    capture_started = time.monotonic()
    terminal_capture_cutoff: int | None = None
    for second in capture_intervals:
        target = capture_started + float(second)
        wait_seconds = target - time.monotonic()
        if wait_seconds > 0:
            time.sleep(wait_seconds)
        snapshot = _capture_send_snapshot(
            context=context,
            serial=serial,
            app_package=app_package,
            capture_root=capture_root,
            second=second,
            prompt=prompt,
        )
        snapshots.append(snapshot)
        if snapshot.screenshot:
            screenshots.append(snapshot.screenshot)
        if (
            terminal_capture_cutoff is None
            and snapshot.terminal_event_seen
            and snapshot.response_visible
            and snapshot.response_non_empty
        ):
            terminal_capture_cutoff = min(
                reply_timeout_seconds,
                second + _SEND_CAPTURE_POST_TERMINAL_GRACE_SECONDS,
            )
        if (
            terminal_capture_cutoff is None
            and fast_smoke_mode
            and snapshot.first_token_seen
            and snapshot.response_visible
            and snapshot.response_non_empty
        ):
            # In fast-smoke mode, first non-empty assistant text is sufficient.
            # Stop early after a small grace window to keep feedback loops fast.
            terminal_capture_cutoff = min(
                reply_timeout_seconds,
                second + _SEND_CAPTURE_READY_GRACE_SECONDS,
            )
        if terminal_capture_cutoff is not None:
            has_more_within_cutoff = any(
                value > second and value <= terminal_capture_cutoff for value in capture_intervals
            )
            if not has_more_within_cutoff:
                break

    snapshots_payload_path = capture_root / "send-snapshots.json"
    snapshots_payload_path.write_text(
        json.dumps(
            [
                {
                    "second": snapshot.second,
                    "screenshot": snapshot.screenshot,
                    "window_dump": snapshot.window_dump,
                    "chat_state_snapshot": snapshot.chat_state_snapshot,
                    "runtime_status": snapshot.runtime_status,
                    "backend": snapshot.backend,
                    "active_model_id": snapshot.active_model_id,
                    "placeholder_visible": snapshot.placeholder_visible,
                    "runtime_error_visible": snapshot.runtime_error_visible,
                    "timeout_message_visible": snapshot.timeout_message_visible,
                    "streaming_text_visible": snapshot.streaming_text_visible,
                    "response_visible": snapshot.response_visible,
                    "response_role": snapshot.response_role,
                    "response_non_empty": snapshot.response_non_empty,
                    "first_token_seen": snapshot.first_token_seen,
                    "request_id": snapshot.request_id,
                    "finish_reason": snapshot.finish_reason,
                    "terminal_event_seen": snapshot.terminal_event_seen,
                    "first_session_stage": snapshot.first_session_stage,
                    "advanced_unlocked": snapshot.advanced_unlocked,
                    "first_answer_completed": snapshot.first_answer_completed,
                    "follow_up_completed": snapshot.follow_up_completed,
                }
                for snapshot in snapshots
            ],
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    _capture_logcat(context, serial, logcat_path)
    duration_seconds = time.monotonic() - started
    final = snapshots[-1]
    completion = next(
        (
            item
            for item in snapshots
            if item.response_visible
            and item.response_non_empty
            and item.response_role == "assistant"
            and not item.placeholder_visible
            and not item.runtime_error_visible
            and item.terminal_event_seen
        ),
        None,
    )
    first_token = next((item for item in snapshots if item.first_token_seen), None)
    terminal_snapshot = next(
        (
            item
            for item in snapshots
            if item.terminal_event_seen and item.response_visible and item.response_non_empty
        ),
        None,
    )
    utf8_error_snapshot = next(
        (
            item
            for item in snapshots
            if "utf8" in (item.finish_reason or "").strip().lower()
        ),
        None,
    )

    status = "passed"
    phase = "completed"
    failure_signature: str | None = None
    elapsed_ms = int(duration_seconds * 1000)
    first_token_ms = first_token.second * 1000 if first_token is not None else None
    completion_ms = completion.second * 1000 if completion is not None else None
    request_id = (
        completion.request_id
        if completion is not None
        else (terminal_snapshot.request_id if terminal_snapshot is not None else final.request_id)
    )
    finish_reason = (
        completion.finish_reason
        if completion is not None
        else (terminal_snapshot.finish_reason if terminal_snapshot is not None else final.finish_reason)
    )
    terminal_event_seen = terminal_snapshot is not None or bool(final.terminal_event_seen)

    if completion is not None and completion.second > reply_timeout_seconds:
        status = "failed"
        phase = "timeout"
        elapsed_ms = reply_timeout_seconds * 1000
        failure_signature = "completion_after_sla"
    elif completion is not None:
        elapsed_ms = completion.second * 1000
        if strict_mode:
            grace_limit = min(reply_timeout_seconds, completion.second + _SEND_CAPTURE_READY_GRACE_SECONDS)
            ready_window = [
                item
                for item in snapshots
                if completion.second <= item.second <= grace_limit
            ]
            ready_within_grace = any((item.runtime_status or "").strip().lower() == "ready" for item in ready_window)
            ready_window_has_signal = len(ready_window) > 1 or completion.second >= reply_timeout_seconds
            if ready_window_has_signal and not ready_within_grace:
                status = "failed"
                phase = "timeout"
                failure_signature = "completion_without_ready_within_grace"
            elif utf8_error_snapshot is not None:
                status = "failed"
                phase = "error"
                failure_signature = "utf8_stream_error"
        elif utf8_error_snapshot is not None:
            status = "failed"
            phase = "error"
            failure_signature = "utf8_stream_error"
    elif final.timeout_message_visible or final.runtime_error_visible:
        if fast_smoke_mode and final.timeout_message_visible and first_token is not None:
            status = "passed"
            phase = "timeout"
            elapsed_ms = reply_timeout_seconds * 1000
            failure_signature = None
        elif final.runtime_error_visible:
            status = "failed"
            phase = "error"
            elapsed_ms = reply_timeout_seconds * 1000
            failure_signature = "ui_runtime_error_visible_at_sla"
        else:
            status = "failed"
            phase = "timeout"
            elapsed_ms = reply_timeout_seconds * 1000
            if first_token is not None and not terminal_event_seen:
                failure_signature = "cancel_ack_missing"
            elif first_token is None:
                failure_signature = f"no_first_token_{reply_timeout_seconds}s"
            elif final.timeout_message_visible:
                failure_signature = "ui_timeout_message_visible_at_sla"
            else:
                failure_signature = "ui_runtime_error_visible_at_sla"
    elif first_token is not None and not terminal_event_seen:
        if fast_smoke_mode:
            status = "passed"
            phase = "first_token"
            elapsed_ms = first_token.second * 1000
            failure_signature = None
        else:
            status = "failed"
            phase = "first_token"
            elapsed_ms = first_token.second * 1000
            failure_signature = "no_terminal_event"
    elif utf8_error_snapshot is not None:
        status = "failed"
        phase = "error"
        failure_signature = "utf8_stream_error"
    elif final.placeholder_visible or ((final.runtime_status or "").strip().lower() == "loading"):
        if fast_smoke_mode and not final.runtime_error_visible and not final.timeout_message_visible:
            status = "passed"
            phase = "first_token"
            elapsed_ms = min(reply_timeout_seconds * 1000, int(duration_seconds * 1000))
            failure_signature = None
        else:
            status = "failed"
            phase = "timeout"
            elapsed_ms = reply_timeout_seconds * 1000
            failure_signature = f"placeholder_or_loading_persisted_at_{reply_timeout_seconds}s"
    else:
        status = "failed"
        phase = "error"
        failure_signature = "no_assistant_or_system_reply_observed"

    details_lines = [
        f"Prompt: {prompt}",
        f"Kickoff output: {_report_path(kickoff_output)}",
        f"Capture intervals (s): {', '.join(str(snapshot.second) for snapshot in snapshots)}",
        f"Snapshot timeline: {_report_path(snapshots_payload_path)}",
        f"State snapshots: {_report_path(capture_root / 'state')}",
    ]

    return JourneyStepResult(
        name="send-capture",
        status=status,
        duration_seconds=duration_seconds,
        details="\n".join(details_lines),
        screenshots=screenshots,
        failure_signature=failure_signature,
        logcat=_report_path(logcat_path),
        phase=phase,
        elapsed_ms=elapsed_ms,
        runtime_status=_normalize_qa13_text(final.runtime_status),
        backend=_normalize_qa13_text(final.backend),
        active_model_id=_normalize_qa13_text(final.active_model_id),
        placeholder_visible=final.placeholder_visible,
        response_visible=completion is not None or final.response_visible,
        response_role=completion.response_role if completion is not None else final.response_role,
        response_non_empty=completion.response_non_empty if completion is not None else final.response_non_empty,
        first_token_seen=first_token is not None,
        request_id=request_id,
        finish_reason=finish_reason,
        terminal_event_seen=terminal_event_seen,
        first_token_ms=first_token_ms,
        completion_ms=completion_ms,
        mode=mode,
        first_session_stage=final.first_session_stage,
        advanced_unlocked=final.advanced_unlocked,
        first_answer_completed=final.first_answer_completed,
        follow_up_completed=final.follow_up_completed,
    )

def _extract_summary_path(output: str) -> Path | None:
    matches = re.findall(r"Run summary written to ([^\n]+summary\.csv)", output)
    if not matches:
        return None
    summary_path = Path(matches[-1].strip())
    if not summary_path.is_absolute():
        summary_path = REPO_ROOT / summary_path
    return summary_path


def _read_loop_summary_rows(summary_path: Path) -> list[dict[str, str]]:
    if not summary_path.exists():
        raise DevctlError("DEVICE_ERROR", f"Loop summary file missing: {summary_path}")

    with summary_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        rows = [dict(row) for row in reader]

    if not rows:
        raise DevctlError("DEVICE_ERROR", f"Loop summary is empty: {summary_path}")
    return rows


def _scan_log_file(path: Path, patterns: Sequence[str]) -> bool:
    if not path.exists():
        return False

    text = path.read_text(encoding="utf-8", errors="replace")
    for pattern in patterns:
        try:
            if re.search(pattern, text):
                return True
        except re.error as exc:
            raise DevctlError("CONFIG_ERROR", f"Invalid regex pattern '{pattern}': {exc}") from exc
    return False


def _evaluate_loop_output(summary_path: Path, crash_patterns: Sequence[str], oom_patterns: Sequence[str]) -> None:
    rows = _read_loop_summary_rows(summary_path)
    failing_runs: list[str] = []

    for row in rows:
        run_id = row.get("run", "?")
        crash = (row.get("crash_detected") or "").strip().lower() == "true"
        oom = (row.get("oom_detected") or "").strip().lower() == "true"

        log_file = (row.get("log_file") or "").strip()
        if log_file:
            log_path = Path(log_file)
            if not log_path.is_absolute():
                log_path = REPO_ROOT / log_path
            crash = crash or _scan_log_file(log_path, crash_patterns)
            oom = oom or _scan_log_file(log_path, oom_patterns)

        if crash or oom:
            labels: list[str] = []
            if crash:
                labels.append("crash")
            if oom:
                labels.append("oom")
            failing_runs.append(f"{run_id} ({'+'.join(labels)})")

    if failing_runs:
        raise DevctlError(
            "DEVICE_ERROR",
            f"Crash/OOM signals detected in short-loop results: {', '.join(failing_runs)}",
        )


def parse_device_lane_args(raw_args: Sequence[str], default_scenario_command: Sequence[str]) -> DeviceLaneArgs:
    runs: int | None = None
    label: str | None = None
    framework = "both"
    framework_explicit = False
    scenario_command: list[str] = []

    args = list(raw_args)
    idx = 0
    while idx < len(args):
        token = args[idx]
        if token == "--":
            scenario_command = args[idx + 1 :]
            break

        if token == "--framework":
            if idx + 1 >= len(args):
                raise DevctlError("CONFIG_ERROR", "Missing value for --framework")
            framework = args[idx + 1].strip().lower()
            framework_explicit = True
            idx += 2
            continue

        if token.startswith("--framework="):
            framework = token.split("=", 1)[1].strip().lower()
            framework_explicit = True
            idx += 1
            continue

        if token.startswith("--"):
            raise DevctlError("CONFIG_ERROR", f"Unknown device lane flag: {token}")

        if runs is None:
            try:
                runs = int(token)
            except ValueError as exc:
                raise DevctlError("CONFIG_ERROR", f"Invalid runs value: {token}") from exc
            idx += 1
            continue

        if label is None:
            label = token
            idx += 1
            continue

        raise DevctlError("CONFIG_ERROR", f"Unexpected argument before '--': {token}")

    if runs is None:
        runs = 10
    if runs <= 0:
        raise DevctlError("CONFIG_ERROR", "runs must be > 0")

    if label is None:
        label = "scenario-a-stage-run"

    if not scenario_command:
        scenario_command = list(default_scenario_command)

    if framework not in {"espresso", "maestro", "both"}:
        raise DevctlError("CONFIG_ERROR", "framework must be one of: espresso|maestro|both")

    return DeviceLaneArgs(
        runs=runs,
        label=label,
        framework=framework,
        framework_explicit=framework_explicit,
        scenario_command=scenario_command,
    )


def _parse_journey_args(
    raw_args: Sequence[str],
    *,
    repeats_default: int,
    repeats_max: int,
    reply_timeout_default: int,
    capture_intervals_default: Sequence[int],
    prompt_default: str,
) -> argparse.Namespace:
    raw_arg_list = list(raw_args)
    timeout_explicit = any(
        token == "--reply-timeout-seconds" or token.startswith("--reply-timeout-seconds=")
        for token in raw_arg_list
    )
    capture_explicit = any(
        token == "--capture-intervals" or token.startswith("--capture-intervals=")
        for token in raw_arg_list
    )
    parser = argparse.ArgumentParser(prog="devctl lane journey")
    parser.add_argument("--repeats", type=int, default=repeats_default)
    parser.add_argument("--reply-timeout-seconds", type=int, default=reply_timeout_default)
    parser.add_argument(
        "--capture-intervals",
        type=str,
        default=",".join(str(value) for value in capture_intervals_default),
    )
    parser.add_argument("--prompt", type=str, default=prompt_default)
    parser.add_argument("--mode", choices={"strict", "valid-output", "fast-smoke"}, default="strict")
    parser.add_argument("--steps", type=str, default="instrumentation,send-capture")
    parser.add_argument("--maestro-flows", type=str, default="")
    parsed = parser.parse_args(raw_arg_list)
    if parsed.repeats <= 0:
        raise DevctlError("CONFIG_ERROR", "--repeats must be > 0")
    if parsed.repeats > repeats_max:
        raise DevctlError("CONFIG_ERROR", f"--repeats must be <= {repeats_max}")
    if parsed.reply_timeout_seconds <= 0:
        raise DevctlError("CONFIG_ERROR", "--reply-timeout-seconds must be > 0")

    if parsed.mode == "valid-output" and not timeout_explicit:
        parsed.reply_timeout_seconds = 480
    elif parsed.mode == "fast-smoke" and not timeout_explicit:
        parsed.reply_timeout_seconds = 60

    if parsed.mode == "valid-output" and not capture_explicit:
        capture_intervals = [0, 5, 15, 30, 60, 90, 120, 180, 240, 300, 420, parsed.reply_timeout_seconds]
    elif parsed.mode == "fast-smoke" and not capture_explicit:
        capture_intervals = [0, 5, 15, 30, parsed.reply_timeout_seconds]
    else:
        capture_intervals = _parse_capture_intervals(parsed.capture_intervals)
    capture_intervals = sorted({0, *capture_intervals})
    capture_intervals = [value for value in capture_intervals if value <= parsed.reply_timeout_seconds]
    if capture_intervals[-1] != parsed.reply_timeout_seconds:
        capture_intervals.append(parsed.reply_timeout_seconds)
    parsed.capture_intervals = capture_intervals

    prompt = parsed.prompt.strip()
    if not prompt:
        raise DevctlError("CONFIG_ERROR", "--prompt must not be empty")
    parsed.prompt = prompt

    parsed.steps = _parse_journey_steps(parsed.steps)
    parsed.maestro_flows = _parse_maestro_flows(parsed.maestro_flows)
    if parsed.maestro_flows and "maestro" not in parsed.steps:
        raise DevctlError("CONFIG_ERROR", "--maestro-flows requires --steps to include maestro")
    return parsed


def _parse_capture_intervals(raw: str) -> list[int]:
    values: list[int] = []
    for token in raw.split(","):
        value_raw = token.strip()
        if not value_raw:
            continue
        try:
            value = int(value_raw)
        except ValueError as exc:
            raise DevctlError("CONFIG_ERROR", f"Invalid capture interval: {value_raw}") from exc
        if value < 0:
            raise DevctlError("CONFIG_ERROR", f"Capture intervals must be >= 0, got {value}")
        values.append(value)
    if not values:
        raise DevctlError("CONFIG_ERROR", "--capture-intervals must include at least one integer value")
    return values


def _parse_journey_steps(raw: str) -> list[str]:
    if not raw.strip():
        raise DevctlError("CONFIG_ERROR", "--steps must not be empty")
    allowed = {"instrumentation", "send-capture", "maestro"}
    ordered = ["instrumentation", "send-capture", "maestro"]
    requested = {token.strip().lower() for token in raw.split(",") if token.strip()}
    if not requested:
        raise DevctlError("CONFIG_ERROR", "--steps must include at least one step")
    unknown = sorted(requested - allowed)
    if unknown:
        raise DevctlError("CONFIG_ERROR", f"Unknown journey step(s): {', '.join(unknown)}")
    return [step for step in ordered if step in requested]


def _parse_maestro_flows(raw: str) -> list[str]:
    if not raw.strip():
        return []
    return [token.strip() for token in raw.split(",") if token.strip()]


def _parse_maestro_tags(raw: str) -> list[str]:
    if not raw.strip():
        return []
    return [token.strip() for token in raw.split(",") if token.strip()]


def _parse_maestro_lane_args(raw_args: Sequence[str]) -> MaestroLaneArgs:
    parser = argparse.ArgumentParser(prog="devctl lane maestro")
    parser.add_argument("--flows", type=str, default="")
    parser.add_argument("--include-tags", type=str, default="")
    parser.add_argument("--exclude-tags", type=str, default="")
    parsed = parser.parse_args(list(raw_args))
    flows = _parse_maestro_flows(parsed.flows)
    include_tags = _parse_maestro_tags(parsed.include_tags)
    exclude_tags = _parse_maestro_tags(parsed.exclude_tags)
    overlap = sorted(set(include_tags) & set(exclude_tags))
    if overlap:
        raise DevctlError("CONFIG_ERROR", f"Maestro include/exclude tags overlap: {', '.join(overlap)}")
    return MaestroLaneArgs(flows=flows, include_tags=include_tags, exclude_tags=exclude_tags)


def _load_maestro_flow_tags(flow_path: Path) -> set[str]:
    if yaml is None:
        return set()
    try:
        docs = list(yaml.safe_load_all(flow_path.read_text(encoding="utf-8")))
    except Exception:
        return set()
    payload = next((doc for doc in docs if isinstance(doc, dict)), None)
    if not isinstance(payload, dict):
        return set()
    tags = payload.get("tags")
    if not isinstance(tags, list):
        return set()
    return {str(tag).strip() for tag in tags if str(tag).strip()}


def _filter_maestro_flows_by_tags(
    flow_paths: Sequence[Path],
    *,
    include_tags: Sequence[str],
    exclude_tags: Sequence[str],
) -> list[Path]:
    if not include_tags and not exclude_tags:
        return list(flow_paths)
    include = set(include_tags)
    exclude = set(exclude_tags)
    selected: list[Path] = []
    for flow_path in flow_paths:
        flow_tags = _load_maestro_flow_tags(flow_path)
        if include and not include.issubset(flow_tags):
            continue
        if exclude and flow_tags.intersection(exclude):
            continue
        selected.append(flow_path)
    return selected


def _resolve_maestro_flow_selection(available_flows: Sequence[str], selected_tokens: Sequence[str]) -> list[str]:
    if not selected_tokens:
        return list(available_flows)

    resolved: list[str] = []
    for token in selected_tokens:
        if token in available_flows:
            resolved.append(token)
            continue
        normalized = token.removesuffix(".yaml")
        match = next(
            (
                flow
                for flow in available_flows
                if Path(flow).stem == normalized or flow.endswith(f"/{normalized}.yaml")
            ),
            None,
        )
        if match is None:
            raise DevctlError("CONFIG_ERROR", f"Unknown Maestro flow selection: {token}")
        resolved.append(match)
    return resolved


def _write_runtime_log_signal_artifacts(logcat_path: Path) -> tuple[str, str, dict[str, Any]] | None:
    resolved_logcat = logcat_path if logcat_path.is_absolute() else (REPO_ROOT / logcat_path)
    if not resolved_logcat.exists():
        return None
    base_path = resolved_logcat.with_name(f"{resolved_logcat.stem}-runtime-log-signals")
    json_path = base_path.with_suffix(".json")
    markdown_path = base_path.with_suffix(".md")
    analysis = write_runtime_log_signal_reports(
        logcat_path=resolved_logcat,
        json_output_path=json_path,
        markdown_output_path=markdown_path,
    )
    return _report_path(json_path), _report_path(markdown_path), analysis


def _write_journey_report(
    *,
    report_path: Path,
    summary_path: Path,
    serial: str,
    steps: Sequence[JourneyStepResult],
) -> None:
    run_owner = os.environ.get("POCKETGPT_RUN_OWNER") or os.environ.get("USER") or os.environ.get("USERNAME") or "unknown"
    run_host = os.environ.get("HOSTNAME") or os.uname().nodename
    step_signal_reports: dict[str, tuple[str, str, dict[str, Any]]] = {}
    for step in steps:
        if not step.logcat:
            continue
        report = _write_runtime_log_signal_artifacts(Path(step.logcat))
        if report is not None:
            step_signal_reports[step.name] = report
    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "serial": serial,
        "run_owner": run_owner,
        "run_host": run_host,
        "runtime_log_signal_reports": [
            {
                "step": step.name,
                "json_report": step_signal_reports[step.name][0],
                "markdown_report": step_signal_reports[step.name][1],
                "status": step_signal_reports[step.name][2]["status"],
                "issue_count": step_signal_reports[step.name][2]["issue_count"],
            }
            for step in steps
            if step.name in step_signal_reports
        ],
        "steps": [
            {
                "name": step.name,
                "status": step.status,
                "duration_seconds": round(step.duration_seconds, 2),
                "details": step.details,
                "screenshots": step.screenshots or [],
                "failure_signature": step.failure_signature,
                "logcat": step.logcat,
                "runtime_log_signals_json": step_signal_reports[step.name][0] if step.name in step_signal_reports else None,
                "runtime_log_signals_markdown": step_signal_reports[step.name][1] if step.name in step_signal_reports else None,
                "runtime_log_signals_status": step_signal_reports[step.name][2]["status"] if step.name in step_signal_reports else None,
                "runtime_log_signals_issue_count": step_signal_reports[step.name][2]["issue_count"] if step.name in step_signal_reports else 0,
                "phase": step.phase,
                "elapsed_ms": step.elapsed_ms,
                "runtime_status": step.runtime_status,
                "backend": step.backend,
                "active_model_id": step.active_model_id,
                "placeholder_visible": step.placeholder_visible,
                "response_visible": step.response_visible,
                "response_role": step.response_role,
                "response_non_empty": step.response_non_empty,
                "first_token_seen": step.first_token_seen,
                "request_id": step.request_id,
                "finish_reason": step.finish_reason,
                "terminal_event_seen": step.terminal_event_seen,
                "first_token_ms": step.first_token_ms,
                "completion_ms": step.completion_ms,
                "mode": step.mode,
                "first_session_stage": step.first_session_stage,
                "advanced_unlocked": step.advanced_unlocked,
                "first_answer_completed": step.first_answer_completed,
                "follow_up_completed": step.follow_up_completed,
            }
            for step in steps
        ],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Journey Summary",
        "",
        f"- Device: `{serial}`",
        f"- Run owner: `{run_owner}`",
        f"- Run host: `{run_host}`",
        f"- Generated: `{payload['generated_at']}`",
        "",
        "| Step | Mode | Phase | Status | Duration (s) | Elapsed (ms) | Runtime | Backend | Model | Placeholder | Response | Role | Non-empty | First token | Request ID | Finish reason | Terminal | First token ms | Completion ms | First-session stage | Advanced unlocked | First answer done | Follow-up done |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for step in steps:
        signal_report = step_signal_reports.get(step.name)
        lines.append(
            "| "
            f"{step.name} | {step.mode or '-'} | {step.phase or '-'} | {step.status} | {step.duration_seconds:.2f} | "
            f"{step.elapsed_ms if step.elapsed_ms is not None else '-'} | "
            f"{step.runtime_status or '-'} | {step.backend or '-'} | {step.active_model_id or '-'} | "
            f"{step.placeholder_visible if step.placeholder_visible is not None else '-'} | "
            f"{step.response_visible if step.response_visible is not None else '-'} | "
            f"{step.response_role or '-'} | "
            f"{step.response_non_empty if step.response_non_empty is not None else '-'} | "
            f"{step.first_token_seen if step.first_token_seen is not None else '-'} | "
            f"{step.request_id or '-'} | "
            f"{step.finish_reason or '-'} | "
            f"{step.terminal_event_seen if step.terminal_event_seen is not None else '-'} | "
            f"{step.first_token_ms if step.first_token_ms is not None else '-'} | "
            f"{step.completion_ms if step.completion_ms is not None else '-'} | "
            f"{step.first_session_stage or '-'} | "
            f"{step.advanced_unlocked if step.advanced_unlocked is not None else '-'} | "
            f"{step.first_answer_completed if step.first_answer_completed is not None else '-'} | "
            f"{step.follow_up_completed if step.follow_up_completed is not None else '-'} |"
        )
        if step.details:
            lines.append(f"- Details: {step.details}")
        if step.failure_signature:
            lines.append(f"- Failure signature: `{step.failure_signature}`")
        if step.logcat:
            lines.append(f"- Logcat: `{step.logcat}`")
        if signal_report is not None:
            lines.append(
                f"- Runtime log signals: `{signal_report[1]}` (status={signal_report[2]['status']}, issues={signal_report[2]['issue_count']})",
            )
        if step.screenshots:
            lines.append("- Screenshots:")
            for shot in step.screenshots:
                lines.append(f"  - `{shot}`")
        lines.append("")
    summary_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")

def _normalize_test_mode(mode: str, context: RuntimeContext) -> str:
    canonical = mode.strip().lower()
    aliases = context.configs.test_profiles.aliases
    canonical = aliases.get(canonical, canonical)
    if canonical not in context.configs.test_profiles.profiles:
        allowed = sorted(set(context.configs.test_profiles.profiles.keys()) | set(aliases.keys()))
        raise DevctlError("CONFIG_ERROR", f"Unsupported test mode '{mode}'. Allowed: {', '.join(allowed)}")
    return canonical


def _collect_changed_files(context: RuntimeContext) -> list[str]:
    result = context.run(["git", "status", "--porcelain"], check=False, capture_output=True, env=context.env)
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        raise DevctlError("CONFIG_ERROR", f"Unable to detect changed files via git status.\n{stderr}")

    changed: list[str] = []
    for line in (result.stdout or "").splitlines():
        if not line:
            continue
        raw_path = line[3:].strip()
        if " -> " in raw_path:
            raw_path = raw_path.split(" -> ", 1)[1].strip()
        if raw_path:
            changed.append(raw_path)
    return sorted(set(changed))


def _path_matches(path: str, pattern: str) -> bool:
    normalized = path.replace("\\", "/")
    normalized_pattern = pattern.replace("\\", "/")
    return fnmatch.fnmatch(normalized, normalized_pattern)


def _select_gradle_tasks_for_changed_files(
    changed_files: Sequence[str],
    context: RuntimeContext,
) -> tuple[list[str], list[str], bool]:
    selected_tasks: set[str] = set()
    recommended_lanes: set[str] = set()
    include_android = False

    rules = context.configs.test_selection.rules
    for path in changed_files:
        for rule in rules:
            if _path_matches(path, rule.pattern):
                selected_tasks.update(rule.gradle_tasks)
                recommended_lanes.update(rule.recommended_lanes)
                include_android = include_android or bool(rule.include_android_unit)

    return sorted(selected_tasks), sorted(recommended_lanes), include_android


def _resolve_auto_fallback_profile(context: RuntimeContext) -> str:
    fallback = context.configs.test_selection.defaults.fallback_profile
    aliases = context.configs.test_profiles.aliases
    fallback = aliases.get(fallback, fallback)
    if fallback not in context.configs.test_profiles.profiles:
        raise DevctlError("CONFIG_ERROR", f"Unknown fallback profile configured for test auto mode: {fallback}")
    return fallback


def _write_recommendation_file(
    context: RuntimeContext,
    profile: str,
    changed_files: Sequence[str],
    selected_tasks: Sequence[str],
    recommended_lanes: Sequence[str],
) -> Path:
    rel = context.configs.test_profiles.defaults.recommendation_output
    output_path = REPO_ROOT / rel
    output_path.parent.mkdir(parents=True, exist_ok=True)

    lines = [
        "# Devctl Lane Recommendations",
        "",
        f"- Generated: {datetime.now().isoformat(timespec='seconds')}",
        f"- Profile: {profile}",
        f"- Changed files: {len(changed_files)}",
        "",
        "## Changed Files",
    ]
    if changed_files:
        lines.extend([f"- {path}" for path in changed_files])
    else:
        lines.append("- (none detected)")

    lines.extend(["", "## Selected Gradle Tasks"])
    if selected_tasks:
        lines.extend([f"- `{task}`" for task in selected_tasks])
    else:
        lines.append("- (none)")

    lines.extend(["", "## Recommended Follow-up Lanes"])
    if recommended_lanes:
        lines.extend([f"- `{lane}`" for lane in recommended_lanes])
    else:
        lines.append("- (none)")

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output_path


def _load_stage2_run_meta(run_dir: Path) -> dict[str, str]:
    meta_path = run_dir / "stage2-run-meta.env"
    if not meta_path.exists():
        return {}
    meta: dict[str, str] = {}
    for line in meta_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        meta[key.strip()] = value.strip()
    return meta


def _read_csv_scenarios(path: Path) -> set[str]:
    if not path.exists():
        return set()
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        scenarios: set[str] = set()
        for row in reader:
            scenario = (row.get("scenario") or "").strip().upper()
            if scenario:
                scenarios.add(scenario)
    return scenarios


def _write_report(path: Path, text: str) -> None:
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def _lane_test_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    default_profile = context.configs.test_profiles.defaults.default_profile
    raw_mode = raw_args[0] if raw_args else default_profile
    if len(raw_args) > 1:
        raise DevctlError(
            "CONFIG_ERROR",
            "Usage: devctl lane test [fast|core|merge|auto|full|quick|ci]",
        )
    mode = _normalize_test_mode(raw_mode, context)

    lane_cfg = context.configs.lanes.lanes.test
    profile_cfg = context.configs.test_profiles.profiles[mode]

    for step in lane_cfg.commands:
        context.run(step.argv, check=True, env=context.env)

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        print_step("Android SDK not configured; running host/JVM test lane only.")

    gradle_tasks: list[str] = list(lane_cfg.common_tasks)
    recommended_lanes: list[str] = []
    changed_files: list[str] = []
    include_android_from_selection = False

    if profile_cfg.use_changed_selection:
        changed_files = _collect_changed_files(context)
        selected_tasks, recommended_lanes, include_android_from_selection = _select_gradle_tasks_for_changed_files(
            changed_files,
            context,
        )
        if selected_tasks:
            gradle_tasks = selected_tasks
        else:
            fallback = _resolve_auto_fallback_profile(context)
            gradle_tasks = list(context.configs.test_profiles.profiles[fallback].extra_gradle_tasks) or list(
                lane_cfg.common_tasks
            )
            print_step(
                f"No changed-file rule matched for profile '{mode}'. Falling back to '{fallback}' Gradle task set."
            )

        recommendation_path = _write_recommendation_file(
            context=context,
            profile=mode,
            changed_files=changed_files,
            selected_tasks=gradle_tasks,
            recommended_lanes=recommended_lanes,
        )
        print_step(f"Wrote lane recommendations to {recommendation_path}")

    for extra_task in profile_cfg.extra_gradle_tasks:
        if extra_task not in gradle_tasks:
            gradle_tasks.append(extra_task)

    include_android_tasks = profile_cfg.include_android_when_available and android_configured
    if include_android_from_selection and android_configured:
        include_android_tasks = True
    if include_android_tasks:
        for task in lane_cfg.android_tasks:
            if task not in gradle_tasks:
                gradle_tasks.append(task)

    gradle_command = build_gradle_test_command(
        gradle_binary=lane_cfg.gradle_binary,
        gradle_flags=lane_cfg.gradle_flags,
        gradle_tasks=gradle_tasks,
        clean=bool(profile_cfg.clean),
    )
    context.run(gradle_command, check=True, env=resolved_env)


def _lane_android_instrumented_impl(raw_args: Sequence[str], context: RuntimeContext, strict: bool = True) -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane android-instrumented")

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        message = "Android SDK not configured for instrumentation lane."
        if strict:
            raise DevctlError("ENVIRONMENT_ERROR", message)
        print_step(f"Skipping instrumentation lane: {message}")
        return

    serial = _ensure_serial(context)
    with _device_lock(serial, owner="lane:android-instrumented"):
        device_env = _with_target_device_env(resolved_env, serial)
        _run_device_health_preflight(context, serial)
        lane_cfg = context.configs.lanes.lanes.android_instrumented
        artifact_root = _resolve_lane_artifact_dir(lane_cfg.artifacts.output_dir_template, serial, "android-instrumented")
        artifact_root.mkdir(parents=True, exist_ok=True)
        print_step(f"android-instrumented artifacts: {artifact_root}")
        runner_args: dict[str, str] = {}
        preflight_prepared = False

        for step in lane_cfg.commands:
            command = _append_native_build_flag(step.argv, env=device_env)
            if _run_serialized_gradle_install_step(
                context=context,
                command=command,
                serial=serial,
                env=device_env,
            ):
                continue
            is_connected_step = "connected" in step.name.lower() or any(
                "connected" in token and "androidtest" in token.lower()
                for token in command
            )
            if not is_connected_step:
                context.run(command, check=True, env=device_env)
                continue

            if not preflight_prepared:
                preflight = prepare_real_runtime_env(context, serial, artifact_root=artifact_root)
                runner_args = _instrumentation_args_from_model_paths(preflight.model_device_paths_by_id)
                preflight_prepared = True
            command = _append_gradle_instrumentation_args(command, runner_args)
            context.run(command, check=True, env=device_env)

        _capture_logcat(context, serial, artifact_root / context.configs.lanes.lanes.real_runtime.logcat_file_name)


def _run_maestro_flow(
    *,
    context: RuntimeContext,
    maestro_bin: str,
    serial: str,
    app_package: str | None = None,
    flow_path: Path,
    debug_output_dir: Path,
    env: Mapping[str, str] | None = None,
) -> JourneyStepResult:
    debug_output_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    result = None
    attempt_outputs: list[str] = []
    run_env = env or context.env
    for attempt in range(2):
        # Clear stale adb forwards that can block Maestro TcpForwarder sessions on long-lived devices.
        context.run(
            ["adb", "-s", serial, "forward", "--remove-all"],
            check=False,
            capture_output=True,
            env=run_env,
        )
        if app_package and attempt > 0:
            context.run(
                ["adb", "-s", serial, "shell", "am", "force-stop", app_package],
                check=False,
                capture_output=True,
                env=run_env,
            )
        run_result = context.run(
            [maestro_bin, "--device", serial, "test", str(flow_path), "--debug-output", str(debug_output_dir)],
            check=False,
            capture_output=True,
            env=run_env,
            cwd=debug_output_dir,
        )
        combined = ((run_result.stdout or "") + "\n" + (run_result.stderr or "")).strip()
        attempt_outputs.append(f"=== attempt {attempt + 1} ===\n{combined}\n")
        result = run_result
        if run_result.returncode == 0:
            break
        if attempt == 0 and any(marker in combined for marker in _MAESTRO_TRANSIENT_FAILURE_MARKERS):
            print_step(f"maestro flow {flow_path.stem} hit transient launch failure; retrying once.")
            _run_maestro_transport_recovery(context=context, serial=serial, env=run_env)
            time.sleep(1.5)
            continue
        break
    assert result is not None
    duration = time.monotonic() - started
    output_path = debug_output_dir / "maestro-output.txt"
    output_path.write_text("".join(attempt_outputs), encoding="utf-8")
    screenshots = _collect_maestro_screenshots(debug_output_dir)

    status = "passed" if result.returncode == 0 else "failed"
    failure_signature: str | None = None
    if result.returncode != 0:
        combined = ((result.stdout or "") + "\n" + (result.stderr or "")).strip()
        failure_signature = combined.splitlines()[-1] if combined else f"exit={result.returncode}"
        device_failure_path = f"/sdcard/{_sanitize_file_token(flow_path.stem)}-failure.png"
        local_failure_path = debug_output_dir / "failure-screenshot.png"
        context.run(
            ["adb", "-s", serial, "shell", "screencap", "-p", device_failure_path],
            check=False,
            env=run_env,
        )
        context.run(
            ["adb", "-s", serial, "pull", device_failure_path, str(local_failure_path)],
            check=False,
            env=run_env,
        )
        if local_failure_path.exists():
            try:
                screenshots.append(str(local_failure_path.relative_to(REPO_ROOT)))
            except ValueError:
                screenshots.append(str(local_failure_path))

    return JourneyStepResult(
        name=f"maestro:{flow_path.stem}",
        status=status,
        duration_seconds=duration,
        details=f"Debug output: {debug_output_dir}",
        screenshots=screenshots,
        failure_signature=failure_signature,
        phase="completed" if status == "passed" else "error",
        elapsed_ms=int(duration * 1000),
    )


def _lane_maestro_impl(raw_args: Sequence[str], context: RuntimeContext, strict: bool = True) -> None:
    args = _parse_maestro_lane_args(raw_args)

    maestro_bin = shutil.which("maestro")
    if maestro_bin is None:
        message = "Maestro CLI is not installed. Install with: curl -Ls https://get.maestro.mobile.dev | bash"
        if strict:
            raise DevctlError("ENVIRONMENT_ERROR", message)
        print_step(f"Skipping Maestro lane: {message}")
        return

    serial = _ensure_serial(context)
    with _device_lock(serial, owner="lane:maestro"):
        device_env = _with_target_device_env(context.env, serial)
        lane_cfg = context.configs.lanes.lanes.maestro
        real_runtime_cfg = context.configs.lanes.lanes.real_runtime
        _run_device_health_preflight(context, serial, app_package=real_runtime_cfg.app_package)
        artifact_root = _resolve_lane_artifact_dir(lane_cfg.artifacts.output_dir_template, serial, "maestro")
        artifact_root.mkdir(parents=True, exist_ok=True)
        debug_root = artifact_root / real_runtime_cfg.maestro_debug_dir_name
        debug_root.mkdir(parents=True, exist_ok=True)
        print_step(f"maestro artifacts: {artifact_root}")

        # Ensure instrumentation package exists before real-runtime provisioning sanity probe.
        install_test_command = _append_native_build_flag(
            ["./gradlew", "--no-daemon", ":apps:mobile-android:installDebugAndroidTest"],
            env=device_env,
        )
        if not _run_serialized_gradle_install_step(
            context=context,
            command=install_test_command,
            serial=serial,
            env=device_env,
        ):
            context.run(install_test_command, check=True, env=device_env)

        for command in lane_cfg.preflight_commands:
            prepared = _append_native_build_flag(command, env=device_env)
            if _run_serialized_gradle_install_step(
                context=context,
                command=prepared,
                serial=serial,
                env=device_env,
            ):
                continue
            context.run(prepared, check=True, env=device_env)

        prepare_real_runtime_env(context, serial, artifact_root=artifact_root)

        selected_flows = _resolve_maestro_flow_selection(lane_cfg.flows, args.flows)
        selected_flow_paths = [REPO_ROOT / flow for flow in selected_flows]
        selected_flow_paths = _filter_maestro_flows_by_tags(
            selected_flow_paths,
            include_tags=args.include_tags,
            exclude_tags=args.exclude_tags,
        )
        if not selected_flow_paths:
            raise DevctlError("CONFIG_ERROR", "No Maestro flows selected.")

        for flow_path in selected_flow_paths:
            if not flow_path.exists():
                raise DevctlError("CONFIG_ERROR", f"Missing Maestro flow: {flow_path}")
            step = _run_maestro_flow(
                context=context,
                maestro_bin=maestro_bin,
                serial=serial,
                app_package=real_runtime_cfg.app_package,
                flow_path=flow_path,
                debug_output_dir=debug_root / flow_path.stem,
                env=device_env,
            )
            if step.status != "passed":
                _capture_logcat(context, serial, artifact_root / real_runtime_cfg.logcat_file_name)
                raise DevctlError(
                    "DEVICE_ERROR",
                    f"Maestro flow failed: {flow_path}. Failure signature: {step.failure_signature}",
                )

        _capture_logcat(context, serial, artifact_root / real_runtime_cfg.logcat_file_name)


def _lane_screenshot_pack_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    args = _parse_screenshot_pack_args(raw_args)
    maestro_bin = shutil.which("maestro")
    if maestro_bin is None:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            "Maestro CLI is not installed. Install with: curl -Ls https://get.maestro.mobile.dev | bash",
        )

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        raise DevctlError("ENVIRONMENT_ERROR", "Android SDK not configured for screenshot-pack lane.")

    inventory = _load_screenshot_inventory()
    serial = _ensure_serial(context)
    with _device_lock(serial, owner="lane:screenshot-pack"):
        device_env = _with_target_device_env(resolved_env, serial)
        real_runtime_cfg = context.configs.lanes.lanes.real_runtime
        _run_device_health_preflight(context, serial, app_package=real_runtime_cfg.app_package)

        date_value = datetime.now().strftime("%Y-%m-%d")
        stamp = _now_stamp()
        artifact_root = build_artifact_dir(
            "scripts/benchmarks/runs/{date}/{device}/{label}/{stamp}",
            date_value,
            serial,
            "screenshot-pack",
            stamp,
        )
        artifact_root.mkdir(parents=True, exist_ok=True)
        instrumented_pull_dir = artifact_root / "instrumented-pull"
        instrumented_dir = artifact_root / "instrumented"
        maestro_dir = artifact_root / "maestro"
        combined_dir = artifact_root / "combined"
        report_json_path = artifact_root / "inventory-report.json"
        report_md_path = artifact_root / "inventory-report.md"
        maestro_debug_root = artifact_root / "maestro-debug"
        maestro_debug_root.mkdir(parents=True, exist_ok=True)
        print_step(f"screenshot-pack artifacts: {artifact_root}")

        install_debug = _append_native_build_flag(
            ["./gradlew", "--no-daemon", ":apps:mobile-android:installDebug"],
            env=device_env,
        )
        install_debug_android_test = _append_native_build_flag(
            ["./gradlew", "--no-daemon", ":apps:mobile-android:installDebugAndroidTest"],
            env=device_env,
        )
        if not _run_serialized_gradle_install_step(
            context=context,
            command=install_debug,
            serial=serial,
            env=device_env,
        ):
            context.run(install_debug, check=True, env=device_env)
        if not _run_serialized_gradle_install_step(
            context=context,
            command=install_debug_android_test,
            serial=serial,
            env=device_env,
        ):
            context.run(install_debug_android_test, check=True, env=device_env)

        preflight = prepare_real_runtime_env(context, serial, artifact_root=artifact_root)
        runner_args = _instrumentation_args_from_model_paths(preflight.model_device_paths_by_id)
        screenshot_pack_root = f"/sdcard/Download/pocketgpt-screenshot-pack/{stamp}"
        device_screenshot_dir = f"{screenshot_pack_root}/primary"
        device_screenshot_fallback_dir = f"{screenshot_pack_root}/fallback"
        device_screenshot_app_external_dir = f"/sdcard/Android/data/{real_runtime_cfg.app_package}/files/screenshot-pack"
        runner_args["screenshot_pack_dir"] = device_screenshot_dir
        runner_args["screenshot_pack_fallback_dir"] = device_screenshot_fallback_dir
        instrumentation_warnings: list[str] = []

        def _clear_device_screenshot_outputs() -> None:
            _run_remote_shell_script(
                context=context,
                serial=serial,
                script=(
                    f"rm -rf {_shell_single_quote(screenshot_pack_root)} "
                    f"{_shell_single_quote(device_screenshot_app_external_dir)}"
                ),
            )

        def _pull_instrumented_outputs(phase: str, *, required: bool) -> list[str]:
            pulled_remote_dirs: list[str] = []
            phase_pull_dir = instrumented_pull_dir / phase
            phase_pull_dir.mkdir(parents=True, exist_ok=True)
            for remote_dir in (
                device_screenshot_dir,
                device_screenshot_fallback_dir,
                device_screenshot_app_external_dir,
            ):
                if not _remote_path_exists(context, serial, remote_dir):
                    continue
                context.run(
                    ["adb", "-s", serial, "pull", remote_dir, str(phase_pull_dir)],
                    check=False,
                    env=context.env,
                )
                pulled_remote_dirs.append(remote_dir)

            if not pulled_remote_dirs:
                fallback_root = "/sdcard/Download/pocketgpt-screenshot-pack"
                find_result = _run_remote_shell_script(
                    context=context,
                    serial=serial,
                    script=(
                        f"if [ -d {_shell_single_quote(fallback_root)} ]; then "
                        f"find {_shell_single_quote(fallback_root)} -type f -name '*.png' | head -n 1; "
                        "fi"
                    ),
                )
                if (find_result.stdout or "").strip():
                    context.run(
                        ["adb", "-s", serial, "pull", fallback_root, str(phase_pull_dir)],
                        check=False,
                        env=context.env,
                    )
                    pulled_remote_dirs.append(fallback_root)

            if not pulled_remote_dirs and required:
                raise DevctlError(
                    "DEVICE_ERROR",
                    "Instrumentation screenshot output directory not found on device. "
                    "Checked: "
                    f"{device_screenshot_dir}, {device_screenshot_fallback_dir}, {device_screenshot_app_external_dir}",
                )

            _copy_pngs_flat(phase_pull_dir, instrumented_dir)
            return pulled_remote_dirs

        def _run_screenshot_instrumentation(*, deterministic: bool, required_outputs: bool) -> None:
            phase_label = "deterministic-subset" if deterministic else "full-expansion"
            selector = _screenshot_pack_selector(deterministic=deterministic)
            _clear_device_screenshot_outputs()
            context.run(
                ["adb", "-s", serial, "shell", "pm", "clear", real_runtime_cfg.app_package],
                check=False,
                env=context.env,
            )
            try:
                _run_instrumentation_class(
                    context=context,
                    serial=serial,
                    test_class=selector,
                    runner=preflight.instrumentation_runner or real_runtime_cfg.instrumentation_runner,
                    args=runner_args,
                    timeout_seconds=real_runtime_cfg.startup_probe_timeout_seconds,
                )
            except DevctlError as exc:
                if args.product_signal_only and _is_screenshot_pack_harness_noise(exc.message):
                    warning = f"{phase_label} instrumentation harness noise: {exc.message.splitlines()[-1]}"
                    print_step(f"Screenshot-pack warning: {warning}")
                    instrumentation_warnings.append(warning)
                    _pull_instrumented_outputs(phase_label, required=False)
                    _clear_device_screenshot_outputs()
                    return
                raise
            _pull_instrumented_outputs(phase_label, required=required_outputs)
            _clear_device_screenshot_outputs()

        instrumented_pull_dir.mkdir(parents=True, exist_ok=True)
        _run_screenshot_instrumentation(deterministic=True, required_outputs=not args.product_signal_only)

        lane_cfg = context.configs.lanes.lanes.maestro
        maestro_failures: list[str] = []
        for flow in lane_cfg.flows:
            flow_path = REPO_ROOT / flow
            if not flow_path.exists():
                raise DevctlError("CONFIG_ERROR", f"Missing Maestro flow: {flow_path}")
            step = _run_maestro_flow(
                context=context,
                maestro_bin=maestro_bin,
                serial=serial,
                flow_path=flow_path,
                debug_output_dir=maestro_debug_root / flow_path.stem,
            )
            _copy_pngs_flat(maestro_debug_root / flow_path.stem, maestro_dir)
            if step.status != "passed":
                maestro_failures.append(str(flow_path))
                print_step(
                    "Screenshot-pack warning: Maestro flow failed and will be excluded from lane pass/fail "
                    f"because inventory completeness is authoritative. Flow: {flow_path}. "
                    f"Failure signature: {step.failure_signature}"
                )

        report_payload = _build_screenshot_inventory_report(
            inventory=inventory,
            serial=serial,
            artifact_root=artifact_root,
            instrumented_dir=instrumented_dir,
            maestro_dir=maestro_dir,
            combined_dir=combined_dir,
            report_json_path=report_json_path,
            report_md_path=report_md_path,
        )
        missing_ids = report_payload.get("missing_ids", [])
        if missing_ids and not args.deterministic_only:
            print_step(
                "Screenshot-pack deterministic subset did not satisfy full inventory; "
                "running full instrumentation expansion before final verdict."
            )
            _run_screenshot_instrumentation(deterministic=False, required_outputs=not args.product_signal_only)
            report_payload = _build_screenshot_inventory_report(
                inventory=inventory,
                serial=serial,
                artifact_root=artifact_root,
                instrumented_dir=instrumented_dir,
                maestro_dir=maestro_dir,
                combined_dir=combined_dir,
                report_json_path=report_json_path,
                report_md_path=report_md_path,
            )
            missing_ids = report_payload.get("missing_ids", [])

        _capture_logcat(context, serial, artifact_root / real_runtime_cfg.logcat_file_name)

        if args.update_reference:
            _promote_screenshot_reference_set(combined_dir=combined_dir, inventory=inventory)
            print_step(f"Updated screenshot references: {_SCREENSHOT_REFERENCE_DIR}")

        if maestro_failures:
            print_step("Maestro flow failures during screenshot-pack: " + ", ".join(maestro_failures))
        if instrumentation_warnings:
            print_step("Instrumentation warnings during screenshot-pack: " + " | ".join(instrumentation_warnings))

        if missing_ids:
            raise DevctlError(
                "DEVICE_ERROR",
                "Screenshot inventory is incomplete. Missing required IDs: "
                f"{', '.join(str(item) for item in missing_ids)}. See {report_json_path}",
            )

        print_step(f"Screenshot inventory report: {report_json_path}")
        print_step(f"Screenshot inventory markdown: {report_md_path}")


def _lane_journey_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    lane_cfg = context.configs.lanes.lanes.journey
    args = _parse_journey_args(
        raw_args,
        repeats_default=lane_cfg.repeats_default,
        repeats_max=lane_cfg.repeats_max,
        reply_timeout_default=lane_cfg.reply_timeout_seconds_default,
        capture_intervals_default=lane_cfg.capture_intervals_default,
        prompt_default=lane_cfg.prompt_default,
    )
    maestro_bin = shutil.which("maestro")
    if maestro_bin is None:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            "Maestro CLI is not installed. Install with: curl -Ls https://get.maestro.mobile.dev | bash",
        )

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        raise DevctlError("ENVIRONMENT_ERROR", "Android SDK not configured for journey lane.")

    serial = _ensure_serial(context)
    with _device_lock(serial, owner="lane:journey"):
        device_env = _with_target_device_env(resolved_env, serial)
        real_runtime_cfg = context.configs.lanes.lanes.real_runtime
        _run_device_health_preflight(context, serial, app_package=real_runtime_cfg.app_package)
        artifact_root = _resolve_lane_artifact_dir(lane_cfg.artifacts.output_dir_template, serial, "journey")
        artifact_root.mkdir(parents=True, exist_ok=True)
        print_step(f"journey artifacts: {artifact_root}")
        steps: list[JourneyStepResult] = []

        install_command = _append_native_build_flag(
            ["./gradlew", "--no-daemon", ":apps:mobile-android:installDebug"],
            env=device_env,
        )
        install_test_command = _append_native_build_flag(
            ["./gradlew", "--no-daemon", ":apps:mobile-android:installDebugAndroidTest"],
            env=device_env,
        )
        if not _run_serialized_gradle_install_step(
            context=context,
            command=install_command,
            serial=serial,
            env=device_env,
        ):
            context.run(install_command, check=True, env=device_env)
        if not _run_serialized_gradle_install_step(
            context=context,
            command=install_test_command,
            serial=serial,
            env=device_env,
        ):
            context.run(install_test_command, check=True, env=device_env)

        preflight = prepare_real_runtime_env(context, serial, artifact_root=artifact_root)
        selected_flows = _resolve_maestro_flow_selection(
            context.configs.lanes.lanes.maestro.flows,
            args.maestro_flows,
        )

        for index in range(args.repeats):
            run_label = f"run-{index + 1:02d}"
            run_root = artifact_root / run_label
            run_root.mkdir(parents=True, exist_ok=True)

            missing_model_path = next(
                (
                    path
                    for path in preflight.model_device_paths_by_id.values()
                    if _remote_file_size_bytes(context, serial, path) is None
                ),
                None,
            )
            if missing_model_path is not None:
                print_step(
                    f"{run_label}: model artifact missing at {missing_model_path}; re-running real-runtime preflight."
                )
                preflight = prepare_real_runtime_env(context, serial, artifact_root=artifact_root)

            runner_args = _instrumentation_args_from_model_paths(preflight.model_device_paths_by_id)

            device_journey_dir = f"/sdcard/Android/media/{real_runtime_cfg.app_package}/journey/{_now_stamp()}-{index + 1}"
            instrumentation_args = dict(runner_args)
            instrumentation_args["stage2_enable_journey_test"] = "true"
            instrumentation_args["journey_artifact_dir"] = device_journey_dir
            instrumentation_args["journey_reply_timeout_seconds"] = str(args.reply_timeout_seconds)

            instrumentation_output_path = run_root / "instrumentation-output.txt"
            if "instrumentation" in args.steps:
                started = time.monotonic()
                try:
                    result = _run_instrumentation_class(
                        context=context,
                        serial=serial,
                        test_class=real_runtime_cfg.journey_test_class,
                        runner=preflight.instrumentation_runner or real_runtime_cfg.instrumentation_runner,
                        args=instrumentation_args,
                        timeout_seconds=real_runtime_cfg.startup_probe_timeout_seconds,
                    )
                    instrumentation_output_path.write_text(
                        (result.stdout or "") + (result.stderr or ""),
                        encoding="utf-8",
                    )
                    instrumentation_status = "passed"
                    instrumentation_failure = None
                except DevctlError as exc:
                    instrumentation_output_path.write_text(exc.message + "\n", encoding="utf-8")
                    instrumentation_status = "failed"
                    instrumentation_failure = exc.message
                duration = time.monotonic() - started
            else:
                instrumentation_output_path.write_text("Instrumentation skipped by --steps.\n", encoding="utf-8")
                instrumentation_status = "skipped"
                instrumentation_failure = None
                duration = 0.0

            local_instrumentation_shots = run_root / real_runtime_cfg.screenshot_dir_name / "instrumentation"
            local_instrumentation_shots.mkdir(parents=True, exist_ok=True)
            if _remote_path_exists(context, serial, device_journey_dir):
                context.run(
                    ["adb", "-s", serial, "pull", device_journey_dir, str(local_instrumentation_shots)],
                    check=False,
                    env=context.env,
                )
            else:
                print_step(
                    f"No instrumentation artifact directory found on device: {device_journey_dir}"
                )
            step = JourneyStepResult(
                name=f"{run_label}:instrumentation",
                status=instrumentation_status,
                duration_seconds=duration,
                details=_report_path(instrumentation_output_path),
                screenshots=_collect_maestro_screenshots(local_instrumentation_shots),
                failure_signature=instrumentation_failure,
                phase=(
                    "startup"
                    if instrumentation_status == "passed"
                    else ("skipped" if instrumentation_status == "skipped" else "error")
                ),
                elapsed_ms=int(duration * 1000),
                mode=args.mode,
            )
            steps.append(step)
            if instrumentation_status == "failed":
                _capture_logcat(context, serial, run_root / real_runtime_cfg.logcat_file_name)
                continue

            if "send-capture" in args.steps:
                send_step = _run_send_capture_stage(
                    context=context,
                    maestro_bin=maestro_bin,
                    serial=serial,
                    app_package=real_runtime_cfg.app_package,
                    run_root=run_root,
                    prompt=args.prompt,
                    reply_timeout_seconds=args.reply_timeout_seconds,
                    capture_intervals=args.capture_intervals,
                    mode=args.mode,
                    env=device_env,
                )
                send_step.name = f"{run_label}:send-capture"
                send_step.mode = args.mode
                steps.append(send_step)
                if send_step.status != "passed":
                    _capture_logcat(context, serial, run_root / real_runtime_cfg.logcat_file_name)
                    continue

            if "maestro" in args.steps:
                for flow in selected_flows:
                    flow_path = REPO_ROOT / flow
                    if not flow_path.exists():
                        raise DevctlError("CONFIG_ERROR", f"Missing Maestro flow: {flow_path}")
                    flow_step = _run_maestro_flow(
                        context=context,
                        maestro_bin=maestro_bin,
                        serial=serial,
                        app_package=real_runtime_cfg.app_package,
                        flow_path=flow_path,
                        debug_output_dir=run_root / real_runtime_cfg.maestro_debug_dir_name / flow_path.stem,
                        env=device_env,
                    )
                    flow_step.name = f"{run_label}:{flow_step.name}"
                    flow_step.mode = args.mode
                    steps.append(flow_step)
                    if flow_step.status != "passed":
                        break

            _capture_logcat(context, serial, run_root / real_runtime_cfg.logcat_file_name)

        report_path = artifact_root / real_runtime_cfg.report_file_name
        summary_path = artifact_root / real_runtime_cfg.summary_file_name
        _write_journey_report(report_path=report_path, summary_path=summary_path, serial=serial, steps=steps)

        failed = [step for step in steps if step.status not in {"passed", "skipped"}]
        if failed:
            names = ", ".join(step.name for step in failed)
            raise DevctlError(
                "DEVICE_ERROR",
                f"Journey lane failed step(s): {names}. See {report_path}",
            )
        print_step(f"Journey report: {report_path}")


def _lane_fast_smoke_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    lane_journey(["--mode", "fast-smoke", "--steps", "instrumentation,send-capture", *list(raw_args)], context)


def _lane_valid_output_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    lane_journey(["--mode", "valid-output", *list(raw_args)], context)


def _lane_strict_journey_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    lane_journey(["--mode", "strict", *list(raw_args)], context)


def _lane_device_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    lane_cfg = context.configs.lanes.lanes.device

    parsed = parse_device_lane_args(raw_args, lane_cfg.default_scenario_command)
    serial = _ensure_serial(context)
    with _device_lock(serial, owner="lane:device"):
        _run_device_health_preflight(context, serial)
        _validate_required_device_props(context, serial)

        date_value = datetime.now().strftime("%Y-%m-%d")
        stamp_value = datetime.now().strftime("%Y%m%d-%H%M%S")
        resolved_dir = build_artifact_dir(
            lane_cfg.artifacts.output_dir_template,
            date_value,
            serial,
            parsed.label,
            stamp_value,
        )
        print_step(f"Resolved artifact output directory: {resolved_dir}")

        command_map = {step.name: step.argv for step in lane_cfg.commands}
        required = {
            "stage_checks",
            "capture_baseline",
            "apply_benchmark_settings",
            "reset_benchmark_settings",
            "run_short_loop",
        }
        missing = sorted(required - set(command_map))
        if missing:
            raise DevctlError("CONFIG_ERROR", f"lanes.device.commands missing required steps: {', '.join(missing)}")

        context.run(command_map["stage_checks"], check=True, env=context.env)
        context.run(command_map["capture_baseline"], check=True, env=context.env)
        context.run(command_map["apply_benchmark_settings"], check=True, env=context.env)

        try:
            loop_command = [
                *command_map["run_short_loop"],
                "--runs",
                str(parsed.runs),
                "--label",
                parsed.label,
                "--",
                *parsed.scenario_command,
            ]
            loop_result = context.run(loop_command, check=True, capture_output=True, env=context.env)
            if loop_result.stdout:
                print(loop_result.stdout, end="")
            if loop_result.stderr:
                print(loop_result.stderr, end="", file=sys.stderr)

            summary_path = _extract_summary_path(loop_result.stdout or "")
            if summary_path is not None:
                _evaluate_loop_output(
                    summary_path,
                    context.configs.device.crash_signatures,
                    context.configs.device.oom_signatures,
                )
            else:
                print_step("Short-loop summary path not found in output; skipping crash/OOM post-scan.")

            if parsed.framework in {"espresso", "both"}:
                try:
                    lane_android_instrumented([], context, strict=parsed.framework_explicit)
                except DevctlError as exc:
                    if not parsed.framework_explicit and exc.code == "ENVIRONMENT_ERROR":
                        print_step(f"Skipping espresso lane (default framework mode): {exc.message}")
                    else:
                        raise

            if parsed.framework in {"maestro", "both"}:
                try:
                    lane_maestro([], context, strict=parsed.framework_explicit)
                except DevctlError as exc:
                    if not parsed.framework_explicit and exc.code == "ENVIRONMENT_ERROR":
                        print_step(f"Skipping Maestro lane (default framework mode): {exc.message}")
                    else:
                        raise
        finally:
            context.run(command_map["reset_benchmark_settings"], check=False, env=context.env)


def _parse_stage2_args(raw_args: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="devctl lane stage2")
    parser.add_argument("--device", required=True)
    parser.add_argument("--date", default=datetime.now().strftime("%Y-%m-%d"))
    parser.add_argument("--profile", choices={"quick", "closure"}, default="closure")
    parser.add_argument("--models", choices={"0.8b", "1.7b", "both"})
    parser.add_argument("--scenarios", choices={"a", "b", "both"}, default="both")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--install-mode", choices={"auto", "force", "skip"}, default="auto")
    parser.add_argument("--logcat", choices={"filtered", "full"})
    parser.add_argument("--evidence-note-path")
    parser.add_argument("--scenario-a")
    parser.add_argument("--scenario-b")
    return parser.parse_args(list(raw_args))


def _lane_stage2_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    args = _parse_stage2_args(raw_args)
    lane_cfg = context.configs.lanes.lanes.stage2
    stage2_cfg = context.configs.stage2
    models = args.models or ("0.8b" if args.profile == "quick" else "both")
    scenarios = args.scenarios.upper()
    strict_thresholds = args.profile == "closure"
    logcat_mode = args.logcat or ("filtered" if args.profile == "quick" else "full")
    evidence_note_path = Path(args.evidence_note_path).resolve() if args.evidence_note_path else None

    if args.profile == "closure":
        if models != "both":
            raise DevctlError("CONFIG_ERROR", "stage2 closure profile requires --models both")
        if scenarios != "BOTH":
            raise DevctlError("CONFIG_ERROR", "stage2 closure profile requires --scenarios both")

    run_dir = REPO_ROOT / lane_cfg.artifacts.output_dir_template.format(date=args.date, device=args.device)
    run_dir.mkdir(parents=True, exist_ok=True)

    scenario_a_path = run_dir / "scenario-a.csv"
    scenario_b_path = run_dir / "scenario-b.csv"
    threshold_input_path = run_dir / "stage-2-threshold-input.csv"
    model_1_7b_metrics_path = run_dir / "model-1.7b-metrics.csv"
    threshold_report_path = run_dir / "threshold-report.txt"
    runtime_validation_report_path = run_dir / "runtime-evidence-validation.txt"
    logcat_path = run_dir / "logcat.txt"
    notes_path = run_dir / "notes.md"
    summary_path = run_dir / "summary.json"
    evidence_draft_path = run_dir / "evidence-draft.md"

    if args.scenario_a or args.scenario_b:
        print_step("stage2 --scenario-a/--scenario-b overrides are ignored in native closure mode.")

    lane_env = dict(context.env)
    lane_env["POCKETGPT_STAGE2_STRICT_THRESHOLDS"] = "1" if strict_thresholds else "0"

    native_command = [
        "bash",
        "scripts/android/run_stage2_native.sh",
        "--device",
        args.device,
        "--date",
        args.date,
        "--run-dir",
        str(run_dir),
        "--profile",
        args.profile,
        "--models",
        models,
        "--scenarios",
        scenarios.lower(),
        "--install-mode",
        args.install_mode,
        "--logcat",
        logcat_mode,
    ]
    if args.resume:
        native_command.append("--resume")

    context.run(
        native_command,
        check=True,
        env=lane_env,
    )

    threshold_exit_code = 0
    threshold_output = ""
    scenarios_seen = _read_csv_scenarios(threshold_input_path)
    can_run_thresholds = {"A", "B"}.issubset(scenarios_seen)
    if threshold_input_path.exists():
        validate_threshold_columns(threshold_input_path, stage2_cfg.threshold_csv.columns)

    if can_run_thresholds:
        threshold_result = context.run(
            [*lane_cfg.threshold_command, str(threshold_input_path)],
            check=False,
            capture_output=True,
            env=lane_env,
        )
        threshold_exit_code = threshold_result.returncode
        threshold_output = (threshold_result.stdout or "") + (threshold_result.stderr or "")
    else:
        threshold_output = (
            "Threshold evaluation skipped: partial scenario selection does not include both A and B rows.\n"
            f"Detected scenarios: {', '.join(sorted(scenarios_seen)) or '(none)'}\n"
        )
    _write_report(threshold_report_path, threshold_output)

    runtime_exit_code = 0
    runtime_output = ""
    if args.profile == "closure":
        runtime_validation_result = context.run(
            ["python3", "scripts/benchmarks/validate_stage2_runtime_evidence.py", str(run_dir)],
            check=False,
            capture_output=True,
            env=lane_env,
        )
        runtime_exit_code = runtime_validation_result.returncode
        runtime_output = (runtime_validation_result.stdout or "") + (runtime_validation_result.stderr or "")
    else:
        runtime_output = "Runtime evidence validation skipped for quick profile.\n"
    _write_report(runtime_validation_report_path, runtime_output)

    runtime_log_signals_json_path = run_dir / "runtime-log-signals.json"
    runtime_log_signals_markdown_path = run_dir / "runtime-log-signals.md"
    runtime_log_signal_analysis = write_runtime_log_signal_reports(
        logcat_path=logcat_path,
        json_output_path=runtime_log_signals_json_path,
        markdown_output_path=runtime_log_signals_markdown_path,
    )

    run_meta = _load_stage2_run_meta(run_dir)
    summary_data = {
        "stage": "stage2",
        "device_id": args.device,
        "run_date": args.date,
        "profile": args.profile,
        "models": models,
        "scenarios": scenarios,
        "resume_used": args.resume,
        "install_mode": args.install_mode,
        "run_dir": str(run_dir.relative_to(REPO_ROOT)),
        "scenario_a_csv": str(scenario_a_path.relative_to(REPO_ROOT)),
        "scenario_b_csv": str(scenario_b_path.relative_to(REPO_ROOT)),
        "threshold_input_csv": str(threshold_input_path.relative_to(REPO_ROOT)),
        "model_1_7b_metrics_csv": str(model_1_7b_metrics_path.relative_to(REPO_ROOT)),
        "threshold_report": str(threshold_report_path.relative_to(REPO_ROOT)),
        "runtime_evidence_validation_report": str(runtime_validation_report_path.relative_to(REPO_ROOT)),
        "runtime_log_signals_json": str(runtime_log_signals_json_path.relative_to(REPO_ROOT)),
        "runtime_log_signals_markdown": str(runtime_log_signals_markdown_path.relative_to(REPO_ROOT)),
        "runtime_log_signals_status": runtime_log_signal_analysis["status"],
        "runtime_log_signals_issue_count": runtime_log_signal_analysis["issue_count"],
        "logcat": str(logcat_path.relative_to(REPO_ROOT)),
        "notes": str(notes_path.relative_to(REPO_ROOT)),
        "evidence_draft": str(evidence_draft_path.relative_to(REPO_ROOT)),
        "evidence_note_path": str(evidence_note_path) if evidence_note_path else "",
        "summary_json": str(summary_path.relative_to(REPO_ROOT)),
        "threshold_exit_code": threshold_exit_code,
        "runtime_evidence_exit_code": runtime_exit_code,
        "strict_thresholds": strict_thresholds,
        "apk_install_skipped": run_meta.get("STAGE2_APK_INSTALL_SKIPPED", "unknown"),
        "model_provision_skipped": run_meta.get("STAGE2_MODEL_PROVISION_SKIPPED", "unknown"),
        "model_load_mode": run_meta.get("STAGE2_MODEL_LOAD_MODE", "warm_within_sweep"),
        "prefix_cache_enabled": run_meta.get("STAGE2_PREFIX_CACHE_ENABLED", "unknown"),
        "prefix_cache_hits": run_meta.get("STAGE2_PREFIX_CACHE_HITS", "0"),
        "prefix_cache_misses": run_meta.get("STAGE2_PREFIX_CACHE_MISSES", "0"),
        "prefill_tokens_reused": run_meta.get("STAGE2_PREFILL_TOKENS_REUSED", "0"),
        "warm_vs_cold_first_token_delta_ms": run_meta.get("STAGE2_WARM_VS_COLD_FIRST_TOKEN_DELTA_MS", ""),
    }
    filtered = {field: summary_data[field] for field in stage2_cfg.summary_json.fields if field in summary_data}
    summary_path.write_text(json.dumps(filtered, indent=2) + "\n", encoding="utf-8")

    evidence_command = [
        "python3",
        "tools/devctl/generate_stage2_evidence_draft.py",
        str(run_dir),
        "--output",
        str(evidence_draft_path),
    ]
    if evidence_note_path is not None:
        evidence_command.extend(["--evidence-note-path", str(evidence_note_path)])
    context.run(evidence_command, check=True, env=lane_env)

    required_files = stage2_cfg.required_files if args.profile == "closure" else stage2_cfg.quick_required_files
    missing_files = [name for name in required_files if not (run_dir / name).exists()]
    if missing_files:
        raise DevctlError("SCHEMA_ERROR", f"stage2 lane did not produce required file(s): {', '.join(missing_files)}")

    if runtime_exit_code != 0:
        raise DevctlError(
            "THRESHOLD_FAIL",
            f"Runtime evidence validation failed with exit code {runtime_exit_code}. "
            f"See {runtime_validation_report_path}",
        )

    if threshold_exit_code != 0:
        raise DevctlError(
            "THRESHOLD_FAIL",
            f"Threshold evaluation failed with exit code {threshold_exit_code}. See {threshold_report_path}",
        )

    print_step(f"Stage-2 benchmark artifacts: {run_dir}")
    print_step(f"Summary JSON: {summary_path}")


def _lane_nightly_hardware_impl(raw_args: Sequence[str], context: RuntimeContext) -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane nightly-hardware")

    if shutil.which("adb") is None:
        print_step("Nightly hardware lane prerequisite missing: adb not installed.")
        return

    devices = context.run(["adb", "devices", "-l"], check=False, capture_output=True, env=context.env)
    if devices.returncode != 0 or " device " not in (devices.stdout or ""):
        print_step("Nightly hardware lane prerequisite missing: no authorized physical device attached.")
        return

    print_step("Authorized device detected. Running nightly hardware smoke lane.")
    lane_device(["1", "nightly-hardware-smoke"], context)


def lane_test(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import shared as shared_lane

    shared_lane.run_test(raw_args, context)


def lane_android_instrumented(raw_args: Sequence[str], context: RuntimeContext, strict: bool = True) -> None:
    from tools.devctl.lanes_modules import android as android_lane

    android_lane.run_android_instrumented(raw_args, context, strict=strict)


def lane_maestro(raw_args: Sequence[str], context: RuntimeContext, strict: bool = True) -> None:
    from tools.devctl.lanes_modules import maestro as maestro_lane

    maestro_lane.run_maestro(raw_args, context, strict=strict)


def lane_screenshot_pack(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import screenshot_pack as screenshot_pack_lane

    screenshot_pack_lane.run_screenshot_pack(raw_args, context)


def lane_journey(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import journey as journey_lane

    journey_lane.run_journey(raw_args, context)


def lane_fast_smoke(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import journey as journey_lane

    journey_lane.run_fast_smoke(raw_args, context)


def lane_valid_output(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import journey as journey_lane

    journey_lane.run_valid_output(raw_args, context)


def lane_strict_journey(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import journey as journey_lane

    journey_lane.run_strict_journey(raw_args, context)


def lane_device(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import android as android_lane

    android_lane.run_device(raw_args, context)


def lane_stage2(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import stage2 as stage2_lane

    stage2_lane.run_stage2(raw_args, context)


def lane_nightly_hardware(raw_args: Sequence[str], context: RuntimeContext) -> None:
    from tools.devctl.lanes_modules import stage2 as stage2_lane

    stage2_lane.run_nightly_hardware(raw_args, context)


def dispatch_lane(lane_name: str, lane_args: Sequence[str], context: RuntimeContext) -> None:
    handlers = {
        "test": lane_test,
        "device": lane_device,
        "stage2": lane_stage2,
        "android-instrumented": lane_android_instrumented,
        "maestro": lane_maestro,
        "screenshot-pack": lane_screenshot_pack,
        "journey": lane_journey,
        "fast-smoke": lane_fast_smoke,
        "valid-output": lane_valid_output,
        "strict-journey": lane_strict_journey,
        "nightly-hardware": lane_nightly_hardware,
    }
    handler = handlers.get(lane_name)
    if handler is None:
        raise DevctlError("CONFIG_ERROR", f"Unknown lane '{lane_name}'.")
    handler(lane_args, context)


def build_runtime_context(configs: DevctlConfigs, env: Mapping[str, str] | None = None) -> RuntimeContext:
    return RuntimeContext(
        repo_root=REPO_ROOT,
        configs=configs,
        env=dict(env or os.environ),
        run=run_subprocess,
    )
