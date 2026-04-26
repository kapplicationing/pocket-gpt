from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.devctl import cloud_artifacts


class CloudArtifactsTest(unittest.TestCase):
    def test_build_api_run_status_marks_external_account_blocker(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log_path = root / "cli-output.log"
            log_path.write_text(
                "\n".join(
                    [
                        "Evaluating flow(s)...",
                        "[ERROR] Your trial has not started yet",
                        "[INPUT] Please enter your company name to start the free trial:",
                    ]
                ),
                encoding="utf-8",
            )

            payload = cloud_artifacts.build_api_run_status(
                android_api_level="34",
                cli_exit_code=1,
                cli_output_path=log_path,
                completed_at_utc="2026-04-26T00:00:00Z",
                junit_path=root / "junit.xml",
            )

            self.assertEqual("blocked_external_account_setup", payload["status"])
            self.assertEqual("blocked_external_account_setup", payload["blocker_key"])
            self.assertTrue(payload["blocked_external_account_setup"])

    def test_build_api_run_status_extracts_upload_and_failed_flows(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log_path = root / "cli-output.log"
            log_path.write_text(
                "\n".join(
                    [
                        "Visit Maestro Cloud for more details about this upload:",
                        "https://app.maestro.dev/project/proj_123/maestro-test/app/app_456/upload/mupload_789",
                        "App binary id: binary_abc",
                        "Waiting for runs to be completed...",
                        "[Failed] scenario-runtime-ready-smoke (1m 4.319s) (Element not found: Text matching regex: Skip)",
                        "[Passed] scenario-session-drawer-smoke (18.001s)",
                        "Process will exit with code 1 (FAIL)",
                    ]
                ),
                encoding="utf-8",
            )
            junit_path = root / "junit.xml"
            junit_path.write_text(
                "\n".join(
                    [
                        "<?xml version='1.0' encoding='UTF-8'?>",
                        "<testsuites>",
                        "  <testsuite name='Test Suite' tests='2' failures='1'>",
                        "    <testcase id='scenario-runtime-ready-smoke' name='scenario-runtime-ready-smoke' classname='scenario-runtime-ready-smoke'>",
                        "      <failure>Element not found: Text matching regex: Skip</failure>",
                        "    </testcase>",
                        "    <testcase id='scenario-session-drawer-smoke' name='scenario-session-drawer-smoke' classname='scenario-session-drawer-smoke'/>",
                        "  </testsuite>",
                        "</testsuites>",
                    ]
                ),
                encoding="utf-8",
            )

            payload = cloud_artifacts.build_api_run_status(
                android_api_level="34",
                cli_exit_code=1,
                cli_output_path=log_path,
                completed_at_utc="2026-04-26T00:00:00Z",
                junit_path=junit_path,
            )

            self.assertEqual("failed", payload["status"])
            self.assertEqual("mupload_789", payload["upload_id"])
            self.assertEqual("proj_123", payload["project_id"])
            self.assertEqual("binary_abc", payload["app_binary_id"])
            self.assertEqual("scenario-runtime-ready-smoke", payload["first_failed_flow"]["name"])
            self.assertEqual(1, len(payload["failed_flows"]))
            self.assertEqual(1, payload["junit_failures"])

    def test_build_api_run_status_reports_running_for_partial_upload(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log_path = root / "cli-output.log"
            log_path.write_text(
                "\n".join(
                    [
                        "Visit Maestro Cloud for more details about this upload:",
                        "https://app.maestro.dev/project/proj_123/maestro-test/app/app_456/upload/mupload_789",
                        "App binary id: binary_abc",
                        "Waiting for runs to be completed...",
                    ]
                ),
                encoding="utf-8",
            )

            payload = cloud_artifacts.build_api_run_status(
                android_api_level="34",
                cli_exit_code=0,
                cli_output_path=log_path,
                completed_at_utc="2026-04-26T00:00:00Z",
                junit_path=root / "junit.xml",
            )

            self.assertEqual("running", payload["status"])
            self.assertEqual("mupload_789", payload["upload_id"])
            self.assertTrue(payload["waiting_for_completion"])
            self.assertFalse(payload["junit_present"])

    def test_build_api_run_status_marks_partial_results_plus_status_fetch_failure_as_infra_blocker(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log_path = root / "cli-output.log"
            log_path.write_text(
                "\n".join(
                    [
                        "Visit Maestro Cloud for more details about this upload:",
                        "https://app.maestro.dev/project/proj_123/maestro-test/app/app_456/upload/mupload_789",
                        "App binary id: binary_abc",
                        "Waiting for runs to be completed...",
                        "[Passed] scenario-session-drawer-smoke (18.001s)",
                        "Failed to fetch the status of an upload mupload_789. Status code = null",
                    ]
                ),
                encoding="utf-8",
            )

            payload = cloud_artifacts.build_api_run_status(
                android_api_level="34",
                cli_exit_code=1,
                cli_output_path=log_path,
                completed_at_utc="2026-04-26T00:00:00Z",
                junit_path=root / "junit.xml",
            )

            self.assertEqual("infra_status_fetch_failed", payload["status"])
            self.assertEqual("maestro_cloud_status_fetch_failed", payload["blocker_key"])
            self.assertEqual("mupload_789", payload["upload_id"])
            self.assertEqual("null", payload["status_fetch_status_code"])
            self.assertEqual("scenario-session-drawer-smoke", payload["passed_flows"][0]["name"])
            self.assertEqual("scenario-session-drawer-smoke", payload["last_reported_flow"]["name"])
            self.assertIn("partial hosted results", payload["blocker_message"])

    def test_aggregate_api_run_statuses_prefers_failed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = root / "api-34-status.json"
            second = root / "api-35-status.json"
            first.write_text(json.dumps({"status": "running", "upload_id": "mupload_1"}), encoding="utf-8")
            second.write_text(
                json.dumps(
                    {
                        "first_failed_flow": {"name": "scenario-runtime-ready-smoke"},
                        "status": "failed",
                        "upload_id": "mupload_2",
                    }
                ),
                encoding="utf-8",
            )

            payload = cloud_artifacts.aggregate_api_run_statuses(
                completed_at_utc="2026-04-26T00:00:00Z",
                run_root="tmp/maestro-cloud-smoke/example",
                status_paths=[first, second],
            )

            self.assertEqual("failed", payload["status"])
            self.assertEqual("scenario-runtime-ready-smoke", payload["first_failed_flow"]["name"])
            self.assertEqual(["mupload_1", "mupload_2"], payload["upload_ids"])

    def test_build_api_run_status_prefers_failed_flow_over_status_fetch_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log_path = root / "cli-output.log"
            log_path.write_text(
                "\n".join(
                    [
                        "Visit Maestro Cloud for more details about this upload:",
                        "https://app.maestro.dev/project/proj_123/maestro-test/app/app_456/upload/mupload_789",
                        "App binary id: binary_abc",
                        "Waiting for runs to be completed...",
                        "[Failed] scenario-send-after-ready-smoke (5m 44.787s) (Assertion is false: id: message_bubble_assistant_complete is visible)",
                        "Failed to fetch the status of an upload mupload_789. Status code = null",
                    ]
                ),
                encoding="utf-8",
            )

            payload = cloud_artifacts.build_api_run_status(
                android_api_level="34",
                cli_exit_code=1,
                cli_output_path=log_path,
                completed_at_utc="2026-04-26T00:00:00Z",
                junit_path=root / "junit.xml",
            )

            self.assertEqual("failed", payload["status"])
            self.assertEqual("scenario-send-after-ready-smoke", payload["first_failed_flow"]["name"])
            self.assertTrue(payload["status_fetch_failed"])
            self.assertIn("First failing flow", payload["blocker_message"])

    def test_aggregate_api_run_statuses_prefers_failed_over_infra(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = root / "api-34-status.json"
            second = root / "api-35-status.json"
            first.write_text(
                json.dumps(
                    {
                        "passed_flows": [{"name": "scenario-session-drawer-smoke"}],
                        "status": "infra_status_fetch_failed",
                        "upload_id": "mupload_infra",
                    }
                ),
                encoding="utf-8",
            )
            second.write_text(
                json.dumps(
                    {
                        "first_failed_flow": {"name": "scenario-send-after-ready-smoke"},
                        "status": "failed",
                        "upload_id": "mupload_fail",
                    }
                ),
                encoding="utf-8",
            )

            payload = cloud_artifacts.aggregate_api_run_statuses(
                completed_at_utc="2026-04-26T00:00:00Z",
                run_root="tmp/maestro-cloud-smoke/example",
                status_paths=[first, second],
            )

            self.assertEqual("failed", payload["status"])
            self.assertEqual("scenario-send-after-ready-smoke", payload["first_failed_flow"]["name"])
            self.assertEqual(["mupload_infra", "mupload_fail"], payload["upload_ids"])


if __name__ == "__main__":
    unittest.main()
