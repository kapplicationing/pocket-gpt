# Play Store Submission Readiness

Last updated: 2026-05-04  
Owner: Product + Marketing + Release Ops

Mutable status stays in `docs/operations/execution-board.md`.

## Objective

Define the final cross-functional checklist that must be complete before PocketAgent is submitted to the Play Store.

## Hard Prerequisites

1. `PROD-10` required rows are all `PASS`.
2. Latest `android-instrumented` and `journey` pass IDs exist in the current release window, plus a valid current-window physical canary substitute while wireless Maestro remains harness-class only.
3. Latest journey send-capture report shows `phase=completed` and `placeholder_visible=false`.
4. WP-13 packet has measured values with no placeholder fields.
5. `SEC-02`, `PROD-11`, `MKT-08`, and `MKT-10` are closed enough to support publish-safe copy and support handling.

## Current State

As of 2026-05-04, the promotion gate is already green for the controlled MVP. The remaining work is submission/package execution:

1. final release build versioning and install validation,
2. final claim-safe listing assets,
3. current support/publication metadata,
4. and Play Console submission inputs.

## Release Package

1. Release build version code/name is final for the target track.
2. Release bundle or APK is reproducible from the documented command path.
3. Install validation succeeds on the canary device path.
4. Rollout plan and rollback notes are prepared.

## Listing And Asset Package

1. Short and full descriptions match `docs/ux/play-store-listing-spec.md`, including prompt-first tool wording and single-image claim bounds.
2. Every screenshot or video asset maps to a `PROD-10` row and uses only claim-safe copy.
3. No asset implies unsupported or unverified behavior, including broad voice-now, direct-tool-richness, or broader image-analysis claims.
4. Beta limitations and supported device-tier language match the retained evidence.
5. Any voice mention is limited to clearly labeled closed-track beta materials, not the broad public listing package.

## Privacy, Policy, And Support

1. Privacy policy URL matches the implemented privacy posture.
2. Contact email and support path are current.
3. Content rating, data safety, and category answers are aligned to verified behavior only.
4. Any unsupported privacy-control claim remains excluded from external copy.

## Release Decision Package

1. Launch-readiness report from `bash scripts/dev/launch-readiness.sh`
2. Current `PROD-10` decision state
3. Current WP-13 packet
4. Current claim map (`SEC-02` / `MKT-10`)
5. Current support-readiness packet (`PROD-11`)

## Stop Rules

Do not submit if any of the following remain true:

1. required `PROD-10` rows are failing or pending,
2. the latest lane evidence is blocked or stale,
3. WP-13 still contains `not collected` fields,
4. claim parity is partial for any external claim block being used,
5. or the release build cannot be reproduced cleanly.

## Human-Needed Closeout

Use `docs/operations/publication-closeout-checklist.md` as the operator-facing final sequence for publication and wrap-up.
