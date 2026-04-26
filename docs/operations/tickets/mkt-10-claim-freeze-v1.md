# MKT-10 Claim Freeze v1

Last updated: 2026-04-26
Owner: Marketing
Support: Product, Security
Status: Ready

## Objective

Freeze claim language for launch windows so only evidence-safe claims are used externally.

## Publish-Safe Claims (External)

1. Local-first runtime path with on-device inference default.
2. Offline quick-answer and prompt-first local tool utility for validated MVP workflows.
3. Single-image attach/contextual image help for validated in-thread workflows.
4. Deterministic runtime status visibility (`Not ready`, `Loading`, `Ready`, `Error`).
5. Session continuity and local diagnostics export.

## Internal-Only Claims (Not Yet External)

1. Claims requiring moderated UX metrics not yet populated (`WP-13` run-02 pending).
2. Privacy-control depth claims without full claim parity verification (`SEC-02` partial rows).
3. Voice activation/STT/TTS claims beyond clearly labeled limited-beta closed-track materials.
4. Any performance claim lacking latest required-tier artifact links.

## Claim Mapping Contract

1. Every external claim maps to `PROD-10` required row with `PASS` state.
2. Every privacy claim maps to a `SEC-02` `Verified` row.
3. Any hold-state row in `PROD-10` automatically blocks related public claim text.

## Acceptance

1. Launch copy and listing assets use only publish-safe claim set.
2. First 7-day scorecard (`MKT-09`) records claim block used per channel.
