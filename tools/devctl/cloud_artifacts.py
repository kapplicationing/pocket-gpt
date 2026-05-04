from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any
from xml.etree import ElementTree

_ACCOUNT_BLOCKER_PATTERNS = (
    r"trial has not started yet",
    r"enter your company name",
    r"start the free trial",
)
_UPLOAD_URL_RE = re.compile(
    r"https://app\.maestro\.dev/project/(?P<project_id>[^/\s]+)/maestro-test/app/(?P<app_id>[^/\s]+)/upload/(?P<upload_id>[^/\s]+)"
)
_APP_BINARY_RE = re.compile(r"App binary id:\s*(?P<app_binary_id>\S+)")
_FLOW_RESULT_RE = re.compile(
    r"^\[(?P<status>Passed|Failed)\]\s+(?P<name>\S+)\s+\((?P<duration>[^)]+)\)(?:\s+\((?P<message>.*)\))?$"
)
_PROCESS_EXIT_RE = re.compile(r"Process will exit with code (?P<code>\d+) \((?P<label>[^)]+)\)")
_STATUS_FETCH_FAILURE_RE = re.compile(
    r"Failed to fetch the status of an upload\s+(?P<upload_id>\S+)\.\s+Status code = (?P<status_code>\S+)"
)
_PROJECT_FETCH_FAILURE_RE = re.compile(r"Failed to fetch projects\.\s+Status code:\s*(?P<status_code>\S+)")


def _read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def _json_dump(payload: dict[str, Any]) -> str:
    return json.dumps(payload, sort_keys=True)


def has_external_account_blocker(text: str) -> bool:
    return any(re.search(pattern, text, flags=re.IGNORECASE) for pattern in _ACCOUNT_BLOCKER_PATTERNS)


def parse_cli_output(text: str) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "account_blocked": has_external_account_blocker(text),
        "app_binary_id": None,
        "app_id": None,
        "flow_results": [],
        "process_exit_code": None,
        "process_exit_label": None,
        "project_id": None,
        "project_fetch_failed": False,
        "project_fetch_status_code": None,
        "status_fetch_failed": False,
        "status_fetch_status_code": None,
        "upload_id": None,
        "upload_url": None,
        "waiting_for_completion": "Waiting for runs to be completed..." in text,
    }

    for line in text.splitlines():
        stripped = line.strip()
        if not payload["upload_url"]:
            upload_match = _UPLOAD_URL_RE.search(stripped)
            if upload_match:
                payload["upload_url"] = upload_match.group(0)
                payload["project_id"] = upload_match.group("project_id")
                payload["app_id"] = upload_match.group("app_id")
                payload["upload_id"] = upload_match.group("upload_id")
                continue
        if not payload["app_binary_id"]:
            binary_match = _APP_BINARY_RE.search(stripped)
            if binary_match:
                payload["app_binary_id"] = binary_match.group("app_binary_id")
                continue
        flow_match = _FLOW_RESULT_RE.match(stripped)
        if flow_match:
            payload["flow_results"].append(
                {
                    "duration": flow_match.group("duration"),
                    "message": flow_match.group("message") or None,
                    "name": flow_match.group("name"),
                    "status": flow_match.group("status").lower(),
                }
            )
            continue
        process_exit_match = _PROCESS_EXIT_RE.search(stripped)
        if process_exit_match:
            payload["process_exit_code"] = int(process_exit_match.group("code"))
            payload["process_exit_label"] = process_exit_match.group("label").lower()
            continue
        status_fetch_match = _STATUS_FETCH_FAILURE_RE.search(stripped)
        if status_fetch_match:
            payload["status_fetch_failed"] = True
            payload["status_fetch_status_code"] = status_fetch_match.group("status_code")
            if not payload["upload_id"]:
                payload["upload_id"] = status_fetch_match.group("upload_id")
            continue
        project_fetch_match = _PROJECT_FETCH_FAILURE_RE.search(stripped)
        if project_fetch_match:
            payload["project_fetch_failed"] = True
            payload["project_fetch_status_code"] = project_fetch_match.group("status_code")

    failed_flows = [item for item in payload["flow_results"] if item["status"] == "failed"]
    passed_flows = [item for item in payload["flow_results"] if item["status"] == "passed"]
    payload["failed_flows"] = failed_flows
    payload["first_failed_flow"] = failed_flows[0] if failed_flows else None
    payload["passed_flows"] = passed_flows
    payload["last_reported_flow"] = payload["flow_results"][-1] if payload["flow_results"] else None
    return payload


def summarize_junit(junit_path: Path) -> dict[str, Any]:
    if not junit_path.exists():
        return {
            "first_failed_case": None,
            "failed_cases": [],
            "failures": 0,
            "junit_present": False,
            "tests": 0,
        }

    root = ElementTree.parse(junit_path).getroot()
    cases: list[dict[str, Any]] = []
    for case in root.iter("testcase"):
        failure = case.find("failure")
        cases.append(
            {
                "classname": case.get("classname"),
                "message": failure.text.strip() if failure is not None and failure.text else None,
                "name": case.get("name"),
                "status": "failed" if failure is not None else "passed",
                "time": case.get("time"),
            }
        )

    failed_cases = [item for item in cases if item["status"] == "failed"]
    suite_tests = sum(int(item.get("tests", 0)) for item in root.iter("testsuite"))
    suite_failures = sum(int(item.get("failures", 0)) for item in root.iter("testsuite"))

    return {
        "first_failed_case": failed_cases[0] if failed_cases else None,
        "failed_cases": failed_cases,
        "failures": suite_failures or len(failed_cases),
        "junit_present": True,
        "tests": suite_tests or len(cases),
    }


def build_api_run_status(
    *,
    android_api_level: str,
    cli_exit_code: int,
    cli_output_path: Path,
    completed_at_utc: str,
    junit_path: Path,
) -> dict[str, Any]:
    cli_summary = parse_cli_output(_read_text(cli_output_path))
    junit_summary = summarize_junit(junit_path)

    status = "passed"
    blocker_key = None
    blocker_message = None

    if cli_summary["account_blocked"]:
        status = "blocked_external_account_setup"
        blocker_key = "blocked_external_account_setup"
        blocker_message = (
            "Maestro Cloud account activation is incomplete. Start the trial and provide the required "
            "company name in the Maestro Cloud account before rerunning."
        )
    elif (
        cli_summary["project_fetch_failed"]
        and not cli_summary["failed_flows"]
        and junit_summary["failures"] == 0
    ):
        status = "infra_status_fetch_failed"
        blocker_key = "maestro_cloud_project_fetch_failed"
        blocker_message = (
            "Maestro Cloud project fetch failed before an upload was created. "
            f"Status code: {cli_summary['project_fetch_status_code']}."
        )
    elif cli_summary["failed_flows"] or junit_summary["failures"] > 0:
        status = "failed"
    elif (
        cli_summary["status_fetch_failed"]
        and not cli_summary["failed_flows"]
        and junit_summary["failures"] == 0
    ):
        status = "infra_status_fetch_failed"
        blocker_key = "maestro_cloud_status_fetch_failed"
        if cli_summary["passed_flows"]:
            passed_flow_names = ", ".join(item["name"] for item in cli_summary["passed_flows"])
            blocker_message = (
                "Maestro Cloud upload status polling failed after partial hosted results were returned. "
                f"Completed flows before polling failed: {passed_flow_names}. "
                f"Upload id: {cli_summary['upload_id']} (status code {cli_summary['status_fetch_status_code']})."
            )
        else:
            blocker_message = (
                "Maestro Cloud upload status polling failed before hosted results were returned. "
                f"Upload id: {cli_summary['upload_id']} (status code {cli_summary['status_fetch_status_code']})."
            )
    elif cli_summary["waiting_for_completion"] and cli_summary["process_exit_code"] is None and not junit_summary["junit_present"]:
        status = "running"
    elif cli_summary["upload_id"] and cli_summary["process_exit_code"] is None and not cli_summary["flow_results"]:
        status = "uploaded"
    elif cli_exit_code != 0 or (cli_summary["process_exit_code"] not in (None, 0)):
        status = "failed"

    if blocker_message is None and status == "failed":
        first_failed_flow = cli_summary["first_failed_flow"]
        if first_failed_flow and first_failed_flow.get("message"):
            blocker_message = f"First failing flow: {first_failed_flow['name']} ({first_failed_flow['message']})"
        elif cli_exit_code != 0:
            blocker_message = "Maestro Cloud CLI exited non-zero before producing a passing hosted smoke result."

    return {
        "android_api_level": android_api_level,
        "app_binary_id": cli_summary["app_binary_id"],
        "app_id": cli_summary["app_id"],
        "blocked_external_account_setup": status == "blocked_external_account_setup",
        "blocker_key": blocker_key,
        "blocker_message": blocker_message,
        "cli_exit_code": cli_exit_code,
        "cli_output_path": str(cli_output_path),
        "completed_at_utc": completed_at_utc,
        "failed_flows": cli_summary["failed_flows"],
        "first_failed_flow": cli_summary["first_failed_flow"],
        "flow_results": cli_summary["flow_results"],
        "last_reported_flow": cli_summary["last_reported_flow"],
        "junit_failures": junit_summary["failures"],
        "junit_path": str(junit_path),
        "junit_present": junit_summary["junit_present"],
        "junit_tests": junit_summary["tests"],
        "passed_flows": cli_summary["passed_flows"],
        "process_exit_code": cli_summary["process_exit_code"],
        "project_id": cli_summary["project_id"],
        "project_fetch_failed": cli_summary["project_fetch_failed"],
        "project_fetch_status_code": cli_summary["project_fetch_status_code"],
        "status_fetch_failed": cli_summary["status_fetch_failed"],
        "status_fetch_status_code": cli_summary["status_fetch_status_code"],
        "status": status,
        "upload_id": cli_summary["upload_id"],
        "upload_url": cli_summary["upload_url"],
        "waiting_for_completion": cli_summary["waiting_for_completion"],
    }


def aggregate_api_run_statuses(*, completed_at_utc: str, run_root: str, status_paths: list[Path]) -> dict[str, Any]:
    api_runs = [json.loads(path.read_text(encoding="utf-8")) for path in status_paths]
    statuses = {str(item.get("status")) for item in api_runs}

    if "failed" in statuses:
        overall_status = "failed"
    elif "infra_status_fetch_failed" in statuses:
        overall_status = "infra_status_fetch_failed"
    elif "blocked_external_account_setup" in statuses:
        overall_status = "blocked_external_account_setup"
    elif "running" in statuses:
        overall_status = "running"
    elif "uploaded" in statuses:
        overall_status = "uploaded"
    else:
        overall_status = "passed"

    first_failed_flow = next((item.get("first_failed_flow") for item in api_runs if item.get("first_failed_flow")), None)
    upload_ids = [item["upload_id"] for item in api_runs if item.get("upload_id")]

    return {
        "api_runs": api_runs,
        "blocked_external_account_setup": overall_status == "blocked_external_account_setup",
        "completed_at_utc": completed_at_utc,
        "first_failed_flow": first_failed_flow,
        "run_root": run_root,
        "status": overall_status,
        "upload_ids": upload_ids,
    }


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python3 -m tools.devctl.cloud_artifacts")
    sub = parser.add_subparsers(dest="command", required=True)

    status = sub.add_parser("status", help="summarize one Maestro Cloud API-level run")
    status.add_argument("--android-api-level", required=True)
    status.add_argument("--cli-exit-code", type=int, required=True)
    status.add_argument("--cli-output", required=True)
    status.add_argument("--completed-at", required=True)
    status.add_argument("--junit", required=True)

    aggregate = sub.add_parser("aggregate", help="aggregate multiple Maestro Cloud API-level statuses")
    aggregate.add_argument("--completed-at", required=True)
    aggregate.add_argument("--run-root", required=True)
    aggregate.add_argument("status_files", nargs="+")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.command == "status":
        payload = build_api_run_status(
            android_api_level=args.android_api_level,
            cli_exit_code=args.cli_exit_code,
            cli_output_path=Path(args.cli_output),
            completed_at_utc=args.completed_at,
            junit_path=Path(args.junit),
        )
        print(_json_dump(payload))
        return 0

    payload = aggregate_api_run_statuses(
        completed_at_utc=args.completed_at,
        run_root=args.run_root,
        status_paths=[Path(item) for item in args.status_files],
    )
    print(_json_dump(payload))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
