# PocketAgent Play Store Launch Program

Last updated: 2026-05-04  
Owner: Product + Tech Lead

Mutable status stays in `docs/operations/execution-board.md`.
Program learnings stay in `docs/operations/historical/launch-program-learnings.md`.

Current status note: the controlled-MVP launch gate can now advance. The retained provisioning `SIGILL` class is no longer the live issue, the later setup/provisioning blocker was narrowed to missing multimodal projector (`mmproj`) sync in `devctl` preflight and fixed, and the last live hosted send blocker has been cleared. The freshest preserved authoritative proof is still the S906N `android-instrumented` pass at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/android-instrumented/20260503-213837/`, and the freshest physical canary is the S22 provisioning pass at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`. Wireless Samsung `maestro` remains harness-class supporting evidence only. Hosted/default coverage is now current and green on the required targeted surfaces: account 1 `send-after-ready` passes at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`, and account 2 model-management passes at `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`. Strict `journey` still preserves a current-window S22 send-capture completion at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/journey/20260503-234734/journey-report.json` with `phase=completed`, `placeholder_visible=false`, and `first_token_ms=180222`; keep that long-prefill shape as rollout/perf caution, not as a blocker. The emulator and A51 strict runs remain diagnostic-only. The disclosed `AI human-proxy` WP-13 packet now lands on `promote` for the controlled MVP. Branch-wise, local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; the remaining branch-closeout work is publication from local `main` to `origin/main`, not an internal merge between those two local refs.

Execution policy for the active program:

1. Code and contract closure come first.
2. Cloud-hosted machine-verifiable reruns come next and are the default evidence path.
3. One narrow real-device canary is reserved for OEM/runtime confirmation and final brush, not broad discovery.
4. Moderation-backed evaluation happens only after the deterministic technical path is materially stable.
5. If human moderators are temporarily unavailable, use the disclosed `AI human-proxy` operating mode to execute the same WP-13 workflow and reporting contract for the controlled MVP; keep those outputs labeled as proxy-derived rather than machine-verifiable proof.

Superpower-context canon for the active program:

1. `docs/testing/generated/launch-flow-truth.md` is the only selector/copy/runtime-state authority for automated QA flow maintenance.
2. QA agents and subagents are valid for machine-verifiable reruns, artifact inspection, blocker summaries, and the disclosed `AI human-proxy` fallback path when run through the approved bundle/setup flow.
3. `docs/architecture/android-performance-contract.md` is mandatory for UI/runtime hot-path launch work; performance evidence is measured on `benchmark` only.
4. TurboQuant remains a separate runtime/R&D track unless a specific required-lane blocker is traced to it.

Branch/merge note for the active program:

1. `codex/launch-readiness-implementation` is still the preserved name for the launch integration lineage, but local `main` is already at the same tip.
2. The live branch-hygiene gap is local `main` versus `origin/main`, not `main` versus `codex/launch-readiness-implementation`.
3. Older `codex/*` branches appear subsumed into local `main`; `cursor/cloud-agent-1775007300791-5ig66` remains intentionally separate and should not be merged wholesale into launch closeout.
4. Safe merge-back/publication now means auditing `origin/main..main`, validating the final gate state, and then pushing `main` from a clean worktree, rather than inventing another internal merge step.

## Objective

Ship the current PocketAgent MVP to the Play Store without expanding product scope beyond what is already implemented.

## Locked Launch Scope

1. Core launch surface stays locked.
2. Image attach remains in scope, but external claims stay bounded to single-image/contextual Q&A rather than broader image-analysis promises.
3. Tools stay prompt-first at launch; richer direct-tool/runtime depth is an implementation detail, not the user-facing claim surface.
4. Voice stays in scope only as a limited beta for controlled cohorts and closed-track handling; it is not part of the broad public Play Store claim set in the current launch window.
5. Setup stays simple-first: `Get ready` is the primary blocked-state setup action, while the unified `Model library` is the advanced import/download/recovery surface.

This program is complete only when:

1. required technical gates are green with current evidence,
2. moderation-backed WP-13 usability evidence is complete,
3. privacy, claim, support, and release-governance work is closed,
4. the Play Store submission package is ready for rollout,
5. and the remaining physical-device canary work is reduced to final brush rather than active blocker discovery.

## Critical Path

1. Preserve the S906N `android-instrumented` pass at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/android-instrumented/20260503-213837/`.
2. Preserve the current hosted/default targeted pass roots and the S22 physical canary substitute as the publication evidence set.
3. Preserve the current-window strict `journey` proof and treat its near-timeout first-token behavior as perf/risk context only.
4. Carry the disclosed `AI human-proxy` packet truthfully and only replace it with human-moderated evidence if promotion needs stronger non-proxy closure.
5. Finalize the Play Store submission package and decision package from the promoted evidence set.
6. Publish local `main` to `origin/main` only from a clean worktree.

## Current Evidence Anchors

1. Current local authoritative proof: `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/android-instrumented/20260503-213837/`
2. Current repo-side launch snapshot: regenerate it locally with `bash scripts/dev/launch-readiness.sh`
3. Earlier hosted/default preserved passes: `tmp/maestro-cloud-first-run/junit.xml`, `tmp/maestro-cloud-s22/junit.xml`, `tmp/maestro-cloud-session-drawer/junit.xml`, `tmp/maestro-cloud-targeted/20260503T150550Z-scenario-model-management-split-smoke-account-1/status.json`, `tmp/maestro-cloud-targeted/20260503T150759Z-scenario-model-management-split-smoke-account-2/status.json`
4. Current targeted cloud proof: account 1 `send-after-ready` passes at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`, and account 2 current model-management passes at `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`. Older account-2 runtime-ready reruns that remain `PENDING` without app launch stay classified as infra noise rather than blocker evidence.
5. Current strict `journey` authority note: `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/journey/20260503-234734/journey-report.json` remains the preserved physical strict-journey proof even though the first token only appears at `180222ms` with `runtime_status=LOADING` and `model_status_detail=Prefill...`; `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-235023/journey-report.json` remains a diagnostic prefill-stall artifact; and the A51 is still non-authoritative because `tmp/devctl-artifacts/2026-05-04/A51_TCPIP/journey/20260504-004041/` still times out in send-capture after a 180s budget with `phase=timeout`, `runtime_status=LOADING`, and `model_status_detail=Prefill...`.

## Current Execution Graph

### Stream A: Deterministic Technical Evidence

This is the critical stream.

Dependencies:

1. Uses the fixed startup/readiness and multimodal packaging path already on local `main`.
2. Must finish before final moderated measurement or publication.

Current work:

1. Preservation of the S906N `android-instrumented` pass and its artifact root
2. Preservation of publishable direct S22 physical canary evidence while wireless Maestro remains harness-class only
3. Preservation of the current hosted/default targeted pass set
4. Preservation of the current strict-journey authority already in hand, with emulator/A51 strict reruns treated as diagnostic-only unless they add new authority
5. Explicit non-pass classification on every rerun: `product defect`, `harness/bootstrap failure`, `cloud infra failure`, or `device transport failure`

### Stream B: Human-Required Usability Closure

This stream can prepare early, but should measure late.

Dependencies:

1. Packet prep can run in parallel with Stream A.
2. Final moderated or proxy runs should wait until Stream A is materially stable.

Current work:

1. WP-13 packet cleanup
2. Facilitator/script readiness
3. Carry `docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md` as the current disclosed packet until a human-moderated rerun replaces it
4. Keep older AI-moderated packets as diagnostic pre-screening evidence only; use the approved `AI human-proxy` bundle path when fallback closure is needed

### AI Human-Proxy Operating Mode

Use this mode only when human moderators are unavailable and Stream A is already materially stable enough that the session is more likely to expose workflow/comprehension issues than raw technical breakage.

This mode can close only:

1. the controlled-MVP WP-13 moderation leg,
2. disclosed packet-draft completeness checks,
3. facilitator/script ambiguity cleanup,
4. obvious UX-blocker discovery logs and issue filing,
5. and disclosed `promote`/`iterate`/`hold` recommendations for the controlled MVP.

This mode cannot close:

1. missing machine-verifiable lane evidence,
2. broader public-launch or post-MVP expansion decisions,
3. undisclosed privacy/trust/comprehension claim expansion,
4. or contradictions between proxy findings and deterministic evidence.

Future dependency:

1. Use the small-discovery-path setup tooling as a required setup dependency for `AI human-proxy` sessions so proxy review starts from the same narrow simple-first setup path instead of an ad hoc setup branch.

### Stream C: Release Governance And Package Readiness

This stream can prepare in parallel, but can only close on verified evidence.

Dependencies:

1. Can prepare in parallel with Streams A and B.
2. Must consume current evidence from Stream A and measured values from Stream B.

Current work:

1. `PROD-11`, `MKT-08`, `MKT-09`, `MKT-10`, `PROD-13`
2. Claim/copy freeze against verified behavior only
3. Launch decision memo and rollout recommendation
4. README and outward-facing copy remain bounded to offline chat, prompt-first tools, single-image Q&A, and privacy-first local behavior, with voice still limited-beta only

### Stream D: Branch Hygiene And Publication

This is governance work, not product-scope work.

Dependencies:

1. Can be audited in parallel immediately.
2. Final publication waits on Streams A-C.

Current work:

1. Keep local `main` and `codex/launch-readiness-implementation` recognized as the same launch tip.
2. Keep `cursor/cloud-agent-1775007300791-5ig66` out of the launch path unless a specific change is deliberately extracted.
3. Publish by pushing local `main` to `origin/main` after final gate review from a clean worktree.

## Parallel Agent Support Topology

### Subagent A: Cloud Run Operator

Responsibilities:

1. Own hosted reruns, upload polling, artifact capture, and retry bookkeeping.
2. Preserve upload provenance and artifact roots for current-window evidence.

### Subagent B: Failure Triage Analyst

Responsibilities:

1. Inspect screenshots, logcat, journey reports, and structured runtime outputs.
2. Classify the first failure under the canonical four-way taxonomy.

### Subagent C: Flow Truth / Docs Auditor

Responsibilities:

1. Validate automated flows against `docs/testing/generated/launch-flow-truth.md`.
2. Flag selector/copy drift and over-claiming docs before broad reruns or copy freeze.

### Subagent D: Evidence Packager

Responsibilities:

1. Update pass-id mapping, evidence inventory, and launch-readiness snapshots.
2. Keep machine-verifiable evidence ready for `PROD-10` and governance tickets.

### Release-owner only

Responsibilities:

1. Approve any use of `AI human-proxy` fallback mode.
2. Interpret the disclosed moderation-backed packet in context of machine-verifiable evidence.
3. Make the final go/no-go recommendation.
4. Keep proxy-derived decisions explicitly disclosed in governance and launch materials.

## Closeout Ownership

### Release Owner

Own the final package and publication decision.

Responsibilities:

1. Keep the promoted evidence set and publication checklist aligned.
2. Approve the exact release bundle, track, and claim-safe listing package.
3. Publish only from a clean worktree after reviewing `origin/main..main`.

### QA / Evidence Owner

Own preservation of the promoted evidence set.

Responsibilities:

1. Keep the preserved `android-instrumented`, hosted/default targeted cloud, S22 physical canary, and strict `journey` artifacts referenced consistently.
2. Run targeted rechecks only if a new blocker appears; classify any new non-pass under the four-way taxonomy.
3. Keep `bash scripts/dev/launch-readiness.sh` and `PROD-10` aligned to the same evidence roots.

### Product / Marketing Owner

Own copy, assets, and package readiness.

Responsibilities:

1. Apply only the frozen claim set to listing copy and proof assets.
2. Close `MKT-08`, `MKT-10`, and `PROD-13` from the promoted evidence state.
3. Keep any broader public-launch or non-proxy expansion work out of this closeout.

### Support / Operations Owner

Own support-readiness and rollout notes.

Responsibilities:

1. Close `PROD-11` with the current contact path, support metadata, and rollout/incident notes.
2. Record the chosen track, release identifiers, and rollback plan.
3. Keep operator-facing closeout steps in `docs/operations/publication-closeout-checklist.md`.

## Closeout Phases

### Phase 1: Preserve The Promoted State

1. Keep the existing evidence roots, packet, and claim map stable.
2. Do not reopen solved launch-gate rows without a new first-failure artifact.
3. Keep wireless Samsung Maestro harness-class only.

### Phase 2: Prepare The Publication Package

1. Finalize support metadata, release versioning, rollout notes, and claim-safe listing assets.
2. Keep release copy, screenshots, and videos tied to the frozen claim set.
3. Record the final package inputs in the submission checklist.

### Phase 3: Publish Cleanly

1. Confirm `bash scripts/dev/launch-readiness.sh` still reports `Promote`.
2. Clean the worktree or isolate non-publication work before pushing `main`.
3. Push `main`, upload the selected release bundle, and publish to the chosen Play track.

## Internal Contracts That Must Be Stable

1. Provisioning/startup contract: provisioning preflight must not crash, stale runtime metadata must self-heal, and single-image launch claims require claim-safe multimodal companion packaging (`mmproj`) in setup/provisioning evidence.
2. Timeout/cancel/send contract: timeout maps to `UI-RUNTIME-001`, retry preserves context, and send-capture fields are always emitted.
3. QA artifact contract: cloud/device reruns must produce the pass IDs, reports, screenshots, logcat, and runtime fields required by launch gates.
4. Gate taxonomy: machine-verifiable evidence and moderation-backed evidence stay explicitly separated through `PROD-12`.
5. Claim boundary: retention/reset/per-tool privacy controls remain internal-only claims until explicitly implemented and verified.
6. Evidence authority split: cloud-first is the default machine-verifiable execution path, but `android-instrumented`, current hosted/default targeted proofs, and strict `journey` remain the retained gate roots and are not replaced by unrelated smoke alone.
7. Older AI-moderated outputs remain deterministic QA support only; disclosed `AI human-proxy` outputs may satisfy the moderation-backed WP-13 leg for the controlled MVP when they use the approved bundle/setup path.
8. Performance-contract audits and benchmark-only evidence are part of launch-readiness expectations for risky UI/runtime hot-path changes.
9. TurboQuant remains outside the launch critical path unless a concrete launch blocker is traced to it.

## Required Output Artifacts

1. Current evidence IDs for `android-instrumented`, hosted/default targeted cloud coverage, and `journey`, plus direct S22 physical canary artifacts while wireless Maestro remains harness-class
2. Current WP-13 packet with measured values
3. Current claim-safe Play Store asset package
4. Current support and incident playbook
5. Current launch-readiness report from `bash scripts/dev/launch-readiness.sh`

## Weekly Operating Cadence

1. Start the week with `bash scripts/dev/launch-readiness.sh` and review the generated report.
2. Review board blockers against the closeout ownership areas and keep publication/package work separate from new engineering work.
3. Run cloud-first machine-verifiable evidence only when a new blocker appears; otherwise preserve the promoted artifact roots.
4. Keep machine-verifiable evidence and moderation-backed evidence on separate tracks.
5. Before publishing `main`, confirm the publication checklist is closed, audit `origin/main..main`, and verify that no unrelated visible branch needs deliberate extraction.

## Completion Rule

The program is complete when:

1. all required `PROD-10` rows pass,
2. current-window pass IDs exist for the required lanes,
3. journey send-capture is clean,
4. WP-13 contains measured values,
5. privacy/claim/support/asset work is closed,
6. `PROD-13` is ready for submission,
7. and the chosen publication worktree is clean enough to push intentionally.
