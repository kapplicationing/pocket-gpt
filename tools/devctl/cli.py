from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from typing import Sequence

from tools.devctl.doctor import print_doctor_report, run_doctor
from tools.devctl.gates import dispatch_gate
from tools.devctl.governance import (
    docs_accuracy_check,
    docs_health_check,
    docs_drift_check,
    evidence_check,
    evidence_check_changed,
    launch_readiness_report,
    model_audit,
    screenshot_inventory_check,
    governance_self_test,
    stage_close_gate,
    validate_pr_body,
)
from tools.devctl import reporting
from tools.devctl.lanes import build_runtime_context, dispatch_lane
from tools.devctl.subprocess_utils import DevctlError


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="devctl")
    subparsers = parser.add_subparsers(dest="command", required=True)

    lane_parser = subparsers.add_parser("lane", help="run a named lane")
    lane_parser.add_argument("name", help="lane name")
    lane_parser.add_argument("args", nargs=argparse.REMAINDER, help="lane-specific args")

    gate_parser = subparsers.add_parser("gate", help="run a named gate")
    gate_parser.add_argument("args", nargs=argparse.REMAINDER, help="gate-specific args")

    gov_parser = subparsers.add_parser("governance", help="run governance checks")
    gov_sub = gov_parser.add_subparsers(dest="name", required=True)

    gov_sub.add_parser("docs-drift", help="validate docs drift")
    gov_sub.add_parser("docs-health", help="validate documentation health and policy rules")
    gov_sub.add_parser("docs-accuracy", help="validate feature-doc-code parity and emit drift report")
    gov_sub.add_parser("screenshot-inventory-check", help="validate screenshot inventory/reference/report contract")

    evidence = gov_sub.add_parser("evidence-check", help="validate evidence file paths")
    evidence.add_argument("file", help="evidence markdown path")

    evidence_changed = gov_sub.add_parser("evidence-check-changed", help="validate changed evidence files in PR")
    evidence_changed.add_argument("--event-name", default=os.environ.get("GITHUB_EVENT_NAME"))
    evidence_changed.add_argument("--base-ref", default=os.environ.get("GITHUB_BASE_REF"))

    validate = gov_sub.add_parser("validate-pr-body", help="validate PR body checkboxes")
    validate.add_argument("file", help="PR body markdown path")

    stage = gov_sub.add_parser("stage-close-gate", help="validate stage-close evidence requirements")
    stage.add_argument("file", help="PR body markdown path")

    gov_sub.add_parser("model-audit", help="cross-reference model catalog, distribution, routing, and scripts")
    gov_sub.add_parser("launch-readiness", help="compile the current launch readiness snapshot from board, matrix, and ticket docs")
    gov_sub.add_parser("self-test", help="run governance self-tests")

    report = subparsers.add_parser("report", help="inspect the latest Maestro/devctl report artifacts")
    report.add_argument("kind", choices=["journey", "screenshot-pack"], help="report family to inspect")
    report.add_argument("--root", type=str, default=None, help="override repository root")
    report.add_argument("--open", action="store_true", dest="open_files", help="open matched report files")

    doctor = subparsers.add_parser("doctor", help="run environment diagnostics")
    doctor.add_argument("--json", action="store_true", dest="as_json", help="emit machine-readable JSON")

    return parser


def _handle_lane(name: str, lane_args: Sequence[str]) -> None:
    try:
        from tools.devctl.config_models import load_devctl_configs
    except ModuleNotFoundError as exc:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            "Missing devctl dependencies. Run: python3 -m pip install -r tools/devctl/requirements.txt",
        ) from exc

    configs = load_devctl_configs()
    context = build_runtime_context(configs)
    dispatch_lane(name, lane_args, context)


def _handle_governance(parsed: argparse.Namespace) -> None:
    command = parsed.name
    if command == "docs-drift":
        docs_drift_check()
        return
    if command == "docs-health":
        docs_health_check()
        return
    if command == "docs-accuracy":
        docs_accuracy_check()
        return
    if command == "screenshot-inventory-check":
        screenshot_inventory_check()
        return
    if command == "evidence-check":
        evidence_check(parsed.file)
        return
    if command == "evidence-check-changed":
        evidence_check_changed(parsed.event_name, parsed.base_ref)
        return
    if command == "validate-pr-body":
        validate_pr_body(parsed.file)
        return
    if command == "stage-close-gate":
        stage_close_gate(parsed.file)
        return
    if command == "model-audit":
        model_audit()
        return
    if command == "launch-readiness":
        launch_readiness_report()
        return
    if command == "self-test":
        governance_self_test()
        return

    raise DevctlError("CONFIG_ERROR", f"Unknown governance command '{command}'")


def _handle_doctor(as_json: bool) -> int:
    report = run_doctor()
    print_doctor_report(report, as_json=as_json)
    return 0 if report.ok else 1


def _handle_report(parsed: argparse.Namespace) -> None:
    root = None if parsed.root is None else Path(parsed.root)
    reporting.show_report(parsed.kind, repo_root=root, open_files=parsed.open_files)


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    parsed = parser.parse_args(list(argv) if argv is not None else None)

    try:
        if parsed.command == "lane":
            _handle_lane(parsed.name, parsed.args)
            return 0

        if parsed.command == "gate":
            dispatch_gate(parsed.args)
            return 0

        if parsed.command == "governance":
            _handle_governance(parsed)
            return 0

        if parsed.command == "doctor":
            return _handle_doctor(parsed.as_json)

        if parsed.command == "report":
            _handle_report(parsed)
            return 0

        raise DevctlError("CONFIG_ERROR", f"Unknown command '{parsed.command}'")
    except DevctlError as exc:
        print(f"{exc.code}: {exc.message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
