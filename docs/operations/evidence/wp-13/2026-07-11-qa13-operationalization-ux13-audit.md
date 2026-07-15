# QA-13 Operationalization and UX-13 Acceptance Audit

Date: 2026-07-11

Owners: QA + Engineering + Product

Scope: Weekly send-capture gate and stuck-send/timeout recovery acceptance

## Decision

1. `QA-13`: close as `Done`. The strict send-capture contract now has one scheduled/operator path, retained weekly packets, prior-week deltas, and blocking-issue routing.
2. `UX-13`: keep `In Progress`. The original Refresh blocker is addressed by `18fe52bf`, and the retained focused suite is green, but the exact timeout -> immediate second-send regression is not retained yet.

## QA-13 Implementation

1. `scripts/ci/run_weekly_send_capture.sh` wraps the existing strict `devctl lane journey` path with one required-tier device, one repeat, instrumentation + send-capture, and a 90-second reply SLA.
2. `tools/devctl/weekly_send_capture.py` validates all required fields and emits:
   - `qa-13-weekly-report.json`
   - `qa-13-weekly-summary.md`
3. `.github/workflows/nightly-hardware-lane.yml` runs the gate Monday at `06:00 UTC`, supports manual `lane=weekly-send-capture`, uploads the packet and raw journey artifacts, and fails when required hardware is unavailable.
4. The workflow downloads the latest non-expired packet for the same device and classifies the delta against the prior week.
5. A failed or missing packet is `UX-S1` and creates or updates a deduplicated blocking issue with owner, four-hour triage-update ETA, blocker classification, device, artifact path, and workflow URL.
6. Issue routing runs on a GitHub-hosted job after both hardware branches. If the Monday runner is unavailable, the route still executes with `blocker class: infra`, the runner reason, and `packet status: missing`.
7. A later passing packet comments with the green workflow evidence and closes the matching stale issue automatically.

## QA-13 Focused Proof

1. Command:

   ```bash
   python3 -m unittest \
     tools.devctl.tests.test_weekly_send_capture \
     tools.devctl.tests.test_lanes
   ```

   Result: `PASS`, 103 tests.

2. Command:

   ```bash
   bash -n scripts/ci/run_weekly_send_capture.sh
   ```

   Result: `PASS`.

3. Command:

   ```bash
   maestro-android lint tests/maestro/scenario-a.yaml
   ```

   Result: `PASS`, 1 flow, 0 errors, 0 warnings.

4. `.github/workflows/nightly-hardware-lane.yml` parsed successfully as YAML, its QA-13 routing contracts passed source assertions, and both embedded `github-script` blocks passed JavaScript syntax checks. The scheduled/self-hosted run was not dispatched from this slice; the workflow itself is the production execution surface.

## UX-13 Confirmed Behavior

1. `UiErrorMapper.runtimeTimeout` produces `UI-RUNTIME-001` and deterministic retry/readiness guidance.
2. Timeout/cancel reducers stop sending and remove the blank assistant placeholder.
3. The timed-out user prompt remains in the session timeline.
4. `ChatStreamCoordinatorTest` proves request-scoped cancellation and timeout terminal delivery.
5. Maestro Scenario A now explicitly asserts that `Preparing response…` is gone after the assistant completes and the composer returns to `Send`.
6. Preserved physical S22 authority remains:
   - `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/journey/20260503-234734/journey-report.json`
   - send-capture `phase=completed`
   - `placeholder_visible=false`
7. Post-audit code movement: `18fe52bf` restores `READY` for timeout/retryable failures; the focused existing timeout, preparation-retry, coordinator-timeout, and backup-policy tests pass on the rebased closeout branch.

## UX-13 Pre-Fix Acceptance Mismatch

A temporary focused acceptance probe executed this sequence in one session:

1. Send a prompt.
2. Receive a timeout terminal.
3. Confirm the prompt remains, the placeholder is gone, `UI-RUNTIME-001` is visible, and the composer is not sending.
4. Retry the same prompt immediately.

Command:

```bash
./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest \
  --tests 'com.pocketagent.android.ui.ChatViewModelTest.timeout preserves prompt context and allows immediate retry' \
  --tests 'com.pocketagent.android.ui.ChatViewModelTest.send message timeout with no partial text finalizes assistant error bubble' \
  --tests 'com.pocketagent.android.ui.controllers.ChatStreamCoordinatorTest.collect stream timeout cancels request and emits timeout terminal'
```

Result: 3 tests executed; the two existing timeout tests passed, while the direct-retry probe failed with `expected:<2> but was:<1>` user turns. The probe was removed after diagnosis so this closeout does not leave a knowingly red test in the shared worktree.

Root cause classification at baseline `6015bad1`: `product`, not harness. Runtime-error finalization set `modelRuntimeStatus=ERROR`; `ChatSendFlow.isRuntimeReadyForSend` accepted only `READY`, so the retry never entered the stream until Refresh restored readiness.

## Next Proof

The readiness fix has landed in `18fe52bf`. Retain one exact direct timeout-to-retry test, rerun that focused set, then let the integration owner run the broad Android/runtime gate once after the remaining closeout slices converge.

## Broad Gate Intentionally Skipped

This slice did not run the physical weekly journey or lifecycle E2E. Host contract tests and Maestro lint are the narrow proofs for the new packet/workflow logic; direct-retry acceptance is isolated to one missing retained two-send regression, and broad device fan-out cannot replace that proof.
