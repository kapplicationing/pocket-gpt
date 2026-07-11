import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.benchmarks.verify_android_baseline_profile import (
    BaselineProfileVerificationError,
    verify_baseline_profile,
)


APP_RULE = "HSPLcom/pocketagent/android/MainActivity;->onCreate(Landroid/os/Bundle;)V"
LIBRARY_RULE = "HSPLandroidx/compose/runtime/ComposerImpl;->startRestartGroup(I)Landroidx/compose/runtime/Composer;"


class VerifyAndroidBaselineProfileTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.apk = self.root / "app.apk"
        self.generated = self.root / "generated.txt"
        self.merged = self.root / "merged.txt"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _write_apk(self, *, profile_size: int = 1, metadata_size: int = 1) -> None:
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("assets/dexopt/baseline.prof", b"p" * profile_size)
            archive.writestr("assets/dexopt/baseline.profm", b"m" * metadata_size)

    def test_accepts_nonempty_packaging_with_app_rules_merged(self) -> None:
        self._write_apk()
        self.generated.write_text(f"{APP_RULE}\n", encoding="utf-8")
        self.merged.write_text(f"{LIBRARY_RULE}\n{APP_RULE}\n", encoding="utf-8")

        result = verify_baseline_profile(self.apk, self.generated, self.merged)

        self.assertEqual(1, result.app_rule_count)
        self.assertEqual(1, result.packaged_entry_sizes["assets/dexopt/baseline.prof"])

    def test_rejects_transitive_library_profile_without_app_rules(self) -> None:
        self._write_apk()
        self.generated.write_text(f"{LIBRARY_RULE}\n", encoding="utf-8")
        self.merged.write_text(f"{LIBRARY_RULE}\n", encoding="utf-8")

        with self.assertRaisesRegex(BaselineProfileVerificationError, "no PocketGPT app rules"):
            verify_baseline_profile(self.apk, self.generated, self.merged)

    def test_rejects_generated_rules_that_were_not_merged(self) -> None:
        self._write_apk()
        self.generated.write_text(f"{APP_RULE}\n", encoding="utf-8")
        self.merged.write_text(f"{LIBRARY_RULE}\n", encoding="utf-8")

        with self.assertRaisesRegex(BaselineProfileVerificationError, "not merged"):
            verify_baseline_profile(self.apk, self.generated, self.merged)

    def test_rejects_missing_or_empty_packaged_entries(self) -> None:
        self._write_apk(metadata_size=0)
        self.generated.write_text(f"{APP_RULE}\n", encoding="utf-8")
        self.merged.write_text(f"{APP_RULE}\n", encoding="utf-8")

        with self.assertRaisesRegex(BaselineProfileVerificationError, "empty"):
            verify_baseline_profile(self.apk, self.generated, self.merged)


if __name__ == "__main__":
    unittest.main()
