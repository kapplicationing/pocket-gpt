from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.maestro_android.config import load_config
from tools.maestro_android.common import MaestroAndroidError


class ConfigTest(unittest.TestCase):
    def test_load_config_uses_defaults_without_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            config = load_config(repo_root=Path(tmp))

        self.assertEqual("apps/mobile-android/build/outputs/apk/debug/*.apk", config.project.apk_glob)
        self.assertIn("smoke", config.lanes)

    def test_load_config_merges_override(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".maestro-android.yaml").write_text(
                "project:\n  app_id: com.example.app\nlanes:\n  smoke:\n    kind: command\n    argv: [bash, smoke.sh]\n",
                encoding="utf-8",
            )
            config = load_config(repo_root=root)

        self.assertEqual("com.example.app", config.project.app_id)
        self.assertEqual("command", config.lanes["smoke"].kind)

    def test_load_config_accepts_legacy_doctor_keys(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".maestro-android.yaml").write_text(
                (
                    "doctor:\n"
                    "  required_commands: [adb, maestro, python3]\n"
                    "  optional_commands: [emulator]\n"
                    "  require_gradlew: true\n"
                ),
                encoding="utf-8",
            )
            config = load_config(repo_root=root)

        self.assertEqual(["python3", "tools/devctl/main.py", "doctor"], config.doctor.delegate_command)
        self.assertEqual(["adb", "maestro", "python3"], config.doctor.required_commands)
        self.assertEqual(["emulator"], config.doctor.optional_commands)
        self.assertTrue(config.doctor.require_gradlew)

    def test_explicit_missing_config_raises(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(MaestroAndroidError):
                load_config(repo_root=Path(tmp), explicit_path=Path(tmp) / "missing.yaml")


if __name__ == "__main__":
    unittest.main()
