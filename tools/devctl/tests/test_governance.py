from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from tools.devctl import governance
from tools.devctl.subprocess_utils import DevctlError


class GovernanceTest(unittest.TestCase):
    def _seed_docs_health_repo(self, root: Path) -> None:
        (root / "docs/start-here").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/tickets").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/evidence/wp-09").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/evidence/wp-12").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/evidence/wp-13").mkdir(parents=True, exist_ok=True)
        (root / "docs/testing").mkdir(parents=True, exist_ok=True)
        (root / "docs/ux").mkdir(parents=True, exist_ok=True)
        (root / "docs/governance").mkdir(parents=True, exist_ok=True)
        (root / "scripts/dev").mkdir(parents=True, exist_ok=True)
        (root / "scripts/android").mkdir(parents=True, exist_ok=True)
        (root / "tests/maestro").mkdir(parents=True, exist_ok=True)
        (root / "apps/mobile-android").mkdir(parents=True, exist_ok=True)
        (root / "apps/mobile-android-host").mkdir(parents=True, exist_ok=True)
        (root / "config/devctl").mkdir(parents=True, exist_ok=True)

        (root / "README.md").write_text("# Root\nSource of truth\n", encoding="utf-8")
        (root / "scripts/dev/README.md").write_text("# Dev\nSource of truth\n", encoding="utf-8")
        (root / "scripts/android/README.md").write_text("# Android\n", encoding="utf-8")
        (root / "tests/maestro/README.md").write_text("# Maestro\n", encoding="utf-8")
        (root / "apps/mobile-android/README.md").write_text("# App\n", encoding="utf-8")
        (root / "apps/mobile-android-host/README.md").write_text("# Host\n", encoding="utf-8")

        (root / "docs/start-here/new-joiner.md").write_text("# New Joiner\n", encoding="utf-8")
        (root / "docs/start-here/resource-map.md").write_text("# Resource Map\n", encoding="utf-8")
        (root / "docs/start-here/source-of-truth-matrix.md").write_text("# Matrix\n", encoding="utf-8")
        (root / "docs/start-here/documentation-drift-register.md").write_text("# Drift\n", encoding="utf-8")

        (root / "docs/testing/test-strategy.md").write_text("# Strategy\n", encoding="utf-8")
        (root / "docs/testing/runbooks.md").write_text("# Runbooks\n", encoding="utf-8")

        (root / "docs/README.md").write_text(
            "\n".join(
                [
                    "- start-here/new-joiner.md",
                    "- start-here/resource-map.md",
                    "- start-here/source-of-truth-matrix.md",
                    "- start-here/documentation-drift-register.md",
                ]
            ),
            encoding="utf-8",
        )
        (root / "docs/operations/README.md").write_text(
            "- execution-board.md\n- evidence/index.md\n- tickets/\n",
            encoding="utf-8",
        )
        (root / "docs/ux/README.md").write_text(
            "- implemented-behavior-reference.md\n- model-management-flow.md\n",
            encoding="utf-8",
        )
        (root / "docs/testing/README.md").write_text(
            "- test-strategy.md\n- runbooks.md\n",
            encoding="utf-8",
        )

        (root / "docs/operations/execution-board.md").write_text("Status: In Progress\n", encoding="utf-8")
        (root / "docs/operations/tickets/ticket.md").write_text("Status: Ready\n", encoding="utf-8")
        (root / "docs/ux/implemented-behavior-reference.md").write_text("# Impl\n", encoding="utf-8")
        (root / "docs/ux/model-management-flow.md").write_text("# Flow\n", encoding="utf-8")

        (root / "docs/operations/evidence/index.md").write_text(
            "\n".join(["WP-01", "WP-02", "WP-03", "WP-04", "WP-05", "WP-06", "WP-07", "WP-08", "WP-11"]),
            encoding="utf-8",
        )
        (root / "docs/operations/evidence/wp-09/2026-03-04-note.md").write_text("# WP09\n", encoding="utf-8")
        (root / "docs/operations/evidence/wp-13/2026-03-05-note.md").write_text("# WP13\n", encoding="utf-8")

        for note in governance.DEFAULT_DOCS_GOVERNANCE_CONFIG["evidence_retention"]["retained_notes"]["wp-12"]:  # type: ignore[index]
            (root / "docs/operations/evidence/wp-12" / str(note)).write_text("# WP12\n", encoding="utf-8")

        (root / "config/devctl/docs-governance.json").write_text(
            json.dumps(governance.DEFAULT_DOCS_GOVERNANCE_CONFIG, indent=2) + "\n",
            encoding="utf-8",
        )

    def test_evidence_check_pass_and_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_path = root / "tmp/devctl-artifacts/2026-03-03/DEVICE/run"
            run_path.mkdir(parents=True, exist_ok=True)
            (run_path / "artifact.txt").write_text("ok", encoding="utf-8")

            ok_note = root / "ok.md"
            ok_note.write_text(
                "Evidence\n- raw: tmp/devctl-artifacts/2026-03-03/DEVICE/run/artifact.txt\n",
                encoding="utf-8",
            )
            governance.evidence_check("ok.md", repo_root=root)

            bad_note = root / "bad.md"
            bad_note.write_text(
                "Evidence\n- raw: tmp/devctl-artifacts/2026-03-03/DEVICE/run/missing.txt\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                governance.evidence_check("bad.md", repo_root=root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_validate_pr_body_rejects_unchecked_requirements(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pr = root / "pr.md"
            pr.write_text("- [ ] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n", encoding="utf-8")
            with self.assertRaises(DevctlError) as raised:
                governance.validate_pr_body("pr.md", repo_root=root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_stage_close_gate_skip_when_not_requested(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pr = root / "pr.md"
            pr.write_text("Stage close: no\n", encoding="utf-8")
            governance.stage_close_gate("pr.md", repo_root=root)

    def test_evidence_check_changed_skips_outside_pr(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            governance.evidence_check_changed(event_name="push", base_ref="main", repo_root=root)

    def test_docs_drift_missing_source_of_truth_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "scripts/dev").mkdir(parents=True, exist_ok=True)
            (root / "docs/testing").mkdir(parents=True, exist_ok=True)
            (root / "docs/operations").mkdir(parents=True, exist_ok=True)

            (root / "scripts/dev/README.md").write_text("Source of truth", encoding="utf-8")
            (root / "docs/testing/test-strategy.md").write_text("Source of truth", encoding="utf-8")
            (root / "docs/testing/runbooks.md").write_text("Source of truth", encoding="utf-8")
            (root / "README.md").write_text("no marker", encoding="utf-8")

            with self.assertRaises(DevctlError) as raised:
                governance.docs_drift_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_docs_health_passes_for_compliant_structure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_health_repo(root)
            governance.docs_health_check(root)

    def test_docs_health_rejects_status_outside_board_or_tickets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_health_repo(root)
            (root / "docs/product").mkdir(parents=True, exist_ok=True)
            (root / "docs/product/bad.md").write_text("Status: Stale\n", encoding="utf-8")
            with self.assertRaises(DevctlError) as raised:
                governance.docs_health_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_docs_health_rejects_broken_local_markdown_links(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_health_repo(root)
            (root / "docs/README.md").write_text(
                "- start-here/new-joiner.md\n- start-here/resource-map.md\n- [bad](missing.md)\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                governance.docs_health_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_docs_health_rejects_broken_local_backtick_path_refs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_health_repo(root)
            (root / "docs/README.md").write_text(
                "- start-here/new-joiner.md\n- bad ref: `docs/missing/path.md`\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                governance.docs_health_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def _seed_docs_accuracy_repo(self, root: Path) -> None:
        (root / "docs/architecture").mkdir(parents=True, exist_ok=True)
        (root / "docs/ux").mkdir(parents=True, exist_ok=True)
        (root / "docs/security").mkdir(parents=True, exist_ok=True)
        (root / "docs/prd").mkdir(parents=True, exist_ok=True)
        (root / "docs/governance").mkdir(parents=True, exist_ok=True)
        (root / "packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime").mkdir(parents=True, exist_ok=True)
        (root / "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers").mkdir(parents=True, exist_ok=True)
        (root / "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui").mkdir(parents=True, exist_ok=True)
        (root / "apps/mobile-android/src/main/res/values").mkdir(parents=True, exist_ok=True)
        (root / "packages/core-domain/src/commonMain/kotlin/com/pocketagent/core").mkdir(parents=True, exist_ok=True)

        (root / "docs/architecture/system-context.md").write_text("StreamChatRequestV2 TOKEN_STREAM previousResponseId\n", encoding="utf-8")
        (root / "docs/ux/implemented-behavior-reference.md").write_text(
            "StreamChatRequestV2 CHAT_START previousResponseId pre-fills composer text legacyToolIntentParserEnabled = false\n",
            encoding="utf-8",
        )
        (root / "docs/security/privacy-model.md").write_text(
            "User-Visible Controls (Implemented)\nUser-Visible Controls (Not Yet Implemented)\nDo not publish these as available controls\n",
            encoding="utf-8",
        )
        (root / "docs/prd/phase-0-prd.md").write_text("QWEN_0_8B QWEN_2B SMOLLM3_3B PHI_4_MINI\n", encoding="utf-8")

        (root / "packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/MvpRuntimeFacade.kt").write_text(
            "data class StreamChatRequestV2\nenum class ChatStreamPhase\npreviousResponseId: String? = null\n",
            encoding="utf-8",
        )
        (root / "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/SendReducer.kt").write_text(
            "ChatStreamPhase.CHAT_START\nChatStreamPhase.TOKEN_STREAM\n",
            encoding="utf-8",
        )
        (root / "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt").write_text(
            "latestAssistantRequestId\nbuildStreamChatRequest\npreviousResponseId = previousResponseId\n",
            encoding="utf-8",
        )
        (root / "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ToolDialog.kt").write_text(
            "onUsePrompt\ncalculate 4*9\n",
            encoding="utf-8",
        )
        (root / "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/ChatSendFlow.kt").write_text(
            "legacyToolIntentParserEnabled: Boolean = false\nif (!legacyToolIntentParserEnabled)\n",
            encoding="utf-8",
        )
        (root / "apps/mobile-android/src/main/res/values/strings.xml").write_text(
            "ui_privacy_title ui_privacy_item_1 ui_privacy_item_2 ui_privacy_item_3\n",
            encoding="utf-8",
        )
        (root / "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/SettingsSheet.kt").write_text(
            "internal fun PrivacyInfoSheet\n",
            encoding="utf-8",
        )
        (root / "packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/RoutingMode.kt").write_text(
            "QWEN_0_8B\nQWEN_2B\nSMOLLM3_3B\nPHI_4_MINI\n",
            encoding="utf-8",
        )

        (root / "docs/governance/docs-accuracy-manifest.json").write_text(
            json.dumps(
                {
                    "schema": "docs-accuracy-manifest-v1",
                    "features": [
                        {
                            "id": "feature-a",
                            "docs": [
                                {
                                    "path": "docs/architecture/system-context.md",
                                    "must_contain": ["StreamChatRequestV2"],
                                }
                            ],
                            "code": [
                                {
                                    "glob": "packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/MvpRuntimeFacade.kt",
                                    "must_contain": ["data class StreamChatRequestV2"],
                                }
                            ],
                        }
                    ],
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )

    def test_docs_accuracy_passes_and_writes_report(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_accuracy_repo(root)
            governance.docs_accuracy_check(root)
            report = root / "build/devctl/docs-drift-report.json"
            self.assertTrue(report.exists())
            payload = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("docs-drift-report-v1", payload["schema"])
            self.assertEqual("pass", payload["status"])

    def test_docs_accuracy_fails_on_missing_marker(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_accuracy_repo(root)
            (root / "packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/MvpRuntimeFacade.kt").write_text(
                "data class SomethingElse\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                governance.docs_accuracy_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def _seed_screenshot_inventory_repo(self, root: Path) -> None:
        (root / "config/devctl").mkdir(parents=True, exist_ok=True)
        (root / "config/devctl/docs-governance.json").write_text(
            json.dumps(governance.DEFAULT_DOCS_GOVERNANCE_CONFIG, indent=2) + "\n",
            encoding="utf-8",
        )

        inventory_dir = root / "tests/ui-screenshots"
        reference_dir = root / "tests/ui-screenshots/reference/sm-a515f-android13"
        inventory_dir.mkdir(parents=True, exist_ok=True)
        reference_dir.mkdir(parents=True, exist_ok=True)
        (inventory_dir / "inventory.yaml").write_text(
            "\n".join(
                [
                    "schema: ui-screenshot-inventory-v1",
                    "screenshots:",
                    "  - id: ui-01-onboarding-page-1",
                    "    filename: ui-01-onboarding-page-1.png",
                    "    candidates:",
                    "      - instrumented/ui-01-onboarding-page-1.png",
                    "  - id: ui-02-onboarding-page-2",
                    "    filename: ui-02-onboarding-page-2.png",
                    "    candidates:",
                    "      - instrumented/ui-02-onboarding-page-2.png",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        (reference_dir / "ui-01-onboarding-page-1.png").write_bytes(b"\x89PNG\r\n\x1a\n")
        (reference_dir / "ui-02-onboarding-page-2.png").write_bytes(b"\x89PNG\r\n\x1a\n")
        (reference_dir / "index.md").write_text("# Gallery\n", encoding="utf-8")

        report_dir = root / "tmp/devctl-artifacts/2026-03-06/SER123/screenshot-pack/20260306-010101"
        report_dir.mkdir(parents=True, exist_ok=True)
        generated_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        (report_dir / "inventory-report.json").write_text(
            json.dumps(
                {
                    "schema": "ui-screenshot-inventory-report-v2",
                    "generated_at_utc": generated_at,
                    "inventory_schema": "ui-screenshot-inventory-v1",
                    "inventory_digest": "sha256:abc",
                    "git_commit": "abcdef1",
                    "run_id": "20260306-010101",
                    "device_serial": "SER123",
                    "missing_ids": [],
                    "entries": [
                        {"id": "ui-01-onboarding-page-1", "status": "PASS"},
                        {"id": "ui-02-onboarding-page-2", "status": "PASS"},
                    ],
                }
            )
            + "\n",
            encoding="utf-8",
        )

    def test_screenshot_inventory_check_passes_for_complete_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_screenshot_inventory_repo(root)
            governance.screenshot_inventory_check(root)

    def test_screenshot_inventory_check_fails_when_reference_or_report_is_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_screenshot_inventory_repo(root)
            (root / "tests/ui-screenshots/reference/sm-a515f-android13/ui-02-onboarding-page-2.png").unlink()
            broken_report = root / "tmp/devctl-artifacts/2026-03-06/SER123/screenshot-pack/20260306-010101/inventory-report.json"
            generated_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            broken_report.write_text(
                json.dumps(
                    {
                        "schema": "ui-screenshot-inventory-report-v2",
                        "generated_at_utc": generated_at,
                        "inventory_schema": "ui-screenshot-inventory-v1",
                        "inventory_digest": "sha256:abc",
                        "git_commit": "abcdef1",
                        "run_id": "20260306-010101",
                        "device_serial": "SER123",
                        "missing_ids": ["ui-02-onboarding-page-2"],
                        "entries": [
                            {"id": "ui-01-onboarding-page-1", "status": "PASS"},
                            {"id": "ui-02-onboarding-page-2", "status": "MISSING"},
                        ],
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                governance.screenshot_inventory_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_screenshot_inventory_check_accepts_legacy_report_location(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_screenshot_inventory_repo(root)
            current_report_dir = root / "tmp/devctl-artifacts/2026-03-06/SER123/screenshot-pack/20260306-010101"
            for child in current_report_dir.iterdir():
                child.unlink()
            current_report_dir.rmdir()
            legacy_report_dir = root / "scripts/benchmarks/runs/2026-03-06/SER123/screenshot-pack/20260306-010101"
            legacy_report_dir.mkdir(parents=True, exist_ok=True)
            generated_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            (legacy_report_dir / "inventory-report.json").write_text(
                json.dumps(
                    {
                        "schema": "ui-screenshot-inventory-report-v2",
                        "generated_at_utc": generated_at,
                        "inventory_schema": "ui-screenshot-inventory-v1",
                        "inventory_digest": "sha256:abc",
                        "git_commit": "abcdef1",
                        "run_id": "20260306-010101-legacy",
                        "device_serial": "SER123",
                        "missing_ids": [],
                        "entries": [
                            {"id": "ui-01-onboarding-page-1", "status": "PASS"},
                            {"id": "ui-02-onboarding-page-2", "status": "PASS"},
                        ],
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            governance.screenshot_inventory_check(root)

    def _seed_launch_readiness_repo(self, root: Path) -> None:
        (root / "docs/operations/tickets").mkdir(parents=True, exist_ok=True)
        (root / "docs/start-here").mkdir(parents=True, exist_ok=True)
        (root / "docs/ux").mkdir(parents=True, exist_ok=True)

        (root / "docs/operations/execution-board.md").write_text(
            "\n".join(
                [
                    "# Execution Board",
                    "",
                    "Last updated: 2026-04-25",
                    "",
                    "### In Progress",
                    "- [ ] ENG-20 runtime cancel/timeout contract hardening",
                    "",
                    "### Blocked",
                    "- [ ] QA-11 rerun blocked by native SIGILL in provisioning preflight",
                    "",
                    "### Ready",
                    "- [ ] QA-14 cloud-first QA evidence migration",
                    "",
                    "### Done (Recent)",
                    "- [x] PROD-09 soft-gate pilot policy published",
                    "",
                ]
            ),
            encoding="utf-8",
        )

        (root / "docs/operations/tickets/prod-10-launch-gate-matrix.md").write_text(
            "\n".join(
                [
                    "# PROD-10 Launch Gate Matrix",
                    "",
                    "Status: Hold",
                    "",
                    "## Matrix",
                    "",
                    "| Story ID | User Story | UX Flow Reference | Test IDs / Lanes | Evidence IDs | Claim ID | Gate Type | Current State |",
                    "|---|---|---|---|---|---|---|---|",
                    "| S-A | Offline quick answer works in first session | doc | lane | evidence | C-01 | Required | PASS |",
                    "| S-D | User can recover from `NotReady` to `Ready` | doc | lane | evidence | C-04 | Required | FAIL (native SIGILL in provisioning preflight) |",
                    "| S-E | Privacy boundaries and controls are understandable | doc | lane | evidence | C-05 | Required | FAIL (moderated metrics missing) |",
                    "| A-01 | Time-to-first-useful-answer meets pilot target | doc | lane | evidence | C-06 | Advisory | Pending |",
                    "",
                ]
            ),
            encoding="utf-8",
        )

        for ticket_id, rel_path in governance.LAUNCH_READINESS_TICKETS.items():
            path = root / rel_path
            path.parent.mkdir(parents=True, exist_ok=True)
            if ticket_id == "ENG-20":
                status = "Done for host-side contract; pending final strict journey evidence under `QA-11`"
            else:
                status = "Done" if ticket_id == "QA-13" else "Ready"
            path.write_text(
                f"# {ticket_id}\n\nOwner: Owner {ticket_id}\nStatus: {status}\n",
                encoding="utf-8",
            )

        for rel_path in governance.LAUNCH_READINESS_DOCS.values():
            path = root / rel_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"# {path.stem}\n", encoding="utf-8")


    def _seed_model_audit_repo(self, root: Path) -> None:
        catalog_dir = root / "packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference"
        catalog_dir.mkdir(parents=True, exist_ok=True)
        (catalog_dir / "ModelCatalog.kt").write_text(
            'package com.pocketagent.inference\n'
            'object ModelCatalog {\n'
            '    const val MODEL_A = "model-a"\n'
            '    const val MODEL_B = "model-b"\n'
            '    private val descriptors = listOf(\n'
            '        ModelDescriptor(\n'
            '            modelId = MODEL_A,\n'
            '            bridgeSupported = true,\n'
            '            startupCandidate = true,\n'
            '            templateFamily = PromptTemplateFamily.CHATML,\n'
            '            explicitRoutingModes = setOf(RoutingMode.MODEL_A_MODE),\n'
            '        ),\n'
            '        ModelDescriptor(\n'
            '            modelId = MODEL_B,\n'
            '            bridgeSupported = true,\n'
            '            startupCandidate = false,\n'
            '            templateFamily = PromptTemplateFamily.PHI,\n'
            '        ),\n'
            '    )\n'
            '}\n',
            encoding="utf-8",
        )

        dist_dir = root / "apps/mobile-android/src/main/assets"
        dist_dir.mkdir(parents=True, exist_ok=True)
        (dist_dir / "model-distribution-catalog.json").write_text(
            json.dumps({
                "models": [
                    {"modelId": "model-a"},
                    {"modelId": "model-b"},
                ]
            }),
            encoding="utf-8",
        )

        routing_dir = root / "packages/core-domain/src/commonMain/kotlin/com/pocketagent/core"
        routing_dir.mkdir(parents=True, exist_ok=True)
        (routing_dir / "RoutingMode.kt").write_text(
            'enum class RoutingMode {\n    AUTO,\n    MODEL_A_MODE,\n}\n',
            encoding="utf-8",
        )

        scripts_dir = root / "scripts/dev"
        scripts_dir.mkdir(parents=True, exist_ok=True)
        (scripts_dir / "maestro-gpu-matrix-common.sh").write_text("#!/usr/bin/env bash\n", encoding="utf-8")

    def test_launch_readiness_report_writes_json_and_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_launch_readiness_repo(root)

            json_path, markdown_path = governance.launch_readiness_report(root)

            self.assertTrue(json_path.exists())
            self.assertTrue(markdown_path.exists())

            payload = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual("launch-readiness-report-v1", payload["schema"])
            self.assertEqual("Hold", payload["prod10_status"])
            self.assertEqual("blocked", payload["overall_readiness"])
            self.assertEqual(1, payload["matrix"]["required_pass"])
            self.assertEqual(3, payload["matrix"]["required_total"])
            self.assertEqual(len(governance.LAUNCH_READINESS_TICKETS), len(payload["ticket_statuses"]))
            self.assertIn(
                "QA-11 rerun blocked by native SIGILL in provisioning preflight",
                payload["execution_board"]["blocked"][0],
            )

            summary = markdown_path.read_text(encoding="utf-8")
            self.assertIn("Launch Readiness Report", summary)
            self.assertIn("`S-D` User can recover from `NotReady` to `Ready`", summary)
            self.assertIn(
                "- `ENG-20` Done for host-side contract; pending final strict journey evidence under `QA-11` — Owner ENG-20",
                summary,
            )

    def test_model_audit_passes_for_consistent_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_model_audit_repo(root)
            governance.model_audit(root)

    def test_model_audit_fails_for_missing_distribution_entry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_model_audit_repo(root)
            dist_path = root / "apps/mobile-android/src/main/assets/model-distribution-catalog.json"
            dist_path.write_text(
                json.dumps({"models": [{"modelId": "model-a"}]}),
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError):
                governance.model_audit(root)

    def test_model_audit_fails_for_invalid_template_family(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_model_audit_repo(root)
            catalog_path = root / "packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ModelCatalog.kt"
            text = catalog_path.read_text(encoding="utf-8")
            catalog_path.write_text(
                text.replace("PromptTemplateFamily.PHI", "PromptTemplateFamily.INVALID_TEMPLATE"),
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError):
                governance.model_audit(root)

    def test_model_audit_fails_for_invalid_interaction_feature(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_model_audit_repo(root)
            catalog_path = root / "packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ModelCatalog.kt"
            text = catalog_path.read_text(encoding="utf-8")
            catalog_path.write_text(
                text.replace(
                    'templateFamily = PromptTemplateFamily.CHATML,',
                    'templateFamily = PromptTemplateFamily.CHATML,\n            interactionFeatures = setOf("TOOL_CALL_XML", "UNSUPPORTED_FEATURE"),',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError):
                governance.model_audit(root)


if __name__ == "__main__":
    unittest.main()
