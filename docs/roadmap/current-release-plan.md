# Current Release Plan

Last updated: 2026-07-11

This is the single planning view for the current PocketAgent release. Mutable
ticket status lives only in `docs/operations/execution-board.md`; generate the
current decision snapshot with `bash scripts/dev/launch-readiness.sh`.

## Outcome

Publish PocketAgent to a controlled Play Store track without adding features or
widening claims.

The product is past controlled-MVP implementation. The remaining release work
is Play generative-AI policy closure, direct timeout-to-retry closure, final
asset capture/approval, signed build/device proof, and Play Console execution.

## Current Position

The 2026-07-11 repository snapshot reports:

1. Controlled-MVP gate: `promoted`
2. `PROD-10` decision: `Promote`
3. Required rows passing: `8/8`
4. Publication readiness: `blocked`
5. Board blockers: `3`

The code baseline on `main` is synchronized with `origin/main` at this
checkpoint. The July 11 hardening milestone is published and adds durable model
imports, model/GGUF validation, serialized runtime ownership, cancellation and
close recovery, cloud-backup and Android 12+ transfer exclusions, and
interaction-performance gates.
The release-closeout payload is isolated on `codex/release-closeout` for review;
it does not include the engineer-owned runtime/CI worktree changes. After that
payload merges, recheck the live branch, worktree, and GitHub Actions state
immediately before building a release artifact.

## Closed For The Controlled MVP

1. Core offline chat, streaming, model management, prompt-first tools, memory,
   single-image Q&A, runtime status, and recovery behavior are implemented.
2. Required technical and usability rows in `PROD-10` pass.
3. The disclosed AI human-proxy WP-13 packet supplies the controlled-MVP
   moderation leg and lands on `promote`.
4. `SEC-02` closes privacy claim parity for the verified claim set.
5. The July Hugging Face/CI stack and the July 11 import/runtime hardening are on
   `main` and `origin/main`.
6. Branch hygiene for the release baseline is closed.
7. `QA-13` has the required weekly hardware send-capture gate and fail-closed
   issue routing.
8. `PROD-11` has public/private support paths, incident rules, and the rollout
   reporting template.
9. Release identity, claim-safe copy, metadata worksheets, build automation,
   adaptive icon, and draft store brand graphics are prepared in-repo.

## Active Release Work

### 1. Add required generative-AI safety and reporting

Close `PROD-14` with restricted-content prevention and an accessible in-app
report/flag flow that reaches a developer-controlled intake without leaving the
app. Define the collected payload, retention/deletion, abuse controls,
moderation loop, privacy notice, and Play Data Safety answers before building
the product path.

### 2. Apply frozen public claims

Close `MKT-10` using only the verified surface:

1. offline/local chat,
2. prompt-first local tools,
3. single-image Q&A,
4. on-device inference by default,
5. no hidden cloud upload in MVP workflows,
6. privacy-safe diagnostics and runtime transparency.

Exclude general-availability voice, broad image/document analysis, rich direct
tool launching, and unverified retention/reset/per-tool privacy controls.

### 3. Produce claim-safe assets

Close `MKT-08` with approved screenshots/video tied to the frozen claim set.
Assets must show current PocketAgent UI and must not imply unsupported scope.

### 4. Close direct timeout-to-retry recovery

Close `UX-13` only when the user can retry immediately after a send timeout
without refreshing runtime state and without losing session context. The
pre-fix probe failed this contract; `18fe52bf` now restores retryable runtime
readiness, but the exact two-send regression still must be retained and pass.

### 5. Produce and validate the release package

Close `PROD-13` by selecting the version code/name and Play track, producing the
final reproducible bundle, installing it on the selected canary, recording the
artifact identifier/checksum, and completing rollback notes.

### 6. Complete Play Console metadata and publish

Record and apply:

1. privacy-policy URL,
2. support contact/path,
3. Data Safety answers,
4. content rating,
5. category,
6. selected track and rollout percentage,
7. approved listing copy and assets.

Upload only after the publication checklist and launch snapshot agree.

## Release Sequence

1. Review and merge `codex/release-closeout` without pulling in unrelated
   engineer-owned changes.
2. Close `PROD-14` and `UX-13`, then close `MKT-10` and `MKT-08` against verified behavior.
3. Provide the existing upload key, connected physical canary, and enrolled
   Play developer account.
4. Build and install-validate the final release bundle from a clean, reviewed
   baseline.
5. Complete Play Console metadata.
6. Regenerate `bash scripts/dev/launch-readiness.sh` and confirm `Promote`,
   `8/8`, no blockers, and no new required action.
7. Upload and publish to the selected controlled track.
8. Record publication date, track, version, artifact identifiers, rollout
   owner, and rollback plan.

## Post-Rollout Work

`MKT-09` does not block initial controlled-MVP publication. Start it with the
real cohort after rollout and use its 7-day scorecard to decide whether to keep,
iterate, stop, or expand.

Before broader expansion:

1. Decide the hard time-to-first-useful-answer threshold for advisory row
   `A-01`.
2. Expand Android device/OEM qualification.
3. Replace or augment the disclosed proxy packet with human-moderated evidence
   when the rollout is no longer a controlled-MVP cohort.
4. Decide whether retention/reset/per-tool privacy controls enter the next
   product scope.
5. Keep voice limited-beta until device, privacy, and claim evidence support a
   wider promise.

## Stop Rules

Do not publish if any of these becomes true:

1. A required `PROD-10` row is no longer passing.
2. A new `UX-S0` or `UX-S1` blocker appears.
3. The final bundle cannot be reproduced or installed on the selected canary.
4. Listing copy or assets exceed the verified claim set.
5. Required support, policy, or Play Console metadata is missing.
6. The release worktree includes unrelated or unreviewed changes.

## Canonical Interfaces

- Mutable status: `docs/operations/execution-board.md`
- Generated readiness decision: `bash scripts/dev/launch-readiness.sh`
- Launch gate: `docs/operations/tickets/prod-10-launch-gate-matrix.md`
- Publication checklist: `docs/operations/publication-closeout-checklist.md`
- Submission readiness: `docs/operations/play-store-submission-readiness.md`
- Verified privacy claims: `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md`
- Feature and claim boundaries: `docs/product/feature-catalog.md`
