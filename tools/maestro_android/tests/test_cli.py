from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.maestro_android import cli
from tools.maestro_android.common import MaestroAndroidError


class CliTest(unittest.TestCase):
    def test_main_dispatches_lane(self) -> None:
        captured: list[list[str]] = []
        original = cli.run_subprocess
        try:
            cli.run_subprocess = lambda command, **_kwargs: captured.append(list(command))  # type: ignore[assignment]
            exit_code = cli.main(["lane", "smoke"])
        finally:
            cli.run_subprocess = original  # type: ignore[assignment]

        self.assertEqual(0, exit_code)
        self.assertEqual([["python3", "tools/devctl/main.py", "lane", "maestro"]], captured)

    def test_main_dispatches_lane_with_device_pinned_in_env(self) -> None:
        captured: list[tuple[list[str], dict[str, str] | None]] = []
        original = cli.run_subprocess
        try:
            def fake_run_subprocess(command, **kwargs):
                command_list = list(command)
                if command_list == ["adb", "devices", "-l"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="List of devices attached\nSER123 device\n", stderr="")
                if command_list == ["adb", "mdns", "services"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")
                captured.append((command_list, kwargs.get("env")))
                return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

            cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]
            exit_code = cli.main(["lane", "smoke", "--device", "SER123"])
        finally:
            cli.run_subprocess = original  # type: ignore[assignment]

        self.assertEqual(0, exit_code)
        self.assertEqual([["python3", "tools/devctl/main.py", "lane", "maestro"]], [command for command, _env in captured])
        self.assertEqual("SER123", captured[0][1]["ANDROID_SERIAL"])
        self.assertEqual("SER123", captured[0][1]["ADB_SERIAL"])

    def test_main_dispatches_lane_with_endpoint_resolved_to_mdns_transport(self) -> None:
        captured: list[tuple[list[str], dict[str, str] | None]] = []
        original = cli.run_subprocess
        try:
            def fake_run_subprocess(command, **kwargs):
                command_list = list(command)
                if command_list == ["adb", "devices", "-l"]:
                    return subprocess.CompletedProcess(
                        command_list,
                        0,
                        stdout=(
                            "List of devices attached\n"
                            "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp device product:a model:x transport_id:7\n"
                        ),
                        stderr="",
                    )
                if command_list == ["adb", "mdns", "services"]:
                    return subprocess.CompletedProcess(
                        command_list,
                        0,
                        stdout="adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp 192.168.1.37:37361\n",
                        stderr="",
                    )
                captured.append((command_list, kwargs.get("env")))
                return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

            cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]
            exit_code = cli.main(["lane", "smoke", "--device", "192.168.1.37:37361"])
        finally:
            cli.run_subprocess = original  # type: ignore[assignment]

        self.assertEqual(0, exit_code)
        lane_calls = [(command, env) for command, env in captured if command == ["python3", "tools/devctl/main.py", "lane", "maestro"]]
        self.assertEqual(1, len(lane_calls))
        self.assertEqual(
            "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp",
            lane_calls[0][1]["ANDROID_SERIAL"],
        )

    def test_scoped_requires_title_description_comments(self) -> None:
        tmp_root = Path.cwd() / "tmp"
        tmp_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=tmp_root) as tmp:
            flow_path = Path(tmp) / "bad.yaml"
            flow_path.write_text("appId: x\n---\n- launchApp\n", encoding="utf-8")
            with self.assertRaises(MaestroAndroidError):
                cli._validate_scoped_flow(flow_path, cli.load_config())

    def test_run_test_pins_install_to_selected_device(self) -> None:
        config = cli.load_config()
        flow_path = cli.REPO_ROOT / "tests/maestro/scenario-a.yaml"
        captured: list[tuple[list[str], dict[str, str] | None]] = []

        original_resolve_serial = cli._resolve_serial
        original_select_flows = cli._select_flows
        original_resolve_apk = cli._resolve_apk
        original_normalize_artifact_root = cli._normalize_artifact_root
        original_capture_logcat = cli._capture_logcat
        original_run_subprocess = cli.run_subprocess
        try:
            cli._resolve_serial = lambda _device: "SER123"  # type: ignore[assignment]
            cli._select_flows = lambda *_args, **_kwargs: [flow_path]  # type: ignore[assignment]
            cli._resolve_apk = lambda _config: cli.REPO_ROOT / "fake.apk"  # type: ignore[assignment]
            cli._capture_logcat = lambda _serial, output_path: output_path.write_text("", encoding="utf-8")  # type: ignore[assignment]

            with tempfile.TemporaryDirectory() as tmp:
                cli._normalize_artifact_root = lambda *_args, **_kwargs: Path(tmp) / "artifacts"  # type: ignore[assignment]

                def fake_run_subprocess(command, **kwargs):
                    command_list = list(command)
                    env = kwargs.get("env")
                    captured.append((command_list, env))
                    if command_list[:5] == ["adb", "-s", "SER123", "shell", "pm"]:
                        return subprocess.CompletedProcess(command_list, 0, stdout="package:/tmp/base.apk\n", stderr="")
                    if command_list[:4] == ["adb", "-s", "SER123", "get-state"]:
                        return subprocess.CompletedProcess(command_list, 0, stdout="device\n", stderr="")
                    if command_list[:4] == ["adb", "-s", "SER123", "shell"] and command_list[4:] == ["echo", "maestro-android-ok"]:
                        return subprocess.CompletedProcess(command_list, 0, stdout="maestro-android-ok\n", stderr="")
                    if command_list[:4] == ["maestro", "--device", "SER123", "test"]:
                        return subprocess.CompletedProcess(command_list, 0, stdout="<testsuite/>\n", stderr="")
                    return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

                cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]

                parsed = argparse.Namespace(
                    device="SER123",
                    flows=[],
                    flow_csv="tests/maestro/scenario-a.yaml",
                    include_tags="",
                    exclude_tags="",
                    no_build=True,
                    no_install=False,
                    format="junit",
                    clear_state=False,
                )

                cli._run_test(parsed, config)
        finally:
            cli._resolve_serial = original_resolve_serial  # type: ignore[assignment]
            cli._select_flows = original_select_flows  # type: ignore[assignment]
            cli._resolve_apk = original_resolve_apk  # type: ignore[assignment]
            cli._normalize_artifact_root = original_normalize_artifact_root  # type: ignore[assignment]
            cli._capture_logcat = original_capture_logcat  # type: ignore[assignment]
            cli.run_subprocess = original_run_subprocess  # type: ignore[assignment]

        install_calls = [env for command, env in captured if command == config.project.install_command]
        self.assertEqual(1, len(install_calls))
        self.assertIsNotNone(install_calls[0])
        self.assertEqual("SER123", install_calls[0]["ANDROID_SERIAL"])
        self.assertEqual("SER123", install_calls[0]["ADB_SERIAL"])

    def test_run_test_fails_fast_when_app_missing_with_no_install(self) -> None:
        config = cli.load_config()
        flow_path = cli.REPO_ROOT / "tests/maestro/scenario-a.yaml"

        original_resolve_serial = cli._resolve_serial
        original_select_flows = cli._select_flows
        original_resolve_apk = cli._resolve_apk
        original_run_subprocess = cli.run_subprocess
        try:
            cli._resolve_serial = lambda _device: "SER123"  # type: ignore[assignment]
            cli._select_flows = lambda *_args, **_kwargs: [flow_path]  # type: ignore[assignment]
            cli._resolve_apk = lambda _config: cli.REPO_ROOT / "fake.apk"  # type: ignore[assignment]

            def fake_run_subprocess(command, **kwargs):
                command_list = list(command)
                if command_list[:5] == ["adb", "-s", "SER123", "shell", "pm"]:
                    return subprocess.CompletedProcess(command_list, 1, stdout="", stderr="")
                return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

            cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]

            parsed = argparse.Namespace(
                device="SER123",
                flows=[],
                flow_csv="tests/maestro/scenario-a.yaml",
                include_tags="",
                exclude_tags="",
                no_build=True,
                no_install=True,
                format="junit",
                clear_state=False,
            )

            with self.assertRaises(MaestroAndroidError) as raised:
                cli._run_test(parsed, config)
        finally:
            cli._resolve_serial = original_resolve_serial  # type: ignore[assignment]
            cli._select_flows = original_select_flows  # type: ignore[assignment]
            cli._resolve_apk = original_resolve_apk  # type: ignore[assignment]
            cli.run_subprocess = original_run_subprocess  # type: ignore[assignment]

        self.assertEqual("DEVICE_ERROR", raised.exception.code)
        self.assertIn("Rerun without --no-install", raised.exception.message)

    def test_run_test_retries_transient_maestro_launch_failure(self) -> None:
        config = cli.load_config()
        flow_path = cli.REPO_ROOT / "tests/maestro/scenario-a.yaml"
        maestro_attempts = 0
        issued_commands: list[list[str]] = []

        original_resolve_serial = cli._resolve_serial
        original_select_flows = cli._select_flows
        original_resolve_apk = cli._resolve_apk
        original_normalize_artifact_root = cli._normalize_artifact_root
        original_capture_logcat = cli._capture_logcat
        original_run_subprocess = cli.run_subprocess
        try:
            cli._resolve_serial = lambda _device: "SER123"  # type: ignore[assignment]
            cli._select_flows = lambda *_args, **_kwargs: [flow_path]  # type: ignore[assignment]
            cli._resolve_apk = lambda _config: cli.REPO_ROOT / "fake.apk"  # type: ignore[assignment]
            cli._capture_logcat = lambda _serial, output_path: output_path.write_text("", encoding="utf-8")  # type: ignore[assignment]

            def fake_run_subprocess(command, **kwargs):
                nonlocal maestro_attempts
                command_list = list(command)
                issued_commands.append(command_list)
                if command_list[:5] == ["adb", "-s", "SER123", "shell", "pm"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="package:/tmp/base.apk\n", stderr="")
                if command_list[:4] == ["adb", "-s", "SER123", "get-state"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="device\n", stderr="")
                if command_list[:4] == ["adb", "-s", "SER123", "shell"] and command_list[4:] == ["echo", "maestro-android-ok"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="maestro-android-ok\n", stderr="")
                if command_list[:4] == ["maestro", "--device", "SER123", "test"]:
                    maestro_attempts += 1
                    if maestro_attempts == 1:
                        return subprocess.CompletedProcess(command_list, 1, stdout="", stderr="TimeoutException: TcpForwarder")
                    return subprocess.CompletedProcess(command_list, 0, stdout="<testsuite/>\n", stderr="")
                return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

            cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]

            with tempfile.TemporaryDirectory() as tmp, mock.patch("tools.maestro_android.cli.time.sleep", return_value=None):
                cli._normalize_artifact_root = lambda *_args, **_kwargs: Path(tmp) / "artifacts"  # type: ignore[assignment]
                parsed = argparse.Namespace(
                    device="SER123",
                    flows=[],
                    flow_csv="tests/maestro/scenario-a.yaml",
                    include_tags="",
                    exclude_tags="",
                    no_build=True,
                    no_install=True,
                    format="junit",
                    clear_state=False,
                )

                cli._run_test(parsed, config)
                output = (Path(tmp) / "artifacts/flows/scenario-a/maestro-output.txt").read_text(encoding="utf-8")
                diagnostics = json.loads((Path(tmp) / "artifacts/flows/scenario-a/diagnostics.json").read_text(encoding="utf-8"))
        finally:
            cli._resolve_serial = original_resolve_serial  # type: ignore[assignment]
            cli._select_flows = original_select_flows  # type: ignore[assignment]
            cli._resolve_apk = original_resolve_apk  # type: ignore[assignment]
            cli._normalize_artifact_root = original_normalize_artifact_root  # type: ignore[assignment]
            cli._capture_logcat = original_capture_logcat  # type: ignore[assignment]
            cli.run_subprocess = original_run_subprocess  # type: ignore[assignment]

        self.assertEqual(2, maestro_attempts)
        self.assertIn(["adb", "-s", "SER123", "forward", "--remove-all"], issued_commands)
        self.assertIn(["adb", "-s", "SER123", "wait-for-device"], issued_commands)
        self.assertIn(["adb", "-s", "SER123", "shell", "am", "force-stop", config.project.app_id], issued_commands)
        self.assertIn("=== attempt 1 ===", output)
        self.assertIn("TimeoutException: TcpForwarder", output)
        self.assertIn("=== attempt 2 ===", output)
        self.assertEqual("passed", diagnostics["failure_phase"])

    def test_run_maestro_transport_recovery_reconnects_adb_tls_mdns_serial(self) -> None:
        issued_commands: list[list[str]] = []
        original = cli.run_subprocess
        try:
            cli.run_subprocess = lambda command, **_kwargs: issued_commands.append(list(command))  # type: ignore[assignment]
            cli._run_maestro_transport_recovery(
                "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp",
                env={"ANDROID_SERIAL": "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp"},
            )
        finally:
            cli.run_subprocess = original  # type: ignore[assignment]

        self.assertIn(["adb", "disconnect", "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp"], issued_commands)
        self.assertIn(["adb", "connect", "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp"], issued_commands)

    def test_run_test_does_not_retry_non_transient_maestro_failure(self) -> None:
        config = cli.load_config()
        flow_path = cli.REPO_ROOT / "tests/maestro/scenario-a.yaml"
        maestro_attempts = 0

        original_resolve_serial = cli._resolve_serial
        original_select_flows = cli._select_flows
        original_resolve_apk = cli._resolve_apk
        original_normalize_artifact_root = cli._normalize_artifact_root
        original_capture_logcat = cli._capture_logcat
        original_run_subprocess = cli.run_subprocess
        try:
            cli._resolve_serial = lambda _device: "SER123"  # type: ignore[assignment]
            cli._select_flows = lambda *_args, **_kwargs: [flow_path]  # type: ignore[assignment]
            cli._resolve_apk = lambda _config: cli.REPO_ROOT / "fake.apk"  # type: ignore[assignment]
            cli._capture_logcat = lambda _serial, output_path: output_path.write_text("", encoding="utf-8")  # type: ignore[assignment]

            def fake_run_subprocess(command, **kwargs):
                nonlocal maestro_attempts
                command_list = list(command)
                if command_list[:5] == ["adb", "-s", "SER123", "shell", "pm"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="package:/tmp/base.apk\n", stderr="")
                if command_list[:4] == ["adb", "-s", "SER123", "get-state"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="device\n", stderr="")
                if command_list[:4] == ["adb", "-s", "SER123", "shell"] and command_list[4:] == ["echo", "maestro-android-ok"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="maestro-android-ok\n", stderr="")
                if command_list[:4] == ["maestro", "--device", "SER123", "test"]:
                    maestro_attempts += 1
                    return subprocess.CompletedProcess(command_list, 1, stdout="", stderr="Element not found")
                return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

            cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]

            with tempfile.TemporaryDirectory() as tmp:
                cli._normalize_artifact_root = lambda *_args, **_kwargs: Path(tmp) / "artifacts"  # type: ignore[assignment]
                parsed = argparse.Namespace(
                    device="SER123",
                    flows=[],
                    flow_csv="tests/maestro/scenario-a.yaml",
                    include_tags="",
                    exclude_tags="",
                    no_build=True,
                    no_install=True,
                    format="junit",
                    clear_state=False,
                )

                with self.assertRaises(MaestroAndroidError):
                    cli._run_test(parsed, config)
                output = (Path(tmp) / "artifacts/flows/scenario-a/maestro-output.txt").read_text(encoding="utf-8")
        finally:
            cli._resolve_serial = original_resolve_serial  # type: ignore[assignment]
            cli._select_flows = original_select_flows  # type: ignore[assignment]
            cli._resolve_apk = original_resolve_apk  # type: ignore[assignment]
            cli._normalize_artifact_root = original_normalize_artifact_root  # type: ignore[assignment]
            cli._capture_logcat = original_capture_logcat  # type: ignore[assignment]
            cli.run_subprocess = original_run_subprocess  # type: ignore[assignment]

        self.assertEqual(1, maestro_attempts)

    def test_run_test_classifies_transient_failure_with_healthy_adb_as_maestro_bootstrap(self) -> None:
        config = cli.load_config()
        flow_path = cli.REPO_ROOT / "tests/maestro/scenario-a.yaml"

        original_resolve_serial = cli._resolve_serial
        original_select_flows = cli._select_flows
        original_resolve_apk = cli._resolve_apk
        original_normalize_artifact_root = cli._normalize_artifact_root
        original_capture_logcat = cli._capture_logcat
        original_run_subprocess = cli.run_subprocess
        try:
            cli._resolve_serial = lambda _device: "SER123"  # type: ignore[assignment]
            cli._select_flows = lambda *_args, **_kwargs: [flow_path]  # type: ignore[assignment]
            cli._resolve_apk = lambda _config: cli.REPO_ROOT / "fake.apk"  # type: ignore[assignment]
            cli._capture_logcat = lambda _serial, output_path: output_path.write_text("", encoding="utf-8")  # type: ignore[assignment]

            def fake_run_subprocess(command, **kwargs):
                command_list = list(command)
                if command_list[:5] == ["adb", "-s", "SER123", "shell", "pm"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="package:/tmp/base.apk\n", stderr="")
                if command_list[:4] == ["adb", "-s", "SER123", "get-state"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="device\n", stderr="")
                if command_list[:4] == ["adb", "-s", "SER123", "shell"] and command_list[4:] == ["echo", "maestro-android-ok"]:
                    return subprocess.CompletedProcess(command_list, 0, stdout="maestro-android-ok\n", stderr="")
                if command_list[:4] == ["maestro", "--device", "SER123", "test"]:
                    return subprocess.CompletedProcess(command_list, 1, stdout="", stderr="UNAVAILABLE: io exception\nConnection refused")
                return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

            cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]

            with tempfile.TemporaryDirectory() as tmp, mock.patch("tools.maestro_android.cli.time.sleep", return_value=None):
                cli._normalize_artifact_root = lambda *_args, **_kwargs: Path(tmp) / "artifacts"  # type: ignore[assignment]
                parsed = argparse.Namespace(
                    device="SER123",
                    flows=[],
                    flow_csv="tests/maestro/scenario-a.yaml",
                    include_tags="",
                    exclude_tags="",
                    no_build=True,
                    no_install=True,
                    format="junit",
                    clear_state=False,
                )

                with self.assertRaises(MaestroAndroidError) as raised:
                    cli._run_test(parsed, config)
                diagnostics = json.loads((Path(tmp) / "artifacts/flows/scenario-a/diagnostics.json").read_text(encoding="utf-8"))
                output = (Path(tmp) / "artifacts/flows/scenario-a/maestro-output.txt").read_text(encoding="utf-8")
        finally:
            cli._resolve_serial = original_resolve_serial  # type: ignore[assignment]
            cli._select_flows = original_select_flows  # type: ignore[assignment]
            cli._resolve_apk = original_resolve_apk  # type: ignore[assignment]
            cli._normalize_artifact_root = original_normalize_artifact_root  # type: ignore[assignment]
            cli._capture_logcat = original_capture_logcat  # type: ignore[assignment]
            cli.run_subprocess = original_run_subprocess  # type: ignore[assignment]

        self.assertIn("[maestro_server_bootstrap]", raised.exception.message)
        self.assertEqual("maestro_server_bootstrap", diagnostics["failure_phase"])
        self.assertIn("=== attempt 1 ===", output)
        self.assertIn("=== attempt 2 ===", output)

    def test_run_test_retries_when_transient_marker_only_exists_in_maestro_log(self) -> None:
        config = cli.load_config()
        flow_path = cli.REPO_ROOT / "tests/maestro/scenario-a.yaml"
        maestro_attempts = 0

        original_resolve_serial = cli._resolve_serial
        original_select_flows = cli._select_flows
        original_resolve_apk = cli._resolve_apk
        original_normalize_artifact_root = cli._normalize_artifact_root
        original_capture_logcat = cli._capture_logcat
        original_run_subprocess = cli.run_subprocess
        try:
            cli._resolve_serial = lambda _device: "SER123"  # type: ignore[assignment]
            cli._select_flows = lambda *_args, **_kwargs: [flow_path]  # type: ignore[assignment]
            cli._resolve_apk = lambda _config: cli.REPO_ROOT / "fake.apk"  # type: ignore[assignment]
            cli._capture_logcat = lambda _serial, output_path: output_path.write_text("", encoding="utf-8")  # type: ignore[assignment]

            with tempfile.TemporaryDirectory() as tmp, mock.patch("tools.maestro_android.cli.time.sleep", return_value=None):
                artifact_root = Path(tmp) / "artifacts"
                cli._normalize_artifact_root = lambda *_args, **_kwargs: artifact_root  # type: ignore[assignment]

                def fake_run_subprocess(command, **kwargs):
                    nonlocal maestro_attempts
                    command_list = list(command)
                    if command_list[:5] == ["adb", "-s", "SER123", "shell", "pm"]:
                        return subprocess.CompletedProcess(command_list, 0, stdout="package:/tmp/base.apk\n", stderr="")
                    if command_list[:4] == ["adb", "-s", "SER123", "get-state"]:
                        return subprocess.CompletedProcess(command_list, 0, stdout="device\n", stderr="")
                    if command_list[:4] == ["adb", "-s", "SER123", "shell"] and command_list[4:] == ["echo", "maestro-android-ok"]:
                        return subprocess.CompletedProcess(command_list, 0, stdout="maestro-android-ok\n", stderr="")
                    if command_list[:4] == ["maestro", "--device", "SER123", "test"]:
                        maestro_attempts += 1
                        debug_dir = Path(command_list[6])
                        log_dir = debug_dir / ".maestro/tests/2026-04-26_000000"
                        log_dir.mkdir(parents=True, exist_ok=True)
                        if maestro_attempts == 1:
                            (log_dir / "maestro.log").write_text("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception\n", encoding="utf-8")
                            return subprocess.CompletedProcess(command_list, 1, stdout="Waiting for flows to complete...\n[Failed]\n", stderr="")
                        (log_dir / "maestro.log").write_text("flow passed\n", encoding="utf-8")
                        return subprocess.CompletedProcess(command_list, 0, stdout="<testsuite/>\n", stderr="")
                    return subprocess.CompletedProcess(command_list, 0, stdout="", stderr="")

                cli.run_subprocess = fake_run_subprocess  # type: ignore[assignment]

                parsed = argparse.Namespace(
                    device="SER123",
                    flows=[],
                    flow_csv="tests/maestro/scenario-a.yaml",
                    include_tags="",
                    exclude_tags="",
                    no_build=True,
                    no_install=True,
                    format="junit",
                    clear_state=False,
                )

                cli._run_test(parsed, config)
                output = (artifact_root / "flows/scenario-a/maestro-output.txt").read_text(encoding="utf-8")
        finally:
            cli._resolve_serial = original_resolve_serial  # type: ignore[assignment]
            cli._select_flows = original_select_flows  # type: ignore[assignment]
            cli._resolve_apk = original_resolve_apk  # type: ignore[assignment]
            cli._normalize_artifact_root = original_normalize_artifact_root  # type: ignore[assignment]
            cli._capture_logcat = original_capture_logcat  # type: ignore[assignment]
            cli.run_subprocess = original_run_subprocess  # type: ignore[assignment]

        self.assertEqual(2, maestro_attempts)
        self.assertIn("UNAVAILABLE: io exception", output)
        self.assertIn("=== attempt 2 ===", output)


if __name__ == "__main__":
    unittest.main()
