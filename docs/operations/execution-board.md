# Execution Board

Last updated: 2026-07-11

This is the single mutable board for planning and delivery. Ticket acceptance
criteria live in `docs/operations/tickets/`; fixed evidence baselines live in
`docs/operations/evidence/evidence-ledger.json`.

## Program Standard

1. Completion requires acceptance criteria, proof, and evidence links.
2. A promoted product gate and a publishable Play Store package are separate
   states.
3. Regressions block scope expansion.
4. Verify live branch/CI/device state at execution time; do not copy moving
   status into multiple documents.

## Status Legend

- `Done`: acceptance criteria and required evidence are complete
- `In Progress`: currently being executed
- `Ready`: unblocked and queued
- `Blocked`: cannot proceed until the named dependency changes
- `Backlog`: not started and not yet ready

## Current Status Snapshot

The 2026-07-11 launch-readiness snapshot reports:

1. Controlled-MVP gate: `promoted`
2. `PROD-10`: `Promote`
3. Required rows: `8/8 PASS`
4. Publication readiness: `blocked`
5. Explicit board blockers: Play generative-AI policy controls, final asset capture/approval, and signed Play package execution

The July Hugging Face/CI stack and the July 11 model-import/runtime-ownership
hardening are published on `main` and `origin/main`. The latter adds
cancellable/durable import transactions, model and bounded GGUF validation,
serialized runtime operation ownership, cancellation/close recovery, Android
cloud-backup and Android 12+ transfer exclusions, and interaction-performance
gates. The release-closeout
payload is isolated on `codex/release-closeout` for review; it does not include
the engineer-owned runtime/CI worktree changes. The open path is that review,
then Play Store package and rollout execution.

Generate the current repository decision with:

`bash scripts/dev/launch-readiness.sh`

## Work Packages

| ID | Work package | Status | Current boundary |
|---|---|---|---|
| WP-00 .. WP-08 | Foundation through launch preparation | Done | Historical summaries remain in the evidence index |
| WP-09 | Distribution plan and beta operations | In Progress | Closes only after controlled publication and operator follow-through |
| WP-10 | Voice limited-beta rail | Ready | Closed-track only; excluded from broad public claims |
| WP-11 | Android MVP UX | Done | Controlled-MVP UX gate closed |
| WP-12 | Production runtime | Done | Includes July 11 import/runtime hardening on `main` |
| WP-13 | UX quality closure | Done | Controlled-MVP leg closed by the disclosed promote-level AI human-proxy packet; human moderation remains an expansion follow-up |

## Current Sprint Board

### In Progress

- [ ] WP-09 distribution plan and beta operations execution
- [ ] MKT-10 apply the frozen claims to approved assets and the first live scorecard
- [ ] UX-13 direct timeout-to-retry acceptance closure

### Blocked

- [ ] PROD-14 in-app AI-output reporting plus restricted-content prevention — needs an approved developer-controlled intake, privacy/Data Safety contract, and implemented/tested product controls
- [ ] MKT-08 final screenshots/video and Product + Marketing approval — needs a connected current Android canary with the release candidate installed
- [ ] PROD-13 signed bundle, physical install proof, and Play upload — needs the existing upload key, a connected physical canary, and an enrolled Play developer account with two-step verification

### Ready

- [ ] MKT-09 first 7-day channel scorecard after rollout (non-blocking for initial controlled publication)

### Done (Recent)

- [x] QA-13 now has one required weekly send-capture wrapper, Monday hardware schedule, retained packet/delta contract, fail-closed runner handling, and deduplicated issue routing with automatic stale-blocker closure.
- [x] PROD-11 now has a public support path, private contact, incident/SLA rules, weekly rollout template, and claim/rollout pause rules.
- [x] PocketAgent is now the canonical customer-facing name in app strings, listing copy, policy/support pages, and release metadata; technical `pocketgpt` identifiers remain compatible.
- [x] Release identity, internal-track plan, reproducible signing/build contract, Play metadata worksheet, claim-safe copy, adaptive launcher icon, and draft store brand graphics are prepared in-repo.
- [x] July 11 model-import/runtime-ownership hardening landed on `main` and `origin/main` with focused tests and CI wiring.
- [x] Dynamic Hugging Face discovery/import and CI stabilization landed through PRs #4-#9.
- [x] July CI, CodeQL, nightly, and hardware-runner baselines were recorded in the evidence ledger.
- [x] Branch/publication hygiene closed for the July engineering baseline; the new release-closeout payload is isolated on `codex/release-closeout` for review.
- [x] `PROD-10` required rows reached `8/8 PASS` and the decision advanced to `Promote`.
- [x] The disclosed AI human-proxy WP-13 packet reached a `promote` recommendation for the controlled MVP.
- [x] `SEC-02` closed privacy parity for verified external claims; partial `P-04` controls remain internal-only.
- [x] Required hosted/default send and model-management proof, S906N Android instrumentation, S22 physical canary, and strict journey authority were preserved.
- [x] Cloud-first evidence and agent-assisted first-failure triage were operationalized for the controlled-MVP decision.
- [x] Android hot-path launch work gained mandatory performance-contract audits and benchmark-only evidence rules.

## Publication Critical Path

1. Close `PROD-14` with in-app reporting that reaches the developer without
   leaving the app, plus evidenced restricted-content prevention and the
   matching privacy/Data Safety contract.
2. Close `UX-13` with a green direct timeout-to-retry contract without losing
   session context.
3. Apply the frozen `MKT-10` copy blocks to the final approved assets.
4. Close `MKT-08` with approved current UI screenshots/video; approve the
   prepared icon and feature graphic.
5. Close `PROD-13` with final versioning, selected Play track, reproducible
   bundle, canary install proof, and artifact identifiers.
6. Complete developer enrollment, privacy policy, Data Safety, content rating,
   category, support, and
   rollout metadata in Play Console.
7. Regenerate launch readiness and publish only if it still reports `Promote`
   with no required row regression.

`MKT-09` starts after the real cohort launches and informs expansion. It does
not block the initial controlled-MVP publication.

## Owner Focus

1. QA: monitor the weekly send-capture gate and preserve fixed evidence roots.
2. Product/Release Ops: provide signing/account access, complete live Play
   declarations, and record the final bundle/install identifiers.
3. Marketing: capture and approve only claim-safe current assets.
4. Engineering: close the isolated UX-13 retry defect; avoid unrelated release
   scope.
5. Product Research: run the prepared seven-day scorecard after rollout and
   plan human-moderated evidence before broader non-proxy
   expansion.

## Evidence Rules

1. Fixed CI/device/program baselines belong in the evidence ledger and evidence
   index.
2. Live CI state comes from GitHub Actions.
3. New targeted runs must retain wrapper-generated status and first-failure
   artifacts.
4. A new non-pass gets one classification: `product defect`,
   `harness/bootstrap failure`, `cloud infra failure`, or
   `device transport failure`.
5. Preserve the promoted set unless a fresh artifact proves a regression; do
   not rerun broad lanes for reassurance alone.

## Source Links

- Current plan: `docs/roadmap/current-release-plan.md`
- Active tickets: `docs/operations/tickets/`
- Evidence ledger: `docs/operations/evidence/evidence-ledger.json`
- Evidence index: `docs/operations/evidence/index.md`
- Testing strategy: `docs/testing/test-strategy.md`
- Short commands: `docs/testing/runbooks.md`
- Program learnings: `docs/operations/historical/launch-program-learnings.md`
