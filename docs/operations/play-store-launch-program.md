# PocketAgent Play Store Launch Program

Last updated: 2026-05-03  
Owner: Product + Tech Lead

Mutable status stays in `docs/operations/execution-board.md`.
Program learnings stay in `docs/operations/launch-program-learnings.md`.

Current status note: launch remains `Hold`. The retained provisioning `SIGILL` class is no longer the first live failure, and the later setup/provisioning blocker was narrowed to missing multimodal projector (`mmproj`) sync in `devctl` preflight rather than a new generic native-runtime defect; that local code path is now understood and fixed. The freshest preserved authoritative proof is still the S906N `android-instrumented` pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`, and the freshest physical canary is now the S22 provisioning pass at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`. Wireless Samsung `maestro` remains harness-class supporting evidence only. The launched hosted/default surface is now split rather than converged: account 1 runtime-ready now passes at `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-1-meteredfix/upload-status.json`, account 1 `send-after-ready` fails later at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account-1-current/upload-status.json` with `Assertion is false: id: message_bubble_assistant_complete is visible`, and account 2 current model-management still fails at `tmp/maestro-cloud-targeted/20260504T025141Z-account-2-model-management-fresh/upload-status.json` with `Assertion is false: id: session_drawer_button is visible`. The fresh account-2 runtime-ready reruns at `tmp/maestro-cloud-targeted/20260504T0045Z-scenario-runtime-ready-smoke-account-2-rerun/`, `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-2-meteredfix/`, and `tmp/maestro-cloud-targeted/20260504T013500Z-scenario-runtime-ready-smoke-account-2-classify/` remain `PENDING` with `completed=false` and `wasAppLaunched=false`, so they are cloud-infra noise rather than new app verdicts. Strict `journey` is no longer blocked on missing authority: the latest S22 strict-journey rerun still preserves a current-window send-capture completion at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/journey/20260503-234734/journey-report.json` with `phase=completed`, `placeholder_visible=false`, and `first_token_ms=180222`. The emulator rerun at `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-235023/journey-report.json` remains diagnostic-only and still times out in `LOADING` with `model_status_detail=Prefill...` plus the visible slow-state message, while the A51 stays off the critical path because its latest strict run at `tmp/devctl-artifacts/2026-05-04/192.168.1.44:37643/journey/20260504-004041/` still times out in send-capture with `phase=timeout`, `runtime_status=LOADING`, and `model_status_detail=Prefill...`. The disclosed `AI human-proxy` WP-13 packet now exists but remains a measured `hold`. Branch-wise, local `main` is already fast-forwarded to the same tip as `codex/launch-readiness-implementation`; the remaining branch-closeout work is publication from local `main` to `origin/main`, not an internal merge between those two local refs.

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
4. Safe merge-back/publication now means auditing `origin/main..main`, validating the final gate state, and then pushing `main` once the launch hold is cleared, rather than inventing another internal merge step.

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

1. Preserve the S906N `android-instrumented` pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`.
2. Close hosted/default machine-verifiable gaps, with special focus on preserving a publishable direct S22 physical canary substitute and clearing the current targeted hosted/default failure set rather than treating one cloud flow as the whole blocker.
3. Preserve the current-window strict `journey` proof and treat its near-timeout first-token behavior as perf/risk context, not as a missing-authority blocker.
4. Use one narrow real-device Samsung canary only as final OEM/runtime confirmation once the cloud/default path is materially stable.
5. Carry the disclosed `AI human-proxy` packet truthfully and only replace it with human-moderated evidence if promotion needs stronger non-proxy closure.
6. Re-run the release decision flow and finalize the Play Store submission package.
7. Publish local `main` to `origin/main` only after the launch hold is cleared.

## Current Evidence Anchors

1. Current local authoritative proof: `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`
2. Current repo-side launch snapshot: `build/devctl/launch-readiness/launch-readiness-report.md`
3. Earlier hosted/default preserved passes: `tmp/maestro-cloud-first-run/junit.xml`, `tmp/maestro-cloud-s22/junit.xml`, `tmp/maestro-cloud-session-drawer/junit.xml`, `tmp/maestro-cloud-targeted/20260503T150550Z-scenario-model-management-split-smoke-account-1/status.json`, `tmp/maestro-cloud-targeted/20260503T150759Z-scenario-model-management-split-smoke-account-2/status.json`
4. Latest targeted cloud blockers: account 1 and account 2 are now split. Account 1 runtime-ready passes at `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-1-meteredfix/upload-status.json`, but account 1 `send-after-ready` fails later at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account-1-current/upload-status.json` with `Assertion is false: id: message_bubble_assistant_complete is visible`. Account 2 current model-management still fails earlier at `tmp/maestro-cloud-targeted/20260504T025141Z-account-2-model-management-fresh/upload-status.json` with `Assertion is false: id: session_drawer_button is visible`. The fresh account-2 runtime-ready reruns are still `PENDING` without app launch, so they do not replace the launched blocker evidence.
5. Current strict `journey` authority note: `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/journey/20260503-234734/journey-report.json` remains the preserved physical strict-journey proof even though the first token only appears at `180222ms` with `runtime_status=LOADING` and `model_status_detail=Prefill...`; `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-235023/journey-report.json` remains a diagnostic prefill-stall artifact; and the A51 is still non-authoritative because `tmp/devctl-artifacts/2026-05-04/192.168.1.44:37643/journey/20260504-004041/` still times out in send-capture after a 180s budget with `phase=timeout`, `runtime_status=LOADING`, and `model_status_detail=Prefill...`.

## Current Execution Graph

### Stream A: Deterministic Technical Evidence

This is the critical stream.

Dependencies:

1. Uses the fixed startup/readiness and multimodal packaging path already on local `main`.
2. Must finish before final moderated measurement or publication.

Current work:

1. Preservation of the S906N `android-instrumented` pass and its artifact root
2. Preservation or rerun of publishable direct S22 physical canary evidence while wireless Maestro remains harness-class only
3. Closure of the current targeted hosted/default failure set (`send-after-ready`, runtime-ready smoke, and the latest account-2 model-management reruns)
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

1. `PROD-12`, `PROD-11`, `SEC-02`, `MKT-08`, `MKT-10`, `PROD-13`
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
3. Publish by pushing local `main` to `origin/main` after final gate review.

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

1. Cloud-backed reruns exist for `android-instrumented`, hosted/default runtime-readiness coverage, `journey`, `screenshot-pack`, and lifecycle smoke where applicable; wireless Maestro remains supplemental while it is harness-class.
2. Artifact schema matches what `PROD-10` and WP-13 need.
3. The current targeted hosted/default reruns return clearing verdicts, or any remaining cloud-only failures are explicitly classified with preserved upload provenance rather than being taught as resolved.
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

1. Fresh cloud-first hosted/default verdicts where applicable, including `send-after-ready`
2. Journey send-capture output with `phase=completed` and `placeholder_visible=false`
3. Updated evidence notes with artifact roots
4. Cloud/device parity proof for deterministic lanes
5. Preserved current-window `android-instrumented` pass ID plus fresh strict `journey` pass ID
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

1. `PROD-12` machine-verifiable vs moderation-backed gate split executed from current evidence
2. `PROD-10` rerun from current evidence only
3. Final rollout recommendation and release-date decision
4. Play Store submission package ready for the chosen track

Dependency rule:

1. If required rows do not pass, publish `hold` or `iterate` with explicit blockers and no date commitment.

## Internal Contracts That Must Be Stable

1. Provisioning/startup contract: provisioning preflight must not crash, stale runtime metadata must self-heal, and single-image launch claims require claim-safe multimodal companion packaging (`mmproj`) in setup/provisioning evidence.
2. Timeout/cancel/send contract: timeout maps to `UI-RUNTIME-001`, retry preserves context, and send-capture fields are always emitted.
3. QA artifact contract: cloud/device reruns must produce the pass IDs, reports, screenshots, logcat, and runtime fields required by launch gates.
4. Gate taxonomy: machine-verifiable evidence and moderation-backed evidence stay explicitly separated through `PROD-12`.
5. Claim boundary: retention/reset/per-tool privacy controls remain internal-only claims until explicitly implemented and verified.
6. Evidence authority split: cloud-first is the default machine-verifiable execution path, but `android-instrumented` and strict `journey` remain authoritative gate rows and are not replaced by hosted smoke alone.
7. Older AI-moderated outputs remain deterministic QA support only; disclosed `AI human-proxy` outputs may satisfy the moderation-backed WP-13 leg for the controlled MVP when they use the approved bundle/setup path.
8. Performance-contract audits and benchmark-only evidence are part of launch-readiness expectations for risky UI/runtime hot-path changes.
9. TurboQuant remains outside the launch critical path unless a concrete launch blocker is traced to it.

## Required Output Artifacts

1. Current evidence IDs for `android-instrumented`, hosted/default runtime-readiness coverage, and `journey`, plus direct S22 physical canary artifacts while wireless Maestro remains harness-class
2. Current WP-13 packet with measured values
3. Current claim-safe Play Store asset package
4. Current support and incident playbook
5. Current launch-readiness report from `bash scripts/dev/launch-readiness.sh`

## Weekly Operating Cadence

1. Start the week with `bash scripts/dev/launch-readiness.sh` and review the generated report.
2. Review board blockers against the five engineering ownership areas and keep code closure ahead of evidence expansion.
3. Run cloud-first machine-verifiable evidence before scheduling real-device confirmation or moderated sessions.
4. Keep machine-verifiable evidence and moderation-backed evidence on separate tracks.
5. Run release-date planning only when the readiness report and current evidence both support it.
6. Before publishing `main`, confirm it still matches `codex/launch-readiness-implementation`, audit `origin/main..main`, and verify that no unrelated visible branch needs deliberate extraction.

## Completion Rule

The program is complete when:

1. all required `PROD-10` rows pass,
2. current-window pass IDs exist for the required lanes,
3. journey send-capture is clean,
4. WP-13 contains measured values,
5. privacy/claim/support/asset work is closed,
6. and `PROD-13` is ready for submission.
