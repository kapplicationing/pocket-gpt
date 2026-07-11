from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.devctl.weekly_send_capture import build_weekly_packet, main


def journey_payload(*, status: str = "passed", phase: str = "completed", placeholder: bool = False) -> dict:
    return {
        "serial": "required-device",
        "steps": [
            {
                "name": "run-01:send-capture",
                "status": status,
                "phase": phase,
                "elapsed_ms": 42_000,
                "runtime_status": "Ready",
                "backend": "NATIVE_JNI",
                "active_model_id": "qwen3.5-0.8b-q4",
                "placeholder_visible": placeholder,
                "failure_signature": None,
            },
        ],
    }


class WeeklySendCaptureTest(unittest.TestCase):
    def test_workflow_closes_stale_blocking_issue_after_resolved_packet(self) -> None:
        repo_root = Path(__file__).resolve().parents[3]
        workflow = (repo_root / ".github/workflows/nightly-hardware-lane.yml").read_text(encoding="utf-8")

        self.assertIn("Reconcile QA-13 blocking issue", workflow)
        self.assertIn("if (!gateFailed)", workflow)
        self.assertIn("state: 'closed'", workflow)
        self.assertIn("stale release blocker closed automatically", workflow)
        self.assertEqual(1, workflow.count("issues: write"))

    def test_passing_contract_emits_weekly_pass_baseline(self) -> None:
        packet = build_weekly_packet(
            journey_payload=journey_payload(),
            journey_report=Path("journey-report.json"),
            journey_log=Path("journey.log"),
            lane_exit_code=0,
        )

        self.assertEqual("PASS", packet["status"])
        self.assertEqual("baseline", packet["delta_vs_prior_week"])
        self.assertIsNone(packet["severity"])
        self.assertFalse(packet["blocking_issue_required"])
        self.assertEqual([], packet["missing_required_fields"])
        self.assertEqual("required-device", packet["device"])

    def test_timeout_after_previous_pass_is_blocking_regression(self) -> None:
        packet = build_weekly_packet(
            journey_payload=journey_payload(status="failed", phase="timeout", placeholder=True),
            journey_report=Path("journey-report.json"),
            journey_log=Path("journey.log"),
            lane_exit_code=1,
            previous_payload={"status": "PASS"},
        )

        self.assertEqual("FAIL", packet["status"])
        self.assertEqual("regressed", packet["delta_vs_prior_week"])
        self.assertEqual("UX-S1", packet["severity"])
        self.assertTrue(packet["blocking_issue_required"])
        self.assertIn("phase=timeout", packet["failure_reasons"])

    def test_cli_writes_failure_packet_when_journey_report_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            log = root / "journey.log"
            log.write_text("instrumentation failed before report creation\n", encoding="utf-8")
            output = root / "packet"

            exit_code = main(
                [
                    "--journey-log",
                    str(log),
                    "--output-dir",
                    str(output),
                    "--lane-exit-code",
                    "1",
                ],
            )

            self.assertEqual(1, exit_code)
            payload = json.loads((output / "qa-13-weekly-report.json").read_text(encoding="utf-8"))
            self.assertEqual("FAIL", payload["status"])
            self.assertIn("journey report missing", payload["failure_reasons"])
            self.assertTrue((output / "qa-13-weekly-summary.md").exists())

    def test_cli_discovers_journey_report_from_log_and_resolves_prior_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            journey_report = root / "journey-report.json"
            journey_report.write_text(json.dumps(journey_payload()), encoding="utf-8")
            log = root / "journey.log"
            log.write_text(f"[devctl] Journey report: {journey_report}\n", encoding="utf-8")
            previous = root / "previous.json"
            previous.write_text(json.dumps({"status": "FAIL"}), encoding="utf-8")
            output = root / "packet"

            exit_code = main(
                [
                    "--journey-log",
                    str(log),
                    "--previous-report",
                    str(previous),
                    "--output-dir",
                    str(output),
                    "--lane-exit-code",
                    "0",
                ],
            )

            self.assertEqual(0, exit_code)
            payload = json.loads((output / "qa-13-weekly-report.json").read_text(encoding="utf-8"))
            self.assertEqual("PASS", payload["status"])
            self.assertEqual("resolved", payload["delta_vs_prior_week"])
            self.assertEqual(str(journey_report.resolve()), payload["journey_report"])

    def test_corrupt_prior_report_does_not_turn_current_pass_into_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            journey_report = root / "journey-report.json"
            journey_report.write_text(json.dumps(journey_payload()), encoding="utf-8")
            log = root / "journey.log"
            log.write_text(f"Journey report: {journey_report}\n", encoding="utf-8")
            previous = root / "previous.json"
            previous.write_text("{not-json", encoding="utf-8")
            output = root / "packet"

            exit_code = main(
                [
                    "--journey-log",
                    str(log),
                    "--previous-report",
                    str(previous),
                    "--output-dir",
                    str(output),
                    "--lane-exit-code",
                    "0",
                ],
            )

            self.assertEqual(0, exit_code)
            payload = json.loads((output / "qa-13-weekly-report.json").read_text(encoding="utf-8"))
            self.assertEqual("PASS", payload["status"])
            self.assertEqual("baseline", payload["delta_vs_prior_week"])
            self.assertIn("prior weekly report ignored", payload["warnings"][0])


if __name__ == "__main__":
    unittest.main()
