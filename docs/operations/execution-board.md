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
- Program learnings: `docs/operations/historical/launch-program-learnings.md`
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

- [ ] WP-09 distribution plan and beta operations execution
- [ ] QA-13 send-capture gate operationalization in weekly regression workflow

### Blocked

None.

### Ready

- [ ] PROD-13 Play Store submission readiness package
- [ ] UX-13 stuck send + timeout recovery UX acceptance coverage
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
- [x] ENG-20 host-side runtime cancel/timeout contract coverage landed; preserved strict-journey evidence now carries current physical send authority for the controlled MVP
- [x] Local authoritative onboarding proof is now represented in `android-instrumented` coverage through `MainActivityAuthoritativeOnboardingInstrumentationTest`; that contract is now preserved in the promoted evidence set
- [x] Current-window `android-instrumented` rerun passed on the S906N canary and remains part of the promoted evidence set alongside the now-green hosted/default targeted cloud surface and preserved strict `journey`
- [x] Local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; branch-closeout risk is now publication to `origin/main`, not an internal merge between those two local refs
- [x] WP-12 package closeout complete
- [x] `docs/testing/generated/launch-flow-truth.md` is now the only selector/copy/runtime-state authority for automated QA flow maintenance and agent-assisted deterministic QA tooling
- [x] Android hot-path launch work is now explicitly governed by `docs/architecture/android-performance-contract.md` and benchmark-only evidence rules
- [x] DOC-03 superpower-plan canon sync and docs-health cleanup completed; broken local plan references are cleared and the launch/testing canon now reflects the controlled-MVP evidence policy
- [x] DOC-01 timeline/status reconciliation across roadmap + board + role docs
- [x] DOC-02 product/UX doc parity sync for timeout/cancel/send-capture + manifest outage UX
- [x] QA-11 rerun preservation/closure now has current-window authority: `android-instrumented` remains preserved, hosted account 1 `send-after-ready` now passes at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`, hosted account 2 model-management passes at `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`, and strict S22 `journey` remains preserved as physical send authority
- [x] QA-WP13-RUN02 now has a current disclosed `AI human-proxy` packet with measured values and a `promote` recommendation at `docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md`
- [x] WP-13 run-01 hold closure prep is complete; the current disclosed proxy packet is now the active controlled-MVP moderation-backed leg
- [x] PROD-10 rerun from current evidence now lands on `Promote` for the controlled MVP
- [x] PROD-12 human-required gate split is closed; machine-verifiable versus moderation-backed evidence is now canon and the disclosed `AI human-proxy` fallback path is fully documented for the controlled MVP
- [x] SEC-02 privacy claim parity audit is closed; publish-safe privacy claims are now limited to `Verified` rows and `P-04` remains explicitly internal-only
- [x] QA-14 cloud-first QA evidence migration is closed for the controlled MVP; the promoted evidence set now carries current hosted/default proofs plus the preserved narrow physical canary
- [x] QA-15 agent-assisted QA triage loop is closed for the controlled MVP; cloud failures were triaged through the agent loop with first-failure classification and evidence packaging

## Critical Path And Parallel Streams

### Critical Path

1. Preserve the S906N `android-instrumented` pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`.
2. Preserve the current hosted/default pass roots and the S22 physical canary/journey artifacts as the publication evidence set.
3. Use the narrow Samsung canary only for OEM/runtime confirmation; do not reopen local wireless Maestro as launch authority.
4. Carry the disclosed `AI human-proxy` packet as the closed moderation-backed leg for the controlled MVP.
5. Execute final publication/package steps from the now-promoted evidence set, then publish local `main` to `origin/main` from a clean worktree.
6. Keep the approved `AI human-proxy` bundle documented as a fallback closure path only; it never substitutes for machine-verifiable evidence.

### Parallel Streams

1. Stream A, closed: hosted/default machine-verifiable evidence closure (`QA-11`, `QA-14`, `QA-15`, `QA-13`) is sufficient for the controlled-MVP decision.
2. Stream B, closed for controlled MVP: the disclosed `AI human-proxy` fallback packet now supplies the moderation-backed leg when moderators are unavailable.
3. Stream C, active: support/claim/package readiness (`PROD-11`, `MKT-08`, `MKT-09`, `MKT-10`, `PROD-13`).
4. Stream D, active: branch/publication hygiene. Audit `origin/main..main`, keep `cursor/cloud-agent-1775007300791-5ig66` out of the launch closeout path unless a specific change is deliberately extracted, and publish `main` from the promoted evidence state.

### Parallel Agent Support Topology

1. Subagent A, Cloud Run Operator: hosted reruns, upload polling, artifact capture, retry bookkeeping.
2. Subagent B, Failure Triage Analyst: screenshot/logcat/report inspection and first-failure classification.
3. Subagent C, Flow Truth / Docs Auditor: validates automated flows against `docs/testing/generated/launch-flow-truth.md` and flags doc drift or over-claims.
4. Subagent D, Evidence Packager: updates pass-id mapping, evidence inventory, and launch-readiness snapshots.
5. Release-owner only: approve proxy fallback use, interpret disclosed proxy output, and make the final go/no-go recommendation.

### Dependency Rules

1. Stream C and Stream D now consume the promoted evidence state from closed Streams A and B.
2. Copy and assets must stay frozen to the verified current claim surface.
3. Pushing `main` remains a post-gate action, not a substitute for passing evidence.

## Owner Focus (Current)

1. Engineering: preserve the current hosted/device evidence roots and avoid reopening solved send/setup contracts without a new first-failure artifact.
2. QA: keep the promoted evidence set intact and use the preserved S22 strict-journey perf caution only as rollout context.
3. Product: execute the publication package from the promoted state and keep the proxy-derived moderation leg explicitly disclosed.
4. Marketing: execute `MKT-08` and `MKT-09`, then finalize `MKT-10` claim freeze against verified behavior only, with voice still bounded to limited-beta/closed-track language and image claims staying single-image only.
5. Performance: for any post-promotion UI/runtime hot-path changes, require clean performance-contract audits and benchmark-only evidence before widening rollout confidence.

Full owner/dependency split: `docs/operations/play-store-launch-program.md`

## Evidence Requirements (Current)

1. Active package evidence belongs in `docs/operations/evidence/wp-09/` and `docs/operations/evidence/wp-13/`.
2. Production-claim-critical WP-12 evidence remains in `docs/operations/evidence/wp-12/`.
3. Historical package detail is summarized in `docs/operations/evidence/index.md`.
4. Current local authoritative lane evidence is anchored at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`, and the current repo-side launch snapshot is `build/devctl/launch-readiness/launch-readiness-report.md`.
