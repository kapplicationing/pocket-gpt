# Test Strategy (Canonical Playbook)

Last updated: 2026-04-25

## Source Of Truth

1. Command contract: `scripts/dev/README.md`
2. This document: quality strategy, lane policy, release gates
3. Task-focused runbooks: `docs/testing/runbooks.md`
4. Screenshot review workflow: `docs/testing/screenshot-regression-workflow.md`

## Quality Policy

1. Correctness is required; fast feedback does not replace deterministic validation.
2. Core user flows must be verifiable on both host and Android device lanes.
3. Product claims are publishable only when tests and evidence align.
4. Known quality debt requires owner, severity, and closure target.

## Core Flow Coverage Contract

| Core Flow | Minimum Automated Coverage | Lane/Evidence Expectation | Owner |
|---|---|---|---|
| Startup/readiness | Runtime startup checks + ViewModel mapping tests | `android-instrumented` smoke + lifecycle E2E gate | Eng + QA |
| Send/streaming | stream event reducer + timeout/cancel tests | `journey` send-capture evidence + lifecycle E2E gate | Eng + QA |
| Session continuity | persistence/session tests | weekly regression matrix evidence | Eng + QA |
| Image attach | runtime/image contract tests | maestro scenario coverage | Eng + QA |
| Tool safety contracts | tool-runtime schema tests + typed tool result mapping | local-tool evidence in QA matrix/usability packet | Eng + QA + Security |
| Privacy controls and redaction | diagnostics redaction tests + privacy UI checks | privacy claim-parity ticket evidence | Eng + QA + Security |
| Model setup recovery | provisioning/viewmodel tests | first-run lifecycle E2E + recovery evidence in WP-13 packet; use a scoped bundle-download repro when provisioning changes affect multi-artifact models | Eng + QA + Product |

## Environment Decision Matrix

| Environment | Strengths | Limits | Best Use |
|---|---|---|---|
| Local host/unit lanes | fastest turnaround; lowest setup cost; easy debug cycle | cannot validate full Android UI/runtime behavior | per-save iteration, contract and reducer tests |
| Local Android device (`devctl`) | production-like behavior, richest diagnostics (`journey-report.json`, local screenshots/logcat), deterministic preflight contracts | limited parallelism; depends on attached hardware state | root-cause debugging, release/promotion confidence, runtime closure |
| CI emulator lanes | deterministic and repeatable required checks; easy branch protection integration | slower than local loop; emulator fidelity limits | required PR/main gates and broad baseline confidence |
| Maestro Cloud / hosted automation | hosted fan-out; parallel suite expansion; shared cloud reports | queue/network variance; still requires artifact parity and one physical-device canary | default execution path for machine-verifiable reruns under `QA-14`; broad regression expansion once parity is proven |

## Value-Per-Minute Cadence

1. Per-save: use the fastest changed-file checks for logic and contract changes.
2. Targeted bug repro loop: use a scoped Maestro flow plus logcat when one device path is failing.
3. Pre-push: use merge-equivalent validation.
4. Runtime/UI change local check: use the relevant device lanes from the command contract.
5. Weekly release rehearsal: use stage-2/device closure lanes plus the evidence packet.

## Lane Policy

1. `scripts/dev/README.md` owns exact command syntax.
2. Choose the narrowest lane that proves the changed risk.
3. Prefer `fast` for iteration, `merge` for merge-equivalent safety, `android-instrumented` for startup/runtime smoke, `maestro` for workflow coverage, `journey` for strict send/runtime evidence, `screenshot-pack` for screenshot contract, and physical-device stage-2 lanes for closure evidence.
4. Use scoped Maestro + logcat loops only for one device-specific crash/hang path.

## Merge-Unblock vs Promotion Gates

1. Merge-unblock gate contract:
   - `merge` + `doctor` + `android-instrumented`
   - risk-triggered lifecycle flow (`tests/maestro/scenario-first-run-download-chat.yaml`) executed through the hardened wrapper `python3 tools/devctl/main.py lane maestro --flows ...`
2. Promotion gate contract:
   - `merge` + `doctor` + `android-instrumented` + `maestro` + strict `journey`
   - optional `screenshot-pack` via `--include-screenshot-pack`
3. Gate reports are emitted under `build/devctl/gates/` and include:
   - per-step duration (runtime signal)
   - per-step correctness classification (`pass`, `product_signal_fail`, `harness_noise_fail`, `infra_fail`)
   - blocking/non-blocking decision used by the gate
4. Product-signal-only policy:
   - known harness-noise failures in selected expensive lanes (currently strict kickoff-harness journey failures and screenshot-pack compose-harness failures) are recorded as caveats, not blockers, in promotion gating.

## Risk-Based Lifecycle Gate Policy

1. Required CI job name: `lifecycle-e2e-first-run`.
2. PRs run this gate when either:
   - PR label is one of `risk:e2e-lifecycle`, `risk:runtime`, `risk:provisioning`, or
   - high-risk paths change (mobile runtime/provisioning/download/chat and shared app-runtime/native-bridge paths).
3. Every push to `main` runs `lifecycle-e2e-first-run` and blocks on failure.
4. Lifecycle gate executes `tests/maestro/scenario-first-run-download-chat.yaml` through the hardened `devctl lane maestro --flows ...` path rather than raw `maestro test`.
5. Gate allows one bounded clean-state retry; first-failure artifacts are preserved for triage.

## CI Baseline

Primary workflow: `.github/workflows/ci.yml`

1. Hosted required checks: `unit-and-host-tests`, `android-lint`, `native-build-package-check`, `android-instrumented-smoke`, `lifecycle-e2e-first-run` (risk-conditional on PRs, always-on for `main`).
2. `android-instrumented-smoke` is intentionally scoped to two deterministic checks: onboarding completion (`MainActivityUiSmokeTest#onboardingFlowCanProgressAndComplete`) plus the focused model-management Compose contract (`ModelManagementSheetComposeContractTest`). Full first-run/download/send behavior stays in lifecycle E2E.
3. Governance checks run docs drift/health/accuracy and governance self-tests.
4. Nightly workflows provide emulator matrix coverage and cloud-first hosted coverage (including first-run lifecycle and the model-management split smoke); hosted runs should stay tag-scoped and short.
5. Required checks for branch protection should include `lifecycle-e2e-first-run`.

## Engineering Principles (Applied)

1. Layered pyramid: most tests stay at unit/contract level; E2E guards only core lifecycle risk.
2. Risk-based E2E: expensive flows run when risk is high or branch is critical (`main`).
3. Flake containment: bounded retry is explicit, artifacts retained, and failures are visible.
4. Deterministic evidence: release/promotion decisions rely on reproducible artifacts, not ad-hoc re-runs.

## Lessons Learned (Repo-Specific)

1. Local `devctl` lanes are the fastest path to root cause because they bundle preflight checks, provisioning sanity, structured runtime snapshots, screenshots, and logcat in one run.
2. CI emulators are the best place for deterministic required checks that protect `main` and enforce contracts consistently.
3. Cloud/device automation is the default path for machine-verifiable reruns once artifact parity is proven, but it is still not a replacement for the physical-device canary or human-required moderation.
4. The preferred launch sequence is code closure first, cloud-first reruns second, physical-device canary third, and human-required moderation last.
4. First-run lifecycle failures can be environment-sensitive; preserving first-attempt artifacts is essential even when retry passes.
5. Cloud smoke should validate one focused contract per flow; benchmark and qualification paths should be tagged separately and kept out of smoke/default fan-out.

## Automation Boundary

Automate by default:

1. Unit/module/runtime contract tests
2. UI wiring and deterministic flow assertions
3. Governance checks and drift reports

Human-required checkpoints:

1. Physical-device environment control and anomalies
2. Moderated usability packet evaluation
3. Final go/no-go call when multiple evidence sources conflict

Cloud/device automation is the default execution path for machine-verifiable QA evidence. Use `docs/testing/cloud-first-qa-operating-model.md` for the operating split between cloud/agent runs and human-required moderation.

## Evidence Rules

1. Raw artifacts stay under `tmp/devctl-artifacts/...` for `devctl` local lanes, `scripts/benchmarks/runs/...` for stage-2 benchmark evidence, or uploaded CI artifacts.
2. Human-readable notes stay under `docs/operations/evidence/...`.
3. Keep active notes only; prune superseded notes not referenced by active roadmap/PRD/ticket artifacts.
