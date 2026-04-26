# Documentation Drift Register

Last updated: 2026-04-26  
Owner: Product + Engineering

This is the evergreen tracker for doc/code drift. Replace point-in-time audit notes with this register.

## Current Baseline

1. Baseline includes current runtime refactor (`StreamChatRequestV2`, transcript projection, phase-driven status updates).
2. Tool UX is prompt-first in UI. The legacy parser path exists but is disabled by default.
3. Advanced controls/tools are available by default; first-session events are still tracked for telemetry.
4. Current launch-scope lock is narrower than total implementation capability: prompt-first tools, single-image attach/Q&A, and limited-beta voice for controlled cohorts.

## Active Drift Risks

| ID | Area | Risk | Severity | Owner | Next Action |
|---|---|---|---|---|---|
| DR-001 | Streaming contract docs | Runtime stream phase contract can drift when event types change | High | Engineering | Keep `docs/governance/docs-accuracy-manifest.json` synchronized with `MvpRuntimeFacade.kt` |
| DR-002 | Tool UX docs | Prompt shortcuts can be mistaken for direct tool execution | High | Product + Android | Keep `docs/ux/implemented-behavior-reference.md` aligned with `ToolDialog.kt` + `ChatApp.kt` + `ChatViewModel.kt` |
| DR-003 | Privacy claims | User-control claims can exceed current in-app controls | High | Product + Security | Keep `docs/security/privacy-model.md` bounded to implemented controls |
| DR-004 | Test process duplication | Command/process guidance can split across multiple docs | Medium | Engineering + QA | Keep one playbook (`docs/testing/test-strategy.md`) and move task details to runbooks |
| DR-005 | Evidence-note sprawl | Historical notes accumulate after decisions are stable | Medium | Product Ops | Prune superseded notes not referenced by active roadmap/PRD/ticket artifacts |
| DR-006 | Voice scope and launch-claim drift | Docs can describe voice as either post-MVP or generally available instead of limited-beta/closed-track | High | Product + Release Ops | Keep launch-facing docs explicit that voice is in locked scope only as limited beta until evidence/privacy parity supports wider claims |

## Completed This Cycle

1. Replaced date-stamped sync audit with this register.
2. Synced streaming/tool/privacy/routing docs to current code paths.
3. Consolidated testing process docs into one playbook + one runbook index.
4. Pruned superseded evidence notes that were not referenced by active planning/incident artifacts.
5. Redesigned docs governance to include feature-doc-code contract checks and report freshness checks.

## Update Rule

1. Add a register entry when a doc mismatch is discovered.
2. Close entry only after doc and code are both updated and governance checks pass.
3. Keep the table short; move closed historical details to git history.
