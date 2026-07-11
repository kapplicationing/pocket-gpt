# Play Store Submission Readiness

Last updated: 2026-07-11
Owner: Product + Marketing + Release Ops

Mutable ticket ordering stays in `docs/operations/execution-board.md`. This document defines the package contract.

## Current Decision

The controlled-MVP product gate is promoted. The repository now contains the release identity, build/signing contract, rollout/rollback notes, claim-safe listing copy, privacy/support package, Play metadata worksheet, asset shotlist, and scorecard packet.

Submission is not complete. The app still lacks Play-required in-app AI-output reporting and evidenced restricted-content prevention, no upload signing identity is configured in the repository, no Android canary is connected, no fresh listing assets are approved, and no Play artifact/review record exists.

## Required Package

| Area | Prepared | Required Before Upload |
|---|---|---|
| Product gate | `PROD-10` retained `8/8 PASS` | Re-run launch-readiness on final SHA |
| Release identity | `0.1.0` / `20260711`, internal track | Confirm Play accepts the version code |
| Build path | Signed/unsigned modes, checksums, provenance | Existing upload key + clean signed build |
| Install | Device-pinned validation command | Connected physical canary + passing install/launch |
| Support | Email, URL, issue template, incident playbook | Check live inbox/issues for open `S0`/`S1` |
| Privacy/policy | Public policy draft + metadata worksheet | Public URL check + live Console declarations |
| Generative-AI safety | `PROD-14` gap and acceptance contract documented | In-app developer reporting, real intake, restricted-content prevention, tests, Privacy/Data Safety parity |
| Listing copy | Short/full copy and `CH-*` freeze | Paste and verify in Console |
| Assets | Seven-shot plan and manifest | Fresh capture, icon/graphic exports, dual approval |
| Scorecard | Run-01 packet prepared | Execute seven days after first real install |

## Artifact Contract

1. Build from a clean reviewed `main` matching `origin/main`.
2. Use `config/release/controlled-mvp.json` as the identity/config source.
3. Use the existing secure upload key through environment variables; never commit it, generate a replacement implicitly, or use debug signing.
4. Treat `--unsigned` output as non-publishable inspection material.
5. A publishable package includes a verified signed AAB, signed release APK, SHA-256 checksums, provenance JSON, physical-canary install/start/version proof, and the separate manual first-run/send checklist.
6. Promote the same accepted AAB between tracks rather than rebuilding for a wider rollout.

## Listing And Claim Contract

1. Use only the exact copy in `docs/ux/play-store-listing-spec.md`.
2. Every screenshot/video maps to the asset manifest and a passing `PROD-10` row.
3. Voice remains limited beta and absent from public listing claims/assets.
4. Single-image copy must not imply multi-image or document analysis.
5. Prompt-shortcut copy must not imply unrestricted autonomous tool dispatch.
6. Do not publish universal speed/device claims or the unimplemented delete-all/retention controls.

## Stop Rules

Do not upload or widen rollout when:

1. a required `PROD-10` row is not passing;
2. the signed artifact or physical install proof is missing;
3. source SHA/version/checksum provenance is ambiguous;
4. a fresh asset lacks Product and Marketing approval;
5. a listing line exceeds `MKT-10` or privacy parity;
6. an `S0` or unmitigated `S1` incident is open; or
7. `PROD-14` in-app reporting or restricted-content prevention is not evidenced; or
8. Play policy/contact declarations cannot be answered from verified behavior.

Use `docs/operations/publication-closeout-checklist.md` for the remaining operator actions and `docs/operations/play-console-metadata.md` for typed Console inputs.
