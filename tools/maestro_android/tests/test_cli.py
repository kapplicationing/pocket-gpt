from __future__ import annotations

import argparse
import subprocess
import tempfile
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
