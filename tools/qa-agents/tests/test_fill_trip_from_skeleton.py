"""Tests for fill_trip_from_skeleton."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
QA_AGENTS = ROOT / "tools" / "qa-agents"


def _load_module():
    spec = importlib.util.spec_from_file_location(
        "fill_trip_from_skeleton",
        QA_AGENTS / "fill_trip_from_skeleton.py",
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _skeleton() -> dict:
    return {
        "tester_id": "cloud-1",
        "tester_kind": "maestro-cloud",
        "device": {"label": "Cloud", "android_api": 34},
        "build": {"commit": "abc", "apk_path": "x", "branch_tip": "main"},
        "timestamps": {"started_utc": "x", "ended_utc": "y"},
        "workflows": {
            "A": {
                "completed": False,
                "duration_seconds": 0.0,
                "blocker": None,
                "confusion_notes": [],
                "screenshots": [],
                "logcat_excerpts": [],
            },
            "B": {
                "completed": False,
                "duration_seconds": 0.0,
                "blocker": None,
                "confusion_notes": [],
                "screenshots": [],
                "logcat_excerpts": [],
            },
            "C": {
                "completed": False,
                "duration_seconds": 0.0,
                "blocker": None,
                "confusion_notes": [],
                "screenshots": [],
                "logcat_excerpts": [],
            },
        },
        "failure_states": {
            "recovery_not_ready": {
                "recovered": False,
                "deterministic_code_seen": None,
                "cta_path_taken": None,
                "duration_seconds": 0.0,
                "notes": "",
            },
            "stuck_send": {
                "recovered": False,
                "deterministic_code_seen": None,
                "cta_path_taken": None,
                "duration_seconds": 0.0,
                "notes": "",
            },
            "manifest_outage": {
                "recovered": False,
                "deterministic_code_seen": None,
                "cta_path_taken": None,
                "duration_seconds": 0.0,
                "notes": "",
            },
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
    }


def test_merge_from_step_results_seeds_current_blockers(tmp_path, monkeypatch) -> None:
    mod = _load_module()
    tester_root = tmp_path / "tmp" / "qa-agents" / "cloud-1" / "20260504T010203Z"
    tester_root.mkdir(parents=True)

    data = _skeleton()
    data["_runner_artifacts"] = {
        "capture_mode": "small-discovery-v1",
        "step_results": {
            "runtime-ready": {
                "status": "failed",
                "blocker_message": 'First failing flow: scenario-runtime-ready-smoke (Assertion is false: "Setup" is not visible)',
                "status_json": "tmp/qa-agents/cloud-1/20260504T010203Z/runtime-ready/status.json",
            },
            "send-after-ready": {
                "status": "blocked_missing_api_key",
                "blocker_message": "missing MAESTRO_CLOUD_API_KEY",
                "status_json": "tmp/qa-agents/cloud-1/20260504T010203Z/send-after-ready/status.json",
            },
        },
    }
    (tester_root / "trip-report.skeleton.json").write_text(json.dumps(data), encoding="utf-8")

    monkeypatch.setattr(mod, "TMP", tmp_path / "tmp" / "qa-agents")
    monkeypatch.setattr(mod, "OUT_DIR", tmp_path / "tools" / "qa-agents" / "_inputs")

    out_path = mod.merge_one("cloud-1", tester_root)
    payload = json.loads(out_path.read_text(encoding="utf-8"))

    assert payload["workflows"]["A"]["completed"] is False
    assert "send-after-ready" in payload["workflows"]["A"]["blocker"]
    assert payload["failure_states"]["recovery_not_ready"]["recovered"] is False
    assert payload["summary"]["s1_count"] == 1
    assert payload["recommendation"] == "hold"
    assert any("runtime-ready" in blocker for blocker in payload["summary"]["blockers"])
