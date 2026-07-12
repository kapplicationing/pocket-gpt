# MKT-10 Claim Freeze v1

Last updated: 2026-07-11
Owner: Marketing
Support: Product, Security
Status: In Progress

## Objective

Freeze claim language for launch windows so only evidence-safe claims are used externally.

## Publish-Safe Claims (External)

1. Local-first runtime path with on-device inference default.
2. Offline quick-answer and prompt-first local tool utility for validated MVP workflows.
3. Single-image attach/contextual image help for validated in-thread workflows.
4. Deterministic runtime status visibility (`Not ready`, `Loading`, `Ready`, `Error`).
5. Session continuity and local diagnostics export.

## Internal-Only Claims (Not Yet External)

1. Broader public-launch or non-proxy trust claims that go beyond the current disclosed controlled-MVP packet.
2. Privacy-control depth claims without full claim parity verification (`SEC-02` partial rows).
3. Voice activation/STT/TTS claims beyond clearly labeled limited-beta closed-track materials.
4. Any performance claim lacking latest required-tier artifact links.

## Execution Note

The claim set is now defined and publish-safe for the controlled MVP. This ticket remains open until launch copy, listing assets, and any channel experiments actually apply the frozen claim blocks and record the claim set used.

## Claim Mapping Contract

1. Every external claim maps to `PROD-10` required row with `PASS` state.
2. Every privacy claim maps to a `SEC-02` `Verified` row.
3. Any hold-state row in `PROD-10` automatically blocks related public claim text.

## Acceptance

1. Launch copy and listing assets use only publish-safe claim set.
2. First 7-day scorecard (`MKT-09`) records claim block used per channel.

## Current Closeout

The listing title, short description, full description, seven caption blocks (`CH-01` through `CH-07`), screenshot mapping, and exclusions are now frozen in `docs/ux/play-store-listing-spec.md`. The same copy boundary is carried into `PRIVACY.md`, `SUPPORT.md`, Play metadata, release notes, and the asset manifest.

This ticket remains open only because fresh listing assets have not yet applied those copy blocks and `MKT-09` cannot record a live channel window before rollout. Do not expand copy while those execution steps remain.
