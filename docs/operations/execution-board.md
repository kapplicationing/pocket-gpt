# Execution Board

Last updated: 2026-05-04

This is the single mutable board for planning and delivery.

## Program Standard

Engineering and quality excellence are mandatory.

1. Task completion requires acceptance criteria, test proof, and evidence links.
2. Regressions are blockers and must be resolved before scope expansion.
3. Promotion decisions require technical correctness and UX quality confidence.

## Status Legend

- `Done`: completed with evidence and acceptance criteria met
- `In Progress`: currently being executed
- `Ready`: unblocked and queued
- `Blocked`: cannot proceed (blocker listed)
- `Backlog`: not started and not yet ready

## Source Links

- Current release plan: `docs/roadmap/current-release-plan.md`
- Active ticket specs: `docs/operations/tickets/`
- Evidence inventory + retention: `docs/operations/evidence/index.md`
- Program learnings: `docs/operations/launch-program-learnings.md`
- Command/process canon: `scripts/dev/README.md`, `docs/testing/test-strategy.md`, `docs/testing/runbooks.md`

## Work Packages

| ID | Work Package | Status | Notes |
|---|---|---|---|
| WP-00 .. WP-08 | Foundation through launch prep packages | Done | Historical evidence summarized in `docs/operations/evidence/index.md` |
| WP-09 | Distribution plan and beta operations | In Progress | Active full evidence retained in `docs/operations/evidence/wp-09/` |
| WP-10 | Voice limited-beta rail | Ready | Kept in locked launch scope for controlled/closed-track use; broad public claims remain excluded pending evidence + claim parity |
| WP-11 | Android MVP UX package | Done | Historical evidence summarized in `docs/operations/evidence/index.md` |
| WP-12 | Backend production runtime closure | Done | Production-claim-critical evidence retained in `docs/operations/evidence/wp-12/` |
| WP-13 | UX quality closure | In Progress | Active full evidence retained in `docs/operations/evidence/wp-13/` |

## Current Sprint Board

### In Progress

- [ ] DOC-01 timeline/status reconciliation across roadmap + board + role docs
- [ ] DOC-02 product/UX doc parity sync for timeout/cancel/send-capture + manifest outage UX
- [ ] WP-09 distribution plan and beta operations execution
- [ ] WP-13 run-01 hold closure prep
- [ ] QA-14 cloud-first QA evidence migration for deterministic lanes and artifact parity
- [ ] QA-15 agent-assisted QA triage loop for cloud artifact review and issue filing
- [ ] QA-13 send-capture gate operationalization in weekly regression workflow

### Blocked

- [ ] QA-11 rerun preservation/closure for `android-instrumented` + hosted/default runtime-readiness coverage + strict `journey` (`--mode strict --repeats 3`) is now blocked on two separate launched cloud defects, not on missing strict-journey authority. `android-instrumented` still has a preserved current-window pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`. A fresh S22 physical provisioning canary now also passes at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`, confirming seeded-model startup checks, active-version wiring, and warm-load on the launch-authoritative device without Maestro harness involvement. Wireless Samsung `maestro` remains harness-class supporting evidence only: the canonical S22 lane still lacks a publishable report because it emitted an empty `passed` report at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/maestro/20260503-222859/maestro-report.json`, while a direct preserved S22 Maestro run failed in bootstrap with `io.grpc.StatusRuntimeException: UNAVAILABLE` / `localhost:7001` connection refusal at `tmp/direct-maestro-s22-20260503-2231/junit.xml`. On the hosted/default surface, account 1 runtime-ready now has a fresh launched pass at `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-1-meteredfix/upload-status.json`, but account 1 `send-after-ready` now fails later in the journey at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account-1-current/upload-status.json` with `Assertion is false: id: message_bubble_assistant_complete is visible` after app launch and send. Account 2 remains blocked by the fresh launched model-management flow at `tmp/maestro-cloud-targeted/20260504T025141Z-account-2-model-management-fresh/upload-status.json` with `Assertion is false: id: session_drawer_button is visible`; this replaces the older `Setup`-wording artifact as the live hosted blocker. The fresh account-2 runtime-ready reruns at `tmp/maestro-cloud-targeted/20260504T0045Z-scenario-runtime-ready-smoke-account-2-rerun/upload-status.json`, `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-2-meteredfix/upload-status.json`, and `tmp/maestro-cloud-targeted/20260504T013500Z-scenario-runtime-ready-smoke-account-2-classify/upload-status.json` remain `PENDING` with `completed=false` and `wasAppLaunched=false`, so they are cloud-infra noise rather than replacement app verdicts. Strict `journey` still has a preserved current-window S22 pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/journey/20260503-234734/journey-report.json` with `phase=completed`, `placeholder_visible=false`, and `first_token_ms=180222`. The preserved emulator rerun remains diagnostic-only after stalling in in-flight prefill at `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-235023/journey-report.json` with `runtime_status=LOADING`, `model_status_detail=Prefill...`, and the slow-state guidance still visible at the timeout boundary. The A51 remains non-authoritative and diagnostic-only: the latest strict run at `tmp/devctl-artifacts/2026-05-04/192.168.1.44:37643/journey/20260504-004041/` still times out in send-capture after a 180s budget with `runtime_status=LOADING` and `model_status_detail=Prefill...`.
- [ ] QA-WP13-RUN02 no longer lacks a packet: the disclosed `AI human-proxy` result remains preserved at `docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md`. That packet now carries a fresh rerun-status note tied to current artifact roots (`tmp/qa-agents/device-s22/20260503T152401Z/`, `tmp/qa-agents/cloud-1/20260503T152529Z/`) and still lands on `hold`: the retained last-complete proxy matrix remains a measured hold baseline, and the current rerun path is still blocked on local Maestro bootstrap plus missing hosted verdict return. Human-moderated closure remains preferred when available, and proxy evidence still cannot compensate for missing machine-verifiable lane passes.
- [ ] PROD-10 launch gate matrix decision run blocked by required-row failures (`S-D`, `S-E`, `S-F`, `S-G`)

### Ready

- [ ] PROD-12 human-required gate split for moderated usability vs machine-verifiable evidence
- [ ] PROD-13 Play Store submission readiness package
- [ ] UX-13 stuck send + timeout recovery UX acceptance coverage
- [ ] SEC-02 privacy claim parity audit
- [ ] PROD-11 pilot support + incident UX-ops playbook
- [ ] MKT-08 proof asset capture + listing shotlist finalization
- [ ] MKT-09 first 7-day channel scorecard execution
- [ ] MKT-10 claim freeze v1

### Done (Recent)

- [x] PROD-09 soft-gate pilot policy published
- [x] UX-12 recovery journey spec published
- [x] ENG-21 interaction architecture refactor landed
- [x] ENG-19 devctl package UID parser hardening landed (`userId`/`appId`/`uid` + tests)
- [x] ENG-22 provisioning startup-check lane blocker closed (`docs/operations/tickets/archive/eng-22-provisioning-startup-check-lane-blocker.md`)
- [x] ENG-23 host-side native runtime provisioning `SIGILL` mitigation landed; the retained provisioning crash is no longer the live blocker in the launch program
- [x] ENG-24 startup/readiness metadata self-healing landed; the later setup/provisioning gap was narrowed to missing multimodal companion (`mmproj`) sync in `devctl` preflight and fixed in local code
- [x] ENG-20 host-side runtime cancel/timeout contract coverage landed; strict journey proof still depends on clearing the runtime-ready blocker in `QA-11`
- [x] Local authoritative onboarding proof is now represented in `android-instrumented` coverage through `MainActivityAuthoritativeOnboardingInstrumentationTest`; launch still remains blocked on current-window pass evidence, not on missing onboarding contract coverage
- [x] Current-window `android-instrumented` rerun passed on the S906N canary; the remaining technical evidence gap is no longer authoritative onboarding coverage, but the still-red hosted/default cloud surface plus strict `journey`
- [x] Local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; branch-closeout risk is now publication to `origin/main`, not an internal merge between those two local refs
- [x] WP-12 package closeout complete
- [x] `docs/testing/generated/launch-flow-truth.md` is now the only selector/copy/runtime-state authority for automated QA flow maintenance and agent-assisted deterministic QA tooling
- [x] Android hot-path launch work is now explicitly governed by `docs/architecture/android-performance-contract.md` and benchmark-only evidence rules
- [x] DOC-03 superpower-plan canon sync and docs-health cleanup completed; broken local plan references are cleared and the launch/testing canon now reflects the controlled-MVP evidence policy

## Critical Path And Parallel Streams

### Critical Path

1. Preserve the S906N `android-instrumented` pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`.
2. Close the remaining machine-verifiable gap through a publishable physical canary substitute on S22 and a stable hosted/default cloud surface across the current targeted flows; strict `journey` now has current-window proof and should be carried as preserved evidence plus perf-risk context.
3. Use the narrow Samsung canary only for final OEM/runtime confirmation once the cloud/default path and preserved device artifacts are materially stable.
4. Complete the human-moderated or disclosed `AI human-proxy` WP-13 leg only after the deterministic technical path is stable enough to measure real usability signal.
5. Re-run `PROD-10` from current evidence, then decide whether local `main` can be published to `origin/main`.
6. Use the approved `AI human-proxy` bundle only as the disclosed fallback closure path for the controlled MVP; it never substitutes for machine-verifiable evidence.

### Parallel Streams

1. Stream A, critical: hosted/default machine-verifiable evidence closure (`QA-11`, `QA-14`, `QA-15`, `QA-13`).
2. Stream B, parallel prep plus fallback execution: WP-13 packet prep, facilitator packet cleanup, metric templates, and the disclosed `AI human-proxy` fallback path when moderators are unavailable.
3. Stream C, parallel prep only: support/privacy/claim/package readiness (`PROD-11`, `SEC-02`, `MKT-08`, `MKT-10`, `PROD-13`).
4. Stream D, governance: branch/publication hygiene. Audit `origin/main..main`, keep `cursor/cloud-agent-1775007300791-5ig66` out of the launch closeout path unless a specific change is deliberately extracted, and publish `main` only after the launch hold is cleared.

### Parallel Agent Support Topology

1. Subagent A, Cloud Run Operator: hosted reruns, upload polling, artifact capture, retry bookkeeping.
2. Subagent B, Failure Triage Analyst: screenshot/logcat/report inspection and first-failure classification.
3. Subagent C, Flow Truth / Docs Auditor: validates automated flows against `docs/testing/generated/launch-flow-truth.md` and flags doc drift or over-claims.
4. Subagent D, Evidence Packager: updates pass-id mapping, evidence inventory, and launch-readiness snapshots.
5. Release-owner only: approve proxy fallback use, interpret disclosed proxy output, and make the final go/no-go recommendation.

### Dependency Rules

1. Stream A gates every other stream's final closeout value.
2. Stream B can prepare in parallel, but should not run final measurement until Stream A is materially stable.
3. Stream C can prepare in parallel, but must freeze copy and assets only against verified current evidence.
4. Stream D can be audited in parallel at any time, but pushing `main` is a post-gate action, not a substitute for passing evidence.

## Owner Focus (Current)

1. Engineering: keep product-code changes bounded to defects exposed by current lane artifacts; the live launch gap is now evidence closure, not a known missing feature contract.
2. QA: run cloud-first machine-verifiable evidence through `QA-11`, `QA-14`, `QA-13`, and `QA-15`, with special focus on preserving publishable direct S22 physical canary artifacts, clearing the current hosted/default targeted failure set rather than teaching one cloud flow as the only blocker, and carrying the preserved strict-journey pass with its long-prefill/perf warning instead of reopening authority churn.
3. Product: keep machine-verifiable evidence separate from moderation-backed closure, validate the `AI human-proxy` fallback bundle, close `PROD-12`, `PROD-11`, and `PROD-13`, then run the `PROD-10` decision flow and only then authorize publication of local `main`.
4. Marketing: execute `MKT-08` and `MKT-09`, then finalize `MKT-10` claim freeze against verified behavior only, with voice still bounded to limited-beta/closed-track language and image claims staying single-image only.
5. Performance: for risky UI/runtime hot-path changes on the launch path, require clean performance-contract audits and benchmark-only evidence before widening rollout confidence.

Full owner/dependency split: `docs/operations/play-store-launch-program.md`

## Evidence Requirements (Current)

1. Active package evidence belongs in `docs/operations/evidence/wp-09/` and `docs/operations/evidence/wp-13/`.
2. Production-claim-critical WP-12 evidence remains in `docs/operations/evidence/wp-12/`.
3. Historical package detail is summarized in `docs/operations/evidence/index.md`.
4. Current local authoritative lane evidence is anchored at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`, and the current repo-side launch snapshot is `build/devctl/launch-readiness/launch-readiness-report.md`.
