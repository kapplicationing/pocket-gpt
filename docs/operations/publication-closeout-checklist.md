# Controlled-MVP Publication Closeout Checklist

Last updated: 2026-05-04
Owner: Product + Release Ops

Use this checklist after `PROD-10` is already `Promote`.

## Already Complete In Repo

1. `PROD-10` required rows are all `PASS`.
2. Current authoritative evidence roots are preserved for `android-instrumented`, hosted/default targeted cloud proofs, and strict S22 `journey`.
3. The disclosed `AI human-proxy` packet is complete and currently lands on `promote`.
4. `SEC-02` is closed for the publish-safe claim set.

## Still Needed From Humans / Real-World Ops

1. Pick the release version code/name for the build you will actually submit.
2. Build the final release bundle/APK and verify it installs on the selected canary device.
3. Capture final claim-safe screenshots/video assets and store the approved raw assets under `docs/operations/assets/mkt-04/`.
4. Finalize support metadata:
   - contact email
   - support path
   - incident summary / weekly rollout note
5. Finalize Play Console inputs:
   - privacy policy URL
   - data safety answers
   - content rating
   - app category
   - selected track
6. Apply the frozen claim set to the final listing copy and assets.
7. Clean the worktree, audit `origin/main..main`, and push `main`.
8. Upload the final release bundle and listing assets to the selected Play track.

## Suggested Operator Sequence

1. Confirm `bash scripts/dev/launch-readiness.sh` still reports `Promote`.
2. Preserve any local in-progress work so the publication worktree is clean.
3. Push local `main` only when the publish payload is explicit.
4. Build and install-validate the final release package.
5. Capture and approve listing assets.
6. Fill the Play Console metadata fields and upload the release package.
7. Record the publication date, track, and final artifact identifiers in the release note or ticket update.

## Non-Goals

1. Do not widen launch claims beyond the frozen controlled-MVP scope.
2. Do not reopen wireless local Maestro as launch authority.
3. Do not treat `MKT-09` as a blocker for controlled-MVP publication unless you explicitly want the 7-day scorecard before rollout.
