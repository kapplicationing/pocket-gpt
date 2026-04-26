# Execution Board

Last updated: 2026-04-26

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

- [ ] QA-11 rerun preservation/closure for `android-instrumented` + `maestro` + strict `journey` (`--mode strict --repeats 3`) is still blocked on incomplete current-window machine evidence. `android-instrumented` now has a current-window pass and should be preserved from `tmp/devctl-artifacts/2026-04-26/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260426-142357/` as the local authoritative proof already in hand, but local wireless Samsung `maestro` still fails in the Maestro gRPC/bootstrap layer before app logic begins, the latest hosted `send-after-ready` uploads (`mupload_01kq4fc793fdathk37tk97jkmd`, `mupload_01kq4fq5hpf6f9m53fm7pq8ew0`) are accepted by Maestro Cloud but remain `PENDING` without hosted verdicts, and the corrected strict `journey` kickoff still fails in the same `localhost:7001` Maestro bootstrap layer before app logic begins.
- [ ] QA-WP13-RUN02 moderated 5-user usability run packet completion blocked pending required-lane reliability rerun
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
- [x] ENG-22 provisioning startup-check lane blocker closed (`docs/operations/tickets/eng-22-provisioning-startup-check-lane-blocker.md`)
- [x] ENG-23 host-side native runtime provisioning `SIGILL` mitigation landed; the retained provisioning crash is no longer the live blocker in the launch program
- [x] ENG-24 startup/readiness metadata self-healing landed; the later setup/provisioning gap was narrowed to missing multimodal companion (`mmproj`) sync in `devctl` preflight and fixed in local code
- [x] ENG-20 host-side runtime cancel/timeout contract coverage landed; strict journey proof still depends on clearing the runtime-ready blocker in `QA-11`
- [x] Local authoritative onboarding proof is now represented in `android-instrumented` coverage through `MainActivityAuthoritativeOnboardingInstrumentationTest`; launch still remains blocked on current-window pass evidence, not on missing onboarding contract coverage
- [x] Current-window `android-instrumented` rerun passed on the S906N canary; the remaining technical evidence gap is `maestro` / strict `journey`, not authoritative onboarding coverage
- [x] Local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; branch-closeout risk is now publication to `origin/main`, not an internal merge between those two local refs
- [x] WP-12 package closeout complete

## Critical Path And Parallel Streams

### Critical Path

1. Preserve the current authoritative `android-instrumented` pass as the local proof already in hand.
2. Close the remaining machine-verifiable gap through hosted/default `runtime-ready` / `model-management` / `send-after-ready` verdicts and strict `journey` current-window proof.
3. Use the narrow Samsung canary only for final OEM/runtime confirmation once the cloud/default path is materially stable.
4. Complete the moderated WP-13 packet after the deterministic technical path is stable enough to measure real usability signal.
5. Re-run `PROD-10` from current evidence, then decide whether local `main` can be published to `origin/main`.

### Parallel Streams

1. Stream A, critical: hosted/default machine-verifiable evidence closure (`QA-11`, `QA-14`, `QA-15`, `QA-13`).
2. Stream B, parallel prep only: moderated WP-13 packet prep, facilitator packet cleanup, and metric templates.
3. Stream C, parallel prep only: support/privacy/claim/package readiness (`PROD-11`, `SEC-02`, `MKT-08`, `MKT-10`, `PROD-13`).
4. Stream D, governance: branch/publication hygiene. Audit `origin/main..main`, keep `cursor/cloud-agent-1775007300791-5ig66` out of the launch closeout path unless a specific change is deliberately extracted, and publish `main` only after the launch hold is cleared.

### Dependency Rules

1. Stream A gates every other stream's final closeout value.
2. Stream B can prepare in parallel, but should not run final measurement until Stream A is materially stable.
3. Stream C can prepare in parallel, but must freeze copy and assets only against verified current evidence.
4. Stream D can be audited in parallel at any time, but pushing `main` is a post-gate action, not a substitute for passing evidence.

## Owner Focus (Current)

1. Engineering: keep product-code changes bounded to defects exposed by current lane artifacts; the live launch gap is now evidence closure, not a known missing feature contract.
2. QA: run cloud-first machine-verifiable evidence through `QA-11`, `QA-14`, `QA-13`, and `QA-15`, with special focus on getting a clean hosted `send-after-ready` verdict before widening physical-device effort.
3. Product: keep machine-verifiable evidence separate from human-required closure, close `PROD-12`, `PROD-11`, and `PROD-13`, then run the `PROD-10` decision flow and only then authorize publication of local `main`.
4. Marketing: execute `MKT-08` and `MKT-09`, then finalize `MKT-10` claim freeze against verified behavior only, with voice still bounded to limited-beta/closed-track language and image claims staying single-image only.

Full owner/dependency split: `docs/operations/play-store-launch-program.md`

## Evidence Requirements (Current)

1. Active package evidence belongs in `docs/operations/evidence/wp-09/` and `docs/operations/evidence/wp-13/`.
2. Production-claim-critical WP-12 evidence remains in `docs/operations/evidence/wp-12/`.
3. Historical package detail is summarized in `docs/operations/evidence/index.md`.
4. Current local authoritative lane evidence is anchored at `tmp/devctl-artifacts/2026-04-26/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260426-142357/`, and the current repo-side launch snapshot is `build/devctl/launch-readiness/launch-readiness-report.md`.
