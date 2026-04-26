# PROD-12 Human-Required Gate Split

Last updated: 2026-04-25
Owner: Product
Support: QA, Engineering
Status: Ready

## Objective

Separate release evidence into machine-verifiable gates and human-required gates so cloud/agent automation can carry the deterministic load while moderated usability remains explicitly human-owned.

## Scope

1. Classify current QA and release gates as:
   - `machine-verifiable`
   - `human-required`
2. Keep WP-13 moderated usability as the source of truth for trust/comprehension/perception evidence.
3. Keep PROD-10 launch claims tied to validated evidence and the correct gate type.
4. Define the minimum human sample needed for future releases that need usability interpretation.

## Sequencing

1. Use QA-14 evidence to confirm the machine-verifiable gate set can be run through cloud/device automation.
2. Use QA-15 evidence to confirm the triage loop can be driven by agents.
3. Update release docs so human-required evidence is explicitly limited to moderated usability and final go/no-go decisions.
4. Reduce future manual QA work by requiring a machine-verifiable cloud flow for every new user flow.

## Acceptance

1. The gate split is documented in the testing canon and referenced from QA playbooks.
2. Human-required evidence is limited to moderated usability and ambiguity resolution.
3. Machine-verifiable gates are defaulted to cloud/device automation.
4. Future ticket specs can reuse the split instead of re-deriving it.

## References

1. `docs/testing/cloud-first-qa-operating-model.md`
2. `docs/testing/test-strategy.md`
3. `docs/operations/wp-13-usability-gate-packet-template.md`
4. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
