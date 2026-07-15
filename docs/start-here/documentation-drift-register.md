# Documentation Drift Register

Last updated: 2026-07-12
Owner: Product + Engineering

This is the evergreen tracker for doc/code drift. Current code and fresh
first-failure artifacts outrank historical status notes.

## Current Baseline

1. The canonical public/product name is **PocketAgent**. `PocketGPT` and
   `pocket-gpt` are repository/history identifiers only.
2. The controlled-MVP product gate is promoted (`PROD-10: Promote`, required
   rows `8/8`); publication remains blocked on release-package execution.
3. Tool UX is prompt-first. Deeper parser/runtime capability is not the public
   launch surface.
4. Public image claims are limited to single-image Q&A with compatible
   multimodal companion packaging.
5. Voice is a production opt-in available in release builds with no device
   allowlist. Retained multi-OEM and 24-hour qualification is still required
   before broad wake, background-reliability, or battery claims.
6. The July 11 runtime baseline includes durable/cancellable imports, model and
   GGUF validation, serialized runtime ownership, cancellation/close recovery,
   cloud-backup and Android 12+ transfer exclusions, and interaction-performance gates.
7. `main` and `origin/main` contain the July 11 hardening baseline. No obsolete
   local launch branch or unpublished code delta is part of the release plan.

## Active Drift Risks

| ID | Area | Risk | Severity | Owner | Next action |
|---|---|---|---|---|---|
| DR-001 | Streaming/runtime contracts | Phase, cancellation, timeout, and ownership docs can drift when runtime events change | High | Engineering | Keep `docs/governance/docs-accuracy-manifest.json` aligned with runtime facade, coordinator, and send reducers |
| DR-002 | Tool UX | Prompt shortcuts can be mistaken for a rich direct-tool launcher | High | Product + Android | Keep launch copy and implemented behavior bounded to prompt-first entry |
| DR-003 | Privacy claims | Retention/reset/per-tool claims can exceed current UI controls | High | Product + Security | Publish only `SEC-02` verified rows; keep `P-04` internal-only |
| DR-004 | Testing guidance | Commands and evidence policy can split across docs | Medium | Engineering + QA | Keep `docs/testing/test-strategy.md` and `docs/testing/runbooks.md` canonical |
| DR-005 | Evidence notes | Preserved evidence can be mistaken for current live CI/device state | High | Product Ops + QA | Use the evidence ledger for fixed baselines and regenerate launch readiness; use live CI for moving state |
| DR-006 | Voice scope | Docs can confuse shipped availability with support qualification or imply debug/cohort gating | High | Product + Release Ops | Say production opt-in for availability; reserve broad reliability and battery claims for retained Samsung, Pixel, aggressive-background-OEM, and 24-hour evidence |
| DR-008 | Publication status | A promoted product gate can be mistaken for Play Store publication readiness | High | Product + Release Ops | Keep `gate=promoted` separate from `publication=blocked` until the final package and metadata are complete |

## Completed This Cycle

1. Rebuilt the feature catalog around implemented, production-opt-in,
   qualification-in-progress, post-MVP, and claim-safe states.
2. Replaced stale May branch/publication guidance with the synchronized July 11
   baseline and a store-publication-only closeout plan.
3. Pruned resolved pilot, privacy-claim, and gate-policy questions from the open
   questions log.
4. Replaced the Phase-0 risk register with current controlled-MVP residual and
   publication risks.
5. Added the July 11 durable-import, runtime-ownership, backup/transfer, and performance
   milestone to current product/program truth.
6. Established PocketAgent as the canonical public/product name.
7. Closed `DR-007`: customer-facing app strings, listing copy, support/privacy
   pages, release metadata, launcher resources, and draft store graphics now use
   PocketAgent. Historical QA screenshots remain unchanged and are explicitly
   rejected for listing use; technical `pocketgpt` identifiers remain for
   compatibility.

## Update Rule

1. Add an entry when current code/evidence and a maintained document disagree.
2. Close it only after both sides agree and governance checks pass.
3. Keep active rows short; use git history, not this table, as the archive.
4. Never copy a moving SHA, CI result, or temporary artifact into multiple status
   docs when a canonical generator or ledger already exists.
