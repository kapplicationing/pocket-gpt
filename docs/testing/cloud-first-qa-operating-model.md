# Cloud-First QA Operating Model

Last updated: 2026-04-26
Owner: QA + Engineering  
Support: Product

## Goal

Move repeatable QA evidence collection to cloud/device automation wherever the outcome is machine-verifiable, while keeping moderated usability and final release judgment human-owned.

## What Can Be Replaced

1. Required lane reruns for deterministic workflows:
   - `maestro`
   - `journey`
   - `screenshot-pack`
   - lifecycle E2E flows
2. Parallel hosted smoke/model-management/send-after-ready fan-out where the outcome is pass/fail and the uploaded APK is known to come from the branch tip under review.
3. Artifact collection:
   - pass IDs
   - journey reports
   - screenshots
   - logcat
   - structured runtime snapshots
4. Regression detection for scripted flows:
   - first-run setup
   - send/timeout/cancel
   - model provisioning paths
   - manifest outage recovery
5. QA triage prework:
   - compare runs
   - isolate the first failing step
   - generate a reproducible failure summary

## What Cannot Be Replaced

1. Moderated usability evidence for WP-13.
2. Privacy/comprehension/trust judgment that depends on real participants.
3. Final promote/iterate/hold decision when evidence is mixed or incomplete.
4. Any claim that depends on subjective UX interpretation rather than a scripted pass/fail outcome.
5. Authoritative `android-instrumented` or strict `journey` rows; cloud can reduce risk and drive triage, but does not replace those gate sources.

## Operating Rules

1. Treat cloud/agent runs as the default for machine-verifiable QA evidence.
2. Keep one narrow physical-device canary lane for OEM/runtime issues that cloud cannot fully prove, and use it after the cloud-first pass rather than in parallel by default.
3. Use agents as operators and analysts, not as proxies for human users.
4. Record only validated outcomes in gate docs; do not convert agent preference or heuristic feedback into moderated-usability evidence.
5. Escalate to human moderation only after the deterministic technical gate is green or when the remaining question is subjective.
6. Prefer code/contract closure before widening QA fan-out; broad reruns are not a substitute for unfinished engineering work.
7. When a wireless Samsung canary fails before the first app-owned step, classify it as a harness/environment blocker until the evidence shows the failure moved inside the app.
8. Count hosted output as current-window launch evidence only when the uploaded APK was rebuilt from the branch tip under review.
9. Keep the authority split explicit in every gate review: cloud-first is the default machine-verifiable execution path, but `android-instrumented` and strict `journey` still require their own current-window proof.

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
5. If the local Samsung canary shows transport loss, app foreground loss, or `localhost:7001` connection failures before app logic begins, keep the launch gate anchored on the cloud result and track the device issue separately as harness instability.
6. If Maestro Cloud returns a null-status polling failure before any hosted results or JUnit output exist, classify it as infrastructure failure and preserve the upload id rather than recording it as a product regression.
7. Rebuild the APK from the branch tip before uploading to the cloud runner, and record that artifact provenance alongside the hosted pass id.

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

1. Keep the current blocker chain honest: the old retained provisioning blocker was narrowed to missing `mmproj` sync in `devctl` preflight and has been addressed in local code; local authoritative onboarding proof now exists, but the remaining open work is a clean hosted `send-after-ready` verdict, current-window authoritative lane evidence, and incomplete human-required evidence.
2. Run the required hosted/default machine-verifiable flows first and attach pass IDs, upload ids, and artifact roots to the active tickets.
3. If hosted `send-after-ready` returns `infra_status_fetch_failed` or otherwise lacks hosted results, keep it open as incomplete machine evidence rather than downgrading it into a product regression without a hosted verdict.
4. Use agents to inspect failures, compare deltas, and narrow remaining issues.
5. Re-run authoritative `android-instrumented` and strict `journey` separately once the cloud/default technical path is materially stable.
6. Run one narrow physical-device canary after the cloud path is materially stable, and do not let wireless Samsung harness noise overwrite a clean hosted result.
7. Run the moderated WP-13 packet only after the scripted gates are green or the remaining question is explicitly subjective.
8. Publish a promote/iterate/hold recommendation only after both technical and human-required evidence are present.

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
