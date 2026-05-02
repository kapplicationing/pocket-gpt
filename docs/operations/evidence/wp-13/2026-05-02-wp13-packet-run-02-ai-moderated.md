# WP-13 Packet (Run-02, AI-Moderated)

Last updated: 2026-05-02
Owner: QA + Product
Tester kind: AI-agent (4 sub-agents) — PROD-12 deviation: humans still owed when reviewers are available.

## Cohort Metadata

- cloud-1 (maestro-cloud) on Maestro Cloud (account-1), build 94a62e51 from main
- cloud-2 (maestro-cloud) on Maestro Cloud (account-2), build 94a62e51 from main
- device-a51 (physical-device) on Samsung Galaxy A51 (SM-A515F), build 94a62e51 from main
- device-s22 (physical-device) on Samsung Galaxy S22 Ultra (SM-S906N), build 94a62e51 from main

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

## Decision
- AI-moderated recommendation: **hold**

## Per-Tester Trip Reports

- `cloud-1`: artifacts under `tmp/qa-agents/cloud-1/` (session ended 2026-05-02T10:08:10Z)
- `cloud-2`: artifacts under `tmp/qa-agents/cloud-2/` (session ended 2026-05-02T11:15:47Z)
- `device-a51`: artifacts under `tmp/qa-agents/device-a51/` (session ended 2026-05-02T10:05:02Z)
- `device-s22`: artifacts under `tmp/qa-agents/device-s22/` (session ended 2026-05-02T09:59:16Z)
