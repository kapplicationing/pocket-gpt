# Principal PM Handover

Last updated: 2026-07-12
Audience: Principal PM

This handover reflects the retained repo evidence, the July release-closeout package, and the current status tracked on the execution board.
The retained release-gate evidence in the repo is still March-heavy, so use `docs/operations/execution-board.md` as the current operating status when it is more recent than the retained evidence notes.

## Executive Summary

Promotion status is `Promote` for the controlled MVP. PocketAgent is now launch-gate-ready from the retained repository evidence, with publication/package work remaining.

PocketAgent is not blocked on core product definition. The retained launch-gate blockers are cleared, but Play publication now has one required product/policy addition: `PROD-14` in-app AI-output reporting plus restricted-content prevention. Assets, signing, hardware, and Console execution also remain.

The locked launch scope is the core MVP surface plus prompt-first local tools, single-image attach/Q&A, and production opt-in voice. Public launch claims remain narrower than raw implementation capability: the current frozen listing does not promise cross-device hands-free reliability or battery performance, tool claims stay prompt-first, and image claims stay bounded to the single-image flow.

The core MVP surface is already built: offline chat, streaming, model setup and recovery, routing, performance controls, prompt-first local tools, memory, single-image Q&A, diagnostics, privacy-aware network enforcement, and production opt-in voice are all represented in the implementation. The active problem is whether the release path is reliable enough, evidenced enough, and understandable enough to justify promotion beyond the current pilot posture.

For voice specifically, the PM should use the shipped production-opt-in contract rather than older debug, cohort, or manual-provisioning assumptions:

1. voice is user-visible in production builds under `Advanced`; no debug flag or device allowlist hides it
2. the guided switch handles notification and microphone permission, assistant-role selection, durable download and verification of the local voice pack, then automatic enablement
3. battery guidance remains support follow-up; retained 24-hour Samsung, Pixel, and aggressive-background-OEM qualification is still required before broad reliability claims
4. current voice device actions stay intentionally bounded, typed, and confirmed and should not be marketed as a broad autonomous-agent surface

The launch setup experience should be understood as simple-first: `Get ready` is the primary blocked-state setup action, while the unified `Model library` remains the import/download/recovery surface when the default path is not enough.

The current release path is now split into two categories:

1. Preserved launch evidence:
   - required authoritative lane pass IDs
   - current hosted verdicts for cloud-first machine coverage
   - send-capture and timeout/recovery reliability contracts
2. Publication/package execution:
   - claim-safe asset capture
   - Play-required in-app AI-output reporting and restricted-content prevention
   - direct timeout-to-retry closure
   - signed artifact, physical-canary, and Play Console execution

Privacy/claim parity is still bounded: retention/reset/per-tool privacy controls remain internal-only even though `SEC-02` is now closed for the publish-safe claim set.

Current execution policy is now closeout-oriented:

1. Preserve the promoted evidence set; do not reopen solved launch-gate rows without a new first-failure artifact.
2. Keep one narrow real-device canary only as preserved OEM/runtime confirmation, not as a new blocker-discovery surface.
3. Treat the disclosed `AI human-proxy` packet as the closed moderation-backed leg for the controlled MVP unless a stronger human-moderated replacement is intentionally produced.
4. Keep broad public claim language narrower than implementation reality until listing assets and store copy have actually applied the frozen claim set.
5. Publish only from a clean worktree with an explicit release bundle and operator checklist.

## Current Release Posture

The controlled-MVP product gate is promoted, but publication is still blocked. This is an internal-track release candidate, not a public launch.

The current release plan and launch matrix continue to require:

1. passing required lanes,
2. measured WP-13 usability values,
3. privacy/claim parity,
4. and a formal promote/hold decision flow.

The retained gate evidence shows that provisioning preflight was previously blocked by a native `SIGILL` in `libpocket_llama.so`, but that is no longer the immediate blocker in the active launch program. The later setup/provisioning gap was narrowed to missing multimodal projector (`mmproj`) sync in `devctl` preflight for single-image claim-safe coverage, and that local code path is now understood and fixed.

The current launch story is more operational than architectural:

1. `android-instrumented` has a preserved current-window pass on the S906N canary at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/android-instrumented/20260503-213837/`,
2. hosted/default cloud evidence is now green on the required targeted surfaces: account 1 `send-after-ready` passes at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`, and account 2 model-management passes at `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`,
3. older helper-fix reruns that remain `PENDING` without app launch are now only cloud-infra noise, not active blocker truth,
4. the physical-device Samsung canary still lacks a publishable current-window `maestro` report and should remain harness-class only, but the S22 has a fresher non-Maestro physical canary at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`, and strict `journey` already preserves current-window physical send authority at `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/journey/20260503-234734/`,
5. and the disclosed `AI human-proxy` WP-13 packet now exists with measured values and a `promote` recommendation for the controlled MVP.

Current evidence anchors the PM should use:

1. Local authoritative proof already in hand: `tmp/devctl-artifacts/2026-05-03/S906N_TCPIP/android-instrumented/20260503-213837/`
2. Repo-side launch snapshot: regenerate it locally with `bash scripts/dev/launch-readiness.sh`
3. Current package-closeout gap list: `PROD-14` in-app reporting/restricted-content controls, fresh screenshot/video capture and approval, `UX-13` direct retry closure, a signed bundle plus physical-canary proof, and an enrolled Play Console account

Story-level gate picture from the retained launch matrix:

1. `S-A`, `S-B`, `S-C`: `PASS`
2. `S-D`, `S-D1`, `S-E`, `S-F`, `S-G`: `PASS`

One important nuance: the app no longer lacks an authoritative onboarding contract in code, and it no longer lacks a current-window proof either. The local `android-instrumented` lane now includes `MainActivityAuthoritativeOnboardingInstrumentationTest`, and the preserved promoted evidence set already carries that proof.

## What Is Already Built

The PM should assume the following product areas are materially present, not speculative:

1. Offline-first local chat on Android
2. Streaming responses
3. In-app model library and runtime readiness recovery
4. Model routing and performance profiles
5. GPU-gated acceleration
6. Memory and session continuity
7. Single-image Q&A
8. Diagnostics export and backend/runtime transparency
9. Offline policy-aware network enforcement

This matters because the next release decision should not be managed as a large feature roadmap. It should be managed as a controlled release-readiness program.

## What Is Still Open

The current open work falls into three main buckets:

1. Publication package execution:
   - signed bundle generation with the existing upload key
   - canary install validation on the selected release build
   - Play internal-track upload from an enrolled account
2. Asset and copy execution:
   - claim-safe screenshot/video capture
   - listing/package copy application using the frozen claim set
   - optional 7-day scorecard execution if channel validation is still desired
3. Product and publication closure:
   - Play-required in-app AI-output reporting and restricted-content prevention (`PROD-14`)
   - direct timeout-to-retry acceptance closure (`UX-13`)
   - Product + Marketing approval of the prepared store graphics and fresh captures
   - review and merge of the isolated release-closeout payload

The remaining work can be managed through five closeout workstreams:

1. `PROD-14` safety/reporting and privacy/Data Safety closure
2. `UX-13` direct retry closure
3. Claim-safe asset capture and listing finalization
4. Signed package, physical canary, and Play Console preparation
5. Review and merge of `codex/release-closeout`

The critical path is now:

1. preserve the current promoted evidence set,
2. review and merge the release-closeout payload,
3. close `PROD-14` with real policy controls and matching data declarations,
4. close the direct timeout-to-retry acceptance gap,
5. capture and approve claim-safe assets,
6. produce and install-check the signed release from clean synchronized `main`,
7. then submit that verified bundle to the Play internal-testing track.

`QA-14`, `QA-15`, and `PROD-12` are now closed operating-model work for the controlled MVP. They matter because they explain how the evidence was gathered and packaged, but the release decision still rests on the preserved pass roots and the current `PROD-10` matrix, not on the tickets alone.

The repo now also carries an explicit end-to-end launch program and Play Store submission checklist:

1. `docs/operations/play-store-launch-program.md`
2. `docs/operations/play-store-submission-readiness.md`

Those docs define the owner split, dependency order, and final submission package. They do not change the underlying evidence state on their own.

Branch hygiene note:

1. The release-closeout branch was rebased onto the current `origin/main` at `f8f7e5ab` during the final July 11 refresh.
2. The documentation, QA-13, support, brand, and release-package payload is isolated on `codex/release-closeout` for review; it modifies none of the engineer-owned runtime/CI files in its base.
3. Older side branches remain outside this release decision unless a separate reviewed change intentionally brings them in.
4. After the closeout branch merges, create the signed artifact only from clean `main` whose `HEAD` matches `origin/main`.

## Evidence Quality

Evidence strength is mixed.

1. Strong:
   - baseline capability proof from WP-11 and WP-12
   - feature and UX evidence showing the core MVP surface exists
   - March QA matrix and retained closure evidence for implemented product areas
2. Weak:
   - physical-device wireless `maestro` is still harness-class only and should not be overstated as gate authority
   - timeout and manifest-outage recovery are supported mainly by deterministic contract tests plus adjacent current-window runtime/setup passes, not by fresh induced-failure live reruns
   - release readiness still cannot be inferred from earlier implementation evidence alone; use the current promoted matrix plus preserved artifacts

There is also a freshness nuance the PM should account for:

1. Most retained evidence notes are March-dated.
2. The July board, release docs, and generated launch-readiness report reflect the current launch-closeout state more accurately than the older retained notes.
3. Use the current launch matrix and launch-readiness report as the status source of truth, then use older retained notes only for historical context.

## What The Principal PM Should Manage

The PM job here is cross-functional release governance, not net-new scope definition.

The highest-value PM actions are:

1. Keep technical evidence and moderation-backed evidence clearly separated.
2. Prevent subjective human research from being used to excuse missing technical gates.
3. Prevent passing automation from being mistaken for trust/comprehension evidence unless it is explicitly routed through the disclosed `AI human-proxy` fallback packet.
4. Keep marketing and public claims constrained to verified privacy and product evidence.
5. Force every release-date conversation to anchor on passed gates, not informal confidence.
6. Keep the execution order strict: code first, cloud-first machine evidence second, authoritative device/CI rows third, physical canary brush fourth, moderation-backed closure last.

## Publication Guidance

The gate is already green. The PM job is now to package and publish cleanly.

Before publication:

1. run `bash scripts/dev/launch-readiness.sh` and confirm the report still says `Promote`,
2. confirm `PROD-14` safety/reporting and its privacy/Data Safety evidence are done,
3. confirm the release bundle/version to be uploaded,
4. confirm final listing assets and copy use only the frozen claim set,
5. confirm support/contact/store metadata is current,
6. and confirm the build runs from clean `main` synchronized with `origin/main`.

Keep one nuance explicit in publication reviews: wireless Samsung `maestro` is still harness-class only and should not be presented as required launch authority. The publish-safe physical-device authority is the preserved S22 physical canary plus strict `journey`.

Branch hygiene remains real operational work, not just documentation:

1. The July baseline is already published; do not reopen the obsolete `codex/launch-readiness-implementation` merge story.
2. `codex/release-closeout` is the only current integration payload covered by this handover and must be reviewed before merge.
3. It is not safe to describe the app as publishable until `PROD-14`, the signed bundle, physical canary, approved assets, and Play internal-track upload all exist.

## Recommended Follow-Up Plan

1. Review the current release board and the publication-closeout checklist.
2. Treat `PROD-11` and `QA-13` as done; close `PROD-14`, `UX-13`, `MKT-08`, `MKT-10`, and `PROD-13` against fresh evidence.
3. Run `MKT-09` only after rollout; it is not a blocker for initial controlled publication.
4. Keep publication/package work separate from the engineer-owned runtime and CI work in the active tree.

## Recommended Reading Order

1. `docs/start-here/source-of-truth-matrix.md`
2. `docs/roadmap/current-release-plan.md`
3. `docs/operations/execution-board.md`
4. `docs/operations/play-store-launch-program.md`
5. `docs/operations/publication-closeout-checklist.md`
6. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
7. `docs/operations/historical/launch-program-learnings.md`
8. `docs/operations/play-store-submission-readiness.md`
9. `docs/testing/cloud-first-qa-operating-model.md`
10. `docs/security/privacy-model.md`
