import hashlib
import json
import re
import sys
import unittest
from html import escape
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(SCRIPT_DIR))

from android_perf_harness import (  # noqa: E402
    HarnessValidationError,
    assert_appended_probe,
    assert_application_id_metadata,
    assert_broadcast_result,
    assert_compile_result,
    assert_foreground,
    assert_package_identity_unchanged,
    assert_profile_compiled,
    assert_restored_text,
    assert_scenario_final_state,
    find_node_center,
    node_text_length,
    redacted_selector_inventory,
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

    def test_append_and_restore_compare_arbitrary_original_text_without_roundtrip(self):
        original = 'System λ "quoted"\nمرحبا & goodbye'
        probe = "SmoothSettingsProbe"
        before = self.hierarchy_with_text(original)
        appended = self.hierarchy_with_text(original + probe)
        restored = self.hierarchy_with_text(original)

        append_proof = assert_appended_probe(
            before,
            appended,
            "completion_system_prompt_input",
            probe,
        )
        restore_proof = assert_restored_text(
            before,
            restored,
            "completion_system_prompt_input",
        )
        serialized_proof = json.dumps(
            {"append": append_proof, "restore": restore_proof},
            ensure_ascii=False,
        )
        self.assertNotIn(original, serialized_proof)
        self.assertNotIn(hashlib.sha256(original.encode("utf-8")).hexdigest(), serialized_proof)
        self.assertNotIn("sha256", serialized_proof)
        self.assertTrue(append_proof["exact_appended"])
        self.assertTrue(restore_proof["exact_restored"])

    def test_append_assertion_rejects_missing_or_extra_probe_text(self):
        original = 'Keep "this"\nexactly'
        before = self.hierarchy_with_text(original)

        with self.assertRaisesRegex(HarnessValidationError, "original text plus exact probe"):
            assert_appended_probe(
                before,
                self.hierarchy_with_text(original),
                "completion_system_prompt_input",
                "SmoothSettingsProbe",
            )
        with self.assertRaisesRegex(HarnessValidationError, "original text plus exact probe"):
            assert_appended_probe(
                before,
                self.hierarchy_with_text(original + "SmoothSettingsProbe!"),
                "completion_system_prompt_input",
                "SmoothSettingsProbe",
            )

    def test_restore_assertion_rejects_any_original_text_drift(self):
        before = self.hierarchy_with_text("α\nβ")

        with self.assertRaisesRegex(HarnessValidationError, "restored text does not exactly match"):
            assert_restored_text(
                before,
                self.hierarchy_with_text("α\nβ "),
                "completion_system_prompt_input",
            )

    def test_redacted_selector_inventory_omits_text_and_content_description(self):
        sensitive = 'secret prompt "and session"'
        source = (
            '<hierarchy><node resource-id="completion_system_prompt_input" '
            'class="android.widget.EditText" bounds="[1,2][3,4]" '
            f'text="{escape(sensitive, quote=True)}" '
            'content-desc="private description" /></hierarchy>'
        )

        inventory = redacted_selector_inventory(source)
        serialized = json.dumps(inventory)

        self.assertNotIn(sensitive, serialized)
        self.assertNotIn("private description", serialized)
        self.assertEqual(
            inventory["selectors"],
            [
                {
                    "resource_id": "completion_system_prompt_input",
                    "class": "android.widget.EditText",
                    "bounds": "[1,2][3,4]",
                }
            ],
        )

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

    def test_profile_activation_proof_requires_exact_receiver_and_compile_results(self):
        self.assertEqual(
            assert_broadcast_result("Broadcast completed: result=10\n", expected=10),
            10,
        )
        self.assertEqual(assert_compile_result("Success\n"), "Success")
        self.assertEqual(
            assert_compile_result("Compilation PERFORMED successfully\n"),
            "PERFORMED",
        )

        with self.assertRaisesRegex(HarnessValidationError, "expected broadcast result 1"):
            assert_broadcast_result("Broadcast completed: result=0\n", expected=1)
        with self.assertRaisesRegex(HarnessValidationError, "compile output"):
            assert_compile_result("Failure: package could not be compiled\n")

    def test_benchmark_application_id_must_be_isolated_before_install(self):
        metadata = json.dumps({"applicationId": "com.pocketagent.android.benchmark"})
        self.assertEqual(
            assert_application_id_metadata(metadata, "com.pocketagent.android.benchmark"),
            "com.pocketagent.android.benchmark",
        )
        with self.assertRaisesRegex(HarnessValidationError, "expected benchmark applicationId"):
            assert_application_id_metadata(
                json.dumps({"applicationId": "com.pocketagent.android"}),
                "com.pocketagent.android.benchmark",
            )

    def test_profile_compilation_parser_targets_base_primary_abi_for_exact_package(self):
        source = self.dexopt_dump(
            status="speed-profile",
            reason="cmdline",
            secondary_status="verify",
        )

        state = assert_profile_compiled(source, "com.pocketagent.android.benchmark")

        self.assertEqual(state.apk_path, "/data/app/pocket/base.apk")
        self.assertEqual(state.abi, "arm64")
        self.assertEqual(state.status, "speed-profile")
        self.assertEqual(state.reason, "cmdline")

    def test_profile_compilation_parser_rejects_unoptimized_wrong_or_ambiguous_primary_state(self):
        with self.assertRaisesRegex(HarnessValidationError, "expected status 'speed-profile'"):
            assert_profile_compiled(
                self.dexopt_dump(status="verify", reason="install"),
                "com.pocketagent.android.benchmark",
            )
        with self.assertRaisesRegex(HarnessValidationError, "package.*was not found"):
            assert_profile_compiled(
                self.dexopt_dump(status="speed-profile", reason="cmdline"),
                "com.pocketagent.android",
            )
        ambiguous = self.dexopt_dump(status="speed-profile", reason="cmdline").replace(
            "  primaryCpuAbi=arm64-v8a\n",
            "",
        ) + (
            "      x86_64: [status=speed-profile] [reason=cmdline]\n"
        )
        with self.assertRaisesRegex(HarnessValidationError, "exactly one base.apk primary ABI"):
            assert_profile_compiled(ambiguous, "com.pocketagent.android.benchmark")

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

    @staticmethod
    def hierarchy_with_text(text: str) -> str:
        encoded = escape(text, quote=True).replace("\n", "&#10;")
        return (
            '<hierarchy><node resource-id="completion_system_prompt_input" '
            f'text="{encoded}" bounds="[0,0][100,100]" /></hierarchy>'
        )

    @staticmethod
    def dexopt_dump(
        *,
        status: str,
        reason: str,
        secondary_status: str = "speed-profile",
    ) -> str:
        return (
            "  primaryCpuAbi=arm64-v8a\n"
            "Dexopt state:\n"
            "  [com.example.other]\n"
            "    path: /data/app/other/base.apk\n"
            "      arm64: [status=speed] [reason=cmdline]\n"
            "  [com.pocketagent.android.benchmark]\n"
            "    path: /data/app/pocket/split_config.arm64_v8a.apk\n"
            f"      arm64: [status={secondary_status}] [reason=cmdline]\n"
            "    path: /data/app/pocket/base.apk\n"
            f"      arm64: [status={status}] [reason={reason}]\n"
        )


if __name__ == "__main__":
    unittest.main()
