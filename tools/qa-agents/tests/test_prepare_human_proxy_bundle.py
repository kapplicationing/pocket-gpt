"""Tests for prepare_human_proxy_bundle."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
QA_AGENTS = ROOT / "tools" / "qa-agents"


def _load_module():
    spec = importlib.util.spec_from_file_location(
        "prepare_human_proxy_bundle",
        QA_AGENTS / "prepare_human_proxy_bundle.py",
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _fake_runner(tmp_root: Path) -> SimpleNamespace:
    stamp_name = "20260503T010203Z"

    def _main(argv: list[str] | None = None) -> int:
        assert argv is not None
        tester = argv[argv.index("--tester") + 1]
        root = tmp_root / tester / stamp_name
        root.mkdir(parents=True, exist_ok=True)
        (root / "trip-report.skeleton.json").write_text("{}", encoding="utf-8")
        (root / "first-failure-index.md").write_text(
            "# First Failure Index\n\n- First failing flow: `workflow-A-send`\n",
            encoding="utf-8",
        )
        return 0

    return SimpleNamespace(
        TMP=tmp_root,
        DISCOVERY_MODE="small-discovery-v1",
        DEVICES={"device-s22": {"label": "Device"}},
        CLOUD={"cloud-1": ("MAESTRO_CLOUD_API_KEY", "account-1")},
        JOURNEY=[
            ("android-instrumented", "python3 tools/devctl/main.py lane android-instrumented"),
            ("runtime-ready", "tests/maestro-cloud/scenario-runtime-ready-smoke.yaml"),
        ],
        main=_main,
    )


def test_build_bundle_writes_prompt_manifest_and_assets(tmp_path) -> None:
    mod = _load_module()
    artifacts_root = tmp_path / "tmp" / "qa-agents" / "cloud-1" / "20260503T010203Z"
    artifacts_root.mkdir(parents=True)
    (artifacts_root / "trip-report.skeleton.json").write_text("{}", encoding="utf-8")
    (artifacts_root / "first-failure-index.md").write_text(
        "# First Failure Index\n",
        encoding="utf-8",
    )
    flow_truth = tmp_path / "launch-flow-truth.md"
    flow_truth.write_text("# Flow Truth\n", encoding="utf-8")

    runner = _fake_runner(tmp_path / "tmp" / "qa-agents")
    aggregate = SimpleNamespace(
        OUT=tmp_path / "docs" / "packet.md",
        OUT_HUMAN_PROXY=tmp_path / "docs" / "packet-human-proxy.md",
    )

    bundle_root = mod.build_bundle(
        runner=runner,
        aggregate=aggregate,
        tester="cloud-1",
        artifacts_root=artifacts_root,
        flow_truth=flow_truth,
    )

    manifest = json.loads((bundle_root / "bundle-manifest.json").read_text(encoding="utf-8"))
    prompt = (bundle_root / "moderator-prompt.md").read_text(encoding="utf-8")
    checklist = (bundle_root / "workflow-checklist.md").read_text(encoding="utf-8")

    assert manifest["bundle_kind"] == "ai-human-proxy-moderator"
    assert manifest["packet_kind"] == "ai-human-proxy"
    assert manifest["capture_mode"] == "small-discovery-v1"
    assert manifest["tester_kind"] == "maestro-cloud"
    assert manifest["commands"]["seed_report"].startswith(
        "python3 tools/qa-agents/fill_trip_from_skeleton.py --tester cloud-1"
    )
    assert manifest["commands"]["aggregate_all_testers"].endswith(
        "--packet-kind ai-human-proxy"
    )
    assert manifest["aggregate_output_path"].endswith("packet-human-proxy.md")
    assert manifest["discovery_path"][0]["name"] == "android-instrumented"
    assert manifest["journey"][0]["name"] == "android-instrumented"
    assert (bundle_root / "trip_report.schema.json").is_file()
    assert (bundle_root / "trip_report.template.md").is_file()
    assert "## Rubric" in prompt
    assert "human-like usability evaluation" in prompt
    assert "aggregate_wp13.py" in prompt
    assert "fill_trip_from_skeleton.py" in checklist
    assert "short discovery-step artifacts" in checklist


def test_main_run_mode_reuses_runner_and_creates_bundle(tmp_path, monkeypatch) -> None:
    mod = _load_module()
    runner = _fake_runner(tmp_path / "tmp" / "qa-agents")
    aggregate = SimpleNamespace(
        OUT=tmp_path / "docs" / "packet.md",
        OUT_HUMAN_PROXY=tmp_path / "docs" / "packet-human-proxy.md",
    )

    monkeypatch.setattr(mod, "load_runner_module", lambda: runner)
    monkeypatch.setattr(mod, "load_aggregate_module", lambda: aggregate)

    assert mod.main(["--tester", "cloud-1", "--run"]) == 0

    bundle_root = (
        tmp_path
        / "tmp"
        / "qa-agents"
        / "cloud-1"
        / "20260503T010203Z"
        / mod.DEFAULT_BUNDLE_DIRNAME
    )
    assert bundle_root.is_dir()
    assert (bundle_root / "moderator-prompt.md").is_file()
