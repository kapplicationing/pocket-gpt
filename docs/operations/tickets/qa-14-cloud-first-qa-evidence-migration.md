# QA-14 Cloud-First QA Evidence Migration

Last updated: 2026-04-25
Owner: QA
Support: Engineering, Product
Status: Ready

## Objective

Move repeatable QA evidence collection to cloud/device automation for machine-verifiable flows, while preserving a narrow physical-device canary for OEM/runtime issues.

## Scope

1. Define the default execution path for:
   - `android-instrumented`
   - `maestro`
   - `journey`
   - `screenshot-pack`
   - lifecycle E2E flows
2. Standardize the required artifact set for cloud runs:
   - pass id
   - report path
   - screenshots
   - logcat
   - structured runtime snapshot
3. Define the fallback rule for one narrow physical-device canary lane.
4. Align runbook language so cloud reruns are the default for deterministic regressions.

## Sequencing

1. Run one cloud-backed pass per deterministic lane and compare artifacts with the current local/devctl contract.
2. Validate that cloud runs produce the same evidence shape as local runs.
3. Record the cloud-first decision in runbooks and QA references.
4. Keep the physical-device canary only for issues the cloud path cannot prove.

## Acceptance

1. Machine-verifiable lanes have a documented cloud-first execution path.
2. Cloud runs emit the same evidence schema expected by QA and release gates.
3. The canary lane remains explicit and limited to device-specific failures.
4. QA evidence notes reference the cloud-first operating model instead of ad hoc manual reruns.

## References

1. `docs/testing/cloud-first-qa-operating-model.md`
2. `docs/testing/test-strategy.md`
3. `docs/testing/runbooks.md`
4. `docs/operations/execution-board.md`
