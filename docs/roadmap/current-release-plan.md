# Current Release Plan

Last updated: 2026-05-04

This is the single current-release planning document.

Mutable status is tracked only in `docs/operations/execution-board.md`.

## Objective

Ship the controlled Play Store MVP for PocketAgent with complete machine-verifiable evidence, moderation-backed usability evidence, and claim-safe release/governance closure.

## Baseline

1. Foundational package work through WP-12 is closed; WP-09 and WP-13 drive current release risk.
2. Build policy is single-build with download manager enabled by default.
3. The controlled-MVP gate is now promoted from current evidence; the remaining work is publication/package execution, not launch-evidence unblock work.
4. Local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; the remaining branch-closeout step is publication from local `main` to `origin/main`, not an internal merge between those two local refs.
5. The freshest local authoritative evidence currently retained in the repo workspace is the S906N `android-instrumented` artifact root at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/android-instrumented/20260503-213837/`, plus the fresh S22 physical provisioning canary at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`. Regenerate the repo-side program snapshot with `bash scripts/dev/launch-readiness.sh` when you need a current publication decision view. Hosted/default evidence is now current and green on the required targeted surfaces: account 1 `send-after-ready` passes at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`, and account 2 model-management passes at `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`. Strict `journey` still has a preserved current-window S22 pass at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/journey/20260503-234734/`, with the caveat that the first token lands only at the 180s boundary and still carries `Prefill...`/slow-state context, while the A51 remains diagnostic-only at `tmp/devctl-artifacts/2026-05-04/A51_TCPIP/journey/20260504-004041/`. The disclosed `AI human-proxy` packet now also lands on `promote` for the controlled MVP.
6. `docs/testing/generated/launch-flow-truth.md` is the selector/copy/runtime-state authority for automated QA flow maintenance and agent-assisted deterministic QA tooling.
7. AI/agent outputs are valid for machine-verifiable reruns, artifact inspection, and failure classification.
8. If human moderators are unavailable, a disclosed `AI human-proxy` operating mode may close the controlled-MVP moderation leg by using the same workflow script, setup bundle, and reporting utilities as human moderation.
9. `docs/architecture/android-performance-contract.md` is mandatory for UI/runtime hot-path launch work; benchmark-only evidence is required when risky hot paths move.

## Current Execution Graph

### Stream A: Deterministic Technical Evidence

Goal:

Restore and preserve current-window machine-verifiable evidence.

Current work:

1. Preserve the current authoritative `android-instrumented` pass.
2. Preserve the publishable current-window direct S22 physical canary evidence while wireless Maestro remains harness-class only.
3. Preserve the now-green hosted/default send and model-management pass roots.
4. Preserve the current-window S22 strict-journey pass and treat emulator/A51 strict reruns as diagnostic-only unless they add new authority.
5. Keep the Samsung canary narrow and supporting only.
6. Keep one primary non-pass classification on any future rerun: `product defect`, `harness/bootstrap failure`, `cloud infra failure`, or `device transport failure`.

Dependency rule:

1. This is the critical stream.
2. Final human measurement and publication wait on this stream.
3. For the controlled MVP, this stream is now closed and preserved; future work is evidence preservation, not blocker discovery.

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
4. For the controlled MVP, this stream is now closed by the disclosed promote-level proxy packet.

### Stream C: Release Governance And Submission Prep

Goal:

Prepare the decision package and store package without widening scope.

Current work:

1. `PROD-11`, `MKT-08`, `MKT-09`, `MKT-10`, `PROD-13`
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
4. Audit `origin/main..main` and publish `main` only after the publication checklist is intentionally closed from a clean worktree.

Dependency rule:

1. Audit can run in parallel immediately.
2. Final publication waits on Streams A-C.

## Release Phases

### Phase 1: Preserve the promoted evidence set

1. Preserve the existing `android-instrumented` proof.
2. Preserve the direct S22 physical canary and the current hosted/default targeted pass set.
3. Keep the Samsung canary as supporting OEM/runtime confirmation only.

### Phase 2: Finish publication and governance closure

1. Carry the current `AI human-proxy` packet as the closed moderation-backed leg for the controlled MVP.
2. Close support/readiness, listing assets, claim application, and submission-package steps against verified behavior only.
3. Keep any broader public-launch or non-proxy expansion work out of this closeout phase.

### Phase 3: Run the release decision and publish

1. Publish the already-promoted controlled-MVP decision package and operator checklist.
2. Push local `main` to `origin/main` only when the working tree is clean and the intended publish payload is explicit.
3. Upload the final release package to the selected Play track only after the checklist is intentionally closed.

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

1. Preserve the `AI human-proxy` fallback bundle/setup path and the current promote-level packet.
2. Execute `PROD-11`, `MKT-08`, `MKT-09`, `MKT-10`, and `PROD-13` against the promoted evidence set.
3. Capture/finalize claim-safe listing assets and support/publication paperwork.
4. Publish the controlled-MVP decision package.
5. Push `main` only after the publication checklist is intentionally closed and the worktree is clean.
