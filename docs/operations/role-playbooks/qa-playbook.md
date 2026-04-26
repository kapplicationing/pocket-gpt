# QA Playbook

Last updated: 2026-03-06

## Mission

Provide reproducible release evidence across runtime stability, UX quality, and promotion readiness.

## Responsibilities

1. Execute required lanes and publish pass IDs with evidence links.
2. Validate send-capture and timeout/cancel user-recovery behavior.
3. Keep weekly required-tier coverage signals current.
4. Support moderated usability packet execution and synthesis.
5. Use cloud/device automation first for machine-verifiable evidence, then reserve humans for moderated usability and ambiguous release decisions.

## Working Rules

1. Keep status updates in `docs/operations/execution-board.md` only.
2. Keep ticket-level pass/fail criteria in `docs/operations/tickets/`.
3. Separate raw artifacts (`scripts/benchmarks/runs/...`) from evidence notes (`docs/operations/evidence/...`).
4. Flag promotion blockers immediately on board and ticket docs.

## Primary References

- `docs/operations/execution-board.md`
- `docs/operations/play-store-launch-program.md`
- `docs/operations/tickets/qa-13-send-capture-gate-operationalization.md`
- `docs/operations/tickets/qa-14-cloud-first-qa-evidence-migration.md`
- `docs/operations/tickets/qa-15-agent-assisted-qa-triage.md`
- `docs/operations/tickets/ux-13-stuck-send-timeout-recovery.md`
- `docs/operations/tickets/prod-12-human-required-gate-split.md`
- `docs/testing/test-strategy.md`
- `docs/testing/runbooks.md`
- `docs/testing/cloud-first-qa-operating-model.md`
- `docs/testing/wp-09-ui-regression-matrix.md`
