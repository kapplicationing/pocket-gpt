import importlib.util
import sys
from pathlib import Path

QA_AGENTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "run_ai_tester", QA_AGENTS / "run_ai_tester.py")
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
assert spec.loader is not None
spec.loader.exec_module(mod)

def test_devices_have_known_serials():
    assert mod.DEVICES["device-s22"]["serial"].startswith("adb-RFCT2178PDV")
    assert mod.DEVICES["device-a51"]["serial"].startswith("adb-RR8NB087YTF")

def test_cloud_uses_known_env_vars():
    assert mod.CLOUD["cloud-1"][0] == "MAESTRO_CLOUD_API_KEY"
    assert mod.CLOUD["cloud-2"][0] == "MAESTRO_CLOUD_API_KEY_2"

def test_journey_covers_all_required_rows():
    names = [n for n, _ in mod.JOURNEY]
    for r in ("workflow-A-send", "workflow-B-tools", "workflow-C-image",
              "recovery-notready", "stuck-send", "manifest-outage"):
        assert r in names, f"missing journey step {r}"
