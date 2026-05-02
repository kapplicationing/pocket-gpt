"""Per-tester runner. Each AI tester sub-agent calls this once.

Modes:
  --tester cloud-1      → uses MAESTRO_CLOUD_API_KEY,   account label "account-1"
  --tester cloud-2      → uses MAESTRO_CLOUD_API_KEY_2, account label "account-2"
  --tester device-s22   → wireless ADB serial RFCT2178PDV
  --tester device-a51   → wireless ADB serial RR8NB087YTF

The runner does **only** the deterministic, machine-verifiable parts:
  1. assemble debug APK once per invocation
  2. install/upload to chosen target
  3. drive the canonical scripted journey via Maestro flow files
  4. capture artifacts under tmp/qa-agents/<tester>/<utc-timestamp>/
  5. write a trip-report skeleton (schema-valid) with deterministic fields

The qualitative fields (`confusion_notes`, `recommendation`) stay empty so
the dispatched sub-agent fills them with first-party-witness judgement
based on the captured screenshots and logcat.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TMP  = REPO / "tmp/qa-agents"

DEVICES = {
    "device-s22": {
        "label":  "Samsung Galaxy S22 Ultra (SM-S906N)",
        "serial": "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp",
        "model":  "SM-S906N",
        "manufacturer": "Samsung",
        "android_api": 34,
    },
    "device-a51": {
        "label":  "Samsung Galaxy A51 (SM-A515F)",
        "serial": "adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp",
        "model":  "SM-A515F",
        "manufacturer": "Samsung",
        "android_api": 31,
    },
}

CLOUD = {
    "cloud-1": ("MAESTRO_CLOUD_API_KEY",   "account-1"),
    "cloud-2": ("MAESTRO_CLOUD_API_KEY_2", "account-2"),
}

# Canonical scripted journey, kept short on purpose. Each entry maps to a Maestro
# flow file. The journey covers Workflows A/B/C plus the three failure states.
JOURNEY = [
    ("onboarding",        "tests/maestro/scenario-onboarding.yaml"),
    ("workflow-A-send",   "tests/maestro/scenario-a.yaml"),
    ("workflow-B-tools",  "tests/maestro/scenario-b.yaml"),
    ("workflow-C-image",  "tests/maestro/scenario-c.yaml"),
    ("recovery-notready", "tests/maestro/scenario-activation-send-smoke.yaml"),
    ("stuck-send",        "tests/maestro/scenario-first-run-download-chat.yaml"),
    ("manifest-outage",   "tests/maestro/scenario-download-settings-smoke.yaml"),
    ("session-shell",     "tests/maestro/scenario-session-drawer-smoke.yaml"),
]

def utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}", flush=True)
    return subprocess.run(cmd, check=False, **kw)

def ensure_apk() -> Path:
    out = REPO / "apps/mobile-android/build/outputs/apk/debug"
    apks = sorted(out.glob("*.apk"))
    if not apks or any(p.stat().st_mtime < (time.time() - 1800) for p in apks):
        run(["./gradlew", "--no-daemon",
             "-Ppocketgpt.enableNativeBuild=false",
             ":apps:mobile-android:assembleDebug"], cwd=REPO)
        apks = sorted(out.glob("*.apk"))
    if not apks:
        raise SystemExit("APK assembly failed")
    return apks[0]

def head_commit() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO).decode().strip()

def branch_tip() -> str:
    return subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=REPO).decode().strip()

def run_device(tester: str, root: Path) -> dict:
    cfg = DEVICES[tester]
    apk = ensure_apk()
    run(["adb", "-s", cfg["serial"], "install", "-r", str(apk)])
    flow_results: dict[str, dict] = {}
    for name, flow in JOURNEY:
        log_path = root / f"{name}.log"
        xml_path = root / f"{name}.xml"
        with open(log_path, "w", encoding="utf-8") as logf:
            proc = run(
                ["maestro", "--device", cfg["serial"], "test", flow,
                 "--format", "junit", "--output", str(xml_path)],
                stdout=logf, stderr=subprocess.STDOUT,
            )
        # collect screenshots maestro drops into the cwd
        for png in REPO.glob("*.png"):
            shutil.move(str(png), str(root / png.name))
        flow_results[name] = {"exit_code": proc.returncode, "log": str(log_path), "junit": str(xml_path)}
    # capture logcat tail
    lc = root / "logcat.tail.txt"
    with open(lc, "w", encoding="utf-8") as lcf:
        run(["adb", "-s", cfg["serial"], "logcat", "-d", "-t", "5000"],
            stdout=lcf)
    return {"flow_results": flow_results, "logcat": str(lc)}

def run_cloud(tester: str, root: Path) -> dict:
    env_var, label = CLOUD[tester]
    api_key = os.environ.get(env_var)
    if not api_key:
        # try to read from .env without overwriting current env
        env_path = REPO / ".env"
        if env_path.is_file():
            for line in env_path.read_text(encoding="utf-8").splitlines():
                if line.startswith(f"{env_var}="):
                    api_key = line.split("=", 1)[1].strip()
                    break
    if not api_key:
        raise SystemExit(f"missing {env_var}")
    apk = ensure_apk()
    flow_results: dict[str, dict] = {}
    for name, flow in JOURNEY:
        log_path = root / f"{name}.log"
        xml_path = root / f"{name}.xml"
        with open(log_path, "w", encoding="utf-8") as logf:
            proc = run(
                ["maestro", "cloud",
                 "--api-key", api_key,
                 "--android-api-level", "34",
                 "--app-file", str(apk),
                 "--flows", flow,
                 "--format", "junit",
                 "--output", str(xml_path)],
                stdout=logf, stderr=subprocess.STDOUT,
            )
        flow_results[name] = {"exit_code": proc.returncode, "log": str(log_path),
                              "junit": str(xml_path), "account_label": label}
    return {"flow_results": flow_results, "account_label": label}

def make_skeleton(
    tester: str,
    kind: str,
    device: dict,
    root: Path,
    results: dict,
    started_utc: str,
) -> Path:
    skeleton = {
        "tester_id":   tester,
        "tester_kind": kind,
        "device":      device,
        "build": {
            "commit":     head_commit(),
            "apk_path":   "apps/mobile-android/build/outputs/apk/debug",
            "branch_tip": branch_tip(),
        },
        "timestamps": {
            "started_utc": started_utc,
            "ended_utc":   utc(),
        },
        # Qualitative fields are deliberately empty so the dispatched sub-agent
        # fills them based on the captured artifacts.
        "workflows": {k: _empty_workflow() for k in ("A", "B", "C")},
        "failure_states": {
            "recovery_not_ready": _empty_failure(),
            "stuck_send":          _empty_failure(),
            "manifest_outage":     _empty_failure(),
        },
        "advanced_controls": {
            "profiles_visible": [],
            "gpu_toggle_observed": False,
            "diagnostics_export_ok": False,
            "keepalive_options_visible": [],
        },
        "summary": {
            "s0_count": 0, "s1_count": 0, "blockers": [],
            "confusion_runtime_pct": 0.0, "confusion_privacy_pct": 0.0,
        },
        "recommendation": "hold",
        "_runner_artifacts": results,
    }
    skel_path = root / "trip-report.skeleton.json"
    skel_path.write_text(json.dumps(skeleton, indent=2), encoding="utf-8")
    return skel_path

def _empty_workflow() -> dict:
    return {"completed": False, "duration_seconds": 0, "blocker": None,
            "confusion_notes": [], "screenshots": [], "logcat_excerpts": []}

def _empty_failure() -> dict:
    return {"recovered": False, "deterministic_code_seen": None,
            "cta_path_taken": None, "duration_seconds": 0, "notes": ""}

def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--tester", required=True,
                   choices=list(DEVICES) + list(CLOUD))
    args = p.parse_args(argv)

    started = utc()
    root = TMP / args.tester / started
    root.mkdir(parents=True, exist_ok=True)

    if args.tester in DEVICES:
        results = run_device(args.tester, root)
        device = DEVICES[args.tester]
        kind = "physical-device"
    else:
        results = run_cloud(args.tester, root)
        device = {
            "label": f"Maestro Cloud ({CLOUD[args.tester][1]})",
            "serial": "n/a",
            "android_api": 34,
            "manufacturer": "hosted",
            "model": "Pixel 6 (per Maestro Cloud Android default)",
        }
        kind = "maestro-cloud"

    skel = make_skeleton(args.tester, kind, device, root, results, started)
    print(f"\nTrip-report skeleton: {skel}")
    print(f"Artifacts root: {root}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
