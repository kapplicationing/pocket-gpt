import re
import sys
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(SCRIPT_DIR))

from android_perf_harness import (  # noqa: E402
    HarnessValidationError,
    assert_foreground,
    assert_package_identity_unchanged,
    assert_scenario_final_state,
    find_node_center,
    node_text_length,
    run_id,
)


class AndroidPerfHarnessTest(unittest.TestCase):
    def fixture(self, name: str) -> str:
        return (FIXTURES / name).read_text(encoding="utf-8")

    def test_exact_resource_selector_allows_only_package_qualified_id(self):
        source = self.fixture("ui-selector-similar.xml")

        self.assertEqual(find_node_center(source, "model_search_input"), (200, 300))
        self.assertEqual(node_text_length(source, "model_search_input"), 4)

    def test_exact_plain_resource_selector_is_supported(self):
        source = (
            '<hierarchy><node resource-id="session_search_input" text="chat" '
            'bounds="[20,40][120,140]" /></hierarchy>'
        )

        self.assertEqual(find_node_center(source, "session_search_input"), (70, 90))

    def test_similar_selector_and_generic_edit_text_do_not_match(self):
        source = self.fixture("ui-selector-similar.xml")

        with self.assertRaisesRegex(HarnessValidationError, "session_search_input"):
            find_node_center(source, "session_search_input")

    def test_non_id_suffix_is_not_accepted(self):
        source = (
            '<hierarchy><node class="android.widget.EditText" '
            'resource-id="some/prefix/model_search_input" text="qwen" '
            'bounds="[0,0][100,100]" /></hierarchy>'
        )

        with self.assertRaisesRegex(HarnessValidationError, "model_search_input"):
            find_node_center(source, "model_search_input")

    def test_scenario_final_state_requires_exact_destination_and_value(self):
        source = self.fixture("ui-selector-similar.xml")

        assert_scenario_final_state(source, "model-sheet")
        with self.assertRaisesRegex(HarnessValidationError, "expected exact text"):
            assert_scenario_final_state(source.replace('text="qwen"', 'text="qwen2"'), "model-sheet")

    def test_exact_foreground_accepts_pocketgpt(self):
        observed = assert_foreground(
            self.fixture("window-focus-pocketgpt.txt"),
            "com.pocketagent.android",
        )

        self.assertEqual(observed["current_focus_package"], "com.pocketagent.android")

    def test_launcher_background_theft_is_rejected(self):
        with self.assertRaisesRegex(HarnessValidationError, "com.sec.android.app.launcher"):
            assert_foreground(
                self.fixture("window-focus-launcher.txt"),
                "com.pocketagent.android",
            )

    def test_similar_package_name_is_not_foreground(self):
        source = (
            "mCurrentFocus=Window{a u0 "
            "com.pocketagent.android.fake/com.pocketagent.android.fake.MainActivity}\n"
        )

        with self.assertRaisesRegex(HarnessValidationError, "android.fake"):
            assert_foreground(source, "com.pocketagent.android")

    def test_package_identity_and_debuggable_must_remain_exactly_stable(self):
        before = self.package_dump(last_update="2026-07-11 10:00:00")
        assert_package_identity_unchanged(
            before,
            "/data/app/pocket/base.apk\n",
            before,
            "/data/app/pocket/base.apk\n",
        )

        after = self.package_dump(last_update="2026-07-11 10:01:00")
        with self.assertRaisesRegex(HarnessValidationError, "package identity changed"):
            assert_package_identity_unchanged(
                before,
                "/data/app/pocket/base.apk\n",
                after,
                "/data/app/pocket/base.apk\n",
            )

    def test_package_debuggable_drift_is_rejected(self):
        before = self.package_dump(last_update="2026-07-11 10:00:00")
        after = before.replace(
            "ALLOW_CLEAR_USER_DATA ]",
            "ALLOW_CLEAR_USER_DATA DEBUGGABLE ]",
        )

        with self.assertRaisesRegex(HarnessValidationError, "package identity changed"):
            assert_package_identity_unchanged(
                before,
                "/data/app/pocket/base.apk\n",
                after,
                "/data/app/pocket/base.apk\n",
            )

    def test_run_id_has_microseconds_and_pid(self):
        identifier = run_id(4321)

        self.assertRegex(identifier, re.compile(r"^\d{8}T\d{6}\.\d{6}Z-pid4321$"))

    @staticmethod
    def package_dump(*, last_update: str) -> str:
        return (
            "  versionCode=12 minSdk=26 targetSdk=35\n"
            "  versionName=0.2.0\n"
            f"  lastUpdateTime={last_update}\n"
            "  pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]\n"
        )


if __name__ == "__main__":
    unittest.main()
