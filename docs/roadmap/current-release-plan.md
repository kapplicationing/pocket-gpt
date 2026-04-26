# Current Release Plan

Last updated: 2026-04-26

This is the single current-release planning document.

Mutable status is tracked only in `docs/operations/execution-board.md`.

## Objective

Ship a usable privacy-first Android MVP under soft-gate pilot policy with complete UX-quality and promotion evidence.

## Baseline

1. Foundational package work through WP-12 is closed; WP-09 and WP-13 drive current release risk.
2. Build policy is single-build with download manager enabled by default.
3. Promotion beyond pilot remains blocked until required WP-13 usability and launch-gate evidence is complete.
4. Local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; the remaining branch-closeout step is publication from local `main` to `origin/main`, not an internal merge between those two local refs.
5. The freshest local authoritative evidence currently retained in the repo workspace is the `android-instrumented` artifact root at `tmp/devctl-artifacts/2026-04-26/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260426-142357/`, while the repo-side program snapshot remains `build/devctl/launch-readiness/launch-readiness-report.md`.

## Current Execution Graph

### Stream A: Deterministic Technical Evidence

Goal:

Restore and preserve current-window machine-verifiable evidence.

Current work:

1. Preserve the current authoritative `android-instrumented` pass.
2. Close cloud/default `runtime-ready` / `model-management` / `send-after-ready`.
3. Close strict `journey`.
4. Keep the Samsung canary narrow and last.

Dependency rule:

1. This is the critical stream.
2. Final human measurement and publication wait on this stream.

### Stream B: Human-Required Usability Closure

Goal:

Collect the minimum moderated evidence that automation cannot replace.

Current work:

1. Prepare the WP-13 packet, facilitator materials, and metric templates.
2. Run final moderated collection only after Stream A is materially stable.

Dependency rule:

1. Prep can run in parallel with Stream A.
2. Final measurement should not run while deterministic failures still dominate the experience.

### Stream C: Release Governance And Submission Prep

Goal:

Prepare the decision package and store package without widening scope.

Current work:

1. `PROD-12`, `PROD-11`, `SEC-02`, `MKT-08`, `MKT-10`, `PROD-13`
2. Claim/copy freeze against verified behavior only
3. Rollout and submission package prep

Dependency rule:

1. Prep can run in parallel with Streams A and B.
2. Final closeout depends on current evidence from Stream A and measured values from Stream B.

### Stream D: Branch Hygiene And Publication

Goal:

Keep the launch stack publication-safe without inventing missing merge work.

Current work:

1. Recognize that local `main` and `codex/launch-readiness-implementation` already match.
2. Keep already-subsumed older `codex/*` branches out of the closeout discussion.
3. Keep `cursor/cloud-agent-1775007300791-5ig66` separate unless a specific change is deliberately extracted.
4. Audit `origin/main..main` and publish `main` only after the launch hold is cleared.

Dependency rule:

1. Audit can run in parallel immediately.
2. Final publication waits on Streams A-C.

## Release Phases

### Phase 1: Finish deterministic machine evidence

1. Preserve the existing `android-instrumented` proof.
2. Close hosted/default machine evidence and strict `journey`.
3. Use the Samsung canary only as the final OEM/runtime confirmation path.

### Phase 2: Finish human-required and governance closure

1. Complete WP-13 measured values.
2. Close privacy/claim/support/submission readiness against verified behavior only.

### Phase 3: Run the release decision and publish

1. Re-run the launch-gate matrix from current evidence.
2. Publish the promote/hold decision.
3. Push local `main` to `origin/main` only if the decision is to advance and the working tree is clean.

## Required pre-promotion signals

1. Unit and required device lanes pass.
2. Latest lane pass IDs exist for `android-instrumented`, `maestro`, and `journey`.
3. WP-13 usability packet has measured values (no placeholders).
4. No open high-severity UX blockers in required launch workflows.
5. Claim rows in launch-gate matrix map to evidence IDs and remain privacy-compliant.

## Interfaces

- Active status board: `docs/operations/execution-board.md`
- Active ticket specs: `docs/operations/tickets/`
- Evidence inventory and retention policy: `docs/operations/evidence/index.md`
- Launch policy + decision artifacts: `docs/operations/tickets/prod-09-soft-gate-pilot-policy.md`, `docs/operations/tickets/prod-10-launch-gate-matrix.md`
- Full launch execution split: `docs/operations/play-store-launch-program.md`
