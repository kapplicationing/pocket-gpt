"""Tests for aggregate_wp13."""

from __future__ import annotations

import json
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
QA_AGENTS = ROOT / "tools" / "qa-agents"


def _load_agg():
    spec = importlib.util.spec_from_file_location(
        "aggregate_wp13", QA_AGENTS / "aggregate_wp13.py"
    )
    assert spec and spec.loader
    m = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = m
    spec.loader.exec_module(m)
    return m


def _full_report(passing: bool) -> dict:
    wf = {
        "completed": passing,
        "duration_seconds": 1.0,
        "blocker": None,
        "confusion_notes": [],
        "screenshots": [],
        "logcat_excerpts": [],
    }
    fail = {
        "recovered": passing,
        "deterministic_code_seen": "UI-RUNTIME-001",
        "cta_path_taken": "retry",
        "duration_seconds": 1.0,
        "notes": "",
    }
    return {
        "tester_id": "cloud-1",
        "tester_kind": "maestro-cloud",
        "device": {"label": "x", "serial": "n/a", "android_api": 34},
        "build": {"commit": "abc", "apk_path": "x", "branch_tip": "main"},
        "timestamps": {"started_utc": "x", "ended_utc": "y"},
        "workflows": {"A": wf, "B": wf, "C": wf},
        "failure_states": {
            "recovery_not_ready": fail,
            "stuck_send": fail,
            "manifest_outage": fail,
        },
        "advanced_controls": {
            "profiles_visible": ["BATTERY", "BALANCED", "FAST"],
            "gpu_toggle_observed": True,
            "diagnostics_export_ok": True,
            "keepalive_options_visible": ["AUTO"],
        },
        "summary": {
            "s0_count": 0,
            "s1_count": 0,
            "blockers": [],
            "confusion_runtime_pct": 0.0,
            "confusion_privacy_pct": 0.0,
        },
        "recommendation": "promote" if passing else "hold",
    }


def test_aggregator_promotes_on_all_pass(tmp_path, monkeypatch) -> None:
    mod = _load_agg()
    inputs = tmp_path / "_inputs"
    inputs.mkdir()
    for i in range(4):
        (inputs / f"r{i}.json").write_text(json.dumps(_full_report(True)))
    out = tmp_path / "wp13.md"
    monkeypatch.setattr(mod, "IN", inputs)
    monkeypatch.setattr(mod, "OUT", out)
    assert mod.main() == 0
    assert "promote" in out.read_text()


def test_aggregator_holds_on_failures(tmp_path, monkeypatch) -> None:
    mod = _load_agg()
    inputs = tmp_path / "_inputs"
    inputs.mkdir()
    for i in range(4):
        (inputs / f"r{i}.json").write_text(json.dumps(_full_report(False)))
    out = tmp_path / "wp13.md"
    monkeypatch.setattr(mod, "IN", inputs)
    monkeypatch.setattr(mod, "OUT", out)
    assert mod.main() == 1
    assert "hold" in out.read_text()
