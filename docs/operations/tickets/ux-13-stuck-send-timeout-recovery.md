# UX-13 Stuck Send + Timeout Recovery UX

Last updated: 2026-07-11
Owner: Product + Design + Android
Support: QA, Engineering
Status: In Progress

## Objective

Define deterministic user-facing recovery when message send appears stalled (`Loading` or placeholder persists) and generation exceeds timeout SLA.

## User Story

As a user, when a send appears stuck, I can understand what happened and recover without losing session context.

## Scope

1. Runtime timeout/cancel copy and CTA hierarchy in chat surface.
2. Consistent mapping of timeout failures to `UI-RUNTIME-001` with actionable guidance.
3. Recovery flow after timeout:
   - retry send,
   - refresh runtime checks,
   - open model setup.
4. Telemetry/evidence linkage to journey send-capture stage.

## Acceptance

1. Timeout failure shows deterministic copy with explicit next action.
2. Composer exits sending state after timeout and user can retry immediately.
3. Session timeline preserves prompt context across timeout recovery.
4. Instrumented + Maestro assertions cover timeout/recovery UX.
5. Journey send-capture reports `phase=completed` and `placeholder_visible=false` in passing runs.

## Acceptance Audit (2026-07-11)

1. Pass: timeout maps to `UI-RUNTIME-001` with deterministic timeout and recovery guidance.
2. Pending exact proof: the pre-fix probe required Refresh, while `18fe52bf` now restores runtime readiness for timeout/retryable failures. The retained suite passes, but the exact timeout -> second send assertion is not yet present.
3. Pass: the timed-out prompt remains in the same session timeline.
4. Partial: focused JVM coverage proves timeout/cancel state reduction and the new retryable-ready branch, while Maestro Scenario A proves the healthy terminal destination has no stuck placeholder. A direct timeout-to-retry regression and instrumentation assertion still must land.
5. Pass for the preserved controlled-MVP authority: strict S22 evidence reports `phase=completed` and `placeholder_visible=false`. QA-13 now reruns this contract weekly.

This ticket remains open. Do not redefine “retry immediately” as “refresh first.” Commit `18fe52bf` appears to remove the diagnosed admission blocker by restoring `modelRuntimeStatus=READY` for timeout/retryable failures, but the exact two-send acceptance proof must pass before this ticket becomes `Done`.

## Evidence Targets

1. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/ChatViewModelTest.kt`
2. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/controllers/ChatStreamCoordinatorTest.kt`
3. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/state/StreamStateReducerTest.kt`
4. `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/MainActivityUiSmokeTest.kt`
5. `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/ui/ChatStatusHeaderComposeContractTest.kt`
6. `tests/maestro/scenario-a.yaml`
7. `tmp/devctl-artifacts/YYYY-MM-DD/<device>/journey/<stamp>/journey-report.json`

## Remaining Work

1. Preserve the retryable-ready timeout behavior introduced by `18fe52bf`.
2. Add one green timeout -> direct retry regression test that proves the second prompt enters the stream without changing sessions or losing the first prompt.
3. Add the corresponding instrumentation assertion; retain Scenario A's `Send` + complete assistant + no-placeholder destination checks.
4. Rerun the focused timeout tests, then let the integration owner run the broad Android/runtime gate once.

## Audit Evidence

See `docs/operations/evidence/wp-13/2026-07-11-qa13-operationalization-ux13-audit.md`.
