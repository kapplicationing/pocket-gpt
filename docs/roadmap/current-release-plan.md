# Current Release Plan

Last updated: 2026-05-03

This is the single current-release planning document.

Mutable status is tracked only in `docs/operations/execution-board.md`.

## Objective

Ship the controlled Play Store MVP for PocketAgent with complete machine-verifiable evidence, moderation-backed usability evidence, and claim-safe release/governance closure.

## Baseline

1. Foundational package work through WP-12 is closed; WP-09 and WP-13 drive current release risk.
2. Build policy is single-build with download manager enabled by default.
3. Promotion beyond pilot remains blocked until required WP-13 usability and launch-gate evidence is complete.
4. Local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; the remaining branch-closeout step is publication from local `main` to `origin/main`, not an internal merge between those two local refs.
5. The freshest local authoritative evidence currently retained in the repo workspace is the S906N `android-instrumented` artifact root at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`, plus the fresh S22 physical provisioning canary at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`. The repo-side program snapshot remains `build/devctl/launch-readiness/launch-readiness-report.md`. Earlier hosted/default preserved passes still exist for `first-run`, `gpu`, and `session-drawer`. The current launched hosted/default blocker story is now split rather than converged: account 1 runtime-ready now passes at `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-1-meteredfix/upload-status.json`, account 1 `send-after-ready` now fails later at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account-1-current/status.json` with `Assertion is false: id: message_bubble_assistant_complete is visible`, and account 2 current model-management still fails at `tmp/maestro-cloud-targeted/20260504T025141Z-account-2-model-management-fresh/upload-status.json` with `Assertion is false: id: session_drawer_button is visible`. The fresh account-2 runtime-ready reruns at `tmp/maestro-cloud-targeted/20260504T0045Z-scenario-runtime-ready-smoke-account-2-rerun/`, `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-2-meteredfix/`, and `tmp/maestro-cloud-targeted/20260504T013500Z-scenario-runtime-ready-smoke-account-2-classify/` remain `PENDING` without app launch and therefore stay cloud-infra noise rather than new app verdicts. Strict `journey` still has a preserved current-window S22 pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/journey/20260503-234734/`, with the caveat that the first token lands only at the 180s boundary and still carries `Prefill...`/slow-state context, while the A51 remains diagnostic-only at `tmp/devctl-artifacts/2026-05-04/192.168.1.44:37643/journey/20260504-004041/`.
6. `docs/testing/generated/launch-flow-truth.md` is the selector/copy/runtime-state authority for automated QA flow maintenance and agent-assisted deterministic QA tooling.
7. AI/agent outputs are valid for machine-verifiable reruns, artifact inspection, and failure classification.
8. If human moderators are unavailable, a disclosed `AI human-proxy` operating mode may close the controlled-MVP moderation leg by using the same workflow script, setup bundle, and reporting utilities as human moderation.
9. `docs/architecture/android-performance-contract.md` is mandatory for UI/runtime hot-path launch work; benchmark-only evidence is required when risky hot paths move.
10. TurboQuant remains outside the launch critical path unless a specific required-lane blocker is traced to it.

## Current Execution Graph

### Stream A: Deterministic Technical Evidence

Goal:

Restore and preserve current-window machine-verifiable evidence.

Current work:

1. Preserve the current authoritative `android-instrumented` pass.
2. Preserve or rerun publishable current-window direct S22 physical canary evidence while wireless Maestro remains harness-class only.
3. Close the current targeted hosted/default failure set rather than teaching `send-after-ready` as the only live cloud gap.
4. Preserve the current-window S22 strict-journey pass and treat emulator/A51 strict reruns as diagnostic-only unless they add new authority.
5. Keep the Samsung canary narrow and last.
6. Require one primary non-pass classification on every rerun: `product defect`, `harness/bootstrap failure`, `cloud infra failure`, or `device transport failure`.

Dependency rule:

1. This is the critical stream.
2. Final human measurement and publication wait on this stream.

### Stream B: Human-Required Usability Closure

Goal:

Collect the minimum moderation-backed evidence that automation cannot replace.

Current work:

1. Prepare the WP-13 packet, facilitator materials, and metric templates.
2. Run final moderated or proxy collection only after Stream A is materially stable.
3. Treat older AI-moderated packets as pre-screening evidence only; use the approved `AI human-proxy` bundle when fallback closure is needed.
4. If human moderators are unavailable, allow `AI human-proxy` fallback closure for the controlled MVP only when the session is disclosed, the bundle/setup path is used, and current machine-verifiable evidence is already in hand.

Dependency rule:

1. Prep can run in parallel with Stream A.
2. Final measurement should not run while deterministic failures still dominate the experience.
3. `AI human-proxy` sessions depend on the small-discovery-path setup bundle before their outputs are treated as comparable moderation-backed input.

### Stream C: Release Governance And Submission Prep

Goal:

Prepare the decision package and store package without widening scope.

Current work:

1. `PROD-12`, `PROD-11`, `SEC-02`, `MKT-08`, `MKT-10`, `PROD-13`
2. Claim/copy freeze against verified behavior only
3. Rollout and submission package prep
4. Keep README and any outward-facing copy constrained to offline chat, prompt-first tools, single-image Q&A, and privacy-first local behavior, with voice still limited-beta only.

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
2. Close the direct S22 physical canary and the current hosted/default targeted failure set while preserving the current strict-journey authority already in hand.
3. Use the Samsung canary only as the final OEM/runtime confirmation path.

### Phase 2: Finish moderation-backed and governance closure

1. Complete WP-13 measured values through `human-moderated` or disclosed `AI human-proxy` fallback execution.
2. If moderators are temporarily unavailable, use a disclosed `AI human-proxy` packet to close the controlled-MVP moderation leg while keeping the decision record explicitly labeled as proxy-derived.
3. Close privacy/claim/support/submission readiness against verified behavior only.

### Phase 3: Run the release decision and publish

1. Re-run the launch-gate matrix from current evidence.
2. Publish the promote/hold decision.
3. Push local `main` to `origin/main` only if the decision is to advance and the working tree is clean.

## Required pre-promotion signals

1. Unit and required device lanes pass.
2. Latest evidence IDs exist for `android-instrumented`, the current hosted/default targeted cloud surface, and `journey`, plus direct S22 physical canary artifacts while wireless Maestro remains harness-class.
3. WP-13 usability packet has measured values (no placeholders).
4. If the packet is `AI human-proxy`, it is explicitly disclosed as proxy-derived and tied to the approved bundle/setup path.
5. No open high-severity UX blockers in required launch workflows.
6. Claim rows in launch-gate matrix map to evidence IDs and remain privacy-compliant.
7. For risky UI/runtime hot-path changes, performance-contract audits are clean and benchmark-variant evidence is captured.

## Interfaces

- Active status board: `docs/operations/execution-board.md`
- Active ticket specs: `docs/operations/tickets/`
- Evidence inventory and retention policy: `docs/operations/evidence/index.md`
- Launch policy + decision artifacts: `docs/operations/tickets/prod-09-soft-gate-pilot-policy.md`, `docs/operations/tickets/prod-10-launch-gate-matrix.md`
- Full launch execution split: `docs/operations/play-store-launch-program.md`

## Roadmap From Here

1. Validate and preserve the `AI human-proxy` fallback bundle/setup path.
2. Close the remaining direct S22 physical canary and the current hosted/default targeted failure set while preserving the strict-journey authority already in hand.
3. Carry the disclosed `AI human-proxy` packet truthfully, or replace it with human-moderated evidence if that becomes available.
4. Close governance and submission tickets against the updated evidence set.
5. Re-run `PROD-10`, publish the controlled-MVP decision, and push `main` only if the hold clears.
