# WP-13 Usability Gate Packet Template

Last updated: 2026-05-03
Owner: Product
Support: QA, Design, Engineering, Marketing

## Purpose

Operational template for WP-13 closure and promotion decisions.
This packet complements technical gates with moderated usability outcomes.

## Authority And Closure Boundary

1. `human-moderated` is the preferred session mode for WP-13.
2. `ai-human-proxy` is the disclosed fallback mode when moderators are unavailable for the controlled Play Store MVP.
3. `ai-human-proxy` sessions must use the approved small-discovery-path bundle, the same workflow script, the same lane inputs, and the same reporting schema/templates as human moderation.
4. `ai-human-proxy` packets may close the WP-13 moderation-backed leg for the controlled MVP when current machine-verifiable lane evidence is already in hand and the fallback reason is recorded in this packet.
5. `ai-human-proxy` packets cannot close missing machine-verifiable evidence, broader public-launch research needs, or undisclosed trust/claim expansions beyond the controlled MVP.
6. Keep machine-verifiable evidence linked here, but separate from the human/proxy interpretation recorded in this packet.

## Cohort Metadata

1. Session mode (`human-moderated` or `ai-human-proxy`):
2. Moderator availability status / fallback reason:
3. Proxy operator(s) (if `ai-human-proxy`):
4. Small-discovery-path setup tool/version + artifact root (once tooling exists):
5. Proxy bundle manifest path (if `ai-human-proxy`):
6. Cohort id:
7. Build id + commit:
8. Build variant/build id details:
9. Device set used (required-tier + best-effort):
10. Session window (UTC):
11. Moderator(s):
12. Run owner (`run_owner`):
13. Run host (`run_host`):

## Lane Pass IDs (Required)

1. `android-instrumented` pass id:
2. `maestro` pass id:
3. `journey` pass id:
4. Journey report path (`journey-report.json`):
5. Journey summary path (`journey-summary.md`):
6. Latest send-capture step phase (`completed` required):
7. Latest send-capture elapsed (ms):
8. Latest send-capture runtime status/backend/model:
9. Placeholder visible at SLA checkpoint (`false` required):
10. Response visible at SLA checkpoint (`true` required):
11. Response role + non-empty (`assistant`/`system`, `true` required):
12. First token seen before completion (`true` for happy path):
13. Request id / finish reason / terminal event seen (`request_id`, `finish_reason`, `terminal_event_seen=true`):
14. First token / completion timing fields (`first_token_ms`, `completion_ms`):
15. Timeout/cancel UX code observed (expected `UI-RUNTIME-001` on timeout paths):
16. Timeout recovery CTA path verified (`retry`, `refresh`, `fix model setup`):

## Task Script (Workflow A/B/C)

Each participant or proxy reviewer executes without intervention:

1. Workflow A - Offline quick answer.
2. Workflow B - Local tool task.
3. Workflow C - Context follow-up (optional image).

Record per participant:

1. completion (`yes`/`no`)
2. time-to-complete (seconds)
3. blocker reason (if failed)
4. confusion notes (runtime/model/privacy)

## Quantitative Gate Table

| Metric | Threshold | Actual | Pass |
|---|---|---|---|
| Workflow A completion (`human-moderated n=5+`, `ai-human-proxy n=4+`) | `>= 90%` |  |  |
| Workflow B completion (`human-moderated n=5+`, `ai-human-proxy n=4+`) | `>= 90%` |  |  |
| Workflow C completion (`human-moderated n=5+`, `ai-human-proxy n=4+`) | `>= 80%` |  |  |
| Onboarding completion | `>= 80%` |  |  |
| Recovery completion (`NotReady -> Ready`) | `>= 85%` |  |  |
| Runtime/model confusion reports | `<= 10%` |  |  |
| Privacy confusion reports | `<= 10%` |  |  |
| Critical UX blockers (`S0`/`S1`) | `0 open` |  |  |

## UX Event Evidence (Recovery Contract)

Include sampled rows or export references for:

1. `onboarding_completed`
2. `runtime_not_ready_visible`
3. `model_setup_opened`
4. `model_import_started`
5. `model_download_started`
6. `model_version_activated`
7. `runtime_ready`
8. `first_useful_answer_ms`

## Qualitative Synthesis (PROD-08 Taxonomy)

For each category, provide top findings and owner:

1. usability
2. comprehension
3. reliability-perceived
4. performance-perceived
5. trust/privacy perception

## Evidence Links

1. QA weekly matrix run:
2. User session notes:
3. Video/screenshot proof set:
4. Raw artifact root:
5. Claim-map row ids impacted (`PROD-10`):

## Soft-Gate Decision Inputs

1. Pilot cohort size:
2. Pilot duration:
3. Hard-stop triggered (`yes`/`no`):
4. If yes, blocker + owner + ETA:
5. Recommendation scope (`promote`/`iterate`/`hold`):

For `ai-human-proxy` packets:

1. `promote` is allowed only for the controlled Play Store MVP fallback path.
2. The decision log must explicitly disclose that the moderation-backed leg was closed by proxy due to moderator unavailability.
3. Any recommendation remains bounded to the verified claim surface and current machine-verifiable evidence.

## Decision

1. Product recommendation (`promote`/`iterate`/`hold`):
2. QA concurrence (`yes`/`no`):
3. Engineering concurrence (`yes`/`no`):
4. Marketing concurrence (`yes`/`no`):
5. Conditions to close WP-13 or retire the proxy fallback (if not promote):
6. Decision date (UTC):
