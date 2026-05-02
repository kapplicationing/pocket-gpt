from __future__ import annotations

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

    def test_cloud_send_after_ready_flow_is_part_of_default_cloud_smoke_tag(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro-cloud/scenario-send-after-ready-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn("- cloud-send", text)
        self.assertIn("- cloud-smoke", text)

    def test_cloud_open_model_library_uses_text_contract_and_top_bar_entry(self) -> None:
        helper_paths = (
            REPO_ROOT / "tests/maestro-cloud/shared/open-model-library.yaml",
            REPO_ROOT / "tests/maestro/shared/open-model-library.yaml",
        )
        for helper_path in helper_paths:
            with self.subTest(helper=helper_path.relative_to(REPO_ROOT).as_posix()):
                text = helper_path.read_text(encoding="utf-8")
                self.assertIn('visible: "Model library"', text)
                self.assertIn('notVisible: "Model library"', text)
                self.assertIn('visible: "Get ready"', text)
                self.assertIn('id: "open_model_library"', text)
                self.assertIn('visible: "More models…"', text)
                self.assertNotIn('id: "unified_model_sheet"', text)
                self.assertNotIn('id: "advanced_sheet_button"', text)

    def test_cloud_bootstrap_runtime_ready_uses_simple_first_setup_path(self) -> None:
        helper_path = REPO_ROOT / "tests/maestro-cloud/shared/bootstrap-cloud-startup.yaml"
        text = helper_path.read_text(encoding="utf-8")
        self.assertIn('id: "provisioning_bootstrap_loading"', text)
        self.assertIn('visible: "Next"', text)
        self.assertIn('visible: "Get ready"', text)
        self.assertIn('visible: "Model library"', text)
        self.assertIn('id: "session_drawer_button"', text)
        self.assertIn("runFlow: ensure-runtime-loaded.yaml", text)
        self.assertNotIn('visible: "Setup"', text)
        self.assertNotIn('id: "send_button"', text)
        self.assertNotIn('visible: "Pocket GPT"', text)

    def test_ensure_runtime_loaded_uses_downloaded_models_load_path(self) -> None:
        helper_expectations = (
            (
                REPO_ROOT / "tests/maestro/shared/ensure-runtime-loaded.yaml",
                'text: "Downloaded models"',
                None,
            ),
            (
                REPO_ROOT / "tests/maestro-cloud/shared/ensure-runtime-loaded.yaml",
                "recover-runtime-from-model-library.yaml",
                'visible: "Setup"',
            ),
        )
        for helper_path, recovery_marker, explicit_setup_marker in helper_expectations:
            with self.subTest(helper=helper_path.relative_to(REPO_ROOT).as_posix()):
                text = helper_path.read_text(encoding="utf-8")
                commands = [
                    line.strip()
                    for line in helper_path.read_text(encoding="utf-8").splitlines()
                    if line.strip().startswith("- ")
                ]
                self.assertIn(recovery_marker, text)
                self.assertIn('visible: "Load"', text)
                if explicit_setup_marker is not None:
                    self.assertIn(explicit_setup_marker, text)
                self.assertIn('notVisible: "Setup"', text)
                self.assertIn('notVisible: "Retry"', text)
                self.assertIn('notVisible: "Loading…"', text)
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

    def test_cloud_send_after_ready_waits_for_enabled_send_button(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro-cloud/scenario-send-after-ready-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn('notVisible: "Loading…"', text)
        self.assertIn('notVisible: "Retry"', text)
        self.assertIn('id: "send_button"', text)
        self.assertIn('enabled: true', text)
        self.assertIn('id: "message_bubble_assistant_complete"', text)
        self.assertIn('visible: "Loading…"', text)

    def test_download_settings_smoke_uses_unified_model_library_path(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro/scenario-download-settings-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn('- runFlow: shared/open-model-library.yaml', text)
        self.assertIn('assertVisible: "Model library"', text)
        self.assertIn('assertVisible: "Search models"', text)
        self.assertIn('assertVisible: "Downloaded models"', text)
        self.assertIn('assertVisible: "Refresh"', text)
        self.assertIn('text: "Close"', text)
        self.assertIn('assertVisible: "Close"', text)
        self.assertNotIn('id: "advanced_sheet_button"', text)
        self.assertNotIn('visible: "Advanced controls"', text)


if __name__ == "__main__":
    unittest.main()
