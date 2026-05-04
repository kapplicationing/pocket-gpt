# QA-15 Agent-Assisted QA Triage Loop

Last updated: 2026-04-25
Owner: QA
Support: Engineering
Status: Done

## Objective

Use agents to schedule cloud runs, inspect artifacts, summarize deterministic failures, and file or update blocking issues faster than manual triage loops.

## Scope

1. Define the agent workflow for:
   - launching or re-running a QA flow
   - opening journey and screenshot reports
   - comparing logcat and structured runtime data
   - identifying the first failing step
2. Define the output format for agent-assisted triage notes.
3. Keep agent output scoped to deterministic QA evidence, not moderated usability claims.
4. Tie the workflow to the existing cloud-first QA operating model.

## Sequencing

1. Pilot the agent workflow on one failed cloud run from a deterministic lane.
2. Have the agent produce a concise triage summary with the evidence links and likely owning area.
3. Use that summary to update the blocking issue or execution board entry.
4. Keep humans as the final reviewer for ambiguous UX or product decisions.

## Acceptance

1. At least one cloud-run failure is triaged through the agent workflow end to end.
2. The resulting issue update contains the exact failing step and evidence links.
3. The workflow clearly distinguishes automated triage from human moderation.
4. QA playbooks and runbooks point to the same agent-assisted loop.

## References

1. `docs/testing/cloud-first-qa-operating-model.md`
2. `docs/testing/runbooks.md`
3. `docs/testing/test-strategy.md`
4. `docs/operations/execution-board.md`

## 2026-05-04 Update

Closed for the controlled MVP:

1. Hosted/default failures were triaged through the agent workflow end to end.
2. The triage loop now produces first-failure classification, evidence links, and blocker summaries in the launch canon.
3. The workflow remains bounded to deterministic evidence and does not replace moderation-backed review.
