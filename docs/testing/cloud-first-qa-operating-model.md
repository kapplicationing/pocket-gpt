# Cloud-First QA Operating Model

Last updated: 2026-04-25  
Owner: QA + Engineering  
Support: Product

## Goal

Move repeatable QA evidence collection to cloud/device automation wherever the outcome is machine-verifiable, while keeping moderated usability and final release judgment human-owned.

## What Can Be Replaced

1. Required lane reruns for deterministic workflows:
   - `android-instrumented`
   - `maestro`
   - `journey`
   - `screenshot-pack`
   - lifecycle E2E flows
2. Artifact collection:
   - pass IDs
   - journey reports
   - screenshots
   - logcat
   - structured runtime snapshots
3. Regression detection for scripted flows:
   - first-run setup
   - send/timeout/cancel
   - model provisioning paths
   - manifest outage recovery
4. QA triage prework:
   - compare runs
   - isolate the first failing step
   - generate a reproducible failure summary

## What Cannot Be Replaced

1. Moderated usability evidence for WP-13.
2. Privacy/comprehension/trust judgment that depends on real participants.
3. Final promote/iterate/hold decision when evidence is mixed or incomplete.
4. Any claim that depends on subjective UX interpretation rather than a scripted pass/fail outcome.

## Operating Rules

1. Treat cloud/agent runs as the default for machine-verifiable QA evidence.
2. Keep one narrow physical-device canary lane for OEM/runtime issues that cloud cannot fully prove, and use it after the cloud-first pass rather than in parallel by default.
3. Use agents as operators and analysts, not as proxies for human users.
4. Record only validated outcomes in gate docs; do not convert agent preference or heuristic feedback into moderated-usability evidence.
5. Escalate to human moderation only after the deterministic technical gate is green or when the remaining question is subjective.
6. Prefer code/contract closure before widening QA fan-out; broad reruns are not a substitute for unfinished engineering work.

## Implementation Plan

### Phase 1: Separate the gate types

1. Label every QA gate as either `machine-verifiable` or `human-required`.
2. Update test/gate docs so cloud runs are the default path for machine-verifiable gates.
3. Keep WP-13 moderated packet fields as the only source of human usability evidence.

### Phase 2: Cloud-first execution

1. Route lane reruns to Maestro Cloud when the intent is regression evidence, not device forensics.
2. Standardize cloud runs to emit the same artifact set as local device runs.
3. Preserve raw logs and screenshots so QA can compare results across runs.
4. Use the physical device after the hosted pass to confirm the narrow OEM/runtime edge, not to rediscover already-proven cloud failures.

### Phase 3: Agent-assisted QA workflow

1. Use an agent to schedule runs, monitor artifacts, and summarize deltas.
2. Use the agent to open reports and inspect screenshots for obvious failures.
3. Use the agent to file or update the blocking issue with the exact failing step and evidence links.
4. Keep humans in the loop for ambiguity, UX judgment, and release decisions.

### Phase 4: Reduce human effort without removing humans

1. Pre-screen flows with cloud/agent runs before any moderated session.
2. Enter human moderation only after scripted flows are stable enough to be worth measuring.
3. Use the moderated packet to answer questions that automation cannot answer.

## Unblock Sequence For Current Work

1. Keep the current blocker chain honest: provisioning preflight is no longer the first live failure; the active deterministic blocker is the Maestro/runtime-ready contract leaving the app `Unloaded` in lifecycle/startup flows.
2. Clear that runtime-ready blocker, then run the required lanes through the cloud-first path and attach pass IDs and artifact roots to the active tickets.
3. Use agents to inspect failures, compare deltas, and narrow remaining issues.
4. Run one narrow physical-device canary after the cloud path is materially stable.
5. Run the moderated WP-13 packet only after the scripted gates are green or the remaining question is explicitly subjective.
6. Publish a promote/iterate/hold recommendation only after both technical and human-required evidence are present.

## How Future Work Should Be Structured

1. Every new feature that changes user flow should ship with a machine-verifiable cloud flow.
2. If the feature needs human interpretation, define the smallest possible moderated sample up front.
3. Tie each claim to one of three evidence types:
   - cloud/device automation
   - human moderation
   - static contract/test evidence
4. Avoid creating new QA work that depends on ad hoc manual device runs when the same signal can be collected automatically.

## Exit Criteria

1. The current lane reruns are owned by cloud/device automation with stable artifacts.
2. WP-13 human moderation is reduced to the minimum necessary sample.
3. New QA work uses the same taxonomy so future features do not recreate the same manual bottlenecks.
