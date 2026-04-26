# PROD-09 Soft-Gate Pilot Policy (Single-Build Downloads)

Last updated: 2026-03-05
Owner: Product Ops
Status: Active

## Purpose

Define the pilot expansion policy while `WP-13` moderated usability data is still being completed.

## Launch Defaults

1. Build policy: single build with download manager enabled by default.
2. Gate policy: `soft gate` for pilot expansion only.
3. Pilot cohort size: 25 testers.
4. Pilot window: 7 days.
5. Promotion beyond pilot still requires a completed moderated packet.

## Scope

Allowed under this policy:

1. Controlled internal/closed cohort expansion.
2. Real asset capture and claim validation work.
3. Weekly QA matrix and product signal collection.

Not allowed under this policy:

1. Broad public promotion.
2. Unqualified cohort scaling.
3. Claims that require completed moderated usability thresholds.

## Mandatory Signals (Pilot Continue Criteria)

1. No open `UX-S0`/`UX-S1` blockers.
2. For the current pilot window, attach the latest `android-instrumented` PASS id.
3. For the current pilot window, attach the latest `maestro` PASS id.
4. For the current pilot window, attach the latest `journey` report with `run_owner`/`run_host`.
5. For the current pilot window, attach the latest journey send-capture stage with `phase=completed` and `placeholder_visible=false`.
6. Policy/safety regressions remain closed.

## Advisory Signals (Pilot Evaluation)

1. Onboarding completion rate.
2. Runtime/model confusion rate.
3. Privacy confusion rate.
4. Time-to-first-useful-answer.
5. Model setup completion time.

## Hard-Stop Rules

Immediate `hold` if any is true:

1. New `UX-S0` blocker appears.
2. Repeat lane instability blocks release-candidate verification.
3. Privacy/safety control regression is detected.
4. Manifest/download outages persist with no validated fallback path.

## Manifest Outage Fallback Policy

If manifest fetch fails or returns no usable entries in current builds:

1. Keep import flow available and visible as the primary recovery path.
2. Mark download state as degraded in pilot notes.
3. Do not block chat/runtime if required models are already active and verified.
4. Require issue + owner + ETA in weekly synthesis.

## Required Artifacts

1. `docs/operations/evidence/wp-13/...` moderated run packet updates.
2. `docs/operations/evidence/wp-09/...` weekly matrix and promotion checklist updates.
3. `docs/operations/tickets/prod-10-launch-gate-matrix.md` decision row updates.

## Decision Outputs

At the end of each 7-day window, Product publishes one of:

1. `promote` with next cohort cap and support SLA.
2. `iterate` with explicit fixes and re-test window.
3. `hold` with top 3 blockers, owners, and due dates.
