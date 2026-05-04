# WP-09 UX Feedback Taxonomy + Intake Policy

Last updated: 2026-03-10  
Owner: Product Ops (`PROD-08`)  
Lifecycle: Active

## Objective

Standardize UX feedback intake so beta signals are categorized consistently, routed quickly, and converted into release/promotion decisions without ambiguity.

## Taxonomy (Required Categories)

1. `usability`: user cannot complete intended flow efficiently.
2. `comprehension`: copy/status/controls are unclear or misleading.
3. `reliability_perceived`: user perceives instability (crash-like, stuck, inconsistent outputs) regardless of root cause.
4. `performance_perceived`: user perceives slowness/jank/lag, including long-thread rendering and streaming quality.
5. `trust_privacy_perception`: user confidence in local-first/privacy/offline behavior is reduced.

## Severity Framework

1. `UX-S0`: launch blocker, high user harm/trust break, no workaround.
2. `UX-S1`: severe friction in core flows (`UI-01..UI-10`), workaround limited.
3. `UX-S2`: meaningful issue with usable workaround.
4. `UX-S3`: minor friction/cosmetic concern.

## Owner Routing Matrix

| Taxonomy | Primary Owner | Secondary Owner | Default Queue |
|---|---|---|---|
| usability | Product | Engineering | `PROD-08` + ENG backlog |
| comprehension | Product | Marketing | Product messaging/copy queue |
| reliability_perceived | QA | Engineering | QA incident triage + ENG fix queue |
| performance_perceived | Engineering | QA | ENG perf lane + QA validation |
| trust_privacy_perception | Product | Security/QA | Product + security risk review |

## Intake Form Schema (Required Fields)

1. Feedback ID
2. Date/time (UTC)
3. Reporter/cohort
4. App build/version
5. Device model + Android version
6. Workflow (`chat`/`session`/`image`/`tool`/`advanced`/`other`)
7. Taxonomy category (single primary)
8. Severity (`UX-S0..UX-S3`)
9. Reproduction steps
10. Expected behavior
11. Observed behavior
12. Evidence links (`scripts/benchmarks/runs/...`, screenshots/video)
13. Initial owner
14. Status (`new`/`triaged`/`in_progress`/`resolved`/`closed`)

## SLA Policy

1. `UX-S0`: first response <= 30 minutes, owner assignment immediate.
2. `UX-S1`: first response <= 4 hours, owner assignment same day.
3. `UX-S2`: first response <= 1 business day.
4. `UX-S3`: first response <= 2 business days.

## Board and Workflow Mapping

1. Intake enters QA triage process aligned with:
   - `docs/operations/tickets/qa-13-send-capture-gate-operationalization.md`
   - `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-release-promotion-checklist.md`
2. Product owner confirms taxonomy/severity and assigns execution queue.
3. QA validates fix/regression impact and updates promotion checklist.
4. Weekly synthesis is published for Product/Engineering/QA/Marketing.

## Pilot Dry-Run Summary (2026-03-04)

1. Pilot source: WP-09 UI hardening + regression run packet.
2. Categories exercised:
   - `performance_perceived` (streaming/rendering persistence churn risk)
   - `trust_privacy_perception` (startup failure messaging clarity)
   - `reliability_perceived` (instrumented lane stability)
3. Result: taxonomy was sufficient to route each finding class to Product/Eng/QA with no category collision.
