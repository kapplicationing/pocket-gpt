# QA-13 Send-Capture Gate Operationalization

Last updated: 2026-03-05
Owner: QA
Support: Engineering, Product
Status: In Progress

## Objective

Make journey send-capture checks a weekly operational gate, not an ad-hoc diagnostic.

## Canonical Command

`python3 tools/devctl/main.py lane journey --repeats 1 --reply-timeout-seconds 90`

## Required Evidence Fields

1. `phase`
2. `elapsed_ms`
3. `runtime_status`
4. `backend`
5. `active_model_id`
6. `placeholder_visible`

## Pass/Fail Rules

1. Pass: `phase=completed` and `placeholder_visible=false` at SLA checkpoint.
2. Fail-timeout: `phase=timeout`.
3. Fail-first-token-only: `phase=first_token` with no completion by SLA.
4. Fail-error: `phase=error` with failure signature and debug paths.

## Operational Cadence

1. Run weekly on required-tier device.
2. Include best-effort device run when available.
3. Publish result in weekly QA matrix with severity deltas.

## Acceptance

1. QA weekly packet includes send-capture pass/fail row.
2. WP-13 usability packet includes latest send-capture values.
3. Any fail state creates a blocking issue with owner and ETA.
