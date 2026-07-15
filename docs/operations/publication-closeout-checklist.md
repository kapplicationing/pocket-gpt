# Controlled-MVP Publication Closeout Checklist

Last updated: 2026-07-11
Owner: Product + Release Ops

Use this operator checklist after `PROD-10` reports `Promote`. Repository preparation is substantially complete; unchecked items below still need product controls, real credentials, hardware, approvals, or Play Console state.

## Complete In Repo

- [x] `PROD-10` required rows are `8/8 PASS` in the retained controlled-MVP decision.
- [x] Release identity is frozen as PocketAgent `0.1.0` (`20260711`), package `com.pocketagent.android`, initial track `internal`.
- [x] Signed and explicitly unsigned build paths are separate and reproducible through `scripts/release/build-controlled-mvp.sh`.
- [x] Rollout/rollback notes exist in `docs/operations/release/controlled-mvp-0.1.0.md`.
- [x] Public privacy/support pages, support email, issue intake, incident playbook, and weekly summary template exist.
- [x] Short/full listing copy and `CH-01` through `CH-07` claim blocks are frozen.
- [x] Seven-shot capture plan, QA checklist, asset manifest, and stale-reference rejection are recorded.
- [x] Play metadata and Data safety working notes exist in `docs/operations/play-console-metadata.md`.
- [x] At the final July 11 refresh, the closeout branch was rebased onto `origin/main` at `f8f7e5ab`; there was no unpublished main delta.
- [ ] Review and merge the isolated `codex/release-closeout` payload before creating the signed artifact.

## Release Artifact

- [ ] Store the existing upload keystore outside Git and expose all four `POCKETAGENT_RELEASE_*` signing variables.
- [ ] From the final clean reviewed `main`, run `bash scripts/release/build-controlled-mvp.sh --device ANDROID_SERIAL`.
- [ ] Confirm the script reports a `signed-upload` AAB, signed APK, SHA-256 checksums, provenance JSON, and passed install/start/version validation.
- [ ] Open PocketAgent on the canary and verify first-run setup, one completed send, and privacy copy.
- [ ] Verify the public `SUPPORT.md` and configured Play support URL resolve without authentication after merge.
- [ ] Record the final Git SHA and artifact checksums in the submission record.

An unsigned AAB from `--unsigned` is local inspection output only. Never upload it to Play and never substitute the debug keystore for the upload identity.

## Generative-AI Policy Controls

- [ ] Close `PROD-14` with restricted-content prevention for general model output.
- [ ] Provide an accessible in-app report/flag action for generated responses.
- [ ] Prove the report reaches a developer-controlled intake without leaving PocketAgent and returns an in-app receipt/retry state.
- [ ] Record report payload, consent, abuse protection, retention/deletion, incident owner, and moderation-feedback behavior.
- [ ] Update `PRIVACY.md` and live Data Safety answers for the actual report data flow.
- [ ] Attach adversarial safety, Compose/instrumentation, accessibility, offline/error, and intake E2E evidence.

## Listing Assets

- [ ] Capture all seven current PocketAgent states on the final release candidate with the pinned-device command in `docs/operations/assets/mkt-04/README.md`.
- [x] Produce deterministic draft Play listing icon and feature graphic files with provenance.
- [ ] Reject every old-name, fixture-copy, overlapping-modal, blank-image, or sensitive-data capture.
- [ ] Record Product and Marketing approval for screenshots, icon, and feature graphic in `controlled-mvp-asset-manifest.json`.
- [ ] Confirm the live Play Console accepts each file's current format/dimensions.

## Play Console

- [ ] Complete developer-account enrollment and two-step verification.
- [ ] Create the PocketAgent app record with the frozen package id/default language/category.
- [ ] Verify the public `PRIVACY.md` and `SUPPORT.md` URLs after the release commit is published.
- [ ] Complete the live App content, Data safety, content rating, target-audience, permissions, and app-access forms from the current binary behavior.
- [ ] Create the named internal-tester cohort.
- [ ] Upload the signed AAB, listing copy, and approved assets.
- [ ] Record Play's artifact id, review state, tester URL, and rollout timestamp.

## Final Decision

- [ ] Run `bash scripts/dev/launch-readiness.sh` and confirm the gate remains `promoted`.
- [ ] Check both public issues and the private support inbox; require zero open `S0` and zero unmitigated `S1` incidents.
- [ ] Confirm no listing line exceeds the frozen claim set and voice remains excluded from public assets.
- [ ] Publish only to the internal-testing cohort first.
- [ ] Start the prepared `MKT-09` seven-day window from the first successful Play install.

Do not treat `MKT-09` as a blocker for the initial controlled upload. Do not call publication complete until Play has accepted the signed artifact and the install/asset/metadata records are filled.
