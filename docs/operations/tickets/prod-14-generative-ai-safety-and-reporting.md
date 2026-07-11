# PROD-14 Generative-AI Safety And In-App Reporting

Last updated: 2026-07-11
Owner: Product + Security + Android
Support: Release Ops, Legal/Policy
Status: Blocked

## Objective

Meet Google Play's Generative AI Content policy before any Play upload. PocketAgent is a text-to-text conversational generative-AI app, so the submitted binary must both prevent prohibited output and let a user report or flag offensive output to the developer without leaving the app.

Policy source: `https://support.google.com/googleplay/android-developer/answer/13985936?hl=en`

## Current Gap

1. PocketAgent has no in-app report or flag action on generated responses.
2. The public GitHub/email support paths require leaving the app and do not satisfy this policy control.
3. The current runtime has tool-schema safety controls but no evidenced restricted-content prevention/filtering contract for general model output.
4. No developer-controlled report intake, retention policy, abuse workflow, or moderation-feedback loop is configured.

Do not add a decorative flag or `mailto:` shortcut and call this complete. The report must reach a developer-controlled intake without leaving the app, and the resulting reports must inform filtering/moderation work.

## Blocking Decisions

Product, Security, and Release Ops must provide:

1. the developer-controlled report endpoint and accountable owner;
2. the minimal report payload, authentication/abuse controls, retention window, deletion path, and incident SLA;
3. the restricted-content categories and the local/runtime prevention behavior;
4. the policy for whether prompt/response text is excluded by default or included only after explicit user review and consent;
5. the Data Safety and privacy-policy changes required by the resulting developer data collection.

## Acceptance

1. Every generated assistant response exposes an accessible in-app `Report` or `Flag` action.
2. The in-app form lets the user select a reason, review exactly what will be sent, add optional context, and cancel without transmission.
3. Submission reaches the approved developer-controlled intake without opening another app and returns an in-app receipt or actionable retry state.
4. The implementation minimizes report data, protects the endpoint from abuse, defines retention/deletion, and never silently uploads unrelated chat history.
5. Restricted-content prevention/filtering behavior is explicit, fail-safe, and covered by adversarial tests for the policy categories approved by Product/Security.
6. Received reports enter an owned moderation loop and can drive filter, prompt-policy, model, or release changes.
7. Unit, Compose/instrumentation, offline/error, accessibility, and end-to-end intake tests pass on the final release candidate.
8. `PRIVACY.md`, Play Data Safety answers, support/incident docs, claim copy, and `PROD-13` evidence match the actual reporting data flow.
9. Release Ops records the policy-control screenshots and final Play declaration evidence.

## Exit Rule

`PROD-14` remains blocking until the real intake and restricted-content controls pass end to end. A local-only flag, an external support link, an unowned endpoint, or documentation without product behavior does not close it.
