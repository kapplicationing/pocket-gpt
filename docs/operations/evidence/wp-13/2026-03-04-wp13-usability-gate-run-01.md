# WP-13 Usability Gate Run 01 (Operational Packet)

Last updated: 2026-03-10  
Owner: Product  
Support: QA, Design, Engineering

## Status

Operational packet finalized for run-01 decision.  
Decision: `hold` until moderated cohort metrics are collected.

## Cohort Metadata

1. Cohort id: `wp13-run-01`
2. Build id + commit: `414e900` (run-01 packet decision baseline)
3. Device set used (required-tier + best-effort): Samsung `DEVICE_SERIAL_REDACTED` + best-effort fallback lane
4. Session window (UTC): `not executed`
5. Moderator(s): Product + QA (pending scheduling)

## Task Script (Workflow A/B/C)

1. Workflow A - Offline quick answer
2. Workflow B - Local tool task
3. Workflow C - Context follow-up (+ optional image path)

Execution rule:

1. Moderator reads task prompt.
2. Participant executes without intervention.
3. Completion/time/blocker reason logged immediately.

## Quantitative Gate Table

| Metric | Threshold | Actual | Pass |
|---|---|---|---|
| Workflow A completion (n=5+) | `>= 90%` | not collected | fail (blocking) |
| Workflow B completion (n=5+) | `>= 90%` | not collected | fail (blocking) |
| Workflow C completion (n=5+) | `>= 80%` | not collected | fail (blocking) |
| Onboarding completion | `>= 80%` | not collected | fail (blocking) |
| Runtime/model confusion reports | `<= 10%` | not collected | fail (blocking) |
| Privacy confusion reports | `<= 10%` | not collected | fail (blocking) |
| Critical UX blockers (`S0`/`S1`) | `0 open` | no new blocker logged from automation lanes | pass |

## Automation Baseline Attached

1. Instrumentation smoke test includes onboarding/runtime/privacy/NL-tool/model-setup checks:
   - `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/MainActivityUiSmokeTest.kt`
2. Real-runtime app-path smoke exists for RC lane:
   - `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/RealRuntimeAppPathInstrumentationTest.kt`
3. Maestro A/B/C flows:
   - `tests/maestro/scenario-a.yaml`
   - `tests/maestro/scenario-b.yaml`
   - `tests/maestro/scenario-c.yaml`

## Qualitative Synthesis Template (PROD-08 Taxonomy)

1. usability: pending moderated cohort notes
2. comprehension: pending moderated cohort notes
3. reliability-perceived: automation baseline only, user signal pending
4. performance-perceived: automation baseline only, user signal pending
5. trust/privacy perception: pending moderated cohort notes

## Evidence Links

1. QA weekly matrix run: `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md`
2. Instrumentation run id: `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/eng-18-qa-10-ui-hardening-20260304-132259-pass/02-android-instrumented.log`
3. Maestro run id: `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/eng-18-qa-10-ui-hardening-20260304-132259-pass/03-maestro.log`
4. Real runtime closure baseline: `docs/operations/evidence/wp-12/2026-03-05-eng-13-native-runtime-rerun.md`
5. User session notes: `not yet collected (blocking)`
6. Video/screenshot proof set: `not yet collected (blocking)`
7. Raw artifact root: `scripts/benchmarks/runs/2026-03-05/DEVICE_SERIAL_REDACTED/`

## Decision

1. Product recommendation (`promote`/`hold`): `hold`
2. QA concurrence (`yes`/`no`): `yes` (hold)
3. Engineering concurrence (`yes`/`no`): `yes` (hold)
4. Conditions to close WP-13:
   - collect moderated 5-user Workflow A/B/C completion metrics,
   - fill onboarding/runtime/privacy confusion thresholds,
   - attach user-session notes and listing proof set.
5. Decision date (UTC): `2026-03-05`

## Addendum (2026-03-10 rerun checkpoint)

1. Required automation reruns and latest gate validation are recorded in:
   - `docs/operations/evidence/wp-13/2026-03-09-qa-lane-rerun-eng22-a51-revalidation.md`
   - `docs/operations/evidence/wp-13/2026-03-10-qa-gate-policy-validation-and-a51-rerun.md`
2. Moderated `QA-WP13-RUN02` packet remains blocked because required lanes still fail provisioning/runtime preflight.
3. Blocking ticket:
   - `docs/operations/tickets/archive/eng-22-provisioning-startup-check-lane-blocker.md`
