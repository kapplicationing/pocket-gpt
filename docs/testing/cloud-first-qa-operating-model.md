# Cloud-First QA Operating Model

Last updated: 2026-05-03
Owner: QA + Engineering  
Support: Product

## Goal

Move repeatable QA evidence collection to cloud/device automation wherever the outcome is machine-verifiable, while keeping final release judgment owner-driven. When moderators are unavailable, allow a disclosed `AI human-proxy` fallback mode that uses the same workflows, reporting utilities, and packet shape as human moderation for the controlled Play Store MVP.

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
6. When moderators are unavailable, a disclosed `AI human-proxy` pass may be used to execute the same packet workflow a human moderator would use, including packet completion, facilitator/script cleanup, blocker discovery, and measured fallback recommendations for the controlled MVP.

## What Cannot Be Replaced

1. Machine-verifiable lane evidence such as authoritative `android-instrumented` and strict `journey` rows; cloud can reduce risk and drive triage, but does not replace those gate sources.
2. Any claim that expands beyond the controlled-MVP scope or depends on undisclosed public promises.
3. Final release-owner judgment when evidence is mixed, contradictory, or incomplete.
4. Broad-public-launch trust claims; `AI human-proxy` fallback is a controlled-MVP mechanism, not a general substitute for live-user research.
5. Any attempt to count proxy output as machine-verifiable evidence.

## Operating Rules

1. Treat cloud/agent runs as the default for machine-verifiable QA evidence.
2. Keep one narrow physical-device canary lane for OEM/runtime issues that cloud cannot fully prove, and use it after the cloud-first pass rather than in parallel by default.
3. Use agents as operators and analysts by default, and as disclosed human proxies only through the approved bundle/setup path.
4. Record only validated outcomes in gate docs; proxy judgments must stay tied to visible behavior, deterministic artifacts, and the WP-13 packet schema.
5. Escalate to human moderation only after the deterministic technical gate is green or when the remaining question is subjective.
6. Prefer code/contract closure before widening QA fan-out; broad reruns are not a substitute for unfinished engineering work.
7. When a wireless Samsung canary fails before the first app-owned step, classify it as a harness/environment blocker until the evidence shows the failure moved inside the app.
8. Count hosted output as current-window launch evidence only when the uploaded APK was rebuilt from the branch tip under review.
9. Keep the authority split explicit in every gate review: cloud-first is the default machine-verifiable execution path, but `android-instrumented` and strict `journey` still require their own current-window proof.
10. Keep `AI human-proxy` output on its own disclosed track: it may satisfy the moderation-backed leg for the controlled MVP, but it never substitutes for machine-verifiable evidence and must remain labeled as proxy-derived in launch decisions.

## AI Human-Proxy Mode

Use this mode only when all of the following are true:

1. human moderators are temporarily unavailable,
2. the deterministic technical path is materially stable enough to make proxy discovery useful,
3. the packet is labeled `AI human-proxy`,
4. the review uses the approved small-discovery-path setup bundle,
5. the same WP-13 workflow script and reporting schema are used,
6. and the decision scope is the controlled Play Store MVP rather than a broader public expansion.

This mode can close only:

1. the moderation-backed WP-13 packet for the controlled MVP,
2. facilitator/script cleanup,
3. obvious-blocker discovery logs and issue filing,
4. clearly disclosed `promote`/`iterate`/`hold` recommendations for the controlled MVP,
5. and required `PROD-10` rows whose only remaining dependency is the moderation-backed packet.

This mode cannot close:

1. any missing machine-verifiable lane evidence,
2. broader public-launch claims or post-MVP expansion decisions,
3. undisclosed privacy/trust marketing claims that were never observed in the proxy packet,
4. or contradictions between deterministic evidence and the proxy packet.

Required setup dependency:

1. Use the small-discovery-path setup bundle as a prerequisite for `AI human-proxy` sessions so setup variance stays bounded and proxy sessions start from one comparable discovery contract.

## Implementation Plan

### Phase 1: Separate the gate types

1. Label every QA gate as either `machine-verifiable` or `moderation-backed`.
2. Update test/gate docs so cloud runs are the default path for machine-verifiable gates.
3. Keep WP-13 packet fields as the only source of moderation-backed usability evidence.
4. Document `AI human-proxy` as the disclosed fallback mode for the moderation-backed leg, not as a third gate type.

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
4. Keep the release owner in the loop for ambiguity, disclosure approval, and release decisions.

### Phase 4: Reduce moderator dependency without weakening evidence discipline

1. Pre-screen flows with cloud/agent runs before any moderated session.
2. Enter human moderation only after scripted flows are stable enough to be worth measuring.
3. Use the moderated or proxy packet to answer questions that automation cannot answer, with proxy sessions disclosed as fallback.

## Unblock Sequence For Current Work

1. Keep the current blocker chain honest: the old retained provisioning blocker was narrowed to missing `mmproj` sync in `devctl` preflight and has been addressed in local code; local authoritative onboarding proof and a current-window `android-instrumented` pass now exist, but the remaining open work is not just one hosted `send-after-ready` verdict. The latest targeted cloud reruns are still red across `send-after-ready`, runtime-ready smoke, and current account-2 model-management reruns after earlier same-day passes, while strict `journey` still lacks current-window proof and moderation-backed evidence remains incomplete.
2. Run the required hosted/default machine-verifiable flows first and attach pass IDs, upload ids, and artifact roots to the active tickets.
3. If hosted `send-after-ready` returns `infra_status_fetch_failed`, polls with blank status fields, or an older preserved upload id no longer resolves through the Maestro Cloud API, treat that as missing hosted verdict / stale provenance rather than a product regression. Preserve the old upload id as history, then rerun a fresh hosted/default or targeted `send-after-ready` flow and carry the new upload provenance forward.
4. Use agents to inspect failures, compare deltas, and narrow remaining issues.
5. Preserve the current authoritative `android-instrumented` artifact set unless the code under review invalidates it, and re-run strict `journey` once the cloud/default technical path is materially stable. Do not teach the old local Maestro kickoff/bootstrap failure as the active strict-`journey` blocker unless the rerun actually dies before instrumentation starts.
6. Run one narrow physical-device canary after the cloud path is materially stable, and do not let wireless Samsung harness noise overwrite a clean hosted result.
7. Run the human-moderated or `AI human-proxy` WP-13 packet only after the scripted gates are green or the remaining question is explicitly subjective.
8. Publish a promote/iterate/hold recommendation only after both technical and moderation-backed evidence are present.

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
2. WP-13 uses either the minimum necessary human sample or the disclosed `AI human-proxy` fallback path with the same packet/reporting contract.
3. New QA work uses the same taxonomy so future features do not recreate the same manual bottlenecks.
