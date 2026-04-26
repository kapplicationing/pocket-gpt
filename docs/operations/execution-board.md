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

- [ ] QA-11 rerun `android-instrumented` + `maestro` + strict `journey` (`--mode strict --repeats 3`) is now blocked on the Maestro/runtime-ready contract. The active rerun path has moved past provisioning preflight and onboarding selector drift, but lifecycle/bootstrap flows can still leave the app `Unloaded` before first-send evidence starts. Required lane pass IDs are still missing until that runtime-ready path is green.
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
- [x] ENG-23 host-side native runtime provisioning `SIGILL` mitigation landed; active device rerun has moved past provisioning preflight, with final lane proof still pending under `QA-11`
- [x] ENG-24 startup/readiness metadata self-healing landed; next live blocker is the Maestro/runtime-ready `Unloaded` path in `QA-11`
- [x] ENG-20 host-side runtime cancel/timeout contract coverage landed; strict journey proof still depends on clearing the runtime-ready blocker in `QA-11`
- [x] WP-12 package closeout complete

## Owner Focus (Current)

1. Engineering: clear the runtime-ready `Unloaded` blocker in `QA-11`, then stop changing product code unless a lane artifact exposes a real remaining defect.
2. QA: run cloud-first machine-verifiable evidence through `QA-11`, `QA-14`, `QA-13`, and `QA-15`, then use one physical-device canary as the final brush.
3. Product: keep machine-verifiable evidence separate from human-required closure, close `PROD-12`, `PROD-11`, and `PROD-13`, then run the `PROD-10` decision flow.
4. Marketing: execute `MKT-08` and `MKT-09`, then finalize `MKT-10` claim freeze against verified behavior only.

Full owner/dependency split: `docs/operations/play-store-launch-program.md`

## Evidence Requirements (Current)

1. Active package evidence belongs in `docs/operations/evidence/wp-09/` and `docs/operations/evidence/wp-13/`.
2. Production-claim-critical WP-12 evidence remains in `docs/operations/evidence/wp-12/`.
3. Historical package detail is summarized in `docs/operations/evidence/index.md`.
