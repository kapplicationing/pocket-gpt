# Principal PM Handover

Last updated: 2026-04-26  
Audience: Principal PM

This handover reflects the retained repo evidence and planning docs currently checked into the repository, plus the current April release-control updates tracked on the execution board.  
The retained release-gate evidence in the repo is still March-heavy, so use `docs/operations/execution-board.md` as the current operating status when it is more recent than the retained evidence notes.

## Executive Summary

Promotion status is `Hold`. PocketAgent is not release-date-ready from the retained repository evidence.

PocketAgent is not blocked on product definition. It is blocked on release readiness.

The locked launch scope is the core MVP surface plus prompt-first local tools, single-image attach/Q&A, and a limited-beta voice rail. Public launch claims remain narrower than raw implementation capability: voice is closed-track only, tool claims stay prompt-first, and image claims stay bounded to the single-image flow.

The core MVP surface is already built: offline chat, streaming, model setup and recovery, routing, performance controls, prompt-first local tools, memory, single-image Q&A, diagnostics, privacy-aware network enforcement, and a limited-beta voice surface are all represented in the implementation and retained evidence. The active problem is whether the release path is reliable enough, evidenced enough, and understandable enough to justify promotion beyond the current pilot posture.

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
4. Run moderated WP-13 only after the deterministic path is stable enough to measure real usability signal.
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

1. current-window authoritative lane evidence is still missing for the required promotion rows,
2. cloud `send-after-ready` still lacks a clean hosted verdict, so the cloud-first technical packet is not complete yet,
3. wireless Samsung Maestro remains a physical-canary harness blocker before app logic begins,
4. `android-instrumented` and strict `journey` still remain authoritative gate rows even though cloud-first is the default machine-verifiable execution path,
5. and the moderated WP-13 packet is still incomplete.

Story-level gate picture from the retained launch matrix:

1. `S-A`, `S-B`, `S-C`: `PASS`
2. `S-D`, `S-D1`, `S-E`, `S-F`, `S-G`: `FAIL`

One important nuance: the app no longer lacks an authoritative onboarding contract in code. The local `android-instrumented` lane now includes `MainActivityAuthoritativeOnboardingInstrumentationTest`, so first-session onboarding proof is represented locally even though the current-window lane set is still incomplete.

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
2. Human evidence:
   - moderated WP-13 packet
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

1. restore current-window cloud-first technical evidence where hosted execution is valid,
2. get a clean hosted verdict for `send-after-ready`,
3. close the authoritative `android-instrumented` and strict `journey` rows,
4. confirm the result on one narrow physical-device canary once the harness is usable enough for final brush,
5. complete the minimum moderated usability packet,
6. then make the release decision.

Newer April documentation adds `QA-14`, `QA-15`, and `PROD-12` as operating-model work to make that path faster and cleaner. Those items improve execution quality, but they should be treated as `Ready` planning work, not closed evidence.
`QA-14`, `QA-15`, and `QA-13` should now be read as active execution enablers for the deterministic track, not as substitutes for passing lane evidence or human moderation.

The repo now also carries an explicit end-to-end launch program and Play Store submission checklist:

1. `docs/operations/play-store-launch-program.md`
2. `docs/operations/play-store-submission-readiness.md`

Those docs define the owner split, dependency order, and final submission package. They do not change the underlying evidence state on their own.

Branch hygiene note:

1. The current launch stack is intentionally isolated on `codex/launch-readiness-implementation`, which sits linearly on top of local `main`, not directly on `origin/main`.
2. Local `main` itself has diverged from `origin/main`, so merge-back planning must account for both the local-main base and the launch commits above it.
3. Older `codex/*` branches already appear subsumed into `main`; they are not separate launch-closeout streams that need independent merge planning.
4. Once the branch is green and the working tree is clean, the safest merge-back is to fast-forward local `main` to the launch branch tip and then push `main`, after confirming that the local-main-only base commits are intentionally part of what should reach `origin/main`.

## Evidence Quality

Evidence strength is mixed.

1. Strong:
   - baseline capability proof from WP-11 and WP-12
   - feature and UX evidence showing the core MVP surface exists
   - March QA matrix and retained closure evidence for implemented product areas
2. Weak:
   - latest current-window promotion-path evidence is still missing because required device lanes are not green yet
   - moderated WP-13 packet still lacks measured values
   - release readiness cannot be inferred from earlier implementation evidence alone

There is also a freshness nuance the PM should account for:

1. Most retained evidence notes are March-dated.
2. The newer April board/docs reflect the current blocker chain more accurately than the older retained notes.
3. Those April updates improve status accuracy and operating model quality; they are not proof that the release is now ready.

## What The Principal PM Should Manage

The PM job here is cross-functional release governance, not net-new scope definition.

The highest-value PM actions are:

1. Keep technical evidence and moderated evidence clearly separated.
2. Prevent subjective human research from being used to excuse missing technical gates.
3. Prevent passing automation from being mistaken for trust/comprehension evidence that only moderated sessions can provide.
4. Keep marketing and public claims constrained to verified privacy and product evidence.
5. Force every release-date conversation to anchor on passed gates, not informal confidence.
6. Keep the execution order strict: code first, cloud-first machine evidence second, authoritative device/CI rows third, physical canary brush fourth, human-required closure last.

## Release-Date Guidance

A credible release date should not be set from optimism or target pressure.

It should only be prepared after:

1. required authoritative lanes have fresh current-window pass IDs,
2. the WP-13 packet contains measured values,
3. the remaining physical-device canary work has moved from harness diagnosis to final brush,
4. and `PROD-10` can be rerun from current evidence instead of placeholders and blocked rows.

Use `bash scripts/dev/launch-readiness.sh` to generate the current launch-readiness snapshot before any release-date review. That report is a planning aid, not a substitute for the underlying evidence.

Keep one nuance explicit in release reviews: `S-D1` is still open because simple-first onboarding completion does not by itself prove the advanced unlock path. The row only closes when current-window evidence shows the first-session path can reach advanced controls cleanly without the runtime-ready `Unloaded` blocker stopping the journey.

Before those conditions are true, the right PM output is a release window model with explicit conditions, not a committed date.

No public or firm internal release date should be committed until:

1. all required launch-gate rows pass,
2. current-window pass IDs exist for `android-instrumented`, `maestro`, and `journey`,
3. journey send-capture is clean,
4. moderated WP-13 metrics are filled,
5. and claim parity is locked to verified controls.

Branch hygiene remains real operational work, not just documentation:

1. The current branch audit still shows `codex/launch-readiness-implementation` stacked directly on top of local `main`.
2. Local `main` is still ahead of `origin/main`, so merge-back planning must account for the local-main base as well as the launch-closeout commits above it.
3. The branch story is therefore still linear, but it is not yet "already merged" or safe to describe as a trivial push to remote `main`.

## Recommended Follow-Up Plan

1. Review the current release board and active ticket set.
2. Use the release-unblock workstream split to assign owners and dependencies.
3. Track deterministic technical work separately from moderated usability work.
4. Schedule release-date planning only after the technical and human-required evidence sets are both materially complete.

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
