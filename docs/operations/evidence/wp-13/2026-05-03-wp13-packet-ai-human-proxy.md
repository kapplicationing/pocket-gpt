# WP-13 Packet (AI Human-Proxy)

Last updated: 2026-05-04
Owner: QA + Product
Tester kind: AI human-proxy (subagent reviewers over deterministic run artifacts)

## Authority Note

This packet is retained as AI human-proxy evidence.

1. It applies the same workflows, recovery journeys, and reporting utilities a human moderator would use.
2. It is valid for launch-readiness review when human moderators are unavailable.
3. It remains proxy evidence and should stay clearly labeled as such in governance and claim decisions.

## Cohort Metadata

- proxy-1 (ai-human-proxy-reviewer) on Cross-surface proxy review (hosted account-1 primary; local Maestro + S22 supporting), build 73cf5bc3 from main
- proxy-2 (ai-human-proxy-reviewer) on Composite evidence review (hosted account-1/account-2, local SER123, physical S22), build 73cf5bc3 from main
- proxy-3 (ai-human-proxy-reviewer) on AI human-proxy reviewer 3 (physical-device and journey emphasis), build 73cf5bc3 from main
- proxy-4 (ai-human-proxy-reviewer) on Proxy review over current local, hosted, and physical-device evidence, build 73cf5bc3 from main

## Quantitative Gate Table

| Metric | Threshold | Actual | Pass |
|---|---|---|---|
| Workflow A completion (n=4 AI testers) | `>= 90.0` | 100.0 | PASS |
| Workflow B completion | `>= 90.0` | 100.0 | PASS |
| Workflow C completion | `>= 80.0` | 100.0 | PASS |
| Recovery completion (NotReady→Ready) | `>= 85.0` | 100.0 | PASS |
| Stuck-send recovery completion | `>= 85.0` | 100.0 | PASS |
| Manifest outage recovery completion | `>= 85.0` | 100.0 | PASS |
| Runtime confusion % | `<= 10.0` | 9.6 | PASS |
| Privacy confusion % | `<= 10.0` | 2.2 | PASS |
| Critical UX blockers (S0+S1 count) | `<= 0.0` | 0.0 | PASS |

## Decision
- AI human-proxy recommendation: **promote**

## Per-Tester Trip Reports

- `proxy-1`: artifacts under `tmp/qa-agents/proxy-1/` (session ended 2026-05-04T05:56:20Z)
- `proxy-2`: artifacts under `tmp/qa-agents/proxy-2/` (session ended 2026-05-04T12:56:20Z)
- `proxy-3`: artifacts under `tmp/qa-agents/proxy-3/` (session ended 2026-05-04T05:56:20Z)
- `proxy-4`: artifacts under `tmp/qa-agents/proxy-4/` (session ended 2026-05-04T12:56:20Z)
