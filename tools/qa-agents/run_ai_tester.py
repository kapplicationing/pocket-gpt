"""Per-tester runner for the AI human-proxy workflow.

The current contract is intentionally small-discovery rather than the old
eight-flow local/cloud matrix. Each tester should emit a short, current,
verdict-bearing artifact pack even when the answer is "hold" or
"infra/harness blocked".
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TMP = REPO / "tmp/qa-agents"
DEVCTL_ARTIFACTS = REPO / "tmp/devctl-artifacts"
DISCOVERY_MODE = "small-discovery-v1"
DEVICE_TIMEOUT_SECONDS = 20 * 60
CLOUD_TIMEOUT_SECONDS = 15 * 60

ADB_DEVICE_LINE = re.compile(r"^(?P<serial>\S+)\s+(?P<state>\S+)(?:\s+(?P<details>.*))?$")


def _child_env() -> dict[str, str]:
    """Ensure Maestro / Gradle children see ANDROID_HOME (Cursor agents often omit it)."""
    env = dict(os.environ)
    if env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT"):
        return env
    home = Path.home()
    for root in (home / "Library/Android/sdk", home / "Android/Sdk"):
        if (root / "platform-tools" / "adb").is_file():
            env["ANDROID_HOME"] = str(root)
            env.setdefault("ANDROID_SDK_ROOT", str(root))
            break
    return env


DEVICES = {
    "device-s22": {
        "label": "Samsung Galaxy S22 Ultra (SM-S906N)",
        "serial": "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp",
        "model": "SM-S906N",
        "manufacturer": "Samsung",
        "android_api": 34,
    },
    "device-a51": {
        "label": "Samsung Galaxy A51 (SM-A515F)",
        "serial": "adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp",
        "model": "SM-A515F",
        "manufacturer": "Samsung",
        "android_api": 31,
    },
}

CLOUD = {
    "cloud-1": ("MAESTRO_CLOUD_API_KEY", "account-1"),
    "cloud-2": ("MAESTRO_CLOUD_API_KEY_2", "account-2"),
}

JOURNEY = [
    ("android-instrumented", "python3 tools/devctl/main.py lane android-instrumented"),
    ("runtime-ready", "tests/maestro-cloud/scenario-runtime-ready-smoke.yaml"),
    ("send-after-ready", "tests/maestro-cloud/scenario-send-after-ready-smoke.yaml"),
]


def utc_compact() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def utc_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run(cmd: list[str], **kw: object) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}", flush=True)
    if "env" not in kw:
        kw["env"] = _child_env()
    else:
        merged = _child_env()
        merged.update(kw["env"])  # type: ignore[arg-type]
        kw["env"] = merged  # type: ignore[assignment]
    return subprocess.run(cmd, check=False, **kw)  # type: ignore[arg-type]


def _normalize_device_value(raw: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", raw.lower())


def _parse_adb_devices_output(output: str) -> list[dict[str, str]]:
    devices: list[dict[str, str]] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        match = ADB_DEVICE_LINE.match(line)
        if not match:
            continue
        details_text = match.group("details") or ""
        fields: dict[str, str] = {}
        for token in details_text.split():
            if ":" not in token:
                continue
            key, value = token.split(":", 1)
            fields[key] = value
        devices.append(
            {
                "serial": match.group("serial"),
                "state": match.group("state"),
                "details": details_text,
                "model": fields.get("model", ""),
                "device": fields.get("device", ""),
                "product": fields.get("product", ""),
            }
        )
    return devices


def _list_connected_devices() -> list[dict[str, str]]:
    completed = run(["adb", "devices", "-l"], capture_output=True, text=True)
    return [device for device in _parse_adb_devices_output(completed.stdout or "") if device["state"] == "device"]


def _resolve_live_serial(cfg: dict[str, object]) -> str:
    requested = str(cfg.get("serial", "")).strip()
    devices = _list_connected_devices()
    if requested and any(device["serial"] == requested for device in devices):
        return requested

    target_model = _normalize_device_value(str(cfg.get("model", "")))
    if target_model:
        for device in devices:
            if _normalize_device_value(device.get("model", "")) == target_model:
                return device["serial"]

    if requested:
        requested_norm = _normalize_device_value(requested)
        for device in devices:
            if requested_norm and requested_norm in _normalize_device_value(device["serial"]):
                return device["serial"]

    available = ", ".join(f"{device['serial']} ({device.get('model', 'unknown')})" for device in devices) or "none"
    raise SystemExit(
        f"unable to resolve live adb serial for {cfg.get('label', requested or 'device')}; available={available}"
    )


def _dump_logcat_tail(serial: str, dest: Path) -> None:
    print(f"$ adb -s {serial} logcat -d -t 5000", flush=True)
    try:
        with open(dest, "w", encoding="utf-8") as lcf:
            subprocess.run(
                ["adb", "-s", serial, "logcat", "-d", "-t", "5000"],
                check=False,
                stdout=lcf,
                stderr=subprocess.DEVNULL,
                env=_child_env(),
                timeout=15,
            )
    except subprocess.TimeoutExpired:
        dest.write_text(
            f"logcat tail timed out for {serial}; transport did not return within 15s.\n",
            encoding="utf-8",
        )


def extract_provenance_from_log(log_text: str) -> dict[str, str]:
    upload_id = ""
    m = re.search(r"(mupload_[A-Za-z0-9]+)", log_text)
    if m:
        upload_id = m.group(1)
    project_id = ""
    m2 = re.search(r"project[/=]([0-9a-fA-F-]{20,})", log_text)
    if m2:
        project_id = m2.group(1)
    return {"upload_id": upload_id, "project_id": project_id}


def _relative_to_repo(path: Path) -> str:
    try:
        return str(path.relative_to(REPO))
    except ValueError:
        return str(path)


def _find_cloud_api_key(env_var: str) -> str | None:
    value = os.environ.get(env_var)
    if value:
        return value
    dotenv_path = REPO / ".env"
    if not dotenv_path.is_file():
        return None
    for line in dotenv_path.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{env_var}="):
            return line.split("=", 1)[1].strip() or None
    return None


def ensure_apk() -> Path:
    out = REPO / "apps/mobile-android/build/outputs/apk/debug"
    apks = sorted(out.glob("*.apk"))
    stale = (
        not apks
        or any(p.stat().st_mtime < (time.time() - 1800) for p in apks)
    )
    if stale:
        run(
            [
                "./gradlew",
                "--no-daemon",
                "-Ppocketgpt.enableNativeBuild=false",
                ":apps:mobile-android:assembleDebug",
            ],
            cwd=REPO,
        )
        apks = sorted(out.glob("*.apk"))
    if not apks:
        raise SystemExit("APK assembly failed")
    return apks[0]


def head_commit() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO).decode().strip()


def branch_tip() -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=REPO
    ).decode().strip()


def _snapshot_pngs() -> set[Path]:
    return {p.resolve() for p in REPO.glob("*.png") if p.is_file()}


def _collect_new_pngs(before: set[Path], dest: Path) -> list[str]:
    current = {p.resolve() for p in REPO.glob("*.png") if p.is_file()}
    new = current - before
    moved: list[str] = []
    for png in sorted(new):
        try:
            target = dest / png.name
            shutil.move(str(png), str(target))
            moved.append(str(target.relative_to(REPO)))
        except OSError:
            continue
    return moved


def _bootstrap_maestro_local(serial: str) -> None:
    """Delegate to scripts/dev/maestro-local-bootstrap.sh (single source of truth)."""
    script = REPO / "scripts/dev/maestro-local-bootstrap.sh"
    if not script.is_file():
        print(f"warn: missing {script.relative_to(REPO)}; skipping Maestro bootstrap", flush=True)
        return
    proc = subprocess.run(
        ["bash", str(script), "--serial", serial],
        cwd=REPO,
        check=False,
        env=_child_env(),
    )
    if proc.returncode != 0:
        print(
            "warn: maestro-local-bootstrap.sh failed; Maestro flows may fail at driver attach — "
            "fix pairing/reverse/port-7001 then re-run this script manually.",
            flush=True,
        )


def _run_logged(
    cmd: list[str],
    *,
    log_path: Path,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    timeout_seconds: int | None = None,
) -> dict[str, object]:
    print(f"$ {' '.join(cmd)}", flush=True)
    merged_env = _child_env()
    if env:
        merged_env.update(env)
    with open(log_path, "w", encoding="utf-8") as logf:
        try:
            proc = subprocess.run(
                cmd,
                cwd=cwd or REPO,
                check=False,
                stdout=logf,
                stderr=subprocess.STDOUT,
                env=merged_env,
                timeout=timeout_seconds,
            )
            return {"exit_code": proc.returncode, "timed_out": False}
        except subprocess.TimeoutExpired:
            logf.write(
                f"\n[qa-agents] timed out after {timeout_seconds}s while running: {' '.join(cmd)}\n"
            )
            return {"exit_code": 124, "timed_out": True}


def _list_lane_artifact_dirs(serial: str, lane: str) -> set[Path]:
    base = DEVCTL_ARTIFACTS / datetime.now().strftime("%Y-%m-%d") / serial / lane
    if not base.is_dir():
        return set()
    return {path.resolve() for path in base.iterdir() if path.is_dir()}


def _resolve_created_dir(before: set[Path], after: set[Path]) -> Path | None:
    created = sorted(after - before)
    if created:
        return created[-1]
    if after:
        return sorted(after)[-1]
    return None


def _runner_blocked_result(name: str, message: str, *, log_path: Path | None = None) -> dict[str, object]:
    result = {
        "name": name,
        "status": "blocked",
        "blocker_message": message,
    }
    if log_path is not None:
        result["log"] = _relative_to_repo(log_path)
    return result


def _run_android_instrumented_step(serial: str, root: Path) -> dict[str, object]:
    name = "android-instrumented"
    log_path = root / f"{name}.log"
    before = _list_lane_artifact_dirs(serial, name)
    proc = _run_logged(
        ["python3", "tools/devctl/main.py", "lane", name],
        log_path=log_path,
        cwd=REPO,
        env={"ADB_SERIAL": serial, "ANDROID_SERIAL": serial},
        timeout_seconds=DEVICE_TIMEOUT_SECONDS,
    )
    after = _list_lane_artifact_dirs(serial, name)
    artifact_root = _resolve_created_dir(before, after)
    report_payload = None
    report_path = None
    if artifact_root is not None:
        candidate = artifact_root / "android-instrumented-report.json"
        if candidate.is_file():
            report_path = candidate
            report_payload = json.loads(candidate.read_text(encoding="utf-8"))
    status = "failed"
    blocker_message = None
    if report_payload is not None:
        status = str(report_payload.get("status") or status)
        blocker_message = report_payload.get("failure")
    elif proc["timed_out"]:
        status = "timed_out"
        blocker_message = f"android-instrumented timed out after {DEVICE_TIMEOUT_SECONDS}s"
    elif int(proc["exit_code"]) == 0:
        status = "passed"
    else:
        blocker_message = "android-instrumented exited non-zero before report generation"
    result: dict[str, object] = {
        "name": name,
        "kind": "devctl-lane",
        "status": status,
        "exit_code": proc["exit_code"],
        "timed_out": proc["timed_out"],
        "log": _relative_to_repo(log_path),
    }
    if artifact_root is not None:
        result["artifact_root"] = _relative_to_repo(artifact_root)
    if report_path is not None:
        result["report_json"] = _relative_to_repo(report_path)
    if blocker_message:
        result["blocker_message"] = blocker_message
    return result


def _run_cloud_flow_step(
    *,
    env_var: str,
    flow_name: str,
    flow_path: str,
    root: Path,
) -> dict[str, object]:
    run_root = root / flow_name
    run_root.mkdir(parents=True, exist_ok=True)
    wrapper_log = root / f"{flow_name}.log"
    if not _find_cloud_api_key(env_var):
        status_path = run_root / "status.json"
        payload = {
            "status": "blocked_missing_api_key",
            "blocker_key": "missing_api_key",
            "blocker_message": f"missing {env_var}",
            "flow": flow_path,
        }
        status_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        wrapper_log.write_text(f"missing {env_var}\n", encoding="utf-8")
        return {
            "name": flow_name,
            "kind": "maestro-cloud-flow",
            "status": "blocked_missing_api_key",
            "exit_code": 2,
            "timed_out": False,
            "log": _relative_to_repo(wrapper_log),
            "run_root": _relative_to_repo(run_root),
            "status_json": _relative_to_repo(status_path),
            "blocker_message": f"missing {env_var}",
        }

    proc = _run_logged(
        [
            "bash",
            "scripts/dev/maestro-cloud-flow.sh",
            "--no-build",
            "--api-key-env",
            env_var,
            "--flow",
            flow_path,
            "--run-root",
            str(run_root),
        ],
        log_path=wrapper_log,
        cwd=REPO,
        timeout_seconds=CLOUD_TIMEOUT_SECONDS,
    )
    status_path = run_root / "status.json"
    status_payload = json.loads(status_path.read_text(encoding="utf-8")) if status_path.is_file() else {}
    blocker_message = status_payload.get("blocker_message")
    if proc["timed_out"] and not blocker_message:
        blocker_message = f"{flow_name} timed out after {CLOUD_TIMEOUT_SECONDS}s"
    result = {
        "name": flow_name,
        "kind": "maestro-cloud-flow",
        "status": status_payload.get("status", "timed_out" if proc["timed_out"] else "failed"),
        "exit_code": proc["exit_code"],
        "timed_out": proc["timed_out"],
        "flow_path": flow_path,
        "log": _relative_to_repo(wrapper_log),
        "run_root": _relative_to_repo(run_root),
    }
    if status_path.is_file():
        result["status_json"] = _relative_to_repo(status_path)
    if blocker_message:
        result["blocker_message"] = blocker_message
    for key in ("upload_id", "project_id", "upload_url", "blocker_key", "first_failed_flow"):
        if key in status_payload and status_payload.get(key) is not None:
            result[key] = status_payload.get(key)
    return result


def run_device(tester: str, root: Path) -> dict:
    cfg = dict(DEVICES[tester])
    try:
        cfg["serial"] = _resolve_live_serial(cfg)
    except SystemExit as exc:
        return {
            "capture_mode": DISCOVERY_MODE,
            "step_results": {
                "android-instrumented": _runner_blocked_result("android-instrumented", str(exc))
            },
        }
    lc = root / "logcat.tail.txt"
    step = _run_android_instrumented_step(str(cfg["serial"]), root)
    _dump_logcat_tail(str(cfg["serial"]), lc)
    return {
        "capture_mode": DISCOVERY_MODE,
        "step_results": {"android-instrumented": step},
        "logcat": _relative_to_repo(lc),
    }


def run_cloud(tester: str, root: Path) -> dict:
    env_var, label = CLOUD[tester]
    step_results = {
        "runtime-ready": _run_cloud_flow_step(
            env_var=env_var,
            flow_name="runtime-ready",
            flow_path="tests/maestro-cloud/scenario-runtime-ready-smoke.yaml",
            root=root,
        ),
        "send-after-ready": _run_cloud_flow_step(
            env_var=env_var,
            flow_name="send-after-ready",
            flow_path="tests/maestro-cloud/scenario-send-after-ready-smoke.yaml",
            root=root,
        ),
    }
    return {
        "capture_mode": DISCOVERY_MODE,
        "step_results": step_results,
        "account_label": label,
    }


def _empty_workflow() -> dict:
    return {
        "completed": False,
        "duration_seconds": 0.0,
        "blocker": None,
        "confusion_notes": [],
        "screenshots": [],
        "logcat_excerpts": [],
    }


def _empty_failure() -> dict:
    return {
        "recovered": False,
        "deterministic_code_seen": None,
        "cta_path_taken": None,
        "duration_seconds": 0.0,
        "notes": "",
    }


def make_skeleton(
    tester: str,
    kind: str,
    device: dict,
    root: Path,
    results: dict,
    started_iso: str,
) -> Path:
    ended = utc_iso()
    seeded_summary = _seed_summary_from_results(results)
    skeleton = {
        "tester_id": tester,
        "tester_kind": kind,
        "device": device,
        "build": {
            "commit": head_commit(),
            "apk_path": "apps/mobile-android/build/outputs/apk/debug",
            "branch_tip": branch_tip(),
        },
        "timestamps": {
            "started_utc": started_iso,
            "ended_utc": ended,
        },
        "workflows": {k: _empty_workflow() for k in ("A", "B", "C")},
        "failure_states": {
            "recovery_not_ready": _empty_failure(),
            "stuck_send": _empty_failure(),
            "manifest_outage": _empty_failure(),
        },
        "advanced_controls": {
            "profiles_visible": [],
            "gpu_toggle_observed": False,
            "diagnostics_export_ok": False,
            "keepalive_options_visible": [],
        },
        "summary": seeded_summary["summary"],
        "recommendation": seeded_summary["recommendation"],
        "_runner_artifacts": results,
    }
    skel_path = root / "trip-report.skeleton.json"
    skel_path.write_text(json.dumps(skeleton, indent=2), encoding="utf-8")
    _write_first_failure_index(root, results)
    return skel_path


def _describe_step_result(name: str, info: dict[str, object]) -> str:
    status = str(info.get("status", "failed"))
    blocker = str(info.get("blocker_message") or "").strip()
    if blocker:
        return f"{name}: {status} — {blocker}"
    first_failed_flow = info.get("first_failed_flow")
    if isinstance(first_failed_flow, dict):
        flow_name = first_failed_flow.get("name") or "unknown-flow"
        message = first_failed_flow.get("message") or "see status.json"
        return f"{name}: {status} — {flow_name} ({message})"
    return f"{name}: {status}"


def _seed_summary_from_results(results: dict[str, object]) -> dict[str, object]:
    step_results = results.get("step_results") or {}
    if not isinstance(step_results, dict) or not step_results:
        return {
            "summary": {
                "s0_count": 0,
                "s1_count": 1,
                "blockers": ["No discovery steps produced runner artifacts."],
                "confusion_runtime_pct": 0.0,
                "confusion_privacy_pct": 0.0,
            },
            "recommendation": "hold",
        }

    blockers: list[str] = []
    all_passed = True
    for name, raw_info in step_results.items():
        info = raw_info if isinstance(raw_info, dict) else {"status": "failed", "blocker_message": str(raw_info)}
        if str(info.get("status")) != "passed":
            all_passed = False
            blockers.append(_describe_step_result(name, info))

    blockers.append(
        "Small discovery path only: human-proxy reviewers must still judge unexercised workflows and recovery paths from current evidence."
    )
    return {
        "summary": {
            "s0_count": 0,
            "s1_count": 0 if all_passed else 1,
            "blockers": blockers,
            "confusion_runtime_pct": 0.0,
            "confusion_privacy_pct": 0.0,
        },
        "recommendation": "iterate" if all_passed else "hold",
    }


def _write_first_failure_index(root: Path, results: dict) -> None:
    steps = results.get("step_results") or results.get("flow_results") or {}
    lines = ["# First Failure Index", ""]
    if not isinstance(steps, dict) or not steps:
        lines.append("No discovery step artifacts were produced.")
    else:
        first_failure = None
        for name, raw_info in steps.items():
            info = raw_info if isinstance(raw_info, dict) else {"status": "failed", "blocker_message": str(raw_info)}
            if str(info.get("status")) != "passed":
                first_failure = (name, info)
                break
        if first_failure is None:
            lines.append("No failing discovery step (all recorded steps passed).")
        else:
            name, info = first_failure
            lines += [
                f"- First failing step: `{name}`",
                f"- Status: `{info.get('status', 'failed')}`",
            ]
            for key in ("log", "status_json", "report_json", "artifact_root", "run_root"):
                if info.get(key):
                    lines.append(f"- {key.replace('_', ' ').title()}: `{info.get(key)}`")
            if info.get("exit_code") is not None:
                lines.append(f"- Exit code: {info.get('exit_code')}")
            if info.get("blocker_message"):
                lines.append(f"- Blocker: {info.get('blocker_message')}")
    (root / "first-failure-index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument(
        "--tester",
        required=True,
        choices=list(DEVICES) + list(CLOUD),
    )
    args = p.parse_args(argv)

    started_iso = utc_iso()
    stamp = utc_compact()
    root = TMP / args.tester / stamp
    root.mkdir(parents=True, exist_ok=True)

    if args.tester in DEVICES:
        results = run_device(args.tester, root)
        dev = dict(DEVICES[args.tester])
        kind = "physical-device"
    else:
        results = run_cloud(args.tester, root)
        dev = {
            "label": f"Maestro Cloud ({CLOUD[args.tester][1]})",
            "serial": "n/a",
            "android_api": 34,
            "manufacturer": "hosted",
            "model": "Maestro Cloud (API 34)",
        }
        kind = "maestro-cloud"

    skel = make_skeleton(args.tester, kind, dev, root, results, started_iso)
    print(f"\nTrip-report skeleton: {skel}")
    print(f"Artifacts root: {root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
