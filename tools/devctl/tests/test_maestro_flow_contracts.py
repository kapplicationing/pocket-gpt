from __future__ import annotations

import re
import unittest
from pathlib import Path

from tools.devctl.subprocess_utils import REPO_ROOT


class MaestroFlowContractsTest(unittest.TestCase):
    def test_nested_shared_flows_do_not_prefix_local_references_with_shared(self) -> None:
        shared_roots = (
            REPO_ROOT / "tests/maestro/shared",
            REPO_ROOT / "tests/maestro-cloud/shared",
        )
        offenders: list[str] = []
        for shared_root in shared_roots:
            for flow_path in sorted(shared_root.glob("*.yaml")):
                for line_number, line in enumerate(flow_path.read_text(encoding="utf-8").splitlines(), start=1):
                    stripped = line.strip()
                    if stripped.startswith("- runFlow: shared/") or stripped.startswith("- runScript: shared/"):
                        offenders.append(f"{flow_path.relative_to(REPO_ROOT)}:{line_number}: {stripped}")
        self.assertEqual([], offenders, "Nested Maestro shared flows must use local relative paths.")

    def test_bootstrap_to_ready_does_not_start_with_hide_keyboard(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/shared/bootstrap-to-ready.yaml"
        commands = [
            line.strip()
            for line in flow_path.read_text(encoding="utf-8").splitlines()
            if line.strip().startswith("- ")
        ]
        self.assertNotEqual(
            "- hideKeyboard",
            commands[0] if commands else None,
            "bootstrap-to-ready must not start with hideKeyboard because Maestro can resolve that to a back press.",
        )

    def test_cloud_send_after_ready_flow_is_not_part_of_default_cloud_smoke_tag(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro-cloud/scenario-send-after-ready-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn("- cloud-send", text)
        self.assertNotIn("- cloud-smoke", text)

    def test_cloud_open_model_library_uses_text_contract_and_top_bar_entry(self) -> None:
        helper_paths = (
            REPO_ROOT / "tests/maestro-cloud/shared/open-model-library.yaml",
            REPO_ROOT / "tests/maestro/shared/open-model-library.yaml",
        )
        for helper_path in helper_paths:
            with self.subTest(helper=helper_path.relative_to(REPO_ROOT).as_posix()):
                text = helper_path.read_text(encoding="utf-8")
                local_text = (REPO_ROOT / "tests/maestro/shared/open-model-library.yaml").read_text(
                    encoding="utf-8",
                )
                cloud_text = (REPO_ROOT / "tests/maestro-cloud/shared/open-model-library.yaml").read_text(
                    encoding="utf-8",
                )
                self.assertEqual(local_text, cloud_text)
                self.assertIn('visible: "Model library"', text)
                self.assertNotIn('text: "Next"', text)
                self.assertNotIn('text: "Get started"', text)
                self.assertNotIn('id: "onboarding_next"', text)
                self.assertNotIn('id: "onboarding_get_started"', text)
                self.assertNotIn('visible: "Setup"', text)
                self.assertIn('visible: "Get ready"', text)
                self.assertIn('id: "open_model_library"', text)
                self.assertIn('id: "composer_input"', text)
                self.assertGreaterEqual(text.count('visible: "More models…"'), 2)
                self.assertGreaterEqual(text.count('visible: "Open model library"'), 2)
                self.assertNotIn('id: "unified_model_sheet"', text)
                self.assertNotIn('id: "advanced_sheet_button"', text)

    def test_cloud_bootstrap_runtime_ready_uses_simple_first_setup_path(self) -> None:
        helper_path = REPO_ROOT / "tests/maestro-cloud/shared/bootstrap-cloud-startup.yaml"
        text = helper_path.read_text(encoding="utf-8")
        self.assertIn('id: "provisioning_bootstrap_loading"', text)
        self.assertIn("runFlow: bootstrap-clean-start.yaml", text)
        self.assertIn("runFlow: complete-first-run-onboarding.yaml", text)
        self.assertIn("runFlow: dismiss-system-overlays.yaml", text)
        self.assertIn('visible:\n      id: "session_drawer_button"', text)
        self.assertNotIn("runFlow: settle-top-bar-shell.yaml", text)
        self.assertNotIn('visible: "Pocket GPT"', text)

    def test_ensure_runtime_loaded_uses_downloaded_models_load_path(self) -> None:
        helper_expectations = (
            (
                REPO_ROOT / "tests/maestro/shared/ensure-runtime-loaded.yaml",
                ("recover-runtime-from-model-library.yaml",),
                'visible: "Setup"',
            ),
            (
                REPO_ROOT / "tests/maestro-cloud/shared/ensure-runtime-loaded.yaml",
                ("recover-runtime-from-model-library.yaml", "retry-runtime-bootstrap.yaml"),
                'visible: "Setup"',
            ),
        )
        for helper_path, recovery_markers, explicit_setup_marker in helper_expectations:
            with self.subTest(helper=helper_path.relative_to(REPO_ROOT).as_posix()):
                text = helper_path.read_text(encoding="utf-8")
                commands = [
                    line.strip()
                    for line in helper_path.read_text(encoding="utf-8").splitlines()
                    if line.strip().startswith("- ")
                ]
                recovery_text = helper_path.parent.joinpath("recover-runtime-from-model-library.yaml").read_text(encoding="utf-8")
                retry_helper_path = helper_path.parent / "retry-runtime-bootstrap.yaml"
                helper_chain_text = text + "\n" + recovery_text
                if retry_helper_path.exists():
                    helper_chain_text += "\n" + retry_helper_path.read_text(encoding="utf-8")
                launch_default_helper_path = helper_path.parent / "bootstrap-launch-default-model.yaml"
                if launch_default_helper_path.exists():
                    helper_chain_text += "\n" + launch_default_helper_path.read_text(encoding="utf-8")
                self.assertTrue(
                    launch_default_helper_path.exists(),
                    f"{helper_path.relative_to(REPO_ROOT)} must keep the launch-default bootstrap helper.",
                )
                self.assertTrue(
                    any(marker in helper_chain_text for marker in recovery_markers),
                    f"{helper_path.relative_to(REPO_ROOT)} must keep a runtime recovery path.",
                )
                self.assertIn('inputText: "qwen3-0.6b-q4_k_m"', helper_chain_text)
                self.assertIn('id: "model_library_model_qwen3-0.6b-q4_k_m_q4_k_m"', helper_chain_text)
                self.assertIn('id: "model_library_download_qwen3-0.6b-q4_k_m_q4_k_m"', helper_chain_text)
                self.assertIn(
                    'scrollUntilVisible:\n    centerElement: true\n    element:\n      id: "model_library_model_qwen3-0.6b-q4_k_m_q4_k_m"',
                    helper_chain_text,
                )
                self.assertLess(
                    helper_chain_text.index('id: "model_library_model_qwen3-0.6b-q4_k_m_q4_k_m"'),
                    helper_chain_text.index('id: "model_library_download_qwen3-0.6b-q4_k_m_q4_k_m"'),
                    "launch-default helper must scroll to the stable model row before evaluating state-specific actions.",
                )
                self.assertIn('id: "model_library_set_active_qwen3-0.6b-q4_k_m_q4_k_m"', helper_chain_text)
                self.assertIn('id: "model_library_load_qwen3-0.6b-q4_k_m_q4_k_m"', helper_chain_text)
                self.assertRegex(
                    recovery_text,
                    re.compile(
                        r'visible:\s*\n\s*id: "chat_gate_inline_card"\s*\n\s*commands:\s*\n\s*- runFlow: bootstrap-launch-default-model\.yaml',
                    ),
                )
                self.assertNotRegex(
                    recovery_text,
                    re.compile(
                        r'visible:\s*\n\s*id: "composer_input"\s*\n\s*commands:\s*\n\s*- runFlow: bootstrap-launch-default-model\.yaml',
                    ),
                    "runtime recovery must not re-bootstrap from an already-open chat composer.",
                )
                self.assertLess(
                    recovery_text.index('visible: "Setup"'),
                    recovery_text.index('visible: "Unloaded"'),
                    "setup-gated recovery must run before opening Model Library from the Unloaded status.",
                )
                self.assertLess(
                    recovery_text.index('id: "chat_gate_inline_card"'),
                    recovery_text.index('visible: "Unloaded"'),
                    "chat-gated recovery must run before opening Model Library from the Unloaded status.",
                )
                self.assertIn(
                    'id: "unified_model_sheet"\n    commands:\n      - runFlow: close-model-library-if-open.yaml\n      - runFlow: bootstrap-launch-default-model.yaml',
                    recovery_text,
                )
                self.assertLess(
                    recovery_text.index('id: "unified_model_sheet"'),
                    recovery_text.index('visible: "Load"'),
                    "sheet reset must run before generic row text actions.",
                )
                if explicit_setup_marker is not None:
                    self.assertIn(explicit_setup_marker, helper_chain_text)
                if "tests/maestro-cloud/shared" in helper_path.as_posix():
                    self.assertIn('visible: "Refresh"', helper_chain_text)
                    self.assertIn("open-model-library.yaml", helper_chain_text)
                    self.assertIn('visible: "Get ready"', helper_chain_text)
                self.assertIn('notVisible: "Setup"', text)
                self.assertIn('notVisible: "Retry"', text)
                self.assertIn('notVisible: "Refresh"', text)
                self.assertIn('notVisible: "Loading…"', text)
                self.assertIn('notVisible: "Loading..."', text)
                self.assertIn('notVisible: "Cancel"', text)
                self.assertIn('notVisible: "Cancelling"', text)
                self.assertIn('notVisible: "Cancelling..."', text)
                self.assertIn('notVisible: "Sending"', text)
                self.assertIn('notVisible: "Update"', text)
                self.assertIn('visible:\n      id: "composer_input"', text)
                self.assertIn('visible:\n      id: "send_button"', text)
                self.assertNotIn('visible: "Send"', text)
                self.assertLess(
                    text.index('visible:\n      id: "send_button"'),
                    text.rindex('assertNotVisible: "Setup"'),
                    "runtime readiness must re-check blocked labels after the id-scoped send button appears.",
                )
                self.assertLess(
                    text.index('visible:\n      id: "send_button"'),
                    text.rindex('assertNotVisible: "Refresh"'),
                    "runtime readiness must re-check blocked labels after the id-scoped send button appears.",
                )
                self.assertIn('visible: "Large download on metered network"', helper_chain_text)
                self.assertIn('tapOn: "Continue download"', helper_chain_text)
                self.assertNotIn('notVisible: "No downloaded models yet"', helper_chain_text)
                if "tests/maestro-cloud/shared" in helper_path.as_posix():
                    self.assertIn('id: "refresh_button"', helper_chain_text)
                self.assertNotIn('text: "Active model"', text)
                self.assertNotIn('notVisible: "Unloaded"', text)
                self.assertNotEqual(
                    "- hideKeyboard",
                    commands[0] if commands else None,
                    "ensure-runtime-loaded must not start with hideKeyboard because Maestro can resolve that to a back press.",
                )

    def test_cloud_model_management_smoke_uses_unified_library_contract(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro-cloud/scenario-model-management-split-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn('assertVisible: "Model library"', text)
        self.assertIn('assertVisible: "Search models"', text)
        self.assertIn('assertVisible: "Downloaded models"', text)
        self.assertIn('assertVisible: "Refresh"', text)
        self.assertIn('text: "Close"', text)
        self.assertIn('assertVisible: "Close"', text)
        self.assertNotIn('assertVisible: "No downloaded models yet"', text)
        self.assertNotIn('id: "refresh_button"', text)
        self.assertLess(
            text.index('assertVisible: "Refresh"'),
            text.index('text: "Available models"'),
            "Refresh lives at the top of the model sheet and must be asserted before scrolling to lower sections.",
        )

    def test_cloud_send_after_ready_waits_for_enabled_send_button(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro-cloud/scenario-send-after-ready-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn('assertVisible:\n    id: "composer_input"', text)
        self.assertIn('id: "composer_input"', text)
        self.assertIn('notVisible: "Loading…"', text)
        self.assertIn('notVisible: "Retry"', text)
        self.assertIn('id: "send_button"', text)
        self.assertIn('enabled: true', text)
        self.assertIn('id: "message_bubble_assistant_complete"', text)
        self.assertIn('when:\n      visible: "Loading…"', text)
        self.assertNotIn('visible: "Message"', text)
        self.assertIn('visible: "Send"', text)

    def test_cloud_session_drawer_smoke_requires_ready_shell_id_contract(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro-cloud/scenario-session-drawer-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn('assertVisible:\n    id: "session_drawer_button"', text)
        self.assertIn('id: "session_drawer_button"', text)
        self.assertIn('id: "create_session_button"', text)
        self.assertIn('assertVisible: "New chat"', text)

    def test_download_settings_smoke_uses_unified_model_library_path(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/scenario-download-settings-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn("- runFlow: shared/bootstrap-to-ready.yaml", text)
        self.assertIn('assertVisible:\n    id: "composer_input"', text)
        self.assertIn('- runFlow: shared/open-model-library.yaml', text)
        self.assertIn('assertVisible: "Model library"', text)
        self.assertIn('assertVisible: "Search models"', text)
        self.assertIn('assertVisible: "Downloaded models"', text)
        self.assertIn('assertVisible: "Refresh"', text)
        self.assertIn('text: "Close"', text)
        self.assertIn('assertVisible: "Close"', text)
        self.assertNotIn('id: "refresh_button"', text)
        self.assertNotIn('id: "advanced_sheet_button"', text)
        self.assertNotIn('visible: "Advanced controls"', text)
        self.assertNotIn('visible: "Welcome"', text)
        self.assertNotIn('visible: "Privacy first"', text)
        self.assertNotIn('visible: "Get started"', text)

    def test_local_onboarding_flow_does_not_require_onboarding_to_be_present_before_bootstrap(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/scenario-onboarding.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn("- runFlow: shared/bootstrap-clean-start.yaml", text)
        self.assertIn("- runFlow: shared/complete-first-run-onboarding.yaml", text)
        self.assertIn("- runFlow: shared/assert-post-onboarding-chat-surface.yaml", text)
        self.assertLess(
            text.index("shared/bootstrap-clean-start.yaml"),
            text.index("shared/complete-first-run-onboarding.yaml"),
        )
        self.assertNotIn('assertVisible:\n    id: "composer_input"', text)

    def test_post_onboarding_chat_surface_helper_accepts_clean_device_pre_runtime_state(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/shared/assert-post-onboarding-chat-surface.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertNotIn("launchApp:", text)
        self.assertNotIn('id: "onboarding_next"', text)
        self.assertNotIn('id: "onboarding_get_started"', text)
        self.assertIn('id: "permission_allow_button"', text)
        self.assertIn('id: "provisioning_bootstrap_loading"', text)
        self.assertIn('visible: "Preparing PocketAgent"', text)
        self.assertNotIn("hideKeyboard", text)
        self.assertIn('id: "unified_model_sheet"', text)
        self.assertIn("runFlow: close-model-library-if-open.yaml", text)
        self.assertNotIn('visible: "Model library"', text)
        self.assertNotIn("- back", text)
        self.assertIn('id: "session_drawer_button"', text)
        self.assertIn('id: "send_button"', text)
        self.assertNotIn('id: "composer_input"', text)
        self.assertLess(
            text.index('id: "permission_allow_button"'),
            text.index('visible:\n      id: "session_drawer_button"'),
        )

    def test_bootstrap_helpers_keep_local_and_cloud_onboarding_contracts_in_sync(self) -> None:
        local_path = REPO_ROOT / "tests/maestro/shared/bootstrap-clean-start.yaml"
        cloud_path = REPO_ROOT / "tests/maestro-cloud/shared/bootstrap-cloud-startup.yaml"
        local_text = local_path.read_text(encoding="utf-8")
        cloud_text = cloud_path.read_text(encoding="utf-8")
        shared_markers = (
            "runFlow: dismiss-system-overlays.yaml",
            'visible:\n        id: "permission_allow_button"',
            'tapOn:\n          id: "permission_allow_button"',
            'visible: "Allow PocketAgent to send you notifications?"',
            'notVisible:\n        id: "permission_allow_button"',
            '- tapOn: "Allow"',
        )
        for marker in shared_markers:
            with self.subTest(marker=marker):
                self.assertIn(marker, local_text)
        self.assertIn("runFlow: bootstrap-clean-start.yaml", cloud_text)
        self.assertIn("runFlow: complete-first-run-onboarding.yaml", cloud_text)
        self.assertIn("runFlow: bootstrap-clean-start.yaml", REPO_ROOT.joinpath("tests/maestro/shared/bootstrap-to-ready.yaml").read_text(encoding="utf-8"))
        self.assertNotIn("hideKeyboard", cloud_text)
        self.assertEqual(
            local_text,
            REPO_ROOT.joinpath("tests/maestro-cloud/shared/bootstrap-clean-start.yaml").read_text(encoding="utf-8"),
        )

    def test_local_bootstrap_to_ready_normalizes_startup_and_waits_for_chat_shell(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/shared/bootstrap-to-ready.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn("runFlow: bootstrap-clean-start.yaml", text)
        self.assertIn("runFlow: complete-first-run-onboarding.yaml", text)
        self.assertIn("runFlow: settle-ready-shell.yaml", text)
        bootstrap_clean_text = REPO_ROOT.joinpath("tests/maestro/shared/bootstrap-clean-start.yaml").read_text(encoding="utf-8")
        self.assertGreaterEqual(bootstrap_clean_text.count("runFlow: dismiss-system-overlays.yaml"), 3)
        self.assertNotIn("hideKeyboard", bootstrap_clean_text)
        self.assertNotIn('visible: "Next"', bootstrap_clean_text)
        self.assertNotIn('visible: "Skip"', bootstrap_clean_text)
        self.assertNotIn('visible: "Get started"', bootstrap_clean_text)
        self.assertNotIn('id: "onboarding_next"', bootstrap_clean_text)
        self.assertNotIn('id: "onboarding_skip"', bootstrap_clean_text)
        self.assertNotIn('id: "onboarding_get_started"', bootstrap_clean_text)
        self.assertEqual(
            bootstrap_clean_text,
            REPO_ROOT.joinpath("tests/maestro-cloud/shared/bootstrap-clean-start.yaml").read_text(encoding="utf-8"),
        )

    def test_local_bootstrap_runtime_ready_no_launch_reuses_current_shell_helper(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/shared/bootstrap-runtime-ready-no-launch.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn("runFlow: settle-current-ready-shell.yaml", text)
        self.assertIn('id: "composer_input"', text)
        self.assertIn('assertNotVisible: "UI-STARTUP-001"', text)

    def test_local_settle_ready_shell_reuses_current_shell_helper_without_relaunch(self) -> None:
        helper_path = REPO_ROOT / "tests/maestro/shared/settle-ready-shell.yaml"
        text = helper_path.read_text(encoding="utf-8")
        self.assertIn("runFlow: settle-current-ready-shell.yaml", text)
        self.assertNotIn("launchApp:", text)

    def test_cloud_settle_ready_shell_handles_late_permission_and_returns_to_chat_shell(self) -> None:
        helper_path = REPO_ROOT / "tests/maestro-cloud/shared/settle-ready-shell.yaml"
        text = helper_path.read_text(encoding="utf-8")
        self.assertIn("launchApp:", text)
        self.assertIn("clearState: false", text)
        self.assertIn('id: "provisioning_bootstrap_loading"', text)
        self.assertGreaterEqual(text.count("runFlow: dismiss-system-overlays.yaml"), 3)
        self.assertIn("runFlow: bootstrap-clean-start.yaml", text)
        self.assertIn("runFlow: complete-first-run-onboarding.yaml", text)
        self.assertIn('extendedWaitUntil:\n    visible:\n      id: "session_drawer_button"', text)
        self.assertIn("runFlow: ensure-runtime-loaded.yaml", text)
        self.assertIn('id: "permission_allow_button"', text)
        self.assertIn('visible: "Allow PocketAgent to send you notifications?"', text)
        self.assertIn('id: "unified_model_sheet"', text)
        self.assertIn("runFlow: close-model-library-if-open.yaml", text)
        self.assertNotIn('visible: "Model library"', text)
        self.assertNotIn('- back', text)
        self.assertIn('id: "composer_input"', text)

    def test_settle_current_ready_shell_handles_running_app_without_launch(self) -> None:
        helper_path = REPO_ROOT / "tests/maestro/shared/settle-current-ready-shell.yaml"
        text = helper_path.read_text(encoding="utf-8")
        self.assertNotIn("launchApp:", text)
        self.assertIn('visible: "Preparing PocketAgent"', text)
        self.assertIn('id: "provisioning_bootstrap_loading"', text)
        self.assertGreaterEqual(text.count("runFlow: dismiss-system-overlays.yaml"), 3)
        self.assertIn("runFlow: bootstrap-clean-start.yaml", text)
        self.assertIn("runFlow: complete-first-run-onboarding.yaml", text)
        self.assertIn('extendedWaitUntil:\n    visible:\n      id: "session_drawer_button"', text)
        self.assertIn("runFlow: ensure-runtime-loaded.yaml", text)
        self.assertIn('id: "unified_model_sheet"', text)
        self.assertIn("runFlow: close-model-library-if-open.yaml", text)
        self.assertNotIn('visible: "Model library"', text)
        self.assertIn('id: "composer_input"', text)

    def test_first_run_and_benchmark_flows_reuse_shared_bootstrap_contracts(self) -> None:
        local_flow_expectations = (
            REPO_ROOT / "tests/maestro/scenario-first-run-download-chat.yaml",
            REPO_ROOT / "tests/maestro/scenario-first-run-gpu-chat.yaml",
            REPO_ROOT / "tests/maestro/shared/scenario-gpu-qualify-by-model.template.yaml",
        )
        for flow_path in local_flow_expectations:
            with self.subTest(flow=flow_path.relative_to(REPO_ROOT).as_posix()):
                text = flow_path.read_text(encoding="utf-8")
                expected_bootstrap_ref = (
                    "runFlow: bootstrap-clean-start.yaml"
                    if flow_path.parent.name == "shared"
                    else "runFlow: shared/bootstrap-clean-start.yaml"
                )
                self.assertIn(expected_bootstrap_ref, text)
                expected_onboarding_ref = (
                    "runFlow: complete-first-run-onboarding.yaml"
                    if flow_path.parent.name == "shared"
                    else "runFlow: shared/complete-first-run-onboarding.yaml"
                )
                self.assertIn(expected_onboarding_ref, text)
                self.assertIn("assert-post-onboarding-chat-surface.yaml", text)
                if flow_path.name == "scenario-first-run-download-chat.yaml":
                    self.assertIn("runFlow: shared/ensure-runtime-loaded.yaml", text)
                    self.assertNotIn("bootstrap-launch-default-model.yaml", text)
                elif flow_path.parent.name != "shared":
                    self.assertIn("bootstrap-launch-default-model.yaml", text)
                if flow_path.name == "scenario-first-run-download-chat.yaml":
                    self.assertIn('visible: "Load last used"', text)
                    self.assertIn("runFlow: shared/wait-runtime-transition-idle.yaml", text)
                    self.assertNotIn(
                        'notVisible:\n            id: "chat_gate_inline_card"',
                        text,
                    )
                self.assertLess(
                    text.index(expected_bootstrap_ref),
                    text.index(expected_onboarding_ref),
                )
                self.assertNotIn('visible:\n        id: "onboarding_skip"', text)
                self.assertNotIn('visible: "Skip"', text)
                self.assertNotIn('visible: "Get started"', text)

        bootstrap_text = (REPO_ROOT / "tests/maestro/shared/bootstrap-launch-default-model.yaml").read_text(
            encoding="utf-8",
        )
        self.assertIn('id: "model_library_load_qwen3-0.6b-q4_k_m_q4_k_m"', bootstrap_text)
        self.assertGreaterEqual(bootstrap_text.count("enabled: true"), 5)
        self.assertNotIn('text: "Downloaded models"', bootstrap_text)
        self.assertIn('id: "chat_gate_inline_card"', bootstrap_text)
        self.assertIn("runFlow: wait-runtime-transition-idle.yaml", bootstrap_text)
        self.assertIn('inputText: "qwen3-0.6b-q4_k_m"\n- hideKeyboard', bootstrap_text)
        self.assertNotIn("\n      - back", bootstrap_text)
        self.assertIn("runFlow: close-model-library-if-open.yaml", bootstrap_text)
        local_close_helper_text = (
            REPO_ROOT / "tests/maestro/shared/close-model-library-if-open.yaml"
        ).read_text(encoding="utf-8")
        self.assertIn('id: "unified_model_sheet"', local_close_helper_text)
        self.assertIn('visible: "Close"', local_close_helper_text)
        self.assertIn("- back", local_close_helper_text)

        cloud_bootstrap_text = (
            REPO_ROOT / "tests/maestro-cloud/shared/bootstrap-launch-default-model.yaml"
        ).read_text(encoding="utf-8")
        self.assertIn('id: "model_library_load_qwen3-0.6b-q4_k_m_q4_k_m"', cloud_bootstrap_text)
        self.assertGreaterEqual(cloud_bootstrap_text.count("enabled: true"), 5)
        self.assertNotIn('text: "Downloaded models"', cloud_bootstrap_text)
        self.assertIn('id: "chat_gate_inline_card"', cloud_bootstrap_text)
        self.assertIn("runFlow: wait-runtime-transition-idle.yaml", cloud_bootstrap_text)
        self.assertNotIn('id: "refresh_button"', cloud_bootstrap_text)
        self.assertIn('inputText: "qwen3-0.6b-q4_k_m"\n- hideKeyboard', cloud_bootstrap_text)
        self.assertNotIn("\n      - back", cloud_bootstrap_text)
        self.assertIn("runFlow: close-model-library-if-open.yaml", cloud_bootstrap_text)
        cloud_close_helper_text = (
            REPO_ROOT / "tests/maestro-cloud/shared/close-model-library-if-open.yaml"
        ).read_text(encoding="utf-8")
        self.assertIn('id: "unified_model_sheet"', cloud_close_helper_text)
        self.assertIn('visible: "Close"', cloud_close_helper_text)
        self.assertIn("- back", cloud_close_helper_text)

        cloud_benchmark_path = REPO_ROOT / "tests/maestro-cloud/scenario-gpu-cpu-benchmark.yaml"
        cloud_text = cloud_benchmark_path.read_text(encoding="utf-8")
        self.assertIn("runFlow: shared/bootstrap-runtime-ready.yaml", cloud_text)
        self.assertNotIn('id: "onboarding_skip"', cloud_text)
        self.assertNotIn('id: "onboarding_get_started"', cloud_text)
        self.assertNotIn('text: "Skip"', cloud_text)
        self.assertNotIn('text: "Get started"', cloud_text)
        self.assertNotIn('visible: "Runtime: Ready"', cloud_text)

    def test_dismiss_system_overlays_helper_stays_shared_between_local_and_cloud(self) -> None:
        local_path = REPO_ROOT / "tests/maestro/shared/dismiss-system-overlays.yaml"
        cloud_path = REPO_ROOT / "tests/maestro-cloud/shared/dismiss-system-overlays.yaml"
        local_text = local_path.read_text(encoding="utf-8")
        self.assertIn('visible: "Pixel Launcher isn\'t responding"', local_text)
        self.assertIn('tapOn: "Wait"', local_text)
        self.assertIn('visible: "Try out your stylus"', local_text)
        self.assertIn('visible: "Write here"', local_text)
        self.assertIn('tapOn: "Cancel"', local_text)
        self.assertEqual(local_text, cloud_path.read_text(encoding="utf-8"))

    def test_lifecycle_ci_wrapper_recovers_known_non_app_system_overlays(self) -> None:
        script_text = (REPO_ROOT / "scripts/ci/run_lifecycle_e2e.sh").read_text(encoding="utf-8")
        self.assertIn("LIFECYCLE_E2E_SYSTEM_OVERLAY_RETRIES", script_text)
        self.assertIn("com.google.android.apps.nexuslauncher", script_text)
        self.assertIn("Pixel Launcher isn't responding", script_text)
        self.assertIn("android:id/aerr_wait", script_text)
        self.assertIn("stabilize_system_ui", script_text)
        self.assertIn("am force-stop", script_text)
        self.assertIn("non-app system overlay", script_text)
        self.assertNotIn(" rg ", script_text)
        self.assertNotIn("rg -", script_text)
        self.assertIn("launcher_overlay_detected=$?", script_text)
        self.assertIn("crash_detected=$?", script_text)
        self.assertIn("second_rc=$?", script_text)

    def test_settle_top_bar_shell_normalizes_ime_before_asserting_top_bar(self) -> None:
        helper_paths = (
            REPO_ROOT / "tests/maestro/shared/settle-top-bar-shell.yaml",
            REPO_ROOT / "tests/maestro-cloud/shared/settle-top-bar-shell.yaml",
        )
        for helper_path in helper_paths:
            with self.subTest(helper=helper_path.relative_to(REPO_ROOT).as_posix()):
                text = helper_path.read_text(encoding="utf-8")
                self.assertIn("runFlow: settle-ready-shell.yaml", text)
                self.assertIn("- hideKeyboard", text)
                self.assertIn('id: "session_drawer_button"', text)
                self.assertIn('id: "open_model_library"', text)

    def test_bootstrap_clean_start_is_the_authoritative_local_startup_contract(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/shared/bootstrap-clean-start.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn('visible:\n        id: "permission_allow_button"', text)
        self.assertIn('visible: "Allow PocketAgent to send you notifications?"', text)
        self.assertIn('notVisible:\n        id: "permission_allow_button"', text)
        self.assertNotIn('visible: "Next"', text)
        self.assertNotIn('visible: "Skip"', text)
        self.assertNotIn('visible: "Get started"', text)
        self.assertNotIn('id: "onboarding_next"', text)
        self.assertNotIn('id: "onboarding_skip"', text)
        self.assertNotIn('id: "onboarding_get_started"', text)
        self.assertNotIn('visible:\n        id: "refresh_button"', text)
        self.assertNotIn('visible: "Load last used"', text)
        self.assertNotIn('visible: "Setup"', text)
        self.assertNotIn('id: "send_button"', text)
        self.assertNotIn('runFlow: open-model-library.yaml', text)
        self.assertNotIn('text: "Download model"', text)
        self.assertNotIn('text: "Downloading…"', text)

    def test_first_run_onboarding_helper_uses_stable_ids_and_stays_in_sync(self) -> None:
        local_path = REPO_ROOT / "tests/maestro/shared/complete-first-run-onboarding.yaml"
        cloud_path = REPO_ROOT / "tests/maestro-cloud/shared/complete-first-run-onboarding.yaml"
        text = local_path.read_text(encoding="utf-8")
        self.assertEqual(text, cloud_path.read_text(encoding="utf-8"))
        self.assertIn('id: "provisioning_bootstrap_loading"', text)
        self.assertIn('visible: "Preparing PocketAgent"', text)
        self.assertIn('visible: "Checking runtime and model availability..."', text)
        self.assertNotIn('visible:\n      id: "composer_input"', text)
        self.assertNotIn('notVisible:\n        id: "composer_input"', text)
        self.assertIn('visible:\n        id: "onboarding_next"', text)
        self.assertGreaterEqual(text.count("runFlow: dismiss-system-overlays.yaml"), 4)
        self.assertEqual(6, text.count('id: "onboarding_next"'))
        self.assertEqual(2, text.count('tapOn:\n          id: "onboarding_next"'))
        self.assertEqual(4, text.count('id: "onboarding_get_started"'))
        self.assertIn('tapOn:\n          id: "onboarding_get_started"', text)
        self.assertIn('assertNotVisible:\n    id: "onboarding_next"', text)
        self.assertIn('assertNotVisible:\n    id: "onboarding_get_started"', text)
        self.assertIn("runFlow: dismiss-system-overlays.yaml", text)
        self.assertNotIn("hideKeyboard", text)

    def test_clean_state_flows_drive_onboarding_explicitly_before_ready_helpers(self) -> None:
        flow_paths = (
            REPO_ROOT / "tests/maestro/scenario-onboarding.yaml",
            REPO_ROOT / "tests/maestro/scenario-first-run-download-chat.yaml",
            REPO_ROOT / "tests/maestro/scenario-first-run-gpu-chat.yaml",
            REPO_ROOT / "tests/maestro/scenario-download-settings-smoke.yaml",
            REPO_ROOT / "tests/maestro/scenario-session-drawer-smoke.yaml",
            REPO_ROOT / "tests/maestro/scenario-hf-live-download-smoke.yaml",
        )
        for flow_path in flow_paths:
            with self.subTest(flow=flow_path.relative_to(REPO_ROOT).as_posix()):
                text = flow_path.read_text(encoding="utf-8")
                self.assertIn("clearState: true", text)
                self.assertIn("runFlow: shared/bootstrap-clean-start.yaml", text)
                self.assertIn("runFlow: shared/complete-first-run-onboarding.yaml", text)
                self.assertLess(
                    text.index("shared/bootstrap-clean-start.yaml"),
                    text.index("shared/complete-first-run-onboarding.yaml"),
                )


if __name__ == "__main__":
    unittest.main()
