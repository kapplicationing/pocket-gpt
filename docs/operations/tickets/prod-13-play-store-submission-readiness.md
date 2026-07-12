# PROD-13 Play Store Submission Readiness

Last updated: 2026-07-11
Owner: Product + Marketing
Support: QA, Engineering, Release Ops
Status: In Progress

## Objective

Package the current MVP into a Play Store-ready submission set once release gates support scheduling a date.

## Scope

1. Finalize the release package, rollout notes, and rollback notes.
2. Finalize listing metadata and claim-safe asset package.
3. Confirm privacy-policy, support, and Play Console inputs match verified behavior.
4. Ensure the final submission package is based on current evidence only.

## Inputs

1. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
2. `docs/operations/tickets/prod-11-pilot-support-incident-playbook.md`
3. `docs/operations/tickets/prod-12-human-required-gate-split.md`
4. `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md`
5. `docs/operations/tickets/mkt-08-proof-asset-capture-and-listing-finalization.md`
6. `docs/operations/tickets/mkt-10-claim-freeze-v1.md`
7. `docs/ux/play-store-listing-spec.md`
8. `docs/operations/play-store-submission-readiness.md`
9. `docs/operations/tickets/prod-14-generative-ai-safety-and-reporting.md`

## Acceptance

1. Final release package is reproducible and install-validated.
2. Listing copy and assets use only claim-safe, current behavior.
3. Privacy policy URL, support contact, category, and safety metadata are current.
4. `PROD-14` in-app reporting and restricted-content prevention pass end to end, with matching Privacy/Data Safety declarations.
5. Submission package is ready for the selected track immediately after a `promote` decision.

## Stop Rule

Do not mark this complete while required `PROD-10` rows are failing, WP-13 contains placeholder metrics, or `PROD-14` is not done.

## Current Closeout

Completed in repo:

1. Frozen release identity: PocketAgent `0.1.0` (`20260711`), package `com.pocketagent.android`, initial `internal` track.
2. Property/env-driven versioning and upload-signing contract in the Android build.
3. Reproducible signed/unsigned build distinction, checksum, provenance, and optional pinned-device install path in `scripts/release/build-controlled-mvp.sh`.
4. Rollout/rollback notes, public privacy/support pages, Play metadata worksheet, listing copy, asset manifest, and launcher icon resources.
5. The closeout branch was rebased onto `origin/main` at final refresh (`f8f7e5ab`); the build script rechecks a clean matching tree instead of relying on this note.

External completion still required:

1. provide the existing upload keystore and four signing variables; do not generate or use a debug signing identity;
2. run the signed build from the final clean reviewed commit;
3. install-validate on a connected physical canary;
4. complete Play developer-account enrollment/two-step verification and live policy forms;
5. implement and evidence the `PROD-14` in-app reporting/intake and restricted-content controls, then update Privacy/Data Safety;
6. capture and approve the fresh listing assets; and
7. upload the signed AAB and record Play's artifact/review identifiers.

The repository currently has no connected adb device, release signing values, Play service-account credential, developer-controlled report intake, or evidenced general-output safety control. Do not mark `Done` or call the AAB publishable until the product-policy controls, signed artifact, device proof, assets, and Console record exist.
