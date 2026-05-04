from __future__ import annotations

import hashlib
import json
import os
import subprocess
import tempfile
import time
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest import mock

from tools.devctl.config_models import load_devctl_configs
from tools.devctl.lanes import (
    ScreenshotInventoryItem,
    RuntimeContext,
    SendCaptureSnapshot,
    JourneyStepResult,
    _build_screenshot_inventory_report,
    _append_native_build_flag,
    _ensure_remote_dir,
    _extract_first_session_progress,
    _extract_instrumentation_failure,
    _extract_ui_runtime_fields,
    _is_tcpip_serial,
    _media_path_fallbacks,
    _maestro_flow_clears_app_state,
    _materialize_maestro_flow_without_clear_state,
    _model_sync_cache_dir,
    _normalize_test_mode,
    _parse_model_sync_manifest,
    _parse_journey_args,
    _parse_screenshot_pack_args,
    _promote_screenshot_reference_set,
    _parse_package_uid,
    _remote_file_sha256,
    _remote_file_size_bytes,
    _remote_read_text_file,
    _resolve_available_instrumentation_runner,
    _run_send_capture_stage,
    _parse_stage2_args,
    _run_device_health_preflight,
    _run_maestro_flow,
    _copy_remote_file,
    _run_serialized_gradle_install_step,
    _select_gradle_tasks_for_changed_files,
    _ensure_serial,
    _evaluate_loop_output,
    _write_journey_report,
    build_artifact_dir,
    build_gradle_test_command,
    dispatch_lane,
    lane_stage2,
    lane_maestro,
    parse_device_lane_args,
    validate_threshold_columns,
)
from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT


def _extract_shell_script(cmd: list[str]) -> str | None:
    if cmd[:4] != ["adb", "-s", "SER123", "shell"]:
        return None
    if len(cmd) >= 7 and cmd[4:6] == ["sh", "-c"]:
        return cmd[6]
    if len(cmd) >= 5:
        return cmd[4]
    return None


class LanesTest(unittest.TestCase):
    @contextmanager
    def _disable_required_real_runtime_artifacts(self):
        from tools.devctl import lanes

        original = lanes._resolve_real_runtime_required_artifacts
        try:
            lanes._resolve_real_runtime_required_artifacts = lambda **_kwargs: []
            yield
        finally:
            lanes._resolve_real_runtime_required_artifacts = original

    def test_append_native_build_flag_uses_provided_device_env_for_serial(self) -> None:
        command = _append_native_build_flag(
            ["./gradlew", "--no-daemon", ":apps:mobile-android:connectedDebugAndroidTest"],
            env={"ADB_SERIAL": "SER123"},
        )
        self.assertIn("-Ppocketgpt.enableNativeBuild=true", command)
        self.assertIn("-Pandroid.injected.device.serial=SER123", command)

    def test_append_native_build_flag_preserves_explicit_serial_flag(self) -> None:
        command = _append_native_build_flag(
            [
                "./gradlew",
                "--no-daemon",
                "-Pandroid.injected.device.serial=SER999",
                ":apps:mobile-android:connectedDebugAndroidTest",
            ],
            env={"ADB_SERIAL": "SER123"},
        )
        self.assertIn("-Ppocketgpt.enableNativeBuild=true", command)
        self.assertEqual(1, sum(1 for token in command if token.startswith("-Pandroid.injected.device.serial=")))
        self.assertIn("-Pandroid.injected.device.serial=SER999", command)

    def test_is_tcpip_serial_accepts_adb_tls_mdns_serial(self) -> None:
        self.assertTrue(_is_tcpip_serial("192.168.1.5:5555"))
        self.assertTrue(_is_tcpip_serial("adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp"))
        self.assertFalse(_is_tcpip_serial("emulator-5554"))

    def test_copy_remote_file_uses_timeout_guard(self) -> None:
        observed_timeout: float | None = None

        def fake_run(command, **kwargs):
            nonlocal observed_timeout
            observed_timeout = kwargs.get("timeout_seconds")
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

        context = RuntimeContext(
            repo_root=REPO_ROOT,
            configs=load_devctl_configs(REPO_ROOT),
            env={},
            run=fake_run,
        )

        _copy_remote_file(
            context=context,
            serial="SER123",
            source_path="/sdcard/source.bin",
            destination_path="/sdcard/destination.bin",
            failure_label="copy failed",
        )

        self.assertEqual(30.0, observed_timeout)

    def test_build_artifact_dir_is_deterministic(self) -> None:
        path = build_artifact_dir(
            "scripts/benchmarks/runs/{date}/{device}/{label}-{stamp}",
            "2026-03-03",
            "SER123",
            "scenario-a",
            "20260303-101010",
        )
        self.assertEqual(
            REPO_ROOT / "tmp/devctl-artifacts/2026-03-03/SER123/scenario-a-20260303-101010",
            path,
        )

    def test_build_artifact_dir_respects_override_root(self) -> None:
        with mock.patch.dict(os.environ, {"POCKET_GPT_DEVCTL_ARTIFACT_ROOT": "/tmp/pocket-gpt-artifacts"}, clear=False):
            path = build_artifact_dir(
                "scripts/benchmarks/runs/{date}/{device}/{label}-{stamp}",
                "2026-03-03",
                "SER123",
                "scenario-a",
                "20260303-101010",
            )
        self.assertEqual(
            Path("/tmp/pocket-gpt-artifacts/2026-03-03/SER123/scenario-a-20260303-101010"),
            path,
        )

    def test_parse_device_lane_args_defaults(self) -> None:
        parsed = parse_device_lane_args([], ["./gradlew", ":apps:mobile-android-host:run"])
        self.assertEqual(10, parsed.runs)
        self.assertEqual("scenario-a-stage-run", parsed.label)
        self.assertEqual("both", parsed.framework)

    def test_parse_device_lane_args_rejects_unknown_flag(self) -> None:
        with self.assertRaises(DevctlError) as raised:
            parse_device_lane_args(["--unknown"], ["echo"])
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_test_profile_aliases_resolve(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        self.assertEqual("core", _normalize_test_mode("quick", context))
        self.assertEqual("merge", _normalize_test_mode("ci", context))

    def test_changed_file_selection_maps_tasks_and_lanes(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        tasks, lanes, include_android = _select_gradle_tasks_for_changed_files(
            [
                "packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt",
                "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatScreen.kt",
            ],
            context,
        )
        self.assertIn(":packages:native-bridge:test", tasks)
        self.assertIn(":apps:mobile-android:testDebugUnitTest", tasks)
        self.assertIn("android-instrumented", lanes)
        self.assertTrue(include_android)

    def test_changed_file_selection_includes_android_unit_for_app_runtime_boundary_files(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        changed_paths = [
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeGateway.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/StartupProbeController.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/contracts/ChatContracts.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelProvisioningViewModel.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppDependencies.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/PocketAgentApplication.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/MainActivity.kt",
        ]
        for changed_path in changed_paths:
            with self.subTest(changed_path=changed_path):
                tasks, lanes, include_android = _select_gradle_tasks_for_changed_files(
                    [changed_path],
                    context,
                )
                self.assertIn(":apps:mobile-android:testDebugUnitTest", tasks)
                self.assertIn("android-instrumented", lanes)
                self.assertTrue(include_android)

    def test_changed_file_selection_covers_runtime_composition_and_voice_paths(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        runtime_composition_paths = [
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppRuntimeDependencies.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppRuntimeGraphManager.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/AndroidMvpContainer.kt",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/HotSwappableRuntimeFacade.kt",
        ]

        for changed_path in runtime_composition_paths:
            with self.subTest(changed_path=changed_path):
                tasks, lanes, include_android = _select_gradle_tasks_for_changed_files(
                    [changed_path],
                    context,
                )
                self.assertIn(":apps:mobile-android:testDebugUnitTest", tasks)
                self.assertIn("android-instrumented", lanes)
                self.assertIn(
                    "journey --repeats 1 --mode strict --steps instrumentation,send-capture",
                    lanes,
                )
                self.assertTrue(include_android)

        voice_path = "apps/mobile-android/src/main/kotlin/com/pocketagent/android/voice/OffasVoiceStack.kt"
        tasks, lanes, include_android = _select_gradle_tasks_for_changed_files(
            [voice_path],
            context,
        )
        self.assertIn(":apps:mobile-android:testDebugUnitTest", tasks)
        self.assertIn("android-instrumented", lanes)
        self.assertNotIn(
            "journey --repeats 1 --mode strict --steps instrumentation,send-capture",
            lanes,
        )
        self.assertTrue(include_android)

    def test_build_gradle_test_command_disables_native_build_for_android_tasks(self) -> None:
        command = build_gradle_test_command(
            gradle_binary="./gradlew",
            gradle_flags=["--no-daemon"],
            gradle_tasks=[
                ":packages:core-domain:test",
                ":apps:mobile-android:testDebugUnitTest",
                ":apps:mobile-android:assembleBenchmark",
            ],
            clean=True,
        )
        self.assertEqual(
            [
                "./gradlew",
                "--no-daemon",
                "-Ppocketgpt.enableNativeBuild=false",
                "clean",
                ":packages:core-domain:test",
                ":apps:mobile-android:testDebugUnitTest",
                ":apps:mobile-android:assembleBenchmark",
            ],
            command,
        )

    def test_build_gradle_test_command_preserves_explicit_native_flag(self) -> None:
        command = build_gradle_test_command(
            gradle_binary="./gradlew",
            gradle_flags=["--no-daemon", "-Ppocketgpt.enableNativeBuild=true"],
            gradle_tasks=[":apps:mobile-android:testDebugUnitTest"],
            clean=False,
        )
        self.assertEqual(
            [
                "./gradlew",
                "--no-daemon",
                "-Ppocketgpt.enableNativeBuild=true",
                ":apps:mobile-android:testDebugUnitTest",
            ],
            command,
        )

    def test_stage2_parser_supports_profiles_and_resume(self) -> None:
        parsed = _parse_stage2_args(
            [
                "--device",
                "SER123",
                "--profile",
                "quick",
                "--models",
                "0.8b",
                "--scenarios",
                "a",
                "--resume",
                "--install-mode",
                "auto",
                "--logcat",
                "filtered",
            ]
        )
        self.assertEqual("SER123", parsed.device)
        self.assertEqual("quick", parsed.profile)
        self.assertEqual("0.8b", parsed.models)
        self.assertEqual("a", parsed.scenarios)
        self.assertTrue(parsed.resume)
        self.assertEqual("auto", parsed.install_mode)
        self.assertEqual("filtered", parsed.logcat)

    def test_journey_parser_enforces_repeat_bounds(self) -> None:
        parsed = _parse_journey_args(
            ["--repeats", "2"],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual(2, parsed.repeats)
        self.assertEqual(90, parsed.reply_timeout_seconds)
        self.assertEqual([0, 5, 15, 30, 60, 90], parsed.capture_intervals)
        self.assertEqual("ola, how you doin", parsed.prompt)
        self.assertEqual("strict", parsed.mode)
        self.assertEqual(["instrumentation", "send-capture"], parsed.steps)

        with self.assertRaises(DevctlError):
            _parse_journey_args(
                ["--repeats", "0"],
                repeats_default=1,
                repeats_max=5,
                reply_timeout_default=90,
                capture_intervals_default=[5, 15, 30, 60, 90],
                prompt_default="ola, how you doin",
            )
        with self.assertRaises(DevctlError):
            _parse_journey_args(
                ["--repeats", "8"],
                repeats_default=1,
                repeats_max=5,
                reply_timeout_default=90,
                capture_intervals_default=[5, 15, 30, 60, 90],
                prompt_default="ola, how you doin",
            )

    def test_journey_parser_applies_timeout_and_capture_overrides(self) -> None:
        parsed = _parse_journey_args(
            [
                "--reply-timeout-seconds",
                "45",
                "--capture-intervals",
                "3,9,15,60",
                "--prompt",
                "probe prompt",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual(45, parsed.reply_timeout_seconds)
        self.assertEqual([0, 3, 9, 15, 45], parsed.capture_intervals)
        self.assertEqual("probe prompt", parsed.prompt)

    def test_journey_parser_valid_output_mode_applies_long_timeout_defaults(self) -> None:
        parsed = _parse_journey_args(
            [
                "--mode",
                "valid-output",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual("valid-output", parsed.mode)
        self.assertEqual(480, parsed.reply_timeout_seconds)
        self.assertEqual([0, 5, 15, 30, 60, 90, 120, 180, 240, 300, 420, 480], parsed.capture_intervals)

    def test_journey_parser_fast_smoke_mode_applies_short_defaults(self) -> None:
        parsed = _parse_journey_args(
            [
                "--mode",
                "fast-smoke",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual("fast-smoke", parsed.mode)
        self.assertEqual(60, parsed.reply_timeout_seconds)
        self.assertEqual([0, 5, 15, 30, 60], parsed.capture_intervals)

    def test_screenshot_pack_parser_supports_update_reference_flag(self) -> None:
        parsed = _parse_screenshot_pack_args(["--update-reference"])
        self.assertTrue(parsed.update_reference)
        self.assertFalse(parsed.product_signal_only)
        self.assertFalse(parsed.deterministic_only)

    def test_screenshot_pack_parser_supports_product_signal_and_deterministic_flags(self) -> None:
        parsed = _parse_screenshot_pack_args(["--product-signal-only", "--deterministic-only"])
        self.assertTrue(parsed.product_signal_only)
        self.assertTrue(parsed.deterministic_only)

    def test_build_screenshot_inventory_report_marks_missing_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact_root = root / "artifacts"
            instrumented_dir = artifact_root / "instrumented"
            maestro_dir = artifact_root / "maestro"
            combined_dir = artifact_root / "combined"
            report_json_path = artifact_root / "inventory-report.json"
            report_md_path = artifact_root / "inventory-report.md"

            instrumented_dir.mkdir(parents=True, exist_ok=True)
            maestro_dir.mkdir(parents=True, exist_ok=True)
            (instrumented_dir / "ui-01-onboarding-page-1.png").write_bytes(b"\x89PNG\r\n\x1a\n")

            inventory = [
                ScreenshotInventoryItem(
                    id="ui-01-onboarding-page-1",
                    filename="ui-01-onboarding-page-1.png",
                    candidates=("instrumented/ui-01-onboarding-page-1.png",),
                ),
                ScreenshotInventoryItem(
                    id="ui-02-onboarding-page-2",
                    filename="ui-02-onboarding-page-2.png",
                    candidates=("instrumented/ui-02-onboarding-page-2.png",),
                ),
            ]

            payload = _build_screenshot_inventory_report(
                inventory=inventory,
                serial="SER123",
                artifact_root=artifact_root,
                instrumented_dir=instrumented_dir,
                maestro_dir=maestro_dir,
                combined_dir=combined_dir,
                report_json_path=report_json_path,
                report_md_path=report_md_path,
            )

            self.assertEqual(["ui-02-onboarding-page-2"], payload["missing_ids"])
            self.assertEqual("ui-screenshot-inventory-report-v2", payload["schema"])
            self.assertEqual("ui-screenshot-inventory-v1", payload["inventory_schema"])
            self.assertEqual("SER123", payload["device_serial"])
            self.assertIn("generated_at_utc", payload)
            self.assertTrue((combined_dir / "ui-01-onboarding-page-1.png").exists())
            self.assertFalse((combined_dir / "ui-02-onboarding-page-2.png").exists())
            self.assertTrue(report_json_path.exists())
            self.assertTrue(report_md_path.exists())

    def test_promote_screenshot_reference_set_writes_gallery_index(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            combined_dir = root / "combined"
            reference_dir = root / "reference"
            combined_dir.mkdir(parents=True, exist_ok=True)
            (combined_dir / "ui-01-onboarding-page-1.png").write_bytes(b"\x89PNG\r\n\x1a\n")
            (combined_dir / "ui-02-onboarding-page-2.png").write_bytes(b"\x89PNG\r\n\x1a\n")
            inventory = [
                ScreenshotInventoryItem(
                    id="ui-01-onboarding-page-1",
                    filename="ui-01-onboarding-page-1.png",
                    candidates=("instrumented/ui-01-onboarding-page-1.png",),
                ),
                ScreenshotInventoryItem(
                    id="ui-02-onboarding-page-2",
                    filename="ui-02-onboarding-page-2.png",
                    candidates=("instrumented/ui-02-onboarding-page-2.png",),
                ),
            ]

            _promote_screenshot_reference_set(
                combined_dir=combined_dir,
                inventory=inventory,
                reference_dir=reference_dir,
            )

            self.assertTrue((reference_dir / "ui-01-onboarding-page-1.png").exists())
            self.assertTrue((reference_dir / "ui-02-onboarding-page-2.png").exists())
            index_text = (reference_dir / "index.md").read_text(encoding="utf-8")
            self.assertIn("ui-01-onboarding-page-1", index_text)
            self.assertIn("ui-02-onboarding-page-2", index_text)

    def test_send_capture_valid_output_requires_terminal_event(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                return Result(returncode=0, stdout="ok\n", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Loading",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=True,
            response_visible=True,
            response_role="assistant",
            response_non_empty=True,
            first_token_seen=True,
            request_id="req-1",
            finish_reason=None,
            terminal_event_seen=False,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="valid-output",
            )

        self.assertEqual("failed", result.status)
        self.assertEqual("first_token", result.phase)
        self.assertIs(result.placeholder_visible, False)
        self.assertEqual("no_terminal_event", result.failure_signature)

    def test_send_capture_fast_smoke_allows_first_token_without_terminal(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                return Result(returncode=0, stdout="ok\n", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Loading",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=True,
            response_visible=True,
            response_role="assistant",
            response_non_empty=True,
            first_token_seen=True,
            request_id="req-2",
            finish_reason=None,
            terminal_event_seen=False,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="fast-smoke",
            )

        self.assertEqual("passed", result.status)
        self.assertEqual("first_token", result.phase)
        self.assertIsNone(result.failure_signature)

    def test_send_capture_kickoff_retries_on_transient_maestro_launch_failure(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        maestro_attempts = 0
        issued_commands: list[list[str]] = []
        seen_envs: list[dict[str, str]] = []

        def fake_run(command, **kwargs):
            nonlocal maestro_attempts
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd and cmd[0] == "maestro":
                maestro_attempts += 1
                seen_envs.append(dict(kwargs.get("env") or {}))
                if maestro_attempts == 1:
                    return Result(
                        returncode=1,
                        stdout="",
                        stderr="Unable to launch app com.pocketagent.android",
                    )
                return Result(returncode=0, stdout="ok\n", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={"BASE_ENV": "1"}, run=fake_run)
        snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Ready",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=False,
            response_visible=True,
            response_role="assistant",
            response_non_empty=True,
            first_token_seen=True,
            request_id="req-retry",
            finish_reason="stop",
            terminal_event_seen=True,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ), mock.patch(
            "tools.devctl.lanes.time.sleep", return_value=None
        ):
            overridden_env = {"BASE_ENV": "1", "ADB_SERIAL": "SER123", "ANDROID_SERIAL": "SER123"}
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="fast-smoke",
                env=overridden_env,
            )
            output_path = Path(tmpdir) / "maestro-debug" / "send-capture-kickoff" / "maestro-output.txt"
            output = output_path.read_text(encoding="utf-8")

        forward_resets = [
            cmd for cmd in issued_commands if cmd == ["adb", "-s", "SER123", "forward", "--remove-all"]
        ]
        self.assertEqual("passed", result.status)
        self.assertEqual(2, maestro_attempts)
        self.assertGreaterEqual(len(forward_resets), 2)
        self.assertEqual(2, len(seen_envs))
        self.assertTrue(all(env["BASE_ENV"] == "1" for env in seen_envs))
        self.assertTrue(all(env["ADB_SERIAL"] == "SER123" for env in seen_envs))
        self.assertTrue(all(env["ANDROID_SERIAL"] == "SER123" for env in seen_envs))
        self.assertTrue(all("-Djava.net.preferIPv4Stack=true" in env.get("JAVA_TOOL_OPTIONS", "") for env in seen_envs))
        self.assertIn("=== attempt 1 ===", output)
        self.assertIn("=== attempt 2 ===", output)
        self.assertIn("Unable to launch app", output)

    def test_send_capture_kickoff_retries_when_maestro_reports_device_not_connected(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        maestro_attempts = 0

        def fake_run(command, **_kwargs):
            nonlocal maestro_attempts
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                maestro_attempts += 1
                if maestro_attempts == 1:
                    return Result(returncode=1, stderr="Device SER123 was requested, but it is not connected.")
                return Result(returncode=0, stdout="ok\n", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Ready",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=False,
            response_visible=True,
            response_role="assistant",
            response_non_empty=True,
            first_token_seen=True,
            request_id="req-retry-device",
            finish_reason="stop",
            terminal_event_seen=True,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ), mock.patch(
            "tools.devctl.lanes.time.sleep", return_value=None
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="fast-smoke",
            )

        self.assertEqual("passed", result.status)
        self.assertEqual(2, maestro_attempts)

    def test_send_capture_kickoff_probes_transport_before_launch(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        issued_commands: list[list[str]] = []

        def fake_run(command, **_kwargs):
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd and cmd[0] == "maestro":
                return Result(returncode=0, stdout="ok\n", stderr="")
            if cmd[:4] == ["adb", "-s", "SER123", "get-state"]:
                return Result(returncode=0, stdout="device\n", stderr="")
            if cmd[:5] == ["adb", "-s", "SER123", "shell", "echo"]:
                return Result(returncode=0, stdout="maestro-transport-ready\n", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Ready",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=False,
            response_visible=True,
            response_role="assistant",
            response_non_empty=True,
            first_token_seen=True,
            request_id="req-transport",
            finish_reason="stop",
            terminal_event_seen=True,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="fast-smoke",
            )
            kickoff_flow = Path(tmpdir) / "send-capture" / "send-kickoff.yaml"
            kickoff_flow_text = kickoff_flow.read_text(encoding="utf-8")

        self.assertEqual("passed", result.status)
        self.assertIn(["adb", "-s", "SER123", "get-state"], issued_commands)
        self.assertIn(["adb", "-s", "SER123", "shell", "echo", "maestro-transport-ready"], issued_commands)
        self.assertIn(["adb", "-s", "SER123", "shell", "input", "keyevent", "KEYCODE_HOME"], issued_commands)
        self.assertIn(
            ["adb", "-s", "SER123", "shell", "am", "start", "-W", "-n", "com.pocketagent.android/.MainActivity"],
            issued_commands,
        )
        self.assertIn("tests/maestro/shared/bootstrap-runtime-ready-no-launch.yaml", kickoff_flow_text)
        self.assertIn("tests/maestro/shared/ensure-runtime-loaded.yaml", kickoff_flow_text)
        self.assertIn("tests/maestro/shared/dismiss-system-overlays.yaml", kickoff_flow_text)
        self.assertNotIn("hideKeyboard", kickoff_flow_text)

    def test_send_capture_kickoff_timeout_returns_failed_result(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        issued_commands: list[list[str]] = []

        def fake_run(command, **_kwargs):
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd and cmd[0] == "maestro":
                raise DevctlError(
                    "ENVIRONMENT_ERROR",
                    "Command timed out after 120.0s: maestro --device SER123 test /tmp/send-kickoff.yaml",
                )
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        kickoff_snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Ready",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=False,
            response_visible=False,
            response_role=None,
            response_non_empty=False,
            first_token_seen=False,
            request_id=None,
            finish_reason=None,
            terminal_event_seen=False,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=kickoff_snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="fast-smoke",
            )
            output_path = Path(tmpdir) / "maestro-debug" / "send-capture-kickoff" / "maestro-output.txt"
            output = output_path.read_text(encoding="utf-8")

        self.assertEqual("failed", result.status)
        self.assertIn("Command timed out after 120.0s", result.failure_signature or "")
        self.assertIn("=== attempt 1 ===", output)
        self.assertIn("Command timed out after 120.0s", output)
        self.assertTrue(any(cmd and cmd[0] == "maestro" for cmd in issued_commands))

    def test_send_capture_kickoff_failure_populates_required_qa13_fields(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                return Result(returncode=1, stdout="", stderr="send-capture-kickoff: launch failed")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        kickoff_snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status=None,
            backend=None,
            active_model_id=None,
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=False,
            response_visible=False,
            response_role=None,
            response_non_empty=False,
            first_token_seen=False,
            request_id=None,
            finish_reason=None,
            terminal_event_seen=False,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=kickoff_snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="strict",
            )

        self.assertEqual("failed", result.status)
        self.assertEqual("error", result.phase)
        self.assertEqual("unknown", result.runtime_status)
        self.assertEqual("unknown", result.backend)
        self.assertEqual("unknown", result.active_model_id)
        self.assertFalse(result.placeholder_visible)

    def test_send_capture_kickoff_failure_signature_ignores_debug_output_path(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                return Result(
                    returncode=1,
                    stdout=(
                        "Running on SER123\n"
                        "io.grpc.StatusRuntimeException: UNAVAILABLE: io exception\n"
                        "==== Debug output (logs & screenshots) ====\n"
                        "/tmp/debug-path\n"
                    ),
                    stderr="",
                )
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        kickoff_snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status=None,
            backend=None,
            active_model_id=None,
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=False,
            response_visible=False,
            response_role=None,
            response_non_empty=False,
            first_token_seen=False,
            request_id=None,
            finish_reason=None,
            terminal_event_seen=False,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=kickoff_snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="strict",
            )

        self.assertEqual("failed", result.status)
        self.assertEqual("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception", result.failure_signature)

    def test_run_maestro_flow_retries_once_on_transient_launch_failure(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        maestro_attempts = 0
        maestro_commands: list[list[str]] = []
        issued_commands: list[list[str]] = []

        def fake_run(command, **_kwargs):
            nonlocal maestro_attempts
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd[:2] == ["pgrep", "-af"]:
                return Result(returncode=0, stdout="4321 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java -classpath /Users/mkamar/.maestro/lib/* maestro.cli.AppKt --device SER123 test\n", stderr="")
            if cmd[:1] == ["kill"]:
                return Result(returncode=0, stdout="", stderr="")
            if cmd and cmd[0] == "maestro":
                maestro_commands.append(cmd)
                maestro_attempts += 1
                if maestro_attempts == 1:
                    return Result(returncode=1, stderr="TimeoutException: TcpForwarder")
                return Result(returncode=0, stdout="flow passed", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ), mock.patch(
            "tools.devctl.lanes.time.sleep", return_value=None
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            debug_output = Path(tmpdir) / "maestro-debug"
            result = _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                flow_path=flow_path,
                debug_output_dir=debug_output,
            )
            output = (debug_output / "maestro-output.txt").read_text(encoding="utf-8")

        self.assertEqual("passed", result.status)
        self.assertEqual(2, maestro_attempts)
        self.assertIn("=== attempt 1 ===", output)
        self.assertIn("=== attempt 2 ===", output)
        self.assertTrue(any("--reinstall-driver" in command for command in maestro_commands))
        self.assertIn("TcpForwarder", output)
        self.assertIsNone(result.failure_signature)
        self.assertTrue(any(command[:2] == ["pgrep", "-af"] for command in issued_commands))
        self.assertTrue(any(command[:1] == ["kill"] for command in issued_commands))

    def test_run_maestro_flow_transient_retry_recovers_tcp_transport(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        serial = "192.168.1.43:40281"
        issued_commands: list[list[str]] = []
        maestro_attempts = 0

        def fake_run(command, **_kwargs):
            nonlocal maestro_attempts
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd and cmd[0] == "maestro":
                maestro_attempts += 1
                if maestro_attempts == 1:
                    return Result(returncode=1, stderr="TimeoutException: TcpForwarder")
                return Result(returncode=0, stdout="flow passed", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ), mock.patch(
            "tools.devctl.lanes.time.sleep", return_value=None
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial=serial,
                flow_path=flow_path,
                debug_output_dir=Path(tmpdir) / "maestro-debug",
            )

        self.assertEqual(2, maestro_attempts)
        self.assertIn(["adb", "disconnect", serial], issued_commands)
        self.assertIn(["adb", "connect", serial], issued_commands)
        self.assertIn(["adb", "-s", serial, "wait-for-device"], issued_commands)

    def test_run_maestro_flow_does_not_retry_on_non_transient_failure(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        issued_commands: list[list[str]] = []
        maestro_attempts = 0

        def fake_run(command, **_kwargs):
            nonlocal maestro_attempts
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd and cmd[0] == "maestro":
                maestro_attempts += 1
                return Result(returncode=1, stderr="Element not found")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            debug_output = Path(tmpdir) / "maestro-debug"
            result = _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                flow_path=flow_path,
                debug_output_dir=debug_output,
            )
            output = (debug_output / "maestro-output.txt").read_text(encoding="utf-8")

        maestro_calls = [cmd for cmd in issued_commands if cmd and cmd[0] == "maestro"]
        self.assertEqual(1, maestro_attempts)
        self.assertEqual(1, len(maestro_calls))
        self.assertEqual("failed", result.status)
        self.assertEqual("Element not found", result.failure_signature)
        self.assertIn("=== attempt 1 ===", output)
        self.assertNotIn("=== attempt 2 ===", output)

    def test_run_maestro_flow_timeout_returns_failed_result(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        issued_commands: list[list[str]] = []

        def fake_run(command, **_kwargs):
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd and cmd[0] == "maestro":
                raise DevctlError(
                    "ENVIRONMENT_ERROR",
                    "Command timed out after 300.0s: maestro --device SER123 test /tmp/scenario-a.yaml",
                )
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            debug_output = Path(tmpdir) / "maestro-debug"
            result = _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                flow_path=flow_path,
                debug_output_dir=debug_output,
            )
            output = (debug_output / "maestro-output.txt").read_text(encoding="utf-8")

        self.assertEqual("failed", result.status)
        self.assertIn("Command timed out after 300.0s", result.failure_signature or "")
        self.assertIn("=== attempt 1 ===", output)
        self.assertIn("Command timed out after 300.0s", output)
        self.assertTrue(any(cmd and cmd[0] == "maestro" for cmd in issued_commands))

    def test_run_maestro_flow_uses_supplied_env(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        seen_envs: list[dict[str, str]] = []

        def fake_run(command, **kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                seen_envs.append(dict(kwargs.get("env") or {}))
            return Result(returncode=0, stdout="flow passed", stderr="")

        context = RuntimeContext(
            repo_root=REPO_ROOT,
            configs=configs,
            env={"BASE_ENV": "1"},
            run=fake_run,
        )
        overridden_env = {"BASE_ENV": "1", "ADB_SERIAL": "SER123", "ANDROID_SERIAL": "SER123"}
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                flow_path=flow_path,
                debug_output_dir=Path(tmpdir) / "maestro-debug",
                env=overridden_env,
            )

        self.assertEqual(1, len(seen_envs))
        self.assertEqual("1", seen_envs[0]["BASE_ENV"])
        self.assertEqual("SER123", seen_envs[0]["ADB_SERIAL"])
        self.assertEqual("SER123", seen_envs[0]["ANDROID_SERIAL"])
        self.assertIn("-Djava.net.preferIPv4Stack=true", seen_envs[0]["JAVA_TOOL_OPTIONS"])

    def test_run_maestro_flow_force_stops_app_on_retry_when_package_supplied(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        issued_commands: list[list[str]] = []
        maestro_attempts = 0

        def fake_run(command, **_kwargs):
            nonlocal maestro_attempts
            issued_commands.append(list(command))
            if command and command[0] == "maestro":
                maestro_attempts += 1
                if maestro_attempts == 1:
                    return Result(returncode=1, stderr="TimeoutException: TcpForwarder")
            return Result(returncode=0, stdout="flow passed", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ), mock.patch(
            "tools.devctl.lanes.time.sleep", return_value=None
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                flow_path=flow_path,
                debug_output_dir=Path(tmpdir) / "maestro-debug",
            )

        self.assertEqual(2, maestro_attempts)
        self.assertIn(
            ["adb", "-s", "SER123", "shell", "am", "force-stop", "com.pocketagent.android"],
            issued_commands,
        )

    def test_run_maestro_flow_retries_when_transient_marker_only_exists_in_maestro_log(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        maestro_attempts = 0

        def fake_run(command, **_kwargs):
            nonlocal maestro_attempts
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                maestro_attempts += 1
                debug_dir = Path(_kwargs["cwd"])
                log_dir = debug_dir / ".maestro/tests/2026-04-26_000000"
                log_dir.mkdir(parents=True, exist_ok=True)
                if maestro_attempts == 1:
                    (log_dir / "maestro.log").write_text(
                        "io.grpc.StatusRuntimeException: UNAVAILABLE: io exception\n",
                        encoding="utf-8",
                    )
                    return Result(returncode=1, stdout="Waiting for flows to complete...\n[Failed]\n", stderr="")
                (log_dir / "maestro.log").write_text("flow passed\n", encoding="utf-8")
                return Result(returncode=0, stdout="flow passed", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ), mock.patch(
            "tools.devctl.lanes.time.sleep", return_value=None
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            debug_output = Path(tmpdir) / "maestro-debug"
            result = _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                flow_path=flow_path,
                debug_output_dir=debug_output,
            )
            output = (debug_output / "maestro-output.txt").read_text(encoding="utf-8")

        self.assertEqual("passed", result.status)
        self.assertEqual(2, maestro_attempts)
        self.assertIn("UNAVAILABLE: io exception", output)
        self.assertIn("=== attempt 2 ===", output)

    def test_run_maestro_flow_failure_signature_ignores_debug_output_path(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                return Result(
                    returncode=1,
                    stdout=(
                        "Running on SER123\n"
                        "io.grpc.StatusRuntimeException: UNAVAILABLE: io exception\n"
                        "==== Debug output (logs & screenshots) ====\n"
                        "/tmp/debug-path\n"
                    ),
                    stderr="",
                )
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            flow_path = Path(tmpdir) / "scenario-a.yaml"
            flow_path.write_text("appId: com.pocketagent.android\n---\n- launchApp\n", encoding="utf-8")
            result = _run_maestro_flow(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                flow_path=flow_path,
                debug_output_dir=Path(tmpdir) / "maestro-debug",
            )

        self.assertEqual("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception", result.failure_signature)

    def test_extract_ui_runtime_fields_detects_assistant_text(self) -> None:
        dump = (
            '<hierarchy>'
            '<node text="Runtime: Loading" />'
            '<node text="Backend: NATIVE_JNI" />'
            '<node text="Model: QWEN_0_8B" />'
            '<node text="ola, how you doin" />'
            '<node text="Hey! How can I help?" />'
            "</hierarchy>"
        )
        (
            runtime_status,
            backend,
            active_model_id,
            placeholder_visible,
            runtime_error_visible,
            timeout_message_visible,
            ui_response_visible,
        ) = _extract_ui_runtime_fields(dump, prompt="ola, how you doin")
        self.assertEqual("Loading", runtime_status)
        self.assertEqual("NATIVE_JNI", backend)
        self.assertEqual("QWEN_0_8B", active_model_id)
        self.assertFalse(placeholder_visible)
        self.assertFalse(runtime_error_visible)
        self.assertFalse(timeout_message_visible)
        self.assertTrue(ui_response_visible)

    def test_extract_ui_runtime_fields_ignores_scaffold_text(self) -> None:
        dump = (
            '<hierarchy>'
            '<node text="Runtime: Loading" />'
            '<node text="Backend: NATIVE_JNI" />'
            '<node text="Model: QWEN_0_8B" />'
            '<node text="Pocket GPT" />'
            '<node text="Offline-first" />'
            '<node text="Message" />'
            '<node text="Send" />'
            '<node text="ola, how you doin" />'
            "</hierarchy>"
        )
        (
            _runtime_status,
            _backend,
            _active_model_id,
            _placeholder_visible,
            _runtime_error_visible,
            _timeout_message_visible,
            ui_response_visible,
        ) = _extract_ui_runtime_fields(dump, prompt="ola, how you doin")
        self.assertFalse(ui_response_visible)

    def test_extract_ui_runtime_fields_ignores_runtime_phase_and_placeholder(self) -> None:
        dump = (
            '<hierarchy>'
            '<node text="Runtime: Loading" />'
            '<node text="Backend: NATIVE_JNI" />'
            '<node text="Model: QWEN_0_8B" />'
            '<node text="Speed &amp; Battery: BALANCED" />'
            '<node text="Prefill..." />'
            '<node text="Still working on this device. Keep waiting or cancel." />'
            '<node text="Cancel" />'
            '<node text="..." />'
            '<node text="ola, how you doin" />'
            "</hierarchy>"
        )
        (
            _runtime_status,
            _backend,
            _active_model_id,
            placeholder_visible,
            _runtime_error_visible,
            _timeout_message_visible,
            ui_response_visible,
        ) = _extract_ui_runtime_fields(dump, prompt="ola, how you doin")
        self.assertTrue(placeholder_visible)
        self.assertFalse(ui_response_visible)

    def test_extract_first_session_progress_reads_state_from_snapshot(self) -> None:
        payload = json.dumps(
            {
                "firstSessionStage": "FOLLOW_UP_DONE",
                "advancedUnlocked": False,
                "firstAnswerCompleted": True,
                "followUpCompleted": True,
            }
        )
        snapshot = f'<map><string name="chat_state_v2">{payload}</string></map>'
        stage, advanced_unlocked, first_answer_completed, follow_up_completed = _extract_first_session_progress(snapshot)
        self.assertEqual("FOLLOW_UP_DONE", stage)
        self.assertFalse(advanced_unlocked)
        self.assertTrue(first_answer_completed)
        self.assertTrue(follow_up_completed)

    def test_journey_parser_supports_step_and_flow_filters(self) -> None:
        parsed = _parse_journey_args(
            [
                "--steps",
                "send-capture,maestro",
                "--maestro-flows",
                "tests/maestro/scenario-a.yaml,tests/maestro/scenario-c.yaml",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual(["send-capture", "maestro"], parsed.steps)
        self.assertEqual(
            ["tests/maestro/scenario-a.yaml", "tests/maestro/scenario-c.yaml"],
            parsed.maestro_flows,
        )

    def test_journey_parser_rejects_maestro_flows_without_maestro_step(self) -> None:
        with self.assertRaises(DevctlError) as raised:
            _parse_journey_args(
                [
                    "--steps",
                    "send-capture",
                    "--maestro-flows",
                    "tests/maestro/scenario-a.yaml",
                ],
                repeats_default=1,
                repeats_max=5,
                reply_timeout_default=90,
                capture_intervals_default=[5, 15, 30, 60, 90],
                prompt_default="ola, how you doin",
            )
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_extract_instrumentation_failure_detects_short_msg(self) -> None:
        output = "\n".join(
            [
                "INSTRUMENTATION_STATUS: class=com.pocketagent.android.RealRuntimeProvisioningInstrumentationTest",
                "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
                "INSTRUMENTATION_CODE: 0",
            ]
        )
        self.assertEqual("Process crashed.", _extract_instrumentation_failure(output))

    def test_extract_instrumentation_failure_detects_failed_marker(self) -> None:
        output = "INSTRUMENTATION_FAILED: Process crashed."
        self.assertEqual("Process crashed.", _extract_instrumentation_failure(output))

    def test_extract_instrumentation_failure_ignores_success_output(self) -> None:
        output = "\n".join(
            [
                "INSTRUMENTATION_STATUS: class=com.pocketagent.android.RealRuntimeJourneyInstrumentationTest",
                "INSTRUMENTATION_STATUS_CODE: -1",
                "INSTRUMENTATION_RESULT: stream=",
                "OK (1 test)",
            ]
        )
        self.assertIsNone(_extract_instrumentation_failure(output))

    def test_model_sync_cache_dir_maps_models_to_devctl_cache(self) -> None:
        self.assertEqual(
            "/sdcard/Android/media/com.pocketagent.android/devctl-cache",
            _model_sync_cache_dir("/sdcard/Android/media/com.pocketagent.android/models"),
        )

    def test_parse_model_sync_manifest_rejects_invalid_schema(self) -> None:
        with self.assertRaises(ValueError):
            _parse_model_sync_manifest('{"schema":"wrong","models":{}}')

    def test_remote_file_helpers_parse_probe_outputs_and_use_single_shell_script(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        issued_commands: list[list[str]] = []

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            issued_commands.append(cmd)
            script = _extract_shell_script(cmd)
            if script is None:
                return Result()
            if "wc -c" in script:
                return Result(stdout=" 17\n")
            if "sha256sum" in script:
                return Result(
                    stdout=(
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        "  /sdcard/model.gguf\n"
                    )
                )
            if "cat " in script:
                return Result(stdout="manifest-json")
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        size = _remote_file_size_bytes(context, "SER123", "/sdcard/model.gguf")
        sha = _remote_file_sha256(context, "SER123", "/sdcard/model.gguf")
        text = _remote_read_text_file(context, "SER123", "/sdcard/model-sync-v1.json")

        self.assertEqual(17, size)
        self.assertEqual(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            sha,
        )
        self.assertEqual("manifest-json", text)
        shell_calls = [cmd for cmd in issued_commands if cmd[:4] == ["adb", "-s", "SER123", "shell"]]
        self.assertTrue(shell_calls)
        self.assertTrue(all(not (len(cmd) >= 6 and cmd[4:6] == ["sh", "-c"]) for cmd in shell_calls))

    def test_stage2_closure_requires_models_both(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        with self.assertRaises(DevctlError) as raised:
            lane_stage2(
                [
                    "--device",
                    "SER123",
                    "--profile",
                    "closure",
                    "--models",
                    "0.8b",
                    "--scenarios",
                    "both",
                ],
                context,
            )
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_stage2_closure_requires_scenarios_both(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        with self.assertRaises(DevctlError) as raised:
            lane_stage2(
                [
                    "--device",
                    "SER123",
                    "--profile",
                    "closure",
                    "--models",
                    "both",
                    "--scenarios",
                    "a",
                ],
                context,
            )
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_validate_threshold_columns_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            csv_file = Path(tmp) / "stage2.csv"
            csv_file.write_text("scenario,first_token_ms\nA,100\n", encoding="utf-8")

            with self.assertRaises(DevctlError) as raised:
                validate_threshold_columns(csv_file, ["scenario", "first_token_ms", "decode_tps"])
            self.assertEqual("SCHEMA_ERROR", raised.exception.code)

    def test_ensure_serial_returns_device_error_when_preflight_fails(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        def fake_run(*_args, **_kwargs):
            class Result:
                returncode = 1
                stdout = ""
                stderr = "no devices"

            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with self.assertRaises(DevctlError) as raised:
            _ensure_serial(context)
        self.assertEqual("DEVICE_ERROR", raised.exception.code)

    def test_run_serialized_gradle_install_step_assembles_and_installs_on_target_serial(self) -> None:
        from tools.devctl import lanes

        original_mapping = dict(lanes._SERIALIZED_GRADLE_INSTALLS)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                apk_path = Path(tmp) / "mobile-android-debug-androidTest.apk"
                apk_path.write_bytes(b"apk")

                lanes._SERIALIZED_GRADLE_INSTALLS = {
                    ":apps:mobile-android:installDebugAndroidTest": (
                        ":apps:mobile-android:assembleDebugAndroidTest",
                        str(apk_path),
                        True,
                    ),
                }

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    issued_commands.append(list(command))
                    return Result(returncode=0, stdout="ok", stderr="")

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                handled = _run_serialized_gradle_install_step(
                    context=context,
                    command=[
                        "./gradlew",
                        "--no-daemon",
                        ":apps:mobile-android:installDebugAndroidTest",
                    ],
                    serial="SER123",
                    env={},
                )

                self.assertTrue(handled)
                self.assertIn(
                    ["./gradlew", "--no-daemon", ":apps:mobile-android:assembleDebugAndroidTest"],
                    issued_commands,
                )
                self.assertIn(
                    ["adb", "-s", "SER123", "install", "-r", "-t", str(apk_path.resolve())],
                    issued_commands,
                )
                self.assertNotIn(
                    ["./gradlew", "--no-daemon", ":apps:mobile-android:installDebugAndroidTest"],
                    issued_commands,
                )
        finally:
            lanes._SERIALIZED_GRADLE_INSTALLS = original_mapping

    def test_evaluate_loop_output_rejects_invalid_regex(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            log = tmp_path / "run-1-logcat.txt"
            log.write_text("normal log", encoding="utf-8")
            summary = tmp_path / "summary.csv"
            summary.write_text(
                "run,exit_code,crash_detected,oom_detected,log_file,command_output\n"
                f"1,0,false,false,{log},run-1.log\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                _evaluate_loop_output(summary, ["(bad"], ["OutOfMemoryError"])
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_dispatch_lane_rejects_unknown_lane(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        with self.assertRaises(DevctlError) as raised:
            dispatch_lane("unknown-lane", [], context)
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_lane_maestro_requires_cli(self) -> None:
        from tools.devctl import lanes

        original_which = lanes.shutil.which
        try:
            lanes.shutil.which = lambda _name: None
            configs = load_devctl_configs(REPO_ROOT)
            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
            with self.assertRaises(DevctlError) as raised:
                lane_maestro([], context)
            self.assertEqual("ENVIRONMENT_ERROR", raised.exception.code)
        finally:
            lanes.shutil.which = original_which

    def test_lane_maestro_passes_resolved_device_serial(self) -> None:
        from tools.devctl import lanes

        original_which = lanes.shutil.which
        original_prepare = lanes.prepare_real_runtime_env
        original_health_preflight = lanes._run_device_health_preflight
        original_serialized_install = lanes._run_serialized_gradle_install_step
        issued_commands: list[list[str]] = []
        configs = load_devctl_configs(REPO_ROOT)
        ensure_command = configs.device.preflight.ensure_device_command

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            issued_commands.append(list(command))
            if list(command) == list(ensure_command):
                return Result(returncode=0, stdout="SER123\n")
            return Result(returncode=0, stdout="", stderr="")

        def fake_prepare(_context, device_serial: str, artifact_root=None):
            return lanes.RealRuntimePreparedEnv(
                serial=device_serial,
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={
                    "qwen3.5-0.8b-q4": "/tmp/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/tmp/qwen3-1.7b-q4_k_m.gguf",
                },
            )

        try:
            lanes.shutil.which = lambda name: "/usr/bin/maestro" if name == "maestro" else None
            lanes.prepare_real_runtime_env = fake_prepare
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lane_maestro([], context)
        finally:
            lanes.shutil.which = original_which
            lanes.prepare_real_runtime_env = original_prepare
            lanes._run_device_health_preflight = original_health_preflight
            lanes._run_serialized_gradle_install_step = original_serialized_install

        maestro_calls = [cmd for cmd in issued_commands if cmd and cmd[0] == "/usr/bin/maestro"]
        self.assertGreaterEqual(len(maestro_calls), 1)
        for call in maestro_calls:
            self.assertEqual("--device", call[1])
            self.assertEqual("SER123", call[2])

    def test_lane_maestro_can_filter_flows_by_tag(self) -> None:
        from tools.devctl import lanes

        original_which = lanes.shutil.which
        original_prepare = lanes.prepare_real_runtime_env
        original_health_preflight = lanes._run_device_health_preflight
        original_serialized_install = lanes._run_serialized_gradle_install_step
        issued_commands: list[list[str]] = []
        configs = load_devctl_configs(REPO_ROOT)
        ensure_command = configs.device.preflight.ensure_device_command

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            issued_commands.append(list(command))
            if list(command) == list(ensure_command):
                return Result(returncode=0, stdout="SER123\n")
            return Result(returncode=0, stdout="", stderr="")

        def fake_prepare(_context, device_serial: str, artifact_root=None):
            return lanes.RealRuntimePreparedEnv(
                serial=device_serial,
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={
                    "qwen3.5-0.8b-q4": "/tmp/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/tmp/qwen3-1.7b-q4_k_m.gguf",
                },
            )

        try:
            lanes.shutil.which = lambda name: "/usr/bin/maestro" if name == "maestro" else None
            lanes.prepare_real_runtime_env = fake_prepare
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lane_maestro(["--include-tags", "onboarding"], context)
        finally:
            lanes.shutil.which = original_which
            lanes.prepare_real_runtime_env = original_prepare
            lanes._run_device_health_preflight = original_health_preflight
            lanes._run_serialized_gradle_install_step = original_serialized_install

        maestro_calls = [cmd for cmd in issued_commands if cmd and cmd[0] == "/usr/bin/maestro"]
        self.assertEqual(1, len(maestro_calls))
        self.assertTrue(
            any(token.endswith("scenario-onboarding.yaml") for token in maestro_calls[0]),
        )
        clear_commands = [
            cmd for cmd in issued_commands
            if cmd == ["adb", "-s", "SER123", "shell", "pm", "clear", "com.pocketagent.android"]
        ]
        self.assertEqual(1, len(clear_commands))
        self.assertLess(issued_commands.index(clear_commands[0]), issued_commands.index(maestro_calls[0]))

    def test_lane_maestro_writes_report_when_flow_fails(self) -> None:
        from tools.devctl import lanes

        original_which = lanes.shutil.which
        original_prepare = lanes.prepare_real_runtime_env
        original_health_preflight = lanes._run_device_health_preflight
        original_serialized_install = lanes._run_serialized_gradle_install_step
        original_capture_logcat = lanes._capture_logcat
        original_run_maestro_flow = lanes._run_maestro_flow
        original_write_runtime_log_signal_artifacts = lanes._write_runtime_log_signal_artifacts
        configs = load_devctl_configs(REPO_ROOT)
        ensure_command = configs.device.preflight.ensure_device_command

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            if list(command) == list(ensure_command):
                return Result(returncode=0, stdout="SER123\n")
            return Result(returncode=0, stdout="", stderr="")

        def fake_prepare(_context, device_serial: str, artifact_root=None):
            if artifact_root is not None:
                (artifact_root / "real-runtime-preflight.json").write_text('{"ok":true}\n', encoding="utf-8")
            return lanes.RealRuntimePreparedEnv(
                serial=device_serial,
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={
                    "qwen3.5-0.8b-q4": "/tmp/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/tmp/qwen3-1.7b-q4_k_m.gguf",
                },
            )

        def fake_capture_logcat(_context, _serial, output_path):
            Path(output_path).write_text("I/PocketAgentApp: logcat\n", encoding="utf-8")

        def fake_run_maestro_flow(**kwargs):
            return JourneyStepResult(
                name=f"maestro:{Path(kwargs['flow_path']).stem}",
                status="failed",
                duration_seconds=1.25,
                details="debug output",
                failure_signature="Assertion is false: send button not enabled",
                phase="error",
                elapsed_ms=1250,
            )

        try:
            lanes.shutil.which = lambda name: "/usr/bin/maestro" if name == "maestro" else None
            lanes.prepare_real_runtime_env = fake_prepare
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            lanes._capture_logcat = fake_capture_logcat
            lanes._run_maestro_flow = fake_run_maestro_flow
            lanes._write_runtime_log_signal_artifacts = lambda _path: None
            with tempfile.TemporaryDirectory() as tmpdir:
                configs.lanes.lanes.maestro.artifacts.output_dir_template = str(
                    Path(tmpdir) / "{device}" / "{label}" / "{stamp}"
                )
                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                with self.assertRaises(DevctlError) as raised:
                    lane_maestro([], context)
                self.assertEqual("DEVICE_ERROR", raised.exception.code)
                report_dir = next((Path(tmpdir) / "SER123" / "maestro").iterdir())
                report_json = json.loads((report_dir / "maestro-report.json").read_text(encoding="utf-8"))
                self.assertEqual("failed", report_json["status"])
                self.assertEqual("SER123", report_json["serial"])
                self.assertEqual("maestro:scenario-onboarding", report_json["flow_name"])
                self.assertEqual("error", report_json["failure_phase"])
                self.assertEqual("Assertion is false: send button not enabled", report_json["failure_signature"])
                self.assertEqual(str(report_dir), report_json["artifact_root"])
                self.assertEqual(1, len(report_json["steps"]))
                self.assertTrue((report_dir / "maestro-report.md").exists())
        finally:
            lanes.shutil.which = original_which
            lanes.prepare_real_runtime_env = original_prepare
            lanes._run_device_health_preflight = original_health_preflight
            lanes._run_serialized_gradle_install_step = original_serialized_install
            lanes._capture_logcat = original_capture_logcat
            lanes._run_maestro_flow = original_run_maestro_flow
            lanes._write_runtime_log_signal_artifacts = original_write_runtime_log_signal_artifacts

    def test_extract_maestro_failure_signature_ignores_java_tool_options_noise(self) -> None:
        from tools.devctl import lanes

        signature = lanes._extract_maestro_failure_signature(
            combined=(
                "Picked up JAVA_TOOL_OPTIONS: -Djava.net.preferIPv4Stack=true\n"
                "==== Debug output ====\n"
                "/tmp/debug\n"
            ),
            maestro_log=(
                "19:07:54.546 [ERROR] maestro.drivers.AndroidDriver.runDeviceCall: "
                "Received UNAVAILABLE status with message: UNAVAILABLE: Network closed for unknown reason "
                "while processing deviceInfo command\n"
            ),
            returncode=1,
        )
        self.assertEqual(
            "19:07:54.546 [ERROR] maestro.drivers.AndroidDriver.runDeviceCall: "
            "Received UNAVAILABLE status with message: UNAVAILABLE: Network closed for unknown reason "
            "while processing deviceInfo command",
            signature,
        )

    def test_ensure_serial_resolves_ip_port_alias_before_preflight(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        ensure_command = configs.device.preflight.ensure_device_command
        observed_preflight_env: dict[str, str] = {}

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **kwargs):
            command_list = list(command)
            if command_list == ["adb", "devices", "-l"]:
                return Result(
                    stdout=(
                        "List of devices attached\n"
                        "adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp device "
                        "product:a51nsxx model:SM_A515F device:a51 transport_id:1\n"
                    )
                )
            if command_list == ["adb", "mdns", "services"]:
                return Result(stdout="adb-RR8NB087YTF-P4Pfzs    _adb-tls-connect._tcp   192.168.1.45:44439\n")
            if command_list == list(ensure_command):
                observed_preflight_env.update(kwargs["env"])
                return Result(stdout="adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp\n")
            raise AssertionError(f"Unexpected command: {command_list}")

        context = RuntimeContext(
            repo_root=REPO_ROOT,
            configs=configs,
            env={"ADB_SERIAL": "192.168.1.45:44439"},
            run=fake_run,
        )

        resolved = _ensure_serial(context)

        self.assertEqual("adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp", resolved)
        self.assertEqual(
            "adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp",
            observed_preflight_env["ADB_SERIAL"],
        )
        self.assertEqual(
            "adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp",
            observed_preflight_env["ANDROID_SERIAL"],
        )

    def test_ensure_serial_preserves_requested_serial_when_alias_cannot_be_resolved(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        ensure_command = configs.device.preflight.ensure_device_command
        observed_preflight_env: dict[str, str] = {}

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **kwargs):
            command_list = list(command)
            if command_list == ["adb", "devices", "-l"]:
                return Result(stdout="List of devices attached\nSER123 device transport_id:1\n")
            if command_list == ["adb", "mdns", "services"]:
                return Result(stdout="")
            if command_list == list(ensure_command):
                observed_preflight_env.update(kwargs["env"])
                return Result(stdout="SER123\n")
            raise AssertionError(f"Unexpected command: {command_list}")

        context = RuntimeContext(
            repo_root=REPO_ROOT,
            configs=configs,
            env={"ADB_SERIAL": "192.168.1.45:44439"},
            run=fake_run,
        )

        resolved = _ensure_serial(context)

        self.assertEqual("SER123", resolved)
        self.assertEqual("192.168.1.45:44439", observed_preflight_env["ADB_SERIAL"])
        self.assertNotIn("ANDROID_SERIAL", observed_preflight_env)

    def test_maestro_flow_clears_app_state_detects_clear_state_true(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            flow = Path(tmpdir) / "flow.yaml"
            flow.write_text(
                "\n".join(
                    [
                        "appId: com.pocketagent.android",
                        "---",
                        "- launchApp:",
                        "    clearState: true",
                    ]
                ),
                encoding="utf-8",
            )

            self.assertTrue(_maestro_flow_clears_app_state(flow))

    def test_materialize_maestro_flow_without_clear_state_preserves_shared_flows(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            flow_dir = root / "tests" / "maestro"
            shared_dir = flow_dir / "shared"
            shared_dir.mkdir(parents=True)
            (shared_dir / "bootstrap.yaml").write_text("- assertVisible: Ready\n", encoding="utf-8")
            flow = flow_dir / "scenario.yaml"
            flow.write_text(
                "\n".join(
                    [
                        "appId: com.pocketagent.android",
                        "---",
                        "- launchApp:",
                        "    clearState: true",
                        "- runFlow: shared/bootstrap.yaml",
                    ]
                ),
                encoding="utf-8",
            )

            materialized = _materialize_maestro_flow_without_clear_state(
                flow,
                output_dir=root / "out",
            )

            text = materialized.read_text(encoding="utf-8")
            self.assertIn("clearState: false", text)
            self.assertTrue((root / "out" / "shared" / "bootstrap.yaml").exists())

    def test_parse_package_uid(self) -> None:
        self.assertEqual(10635, _parse_package_uid("pkgFlags=[ HAS_CODE ]\nuserId=10635\ngids=[3003]"))
        self.assertEqual(10419, _parse_package_uid("Packages:\n  Package [com.foo]\n    appId=10419\n"))
        self.assertEqual(10419, _parse_package_uid("Permissions:\n  uid=10419 gids=[]\n"))
        self.assertIsNone(_parse_package_uid("pkgFlags=[ HAS_CODE ]\ngids=[3003]"))

    def test_run_instrumentation_class_shell_quotes_args_with_spaces(self) -> None:
        from tools.devctl import lanes

        configs = load_devctl_configs(REPO_ROOT)
        seen_commands: list[list[str]] = []

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            seen_commands.append(list(command))
            return Result(stdout="INSTRUMENTATION_CODE: -1")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        lanes._run_instrumentation_class(
            context=context,
            serial="SER123",
            test_class="com.pocketagent.android.RealRuntimeJourneyInstrumentationTest#runCoreJourneyGate",
            runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            args={
                "journey_prompt": "Reply with exactly: SEND_AFTER_READY_OK",
                "journey_reply_timeout_seconds": "180",
            },
        )

        self.assertEqual(
            [[
                "adb",
                "-s",
                "SER123",
                "shell",
                "am instrument -w -r -e class 'com.pocketagent.android.RealRuntimeJourneyInstrumentationTest#runCoreJourneyGate' -e 'journey_prompt' 'Reply with exactly: SEND_AFTER_READY_OK' -e 'journey_reply_timeout_seconds' '180' 'com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner'",
            ]],
            seen_commands,
        )

    def test_media_path_fallbacks_maps_to_download_dir(self) -> None:
        self.assertEqual(
            ["/sdcard/Download/com.pocketagent.android/models"],
            _media_path_fallbacks("/sdcard/Android/media/com.pocketagent.android/models"),
        )
        self.assertEqual([], _media_path_fallbacks("/data/local/tmp/models"))

    def test_ensure_remote_dir_falls_back_when_media_path_busy(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["mkdir", "-p", "/sdcard/Android/media/com.pocketagent.android/models"]:
                return Result(returncode=1, stderr="mkdir: Device or resource busy")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["ls", "-ld", "/sdcard/Android/media/com.pocketagent.android/models"]:
                return Result(returncode=1, stderr="ls: cannot access")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["mkdir", "-p", "/sdcard/Download/com.pocketagent.android/models"]:
                return Result(returncode=0)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["ls", "-ld", "/sdcard/Download/com.pocketagent.android/models"]:
                return Result(returncode=0)
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        resolved = _ensure_remote_dir(
            context=context,
            serial="SER123",
            path="/sdcard/Android/media/com.pocketagent.android/models",
            fallback_paths=["/sdcard/Download/com.pocketagent.android/models"],
            failure_label="failed",
        )
        self.assertEqual("/sdcard/Download/com.pocketagent.android/models", resolved)

    def test_device_health_preflight_retries_probe_write_on_download_fallback(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        issued_commands: list[list[str]] = []

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            issued_commands.append(cmd)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:6] == ["df", "/data"]:
                return Result(stdout="/dev/block/dm-8 100 10 90 10% /data\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["mkdir", "-p", "/sdcard/Android/media/com.pocketagent.android/devctl-health"]:
                return Result(returncode=0)
            if cmd[:4] == ["adb", "-s", "SER123", "push"]:
                destination = cmd[-1]
                if destination == "/sdcard/Android/media/com.pocketagent.android/devctl-health/probe.txt":
                    return Result(returncode=1, stderr="remote couldn't create file: Operation not permitted")
                if destination == "/sdcard/Download/com.pocketagent.android/devctl-health/probe.txt":
                    return Result(returncode=0)
            if (
                cmd[:4] == ["adb", "-s", "SER123", "shell"]
                and cmd[4:7] == ["mkdir", "-p", "/sdcard/Download/com.pocketagent.android/devctl-health"]
            ):
                return Result(returncode=0)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["rm", "-f", "/sdcard/Download/com.pocketagent.android/devctl-health/probe.txt"]:
                return Result(returncode=0)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:8] == ["pm", "list", "packages", "--user"]:
                package_name = cmd[-1]
                return Result(stdout=f"package:{package_name}\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["dumpsys", "package", "com.pocketagent.android"]:
                return Result(stdout="Packages:\n  Package [com.pocketagent.android]\n    userId=10635\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["dumpsys", "package", "com.pocketagent.android.test"]:
                return Result(stdout="Packages:\n  Package [com.pocketagent.android.test]\n    userId=10636\n")
            return Result(returncode=0)

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        _run_device_health_preflight(context, "SER123")

        push_targets = [cmd[-1] for cmd in issued_commands if cmd[:4] == ["adb", "-s", "SER123", "push"]]
        self.assertEqual(
            [
                "/sdcard/Android/media/com.pocketagent.android/devctl-health/probe.txt",
                "/sdcard/Download/com.pocketagent.android/devctl-health/probe.txt",
            ],
            push_targets,
        )

    def test_resolve_available_instrumentation_runner_prefers_target_match(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            if list(command) == ["adb", "-s", "SER123", "shell", "pm", "list", "instrumentation"]:
                return Result(
                    stdout=(
                        "instrumentation:com.other.app.test/androidx.test.runner.AndroidJUnitRunner "
                        "(target=com.other.app)\n"
                        "instrumentation:com.pocketagent.android.standard.test/"
                        "androidx.test.runner.AndroidJUnitRunner "
                        "(target=com.pocketagent.android.standard)\n"
                    )
                )
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        resolved = _resolve_available_instrumentation_runner(
            context=context,
            serial="SER123",
            preferred_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            app_package="com.pocketagent.android",
        )
        self.assertEqual(
            "com.pocketagent.android.standard.test/androidx.test.runner.AndroidJUnitRunner",
            resolved,
        )

    def test_prepare_real_runtime_env_skips_push_when_remote_size_matches(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    if len(cmd) >= 6 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf"):
                        remote_sizes[cmd[-1]] = Path(cmd[4]).stat().st_size
                        return Result()
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "qwen3.5-0.8b-q4.gguf" in script:
                            return Result(stdout="3\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script:
                            return Result(stdout="5\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                with self._disable_required_real_runtime_artifacts():
                    prepared = lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual([], model_push_calls)
                self.assertEqual(
                    "com.pocketagent.android.standard.test/androidx.test.runner.AndroidJUnitRunner",
                    prepared.instrumentation_runner,
                )
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_reuses_loaded_manifest_cache_path(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                model_0_sha = hashlib.sha256(b"abc").hexdigest()
                model_2_sha = hashlib.sha256(b"12345").hexdigest()
                manifest_payload = json.dumps(
                    {
                        "schema": "model-sync-v1",
                        "selected_model_dir": "/sdcard/Android/media/com.pocketagent.android/models",
                        "models": {
                            "qwen3.5-0.8b-q4": {
                                "host_sha256": model_0_sha,
                                "host_size": 3,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                            },
                            "qwen3-1.7b-q4_k_m": {
                                "host_sha256": model_2_sha,
                                "host_size": 5,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                            },
                        },
                    }
                )

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "model-sync-v1.json" in script and "cat " in script:
                            return Result(stdout=manifest_payload)
                        if "qwen3.5-0.8b-q4.gguf" in script and "wc -c" in script:
                            return Result(stdout="3\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script and "wc -c" in script:
                            return Result(stdout="5\n")
                        if "qwen3.5-0.8b-q4.gguf" in script and "sha256sum" in script:
                            return Result(stdout=f"{model_0_sha}  /sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script and "sha256sum" in script:
                            return Result(stdout=f"{model_2_sha}  /sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                artifact_root = tmp_path / "artifacts"
                with self._disable_required_real_runtime_artifacts():
                    lanes.prepare_real_runtime_env(context, "SER123", artifact_root=artifact_root)

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual([], model_push_calls)

                metadata = json.loads((artifact_root / "real-runtime-preflight.json").read_text(encoding="utf-8"))
                model_sync = metadata.get("model_sync", {})
                self.assertTrue(str(model_sync.get("loaded_manifest_path", "")).endswith("model-sync-v1.json"))
                decisions = model_sync.get("decisions", [])
                self.assertEqual(2, len(decisions))
                self.assertTrue(all(decision.get("decision") == "cache_hit" for decision in decisions))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_force_sync_pushes_models_even_when_sizes_match(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "qwen3.5-0.8b-q4.gguf" in script:
                            return Result(stdout="3\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script:
                            return Result(stdout="5\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(
                    repo_root=REPO_ROOT,
                    configs=configs,
                    env={"POCKETGPT_FORCE_MODEL_SYNC": "1"},
                    run=fake_run,
                )
                with self._disable_required_real_runtime_artifacts():
                    lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual(2, len(model_push_calls))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_syncs_required_companion_artifact(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                mmproj = tmp_path / "mmproj-F16.gguf"
                expected_mmproj_sha = "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")
                mmproj.write_bytes(b"projector")

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []
                remote_sizes: dict[str, int] = {
                    "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf": 3,
                    "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf": 5,
                }

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    if len(cmd) >= 6 and cmd[:4] == ["adb", "-s", "SER123", "push"]:
                        remote_sizes[cmd[-1]] = Path(cmd[4]).stat().st_size
                        return Result()
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "mmproj-F16.gguf" in script and "wc -c" in script:
                            size = remote_sizes.get("/sdcard/Android/media/com.pocketagent.android/models/mmproj-F16.gguf", 0)
                            return Result(stdout=f"{size}\n")
                        if "qwen3.5-0.8b-q4.gguf" in script and "wc -c" in script:
                            size = remote_sizes.get("/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf", 0)
                            return Result(stdout=f"{size}\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script and "wc -c" in script:
                            size = remote_sizes.get("/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf", 0)
                            return Result(stdout=f"{size}\n")
                        if "mmproj-F16.gguf" in script and "sha256sum" in script:
                            return Result(stdout=f"{expected_mmproj_sha}  /sdcard/Android/media/com.pocketagent.android/models/mmproj-F16.gguf\n")
                        return Result(stdout="")
                    return Result()

                artifact_root = tmp_path / "artifacts"
                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                original_compute_sha = lanes._compute_file_sha256
                try:
                    lanes._compute_file_sha256 = (
                        lambda path: expected_mmproj_sha
                        if Path(path).resolve() == mmproj.resolve()
                        else original_compute_sha(path)
                    )
                    lanes.prepare_real_runtime_env(context, "SER123", artifact_root=artifact_root)
                finally:
                    lanes._compute_file_sha256 = original_compute_sha

                mmproj_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith("mmproj-F16.gguf")
                ]
                self.assertEqual(1, len(mmproj_push_calls))

                metadata = json.loads((artifact_root / "real-runtime-preflight.json").read_text(encoding="utf-8"))
                required = metadata["model_required_artifacts_by_id"]["qwen3.5-0.8b-q4"]
                self.assertEqual("mmproj-F16.gguf", required[0]["file_name"])
                self.assertEqual(
                    "/sdcard/Android/media/com.pocketagent.android/models/mmproj-F16.gguf",
                    required[0]["device_path"],
                )
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_download_real_runtime_artifact_persists_verified_file(self) -> None:
        from tools.devctl import lanes

        payload = b"projector-bytes"
        expected_sha = hashlib.sha256(payload).hexdigest()

        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "mmproj-F16.gguf"
            def fake_run_subprocess(command, **_kwargs):
                output_index = list(command).index("-o") + 1
                Path(command[output_index]).write_bytes(payload)
                return subprocess.CompletedProcess(args=command, returncode=0, stdout="", stderr="")

            with mock.patch("tools.devctl.lanes.run_subprocess", side_effect=fake_run_subprocess):
                lanes._download_real_runtime_artifact(
                    download_url="https://example.test/mmproj-F16.gguf",
                    destination=destination,
                    expected_sha256=expected_sha,
                )

            self.assertEqual(payload, destination.read_bytes())

    def test_prepare_real_runtime_env_self_heals_corrupt_sync_manifest(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")
                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []
                remote_sizes: dict[str, int] = {}

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "model-sync-v1.json" in script and "cat" in script:
                            return Result(stdout="{corrupt-json")
                        if "qwen3.5-0.8b-q4.gguf" in script:
                            return Result(stdout="3\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script:
                            return Result(stdout="5\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                with self._disable_required_real_runtime_artifacts():
                    lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                manifest_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith("model-sync-v1.json")
                ]
                self.assertEqual([], model_push_calls)
                self.assertEqual(1, len(manifest_push_calls))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_pushes_when_manifest_path_is_stale(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                model_0_sha = hashlib.sha256(b"abc").hexdigest()
                model_2_sha = hashlib.sha256(b"12345").hexdigest()
                stale_manifest = json.dumps(
                    {
                        "schema": "model-sync-v1",
                        "selected_model_dir": "/sdcard/Android/media/com.pocketagent.android/models",
                        "models": {
                            "qwen3.5-0.8b-q4": {
                                "host_sha256": model_0_sha,
                                "host_size": 3,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                            },
                            "qwen3-1.7b-q4_k_m": {
                                "host_sha256": model_2_sha,
                                "host_size": 5,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                            },
                        },
                    }
                )

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []
                remote_sizes: dict[str, int] = {}

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    if len(cmd) >= 6 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf"):
                        remote_sizes[cmd[-1]] = Path(cmd[4]).stat().st_size
                        return Result()
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "model-sync-v1.json" in script and "cat" in script:
                            return Result(stdout=stale_manifest)
                        if "qwen3.5-0.8b-q4.gguf" in script and "wc -c" in script:
                            size = remote_sizes.get(
                                "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                                0,
                            )
                            return Result(stdout=f"{size}\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script and "wc -c" in script:
                            size = remote_sizes.get(
                                "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                                0,
                            )
                            return Result(stdout=f"{size}\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                with self._disable_required_real_runtime_artifacts():
                    lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual(2, len(model_push_calls))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_rehydrates_fallback_model_dir_to_primary_probe_path(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                model_0_sha = hashlib.sha256(b"abc").hexdigest()
                model_2_sha = hashlib.sha256(b"12345").hexdigest()
                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []
                remote_sizes: dict[str, int] = {}

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                fallback_0 = "/sdcard/Download/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf"
                fallback_2 = "/sdcard/Download/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf"
                remote_sizes[fallback_0] = 3
                remote_sizes[fallback_2] = 5

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "qwen3.5-0.8b-q4.gguf" in script and "wc -c" in script:
                            primary_path = "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf"
                            size = remote_sizes.get(
                                primary_path if "/sdcard/Android/media/com.pocketagent.android/models" in script else fallback_0
                            )
                            if size is None and any(push_cmd[-1] == primary_path for push_cmd in issued_commands if len(push_cmd) >= 6 and push_cmd[:4] == ["adb", "-s", "SER123", "push"]):
                                size = model_0_8b.stat().st_size
                            return Result(stdout=f"{size}\n" if size is not None else "")
                        if "qwen3-1.7b-q4_k_m.gguf" in script and "wc -c" in script:
                            primary_path = "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf"
                            size = remote_sizes.get(
                                primary_path if "/sdcard/Android/media/com.pocketagent.android/models" in script else fallback_2
                            )
                            if size is None and any(push_cmd[-1] == primary_path for push_cmd in issued_commands if len(push_cmd) >= 6 and push_cmd[:4] == ["adb", "-s", "SER123", "push"]):
                                size = model_2b.stat().st_size
                            return Result(stdout=f"{size}\n" if size is not None else "")
                        if "qwen3.5-0.8b-q4.gguf" in script and "sha256sum" in script:
                            return Result(stdout=f"{model_0_sha}  {fallback_0}\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script and "sha256sum" in script:
                            return Result(stdout=f"{model_2_sha}  {fallback_2}\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                with self._disable_required_real_runtime_artifacts():
                    prepared = lanes.prepare_real_runtime_env(context, "SER123")

                self.assertEqual(
                    "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    prepared.model_device_paths_by_id["qwen3.5-0.8b-q4"],
                )
                self.assertEqual(
                    "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                    prepared.model_device_paths_by_id["qwen3-1.7b-q4_k_m"],
                )
                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual(
                    [
                        [
                            "adb",
                            "-s",
                            "SER123",
                            "push",
                            str(model_0_8b),
                            "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                        ],
                        [
                            "adb",
                            "-s",
                            "SER123",
                            "push",
                            str(model_2b),
                            "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                        ],
                    ],
                    model_push_calls,
                )
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_retries_provisioning_probe_on_process_crash(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3-1.7B-Q4_K_M.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3-1.7b-q4_k_m": str(model_2b),
                }

                probe_calls = 0

                def fake_probe(**_kwargs):
                    nonlocal probe_calls
                    probe_calls += 1
                    if probe_calls == 1:
                        raise DevctlError(
                            "DEVICE_ERROR",
                            "Instrumentation failed for "
                            "com.pocketagent.android.RealRuntimeProvisioningInstrumentationTest#"
                            "seedModelsAndVerifyStartupChecks (reported failure: Process crashed.).",
                        )
                    return subprocess.CompletedProcess(args=[], returncode=0, stdout="ok", stderr="")

                lanes._run_instrumentation_class = fake_probe

                configs = load_devctl_configs(REPO_ROOT)
                remote_sizes: dict[str, int] = {}
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    if len(cmd) >= 6 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf"):
                        remote_sizes[cmd[-1]] = Path(cmd[4]).stat().st_size
                        return Result()
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "qwen3.5-0.8b-q4.gguf" in script and "wc -c" in script:
                            size = remote_sizes.get(
                                "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                                0,
                            )
                            return Result(stdout=f"{size}\n")
                        if "qwen3-1.7b-q4_k_m.gguf" in script and "wc -c" in script:
                            size = remote_sizes.get(
                                "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                                0,
                            )
                            return Result(stdout=f"{size}\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                with self._disable_required_real_runtime_artifacts():
                    lanes.prepare_real_runtime_env(context, "SER123")
                self.assertEqual(2, probe_calls)
                self.assertEqual(
                    [
                        ["adb", "-s", "SER123", "shell", "am", "force-stop", "dev.mobile.maestro"],
                        ["adb", "-s", "SER123", "shell", "am", "force-stop", "dev.mobile.maestro.test"],
                        ["adb", "-s", "SER123", "shell", "am", "force-stop", "dev.mobile.maestro"],
                        ["adb", "-s", "SER123", "shell", "am", "force-stop", "dev.mobile.maestro.test"],
                    ],
                    [
                        cmd
                        for cmd in issued_commands
                        if cmd[:6] == ["adb", "-s", "SER123", "shell", "am", "force-stop"]
                        and cmd[-1] in {"dev.mobile.maestro", "dev.mobile.maestro.test"}
                    ],
                )
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_android_instrumented_lane_limits_connected_suite_to_configured_selectors(self) -> None:
        from tools.devctl import lanes

        configs = load_devctl_configs(REPO_ROOT)
        issued_commands: list[list[str]] = []
        instrumentation_calls: list[tuple[str, dict[str, str]]] = []
        original_resolve_android_env = lanes._resolve_android_env
        original_ensure_serial = lanes._ensure_serial
        original_prepare_real_runtime_env = lanes.prepare_real_runtime_env
        original_capture_logcat = lanes._capture_logcat
        original_run_device_health_preflight = lanes._run_device_health_preflight
        original_run_serialized_gradle_install_step = lanes._run_serialized_gradle_install_step
        original_device_lock = lanes._device_lock
        original_run_instrumentation_class = lanes._run_instrumentation_class
        original_resolve_lane_artifact_dir = lanes._resolve_lane_artifact_dir
        try:
            lanes._resolve_android_env = lambda _env: (True, {})
            lanes._ensure_serial = lambda _context: "SER123"
            lanes.prepare_real_runtime_env = lambda *_args, **_kwargs: lanes.RealRuntimePreparedEnv(
                serial="SER123",
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={},
                instrumentation_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            )
            lanes._capture_logcat = lambda *_args, **_kwargs: None
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            lanes._run_instrumentation_class = lambda **kwargs: (
                instrumentation_calls.append((kwargs["test_class"], dict(kwargs["args"]))),
                subprocess.CompletedProcess(args=[], returncode=0, stdout="OK", stderr=""),
            )[1]
            temp_dir = Path(tempfile.mkdtemp(prefix="android-instrumented-artifacts-"))
            lanes._resolve_lane_artifact_dir = lambda *_args, **_kwargs: temp_dir

            @contextmanager
            def fake_device_lock(*_args, **_kwargs):
                yield

            lanes._device_lock = fake_device_lock

            def fake_run(command, **_kwargs):
                issued_commands.append(list(command))
                if list(command) == ["adb", "devices", "-l"]:
                    return subprocess.CompletedProcess(
                        command,
                        0,
                        stdout=(
                            "List of devices attached\n"
                            "SER123 device product:a model:PhoneA device:a transport_id:1\n"
                            "SER999 device product:b model:PhoneB device:b transport_id:2\n"
                        ),
                        stderr="",
                    )
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lanes._lane_android_instrumented_impl([], context)
        finally:
            lanes._resolve_android_env = original_resolve_android_env
            lanes._ensure_serial = original_ensure_serial
            lanes.prepare_real_runtime_env = original_prepare_real_runtime_env
            lanes._capture_logcat = original_capture_logcat
            lanes._run_device_health_preflight = original_run_device_health_preflight
            lanes._run_serialized_gradle_install_step = original_run_serialized_gradle_install_step
            lanes._device_lock = original_device_lock
            lanes._run_instrumentation_class = original_run_instrumentation_class
            lanes._resolve_lane_artifact_dir = original_resolve_lane_artifact_dir

        connected_commands = [
            cmd
            for cmd in issued_commands
            if ":apps:mobile-android:connectedDebugAndroidTest" in cmd
        ]
        self.assertEqual([], connected_commands)
        self.assertEqual(
            [
                "com.pocketagent.android.MainActivityAuthoritativeOnboardingInstrumentationTest#onboardingFlowCompletesIntoChatSurface",
                "com.pocketagent.android.ui.ModelManagementSheetComposeContractTest#productionModelSheetRendersAndDispatchesRefreshEvent",
            ],
            [test_class for test_class, _ in instrumentation_calls],
        )
        self.assertEqual(
            "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
            instrumentation_calls[0][1]["stage2_model_0_8b_path"],
        )
        self.assertEqual(
            "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
            instrumentation_calls[0][1]["stage2_model_1_7b_path"],
        )
        self.assertNotIn("stage2_enable_provisioning_test", instrumentation_calls[0][1])
        self.assertNotIn("stage2_enable_provisioning_test", instrumentation_calls[1][1])
        report_json = json.loads((temp_dir / "android-instrumented-report.json").read_text(encoding="utf-8"))
        self.assertEqual("passed", report_json["status"])
        self.assertEqual("SER123", report_json["serial"])
        self.assertEqual(2, len(report_json["selector_results"]))
        self.assertEqual(2, len(report_json["attached_devices"]))
        self.assertTrue((temp_dir / "android-instrumented-report.md").exists())

    def test_run_device_health_preflight_happy_path(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:] == ["df", "/data"]:
                return Result(stdout="Filesystem 1K-blocks Used Available Use% Mounted on\n/data 100 40 60 40% /data\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:8] == ["pm", "list", "packages", "--user"]:
                package = cmd[-1]
                return Result(stdout=f"package:{package}\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:6] == ["dumpsys", "package"]:
                return Result(stdout="Packages:\n  userId=10635\n")
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        _run_device_health_preflight(context, "SER123")

    def test_run_device_health_preflight_accepts_appid_metadata(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:] == ["df", "/data"]:
                return Result(stdout="Filesystem 1K-blocks Used Available Use% Mounted on\n/data 100 40 60 40% /data\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:8] == ["pm", "list", "packages", "--user"]:
                package = cmd[-1]
                return Result(stdout=f"package:{package}\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:6] == ["dumpsys", "package"]:
                return Result(stdout="Packages:\n  Package [com.pocketagent.android]\n    appId=10419\n")
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        _run_device_health_preflight(context, "SER123")

    def test_run_device_health_preflight_accepts_uid_metadata(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:] == ["df", "/data"]:
                return Result(stdout="Filesystem 1K-blocks Used Available Use% Mounted on\n/data 100 40 60 40% /data\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:8] == ["pm", "list", "packages", "--user"]:
                package = cmd[-1]
                return Result(stdout=f"package:{package}\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:6] == ["dumpsys", "package"]:
                return Result(stdout="Permissions:\n  uid=10419 gids=[]\n")
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        _run_device_health_preflight(context, "SER123")

    def test_write_journey_report_generates_runtime_log_signal_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            logcat_path = tmp_path / "send-window-logcat.txt"
            report_path = tmp_path / "journey-report.json"
            summary_path = tmp_path / "journey-summary.md"
            logcat_path.write_text(
                "\n".join(
                    [
                        "I/PocketLlamaJNI: MMAP|use_mmap=true|use_mlock=false|use_direct_io=false",
                        "I/PocketLlamaJNI: MMAP|stage=readahead|label=target|bytes=1024|result=0",
                        "I/PocketLlamaJNI: FLASH_ATTN|requested=true|type=auto|gpu_ops=true|type_k=Q8_0|type_v=Q8_0|n_ctx=4096|n_batch=768|n_ubatch=384",
                        "I/PocketLlamaJNI: SPECULATIVE|accepted=6|drafted=8|max_draft=6|remaining=32|acceptance_rate=0.750",
                    ],
                ),
                encoding="utf-8",
            )
            _write_journey_report(
                report_path=report_path,
                summary_path=summary_path,
                serial="SER123",
                steps=[
                    JourneyStepResult(
                        name="run-01:send-capture",
                        status="passed",
                        duration_seconds=1.23,
                        logcat=str(logcat_path),
                    ),
                ],
            )

            payload = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(1, len(payload["runtime_log_signal_reports"]))
            signal_report = payload["runtime_log_signal_reports"][0]
            self.assertEqual("pass", signal_report["status"])
            self.assertTrue((Path(signal_report["json_report"]) if Path(signal_report["json_report"]).is_absolute() else REPO_ROOT / signal_report["json_report"]).exists())
            self.assertTrue((Path(signal_report["markdown_report"]) if Path(signal_report["markdown_report"]).is_absolute() else REPO_ROOT / signal_report["markdown_report"]).exists())
            summary = summary_path.read_text(encoding="utf-8")
            self.assertIn("Runtime log signals:", summary)

    def test_write_journey_report_includes_owner_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "journey-report.json"
            summary_path = Path(tmp) / "journey-summary.md"
            old_owner = os.environ.get("POCKETGPT_RUN_OWNER")
            old_host = os.environ.get("HOSTNAME")
            try:
                os.environ["POCKETGPT_RUN_OWNER"] = "qa-owner"
                os.environ["HOSTNAME"] = "qa-host"
                _write_journey_report(
                    report_path=report_path,
                    summary_path=summary_path,
                    serial="SER123",
                    steps=[
                        JourneyStepResult(
                            name="run-01:instrumentation",
                            status="passed",
                            duration_seconds=1.23,
                        ),
                    ],
                )
            finally:
                if old_owner is None:
                    os.environ.pop("POCKETGPT_RUN_OWNER", None)
                else:
                    os.environ["POCKETGPT_RUN_OWNER"] = old_owner
                if old_host is None:
                    os.environ.pop("HOSTNAME", None)
                else:
                    os.environ["HOSTNAME"] = old_host

            payload = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual("qa-owner", payload["run_owner"])
            self.assertEqual("qa-host", payload["run_host"])
            summary = summary_path.read_text(encoding="utf-8")
            self.assertIn("Run owner: `qa-owner`", summary)
            self.assertIn("Run host: `qa-host`", summary)

    def test_write_journey_report_backfills_failed_send_capture_required_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "journey-report.json"
            summary_path = Path(tmp) / "journey-summary.md"

            _write_journey_report(
                report_path=report_path,
                summary_path=summary_path,
                serial="SER123",
                steps=[
                    JourneyStepResult(
                        name="run-01:send-capture",
                        status="failed",
                        duration_seconds=1.23,
                        phase=None,
                        placeholder_visible=None,
                        runtime_status=None,
                        backend=None,
                        active_model_id=None,
                        failure_signature="send-capture-kickoff: launch failed",
                    ),
                ],
            )

            payload = json.loads(report_path.read_text(encoding="utf-8"))
            step = payload["steps"][0]
            self.assertEqual("error", step["phase"])
            self.assertIs(step["placeholder_visible"], False)
            self.assertEqual("unknown", step["runtime_status"])
            self.assertEqual("unknown", step["backend"])
            self.assertEqual("unknown", step["active_model_id"])

    def test_write_journey_report_summary_uses_normalized_failed_send_capture_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "journey-report.json"
            summary_path = Path(tmp) / "journey-summary.md"

            _write_journey_report(
                report_path=report_path,
                summary_path=summary_path,
                serial="SER123",
                steps=[
                    JourneyStepResult(
                        name="run-01:send-capture",
                        status="failed",
                        duration_seconds=1.23,
                        failure_signature="send-capture-kickoff: launch failed",
                    ),
                ],
            )

            summary = summary_path.read_text(encoding="utf-8")
            self.assertIn("| run-01:send-capture | - | error | failed | 1.23 |", summary)
            self.assertIn("| unknown | unknown | unknown | False |", summary)

    def test_lane_journey_retries_instrumentation_once_on_process_crash(self) -> None:
        from tools.devctl import lanes

        original_resolve_android_env = lanes._resolve_android_env
        original_ensure_serial = lanes._ensure_serial
        original_prepare_real_runtime_env = lanes.prepare_real_runtime_env
        original_capture_logcat = lanes._capture_logcat
        original_run_device_health_preflight = lanes._run_device_health_preflight
        original_run_serialized_gradle_install_step = lanes._run_serialized_gradle_install_step
        original_device_lock = lanes._device_lock
        original_run_instrumentation_class = lanes._run_instrumentation_class
        original_resolve_lane_artifact_dir = lanes._resolve_lane_artifact_dir
        original_remote_path_exists = lanes._remote_path_exists
        try:
            configs = load_devctl_configs(REPO_ROOT)
            lanes._resolve_android_env = lambda env: (True, env)
            lanes._ensure_serial = lambda _context: "SER123"
            lanes.prepare_real_runtime_env = lambda *_args, **_kwargs: lanes.RealRuntimePreparedEnv(
                serial="SER123",
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={},
                instrumentation_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            )
            lanes._capture_logcat = lambda *_args, **_kwargs: None
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            lanes._remote_path_exists = lambda *_args, **_kwargs: True

            instrumentation_attempts = 0

            def fake_run_instrumentation_class(**_kwargs):
                nonlocal instrumentation_attempts
                instrumentation_attempts += 1
                if instrumentation_attempts == 1:
                    raise DevctlError(
                        "DEVICE_ERROR",
                        "Instrumentation failed for "
                        "com.pocketagent.android.RealRuntimeJourneyInstrumentationTest#"
                        "runCoreJourneyGate (reported failure: Process crashed.).",
                    )
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="OK", stderr="")

            lanes._run_instrumentation_class = fake_run_instrumentation_class

            temp_dir = Path(tempfile.mkdtemp(prefix="journey-artifacts-"))
            lanes._resolve_lane_artifact_dir = lambda *_args, **_kwargs: temp_dir

            @contextmanager
            def fake_device_lock(*_args, **_kwargs):
                yield

            lanes._device_lock = fake_device_lock

            def fake_run(command, **_kwargs):
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            with mock.patch("tools.devctl.lanes.time.sleep", return_value=None):
                lanes._lane_journey_impl(["--steps", "instrumentation"], context)
        finally:
            lanes._resolve_android_env = original_resolve_android_env
            lanes._ensure_serial = original_ensure_serial
            lanes.prepare_real_runtime_env = original_prepare_real_runtime_env
            lanes._capture_logcat = original_capture_logcat
            lanes._run_device_health_preflight = original_run_device_health_preflight
            lanes._run_serialized_gradle_install_step = original_run_serialized_gradle_install_step
            lanes._device_lock = original_device_lock
            lanes._run_instrumentation_class = original_run_instrumentation_class
            lanes._resolve_lane_artifact_dir = original_resolve_lane_artifact_dir
            lanes._remote_path_exists = original_remote_path_exists

        self.assertEqual(2, instrumentation_attempts)
        report_payload = json.loads((temp_dir / "journey-report.json").read_text(encoding="utf-8"))
        self.assertEqual("passed", report_payload["steps"][0]["status"])

    def test_lane_journey_retries_instrumentation_once_on_exit_negative_15(self) -> None:
        from tools.devctl import lanes
        configs = load_devctl_configs(REPO_ROOT)

        original_resolve_android_env = lanes._resolve_android_env
        original_ensure_serial = lanes._ensure_serial
        original_prepare_real_runtime_env = lanes.prepare_real_runtime_env
        original_capture_logcat = lanes._capture_logcat
        original_run_device_health_preflight = lanes._run_device_health_preflight
        original_run_serialized_gradle_install_step = lanes._run_serialized_gradle_install_step
        original_device_lock = lanes._device_lock
        original_run_instrumentation_class = lanes._run_instrumentation_class
        original_resolve_lane_artifact_dir = lanes._resolve_lane_artifact_dir
        original_remote_path_exists = lanes._remote_path_exists

        try:
            lanes._resolve_android_env = lambda env: (True, env)
            lanes._ensure_serial = lambda _context: "SER123"
            lanes.prepare_real_runtime_env = lambda *_args, **_kwargs: lanes.RealRuntimePreparedEnv(
                serial="SER123",
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={},
                instrumentation_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            )
            lanes._capture_logcat = lambda *_args, **_kwargs: None
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            lanes._remote_path_exists = lambda *_args, **_kwargs: True

            instrumentation_attempts = 0

            def fake_run_instrumentation_class(**_kwargs):
                nonlocal instrumentation_attempts
                instrumentation_attempts += 1
                if instrumentation_attempts == 1:
                    raise DevctlError(
                        "DEVICE_ERROR",
                        "Instrumentation failed for "
                        "com.pocketagent.android.RealRuntimeJourneyInstrumentationTest#"
                        "runCoreJourneyGate (exit=-15).\n"
                        "INSTRUMENTATION_STATUS_CODE: 1",
                    )
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="OK", stderr="")

            lanes._run_instrumentation_class = fake_run_instrumentation_class

            temp_dir = Path(tempfile.mkdtemp(prefix="journey-artifacts-"))
            lanes._resolve_lane_artifact_dir = lambda *_args, **_kwargs: temp_dir

            @contextmanager
            def fake_device_lock(*_args, **_kwargs):
                yield

            lanes._device_lock = fake_device_lock

            def fake_run(command, **_kwargs):
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            with mock.patch("tools.devctl.lanes.time.sleep", return_value=None):
                lanes._lane_journey_impl(["--steps", "instrumentation"], context)
        finally:
            lanes._resolve_android_env = original_resolve_android_env
            lanes._ensure_serial = original_ensure_serial
            lanes.prepare_real_runtime_env = original_prepare_real_runtime_env
            lanes._capture_logcat = original_capture_logcat
            lanes._run_device_health_preflight = original_run_device_health_preflight
            lanes._run_serialized_gradle_install_step = original_run_serialized_gradle_install_step
            lanes._device_lock = original_device_lock
            lanes._run_instrumentation_class = original_run_instrumentation_class
            lanes._resolve_lane_artifact_dir = original_resolve_lane_artifact_dir
            lanes._remote_path_exists = original_remote_path_exists

        self.assertEqual(2, instrumentation_attempts)
        report_payload = json.loads((temp_dir / "journey-report.json").read_text(encoding="utf-8"))
        self.assertEqual("passed", report_payload["steps"][0]["status"])

    def test_lane_journey_retries_when_instrumentation_artifacts_are_missing_once(self) -> None:
        from tools.devctl import lanes
        configs = load_devctl_configs(REPO_ROOT)

        original_resolve_android_env = lanes._resolve_android_env
        original_ensure_serial = lanes._ensure_serial
        original_prepare_real_runtime_env = lanes.prepare_real_runtime_env
        original_capture_logcat = lanes._capture_logcat
        original_run_device_health_preflight = lanes._run_device_health_preflight
        original_run_serialized_gradle_install_step = lanes._run_serialized_gradle_install_step
        original_device_lock = lanes._device_lock
        original_run_instrumentation_class = lanes._run_instrumentation_class
        original_resolve_lane_artifact_dir = lanes._resolve_lane_artifact_dir
        original_remote_path_exists = lanes._remote_path_exists

        try:
            lanes._resolve_android_env = lambda env: (True, env)
            lanes._ensure_serial = lambda _context: "SER123"
            lanes.prepare_real_runtime_env = lambda *_args, **_kwargs: lanes.RealRuntimePreparedEnv(
                serial="SER123",
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={},
                instrumentation_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            )
            lanes._capture_logcat = lambda *_args, **_kwargs: None
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False

            instrumentation_attempts = 0

            def fake_run_instrumentation_class(**_kwargs):
                nonlocal instrumentation_attempts
                instrumentation_attempts += 1
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="OK", stderr="")

            lanes._run_instrumentation_class = fake_run_instrumentation_class

            def fake_remote_path_exists(*_args, **_kwargs):
                return instrumentation_attempts >= 2

            lanes._remote_path_exists = fake_remote_path_exists

            temp_dir = Path(tempfile.mkdtemp(prefix="journey-artifacts-"))
            lanes._resolve_lane_artifact_dir = lambda *_args, **_kwargs: temp_dir

            @contextmanager
            def fake_device_lock(*_args, **_kwargs):
                yield

            lanes._device_lock = fake_device_lock

            def fake_run(command, **_kwargs):
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            with mock.patch("tools.devctl.lanes.time.sleep", return_value=None):
                lanes._lane_journey_impl(["--steps", "instrumentation"], context)
        finally:
            lanes._resolve_android_env = original_resolve_android_env
            lanes._ensure_serial = original_ensure_serial
            lanes.prepare_real_runtime_env = original_prepare_real_runtime_env
            lanes._capture_logcat = original_capture_logcat
            lanes._run_device_health_preflight = original_run_device_health_preflight
            lanes._run_serialized_gradle_install_step = original_run_serialized_gradle_install_step
            lanes._device_lock = original_device_lock
            lanes._run_instrumentation_class = original_run_instrumentation_class
            lanes._resolve_lane_artifact_dir = original_resolve_lane_artifact_dir
            lanes._remote_path_exists = original_remote_path_exists

        self.assertEqual(2, instrumentation_attempts)
        report_payload = json.loads((temp_dir / "journey-report.json").read_text(encoding="utf-8"))
        self.assertEqual("passed", report_payload["steps"][0]["status"])

    def test_lane_journey_extends_instrumentation_timeout_when_send_capture_is_enabled(self) -> None:
        from tools.devctl import lanes

        original_resolve_android_env = lanes._resolve_android_env
        original_ensure_serial = lanes._ensure_serial
        original_prepare_real_runtime_env = lanes.prepare_real_runtime_env
        original_capture_logcat = lanes._capture_logcat
        original_run_device_health_preflight = lanes._run_device_health_preflight
        original_run_serialized_gradle_install_step = lanes._run_serialized_gradle_install_step
        original_device_lock = lanes._device_lock
        original_run_instrumentation_class = lanes._run_instrumentation_class
        original_resolve_lane_artifact_dir = lanes._resolve_lane_artifact_dir
        original_remote_path_exists = lanes._remote_path_exists
        original_wait_for_remote_path = lanes._wait_for_remote_path
        original_load_instrumentation_send_capture_step = lanes._load_instrumentation_send_capture_step
        try:
            configs = load_devctl_configs(REPO_ROOT)
            lanes._resolve_android_env = lambda env: (True, env)
            lanes._ensure_serial = lambda _context: "SER123"
            lanes.prepare_real_runtime_env = lambda *_args, **_kwargs: lanes.RealRuntimePreparedEnv(
                serial="SER123",
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={},
                instrumentation_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            )
            lanes._capture_logcat = lambda *_args, **_kwargs: None
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            lanes._remote_path_exists = lambda *_args, **_kwargs: True
            lanes._wait_for_remote_path = lambda *_args, **_kwargs: True
            lanes._load_instrumentation_send_capture_step = lambda **_kwargs: lanes.JourneyStepResult(
                name="send-capture",
                status="passed",
                duration_seconds=1.0,
                details="instrumentation-artifact",
                phase="completed",
                elapsed_ms=1000,
                mode="strict",
            )

            captured_timeout_seconds: list[float] = []
            captured_instrumentation_args: list[dict[str, str]] = []

            def fake_run_instrumentation_class(**kwargs):
                captured_timeout_seconds.append(float(kwargs["timeout_seconds"]))
                captured_instrumentation_args.append(dict(kwargs["args"]))
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="OK", stderr="")

            lanes._run_instrumentation_class = fake_run_instrumentation_class

            temp_dir = Path(tempfile.mkdtemp(prefix="journey-artifacts-"))
            lanes._resolve_lane_artifact_dir = lambda *_args, **_kwargs: temp_dir

            @contextmanager
            def fake_device_lock(*_args, **_kwargs):
                yield

            lanes._device_lock = fake_device_lock

            def fake_run(command, **_kwargs):
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lanes._lane_journey_impl(["--steps", "instrumentation,send-capture"], context)
        finally:
            lanes._resolve_android_env = original_resolve_android_env
            lanes._ensure_serial = original_ensure_serial
            lanes.prepare_real_runtime_env = original_prepare_real_runtime_env
            lanes._capture_logcat = original_capture_logcat
            lanes._run_device_health_preflight = original_run_device_health_preflight
            lanes._run_serialized_gradle_install_step = original_run_serialized_gradle_install_step
            lanes._device_lock = original_device_lock
            lanes._run_instrumentation_class = original_run_instrumentation_class
            lanes._resolve_lane_artifact_dir = original_resolve_lane_artifact_dir
            lanes._remote_path_exists = original_remote_path_exists
            lanes._wait_for_remote_path = original_wait_for_remote_path
            lanes._load_instrumentation_send_capture_step = original_load_instrumentation_send_capture_step

        self.assertEqual([480.0], captured_timeout_seconds)
        self.assertEqual(
            "Reply with exactly: OK.",
            captured_instrumentation_args[0]["journey_prompt"],
        )

    def test_lane_journey_prefers_instrumentation_send_capture_artifact_over_maestro(self) -> None:
        from tools.devctl import lanes

        configs = load_devctl_configs(REPO_ROOT)
        original_resolve_android_env = lanes._resolve_android_env
        original_ensure_serial = lanes._ensure_serial
        original_prepare_real_runtime_env = lanes.prepare_real_runtime_env
        original_capture_logcat = lanes._capture_logcat
        original_run_device_health_preflight = lanes._run_device_health_preflight
        original_run_serialized_gradle_install_step = lanes._run_serialized_gradle_install_step
        original_device_lock = lanes._device_lock
        original_run_instrumentation_class = lanes._run_instrumentation_class
        original_resolve_lane_artifact_dir = lanes._resolve_lane_artifact_dir
        original_remote_path_exists = lanes._remote_path_exists
        original_run_send_capture_stage = lanes._run_send_capture_stage

        try:
            lanes._resolve_android_env = lambda env: (True, env)
            lanes._ensure_serial = lambda _context: "SER123"
            lanes.prepare_real_runtime_env = lambda *_args, **_kwargs: lanes.RealRuntimePreparedEnv(
                serial="SER123",
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3-1.7b-q4_k_m": "/sdcard/Android/media/com.pocketagent.android/models/qwen3-1.7b-q4_k_m.gguf",
                },
                model_host_paths_by_id={},
                instrumentation_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            )
            lanes._capture_logcat = lambda *_args, **_kwargs: None
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            lanes._run_serialized_gradle_install_step = lambda **_kwargs: False
            lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="OK",
                stderr="",
            )
            lanes._remote_path_exists = lambda *_args, **_kwargs: True
            temp_dir = Path(tempfile.mkdtemp(prefix="journey-artifacts-"))
            lanes._resolve_lane_artifact_dir = lambda *_args, **_kwargs: temp_dir

            @contextmanager
            def fake_device_lock(*_args, **_kwargs):
                yield

            lanes._device_lock = fake_device_lock

            def unexpected_send_capture(**_kwargs):
                raise AssertionError("Maestro send-capture fallback should not run when instrumentation artifact exists")

            lanes._run_send_capture_stage = unexpected_send_capture

            def fake_run(command, **_kwargs):
                if command[:4] == ["adb", "-s", "SER123", "pull"]:
                    destination = Path(command[5])
                    artifact_dir = destination / "20260503-journey"
                    artifact_dir.mkdir(parents=True, exist_ok=True)
                    (artifact_dir / "journey-send-capture.json").write_text(
                        json.dumps(
                            {
                                "phase": "completed",
                                "elapsed_ms": 4321,
                                "first_token_ms": 987,
                                "runtime_status": "Ready",
                                "backend": "NATIVE_JNI",
                                "active_model_id": "qwen3-1.7b-q4_k_m",
                                "placeholder_visible": False,
                            }
                        ),
                        encoding="utf-8",
                    )
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lanes._lane_journey_impl(["--steps", "instrumentation,send-capture"], context)
        finally:
            lanes._resolve_android_env = original_resolve_android_env
            lanes._ensure_serial = original_ensure_serial
            lanes.prepare_real_runtime_env = original_prepare_real_runtime_env
            lanes._capture_logcat = original_capture_logcat
            lanes._run_device_health_preflight = original_run_device_health_preflight
            lanes._run_serialized_gradle_install_step = original_run_serialized_gradle_install_step
            lanes._device_lock = original_device_lock
            lanes._run_instrumentation_class = original_run_instrumentation_class
            lanes._resolve_lane_artifact_dir = original_resolve_lane_artifact_dir
            lanes._remote_path_exists = original_remote_path_exists
            lanes._run_send_capture_stage = original_run_send_capture_stage

        report_payload = json.loads((temp_dir / "journey-report.json").read_text(encoding="utf-8"))
        self.assertEqual("passed", report_payload["steps"][0]["status"])
        self.assertEqual("passed", report_payload["steps"][1]["status"])
        self.assertEqual("completed", report_payload["steps"][1]["phase"])
        self.assertEqual("Ready", report_payload["steps"][1]["runtime_status"])
        self.assertEqual("NATIVE_JNI", report_payload["steps"][1]["backend"])
        self.assertEqual("qwen3-1.7b-q4_k_m", report_payload["steps"][1]["active_model_id"])
        self.assertFalse(report_payload["steps"][1]["placeholder_visible"])

    def test_device_lock_is_reentrant_for_same_process(self) -> None:
        from tools.devctl import lanes

        lock_path = lanes._device_lock_path("SER-LOCK-REENTRANT")
        try:
            with lanes._device_lock("SER-LOCK-REENTRANT", owner="test:outer", timeout_seconds=1):
                with lanes._device_lock("SER-LOCK-REENTRANT", owner="test:inner", timeout_seconds=1):
                    self.assertTrue(True)
        finally:
            lock_path.unlink(missing_ok=True)
            if lock_path.parent.exists() and not any(lock_path.parent.iterdir()):
                lock_path.parent.rmdir()

    def test_device_lock_times_out_when_held_by_another_process(self) -> None:
        from tools.devctl import lanes

        if lanes.fcntl is None:
            self.skipTest("fcntl is unavailable on this platform")

        lock_path = lanes._device_lock_path("SER-LOCK-TIMEOUT")
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        holder_script = (
            "import fcntl, pathlib, time\n"
            f"p = pathlib.Path({str(lock_path)!r})\n"
            "p.parent.mkdir(parents=True, exist_ok=True)\n"
            "f = p.open('a+')\n"
            "fcntl.flock(f.fileno(), fcntl.LOCK_EX)\n"
            "time.sleep(8)\n"
        )
        holder = subprocess.Popen(["python3", "-c", holder_script])
        try:
            time.sleep(0.25)
            with self.assertRaises(DevctlError) as raised:
                with lanes._device_lock("SER-LOCK-TIMEOUT", owner="test:timeout", timeout_seconds=1):
                    self.fail("Expected timeout while waiting for held device lock")
            self.assertEqual("DEVICE_ERROR", raised.exception.code)
        finally:
            holder.terminate()
            holder.wait(timeout=5)
            lock_path.unlink(missing_ok=True)
            if lock_path.parent.exists() and not any(lock_path.parent.iterdir()):
                lock_path.parent.rmdir()


if __name__ == "__main__":
    unittest.main()
