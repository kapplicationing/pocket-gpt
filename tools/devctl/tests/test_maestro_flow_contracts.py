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

    def test_cloud_send_after_ready_flow_is_not_part_of_default_cloud_smoke_tag(self) -> None:
        flow_path = REPO_ROOT / "tests/maestro-cloud/scenario-send-after-ready-smoke.yaml"
        text = flow_path.read_text(encoding="utf-8")
        self.assertIn("- cloud-send", text)
        self.assertNotIn("- cloud-smoke", text)

    def test_cloud_open_model_library_uses_unified_sheet_and_top_bar_entry(self) -> None:
        helper_path = REPO_ROOT / "tests/maestro-cloud/shared/open-model-library.yaml"
        text = helper_path.read_text(encoding="utf-8")
        self.assertIn('id: "unified_model_sheet"', text)
        self.assertIn('id: "open_model_library"', text)
        self.assertIn('visible: "More models…"', text)
        self.assertNotIn('id: "advanced_sheet_button"', text)


if __name__ == "__main__":
    unittest.main()
