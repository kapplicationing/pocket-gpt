# QA Improvement Action Plan

Last updated: 2026-04-26  
Owner: Tech Lead + QA + Product

This document turns current QA learnings into repeatable actions for future work.

Goal: make the improved testing approach part of the normal SDLC so new joiners inherit better defaults instead of rediscovering the same failures.

## Selection Rule

Many ideas are possible. The actions below are selected because they fit the current project:

1. Android app with local runtime complexity
2. mixed host, device, and hosted-cloud evidence
3. small team that needs leverage from automation and documentation
4. launch process that depends on both machine-verifiable and human-required evidence

## Adopt Now

### 1. Freeze a small launch smoke suite

Action:

1. Define one short canonical smoke suite for launch:
   - runtime-ready
   - session shell
   - model-management
   - send-after-ready
2. Make those flows intentionally hard to change.
3. Require code review from QA/tooling owner for changes to shared helpers or launch smoke flows.

Why:

1. Stable smoke suites reduce churn.
2. Shared helper drift caused repeated wasted reruns.

### 2. Add flow-contract ownership

Action:

1. Assign explicit owners for:
   - `tests/maestro/shared/*`
   - `tests/maestro-cloud/shared/*`
   - `tools/devctl` gate/lane wrappers
2. Require a contract test when a shared helper changes.

Why:

1. Shared helpers are high leverage and high blast radius.
2. Contract drift is one of the main failure sources we saw.

### 3. Require failure classification before broad rerun

Action:

1. Add a lightweight failure taxonomy to PRs, tickets, and QA notes:
   - `flow drift`
   - `product bug`
   - `device harness`
   - `hosted infrastructure`
2. Do not rerun a full lane until the failure is classified.

Why:

1. This is the simplest habit change with the biggest time savings.

### 4. Record hosted provenance automatically

Action:

1. Standardize recording of:
   - account label
   - project id
   - upload id
   - upload URL
   - app binary id
   - branch-tip note
2. Put it into every hosted run manifest and evidence note.

Why:

1. Hosted evidence is only useful when we know exactly what binary and account produced it.

### 5. Keep cloud-first as the default machine-verifiable path

Action:

1. Make cloud smoke the default rerun path for deterministic UI contracts.
2. Keep local `android-instrumented` and strict `journey` as authoritative rows.
3. Use physical devices after cloud, not before it.

Why:

1. Hosted parallelism is the biggest practical multiplier we have right now.

### 6. Make device pinning universal

Action:

1. Audit every wrapper with `--device`.
2. Ensure build, install, and execution all pin to the same serial.
3. Add regression tests for the pinning path.

Why:

1. Cross-device contamination wastes time and undermines trust in narrow repros.

### 7. Add a “code-truth before rerun” checklist to reviews

Action:

1. Add a short checklist to PR templates or QA review templates:
   - did we inspect the current UI code?
   - did we inspect the first-failure screenshot?
   - is the scenario proving only one contract?

Why:

1. This prevents stale assumptions from surviving into another rerun cycle.

### 8. Keep docs and wrappers shipping together

Action:

1. Any new wrapper or lane change must update:
   - `scripts/dev/README.md`
   - `docs/testing/runbooks.md`
   - tests for that wrapper when applicable

Why:

1. Operator confusion is expensive and avoidable.

## Adopt Next

### 9. Build one machine-readable evidence ledger

Action:

1. Create one structured ledger for:
   - local pass ids
   - hosted upload ids
   - first-failure class
   - authority level
   - branch-tip provenance
2. Use it in gate reviews and PM handovers.

Why:

1. Right now evidence is present but too distributed across artifacts and notes.

Fit:

1. High fit for this project because release decisions are evidence-heavy.

### 10. Add a first-failure screenshot index

Action:

1. Generate one short index file per lane that points directly to:
   - first failing screenshot
   - first failing log
   - first failing runner output

Why:

1. Engineers currently browse artifact trees too much.

Fit:

1. High fit; small implementation cost and immediate review speed-up.

### 11. Add a hosted single-scenario wrapper

Action:

1. Add a wrapper that runs exactly one hosted scenario by name/tag on one account.
2. Keep `cloud-smoke` fan-out separate from single-scenario diagnosis.

Why:

1. We often need “one clean hosted repro” before we need full smoke.

Fit:

1. High fit with current cloud-first workflow.

### 12. Add automatic failure classification hints

Action:

1. Teach wrappers to suggest likely failure class based on known signatures:
   - selector/assertion drift
   - `localhost:7001`
   - null hosted status
   - product-side runtime errors

Why:

1. This reduces interpretation overhead for newer engineers.

Fit:

1. Good fit; use hints, not absolute decisions.

### 13. Add a flake and harness review ritual

Action:

1. Weekly 15-minute review of:
   - harness failures
   - flaky selectors
   - hosted polling failures
   - stale helper changes

Why:

1. Flakes compound quietly if they are never reviewed as a class.

Fit:

1. High fit for a small team.

### 14. Add a stable local fallback path

Action:

1. Prefer one more stable local authority for Maestro:
   - wired Android path, or
   - emulator-backed local smoke
2. Keep wireless Samsung runs as diagnosis and OEM confirmation when needed.

Why:

1. Wireless local Maestro is still a harness risk.

Fit:

1. High fit because it directly addresses the current bottleneck.

## Adopt Later

### 15. Create a hermetic launch-validation environment

Action:

1. Build a more reproducible environment with:
   - pinned APK provenance
   - fixed device profile(s)
   - stable seed data
   - predictable model artifacts

Why:

1. Hermetic validation is a usual best practice for release programs.

Fit:

1. Good long-term fit, but more expensive than the actions above.

### 16. Add contract linting for flows

Action:

1. Add a lint rule that flags likely anti-patterns such as:
   - readiness helpers asserting send-ready
   - shell controls used as send proxies
   - mixed multi-contract smoke flows

Why:

1. This would prevent common drift before runtime.

Fit:

1. Strong fit once the current flow taxonomy is stable.

### 17. Add nightly broader cloud matrix

Action:

1. Run broader API/device/locale cloud matrices nightly.
2. Keep PR and launch smoke intentionally small.

Why:

1. This follows the usual best practice of separating fast required checks from broader confidence sweeps.

Fit:

1. Good fit after current hosted evidence becomes stable.

## SDLC Changes To Make The Learnings Stick

### Planning

1. Every new feature must define:
   - machine-verifiable evidence
   - human-required evidence if any
   - claim boundary
2. Every user-visible flow change should state whether it changes:
   - runtime-ready
   - send-ready
   - shell navigation
   - recovery/setup

### Design

1. Require stable test tags for launch-critical surfaces.
2. Keep critical CTAs and recovery actions explicit and testable.
3. Review UI changes for automation impact, not just visual correctness.

### Development

1. Keep shared helpers narrow and contract-tested.
2. Update canonical docs in the same PR as a wrapper or lane change.
3. Treat branch-tip APK provenance as part of done-ness for hosted testing.

### Testing

1. Use the smallest proving lane first.
2. Preserve first-failure artifacts.
3. Classify failures before rerunning.
4. Use cloud-first for deterministic contracts.
5. Reserve physical devices for authoritative local rows, OEM confirmation, and final brush.

### Review

1. Add QA review checkpoints to high-risk changes:
   - contract changed?
   - helper changed?
   - selector/tag changed?
   - claim changed?
2. Require explicit evidence notes for promotion-affecting fixes.

### Deploy / Release

1. Keep launch evidence current-window only.
2. Treat hosted infra delay separately from product regressions.
3. Run human moderation only after machine-verifiable gates are materially green.

### Onboarding

1. Put the QA principles page in the new-joiner reading order.
2. Teach the failure taxonomy and evidence ladder on day one.
3. Give new joiners one small hosted run and one small local repro as training, not a full release lane first.

## Outside-The-Box Ideas Worth Considering

These are not the first actions to take, but they are plausible future improvements that match this project better than generic enterprise process.

### 1. “Flight recorder” evidence bundles

Generate one compressed bundle per failed lane with:

1. first screenshot
2. first failing log
3. flow file
4. relevant source file links
5. suggested failure class

Why:

1. This would make both human and agent triage much faster.

### 2. Evidence-backed PR labels

Auto-suggest PR labels from changed files and required lanes, then attach evidence links back to the PR or review note.

Why:

1. This makes risk labeling more consistent and less manual.

### 3. “Do not rerun” safeguard

When the same lane fails the same way twice without code or flow changes, the wrapper should warn and ask for classification before allowing another broad rerun.

Why:

1. This directly targets the churn pattern we experienced.

### 4. On-device smoke capability beacon

Expose a small machine-readable status panel in the app or debug build for:

1. runtime state
2. active model state
3. vision readiness
4. production voice readiness and qualification state
5. tool/runtime availability

Why:

1. This would reduce UI-guessing inside automation and make failure interpretation faster.

## Best-Fit Priorities For This Project

If we want the best return with the current team and repo, prioritize these first:

1. freeze the small launch smoke suite
2. require failure classification before broad reruns
3. make hosted provenance and upload tracking automatic
4. keep cloud-first as the default deterministic rerun path
5. make device pinning universal
6. build one machine-readable evidence ledger
7. add a stable local fallback path for authoritative smoke

These align with the current project’s complexity, team size, and release posture better than heavyweight enterprise process or broad new infrastructure.
