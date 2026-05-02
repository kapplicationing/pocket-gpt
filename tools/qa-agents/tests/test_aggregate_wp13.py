import importlib.util
import json
import sys
from pathlib import Path

QA_AGENTS = Path(__file__).resolve().parents[1]

def _load():
    spec = importlib.util.spec_from_file_location(
        "agg", QA_AGENTS / "aggregate_wp13.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod

def _full_report(passing: bool) -> dict:
    return {
        "tester_id":"cloud-1","tester_kind":"maestro-cloud",
        "device":{"label":"x","android_api":34},
        "build":{"commit":"abc","apk_path":"x","branch_tip":"main"},
        "timestamps":{"started_utc":"x","ended_utc":"y"},
        "workflows":{k:{"completed":passing,"duration_seconds":1,"blocker":None,
                        "confusion_notes":[],"screenshots":[],"logcat_excerpts":[]}
                     for k in ("A","B","C")},
        "failure_states":{k:{"recovered":passing,"deterministic_code_seen":"UI-RUNTIME-001",
                              "cta_path_taken":"retry","duration_seconds":1,"notes":""}
                          for k in ("recovery_not_ready","stuck_send","manifest_outage")},
        "advanced_controls":{"profiles_visible":["BATTERY","BALANCED","FAST"],
                             "gpu_toggle_observed":True,"diagnostics_export_ok":True,
                             "keepalive_options_visible":["AUTO"]},
        "summary":{"s0_count":0,"s1_count":0,"blockers":[],
                   "confusion_runtime_pct":0,"confusion_privacy_pct":0},
        "recommendation":"promote" if passing else "hold",
    }

def test_aggregator_promotes_on_all_pass(tmp_path, monkeypatch):
    mod = _load()
    inputs = tmp_path / "_inputs"; inputs.mkdir()
    for i in range(4):
        (inputs / f"r{i}.json").write_text(json.dumps(_full_report(True)))
    out = tmp_path / "wp13.md"
    monkeypatch.setattr(mod, "IN", inputs); monkeypatch.setattr(mod, "OUT", out)
    assert mod.main() == 0
    assert "promote" in out.read_text(encoding="utf-8")
    assert "tmp/qa-agents/cloud-1/x/" in out.read_text(encoding="utf-8")

def test_aggregator_holds_on_failures(tmp_path, monkeypatch):
    mod = _load()
    inputs = tmp_path / "_inputs"; inputs.mkdir()
    for i in range(4):
        (inputs / f"r{i}.json").write_text(json.dumps(_full_report(False)))
    out = tmp_path / "wp13.md"
    monkeypatch.setattr(mod, "IN", inputs); monkeypatch.setattr(mod, "OUT", out)
    assert mod.main() == 1
    assert "hold" in out.read_text(encoding="utf-8")
