# PROD-10 Launch Gate Matrix

Last updated: 2026-04-26
Owner: Product
Support: QA, Engineering, Marketing
Status: Hold

## Purpose

Single promotion interface for release decisions. Every publishable claim must map to a validated user story, flow, test, and evidence chain.

## Gate Modes

1. Required: must pass for any promotion decision.
2. Advisory: informs scope/pace of expansion, but does not block pilot continuation alone.

## Matrix

| Story ID | User Story | UX Flow Reference | Test IDs / Lanes | Evidence IDs | Claim ID | Gate Type | Current State |
|---|---|---|---|---|---|---|---|
| S-A | Offline quick answer works in first session | `docs/prd/phase-0-prd.md` Workflow A | `MainActivityUiSmokeTest`, `devctl lane android-instrumented`, Maestro scenario A | `docs/operations/evidence/index.md` (WP-11 closure summary), `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md` | C-01 offline quick-answer reliability | Required | PASS |
| S-B | Prompt-first local task/tool flow completes without cloud dependency | `docs/prd/phase-0-prd.md` Workflow B | `MainActivityUiSmokeTest`, Maestro scenario B, journey lane | `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md`, `docs/operations/evidence/index.md` (WP-11 closure summary) | C-02 prompt-first local tool utility | Required | PASS |
| S-C | Context follow-up (incl. single-image attach) stays coherent | `docs/prd/phase-0-prd.md` Workflow C | Maestro scenario C, journey aggregate | `docs/operations/evidence/index.md` (WP-11 closure summary), `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md` | C-03 context continuity + single-image support | Required | PASS |
| S-D | User can recover from `NotReady` to `Ready` | `docs/ux/ux-12-recovery-journey-spec.md`, `docs/ux/model-management-flow.md` | `RealRuntimeProvisioningInstrumentationTest`, journey lane, moderated WP-13 script | `docs/operations/evidence/wp-13/2026-03-09-qa-lane-rerun-eng22-a51-revalidation.md`, `docs/operations/evidence/wp-13/2026-03-10-qa-gate-policy-validation-and-a51-rerun.md`, `docs/operations/evidence/wp-13/2026-03-04-wp13-usability-gate-run-01.md` | C-04 first-run setup clarity | Required | FAIL (the old retained blocker is no longer the first failure; local code now covers the missing multimodal companion sync in preflight, but current-window recovery proof is still blocked by the remaining hosted/default `send-after-ready` / `maestro` / strict `journey` gaps plus incomplete moderated metrics) |
| S-D1 | User completes simple-first lane and advanced unlock path | `docs/ux/implemented-behavior-reference.md`, `docs/prd/phase-0-prd.md` | `ChatViewModelTest`, `MainActivityUiSmokeTest`, `MainActivityAuthoritativeOnboardingInstrumentationTest`, Maestro scenario B unlock prelude, journey first-session fields | `docs/operations/evidence/wp-13/2026-03-09-qa-lane-rerun-eng22-a51-revalidation.md`, `docs/operations/evidence/wp-13/2026-03-10-qa-gate-policy-validation-and-a51-rerun.md` | C-10 simple-first onboarding clarity | Required | FAIL (local authoritative onboarding proof and a current-window `android-instrumented` pass now exist, but the row remains open because current-window advanced-unlock proof and strict `journey` evidence are still incomplete) |
| S-E | Privacy boundaries and controls are understandable | `docs/security/privacy-model.md`, `docs/ux/implemented-behavior-reference.md` | UI smoke privacy checks, moderated WP-13 privacy comprehension metrics | `docs/operations/evidence/wp-13/2026-03-04-wp13-usability-gate-run-01.md` | C-05 privacy-first trust | Required | FAIL (moderated metrics missing) |
| S-F | Stuck send/timeout path recovers without losing context | `docs/prd/phase-0-prd.md`, `docs/operations/tickets/ux-13-stuck-send-timeout-recovery.md` | `ChatViewModelTest`, journey send-capture stage, Maestro scenario A timeout assertions | `docs/operations/evidence/wp-13/2026-03-09-qa-lane-rerun-eng22-a51-revalidation.md`, `docs/operations/evidence/wp-13/2026-03-10-qa-gate-policy-validation-and-a51-rerun.md` | C-08 reliable send recovery | Required | FAIL (host-side timeout contract and journey schema coverage landed; strict journey remains an authoritative open row and still lacks current-window pass evidence) |
| S-G | Manifest outage still allows setup recovery | `docs/ux/model-management-flow.md`, `docs/operations/tickets/prod-09-soft-gate-pilot-policy.md` | provisioning + model setup regression checks, moderated recovery script | `docs/operations/evidence/wp-13/2026-03-10-qa-gate-policy-validation-and-a51-rerun.md` | C-09 resilient model setup recovery | Required | FAIL (startup metadata self-healing and multimodal companion sync are now understood locally, but current-window setup recovery proof is still blocked by incomplete authoritative lane evidence and the missing moderated packet) |
| A-01 | Time-to-first-useful-answer meets pilot target | `docs/ux/ux-12-recovery-journey-spec.md` success targets | journey report timing + moderated notes | pending `QA-WP13-RUN02` packet | C-06 speed perception | Advisory | Pending |
| A-02 | Channel engagement signal supports expansion | `docs/operations/mkt-03-7-day-scorecard-template.md` | `MKT-09` scorecard execution | pending `MKT-09` run-01 | C-07 channel fit | Advisory | Pending |

## Scope Notes

1. This matrix governs publishable launch claims for the current window.
2. Tool rows cover the prompt-first launch surface, not richer direct-tool dispatch that exists behind the runtime/controller contract.
3. Image rows are bounded to single-image attach/contextual Q&A, not broader multi-image or document-analysis promises.
4. Voice remains in locked scope only as a limited beta for controlled cohorts and is intentionally excluded from the broad public claim set tracked here.
5. Single-image rows are only claim-safe when the required multimodal companion artifact is covered by setup/provisioning evidence, not just by the visible attach UI.

## Pilot Promotion Checklist

Required for `promote`:

1. All required rows = `PASS`.
2. No open `UX-S0`/`UX-S1` blockers.
3. Latest lane pass ids recorded for `android-instrumented`, `maestro`, and `journey`.
4. Latest journey send-capture stage reports `phase=completed` and `placeholder_visible=false`.
5. WP-13 moderated packet contains measured values (no `not collected` fields).
6. Privacy-related public claims map only to `Verified` entries in `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md`.

Advisory for scope sizing:

1. `first_useful_answer_ms` trend.
2. Onboarding + recovery completion time distribution.
3. 7-day scorecard keep/iterate/stop recommendation.

## Decision Log

| Decision Date (UTC) | Window | Recommendation | Rationale | Next Scope |
|---|---|---|---|---|
| 2026-03-05 | Pre-run-02 baseline | Hold | WP-13 run-01 has missing moderated cohort metrics | Execute `QA-WP13-RUN02`, then rerun matrix |
| 2026-03-08 | Post-P0 parser rerun | Hold | Required lanes fail at provisioning preflight (`RealRuntimeProvisioningInstrumentationTest`) and moderated packet still incomplete | Close `ENG-22`, rerun required lanes, then execute moderated `QA-WP13-RUN02` |
| 2026-03-10 | Gate policy validation rerun | Hold | Required lanes are blocked by reproducible native runtime crash (`SIGILL`) in provisioning preflight | Resolve native runtime crash, rerun merge-unblock/promotion gates, then execute moderated `QA-WP13-RUN02` |
| 2026-04-25 | Active device rerun progression | Hold | Native baseline selection, startup metadata self-healing, onboarding selector fixes, hardened Maestro entry points, and timeout/journey evidence hardening moved the live rerun past provisioning preflight; the remaining open work was still being misframed as a generic runtime-ready issue | Re-baseline the active blocker chain from current evidence, then rerun required lanes |
| 2026-04-26 | Current canon sync | Hold | The setup/provisioning gap was narrowed to missing multimodal projector (`mmproj`) sync in `devctl` preflight and fixed in local code; cloud-first remains the default machine-verifiable path, but authoritative `android-instrumented` / strict `journey` evidence is still missing, wireless Samsung Maestro remains a local harness blocker, and moderated WP-13 values are still incomplete | Keep cloud-first reruns moving, clear authoritative lane evidence, treat wireless Samsung as diagnosis/final-brush only, then execute moderated `QA-WP13-RUN02` |
| 2026-04-26 | Hosted-status and onboarding-proof sync | Hold | Local authoritative onboarding proof now exists in the `android-instrumented` lane, but hosted `send-after-ready` still lacks a clean hosted verdict; launch therefore remains blocked on incomplete current-window machine evidence plus the still-missing moderated packet | Close the hosted `send-after-ready` verdict, rerun authoritative lanes, then execute moderated `QA-WP13-RUN02` |
| 2026-04-26 | Branch/publication truth sync | Hold | Local `main` is already at the same tip as `codex/launch-readiness-implementation`, and `android-instrumented` now has a current-window pass; the remaining blockers are hosted/default `send-after-ready` / `maestro`, strict `journey`, and the incomplete moderated packet, so the open branch task is publication to `origin/main` after gates clear, not another internal merge | Preserve the current `android-instrumented` evidence, close the remaining machine evidence and moderated packet, then push `main` only if the launch decision advances |
| 2026-04-26 | Infra-blocker classification sync | Hold | The latest hosted `send-after-ready` uploads are accepted by Maestro Cloud but remain `PENDING` without hosted verdicts, and the corrected strict `journey` kickoff still fails in the local Maestro `localhost:7001` bootstrap layer before app logic begins; the remaining machine-evidence gap is therefore infrastructure-class, not a newly observed product regression | Preserve the existing `android-instrumented` proof, keep hosted/default uploads and strict `journey` open as incomplete machine evidence, and do not widen product scope while waiting for harness/cloud closure |
