# MKT-09 Channel Scorecard Run 01

Last updated: 2026-07-11
Owner: Growth Lead
Support: Product, QA
Status: Ready

## Objective

Execute the first 7-day channel experiment and produce a keep/iterate/stop recommendation using evidence-safe claims only.

## Inputs

1. `docs/operations/templates/marketing/mkt-03-7-day-scorecard-template.md`
2. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
3. `docs/operations/tickets/prod-09-soft-gate-pilot-policy.md`

## Experiment Window

1. Start date (UTC):
2. End date (UTC):
3. Cohort/channel:

## Required Outputs

1. Filled scorecard with channel metrics and qualitative notes.
2. Claim-safety audit result per channel message block.
3. Recommendation: `keep`, `iterate`, or `stop`.
4. Claim set used must reference `docs/operations/tickets/mkt-10-claim-freeze-v1.md`.

## Acceptance

1. Scorecard has complete 7-day data.
2. Claims used in the experiment are evidence-linked.
3. Decision and next action are logged on execution board.

## Prepared Run Packet

The prefilled run packet is `docs/operations/scorecards/mkt-09-controlled-mvp-run-01.md`. It freezes the only allowed listing copy ids (`CH-01` through `CH-07`), defines the measurement sources, and starts its seven-day clock from the first actual internal-track installation.

This work is intentionally not complete before publication: no real channel cohort, install timestamp, or seven-day observation window exists yet. `MKT-09` remains advisory and does not block the initial controlled-MVP upload.
