# WP-13 AI Human-Proxy Rerun Status

Last updated: 2026-05-04
Owner: QA + Product

## Scope

Refresh the disclosed AI human-proxy packet against the current flow/bundle setup and replace the older hold-state rerun note with current-window closure truth.

## Outcome

Current result: `promote`

This rerun now has a current four-reviewer proxy matrix and a promote-level packet for the controlled Play Store MVP.

## What Changed

1. Hosted send proof is now current and green on account 1 at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`.
2. Hosted model-management/setup proof is now current and green on account 2 at `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`.
3. The preserved S22 strict-journey authority remains current enough for the controlled-MVP decision at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/journey/20260503-234734/journey-report.json` with send-capture `phase=completed` and `placeholder_visible=false`.
4. Proxy reviewers re-ran the packet against the current evidence set and converged on a promote recommendation once the current timeout/recovery contract tests and manifest-fallback contract tests were included in the review set.

## Current Evidence Anchors

1. `docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md`
2. `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`
3. `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`
4. `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/journey/20260503-234734/journey-report.json`
5. `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/summary.txt`
6. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/controllers/ChatStreamCoordinatorTest.kt`
7. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/ChatViewModelTest.kt`
8. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/state/StreamStateReducerTest.kt`
9. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDistributionManifestProviderTest.kt`

## Findings

### Machine-Verifiable Proof

1. The remaining hosted send blocker is closed on the current build: account 1 `send-after-ready` passes with `status=passed`, `junit_failures=0`, and no failed flows.
2. The setup/model-library surface is also green on the current build: account 2 `scenario-model-management-split-smoke` passes with `status=passed`, `junit_failures=0`, and no failed flows.
3. The preserved S22 strict-journey run still provides current-window physical send authority even though its first token lands at the 180s boundary; treat that as perf caution, not as a missing-authority blocker.
4. Local wireless Samsung Maestro remains harness-class supporting evidence only; the promote decision does not depend on converting that path into publishable gate authority.

### Recovery-State Proof

1. `stuck_send` is now accepted for the controlled MVP because the current timeout/recovery contract is covered by `ChatStreamCoordinatorTest`, `ChatViewModelTest`, and `StreamStateReducerTest`, and the current hosted send pass plus preserved S22 journey prove the healthy destination state on the current build.
2. `manifest_outage` is now accepted for the controlled MVP because `ModelDistributionManifestProviderTest` proves deterministic bundled-manifest fallback under remote refresh failure and stable machine-code surfacing, while the current hosted model-management smoke proves the user-facing model-library recovery surface is operational on the current build.
3. These two rows remain supported mainly by deterministic contract coverage plus adjacent current-window runtime/setup passes, not by a fresh induced-failure live-device replay. That is acceptable for the disclosed `AI human-proxy` fallback path, but it should stay documented as a caution rather than over-claimed as stronger evidence than it is.

## Decision Use

Use this note together with `docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md`.

1. The packet now truthfully lands on `promote` for the controlled MVP.
2. The current four-reviewer proxy matrix is now the active measured baseline for the controlled-MVP decision.
3. The remaining cautions are evidence-strength notes for timeout and manifest recovery plus local wireless Maestro instability; they are no longer launch blockers.
