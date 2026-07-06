# Test Strategy (Canonical Playbook)

Last updated: 2026-05-03

## Source Of Truth

1. Command contract: `scripts/dev/README.md`
2. This document: quality strategy, lane policy, release gates
3. Task-focused runbooks: `docs/testing/runbooks.md`
4. Screenshot review workflow: `docs/testing/screenshot-regression-workflow.md`
5. Flow selector/copy authority for automated QA: `docs/testing/generated/launch-flow-truth.md`
6. Hot-path performance contract: `docs/architecture/android-performance-contract.md`

## Quality Policy

1. Correctness is required; fast feedback does not replace deterministic validation.
2. Core user flows must be verifiable on both host and Android device lanes.
3. Product claims are publishable only when tests and evidence align.
4. Known quality debt requires owner, severity, and closure target.

## Default Android Evidence Matrix

For Android UI/runtime changes, default evidence is:

1. Emulator for fast harness/bootstrap proof.
2. One connected device for real transport, permissions, storage, thermals, and OEM behavior.
3. Cloud for hosted fan-out and hosted-contract confirmation.

Do not silently substitute one surface for another when startup, provisioning, runtime readiness, selectors, or release confidence changed.

Operator shortcut:

- `scripts/dev/README.md` carries the canonical local emulator path: start the emulator, confirm the serial, run `android-instrumented`, then one emulator smoke before widening to the connected-device and cloud legs.

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

Stop-and-pivot rule:

1. If the same path gets stuck twice, stop rerunning it.
2. Classify the blocker as product, harness/bootstrap, device transport, or cloud infrastructure.
3. Pivot to the smallest higher-signal command or another surface in the matrix before widening again.

## Efficiency Techniques That Worked

1. Lock the flow contract before rerunning broad lanes.
   - Read the current app UI code and one first-failure screenshot before editing Maestro flows.
   - Do not widen to `maestro`, `journey`, or cloud fan-out while a shared helper is still based on stale assumptions.
2. Keep one scenario equal to one contract.
   - `runtime-loaded` is different from `send-ready`.
   - shell/navigation smoke is different from model-management smoke.
   - mixed-purpose flows create noisy failures and rerun churn.
3. Classify failures before rerunning.
   - every non-pass should carry one primary class: `product defect`, `harness/bootstrap failure`, `cloud infra failure`, or `device transport failure`.
   - note secondary detail such as selector drift or flow drift under the primary class; do not invent a second competing top-level label.
   - only rerun after something in that class changed.
4. Preserve first-failure artifacts before retrying.
   - first screenshot + logcat + runner output usually explain more than a later retried failure.
5. Use cloud first for cheap parallel confirmation, then narrow device proof.
   - hosted smoke is the fastest way to fan out deterministic coverage.
   - attached devices should confirm cloud findings or cover OEM-only behavior, not rediscover the same flow logic.
6. Keep the physical-device lane narrow.
   - use one authoritative canary and one secondary debug device.
   - do not treat every connected phone as equal gate evidence.

## Tooling Used In Practice

1. `python3 tools/devctl/main.py doctor`
   - environment and lane preflight; use before any device or gate work.
2. `bash scripts/dev/test.sh fast|merge`
   - fast host confidence and merge-equivalent regression coverage.
3. `python3 tools/devctl/main.py lane android-instrumented`
   - authoritative startup/runtime smoke on device; best clean separator between product/runtime failures and Maestro-only failures.
4. `python3 tools/devctl/main.py lane maestro`
   - canonical local UI smoke with release-style preflight, seeded runtime state, and artifacts under `tmp/devctl-artifacts/`.
5. `python3 tools/devctl/main.py lane journey`
   - strict send/runtime evidence and send-capture validation.
6. `maestro-android`
   - scoped repros, selector linting, targeted device runs, and artifact inspection.
   - best for fast local diagnosis when a full lane would be wasteful.
7. `bash scripts/dev/maestro-cloud-smoke.sh`
   - hosted smoke for machine-verifiable parallel reruns.
8. `bash scripts/dev/maestro-cloud-smoke-parallel.sh`
   - same hosted smoke suite across both configured Maestro Cloud accounts.
9. `bash scripts/dev/maestro-cloud-upload-status.sh`
   - explicit upload polling by `label:upload-id` and `project-id`.

## Agent Skills Used In Practice

1. `testing-android-maestro`
   - used for lane selection, scoped repro guidance, selector drift triage, and deciding when to prefer `maestro-android` over raw `adb`.
2. `debugging-pocket-gpt`
   - used when a failure might be in app/runtime/native behavior rather than only in the flow harness.
3. `code-health`
   - used when build or Kotlin-quality issues needed to be separated from QA harness failures.
4. `pocketgpt-coding-best-practices`
   - used when test or tooling changes touched repo-specific architecture or conventions and needed to stay aligned with the codebase.

Flow-truth rule in practice:

1. When selectors, copy, CTAs, or runtime-state labels change, regenerate `docs/testing/generated/launch-flow-truth.md` before widening Maestro or AI-tester reruns.
2. Treat the generated flow-truth manifest as the only selector/copy authority for automated QA maintenance.

## Tooling Gaps We Still Want

1. A first-class `devctl` command that polls hosted Maestro uploads by project/account without requiring manual `project-id` and `label:upload-id` assembly.
2. One authoritative cloud-to-local artifact normalizer so hosted and local evidence are easier to compare automatically.
3. A reliable wired-device or emulator-backed local Maestro path for authoritative local smoke when wireless Samsung transport is unstable.
4. Better harness health reporting that clearly separates:
   - app failure
   - Maestro bootstrap failure
   - device transport failure
   - hosted queue/polling delay
5. A pinned-install helper shared by all local wrappers so every tool installs to one device only.
6. A small screenshot triage index that points directly to first-failure screenshots instead of making engineers browse raw artifact trees.

## QA Best-Practice Gaps And What To Do Better

1. We have not been strict enough about contract ownership.
   - better: define one owner for each shared flow helper and require contract tests when it changes.
2. We have mixed harness debugging with product verification too often.
   - better: separate harness health from product quality in every report and every rerun decision.
3. We relied too long on wireless-device local Maestro as if it were authoritative.
   - better: treat local wireless Maestro as diagnostic until it proves stable twice in a row.
4. We did not document operator details early enough.
   - better: every new wrapper should ship with examples, argument expectations, and one failure-mode note on day one.
5. We allowed broad reruns while the shared helpers were still stale.
   - better: stop and fix the helper contract first, then rerun only the affected scenario set.
6. We do not yet have a small immutable launch smoke suite with stable ownership.
   - better: keep the launch smoke suite short, versioned, and intentionally hard to change.
7. We still depend too much on manual interpretation of hosted/local status.
   - better: invest in one machine-readable evidence ledger for pass ids, upload ids, first-failure class, and current authority.

## Lane Policy

1. `scripts/dev/README.md` owns exact command syntax.
2. Choose the narrowest lane that proves the changed risk.
3. Prefer `fast` for iteration, `merge` for merge-equivalent safety, `android-instrumented` for startup/runtime smoke, `maestro` for workflow coverage, `journey` for strict send/runtime evidence, `screenshot-pack` for screenshot contract, and physical-device stage-2 lanes for closure evidence.
4. Use scoped Maestro + logcat loops only for one device-specific crash/hang path.

## Merge-Unblock vs Promotion Gates

1. Merge-unblock gate contract:
   - `merge` + `doctor` + `android-instrumented`
   - risk-triggered lifecycle flow (`tests/maestro/scenario-first-run-download-chat.yaml`) executed through the CI lifecycle wrapper for retry, artifact, and crash-signature capture
2. Promotion gate contract:
   - `merge` + `doctor` + `android-instrumented` + `maestro` + strict `journey`
   - optional `screenshot-pack` via `--include-screenshot-pack`
3. Gate reports are emitted under `build/devctl/gates/` and include:
   - per-step duration (runtime signal)
   - per-step correctness classification (`pass`, `product_signal_fail`, `harness_noise_fail`, `infra_fail`)
   - blocking/non-blocking decision used by the gate
4. Product-signal-only policy:
   - known harness-noise failures in selected expensive lanes (currently screenshot-pack compose-harness failures, plus any strict `journey` Maestro fallback noise when the instrumentation-produced send-capture artifact is absent) are recorded as caveats, not blockers, in promotion gating.

## Risk-Based Lifecycle Gate Policy

1. Required CI job name: `lifecycle-e2e-first-run`.
2. PRs run this gate when either:
   - PR label is one of `risk:e2e-lifecycle`, `risk:runtime`, `risk:provisioning`, or
   - high-risk paths change (mobile runtime/provisioning/download/chat and shared app-runtime/native-bridge paths).
3. Every push to `main` runs `lifecycle-e2e-first-run` and blocks on failure.
4. The CI lifecycle gate executes `tests/maestro/scenario-first-run-download-chat.yaml` through `scripts/ci/run_lifecycle_e2e.sh`, which wraps raw Maestro with install, crash-signature capture, first-failure artifacts, and one clean-state retry.
5. Local broad rehearsal can still use `python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml`; use the CI wrapper with an explicit `--device` when reproducing the required GitHub job.

## CI Baseline

Primary workflow: `.github/workflows/ci.yml`

1. Hosted required checks: `unit-and-host-tests`, `android-lint`, `native-build-package-check`, `android-instrumented-smoke`, `lifecycle-e2e-first-run` (risk-conditional on PRs, always-on for `main`).
2. `android-instrumented-smoke` is intentionally scoped to two deterministic checks: onboarding completion (`MainActivityUiSmokeTest#onboardingFlowCanProgressAndComplete`) plus the focused model-management Compose contract (`ModelManagementSheetComposeContractTest`). Full first-run/download/send behavior stays in lifecycle E2E.
3. Governance checks run docs drift/health/accuracy and governance self-tests.
4. Nightly workflows provide emulator matrix coverage and attempt cloud-first hosted coverage (including first-run lifecycle and the model-management split smoke). If `MAESTRO_CLOUD_API_KEY` is absent, the hosted job must report an explicit skip; that skip is configuration coverage, not hosted product evidence. Hosted runs should stay tag-scoped and short.
5. The scheduled hosted Maestro smoke intentionally runs a direct onboarding flow instead of `devctl lane maestro`. `devctl` remains the local/promotion gate because it owns storage/model-cache preflights; hosted emulators are better suited to narrow app-launch and onboarding contracts.
6. Required checks for branch protection should include `lifecycle-e2e-first-run`.

## Engineering Principles (Applied)

1. Layered pyramid: most tests stay at unit/contract level; E2E guards only core lifecycle risk.
2. Risk-based E2E: expensive flows run when risk is high or branch is critical (`main`).
3. Flake containment: bounded retry is explicit, artifacts retained, and failures are visible.
4. Deterministic evidence: release/promotion decisions rely on reproducible artifacts, not ad-hoc re-runs.

## Lessons Learned (Repo-Specific)

1. Local `devctl` lanes are the fastest path to root cause because they bundle preflight checks, provisioning sanity, structured runtime snapshots, screenshots, and logcat in one run.
2. CI emulators are the best place for deterministic required checks that protect `main` and enforce contracts consistently.
3. Cloud/device automation is the default path for machine-verifiable reruns once artifact parity is proven, but it is still not a replacement for the physical-device canary or the moderation-backed WP-13 leg.
4. The preferred launch sequence is code closure first, cloud-first reruns second, physical-device canary third, and moderation-backed review last.
4. First-run lifecycle failures can be environment-sensitive; preserving first-attempt artifacts is essential even when retry passes.
5. Cloud smoke should validate one focused contract per flow; benchmark and qualification paths should be tagged separately and kept out of smoke/default fan-out.

## Automation Boundary

Automate by default:

1. Unit/module/runtime contract tests
2. UI wiring and deterministic flow assertions
3. Governance checks and drift reports

Moderation-backed checkpoints:

1. Physical-device environment control and anomalies
2. WP-13 packet evaluation through `human-moderated` by default or disclosed `AI human-proxy` fallback when moderators are unavailable
3. Final go/no-go call when multiple evidence sources conflict
4. Privacy/trust/comprehension interpretation for WP-13

Agent boundary:

1. Agents may schedule runs, inspect artifacts, compare failures, and package deterministic evidence.
2. Agents may also execute the disclosed `AI human-proxy` fallback path when they use the approved bundle/setup flow and the same WP-13 workflow/reporting utilities as human moderation.
3. `AI human-proxy` packets may close the moderation-backed leg for the controlled MVP, but they never replace missing machine-verifiable evidence and must remain labeled as proxy-derived in governance artifacts.

AI human-proxy mode:

1. Use only when human moderators are unavailable and the deterministic technical path is materially stable enough that proxy sessions are unlikely to be dominated by basic runtime breakage.
2. Keep `AI human-proxy` output separate from machine-verifiable evidence, but allow it to satisfy the moderation-backed WP-13 leg for the controlled MVP.
3. It can close packet completion, facilitator/script cleanup, obvious blocker discovery logs, and clearly disclosed `promote`/`iterate`/`hold` recommendations for the controlled MVP.
4. It cannot close missing machine-verifiable rows, undisclosed claim expansion, or broader public-launch research needs.
5. Require the small-discovery-path setup tooling before running `AI human-proxy` sessions so the fallback path starts from one comparable setup contract.

Cloud/device automation is the default execution path for machine-verifiable QA evidence. Use `docs/testing/cloud-first-qa-operating-model.md` for the operating split between cloud/agent runs and moderation-backed review.

## Evidence Rules

1. Raw artifacts stay under `tmp/devctl-artifacts/...` for `devctl` local lanes, `scripts/benchmarks/runs/...` for stage-2 benchmark evidence, or uploaded CI artifacts.
2. Human-readable notes stay under `docs/operations/evidence/...`.
3. Keep active notes only; prune superseded notes not referenced by active roadmap/PRD/ticket artifacts.
