#!/usr/bin/env python3
"""Assemble a human-proxy moderator bundle around the existing QA-agent workflow.

This keeps deterministic artifact capture in run_ai_tester.py and keeps final
roll-up in aggregate_wp13.py. The bundle adds one obvious entrypoint and a
prompt/report pack that a sub-agent can use without repo spelunking.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
from pathlib import Path
from types import ModuleType

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
FLOW_TRUTH = REPO / "docs/testing/generated/launch-flow-truth.md"
SCHEMA = HERE / "trip_report.schema.json"
TEMPLATE = HERE / "trip_report.template.md"
DEFAULT_BUNDLE_DIRNAME = "human-proxy-bundle"


def _load_local_module(filename: str, module_name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(module_name, HERE / filename)
    if not spec or not spec.loader:
        raise RuntimeError(f"unable to load {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_runner_module() -> ModuleType:
    return _load_local_module("run_ai_tester.py", "_qa_run_ai_tester")


def load_aggregate_module() -> ModuleType:
    return _load_local_module("aggregate_wp13.py", "_qa_aggregate_wp13")


def _display(path: Path) -> str:
    try:
        return str(path.relative_to(REPO))
    except ValueError:
        return str(path)


def _choices(runner: ModuleType) -> list[str]:
    return list(runner.DEVICES) + list(runner.CLOUD)


def _tester_kind(runner: ModuleType, tester: str) -> str:
    return "physical-device" if tester in runner.DEVICES else "maestro-cloud"


def _latest_artifacts_root(tmp_root: Path, tester: str) -> Path:
    base = tmp_root / tester
    if not base.is_dir():
        raise SystemExit(f"no runs under {base}")
    stamps = sorted(path for path in base.iterdir() if path.is_dir())
    if not stamps:
        raise SystemExit(f"no stamp dirs under {base}")
    return stamps[-1]


def _run_and_resolve_artifacts_root(runner: ModuleType, tester: str) -> Path:
    base = runner.TMP / tester
    before = {path.resolve() for path in base.iterdir() if path.is_dir()} if base.is_dir() else set()
    exit_code = runner.main(["--tester", tester])
    if exit_code:
        raise SystemExit(exit_code)
    after = {path.resolve() for path in base.iterdir() if path.is_dir()} if base.is_dir() else set()
    created = sorted(after - before)
    if created:
        return created[-1]
    return _latest_artifacts_root(runner.TMP, tester)


def _require_file(path: Path) -> Path:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path


def _copy_bundle_asset(src: Path, dest: Path) -> None:
    shutil.copy2(src, dest)


def _human_proxy_output_path(aggregate: ModuleType) -> Path:
    return getattr(aggregate, "OUT_HUMAN_PROXY", aggregate.OUT)


def build_bundle(
    *,
    runner: ModuleType,
    aggregate: ModuleType,
    tester: str,
    artifacts_root: Path,
    bundle_dirname: str = DEFAULT_BUNDLE_DIRNAME,
    flow_truth: Path = FLOW_TRUTH,
) -> Path:
    artifacts_root = artifacts_root.resolve()
    skeleton = _require_file(artifacts_root / "trip-report.skeleton.json")
    first_failure = _require_file(artifacts_root / "first-failure-index.md")
    bundle_root = artifacts_root / bundle_dirname
    bundle_root.mkdir(parents=True, exist_ok=True)

    schema_dest = bundle_root / SCHEMA.name
    template_dest = bundle_root / TEMPLATE.name
    checklist_dest = bundle_root / "workflow-checklist.md"
    prompt_dest = bundle_root / "moderator-prompt.md"
    start_here_dest = bundle_root / "START_HERE.md"
    prompt_alias_dest = bundle_root / "SUBAGENT_PROMPT.md"
    manifest_dest = bundle_root / "bundle-manifest.json"
    skeleton_dest = bundle_root / "trip-report.skeleton.json"

    _copy_bundle_asset(_require_file(SCHEMA), schema_dest)
    _copy_bundle_asset(_require_file(TEMPLATE), template_dest)
    _copy_bundle_asset(skeleton, skeleton_dest)

    output_json = HERE / "_inputs" / f"{tester}.json"
    seed_command = (
        "python3 tools/qa-agents/fill_trip_from_skeleton.py "
        f"--tester {tester} --stamp {artifacts_root}"
    )
    aggregate_output = _human_proxy_output_path(aggregate)
    aggregate_command = "python3 tools/qa-agents/aggregate_wp13.py --packet-kind ai-human-proxy"
    rerun_command = f"python3 tools/qa-agents/run_ai_tester.py --tester {tester}"

    manifest = {
        "bundle_kind": "ai-human-proxy-moderator",
        "packet_kind": "ai-human-proxy",
        "capture_mode": getattr(runner, "DISCOVERY_MODE", "legacy"),
        "tester_id": tester,
        "tester_kind": _tester_kind(runner, tester),
        "bundle_root": str(bundle_root),
        "artifacts_root": str(artifacts_root),
        "flow_truth_path": str(flow_truth),
        "first_failure_index_path": str(first_failure),
        "trip_report_skeleton_path": str(skeleton_dest),
        "trip_report_schema_path": str(schema_dest),
        "trip_report_template_path": str(template_dest),
        "trip_report_output_path": str(output_json),
        "aggregate_output_path": str(aggregate_output),
        "discovery_path": [{"name": name, "artifact_path": flow} for name, flow in runner.JOURNEY],
        "journey": [{"name": name, "flow_path": flow} for name, flow in runner.JOURNEY],
        "commands": {
            "seed_report": seed_command,
            "aggregate_all_testers": aggregate_command,
            "rerun_deterministic_capture": rerun_command,
        },
    }
    manifest_dest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    checklist_text = "\n".join(
        [
            "# Human-Proxy Workflow Checklist",
            "",
            f"- [ ] Read `{_display(flow_truth)}` before judging labels, states, or error codes.",
            f"- [ ] Read `{_display(first_failure)}` to find the first deterministic break fast.",
            "- [ ] Inspect the short discovery-step artifacts first; widen only if they do not answer the release question.",
            f"- [ ] Run `{seed_command}` to seed `{_display(output_json)}` from the skeleton.",
            "- [ ] Fill the seeded JSON with human-like usability notes and calibrated severity.",
            "- [ ] Keep evidence grounded in visible behavior or deterministic logs only.",
            f"- [ ] When all testers are done, run `{aggregate_command}`.",
        ]
    ) + "\n"
    checklist_dest.write_text(checklist_text, encoding="utf-8")

    prompt_text = "\n".join(
        [
            f"# AI Human-Proxy Moderator Prompt ({tester})",
            "",
            f"You are the human-proxy moderator for `{tester}`.",
            "Your job is to turn deterministic runner artifacts into a human-like usability evaluation.",
            "Judge the experience the way a careful first-time human evaluator would: look for hesitation points, misleading recovery, copy gaps, trust/privacy friction, and whether each journey feels complete without insider knowledge.",
            "",
            "## Source Of Truth",
            "",
            f"- Flow-truth path: `{_display(flow_truth)}`",
            f"- Artifact root: `{_display(artifacts_root)}`",
            f"- First failure index: `{_display(first_failure)}`",
            f"- Skeleton report: `{_display(skeleton_dest)}`",
            f"- Output schema: `{_display(schema_dest)}`",
            f"- Report template: `{_display(template_dest)}`",
            f"- Final JSON output: `{_display(output_json)}`",
            f"- Aggregated packet target: `{_display(aggregate_output)}`",
            "",
            "## Fast Start",
            "",
            "1. Read the flow-truth file first so expected selectors, labels, runtime states, and error codes are fresh.",
            "2. Skim the first-failure index, then inspect the matching short discovery-step evidence under the artifact root.",
            f"3. Seed the final JSON with `{seed_command}`.",
            "4. Edit the seeded JSON in place, filling only what the evidence supports.",
            "5. Use the markdown template only as a rendering aid; the JSON is the canonical output.",
            "",
            "## Workflow Checklist",
            "",
            "- Confirm whether workflows A/B/C would feel complete to a new human without hidden operator steps.",
            "- Separate deterministic failures from experiential problems. A passing flow can still carry confusion notes.",
            "- For failure-state journeys, judge whether the recovery CTA is understandable, credible, and sufficient.",
            "- Record screenshots/log references wherever they materially support a blocker or confusion note.",
            "- Keep wording concrete. Prefer exact controls, banners, and runtime states over abstract complaints.",
            "",
            "## Rubric",
            "",
            "- `workflows.*.completed`: `true` only when the intended outcome is reached without out-of-band rescue.",
            "- `workflows.*.blocker`: the first user-visible blocker or trust break that prevents completion.",
            "- `workflows.*.confusion_notes`: short evidence-backed notes about hesitation, ambiguity, or mislabeled states.",
            "- `failure_states.*.recovered`: `true` only when the recovery path gets the user back to a credible ready state.",
            "- `summary.s0_count`: count catastrophic blockers such as inability to start, data-loss risk, or privacy/security trust failure.",
            "- `summary.s1_count`: count major blockers where a core journey fails or recovery exists but is not realistically discoverable.",
            "- `summary.blockers`: concise strings that a release owner can scan without opening raw logs.",
            "- `summary.confusion_runtime_pct`: percentage of runtime/model-state moments that would likely confuse a first-time human.",
            "- `summary.confusion_privacy_pct`: percentage of privacy-sensitive moments with ambiguous copy, permissions, or data-handling cues.",
            "- `recommendation`: `promote` only when the experience is credible to ship; use `iterate` for contained polish gaps; use `hold` for major blockers.",
            "",
            "## Constraints",
            "",
            "- Do not invent screenshots, steps, or recovery behavior you did not observe.",
            "- Do not treat internal repo knowledge as something the end user sees.",
            "- Prefer exact artifact paths over prose like 'see logs'.",
            "- If evidence conflicts, call out the conflict in the report instead of smoothing it over.",
            "",
            "## Handoff",
            "",
            f"- Deterministic capture can be rerun with `{rerun_command}`.",
            f"- Final roll-up still happens through `{aggregate_command}` after all tester JSON files are ready.",
            "",
        ]
    )
    prompt_dest.write_text(prompt_text, encoding="utf-8")
    start_here_dest.write_text(
        "\n".join(
            [
                "# AI Human-Proxy Bundle",
                "",
                f"1. Read `SUBAGENT_PROMPT.md` in `{_display(bundle_root)}`.",
                f"2. Read `{_display(first_failure)}`.",
                f"3. Run `{seed_command}`.",
                f"4. Edit `{_display(output_json)}` using `{_display(schema_dest)}` and `{_display(template_dest)}`.",
                f"5. When all testers are done, run `{aggregate_command}`.",
                "",
            ]
        ),
        encoding="utf-8",
    )
    prompt_alias_dest.write_text(prompt_text, encoding="utf-8")
    return bundle_root


def main(argv: list[str] | None = None) -> int:
    runner = load_runner_module()
    aggregate = load_aggregate_module()

    parser = argparse.ArgumentParser()
    parser.add_argument("--tester", required=True, choices=_choices(runner))
    parser.add_argument(
        "--artifacts-root",
        type=Path,
        help="Existing tmp/qa-agents/<tester>/<stamp> directory. Defaults to latest run for the tester.",
    )
    parser.add_argument(
        "--run",
        action="store_true",
        help="Run tools/qa-agents/run_ai_tester.py first, then assemble the bundle around the new artifacts.",
    )
    parser.add_argument(
        "--bundle-dirname",
        default=DEFAULT_BUNDLE_DIRNAME,
        help=f"Name for the bundle directory under the artifacts root (default: {DEFAULT_BUNDLE_DIRNAME}).",
    )
    args = parser.parse_args(argv)

    if args.run and args.artifacts_root:
        raise SystemExit("--run cannot be combined with --artifacts-root")

    if args.run:
        artifacts_root = _run_and_resolve_artifacts_root(runner, args.tester)
    else:
        artifacts_root = args.artifacts_root or _latest_artifacts_root(runner.TMP, args.tester)

    bundle_root = build_bundle(
        runner=runner,
        aggregate=aggregate,
        tester=args.tester,
        artifacts_root=artifacts_root,
        bundle_dirname=args.bundle_dirname,
    )
    print(f"bundle ready: {_display(bundle_root)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
