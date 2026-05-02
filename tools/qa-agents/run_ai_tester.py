"""Per-tester runner. Each AI tester sub-agent calls this once.

Modes:
  --tester cloud-1      → uses MAESTRO_CLOUD_API_KEY
  --tester cloud-2      → uses MAESTRO_CLOUD_API_KEY_2
  --tester device-s22   → wireless ADB serial (S906N)
  --tester device-a51   → wireless ADB serial (A515F)

Deterministic steps only: assemble APK, install or maestro cloud, run flows,
capture artifacts, emit trip-report skeleton. Qualitative fields stay empty
for the sub-agent to fill from screenshots/logcat.
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
    ("onboarding", "tests/maestro/scenario-onboarding.yaml"),
    ("workflow-A-send", "tests/maestro/scenario-a.yaml"),
    ("workflow-B-tools", "tests/maestro/scenario-b.yaml"),
    ("workflow-C-image", "tests/maestro/scenario-c.yaml"),
    ("recovery-notready", "tests/maestro/scenario-activation-send-smoke.yaml"),
    ("stuck-send", "tests/maestro/scenario-first-run-download-chat.yaml"),
    ("manifest-outage", "tests/maestro/scenario-download-settings-smoke.yaml"),
    ("session-shell", "tests/maestro/scenario-session-drawer-smoke.yaml"),
]


def utc_compact() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def utc_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run(cmd: list[str], **kw: object) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}", flush=True)
    return subprocess.run(cmd, check=False, **kw)  # type: ignore[arg-type]


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


def run_device(tester: str, root: Path) -> dict:
    cfg = DEVICES[tester]
    apk = ensure_apk()
    run(["adb", "-s", cfg["serial"], "install", "-r", str(apk)])
    flow_results: dict[str, dict] = {}
    for name, flow in JOURNEY:
        before_pngs = _snapshot_pngs()
        log_path = root / f"{name}.log"
        xml_path = root / f"{name}.xml"
        with open(log_path, "w", encoding="utf-8") as logf:
            proc = run(
                [
                    "maestro",
                    "--device",
                    cfg["serial"],
                    "test",
                    flow,
                    "--format",
                    "junit",
                    "--output",
                    str(xml_path),
                ],
                stdout=logf,
                stderr=subprocess.STDOUT,
            )
        screenshots = _collect_new_pngs(before_pngs, root)
        flow_results[name] = {
            "exit_code": proc.returncode,
            "log": str(log_path.relative_to(REPO)),
            "junit": str(xml_path.relative_to(REPO)),
            "screenshots_captured": screenshots,
        }
    lc = root / "logcat.tail.txt"
    with open(lc, "w", encoding="utf-8") as lcf:
        run(
            ["adb", "-s", cfg["serial"], "logcat", "-d", "-t", "5000"],
            stdout=lcf,
            stderr=subprocess.DEVNULL,
        )
    return {"flow_results": flow_results, "logcat": str(lc.relative_to(REPO))}


def run_cloud(tester: str, root: Path) -> dict:
    env_var, label = CLOUD[tester]
    api_key = os.environ.get(env_var)
    if not api_key and (REPO / ".env").is_file():
        for line in (REPO / ".env").read_text(encoding="utf-8").splitlines():
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
                [
                    "maestro",
                    "cloud",
                    "--api-key",
                    api_key,
                    "--android-api-level",
                    "34",
                    "--app-file",
                    str(apk),
                    "--flows",
                    flow,
                    "--format",
                    "junit",
                    "--output",
                    str(xml_path),
                ],
                stdout=logf,
                stderr=subprocess.STDOUT,
            )
        log_text = log_path.read_text(encoding="utf-8", errors="ignore")
        prov = extract_provenance_from_log(log_text)
        flow_results[name] = {
            "exit_code": proc.returncode,
            "log": str(log_path.relative_to(REPO)),
            "junit": str(xml_path.relative_to(REPO)),
            "account_label": label,
            "upload_id": prov.get("upload_id", ""),
            "project_id": prov.get("project_id", ""),
        }
    return {"flow_results": flow_results, "account_label": label}


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
        "summary": {
            "s0_count": 0,
            "s1_count": 0,
            "blockers": [],
            "confusion_runtime_pct": 0.0,
            "confusion_privacy_pct": 0.0,
        },
        "recommendation": "hold",
        "_runner_artifacts": results,
    }
    skel_path = root / "trip-report.skeleton.json"
    skel_path.write_text(json.dumps(skeleton, indent=2), encoding="utf-8")
    _write_first_failure_index(root, results)
    return skel_path


def _write_first_failure_index(root: Path, results: dict) -> None:
    flows = results.get("flow_results", {})
    failing = [(n, info) for n, info in flows.items() if info.get("exit_code")]
    lines = ["# First Failure Index", ""]
    if not failing:
        lines.append("No failing flow (all exit codes 0).")
    else:
        name, info = failing[0]
        lines += [
            f"- First failing flow: `{name}`",
            f"- Log: `{info.get('log', '')}`",
            f"- JUnit: `{info.get('junit', '')}`",
            f"- Exit code: {info.get('exit_code')}",
        ]
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
