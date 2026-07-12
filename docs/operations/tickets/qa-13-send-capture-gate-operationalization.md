# QA-13 Send-Capture Gate Operationalization

Last updated: 2026-07-11
Owner: QA
Support: Engineering, Product
Status: Done

## Objective

Make journey send-capture checks a weekly operational gate, not an ad-hoc diagnostic.

## Canonical Operator Command

```bash
bash scripts/ci/run_weekly_send_capture.sh --device <required-tier-serial>
```

The wrapper owns the strict journey invocation; operators should not reconstruct it:

```bash
python3 tools/devctl/main.py lane journey \
  --repeats 1 \
  --mode strict \
  --steps instrumentation,send-capture \
  --reply-timeout-seconds 90
```

## Scheduled Gate

1. Workflow: `.github/workflows/nightly-hardware-lane.yml`.
2. Schedule: Monday at `06:00 UTC` on the required-tier self-hosted Android runner.
3. Manual path: dispatch `Hardware Truth Lane` with `lane=weekly-send-capture` and an explicit device serial.
4. Missing/offline required hardware fails the weekly job; it does not silently convert the required lane to a caveat.
5. The existing daily Stage-2 schedule remains separate and unchanged at `02:00 UTC`.

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

## Retained Packet

Each run writes and uploads:

1. `qa-13-weekly-report.json` with the required fields, gate status, prior-week delta, severity, and issue-routing decision.
2. `qa-13-weekly-summary.md` with the `Recovery + timeout semantics` weekly matrix row.
3. The underlying `journey-report.json`, screenshots, logcat, and runtime-signal artifacts.
4. A stable artifact name, `weekly-send-capture-<device>`, so the next run can classify `baseline`, `no change`, `regressed`, `resolved`, or `still failing`.

Any failed/missing packet is `UX-S1`, blocks promotion, and creates or updates a deduplicated GitHub issue with `QA + Engineering` ownership, a four-hour triage-update ETA, blocker classification, and the workflow URL. A missing/offline Monday runner routes the same issue as `infra` even though the device job cannot create a packet. The next passing packet comments with the green workflow evidence and closes that stale blocker automatically.

## Acceptance

1. Done: the QA weekly packet includes a send-capture `PASS`/`FAIL` row and prior-week delta.
2. Done: the generated packet exports the latest send-capture values for the WP-13 usability packet and retains the underlying journey evidence.
3. Done: any fail state creates or updates a blocking issue with owner, ETA, device, packet path, and workflow URL.
4. Done: a later pass reconciles and closes the matching stale blocking issue.

## Closeout Evidence

See `docs/operations/evidence/wp-13/2026-07-11-qa13-operationalization-ux13-audit.md`.
