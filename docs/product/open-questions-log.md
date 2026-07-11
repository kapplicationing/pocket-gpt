# Open Questions Log

Last updated: 2026-07-11

Track only unresolved cross-functional questions here. Move resolved decisions into PRD/roadmap/tickets and prune from this page.

## Active Questions

### Publication

1. Which enrolled Play developer identity and existing upload key will own the controlled-MVP submission?
2. Which final Data Safety classifications does the live Play form require for user-initiated model-host traffic?
3. Which content rating does Play assign after the live AI-generated-content questionnaire is completed conservatively?
4. Which developer-controlled report endpoint, payload, retention/deletion policy, and moderation owner will close `PROD-14`?

### Rollout and expansion

1. What channel, start date, and next-cohort cap should `MKT-09` use after the default 25-tester, 7-day pilot?
2. At what expansion point must the disclosed AI human-proxy packet be replaced by a human-moderated usability packet?
3. Which Android device/OEM tiers must be qualified before expanding beyond the controlled cohort?

### Runtime and quality

1. What time-to-first-useful-answer threshold should turn advisory row `A-01` into a hard expansion gate?
2. Which send-capture latency thresholds are hard-blocking versus advisory on older devices after `QA-13` is operationalized?
3. When does the preserved long-prefill S22 result require a product limit instead of rollout monitoring?

### Privacy controls

1. Which post-MVP release should implement and evidence retention, reset, and per-tool privacy controls currently tracked as partial claim `P-04`?

## Resolved Canon

Do not reopen these without new contradictory evidence:

1. The canonical public/product name is **PocketAgent**; `PocketGPT` is a repository/history identifier.
2. The controlled-MVP claim set is offline local chat, prompt-first local tools, single-image Q&A, and verified privacy-first local behavior.
3. Voice is limited-beta/closed-track only.
4. The pilot default is 25 testers for 7 days under `PROD-09`.
5. Only `Verified` `SEC-02` privacy claims are publishable; `P-04` remains internal-only.
6. `MKT-09` is required for expansion learning, not for initial controlled-MVP publication.
7. Release identity is PocketAgent `0.1.0` (`20260711`), package `com.pocketagent.android`, initial track `internal`, category `Productivity`.
8. The public support contact, response/escalation rules, rollback owner, privacy URL, and support URL are frozen in the release package.

## Governance

1. Review weekly.
2. Convert resolved questions into ADRs, roadmap updates, or ticket/spec updates.
3. Keep this page under 20 active questions; prune resolved items immediately.
