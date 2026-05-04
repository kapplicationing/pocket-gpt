# PROD-12 Human-Required Gate Split

Last updated: 2026-05-04
Owner: Product
Support: QA, Engineering
Status: Done

## Objective

Separate release evidence into machine-verifiable gates and moderation-backed gates so cloud/agent automation carries the deterministic load, while a disclosed `AI human-proxy` fallback can stand in for unavailable moderators during the controlled Play Store MVP.

## Scope

1. Classify current QA and release gates as:
   - `machine-verifiable`
   - `moderation-backed`
2. Keep WP-13 as the source of truth for trust/comprehension/perception evidence, with `human-moderated` as the preferred mode and `AI human-proxy` as the disclosed fallback mode.
3. Keep PROD-10 launch claims tied to validated evidence and the correct gate type.
4. Define the minimum proxy cohort and disclosure requirements when moderators are unavailable.
5. Define `AI human-proxy` as a disclosed fallback operating mode that may close the controlled-MVP moderation leg when its prerequisites are satisfied.

## Sequencing

1. Use QA-14 evidence to confirm the machine-verifiable gate set can be run through cloud/device automation.
2. Use QA-15 evidence to confirm the triage loop can be driven by agents.
3. Update release docs so the controlled-MVP moderation leg can be closed by either `human-moderated` sessions or a disclosed `AI human-proxy` fallback session.
4. Reduce future manual QA work by requiring a machine-verifiable cloud flow for every new user flow.
5. Require the small-discovery-path setup tooling for `AI human-proxy` packet prep so fallback sessions start from one bounded setup contract.

## Acceptance

1. The gate split is documented in the testing canon and referenced from QA playbooks.
2. Moderation-backed evidence is limited to usability, privacy/trust, and ambiguity-resolution work that machine-verifiable lanes cannot close.
3. Machine-verifiable gates are defaulted to cloud/device automation.
4. Future ticket specs can reuse the split instead of re-deriving it.
5. `AI human-proxy` is documented as a disclosed fallback-closure mode for the controlled MVP with explicit prerequisites, can-close boundaries, and non-goals.

## References

1. `docs/testing/cloud-first-qa-operating-model.md`
2. `docs/testing/test-strategy.md`
3. `docs/operations/wp-13-usability-gate-packet-template.md`
4. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
5. `docs/operations/play-store-launch-program.md`
