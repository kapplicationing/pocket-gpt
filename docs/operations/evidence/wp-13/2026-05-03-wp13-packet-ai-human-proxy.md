# WP-13 Packet (AI Human-Proxy)

Last updated: 2026-05-03
Owner: QA + Product
Tester kind: AI human-proxy (subagent reviewers over deterministic run artifacts)

## Authority Note

This packet is retained as AI human-proxy evidence.

1. It applies the same workflows, recovery journeys, and reporting utilities a human moderator would use.
2. It is valid for launch-readiness review when human moderators are unavailable.
3. It remains proxy evidence and should stay clearly labeled as such in governance and claim decisions.

## Current Rerun Status

This packet has been refreshed against the current human-proxy bundle path, but the current rerun did **not** produce a new full four-tester matrix.

1. Current repo head for rerun attempts: `7404747a` on `main`.
2. The device-side runner is no longer pinned to stale wireless aliases; `tools/qa-agents/run_ai_tester.py` now resolves the live serial from `adb devices -l` by model before install/Maestro.
3. The corrected S22 rerun reached install and `7001` reverse-port setup on live serial `192.168.1.38:36483`, then stalled inside `scripts/dev/maestro-local-bootstrap.sh` before any WP-13 flow output or skeleton was written. Artifact root reserved for that attempt: `tmp/qa-agents/device-s22/20260503T152401Z/`.
4. The current cloud rerun uploaded the current APK and preserved fresh hosted provenance in `tmp/qa-agents/cloud-1/20260503T152529Z/onboarding.log`, including app binary id `e573dd0d2a5c26f1f70f355d8f46a178c7e458c5` and upload id `mupload_01kqq747x9ea0ays8hv7cy2nzv`, but the command never returned a verdict before manual classification pivot.
5. Follow-up async classification probes confirmed the current CLI constraint `Cannot use --format with --async` in `tmp/qa-agents/cloud-1/20260503T153000Z-async/onboarding.log` and `tmp/qa-agents/cloud-2/20260503T153000Z-async/onboarding.log`.
6. Because the current rerun path is still not verdict-producing on either device or cloud, the last complete four-tester proxy matrix is retained below as baseline only. It is useful as a prior measured hold, but it is **not** a fresh rerun result.

## Current Decision

- AI human-proxy recommendation: **hold**

Current hold rationale:

1. The current proxy bundle path still cannot produce a fresh four-tester verdict set on the current build.
2. Device execution is blocked in local Maestro bootstrap before any workflow evidence is emitted.
3. Cloud execution can preserve fresh upload provenance, but the current runner path still does not return a verdict-bearing result for the packet.
4. The retained last-complete proxy matrix below also remains a hold baseline, so nothing in the current rerun supports a launch upgrade.

## Current Rerun Evidence

- S22 current device attempt: `tmp/qa-agents/device-s22/20260503T152401Z/`
- Cloud-1 current hosted attempt: `tmp/qa-agents/cloud-1/20260503T152529Z/onboarding.log`
- Cloud-1 async classification attempt: `tmp/qa-agents/cloud-1/20260503T153000Z-async/onboarding.log`
- Cloud-2 async classification attempt: `tmp/qa-agents/cloud-2/20260503T153000Z-async/onboarding.log`

## Retained Last Complete Matrix (Baseline Only)

The cohort metadata, quantitative gate table, and per-tester trip reports below are the most recent complete proxy matrix retained in the repo. They remain useful as a measured hold baseline, but they predate the current rerun attempt above.

## Cohort Metadata

- cloud-1 (maestro-cloud) on Maestro Cloud (account-1), build 94a62e51 from main
- cloud-2 (maestro-cloud) on Maestro Cloud (account-2), build 94a62e51 from main
- device-a51 (physical-device) on Samsung Galaxy A51 (SM-A515F), build 7404747a from main
- device-s22 (physical-device) on Samsung Galaxy S22 Ultra (SM-S906N), build 7404747a from main

## Quantitative Gate Table

| Metric | Threshold | Actual | Pass |
|---|---|---|---|
| Workflow A completion (n=4 AI testers) | `>= 90.0` | 0.0 | FAIL |
| Workflow B completion | `>= 90.0` | 0.0 | FAIL |
| Workflow C completion | `>= 80.0` | 0.0 | FAIL |
| Recovery completion (NotReady→Ready) | `>= 85.0` | 0.0 | FAIL |
| Stuck-send recovery completion | `>= 85.0` | 0.0 | FAIL |
| Manifest outage recovery completion | `>= 85.0` | 0.0 | FAIL |
| Runtime confusion % | `<= 10.0` | 36.9 | FAIL |
| Privacy confusion % | `<= 10.0` | 0.0 | PASS |
| Critical UX blockers (S0+S1 count) | `<= 0.0` | 12.0 | FAIL |

## Per-Tester Trip Reports

- `cloud-1`: artifacts under `tmp/qa-agents/cloud-1/` (session ended 2026-05-02T10:08:10Z)
- `cloud-2`: artifacts under `tmp/qa-agents/cloud-2/` (session ended 2026-05-02T11:15:47Z)
- `device-a51`: artifacts under `tmp/qa-agents/device-a51/` (session ended 2026-05-03T09:57:28Z)
- `device-s22`: artifacts under `tmp/qa-agents/device-s22/` (session ended 2026-05-03T09:53:42Z)
