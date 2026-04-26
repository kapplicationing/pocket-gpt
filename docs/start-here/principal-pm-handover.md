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

The near-term release path is gated by two categories of work:

1. Deterministic technical evidence:
   - native provisioning/runtime stability
   - required lane pass IDs
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

The retained gate evidence shows that provisioning preflight was previously blocked by a native `SIGILL` in `libpocket_llama.so`, but that is no longer the immediate blocker in the active device rerun. The live blocker chain has moved forward: onboarding selector drift is fixed, provisioning preflight is no longer the first failure, and the current deterministic blocker is the Maestro/runtime-ready contract leaving the app `Unloaded` during lifecycle/startup flows before the required send evidence can start.

Story-level gate picture from the retained launch matrix:

1. `S-A`, `S-B`, `S-C`: `PASS`
2. `S-D`, `S-D1`, `S-E`, `S-F`, `S-G`: `FAIL`

The blocker chain is now:

1. Maestro/runtime-ready bootstrap can still leave the app `Unloaded` in required lifecycle/startup flows,
2. missing current-window deterministic lane evidence blocks credible moderated WP-13 execution,
3. and the incomplete moderated packet blocks release-decision confidence.

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
   - runtime-ready/bootstrap stability in lifecycle and Maestro flows
   - `ENG-20`
   - required lane reruns
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

1. clear the runtime-ready `Unloaded` blocker in required device flows,
2. restore passing cloud-first technical lane evidence,
3. confirm the result on one narrow physical-device canary,
4. complete the minimum moderated usability packet,
5. then make the release decision.

Newer April documentation adds `QA-14`, `QA-15`, and `PROD-12` as operating-model work to make that path faster and cleaner. Those items improve execution quality, but they should be treated as `Ready` planning work, not closed evidence.
`QA-14`, `QA-15`, and `QA-13` should now be read as active execution enablers for the deterministic track, not as substitutes for passing lane evidence or human moderation.

The repo now also carries an explicit end-to-end launch program and Play Store submission checklist:

1. `docs/operations/play-store-launch-program.md`
2. `docs/operations/play-store-submission-readiness.md`

Those docs define the owner split, dependency order, and final submission package. They do not change the underlying evidence state on their own.

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
6. Keep the execution order strict: code first, cloud-first machine evidence second, device canary third, human-required closure last.

## Release-Date Guidance

A credible release date should not be set from optimism or target pressure.

It should only be prepared after:

1. the active runtime-ready/bootstrap blocker is resolved,
2. required lanes have fresh pass IDs,
3. the WP-13 packet contains measured values,
4. and `PROD-10` can be rerun from current evidence instead of placeholders and blocked rows.

Use `bash scripts/dev/launch-readiness.sh` to generate the current launch-readiness snapshot before any release-date review. That report is a planning aid, not a substitute for the underlying evidence.

Before those conditions are true, the right PM output is a release window model with explicit conditions, not a committed date.

No public or firm internal release date should be committed until:

1. all required launch-gate rows pass,
2. current-window pass IDs exist for `android-instrumented`, `maestro`, and `journey`,
3. journey send-capture is clean,
4. moderated WP-13 metrics are filled,
5. and claim parity is locked to verified controls.

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
