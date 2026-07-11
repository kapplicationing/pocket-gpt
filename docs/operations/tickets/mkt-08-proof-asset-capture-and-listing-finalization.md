# MKT-08 Proof Asset Capture and Listing Finalization

Last updated: 2026-07-11
Owner: Marketing Ops
Support: Product, QA
Status: In Progress

## Objective

Capture claim-safe proof assets from the pilot build and finalize Play Store listing shotlist.

## Inputs

1. `docs/ux/play-store-listing-spec.md`
2. `docs/operations/runbooks/marketing/mkt-04-demo-asset-capture-runbook.md`
3. `docs/operations/tickets/prod-10-launch-gate-matrix.md`

## Deliverables

1. Final screenshot/video shotlist with story/claim mapping.
2. Asset QA checklist (clarity, legibility, no unsafe claims).
3. Evidence links for each published claim block.

## Acceptance

1. Every asset maps to a `PROD-10` matrix row.
2. No asset implies unsupported/publicly unvalidated behavior.
3. Final listing package is approved by Product + Marketing.
4. External-facing assets only use claim blocks allowed by `MKT-10` claim freeze.

## Current Closeout

Completed in repo:

1. Frozen seven-shot story, caption, claim, and gate mapping in `docs/ux/play-store-listing-spec.md`.
2. Machine-readable asset status in `docs/operations/assets/mkt-04/controlled-mvp-asset-manifest.json`.
3. Capture and approval checklist in `docs/operations/assets/mkt-04/README.md`.
4. Explicit rejection of the stale 15-screen QA reference pack for listing use.
5. Device-pinned seven-shot capture script with build/device metadata and checksums.
6. Deterministic draft Play listing icon and feature graphic with dimensions/checksums; approval remains open.

Still open:

1. connect and pin a current Android canary;
2. install the final release candidate;
3. capture the seven current `PocketAgent` states and optional video;
4. record Product + Marketing approval for screenshots, icon, and feature graphic in the asset manifest.

Do not mark `Done` until fresh asset files and both approvals exist.
