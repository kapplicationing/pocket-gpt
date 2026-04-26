from __future__ import annotations

import json
import os
import shutil
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Mapping

from tools.maestro_android.adb_serial import (
    merge_mdns_aliases,
    parse_adb_devices_output,
    parse_adb_mdns_services_output,
    resolve_requested_serial,
)
from tools.devctl.subprocess_utils import REPO_ROOT, format_command, run_subprocess


@dataclass
class DoctorCheck:
    name: str
    ok: bool
    required: bool
    detail: str
    fix: str


@dataclass
class DoctorReport:
    checks: list[DoctorCheck]

    @property
    def failed_required(self) -> list[DoctorCheck]:
        return [check for check in self.checks if check.required and not check.ok]

    @property
    def ok(self) -> bool:
        return not self.failed_required

    def to_json(self) -> str:
        payload = {
            "ok": self.ok,
            "checks": [asdict(check) for check in self.checks],
            "failed_required": [check.name for check in self.failed_required],
        }
        return json.dumps(payload, indent=2) + "\n"


def _get_android_home(env: Mapping[str, str]) -> str | None:
    android_home = env.get("ANDROID_HOME")
    if android_home:
        return android_home
    return env.get("ANDROID_SDK_ROOT")


def _check_device(env: Mapping[str, str]) -> tuple[bool, str, str]:
    if shutil.which("adb") is None:
        return False, "", "adb not installed"

    devices = run_subprocess(["adb", "devices", "-l"], check=False, capture_output=True, env=env)
    if devices.returncode != 0:
        return False, "", (devices.stderr or "adb devices failed").strip()

    parsed_devices = merge_mdns_aliases(
        parse_adb_devices_output(devices.stdout or ""),
        _load_mdns_services(env),
    )
    if not parsed_devices:
        return False, "", "No adb devices detected"

    requested_serial = env.get("ADB_SERIAL") or env.get("ANDROID_SERIAL") or ""
    requested_serial = requested_serial.strip()

    authorized = [device for device in parsed_devices if device.authorized]
    if not authorized:
        first = parsed_devices[0]
        serial = first.serial
        state = first.state
        return False, serial, f"Device {serial} is in state '{state}'"

    if requested_serial:
        matched = resolve_requested_serial(requested_serial, authorized)
        if matched is None:
            return False, requested_serial, f"Requested device {requested_serial} is not connected and authorized"
        if matched.serial != requested_serial:
            return True, matched.serial, f"Requested device {requested_serial} resolved to {matched.serial} and is connected and authorized"
        return True, matched.serial, f"Device {matched.serial} is connected and authorized"

    if len(authorized) > 1:
        return False, "", "Multiple adb devices detected; set ADB_SERIAL"

    serial = authorized[0].serial
    return True, serial, f"Device {serial} is connected and authorized"


def _load_mdns_services(env: Mapping[str, str]) -> list:
    mdns = run_subprocess(["adb", "mdns", "services"], check=False, capture_output=True, env=env)
    return parse_adb_mdns_services_output(mdns.stdout or "")


def _check_transport(serial: str, env: Mapping[str, str]) -> tuple[bool, str]:
    get_state = run_subprocess(["adb", "-s", serial, "get-state"], check=False, capture_output=True, env=env)
    state_output = (get_state.stdout or get_state.stderr or "").strip()
    if get_state.returncode != 0 or state_output != "device":
        return False, state_output or f"adb get-state failed ({get_state.returncode})"
    shell = run_subprocess(["adb", "-s", serial, "shell", "echo", "doctor-ok"], check=False, capture_output=True, env=env)
    shell_output = ((shell.stdout or "") + (shell.stderr or "")).strip()
    if shell.returncode != 0:
        return False, shell_output or f"adb shell probe failed ({shell.returncode})"
    return True, "adb get-state=device and shell probe succeeded"


def run_doctor(env: Mapping[str, str] | None = None, repo_root: Path = REPO_ROOT) -> DoctorReport:
    effective_env = dict(os.environ if env is None else env)
    checks: list[DoctorCheck] = []

    py_ok = os.sys.version_info >= (3, 10)
    checks.append(
        DoctorCheck(
            name="python_version",
            ok=py_ok,
            required=True,
            detail=f"Detected Python {os.sys.version.split()[0]}",
            fix="Install Python 3.10+.",
        )
    )

    android_home = _get_android_home(effective_env)
    android_home_ok = bool(android_home and Path(android_home).is_dir())
    checks.append(
        DoctorCheck(
            name="android_sdk",
            ok=android_home_ok,
            required=True,
            detail=f"ANDROID_HOME={android_home}" if android_home else "ANDROID_HOME/ANDROID_SDK_ROOT not set",
            fix="Set ANDROID_HOME and ANDROID_SDK_ROOT to your Android SDK path.",
        )
    )

    adb_path = shutil.which("adb")
    checks.append(
        DoctorCheck(
            name="adb",
            ok=adb_path is not None,
            required=True,
            detail=f"adb={adb_path}" if adb_path else "adb not found in PATH",
            fix="Install Android platform-tools and add platform-tools to PATH.",
        )
    )

    gradlew = repo_root / "gradlew"
    checks.append(
        DoctorCheck(
            name="gradle_wrapper",
            ok=gradlew.exists(),
            required=True,
            detail=f"gradlew={gradlew}",
            fix="Ensure you are running from the repository root with gradlew present.",
        )
    )

    maestro_bin = shutil.which("maestro")
    checks.append(
        DoctorCheck(
            name="maestro_cli",
            ok=maestro_bin is not None,
            required=False,
            detail=f"maestro={maestro_bin}" if maestro_bin else "Maestro CLI not installed",
            fix="Install Maestro: curl -Ls https://get.maestro.mobile.dev | bash",
        )
    )

    device_ok, serial, device_detail = _check_device(effective_env)
    checks.append(
        DoctorCheck(
            name="adb_device",
            ok=device_ok,
            required=True,
            detail=device_detail,
            fix="Connect a single authorized device and accept USB debugging RSA prompt.",
        )
    )

    transport_ok = False
    transport_detail = "Skipped: device selection failed"
    if device_ok:
        transport_ok, transport_detail = _check_transport(serial, effective_env)
    checks.append(
        DoctorCheck(
            name="adb_transport",
            ok=transport_ok,
            required=True,
            detail=transport_detail,
            fix="For wireless devices, reconnect the selected serial with `adb connect <ip:port>` and verify `adb -s <serial> get-state` before running Maestro.",
        )
    )

    install_ok = False
    install_detail = "Skipped: prerequisites not met"
    launch_ok = False
    launch_detail = "Skipped: install step not run"

    prereqs_ok = all(
        check.ok
        for check in checks
        if check.name in {"android_sdk", "adb", "adb_device", "adb_transport", "gradle_wrapper"}
    )

    if prereqs_ok:
        install_cmd = [
            "./gradlew",
            f"-Pandroid.injected.device.serial={serial}",
            "--no-daemon",
            ":apps:mobile-android:installDebug",
        ]
        install_result = run_subprocess(
            install_cmd,
            check=False,
            capture_output=True,
            env=effective_env,
            cwd=repo_root,
        )
        install_ok = install_result.returncode == 0
        install_detail = (
            "installDebug succeeded"
            if install_ok
            else f"installDebug failed ({install_result.returncode})"
        )

        if install_ok:
            launch_cmd = ["adb", "-s", serial, "shell", "am", "start", "-W", "-n", "com.pocketagent.android/.MainActivity"]
            launch_result = run_subprocess(
                launch_cmd,
                check=False,
                capture_output=True,
                env=effective_env,
                cwd=repo_root,
            )
            launch_output = (launch_result.stdout or "") + (launch_result.stderr or "")
            launch_ok = launch_result.returncode == 0 and "Error:" not in launch_output
            launch_detail = "MainActivity launch succeeded" if launch_ok else launch_output.strip() or "Launch failed"

    checks.append(
        DoctorCheck(
            name="app_installability",
            ok=install_ok,
            required=True,
            detail=install_detail,
            fix="Run ./gradlew --no-daemon :apps:mobile-android:installDebug and resolve Gradle/SDK errors.",
        )
    )
    checks.append(
        DoctorCheck(
            name="app_launchability",
            ok=launch_ok,
            required=True,
            detail=launch_detail,
            fix="Verify package/activity id and check adb logcat for startup errors.",
        )
    )

    return DoctorReport(checks=checks)


def print_doctor_report(report: DoctorReport, as_json: bool = False) -> None:
    if as_json:
        print(report.to_json(), end="")
        return

    print("Devctl Doctor")
    print("============")
    for check in report.checks:
        status = "PASS" if check.ok else "FAIL"
        req = "required" if check.required else "optional"
        print(f"- {check.name} [{req}]: {status}")
        print(f"  detail: {check.detail}")
        if not check.ok:
            print(f"  fix: {check.fix}")

    print("\nOverall:", "PASS" if report.ok else "FAIL")
