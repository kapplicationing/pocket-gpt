from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.devctl import gates


class GatesTest(unittest.TestCase):
    def test_should_run_lifecycle_for_main_push(self) -> None:
        should_run, reason = gates._should_run_lifecycle(
            event_name="push",
            ref_name="main",
            risk_labels=[],
            changed_files=[],
            force=False,
            skip=False,
        )
        self.assertTrue(should_run)
        self.assertIn("main-push", reason)

    def test_should_run_lifecycle_for_risk_label(self) -> None:
        should_run, reason = gates._should_run_lifecycle(
            event_name="pull_request",
            ref_name="feature",
            risk_labels=["risk:runtime"],
            changed_files=[],
            force=False,
            skip=False,
        )
        self.assertTrue(should_run)
        self.assertIn("risk-label", reason)

    def test_should_run_stage2_quick_for_optimization_sensitive_path(self) -> None:
        should_run, reason = gates._should_run_stage2_quick(
            risk_labels=[],
            changed_files=["packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt"],
        )
        self.assertTrue(should_run)
        self.assertIn("optimization-sensitive-path", reason)

    def test_should_skip_stage2_quick_for_low_risk_change(self) -> None:
        should_run, reason = gates._should_run_stage2_quick(
            risk_labels=[],
            changed_files=["docs/README.md"],
        )
        self.assertFalse(should_run)
        self.assertEqual("low-risk-change", reason)

    def test_classify_journey_failure_accepts_unknown_qa13_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "journey-report.json"
            report_path.write_text(
                json.dumps(
                    {
                        "steps": [
                            {
                                "name": "run-01:send-capture",
                                "status": "failed",
                                "phase": "error",
                                "runtime_status": "unknown",
                                "backend": "unknown",
                                "active_model_id": "unknown",
                                "placeholder_visible": False,
                                "failure_signature": "send-capture-kickoff: tcp forward error",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            failure_kind, failure_reason = gates._classify_journey_failure(f"See {report_path}")
            self.assertEqual("harness_noise_fail", failure_kind)
            self.assertEqual("journey-send-kickoff-harness", failure_reason)

    def test_run_gate_command_downgrades_harness_noise_when_allowed(self) -> None:
        original = gates.run_subprocess
        try:
            gates.run_subprocess = lambda *args, **kwargs: subprocess.CompletedProcess(
                args=[],
                returncode=1,
                stdout="MainActivityUiSmokeTest",
                stderr="No compose hierarchies found in the app",
            )
            step = gates._run_gate_command(
                name="screenshot-pack",
                command=["python3", "tools/devctl/main.py", "lane", "screenshot-pack"],
                env={},
                allow_harness_noise=True,
            )
        finally:
            gates.run_subprocess = original

        self.assertEqual("caveat", step.status)
        self.assertEqual("harness_noise_fail", step.correctness)
        self.assertFalse(step.blocking)

    def test_run_gate_command_keeps_harness_noise_blocking_when_not_allowed(self) -> None:
        original = gates.run_subprocess
        try:
            gates.run_subprocess = lambda *args, **kwargs: subprocess.CompletedProcess(
                args=[],
                returncode=1,
                stdout="MainActivityUiSmokeTest",
                stderr="No compose hierarchies found in the app",
            )
            step = gates._run_gate_command(
                name="screenshot-pack",
                command=["python3", "tools/devctl/main.py", "lane", "screenshot-pack"],
                env={},
                allow_harness_noise=False,
            )
        finally:
            gates.run_subprocess = original

        self.assertEqual("failed", step.status)
        self.assertEqual("harness_noise_fail", step.correctness)
        self.assertTrue(step.blocking)

    def test_write_report_recreates_parent_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "build/devctl/gates/report.json"
            step = gates.GateStepResult(
                name="merge",
                command=["bash", "scripts/dev/test.sh", "merge"],
                started_at="2026-03-10T00:00:00",
                duration_seconds=1.23,
                status="passed",
                correctness="pass",
                blocking=False,
            )
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.parent.rmdir()
            gates._write_report(
                gate_name="merge-unblock",
                steps=[step],
                report_path=report_path,
                metadata={},
            )
            self.assertTrue(report_path.exists())

    def test_extract_failure_reason_prefers_devctl_error_line(self) -> None:
        reason = gates._extract_failure_reason(
            "\n".join(
                [
                    "[devctl] $ python3 tools/devctl/main.py lane android-instrumented",
                    "DEVICE_ERROR: Instrumentation failed for test class",
                    "INSTRUMENTATION_CODE: 0",
                ]
            ),
            exit_code=1,
        )
        self.assertEqual("DEVICE_ERROR: Instrumentation failed for test class", reason)

    def test_run_promotion_runs_stage2_quick_for_optimization_sensitive_change(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            parsed = gates._parse_gate_args(["promotion", "--report-path", str(Path(tmp) / "promotion-report.json")])
            original_collect_changed_files = gates._collect_changed_files
            original_run_gate_command = gates._run_gate_command
            original_write_report = gates._write_report
            captured_steps: list[gates.GateStepResult] = []
            captured_metadata: dict[str, object] = {}
            try:
                gates._collect_changed_files = lambda: ["packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt"]

                def _fake_run_gate_command(*, name, command, env, allow_harness_noise):  # type: ignore[no-untyped-def]
                    return gates.GateStepResult(
                        name=name,
                        command=list(command),
                        started_at="2026-03-27T00:00:00",
                        duration_seconds=0.1,
                        status="passed",
                        correctness="pass",
                        blocking=False,
                    )

                def _fake_write_report(*, gate_name, steps, report_path, metadata):  # type: ignore[no-untyped-def]
                    captured_steps.extend(steps)
                    captured_metadata.update(metadata)

                gates._run_gate_command = _fake_run_gate_command
                gates._write_report = _fake_write_report

                gates._run_promotion(parsed)
            finally:
                gates._collect_changed_files = original_collect_changed_files
                gates._run_gate_command = original_run_gate_command
                gates._write_report = original_write_report

            stage2_step = next(step for step in captured_steps if step.name == "stage2-quick")
            self.assertEqual("passed", stage2_step.status)
            self.assertTrue(captured_metadata["stage2_quick_required"])
            self.assertEqual("optimization-sensitive-path", captured_metadata["stage2_quick_reason"])

    def test_run_merge_unblock_uses_hardened_lifecycle_lane(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            parsed = gates._parse_gate_args(
                [
                    "merge-unblock",
                    "--report-path",
                    str(Path(tmp) / "merge-unblock-report.json"),
                    "--risk-label",
                    "risk:runtime",
                ]
            )
            original_collect_changed_files = gates._collect_changed_files
            original_run_gate_command = gates._run_gate_command
            original_write_report = gates._write_report
            captured_steps: list[gates.GateStepResult] = []
            captured_metadata: dict[str, object] = {}
            try:
                gates._collect_changed_files = lambda: []

                def _fake_run_gate_command(*, name, command, env, allow_harness_noise):  # type: ignore[no-untyped-def]
                    return gates.GateStepResult(
                        name=name,
                        command=list(command),
                        started_at="2026-04-25T00:00:00",
                        duration_seconds=0.1,
                        status="passed",
                        correctness="pass",
                        blocking=False,
                    )

                def _fake_write_report(*, gate_name, steps, report_path, metadata):  # type: ignore[no-untyped-def]
                    captured_steps.extend(steps)
                    captured_metadata.update(metadata)

                gates._run_gate_command = _fake_run_gate_command
                gates._write_report = _fake_write_report

                gates._run_merge_unblock(parsed)
            finally:
                gates._collect_changed_files = original_collect_changed_files
                gates._run_gate_command = original_run_gate_command
                gates._write_report = original_write_report

            lifecycle_step = next(step for step in captured_steps if step.name == "lifecycle-e2e-first-run")
            self.assertEqual(
                [
                    "bash",
                    "scripts/ci/run_lifecycle_e2e.sh",
                    "risk-label",
                ],
                lifecycle_step.command,
            )
            self.assertTrue(captured_metadata["lifecycle_required"])
            self.assertEqual(
                "scripts/ci/run_lifecycle_e2e.sh",
                captured_metadata["lifecycle_execution_path"],
            )
            self.assertEqual("ci-wrapper", captured_metadata["lifecycle_execution_kind"])

    def test_run_promotion_skips_stage2_quick_for_low_risk_change(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            parsed = gates._parse_gate_args(["promotion", "--report-path", str(Path(tmp) / "promotion-report.json")])
            original_collect_changed_files = gates._collect_changed_files
            original_run_gate_command = gates._run_gate_command
            original_write_report = gates._write_report
            captured_steps: list[gates.GateStepResult] = []
            captured_metadata: dict[str, object] = {}
            try:
                gates._collect_changed_files = lambda: ["docs/testing/test-strategy.md"]

                def _fake_run_gate_command(*, name, command, env, allow_harness_noise):  # type: ignore[no-untyped-def]
                    return gates.GateStepResult(
                        name=name,
                        command=list(command),
                        started_at="2026-03-27T00:00:00",
                        duration_seconds=0.1,
                        status="passed",
                        correctness="pass",
                        blocking=False,
                    )

                def _fake_write_report(*, gate_name, steps, report_path, metadata):  # type: ignore[no-untyped-def]
                    captured_steps.extend(steps)
                    captured_metadata.update(metadata)

                gates._run_gate_command = _fake_run_gate_command
                gates._write_report = _fake_write_report

                gates._run_promotion(parsed)
            finally:
                gates._collect_changed_files = original_collect_changed_files
                gates._run_gate_command = original_run_gate_command
                gates._write_report = original_write_report

            stage2_step = next(step for step in captured_steps if step.name == "stage2-quick")
            self.assertEqual("skipped", stage2_step.status)
            self.assertFalse(stage2_step.blocking)
            self.assertEqual("low-risk-change", stage2_step.reason)
            self.assertFalse(captured_metadata["stage2_quick_required"])
            self.assertEqual("low-risk-change", captured_metadata["stage2_quick_reason"])


if __name__ == "__main__":
    unittest.main()
