"""Unit tests for tools/qa-agents/run_ai_tester.py."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

QA = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("run_ai_tester", QA / "run_ai_tester.py")
assert SPEC and SPEC.loader
mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mod
SPEC.loader.exec_module(mod)


def test_devices_have_known_serials() -> None:
    assert mod.DEVICES["device-s22"]["serial"].startswith("adb-RFCT2178PDV")
    assert mod.DEVICES["device-a51"]["serial"].startswith("adb-RR8NB087YTF")


def test_cloud_uses_known_env_vars() -> None:
    assert mod.CLOUD["cloud-1"][0] == "MAESTRO_CLOUD_API_KEY"
    assert mod.CLOUD["cloud-2"][0] == "MAESTRO_CLOUD_API_KEY_2"


def test_journey_covers_required_contracts() -> None:
    names = [n for n, _ in mod.JOURNEY]
    for r in (
        "workflow-A-send",
        "workflow-B-tools",
        "workflow-C-image",
        "recovery-notready",
        "stuck-send",
        "manifest-outage",
    ):
        assert r in names, f"missing journey step {r}"


def test_extract_provenance_from_log() -> None:
    text = "See https://console.mobile.dev/uploads/mupload_ABC123xyz for details"
    prov = mod.extract_provenance_from_log(text)
    assert prov["upload_id"] == "mupload_ABC123xyz"
