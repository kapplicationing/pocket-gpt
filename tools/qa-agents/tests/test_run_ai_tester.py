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


def test_devices_have_declared_models_and_serial_aliases() -> None:
    assert mod.DEVICES["device-s22"]["model"] == "SM-S906N"
    assert mod.DEVICES["device-a51"]["model"] == "SM-A515F"
    assert mod.DEVICES["device-s22"]["serial"]
    assert mod.DEVICES["device-a51"]["serial"]


def test_cloud_uses_known_env_vars() -> None:
    assert mod.CLOUD["cloud-1"][0] == "MAESTRO_CLOUD_API_KEY"
    assert mod.CLOUD["cloud-2"][0] == "MAESTRO_CLOUD_API_KEY_2"


def test_journey_covers_current_small_discovery_contracts() -> None:
    names = [n for n, _ in mod.JOURNEY]
    for r in (
        "android-instrumented",
        "runtime-ready",
        "send-after-ready",
    ):
        assert r in names, f"missing journey step {r}"


def test_extract_provenance_from_log() -> None:
    text = "See https://console.mobile.dev/uploads/mupload_ABC123xyz for details"
    prov = mod.extract_provenance_from_log(text)
    assert prov["upload_id"] == "mupload_ABC123xyz"


def test_child_env_injects_sdk_root_when_absent(monkeypatch) -> None:
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)
    env = mod._child_env()
    home = Path.home()
    adb_mac = home / "Library/Android/sdk/platform-tools/adb"
    adb_linux = home / "Android/Sdk/platform-tools/adb"
    if adb_mac.is_file():
        assert env.get("ANDROID_HOME") == str(home / "Library/Android/sdk")
    elif adb_linux.is_file():
        assert env.get("ANDROID_HOME") == str(home / "Android/Sdk")
    else:
        # CI / headless without SDK — _child_env should still return a copy of os.environ
        assert isinstance(env, dict)


def test_resolve_live_serial_prefers_exact_match(monkeypatch) -> None:
    monkeypatch.setattr(
        mod,
        "_list_connected_devices",
        lambda: [
            {"serial": "192.168.1.38:36483", "state": "device", "model": "SM_S906N", "details": "", "device": "g0q", "product": "g0qksx"},
            {"serial": "SER123", "state": "device", "model": "SM_A515F", "details": "", "device": "a51", "product": "a51nsxx"},
        ],
    )
    cfg = {"label": "S22", "serial": "192.168.1.38:36483", "model": "SM-S906N"}
    assert mod._resolve_live_serial(cfg) == "192.168.1.38:36483"


def test_resolve_live_serial_falls_back_to_model(monkeypatch) -> None:
    monkeypatch.setattr(
        mod,
        "_list_connected_devices",
        lambda: [
            {"serial": "192.168.1.38:36483", "state": "device", "model": "SM_S906N", "details": "", "device": "g0q", "product": "g0qksx"},
            {"serial": "192.168.1.44:37643", "state": "device", "model": "SM_A515F", "details": "", "device": "a51", "product": "a51nsxx"},
        ],
    )
    cfg = {"label": "S22", "serial": "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp", "model": "SM-S906N"}
    assert mod._resolve_live_serial(cfg) == "192.168.1.38:36483"
