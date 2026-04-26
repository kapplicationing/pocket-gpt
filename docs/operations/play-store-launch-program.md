# PocketAgent Play Store Launch Program

Last updated: 2026-04-26  
Owner: Product + Tech Lead

Mutable status stays in `docs/operations/execution-board.md`.
Program learnings stay in `docs/operations/launch-program-learnings.md`.

Current status note: launch remains `Hold`. The retained provisioning `SIGILL` class is no longer the first live failure, and the later setup/provisioning blocker was narrowed to missing multimodal projector (`mmproj`) sync in `devctl` preflight rather than a new generic native-runtime defect. That local code path is now understood and fixed. `android-instrumented` now has a current-window pass, but the active remaining blockers are still missing final hosted verdicts for corrected cloud uploads (`runtime-ready` / `model-management` / `send-after-ready`), wireless Samsung Maestro harness instability on the physical canaries before app logic begins, missing strict `journey` current-window proof, and the still-incomplete moderated WP-13 packet.

Execution policy for the active program:

1. Code and contract closure come first.
2. Cloud-hosted machine-verifiable reruns come next and are the default evidence path.
3. One narrow real-device canary is reserved for OEM/runtime confirmation and final brush, not broad discovery.
4. Human-required moderation happens only after the deterministic technical path is materially stable.

Branch/merge note for the active program:

1. `codex/launch-readiness-implementation` is the current launch integration branch and exists to keep the launch stack linear on top of local `main` while the final blockers are being cleared.
2. Local `main` is not the same thing as `origin/main` in this repo, so the merge-back plan must account for the local-main base as well as the launch commits layered above it.
3. Do not treat already-subsumed older `codex/*` branches as separate merge targets during launch closeout.
4. Once the branch is green enough for preservation, merge it back by fast-forwarding local `main` to the launch branch tip and pushing `main`, rather than replaying the stack through cherry-picks or pretending the remote already contains the local-main base.

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
2. moderated WP-13 usability evidence is complete,
3. privacy, claim, support, and release-governance work is closed,
4. the Play Store submission package is ready for rollout,
5. and the remaining physical-device canary work is reduced to final brush rather than active blocker discovery.

## Critical Path

1. Startup/readiness and multimodal packaging contract closure
2. Timeout/cancel/send reliability closure
3. Cloud-first required reruns with a clean hosted `send-after-ready` verdict
4. Authoritative `android-instrumented` and strict `journey` reruns
5. Narrow real-device canary confirmation and final brush
6. Moderated WP-13 packet
7. Release decision and Play Store submission prep

## Team Split

### Engineer 1: Runtime Setup And Packaging Owner

Own the startup/setup contract that still determines whether single-image and first-session claims are evidence-safe.

Responsibilities:

1. Keep the fixed multimodal companion sync (`mmproj`) path stable in setup/provisioning preflight and recovery flows.
2. Preserve the local startup/readiness contract so first-session setup proof stays deterministic under authoritative lane execution.
3. Pair with Engineer 2 on any setup regressions exposed by hosted/default reruns.

Exit criteria:

1. Setup/provisioning preflight remains claim-safe for single-image coverage.
2. Recovery/setup evidence no longer regresses because of companion-artifact or startup contract drift.
3. Evidence notes record the active setup contract and artifact roots rather than the old retained crash signature.

### Engineer 2: Android Runtime / Startup Reliability Owner

Own startup/readiness recovery and stale metadata healing.

Responsibilities:

1. Fix `UI-STARTUP-001` and `MODEL_ARTIFACT_CONFIG_MISSING` paths that still wedge startup or scenario coverage.
2. Make uninstall/reinstall/cache-restore behavior deterministic on the release canary path.
3. Ensure startup checks self-heal stale runtime metadata instead of requiring manual cleanup.

Exit criteria:

1. Startup/readiness paths no longer fail on stale runtime metadata.
2. Maestro startup/readiness flows are stable.
3. Reinstall/recovery behavior is deterministic on the canary device path.

### Engineer 3: Send / Timeout / Reliability Owner

Own `ENG-20` and strict journey reliability.

Responsibilities:

1. Complete timeout/cancel/send contract closure.
2. Ensure timeout maps to `UI-RUNTIME-001`.
3. Ensure send exits cleanly, preserves context, and always emits required send-capture fields.
4. Tighten unit/runtime/UI coverage around timeout, cancel, retry, and placeholder lifecycle.

Exit criteria:

1. The documented timeout/cancel contract is fully satisfied.
2. Journey send-capture fields are present in every passing and failing run.
3. Strict journey reruns are trustworthy enough for release gating.

### Engineer 4: QA Automation / Cloud Evidence Owner

Own deterministic QA evidence generation and `QA-14`.

Responsibilities:

1. Make cloud/device automation the default rerun path for machine-verifiable lanes.
2. Prove artifact parity between cloud runs and the current local/devctl evidence shape.
3. Keep one narrow physical-device canary for OEM/runtime-specific failures.
4. Align runbooks, command docs, and lane guidance so cloud is not treated as merely supplemental for deterministic reruns.

Exit criteria:

1. Cloud-backed reruns exist for `android-instrumented`, `maestro`, `journey`, `screenshot-pack`, and lifecycle smoke where applicable.
2. Artifact schema matches what `PROD-10` and WP-13 need.
3. Hosted `send-after-ready` returns a clean verdict or is explicitly tracked as infrastructure-blocked with preserved upload provenance.
4. The canary-device rule is explicit and limited.

### Engineer 5: Release Tooling / Evidence Ops Owner

Own agent-assisted triage, QA-13 operationalization, and Play Store launch-prep tooling support.

Responsibilities:

1. Operationalize the agent-assisted artifact review loop from `QA-15`.
2. Make `QA-13` a real weekly gate with journey/send-capture evidence feeding weekly QA output and WP-13 inputs.
3. Support Play Store submission readiness by validating screenshot/asset capture flow, release build repeatability, and evidence packaging for decision review.

Exit criteria:

1. One failed cloud run has been triaged end-to-end by the agent workflow.
2. Weekly send-capture gate is live and feeding current evidence.
3. Release evidence is packaged cleanly enough for PM, QA, and release review.

## Phase Plan

### Phase 1: Critical Path Stabilization

Run in parallel across Engineers 1, 2, and 3.

Deliverables:

1. `ENG-23` native provisioning crash fix candidate
2. `ENG-24` startup/readiness self-healing closure
3. `ENG-20` timeout/cancel/send reliability closure
4. QA-14 doc/tooling alignment draft
5. QA-13 plumbing and QA-15 agent-triage scaffolding

Dependency rule:

1. Required lane reruns do not count as release evidence until Engineers 1-3 are functionally stable.

### Phase 2: Evidence Restoration

Starts only when Phase 1 is stable enough for reruns.

Deliverables:

1. Fresh cloud-first `maestro`/hosted smoke verdicts where applicable, including `send-after-ready`
2. Journey send-capture output with `phase=completed` and `placeholder_visible=false`
3. Updated evidence notes with artifact roots
4. Cloud/device parity proof for deterministic lanes
5. Fresh authoritative `android-instrumented` and strict `journey` pass IDs
6. One narrow real-device canary confirmation after cloud evidence is materially stable

Dependency rule:

1. Human moderation should not start final measurement until deterministic flows are stable enough that subjective signal is not polluted by obvious technical failures.

Execution note:

1. Use hosted fan-out and agent triage to find and prove deterministic regressions first.
2. Use the physical device only for OEM/runtime-specific confirmation and the final pre-promotion brush.

### Phase 3: Human-Required Closure

Cross-functional, with engineering support on standby.

Deliverables:

1. Moderated WP-13 packet with measured values
2. `PROD-11` support-readiness closure
3. `SEC-02` privacy/claim parity closure
4. `MKT-08` proof asset package
5. `MKT-10` claim freeze
6. `PROD-13` Play Store submission package draft

Dependency rule:

1. No release-date commitment before WP-13 has measured values and claim/support readiness are closed.

### Phase 4: Launch Decision And Store Submission

This is the gate-consumption phase, not another feature phase.

Deliverables:

1. `PROD-12` machine-vs-human gate split executed from current evidence
2. `PROD-10` rerun from current evidence only
3. Final rollout recommendation and release-date decision
4. Play Store submission package ready for the chosen track

Dependency rule:

1. If required rows do not pass, publish `hold` or `iterate` with explicit blockers and no date commitment.

## Internal Contracts That Must Be Stable

1. Provisioning/startup contract: provisioning preflight must not crash, stale runtime metadata must self-heal, and single-image launch claims require claim-safe multimodal companion packaging (`mmproj`) in setup/provisioning evidence.
2. Timeout/cancel/send contract: timeout maps to `UI-RUNTIME-001`, retry preserves context, and send-capture fields are always emitted.
3. QA artifact contract: cloud/device reruns must produce the pass IDs, reports, screenshots, logcat, and runtime fields required by launch gates.
4. Gate taxonomy: machine-verifiable evidence and human-required evidence stay explicitly separated through `PROD-12`.
5. Claim boundary: retention/reset/per-tool privacy controls remain internal-only claims until explicitly implemented and verified.
6. Evidence authority split: cloud-first is the default machine-verifiable execution path, but `android-instrumented` and strict `journey` remain authoritative gate rows and are not replaced by hosted smoke alone.

## Required Output Artifacts

1. Current pass IDs for `android-instrumented`, `maestro`, and `journey`
2. Current WP-13 packet with measured values
3. Current claim-safe Play Store asset package
4. Current support and incident playbook
5. Current launch-readiness report from `bash scripts/dev/launch-readiness.sh`

## Weekly Operating Cadence

1. Start the week with `bash scripts/dev/launch-readiness.sh` and review the generated report.
2. Review board blockers against the five engineering ownership areas and keep code closure ahead of evidence expansion.
3. Run cloud-first machine-verifiable evidence before scheduling real-device confirmation or moderated sessions.
4. Keep machine-verifiable evidence and human-required evidence on separate tracks.
5. Run release-date planning only when the readiness report and current evidence both support it.
6. Before any closeout merge, confirm the launch branch still contains the intended linear stack and that no unrelated visible branch needs deliberate extraction.

## Completion Rule

The program is complete when:

1. all required `PROD-10` rows pass,
2. current-window pass IDs exist for the required lanes,
3. journey send-capture is clean,
4. WP-13 contains measured values,
5. privacy/claim/support/asset work is closed,
6. and `PROD-13` is ready for submission.
