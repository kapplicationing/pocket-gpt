# Dev Test Commands (Source of Truth)

This is the canonical command reference for local, CI, and device-lane execution.
All wrappers delegate to the config-driven orchestrator:

```bash
python3 tools/devctl/main.py <command> ...
```

## First 10 Minutes (New Joiner)

```bash
python3 -m pip install -r tools/devctl/requirements.txt
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh fast
```

If `doctor` fails, follow the suggested fix lines and rerun `doctor`.

## Newcomer Confidence Checklist (4 Commands)

Run these in order for fast, layered confidence:

```bash
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh fast
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
```

What each command proves:

1. `doctor`
   Environment/toolchain is usable (Python deps, Gradle/Android prerequisites, lane prerequisites).
2. `test.sh fast`
   Core JVM/Kotlin and policy/runtime contracts still hold after your changes.
   `quick` is a compatibility alias for `core`; prefer `fast` for new work.
3. `lane android-instrumented`
   App boots and bridge/runtime integration still works on-device/emulator instrumentation lane.
4. `lane maestro`
   Basic end-to-end UI smoke flow still passes in the external E2E runner.

Use this as a quick pre-PR baseline. For broader coverage, run `bash scripts/dev/test.sh ci` and, when needed, the physical device lane in this README.

## Standard (CI + Local)

```bash
bash scripts/dev/test.sh [fast|core|merge|auto|full|quick|ci]
```

Modes:

- `fast`: changed-file selected Gradle tasks + deterministic lane recommendations output (`build/devctl/recommended-lanes.txt`)
- `core`: full module unit tests + Android unit tests when SDK is configured, no clean
- `merge`: clean + full merge gate task set (CI-equivalent)
- `auto`: changed-file selected tasks with Android unit tests included when rules require them
- `quick`: compatibility alias for `core`
- `full`: compatibility alias for `merge`
- `ci`: compatibility alias for `merge`

Compatibility wrapper:

- `bash scripts/dev/verify.sh` (delegates to `test.sh ci`)

## Physical Device Lane

```bash
bash scripts/dev/device-test.sh [runs] [label] [-- <command...>] [--framework espresso|maestro|both]
```

Defaults:

1. Stage checks and baseline capture.
2. Benchmark settings apply.
3. 10-loop scenario command (`:apps:mobile-android-host:run`).
4. Optional framework lanes (`espresso` + `maestro` by default).
5. Benchmark settings reset on exit.

Examples:

- `bash scripts/dev/device-test.sh`
- `bash scripts/dev/device-test.sh 5 smoke-loop`
- `bash scripts/dev/device-test.sh 10 scenario-a -- ./gradlew --no-daemon :apps:mobile-android-host:run`
- `bash scripts/dev/device-test.sh 10 scenario-a --framework espresso`
- `bash scripts/dev/device-test.sh 10 scenario-a --framework maestro`

For runtime tuning analysis after repeated device runs, use `docs/testing/runtime-tuning-debugging.md`. It explains how to read `RUNTIME_TUNING|...` diagnostics lines, where the generated `runtime-log-signals.{json,md}` artifacts land, and how to correlate them with benchmark artifacts under `scripts/benchmarks/runs/...` or `devctl` lane artifacts under `tmp/devctl-artifacts/...`.

## Stage-2 Benchmark Wrapper

```bash
bash scripts/dev/bench.sh stage2 --device your-device-id [--date YYYY-MM-DD] [--profile quick|closure] [--models 0.8b|1.7b|both] [--scenarios a|b|both] [--resume] [--install-mode auto|force|skip] [--logcat filtered|full] [--evidence-note-path <abs-path>]
```

Required environment (device file paths for side-loaded models):

```bash
export POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=/absolute/device/path/qwen3.5-0.8b-q4.gguf
export POCKETGPT_QWEN3_1_7B_Q4_K_M_SIDELOAD_PATH=/absolute/device/path/qwen3-1.7b-q4_k_m.gguf
```

Only the paths for the selected `--models` are required now. A `0.8b` quick run does not require the 1.7B path.

GitHub's scheduled Hardware Truth Lane is gated by the repository variable
`POCKETGPT_HARDWARE_RUNNER_ENABLED=true` and secret
`POCKETGPT_HARDWARE_RUNNER_ADMIN_TOKEN`. Leave the variable unset or false when
no self-hosted `pocketgpt-android` runner is online so scheduled runs skip
instead of queueing for 24 hours. When the variable is true, the secret must be
able to read repository self-hosted runners so the workflow can fail closed if
the matching runner is offline.

Contract outputs under `scripts/benchmarks/runs/YYYY-MM-DD/your-device-id/`:

1. `scenario-a.csv`
2. `scenario-b.csv`
3. `stage-2-threshold-input.csv`
4. `model-1.7b-metrics.csv` (required for WP-12/ENG-13 closure evidence)
5. `meminfo-*.txt` (PSS snapshots per scenario/model)
6. `threshold-report.txt`
7. `runtime-evidence-validation.txt`
8. `runtime-log-signals.json`
9. `runtime-log-signals.md`
10. `logcat.txt`
11. `notes.md`
12. `summary.json`
13. `evidence-draft.md`

Evidence integrity gate:

```bash
python3 scripts/benchmarks/validate_stage2_runtime_evidence.py \
  scripts/benchmarks/runs/YYYY-MM-DD/your-device-id
```

Profile behavior:

1. `quick`:
   - defaults to `--models 0.8b`
   - uses low-run/token defaults for iteration
   - runs paired cold/warm measurements within the sweep (`runs=2` default) and records `warm_vs_cold_first_token_delta_ms`
   - supports `--resume` and partial model/scenario execution
   - threshold/runtime reports are still emitted; runtime evidence validator is not enforced as a closure gate
2. `closure`:
   - requires `--models both --scenarios both`
   - enforces strict threshold mode
   - enforces full runtime evidence validator gate

Install controls:

1. `--install-mode auto`: assemble APKs and skip reinstall when hashes are unchanged
2. `--install-mode force`: always install app + test APK
3. `--install-mode skip`: never install in this run

Optional cache controls (env):

1. `POCKETGPT_PREFIX_CACHE_ENABLED=0|1` (default `1`)
2. `POCKETGPT_PREFIX_CACHE_STRICT=0|1` (default `0`)
3. `POCKETGPT_RESPONSE_CACHE_TTL_SEC=<seconds>` (default `0`, disabled)
4. `POCKETGPT_RESPONSE_CACHE_MAX_ENTRIES=<count>` (default `0`, disabled)
5. `POCKETGPT_STAGE2_REQUIRE_PREFIX_CACHE_HIT=0|1` (default `0`; use `1` for shared-session regression gates)

Focused shared-session prefix-cache regression:

```bash
bash scripts/dev/prefix-cache-regression.sh --device your-device-id --install-mode skip
```

This wraps the smallest stage-2 run that must prove:

1. shared-session prefix reuse still hits;
2. reused token count stays above zero;
3. the 0.8B runtime path remains healthy on a real device.

Curated CPU tuning sweep:

```bash
bash scripts/dev/cpu-sweep.sh --device your-device-id --install-mode skip
```

This runs the shared-session 0.8B stage-2 path across a small CPU-focused preset set, then writes:

1. `cpu-sweep-summary.json`
2. `cpu-sweep-summary.csv`
3. `cpu-sweep-summary.md`

Optional parity input:

```bash
bash scripts/dev/cpu-sweep.sh --device your-device-id --install-mode skip --pocketpal tmp/pocketpal-benchmark.json
```

Use `--preset full` when you want the larger batch/context sweep instead of the compact default.

## Framework Lanes (Direct)

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane maestro --include-tags smoke
python3 tools/devctl/main.py lane maestro --include-tags model-management
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py lane screenshot-pack --product-signal-only
python3 tools/devctl/main.py lane journey [--repeats N]
python3 tools/devctl/main.py lane journey --steps instrumentation,send-capture,maestro
```

## Report Helpers

```bash
python3 tools/devctl/main.py report journey
python3 tools/devctl/main.py report screenshot-pack
python3 tools/devctl/main.py report journey --open
```

Use these when you want the nearest Playwright-style "show report" path for Maestro-backed runs without manually locating the latest artifact root.

## Maestro Android Companion CLI

```bash
pipx install -e /path/to/maestro-android
maestro-android init
maestro-android doctor
maestro-android devices --json
maestro-android start-device
maestro-android lane smoke
maestro-android lane journey
maestro-android lane screenshot-pack
maestro-android lane lifecycle
maestro-android scoped --flow tmp/maestro-repro.yaml
maestro-android device probe --device your-device-id
maestro-android lint
maestro-android audit-selectors
maestro-android clean --stale-flows --confirm
maestro-android report latest
maestro-android trace latest
maestro-android cloud probe --flow tests/maestro-cloud/scenario-runtime-ready-smoke.yaml
maestro-android cloud smoke
maestro-android cloud benchmark
maestro-android cloud status label:upload-id
```

This wraps the repo's existing Maestro/devctl/scoped-repro patterns behind a single Android-focused external CLI. Use `lint` and `audit-selectors` for flow-health maintenance, and `clean --stale-flows` to prune old tmp flows. See `docs/testing/maestro-android-companion-cli.md`.

Local emulator path:

```bash
maestro-android start-device
maestro-android devices --json
ANDROID_SERIAL=emulator-5554 ADB_SERIAL=emulator-5554 python3 tools/devctl/main.py lane android-instrumented
maestro-android lane smoke --device emulator-5554
```

Use this for the emulator leg of the default evidence matrix. Then run one connected-device lane and one hosted cloud path.

## Gate Wrappers (Policy)

```bash
python3 tools/devctl/main.py gate merge-unblock
python3 tools/devctl/main.py gate promotion
python3 tools/devctl/main.py gate promotion --include-screenshot-pack
```

Gate contract summary:

1. `merge-unblock`: `merge` + `doctor` + `android-instrumented` + risk-triggered lifecycle flow.
2. `promotion`: `merge` + `doctor` + `android-instrumented` + `maestro` + strict `journey` (+ optional `screenshot-pack`).
3. Reports are written to `build/devctl/gates/*.json` with per-step duration, correctness class, and blocking decision.

Device-lock behavior:

1. These lanes now acquire an exclusive per-device lock under the generated lock directory managed by `tools/devctl/lanes.py` to avoid concurrent uninstall/reinstall collisions.
2. Override only for emergency/manual troubleshooting: `POCKETGPT_SKIP_DEVICE_LOCK=1`.
3. Before lane execution, `devctl` runs device-health preflight (wake/unlock, `/data` utilization check, runtime-media storage probe, package-owner metadata check).
4. Runtime-media probe includes retry/backoff and fallback to `/sdcard/Download/<package>/...` when `/sdcard/Android/media/...` returns busy/resource errors.
5. Optional run owner metadata for journey reports:
   - `export POCKETGPT_RUN_OWNER='<your-name-or-handle>'`
6. Real-runtime provisioning auto-resolves the currently installed instrumentation runner before sanity probes (avoids flavor/package-name drift failures).
7. Model artifacts are re-pushed only when remote file size does not match the selected host artifact.
8. Local `devctl` lane artifacts now default to `tmp/devctl-artifacts/`. Override with `POCKET_GPT_DEVCTL_ARTIFACT_ROOT=/absolute/path` when you want a different scratch location.

Wrapper:

```bash
bash scripts/dev/journey.sh [--repeats N]
```

## Scoped Device Crash Repro Helper (Maestro + Logcat)

Use this for targeted single-path crash/hang debugging on a connected device.

```bash
maestro-android scoped --flow tmp/maestro-repro.yaml
```

Common options:

1. `--no-build --no-install` for faster iterative loops.
2. `--device your-device-id` when multiple devices are attached.
3. Keep the flow in `tmp/` with title/description comments on the first two lines.
4. Use `bash scripts/dev/scoped-repro.sh` only when you explicitly need the legacy compatibility wrapper.

GPU probe reason check (log-based, reason-disambiguated):

```bash
bash scripts/dev/gpu-probe-reason-check.sh --expect PROBE_PROCESS_DIED,MODEL_UNAVAILABLE
```

Notes:

1. This command reads `RuntimeGateway` `GPU_OFFLOAD|eligibility` log lines and asserts `probe_reason`, instead of relying on fragile UI text variants.
2. Add `--clear-state` when you want a clean first-run expectation (for example `MODEL_UNAVAILABLE` before model provisioning).
3. `scoped-repro` may still report crash signatures (exit `86`) for `PROBE_PROCESS_DIED`; this wrapper treats that as expected when the parsed reason matches expectations.
4. Add `--strict-maestro-exit` if you also want to fail on scoped Maestro run exit codes (default behavior is reason-first validation).

Screenshot pack workflow:

```bash
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py lane screenshot-pack --product-signal-only
python3 tools/devctl/main.py lane screenshot-pack --update-reference
```

Maestro install:

```bash
curl -Ls https://get.maestro.mobile.dev | bash
```

## GPU Qualification Split Matrix

Use two lanes on purpose:

1. Maestro Cloud for Android UI/API-tier checks only.
2. Explicit physical Android devices for real GPU eligibility.

Commands:

```bash
bash scripts/dev/maestro-cloud-gpu-model-matrix.sh --dry-run --api-levels 29,31,34 --models qwen_0_8b_tiny
bash scripts/dev/maestro-cloud-gpu-model-matrix.sh --api-key-env MAESTRO_CLOUD_API_KEY_2 --models qwen_0_8b
bash scripts/dev/maestro-cloud-upload-status.sh --help
bash scripts/dev/maestro-cloud-upload-status.sh --api-key-env MAESTRO_CLOUD_API_KEY label:mupload_123
bash scripts/dev/maestro-gpu-real-device-matrix.sh --dry-run --serial <serial> --models tiny,qwen_0_8b
```

Plan doc:

- `docs/testing/gpu-qualification-split-plan.md`

## Maestro Cloud (Hosted Automation)

Use this when you want hosted device coverage without a local emulator/phone. Under `QA-14`, this is the target default path for machine-verifiable hosted reruns once artifact parity is proven. Keep `devctl` and the physical-device canary for local preflight, device-specific forensics, and promotion evidence until that parity is established.

Recommended hosted QA ladder:

1. rebuild or confirm the APK is from the current branch tip
2. run one focused hosted scenario first when isolating a new issue
3. run the hosted smoke suite second
4. run local authoritative lanes (`android-instrumented`, `journey`, pinned local `maestro`) after the hosted contract is stable
5. use the physical-device canary for OEM confirmation and final brush, not for first discovery

```bash
bash scripts/dev/maestro-cloud-smoke.sh --api-key-env MAESTRO_CLOUD_API_KEY
bash scripts/dev/maestro-cloud-smoke.sh --api-key-env MAESTRO_CLOUD_API_KEY_2
bash scripts/dev/maestro-cloud-smoke-parallel.sh
```

Optional:

1. Prefer the wrapper commands above instead of copying a raw `maestro cloud --api-key ...` command into shell history.
2. Pick the cloud account explicitly with `--api-key-env MAESTRO_CLOUD_API_KEY`, `--api-key-env MAESTRO_CLOUD_API_KEY_2`, or `--api-key-env MAESTRO_CLOUD_API_KEY_3`.
3. Use `bash scripts/dev/maestro-cloud-smoke-parallel.sh` only when you intentionally want both configured accounts to run the same smoke suite in parallel.
4. Android device model selection is not deterministic in our current lane. As of March 15, 2026 with Maestro CLI `2.2.0`, Android cloud runs executed on `Pixel 6`; use `--android-api-level` and `--device-locale` as the reliable selectors.
5. Maestro Cloud Android binaries should include `arm64-v8a` or be multi-arch. Quick local check:

```bash
unzip -Z1 "${APK_PATH}" | rg '^lib/'
```

First-run flow gate command (writes `status.json`, a run manifest, CLI output, and JUnit for CI/local triage):

```bash
bash scripts/dev/maestro-cloud-flow.sh \
  --flow tests/maestro/scenario-first-run-download-chat.yaml \
  --app-file "${APK_PATH}" \
  --no-build \
  --api-level 34 \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --run-root tmp/maestro-cloud-first-run
```

Full hosted smoke suite on Maestro Cloud:

```bash
bash scripts/dev/maestro-cloud-smoke.sh --api-key-env MAESTRO_CLOUD_API_KEY
```

Dynamic Hugging Face fixture smoke on a pinned local device:

```bash
bash scripts/dev/maestro-hf-fixture-smoke.sh --serial <device>
```

This starts `scripts/dev/hf-fixture-server.py`, builds the debug APK with `-Ppocketgpt.hfFixtureBaseUrl=http://127.0.0.1:<port>/`, reverses the fixture port through `adb`, and runs `tests/maestro/scenario-hf-fixture-download-smoke.yaml`. The smoke resolves the canonical `https://huggingface.co/fixture/tiny-gguf/resolve/main/tiny.gguf` URL through the real debug `ModelProvisioningViewModel` path, then queues it through the normal download UI and asserts visible HF task status. URL input, search result, `Check file`, and install behavior stay covered by the split validation/search flows, Compose contracts, and `ModelDownloadManagerInstrumentationTest`.

The local fixture wrapper builds native bridge libraries by default because the app must pass runtime/provisioning readiness before the debug Model Library entrypoint opens. Use `--disable-native-build` only for compile/debug triage where you do not expect the app to leave the provisioning screen.

HF Maestro flows launch Model Library through the debug-only action `com.pocketagent.android.DEBUG_OPEN_MODEL_LIBRARY` with:

```text
pocketagent.debug.skip_onboarding=true
pocketagent.debug.open_surface=model_library
pocketagent.debug.clear_recent_hf=true
pocketagent.debug.hf_resolve_url=<optional canonical HF file URL>
pocketagent.debug.hf_target_model_id=<optional supported target model id>
```

The action defaults to `skip_onboarding=true` and `open_surface=model_library`. When `pocketagent.debug.hf_resolve_url` is present, the debug build resolves that URL through the same HF acquisition policy used by the `Check file` button. The debug build also accepts `pocketagent.debug.open_surface=model_library` as a launch extra when a runner cannot set a custom Android action. Release builds ignore this debug entrypoint.

Use one pinned transport for device proof:

```bash
adb devices
adb disconnect <duplicate-or-stale-wireless-serial>
maestro-android device probe --device <device>
```

Run split HF proofs before optional hosted fixture work:

```bash
maestro-android test tests/maestro/scenario-hf-url-validation-smoke.yaml --device <device>
maestro-android test tests/maestro/scenario-hf-search-to-candidate-smoke.yaml --device <device>
bash scripts/dev/maestro-hf-fixture-smoke.sh --serial <device>
```

The debug action opens the real `ChatViewModel.showSurface(ModalSurface.ModelLibrary)` path. Do not use `bootstrap-clean-start.yaml` or `open-model-library.yaml` for HF-specific flows; those helpers are intentionally broad and were the source of prior HF automation flake.

For Maestro Cloud HF proof, use the real-Hugging Face candidate and queue-status probes before optional hosted fixture work:

```bash
bash scripts/dev/maestro-cloud-flow.sh \
  --flow tests/maestro-cloud/scenario-hf-realhub-candidate-status-probe.yaml \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --api-level 34

bash scripts/dev/maestro-cloud-flow.sh \
  --flow tests/maestro-cloud/scenario-hf-realhub-queue-status-smoke.yaml \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --api-level 34
```

Run `scripts/dev/maestro-cloud-hf-fixture-smoke.sh` only with a fixture endpoint that Maestro Cloud's hosted device can reach. A local host preflight, Devstack health check, or tunnel health check does not by itself prove hosted-device reachability.

Focused model-management split smoke on Maestro Cloud:

```bash
bash scripts/dev/maestro-cloud-flow.sh \
  --flow tests/maestro-cloud/scenario-model-management-split-smoke.yaml \
  --app-file "${APK_PATH}" \
  --no-build \
  --api-level 34 \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --run-root tmp/maestro-cloud-model-management
```

Poll a launched upload directly:

```bash
bash scripts/dev/maestro-cloud-upload-status.sh \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --project-id <project-id> \
  account-1:<upload-id>
```

Long-running hosted GPU checks can target either account intentionally:

```bash
bash scripts/dev/maestro-cloud-gpu-benchmark.sh --api-key-env MAESTRO_CLOUD_API_KEY_2
bash scripts/dev/maestro-cloud-gpu-model-matrix.sh --api-key-env MAESTRO_CLOUD_API_KEY_2 --dry-run
```

Important:

1. The cloud wrappers still run Maestro flow files directly; they do not run `devctl` device health checks, real-runtime provisioning preflight, per-device lock handling, or local benchmark artifact/logcat capture contracts.
2. Keep `devctl lane maestro`, `devctl lane journey`, and the physical-device canary as the current promotion/closure path until QA-14 artifact parity is complete.
3. Use wrapper-produced `status.json` and `run-manifest.json` as the retained evidence interface for hosted runs.
4. Record the upload id, upload URL, project id, and app binary id for every hosted run you want to reference later.
5. If hosted uploads are `launched=true` but still `PENDING`, treat that as hosted infrastructure latency until a real flow verdict exists.

## Artifact Pruning

Delete stale local lane and cloud reports when they stop being useful:

```bash
bash scripts/dev/prune-devctl-artifacts.sh
bash scripts/dev/prune-devctl-artifacts.sh --days 7 --include-cloud
```

## Hybrid Reliability Gate Policy (CI + Main)

Contract summary:

1. Required branch-protection check name: `ci-required`.
2. `ci-required` verifies whether path-gated jobs ran or skipped by policy; do not require path-gated jobs directly.
3. Required CI lifecycle job name: `lifecycle-e2e-first-run`.
4. For pull requests, lifecycle runs when risk labels or high-risk paths are detected.
5. For every push to `main`, lifecycle runs and blocks on failure.
6. Runtime risk labels:
   - `risk:e2e-lifecycle`
   - `risk:runtime`
   - `risk:provisioning`
7. Lifecycle flow under gate: `tests/maestro/scenario-first-run-download-chat.yaml`.
8. Gate includes one clean-state retry with first-failure artifacts retained.

Local commands to mirror the lifecycle gate:

```bash
maestro-android lint \
  tests/maestro/scenario-first-run-download-chat.yaml \
  tests/maestro/shared/bootstrap-launch-default-model.yaml

./gradlew --no-daemon \
  -Ppocketgpt.enableNativeBuild=true \
  -Ppocketgpt.nativeAbiFilters=x86_64 \
  :apps:mobile-android:assembleDebug

bash scripts/ci/run_lifecycle_e2e.sh --device <serial> local-manual
```

Use an API 33 x86_64 Pixel 6 Google APIs emulator for the closest local match to CI.
The lifecycle wrapper honors `--device`, `ADB_SERIAL`, `ANDROID_SERIAL`, and
`DEVICE_SERIAL`; it now refuses ambiguous multi-device local runs.

## Governance Commands

Wrappers remain callable, but all governance logic runs via `devctl governance`.

```bash
python3 tools/devctl/main.py governance docs-drift
python3 tools/devctl/main.py governance docs-health
python3 tools/devctl/main.py governance docs-accuracy
python3 tools/devctl/main.py governance screenshot-inventory-check
python3 tools/devctl/main.py governance evidence-check docs/operations/evidence/wp-xx/YYYY-MM-DD-note.md
python3 tools/devctl/main.py governance evidence-check-changed
python3 tools/devctl/main.py governance launch-readiness
python3 tools/devctl/main.py governance validate-pr-body /tmp/pr-body.md
python3 tools/devctl/main.py governance stage-close-gate /tmp/pr-body.md
python3 tools/devctl/main.py governance self-test
```

Wrapper:

```bash
bash scripts/dev/launch-readiness.sh
```

## Docs Update Checklist

For changes that affect user-visible behavior or runtime contracts:

1. Update the canonical area owner file from `docs/start-here/source-of-truth-matrix.md`.
2. Update `docs/start-here/documentation-drift-register.md` when a drift risk is opened or closed.
3. Run:
   - `python3 tools/devctl/main.py governance docs-health`
   - `python3 tools/devctl/main.py governance docs-accuracy`
