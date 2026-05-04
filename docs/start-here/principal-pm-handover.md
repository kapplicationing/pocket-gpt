# Principal PM Handover

Last updated: 2026-05-04  
Audience: Principal PM

This handover reflects the retained repo evidence and planning docs currently checked into the repository, plus the current April release-control updates tracked on the execution board.  
The retained release-gate evidence in the repo is still March-heavy, so use `docs/operations/execution-board.md` as the current operating status when it is more recent than the retained evidence notes.

## Executive Summary

Promotion status is `Promote` for the controlled MVP. PocketAgent is now launch-gate-ready from the retained repository evidence, with publication/package work remaining.

PocketAgent is not blocked on product definition. The launch-gate blockers are cleared; the remaining work is publication and package execution.

The locked launch scope is the core MVP surface plus prompt-first local tools, single-image attach/Q&A, and a limited-beta voice rail. Public launch claims remain narrower than raw implementation capability: voice is closed-track only, tool claims stay prompt-first, and image claims stay bounded to the single-image flow.

The core MVP surface is already built: offline chat, streaming, model setup and recovery, routing, performance controls, prompt-first local tools, memory, single-image Q&A, diagnostics, privacy-aware network enforcement, and a limited-beta voice surface are all represented in the implementation and retained evidence. The active problem is whether the release path is reliable enough, evidenced enough, and understandable enough to justify promotion beyond the current pilot posture.

For voice specifically, the PM should use the shipped beta contract rather than older looser assumptions:

1. voice is user-visible today under `Advanced`, so it must be supportable even though it stays limited beta
2. microphone permission and local voice-model readiness are the only hard blockers for always-on listening
3. assistant-role and battery guidance are advisory/support follow-up for capture-once and background reliability
4. current voice device actions stay intentionally bounded and should not be marketed as a broad agent surface

The launch setup experience should be understood as simple-first: `Get ready` is the primary blocked-state setup action, while the unified `Model library` remains the import/download/recovery surface when the default path is not enough.

The current release path is now split into two categories:

1. Preserved launch evidence:
   - required authoritative lane pass IDs
   - current hosted verdicts for cloud-first machine coverage
   - send-capture and timeout/recovery reliability contracts
2. Publication/package execution:
   - claim-safe asset capture
   - support/readiness packet closeout
   - final publication decision package

Privacy/claim parity is still bounded: retention/reset/per-tool privacy controls remain internal-only even though `SEC-02` is now closed for the publish-safe claim set.

Current execution policy is now closeout-oriented:

1. Preserve the promoted evidence set; do not reopen solved launch-gate rows without a new first-failure artifact.
2. Keep one narrow real-device canary only as preserved OEM/runtime confirmation, not as a new blocker-discovery surface.
3. Treat the disclosed `AI human-proxy` packet as the closed moderation-backed leg for the controlled MVP unless a stronger human-moderated replacement is intentionally produced.
4. Keep broad public claim language narrower than implementation reality until listing assets and store copy have actually applied the frozen claim set.
5. Publish only from a clean worktree with an explicit release bundle and operator checklist.

## Current Release Posture

The repository still describes the release as a soft-gate pilot, not a public launch.

The current release plan and launch matrix continue to require:

1. passing required lanes,
2. measured WP-13 usability values,
3. privacy/claim parity,
4. and a formal promote/hold decision flow.

The retained gate evidence shows that provisioning preflight was previously blocked by a native `SIGILL` in `libpocket_llama.so`, but that is no longer the immediate blocker in the active launch program. The later setup/provisioning gap was narrowed to missing multimodal projector (`mmproj`) sync in `devctl` preflight for single-image claim-safe coverage, and that local code path is now understood and fixed.

The current launch story is more operational than architectural:

1. `android-instrumented` has a preserved current-window pass on the S906N canary at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`,
2. hosted/default cloud evidence is now green on the required targeted surfaces: account 1 `send-after-ready` passes at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account1-default64-contractfix/status.json`, and account 2 model-management passes at `tmp/maestro-cloud-targeted/20260504T-model-management-account2-runtime-ready-helper/status.json`,
3. older helper-fix reruns that remain `PENDING` without app launch are now only cloud-infra noise, not active blocker truth,
4. the physical-device Samsung canary still lacks a publishable current-window `maestro` report and should remain harness-class only, but the S22 has a fresher non-Maestro physical canary at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`, and strict `journey` already preserves current-window physical send authority at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/journey/20260503-234734/`,
5. and the disclosed `AI human-proxy` WP-13 packet now exists with measured values and a `promote` recommendation for the controlled MVP.

Current evidence anchors the PM should use:

1. Local authoritative proof already in hand: `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`
2. Repo-side launch snapshot: `build/devctl/launch-readiness/launch-readiness-report.md`
3. Current package-closeout gap list: support readiness, claim-safe asset capture, submission-package completion, a clean publication worktree, and the intentional push of local `main` to `origin/main`

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
   - final release build versioning and reproducibility
   - canary install validation on the selected release build
   - rollout and rollback notes
2. Asset and copy execution:
   - claim-safe screenshot/video capture
   - listing/package copy application using the frozen claim set
   - optional 7-day scorecard execution if channel validation is still desired
3. Publication hygiene:
   - support-readiness packet completion
   - clean worktree and branch audit
   - intentional push of local `main` to `origin/main`

The current open work can be managed through four closeout workstreams:

1. Support-readiness closeout
2. Claim-safe asset capture and listing finalization
3. Submission package and Play Console preparation
4. Branch hygiene and publication

The critical path is now:

1. preserve the current promoted evidence set,
2. finalize the support/readiness and submission package,
3. capture and approve claim-safe assets,
4. clean the publication worktree and audit the branch delta,
5. push `main`,
6. then submit the selected release bundle to the intended Play track.

`QA-14`, `QA-15`, and `PROD-12` are now closed operating-model work for the controlled MVP. They matter because they explain how the evidence was gathered and packaged, but the release decision still rests on the preserved pass roots and the current `PROD-10` matrix, not on the tickets alone.

The repo now also carries an explicit end-to-end launch program and Play Store submission checklist:

1. `docs/operations/play-store-launch-program.md`
2. `docs/operations/play-store-submission-readiness.md`

Those docs define the owner split, dependency order, and final submission package. They do not change the underlying evidence state on their own.

Branch hygiene note:

1. `codex/launch-readiness-implementation` was the launch integration branch while the stack was still moving, but local `main` is now already fast-forwarded to the same tip.
2. The branch-hygiene gap the PM should manage is therefore local `main` versus `origin/main`, not an unfinished merge between local refs.
3. Older `codex/*` branches already appear subsumed into `main`; `cursor/cloud-agent-1775007300791-5ig66` remains intentionally separate and should not be swept into launch closeout wholesale.
4. The safe closeout path is now: keep the worktree clean, audit `origin/main..main`, confirm no deliberate side-branch extraction is needed, validate the final gate state, then push `main`.

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
2. The newer April-May board/docs reflect the current launch-closeout state more accurately than the older retained notes.
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
2. confirm the release bundle/version to be uploaded,
3. confirm final listing assets and copy use only the frozen claim set,
4. confirm support/contact/store metadata is current,
5. and confirm `main` will be pushed from a clean worktree.

Keep one nuance explicit in publication reviews: wireless Samsung `maestro` is still harness-class only and should not be presented as required launch authority. The publish-safe physical-device authority is the preserved S22 physical canary plus strict `journey`.

Branch hygiene remains real operational work, not just documentation:

1. The current branch audit shows local `main` and `codex/launch-readiness-implementation` already at the same tip, so internal merge-back is no longer the open question.
2. Local `main` is still ahead of `origin/main`, so publication planning must still account for the full local-main base that is about to be pushed.
3. The branch story is linear, but it is not safe to describe as "already published" until `origin/main` is intentionally updated.

## Recommended Follow-Up Plan

1. Review the current release board and the publication-closeout checklist.
2. Close `PROD-11`, `MKT-08`, `MKT-09` (if desired), `MKT-10`, and `PROD-13` against the promoted evidence set.
3. Keep publication/package work separate from any new engineering work in the tree.
4. Push `main` only when the publication payload is explicit and the worktree is clean.

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
