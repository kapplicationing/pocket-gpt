# Testing Docs Index

Start here when choosing tests for PocketGPT.

## Read First

1. `docs/testing/test-strategy.md`
   - evidence policy, work-type matrix, stop-and-pivot rules, multi-agent testing
2. `docs/testing/runbooks.md`
   - short command recipes by task
3. `scripts/dev/README.md`
   - complete command syntax and wrapper details

## Android And Maestro

1. `docs/testing/maestro-android-companion-cli.md`
   - standalone `maestro-android` usage and repo integration
2. `.claude/skills/maestro-android-cli/references/testing-map.md`
   - selector/testTag map and Android testing ladder for agents
3. `tests/maestro/`
   - local Maestro flows and shared helpers
4. `tests/maestro-cloud/`
   - hosted Maestro Cloud flows
5. `docs/testing/generated/README.md`
   - generated testing-contract index
6. `docs/testing/generated/launch-flow-truth.md`
   - generated selector/copy authority for automated QA maintenance

## Performance

1. `docs/architecture/android-performance-contract.md`
   - Android hot-path code contract
2. `docs/architecture/performance/android-operational-performance-plan.md`
   - operation-by-operation performance follow-up plan
3. `docs/testing/runbooks.md#performance-regression-check`
   - benchmark commands and thresholds
4. `docs/testing/runbooks.md#perfetto-capture-for-worst-jank`
   - trace capture after bad benchmark medians

## Release And Specialized Evidence

1. `docs/testing/screenshot-regression-workflow.md`
   - screenshot review and reference update workflow
2. `docs/testing/wp-09-ui-regression-matrix.md`
   - weekly UI regression matrix
3. `docs/testing/runtime-tuning-debugging.md`
   - runtime tuning diagnostics and interpretation
4. `docs/testing/runtime-performance-e2e-playbook.md`
   - runtime performance E2E playbook
5. `docs/testing/pocketpal-parity-benchmark.md`
   - PocketGPT vs PocketPal parity benchmark
6. `docs/testing/cloud-first-qa-operating-model.md`
   - cloud/agent QA operating split
7. `docs/testing/qa-operating-principles.md`
   - QA doctrine and moderation boundaries

## Ownership Rule

Do not duplicate command policy in new docs. Link to:

1. `test-strategy.md` for why a test is required.
2. `runbooks.md` for the short recipe.
3. `scripts/dev/README.md` for full command syntax.

When docs disagree, trust current code, fresh first-failure artifacts, and the
canonical files above. Then update or delete the stale guidance.
