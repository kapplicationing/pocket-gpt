# Principal PM Handover

Last updated: 2026-05-03  
Audience: Principal PM

This handover reflects the retained repo evidence and planning docs currently checked into the repository, plus the current April release-control updates tracked on the execution board.  
The retained release-gate evidence in the repo is still March-heavy, so use `docs/operations/execution-board.md` as the current operating status when it is more recent than the retained evidence notes.

## Executive Summary

Promotion status is `Hold`. PocketAgent is not release-date-ready from the retained repository evidence.

PocketAgent is not blocked on product definition. It is blocked on release readiness.

The locked launch scope is the core MVP surface plus prompt-first local tools, single-image attach/Q&A, and a limited-beta voice rail. Public launch claims remain narrower than raw implementation capability: voice is closed-track only, tool claims stay prompt-first, and image claims stay bounded to the single-image flow.

The core MVP surface is already built: offline chat, streaming, model setup and recovery, routing, performance controls, prompt-first local tools, memory, single-image Q&A, diagnostics, privacy-aware network enforcement, and a limited-beta voice surface are all represented in the implementation and retained evidence. The active problem is whether the release path is reliable enough, evidenced enough, and understandable enough to justify promotion beyond the current pilot posture.

For voice specifically, the PM should use the shipped beta contract rather than older looser assumptions:

1. voice is user-visible today under `Advanced`, so it must be supportable even though it stays limited beta
2. microphone permission and local voice-model readiness are the only hard blockers for always-on listening
3. assistant-role and battery guidance are advisory/support follow-up for capture-once and background reliability
4. current voice device actions stay intentionally bounded and should not be marketed as a broad agent surface

The launch setup experience should be understood as simple-first: `Get ready` is the primary blocked-state setup action, while the unified `Model library` remains the import/download/recovery surface when the default path is not enough.

The near-term release path is gated by two categories of work:

1. Deterministic technical evidence:
   - required authoritative lane pass IDs
   - clean hosted verdicts for cloud-first machine coverage, especially `send-after-ready`
   - send-capture and timeout/recovery reliability
2. Human-required usability evidence:
   - onboarding clarity
   - recovery comprehension
   - privacy/trust comprehension
   - moderated workflow completion metrics

Privacy/claim parity is also still bounded: retention/reset/per-tool privacy controls are not publish-safe claims and must remain internal-only until `SEC-02` is fully closed.

Current execution policy is intentionally ordered:

1. Finish code and contract closure first.
2. Restore machine-verifiable evidence through cloud-first reruns.
3. Use one narrow real-device canary as a final OEM/runtime confirmation path.
4. Run the moderation-backed WP-13 leg only after the deterministic path is stable enough to measure real usability signal.
5. Keep broad public claim language narrower than implementation reality until `PROD-10`, `SEC-02`, and current-window evidence support expansion.

## Current Release Posture

The repository still describes the release as a soft-gate pilot, not a public launch.

The current release plan and launch matrix continue to require:

1. passing required lanes,
2. measured WP-13 usability values,
3. privacy/claim parity,
4. and a formal promote/hold decision flow.

The retained gate evidence shows that provisioning preflight was previously blocked by a native `SIGILL` in `libpocket_llama.so`, but that is no longer the immediate blocker in the active launch program. The later setup/provisioning gap was narrowed to missing multimodal projector (`mmproj`) sync in `devctl` preflight for single-image claim-safe coverage, and that local code path is now understood and fixed.

The current blocker chain is more operational than architectural:

1. `android-instrumented` now has a preserved current-window pass on the S906N canary at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`, but the required promotion set is still incomplete because hosted/default cloud coverage, publishable physical-device `maestro`, and the WP-13 packet are still open,
2. hosted/default cloud evidence is mixed rather than absent: `first-run`, `gpu`, and `session-drawer` still have preserved passes, account 1 runtime-ready now has a fresh launched pass at `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-1-meteredfix/upload-status.json`, but the live blockers are now split. Account 1 `send-after-ready` fails later at `tmp/maestro-cloud-targeted/20260504T-send-after-ready-account-1-current/status.json` with `Assertion is false: id: message_bubble_assistant_complete is visible`, while account 2 current model-management still fails earlier at `tmp/maestro-cloud-targeted/20260504T025141Z-account-2-model-management-fresh/upload-status.json` with `Assertion is false: id: session_drawer_button is visible`,
3. fresh helper-fix reruns exist at `tmp/maestro-cloud-targeted/20260504T-runtime-ready-account-1-helperfix/upload-status.json` and `tmp/maestro-cloud-targeted/20260504T0045Z-scenario-runtime-ready-smoke-account-2-rerun/upload-status.json`, but both are still `PENDING` without app launch and therefore represent cloud-infra noise rather than new app verdicts,
4. the physical-device Samsung canary still lacks a publishable current-window `maestro` report because the canonical S22 lane emitted an empty `passed` report, while a direct preserved S22 Maestro run failed in bootstrap with `UNAVAILABLE` / `localhost:7001` connection refusal, so the launch canon cannot truthfully call that lane passed. But the S22 now has a fresher non-Maestro physical canary at `tmp/s22-physical-canary/20260504-004030-real-runtime-provisioning/`, and strict `journey` is no longer missing authority: S22 already preserves a current-window pass at `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/journey/20260503-234734/`, the emulator remains diagnostic-only, and the A51 now publishes a fresher strict artifact set at `tmp/devctl-artifacts/2026-05-04/192.168.1.44:37643/journey/20260504-004041/` but still times out in `LOADING` / `Prefill...`,
5. and the disclosed `AI human-proxy` WP-13 packet now exists with measured values, but it is still a `hold` packet and cannot offset missing machine-verifiable lane passes.

Current evidence anchors the PM should use:

1. Local authoritative proof already in hand: `tmp/devctl-artifacts/2026-05-03/192.168.1.38:36483/android-instrumented/20260503-213837/`
2. Repo-side launch snapshot: `build/devctl/launch-readiness/launch-readiness-report.md`
3. Current machine-evidence gap list: preserved physical-device `maestro`, launched hosted/default verdicts that still fail with `Setup` never clearing, plus the cloud-infra queue noise on the latest helper-fix reruns

Story-level gate picture from the retained launch matrix:

1. `S-A`, `S-B`, `S-C`: `PASS`
2. `S-D`, `S-D1`, `S-E`, `S-F`, `S-G`: `FAIL`

One important nuance: the app no longer lacks an authoritative onboarding contract in code, and it no longer lacks a current-window proof either. The local `android-instrumented` lane now includes `MainActivityAuthoritativeOnboardingInstrumentationTest`, and the latest current-window rerun passed, so first-session onboarding proof is represented locally even though the full current-window lane set is still incomplete.

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

1. Technical reliability:
   - current-window cloud and authoritative lane evidence
   - `ENG-20`
   - required lane reruns and hosted verdict closure
2. Human/proxy evidence:
   - the disclosed `AI human-proxy` WP-13 packet is now measured but still recommends `hold`
   - a human-moderated replacement remains preferred if launch needs stronger non-proxy closure
   - onboarding, recovery, and privacy-comprehension metrics
3. Release governance and ops:
   - privacy/claim parity
   - support readiness
   - launch decision flow

The current open work can be managed through eight execution workstreams:

1. Native runtime/provisioning unblock
2. Runtime metadata/readiness self-healing
3. Timeout/cancel + send reliability closure
4. Required lane reliability reruns
5. Cloud-first deterministic QA migration
6. Agent-assisted QA triage for deterministic failures
7. Moderated WP-13 usability closure
8. Launch decision and release-date readiness

The critical path is:

1. preserve the current authoritative `android-instrumented` proof,
2. preserve a publishable physical-device `maestro` report and close targeted hosted `send-after-ready`,
3. close the strict `journey` row,
4. confirm the result on one narrow physical-device canary once the harness is usable enough for final brush,
5. interpret or replace the measured `AI human-proxy` packet with stronger moderation-backed evidence only after the deterministic path is stable,
6. then make the release decision.

Newer April documentation adds `QA-14`, `QA-15`, and `PROD-12` as operating-model work to make that path faster and cleaner. Those items improve execution quality, but they should be treated as `Ready` planning work, not closed evidence.
`QA-14`, `QA-15`, and `QA-13` should now be read as active execution enablers for the deterministic track, not as substitutes for passing lane evidence or human moderation.

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
   - the current-window promotion-path evidence set is still incomplete even though a fresh `android-instrumented` pass now exists locally, because physical-device `maestro`, targeted hosted `send-after-ready`, and strict `journey` are still open
   - the disclosed `AI human-proxy` WP-13 packet now has measured values, but it remains a `hold` packet rather than a passing human-moderated closeout
   - release readiness cannot be inferred from earlier implementation evidence alone

There is also a freshness nuance the PM should account for:

1. Most retained evidence notes are March-dated.
2. The newer April board/docs reflect the current blocker chain more accurately than the older retained notes.
3. Those April updates improve status accuracy and operating model quality; they are not proof that the release is now ready.

## What The Principal PM Should Manage

The PM job here is cross-functional release governance, not net-new scope definition.

The highest-value PM actions are:

1. Keep technical evidence and moderation-backed evidence clearly separated.
2. Prevent subjective human research from being used to excuse missing technical gates.
3. Prevent passing automation from being mistaken for trust/comprehension evidence unless it is explicitly routed through the disclosed `AI human-proxy` fallback packet.
4. Keep marketing and public claims constrained to verified privacy and product evidence.
5. Force every release-date conversation to anchor on passed gates, not informal confidence.
6. Keep the execution order strict: code first, cloud-first machine evidence second, authoritative device/CI rows third, physical canary brush fourth, moderation-backed closure last.

## Release-Date Guidance

A credible release date should not be set from optimism or target pressure.

It should only be prepared after:

1. required authoritative lanes have fresh current-window pass IDs,
2. the WP-13 packet contains measured values and no longer recommends `hold`,
3. the remaining physical-device canary work has moved from harness diagnosis to final brush,
4. and `PROD-10` can be rerun from current evidence instead of placeholders and blocked rows.

Use `bash scripts/dev/launch-readiness.sh` to generate the current launch-readiness snapshot before any release-date review. That report is a planning aid, not a substitute for the underlying evidence.

Keep one nuance explicit in release reviews: `S-D1` is still open because simple-first onboarding completion does not by itself prove the advanced unlock path. The row only closes when current-window evidence shows the first-session path can reach advanced controls cleanly without the runtime-ready `Unloaded` blocker stopping the journey.

Before those conditions are true, the right PM output is a release window model with explicit conditions, not a committed date.

No public or firm internal release date should be committed until:

1. all required launch-gate rows pass,
2. current-window pass IDs exist for `android-instrumented`, `maestro`, and `journey`,
3. journey send-capture is clean,
4. moderation-backed WP-13 metrics are filled and no longer support a `hold` recommendation,
5. and claim parity is locked to verified controls.

Branch hygiene remains real operational work, not just documentation:

1. The current branch audit shows local `main` and `codex/launch-readiness-implementation` already at the same tip, so internal merge-back is no longer the open question.
2. Local `main` is still ahead of `origin/main`, so publication planning must still account for the full local-main base that is about to be pushed.
3. The branch story is linear, but it is not safe to describe as "already published" until `origin/main` is intentionally updated.

## Recommended Follow-Up Plan

1. Review the current release board and active ticket set.
2. Use the release-unblock workstream split to assign owners and dependencies.
3. Track deterministic technical work separately from moderation-backed usability work.
4. Schedule release-date planning only after the technical and moderation-backed evidence sets are both materially complete.

## Recommended Reading Order

1. `docs/start-here/source-of-truth-matrix.md`
2. `docs/roadmap/current-release-plan.md`
3. `docs/operations/execution-board.md`
4. `docs/operations/play-store-launch-program.md`
5. `docs/operations/release-unblock-workstreams.md`
6. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
7. `docs/operations/launch-program-learnings.md`
8. `docs/operations/play-store-submission-readiness.md`
9. `docs/testing/cloud-first-qa-operating-model.md`
10. `docs/operations/wp-13-usability-gate-packet-template.md`
11. `docs/security/privacy-model.md`
