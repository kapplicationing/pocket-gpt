# Test Strategy

Last updated: 2026-07-08

This document explains how to choose evidence. Exact commands live in
`docs/testing/runbooks.md` and `scripts/dev/README.md`.

## Defaults

1. Prove the risk, not the whole app.
2. Start with the narrowest proof that can fail for the change you made.
3. Keep functional evidence separate from performance evidence.
4. Run broad gates once, after narrow proofs are current.
5. Stop after two dead-end attempts on the same command, hypothesis, or recovery path.
6. Preserve first-failure artifacts before retrying.

The goal is not to run fewer tests. The goal is to run the test that can move the
next fact.

## Evidence Surfaces

| Surface | What It Proves | What It Does Not Prove | Default Use |
|---|---|---|---|
| Host/unit/source audits | Kotlin logic, contracts, static performance rules, docs/tool syntax | Android runtime, selectors, frame budget | First proof for most code and docs changes |
| Emulator | Android bootstrap and deterministic instrumentation in a controlled target | OEM behavior, wireless transport, physical thermals | Startup, provisioning, CI parity, smoke before wider fan-out |
| Connected device | Real hardware state, permissions, storage, transport, thermal behavior | Hosted parity or broad device matrix | Runtime truth, jank, model storage, OEM-only failures |
| Maestro Cloud | Hosted parallel UI confirmation and cloud contract coverage | Local transport, physical-device canary, frame budget | Supplemental machine-verifiable fan-out |
| CI | Required branch protection and reproducible merge/main contracts | Local root cause and performance diagnosis | Merge safety, lifecycle gate, governance |

Use the three-surface matrix only when the risk needs it. Startup, provisioning,
runtime readiness, selectors, and release confidence usually need emulator,
device, and hosted/CI evidence. A local mapper or static rule does not.

## Work-Type Matrix

| Work Type | First Proof | Then Run | Only If Risk Requires |
|---|---|---|---|
| Kotlin/domain logic | Focused unit test or source audit | `bash scripts/dev/test.sh fast` | `merge` |
| Compose shell/state fanout | Source audits such as `PerformanceContractAuditTest` and `ChatAppDerivedStateAuditTest` | `fast`, `maestro-android lint` if selectors changed | Device smoke or benchmark journey |
| UI selector/copy changes | `maestro-android lint` or `maestro-android audit-selectors` | Focused Maestro/scoped flow | Full `maestro`, cloud smoke |
| Startup/provisioning/runtime readiness | Focused ViewModel/runtime tests | `android-instrumented` | lifecycle E2E, CI wrapper |
| Maestro/helper/harness change | Shell syntax, flow lint, helper contract test | One focused affected flow | Full lane only after helper contract is fixed |
| Download/model-library behavior | Focused unit/source audit | `fast`, relevant model-library smoke | lifecycle E2E if first-run/download contract changed |
| Hot text input or settings jank | Source audit for `TextFieldValue` and state fanout | `perf-interaction.sh` on benchmark variant | Perfetto for the worst journey; JankStats only if needed |
| Native/runtime performance | Stage benchmark or operation-specific benchmark | Trace/log evidence | Promotion benchmark sweep |
| Docs/tooling only | `bash -n` or docs/gov check for changed tool | `git diff --check` | No Android lane unless commands changed |

## Android Performance Policy

Never measure frame performance on `debug`. Use the `benchmark` build variant.
Debug builds include Compose/debug/runtime overhead and answer the wrong question.

Functional tests answer whether a workflow works. Benchmark runs answer whether a
workflow fits the frame budget. Do not rerun lifecycle E2E to prove a settings,
drawer, or model-sheet smoothness fix.

Current benchmark-variant guardrails:

| Metric | Target |
|---|---:|
| Janky frames | `<= 20%` |
| p50 | `<= 14 ms` |
| p90 | `<= 25 ms` |
| p99 | `<= 32 ms` |

Use three samples and compare medians before accepting or rejecting a smoothness
claim. If medians are still bad, capture Perfetto for the worst journey rather
than widening functional lanes. Add AndroidX JankStats only when gfxinfo and
Perfetto do not identify the state or composable boundary.

## Stop And Pivot

After two failed attempts on the same path:

1. Stop rerunning.
2. Classify the blocker:
   - `product`
   - `harness/bootstrap`
   - `device transport`
   - `hosted/infra`
   - `selector/flow drift`
3. Read the first-failure artifact.
4. Change only the thing that matches the class.
5. Rerun the smallest command that can prove that change.

Examples:

| Symptom | Class | Pivot |
|---|---|---|
| Maestro cannot attach but `adb devices` is healthy | harness/bootstrap | `maestro-android device probe --device <serial>` |
| UI dump shows SystemUI/Settings over the app | harness/bootstrap | collapse overlays, fix foreground checks |
| Flow waits for stale text | selector/flow drift | inspect current UI dump and update selector contract |
| App crashes or ANRs | product | inspect logcat/crash artifact before flow edits |
| Cloud upload remains pending | hosted/infra | poll sparingly or start one fresh upload; do not edit app code |
| Benchmark frame metrics exceed targets | product/performance | capture Perfetto for the worst journey |

Do not rerun table:

| Failure | Do Not Do This | Do This |
|---|---|---|
| Selector missing | Rerun the full lane | Inspect UI dump, update selector contract, rerun one flow |
| Build or Kotlin compile failure | Run Maestro | Use code-health/Kotlin compile/test output |
| Cloud failure after local pass | Keep polling or editing app code | Inspect hosted artifacts, classify infra vs product |
| Bad perf sample | Expand to more functional lanes | Take 3 benchmark samples, then trace the worst journey |
| Device transport failure | Switch product code | Probe/reconnect/pin device, or move to emulator/USB |

## Multi-Agent Validation

Use one proof owner per risk. Do not make every agent run every lane.

| Workstream | Owner Runs | Integration Runs Once |
|---|---|---|
| CI/harness | shell syntax, helper/unit tests, affected CI job | required PR/main checks |
| Compose state/fanout | focused source audits, Kotlin compile/unit | `fast`, selector lint |
| Selector/flow | `maestro-android lint` or focused scoped flow | full `maestro` or cloud smoke if contract changed |
| Performance | benchmark-variant journey samples on one pinned device | Perfetto for the worst remaining journey; JankStats only if the trace is inconclusive |
| Runtime/provisioning | focused runtime/ViewModel tests, `android-instrumented` if needed | lifecycle E2E when first-run/readiness changed |

Every agent should report:

1. The exact risk it owned.
2. The exact command it ran.
3. The artifact path or CI URL.
4. What broad gate it intentionally did not run.

The integration owner batches broad validation after slices converge. This avoids
spending the day proving the same thing repeatedly.

## Gate Policy

Use gates for release confidence, not for diagnosis.

| Gate | Purpose | Command |
|---|---|---|
| Fast local confidence | Core host/unit/static confidence | `bash scripts/dev/test.sh fast` |
| Merge readiness | Merge-equivalent local confidence | `bash scripts/dev/test.sh merge` |
| Merge unblock | Policy gate for PR-equivalent risk | `python3 tools/devctl/main.py gate merge-unblock` |
| Promotion | Release/promotion confidence | `python3 tools/devctl/main.py gate promotion` |
| Lifecycle E2E | First-run download/load/chat risk | CI `lifecycle-e2e-first-run` or `scripts/ci/run_lifecycle_e2e.sh` |

Do not use a gate as the first command when a focused source audit, unit test,
selector lint, or scoped device flow can fail faster and with clearer artifacts.

## Evidence Rules

1. Raw local artifacts stay under `tmp/`, `build/`, `.maestro-android/runs/`, or
   `scripts/benchmarks/runs/...`.
2. Retained human-readable evidence belongs under `docs/operations/evidence/...`.
3. Performance evidence must state build variant, device serial, command, samples,
   medians, and artifact roots.
4. CI evidence must include the run URL and the first failing job/artifact when it
   fails.
5. A skipped broad gate is acceptable only when the final report says what narrower
   proof replaced it and why that proof matches the changed risk.

## Ownership Map

1. `docs/testing/test-strategy.md` owns evidence policy and decision rules.
2. `docs/testing/runbooks.md` owns short execution recipes.
3. `scripts/dev/README.md` owns complete command syntax.
4. `docs/architecture/android-performance-contract.md` owns Android hot-path code
   contracts.
5. `docs/architecture/performance/android-operational-performance-plan.md` owns
   operation-by-operation performance follow-up.
6. `docs/testing/maestro-android-companion-cli.md` owns standalone Maestro CLI usage.
7. Specialized launch, moderation, cloud, screenshot, and benchmark docs should link
   back here instead of restating lane policy.
