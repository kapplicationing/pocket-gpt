# Release Unblock Workstreams

Last updated: 2026-04-25  
Owner: Tech Lead + Functional Owners

This document is the static execution split for the current release-unblock effort.

This file is historical. Use `docs/roadmap/current-release-plan.md`, `docs/operations/play-store-launch-program.md`, and `docs/operations/publication-closeout-checklist.md` for the active controlled-MVP state.

Mutable status remains in `docs/operations/execution-board.md`.

Current status note: Workstream 1 host-side mitigation is landed and the active device rerun has moved past provisioning preflight. The next live deterministic blocker is the runtime-ready lifecycle/bootstrap path leaving the app `Unloaded`.

Execution policy for the unblock phase:

1. Finish code and contract closure before treating QA fan-out as meaningful evidence.
2. Use cloud-hosted reruns first for machine-verifiable coverage and triage.
3. Keep the physical-device path narrow and use it as a canary/final brush, not the default discovery loop.
4. Keep moderated usability strictly after deterministic technical stabilization.

For the full end-to-end launch program through Play Store submission, use `docs/operations/play-store-launch-program.md`.

## Objective

Convert the current blocked release path into a small number of explicit workstreams with clear dependencies, parallelization boundaries, and acceptance signals.

## Workstream 1: Native Runtime + Provisioning Unblock

Owner: Engineering

Goal:

Resolve the native provisioning preflight crash so the required QA lanes can run as valid release evidence again.

Scope:

1. Root-cause `SIGILL` in `libpocket_llama.so` during `RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks`.
2. Validate the fix on the provisioning preflight path and one narrow physical-device canary.
3. Preserve logcat, crash signature, and artifact links in retained evidence.

Acceptance:

1. Provisioning preflight no longer crashes the app process.
2. Required lanes are no longer blocked at provisioning preflight.
3. Evidence note records the fix signature, rerun commands, and artifact roots.

Parallelization:

1. Can run in parallel with doc, QA-operating-model, and PM-handover work.
2. Blocks full-value QA reruns and any credible release-date discussion.

## Workstream 2: Runtime Metadata + Readiness Self-Healing

Owner: Engineering  
Support: QA

Goal:

Remove stale runtime-metadata and startup-readiness failures that continue to wedge required lanes even after the provisioning crash class is fixed.

Scope:

1. Fix `UI-STARTUP-001` and `MODEL_ARTIFACT_CONFIG_MISSING` failure paths that still break startup and scenario coverage.
2. Make uninstall/reinstall/cache-restore behavior deterministic.
3. Ensure startup checks self-heal stale runtime metadata instead of wedging the app in a blocked state.

Acceptance:

1. `MainActivityUiSmokeTest` and Maestro startup/readiness paths no longer fail on stale runtime metadata.
2. Uninstall/reinstall/cache-restore behavior is deterministic on the required canary device path.
3. Startup checks recover stale metadata instead of forcing manual cleanup or leaving the app wedged.

Parallelization:

1. Starts in parallel with Workstream 1.
2. Final proof value depends on a stable fix candidate from Workstream 1.

## Workstream 3: Timeout/Cancel + Send Reliability Closure

Owner: Engineering  
Support: QA, Product

Goal:

Close the remaining timeout/cancel/send reliability contract so journey evidence and user recovery behavior are deterministic.

Scope:

1. Complete `ENG-20`.
2. Ensure timeout maps to `UI-RUNTIME-001`.
3. Ensure send exits cleanly and preserves context after timeout/cancel.
4. Ensure journey reports always emit the required send-capture fields.

Acceptance:

1. Timeout/cancel behavior satisfies the documented contract.
2. Journey send-capture fields are always present in passing and failing runs.
3. Send/runtime reliability issues are narrowed enough for QA to trust strict journey reruns.

Parallelization:

1. Can run in parallel with Workstreams 1 and 2.
2. Lane-level proof still depends on Workstreams 1 and 2.

## Workstream 4: Required Lane Reliability Rerun

Owner: QA  
Support: Engineering

Goal:

Re-establish passing technical evidence for the required release lanes after Workstreams 1-3 are stable, using cloud-first reruns and a final canary-device confirmation.

Scope:

1. Rerun `android-instrumented`.
2. Rerun `maestro`.
3. Rerun strict `journey`.
4. Run those flows through the cloud-first path where supported and stable.
5. Use one physical-device canary to confirm the final path and capture OEM/runtime-specific proof.
6. Attach pass IDs, reports, screenshots, logcat, and send-capture fields.

Acceptance:

1. Latest pass IDs exist for the required lanes.
2. Journey send-capture fields satisfy the gate contract.
3. `devctl gate merge-unblock` and `promotion` stop failing at provisioning preflight.

Parallelization:

1. Mostly sequential after Workstreams 1-3.
2. Hosted reruns and agent triage can fan out in parallel once the contract is stable.
3. Device canary confirmation should trail cloud-first proof instead of competing with it.

## Workstream 5: Cloud-First Deterministic QA Migration

Owner: QA  
Support: Engineering, Product

Goal:

Make cloud/device automation the default path for machine-verifiable QA evidence, while retaining one narrow physical-device canary.

Scope:

1. Execute `QA-14` for deterministic lane migration.
2. Standardize artifact parity between cloud and local runs.
3. Update the runbooks, command docs, and testing references to use cloud-first wording.
4. Stop treating cloud-only as merely supplemental for machine-verifiable lanes.

Acceptance:

1. Deterministic lanes have a documented cloud-first path.
2. Cloud runs produce the evidence shape needed by `PROD-10` and WP-13 packet inputs.
3. The physical-device canary is explicit and limited.
4. Canonical docs no longer describe cloud as only supplemental for the machine-verifiable lane set.

Parallelization:

1. Docs and tooling alignment can begin immediately.
2. Final artifact-parity proof depends on stable lanes from Workstream 4.

## Workstream 6: Agent-Assisted QA Triage Loop

Owner: QA  
Support: Engineering

Goal:

Use agents to shorten deterministic failure triage and reduce manual artifact review time.

Scope:

1. Execute `QA-15`.
2. Define the agent loop for rerun, artifact inspection, diff summary, and issue update.
3. Keep the output bounded to machine-verifiable failures.

Acceptance:

1. At least one failed cloud run is triaged end-to-end by the agent loop.
2. The resulting issue update identifies the first failing step and links the evidence.
3. Humans remain the final reviewer for ambiguous UX or product interpretation.

Parallelization:

1. Can be piloted immediately on any current failing lane artifact.
2. Works best once cloud-first artifact parity is stable.

## Workstream 7: Moderation-Backed Usability Closure

Owner: Product + QA  
Support: Design, Engineering

Goal:

Complete the minimum moderation-backed evidence that automation cannot replace.

Scope:

1. Run the `human-moderated` WP-13 packet when moderators are available, or the disclosed `AI human-proxy` fallback packet when they are not, only after scripted flows are stable enough to measure.
2. Capture onboarding, recovery, privacy-comprehension, and confusion metrics.
3. Keep the sample focused on questions that remain subjective.

Acceptance:

1. WP-13 packet has measured values instead of placeholders.
2. Moderation-backed evidence is limited to usability, privacy/trust, and ambiguity-resolution work that machine-verifiable lanes cannot close.
3. No contradictory story -> flow -> test -> evidence mapping remains.

Parallelization:

1. Preparation can run in parallel with Workstreams 1-6.
2. Final moderated or proxy sessions should wait until deterministic technical regressions are no longer dominating the result.

## Workstream 8: Launch Decision + Release-Date Readiness

Owner: Product  
Support: QA, Engineering, Marketing

Goal:

Convert the restored technical evidence and moderated usability evidence into a defensible release decision and release-date plan.

Scope:

1. Execute `PROD-12` gate split.
2. Re-run `PROD-10` from current evidence.
3. Close `PROD-11` support readiness.
4. Close `SEC-02` and `MKT-10` claim parity/freeze work.
5. Decide whether a release date can be scheduled or whether the program remains in hold/iterate mode.

Acceptance:

1. Required launch-gate rows are supported by current evidence.
2. Open `S0`/`S1` blockers are closed or explicitly incompatible with release.
3. Support readiness and claim freeze are complete enough for a promote decision.
4. Release-date planning is based on gates passed, not assumed completion dates.

Parallelization:

1. Planning prep can run early.
2. Final date commitment should wait for Workstreams 1, 4, and 7.

## Dependency Order

1. Workstream 1 is the critical technical unblocker.
2. Workstreams 2 and 3 can run in parallel with Workstream 1.
3. Workstream 4 depends on Workstreams 1-3 and should execute cloud-first before the real-device brush.
4. Workstream 5 can begin in documentation/tooling mode early, but final parity proof depends on Workstream 4.
5. Workstream 6 becomes more valuable after Workstream 5 has stable artifacts to inspect.
6. Workstream 7 should start in prep mode early, but only measure once Workstreams 1-4 are stable enough for real usability signal.
7. Workstream 8 consumes the outputs of Workstreams 4 and 7.

## Recommended Immediate Split

1. Engineering owner 1: `ENG-23` native runtime/provisioning crash.
2. Engineering owner 2: `ENG-24` runtime metadata/readiness self-healing.
3. Engineering owner 3: `ENG-20` timeout/cancel and send/runtime reliability.
4. QA/release automation owner: `QA-14`, `QA-15`, and `QA-13`.
5. Product/release owner: WP-13 moderated packet prep, launch-decision evidence mapping, and `PROD-13` submission readiness.
