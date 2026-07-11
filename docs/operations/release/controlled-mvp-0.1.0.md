# PocketAgent Controlled MVP 0.1.0

Last updated: 2026-07-11
Owner: Release Ops
Lifecycle: Prepared; signed build, device install, assets, and Play submission pending

## Frozen Release Identity

- Product: `PocketAgent`
- Application id: `com.pocketagent.android`
- Version name: `0.1.0`
- Version code: `20260711`
- Initial Play track: `internal`
- Release scope: controlled MVP

Use the same approved AAB when promoting between Play tracks. Do not rebuild merely to move an accepted artifact from internal testing to a wider track.

## User-Facing Release Notes

PocketAgent brings local-first AI chat to supported Android devices. This controlled MVP includes streaming on-device answers, model download and import, local session continuity, prompt-first tool shortcuts, single-image contextual help, clear runtime status, and recovery guidance when a model is not ready.

## Claim Boundary

Public listing copy may cover:

1. local-first, on-device inference by default;
2. offline chat after a supported model is installed;
3. prompt-first local tool workflows;
4. one-image contextual help in a chat;
5. local session continuity;
6. model/runtime state and redacted diagnostics.

Do not advertise broad voice availability, multi-image or document analysis, cloud sync, universal device performance, or an in-app delete-all/retention-control surface.

## Rollout Plan

1. Upload the signed AAB to the Play internal-testing track.
2. Limit access to the named controlled-MVP tester list.
3. Confirm install/upgrade, first-run setup, one completed chat send, and support links on at least one clean physical canary.
4. Hold the artifact for 24 hours while reviewing crashes, blocked setup, send timeouts, and privacy/support reports.
5. Promote the same AAB only when there is no open `S0` or unmitigated `S1` incident.

## Stop And Rollback

Pause rollout immediately for a suspected privacy/security event, data loss, repeated startup crash, model setup dead end, or unrecoverable send failure.

For this first release there may be no earlier production artifact to restore. Stop distribution in Play Console, preserve the failed artifact and evidence, publish a corrected build with a higher version code, and tell installed testers the recovery steps. Do not instruct testers to uninstall unless local-data loss has been considered and communicated.

## Build Contract

Unsigned local inspection artifact:

```bash
bash scripts/release/build-controlled-mvp.sh --unsigned
```

Signed upload artifact and physical-canary install:

```bash
export POCKETAGENT_RELEASE_KEYSTORE=/absolute/path/to/pocketagent-upload.jks
export POCKETAGENT_RELEASE_STORE_PASSWORD='stored-outside-git'
export POCKETAGENT_RELEASE_KEY_ALIAS='stored-outside-git'
export POCKETAGENT_RELEASE_KEY_PASSWORD='stored-outside-git'
bash scripts/release/build-controlled-mvp.sh --device ANDROID_SERIAL
```

The signed command must run from a clean `main` whose `HEAD` matches the local `origin/main` ref. It emits the AAB, installable release APK, checksums, and a provenance manifest under ignored `dist/releases/`. Its device check proves install, successful `MainActivity` start, and package version; the operator must still complete the manual first-run/setup/send/support/privacy canary checklist.

## Current External Closeout

The repository does not contain or generate a release signing identity. Release Ops must provide the existing upload key through the four environment variables above. A Play developer account with two-step verification must exist before upload. Final screenshots and feature graphic still require fresh capture and approval under `docs/operations/assets/mkt-04/`.
