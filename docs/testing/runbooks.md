# Testing Runbooks

Last updated: 2026-05-04

These runbooks are short task guides. Strategy and gates stay in `docs/testing/test-strategy.md`.

## Runbook: Fast Engineer Loop

```bash
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh fast
```

Then execute any recommended follow-up lanes from `build/devctl/recommended-lanes.txt`.

## Runbook: Merge Readiness

```bash
bash scripts/dev/test.sh merge
python3 tools/devctl/main.py governance docs-health
python3 tools/devctl/main.py governance docs-accuracy
```

## Runbook: Merge Unblock Gate

```bash
python3 tools/devctl/main.py gate merge-unblock
```

Risk labels can be provided explicitly when triaging PR-equivalent risk locally:

```bash
python3 tools/devctl/main.py gate merge-unblock --risk-label risk:runtime
```

## Runbook: Promotion Gate

```bash
python3 tools/devctl/main.py gate promotion
```

Include screenshot contract checks when needed:

```bash
python3 tools/devctl/main.py gate promotion --include-screenshot-pack
```

## Runbook: Launch Readiness Snapshot

```bash
bash scripts/dev/launch-readiness.sh
```

Use this before weekly launch review, PM planning, or publication review.
It compiles the current execution board, `PROD-10`, and key launch-ticket statuses into two local launch-readiness artifacts in the build tree:

1. `launch-readiness-report.json`
2. `launch-readiness-report.md`

Launch review ordering stays strict:

1. Close code/contract blockers first.
2. Run cloud-first machine-verifiable reruns second.
3. Use one narrow physical-device canary third.
4. Run moderation-backed review last.
5. Once the gate is already green, switch to `docs/operations/publication-closeout-checklist.md` for operator closeout.

## Runbook: Android UI/Runtime Smoke

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane maestro --include-tags smoke
python3 tools/devctl/main.py lane maestro --include-tags model-management
```

If selector/copy/runtime-state labels changed, regenerate the automated-QA truth manifest before widening reruns:

```bash
python3 tools/qa-agents/code_truth_manifest.py
maestro-android lint
maestro-android audit-selectors
```

## Runbook: WP-13 AI human-proxy fallback bundle

Use this when human moderators are unavailable and the deterministic technical path is already stable enough that a proxy session is more likely to expose comprehension/recovery issues than raw runtime breakage.

Primary entrypoint:

```bash
python3 tools/qa-agents/prepare_human_proxy_bundle.py --tester cloud-1 --run
```

That command:

1. Reuses `tools/qa-agents/run_ai_tester.py` for deterministic capture.
2. Creates `tmp/qa-agents/<tester>/<stamp>/human-proxy-bundle/`.
3. Drops a minimal discovery pack:
   - `START_HERE.md`
   - `SUBAGENT_PROMPT.md`
   - `workflow-checklist.md`
   - `bundle-manifest.json`
   - `trip_report.schema.json`
   - `trip_report.template.md`
   - `trip-report.skeleton.json`
4. Points the reviewer at the exact seed and aggregate commands for proxy closure.

Fallback execution contract:

1. Prepare one bundle per tester (`cloud-1`, `cloud-2`, `device-s22`, `device-a51`) or reuse an existing artifact root with `--artifacts-root`.
2. Give the assigned reviewer only the generated bundle path; the bundle is the discovery boundary.
3. Seed each report with the bundle-provided `fill_trip_from_skeleton.py` command.
4. Write reviewer output to `tools/qa-agents/_inputs/<tester>.json`.
5. Aggregate with:

```bash
python3 tools/qa-agents/aggregate_wp13.py --packet-kind ai-human-proxy
```

6. Use the resulting packet at `docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md` as the disclosed moderation-backed fallback artifact for the controlled MVP.

Policy boundary:

1. This path may close the WP-13 moderation leg for the controlled Play Store MVP when moderators are unavailable.
2. It must remain explicitly labeled `AI human-proxy`.
3. It does not replace current machine-verifiable lane evidence.
4. It does not authorize broader public-launch claims or scope expansion.

## Runbook: WP-13 AI testers / AI human-proxy deterministic capture (`run_ai_tester.py`)

**Why this blocks:** Maestro talks to the phone over a **local gRPC tunnel** (host `tcp:7001` ↔ `adb reverse`). `adb install` succeeding does **not** prove Maestro can attach. Stale Maestro processes, a second host consumer of port **7001**, or a wireless serial mismatch yields **`device … is not connected`** before any YAML runs.

**Smooth path (do in order):**

1. `adb devices` — confirm the **exact** serial strings match `tools/qa-agents/run_ai_tester.py` → `DEVICES` (wireless `adb-…​._adb-tls-connect._tcp` rows must be `device`, not `offline`).
2. Per phone, before the eight-flow journey:  
   `bash scripts/dev/maestro-local-bootstrap.sh --serial '<serial>'`  
   (clears reverse rules on that device, re-binds **7001**, kills stray `maestro test` / Studio, runs a noop flow). **Run one device at a time** if you hit “address already in use” on **7001**.
3. `python3 tools/qa-agents/run_ai_tester.py --tester device-s22` then `… device-a51` (runner also invokes the bootstrap script after `adb install`).
4. Merge qualitative fields into `tools/qa-agents/_inputs/device-*.json` from the newest `tmp/qa-agents/<tester>/<stamp>/` logs if needed.
5. `python3 tools/qa-agents/aggregate_wp13.py`
   - expect exit **0** when the current packet lands on `promote`
   - expect exit **1** only when the filled reviewer inputs still support `hold`

Policy boundary:

1. These AI-tester runs are valid for deterministic QA support, artifact review, and blocker summaries.
2. On their own, they do **not** satisfy the WP-13 moderation leg; the approved bundle plus the proxy reporting workflow is required for that.
3. Use the AI packet to pre-screen or seed the fallback moderation packet, not as a substitute for the packet itself.
4. If human moderators are unavailable, label the session `AI human-proxy` and complete the bundle-driven proxy packet path.
5. Use the bundle setup path before treating an `AI human-proxy` session as moderation-backed fallback evidence.

**If Maestro says `device … is not connected` but `adb devices` shows `device`:** Maestro 2.x maintains its **own** device attachment path (see `~/.maestro/tests/*/maestro.log`). Try: unlock the phone, disable/re-enable **Wireless debugging**, switch to a **USB serial** (update `DEVICES` in `run_ai_tester.py` temporarily), reboot `adb kill-server && adb start-server`, upgrade/downgrade Maestro, or run the same commands from a normal terminal session outside IDE automation.

## Runbook: Performance Regression Check

Before UI/runtime refactors, or after touching `ChatViewModel` / `ChatApp`, run on
the **`benchmark` build variant**, NEVER on `debug`:

```bash
# First time, or after dependency/code changes: build and install benchmark APK
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh --build

# Subsequent runs (no rebuild)
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh

# Companion lane for broader perf coverage
python3 tools/devctl/main.py lane maestro --include-tags perf

# Compose stability review for hot composables
bash scripts/dev/compose-report-hotpath.sh --build
```

The `debug` variant carries a 30-50% Compose recompose tax (no AOT, debug source
instrumentation, `MainThreadGuard` + `StrictMode` overhead). `perf-baseline.sh`
refuses to measure debuggable builds without `--allow-debuggable`. See
[android-performance-contract.md](../architecture/android-performance-contract.md)
for the rationale and the 2026-05-02 RCA. For the broader operation-by-operation
follow-up plan, see
[`android-operational-performance-plan.md`](../architecture/performance/android-operational-performance-plan.md).

Run the script three times and compare medians — the first run is always
warmup-skewed because of cold ART, cold Compose runtime, and cold IME.

Current thresholds (Pixel/Galaxy class on benchmark variant):

- `janky_frames <= 20%`
- `p50 <= 14 ms`
- `p90 <= 25 ms`
- `p99 <= 32 ms`

Treat thresholds as ratcheting guardrails: loosening them requires written
justification and reviewer sign-off. Do not accept the current app behavior as the
long-term bar.

### Hot-Path PR Checklist

Use this checklist for changes touching ViewModels, `runtime/`, provisioning, or Compose
shell files:

- [ ] No disk, network, SharedPreferences first-read, native diagnostics, package-manager
      calls, or filesystem probes run on the main thread.
- [ ] Any sync runtime/provisioning method that can touch disk calls
      `MainThreadGuard.assertNotMainThread(...)` or is documented as in-memory only.
- [ ] Root/shell Compose observes narrow flows; full `uiState` collection stays behind a
      visibility gate.
- [ ] New `derivedStateOf {}` usages are wrapped in `remember {}`.
- [ ] Every new UI state `data class` carries `@Immutable` (or is listed in
      `apps/mobile-android/compose-stability.conf`).
- [ ] No new property getters on `@Immutable` data classes.
- [ ] High-frequency `OutlinedTextField`s (composer, search) use the `TextFieldValue`
      overload with local `mutableStateOf`, not the `String` overload.
- [ ] Hot-path caches document their lifetime and invalidation rule.
- [ ] After Compose changes, regenerated the Android module Compose compiler reports
      and verified no new hot-path `unstable` parameters with
      `bash scripts/dev/compose-report-hotpath.sh --build`.
- [ ] Device evidence from
      `ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh --build` is captured
      for risky UI/runtime changes (3 runs, median compared).

## Runbook: Local Lifecycle E2E (First-Run Download -> Chat)

```bash
python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml
```

Use the hardened lane entry instead of raw `maestro test` so the lifecycle run inherits the release-gate clear-state, seeded-runtime, and flow-materialization behavior.

## Runbook: Scoped Device Crash Repro (Maestro + Logcat Fast Loop)

Use this when debugging a specific runtime crash/hang on one device. Keep the flow minimal and local under `tmp/`.

```bash
maestro-android scoped --flow tmp/maestro-repro.yaml
```

Speed loop options:

1. Re-run without rebuilding/installing app:
   - `maestro-android scoped --flow tmp/maestro-repro.yaml --no-build --no-install`
2. Target a specific device:
   - `maestro-android scoped --flow tmp/maestro-repro.yaml --device your-device-id`
3. Use the legacy shell wrapper only if you explicitly need compatibility with old artifact locations:
   - `bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml`

Promotion rule:

1. After fix confirmation, add or update a stable flow under `tests/maestro/` if the journey is a recurring risk.
2. Add targeted unit/contract tests for the logic branch that caused the failure.
3. Run canonical lanes (`fast`, and risk-appropriate `android-instrumented`/`maestro`/`journey`) before merge.

### Local Maestro Bootstrap Recovery

If `lane maestro` or another locally bootstrapped Maestro smoke fails with a
`localhost:7001` gRPC error before any flow logic runs, the Maestro local driver
did not bind. Run:

```bash
maestro-android device probe --device <serial>
```

Then re-run the lane. If the bootstrap probe fails twice in a row, switch to
the wired USB path or the emulator-backed local smoke; do not retry the
wireless serial in a loop.

This is a local Maestro harness recovery step, not the default explanation for
strict `journey`. If strict `journey` already reaches instrumentation or
send-capture, inspect `journey-report.json`, the send-capture artifacts, and
the active send/readiness failure before using this helper.

## Runbook: Bundle Download E2E (Remote Manifest + Local Fixture Server)

Use this when validating the multi-artifact provisioning path (`PRIMARY_GGUF` + `MMPROJ` or future auxiliary artifacts) on a real device.

Preconditions:

1. A single authorized ADB device is visible in `python3 tools/devctl/main.py doctor`.
2. You have a real model bundle on the host machine, not placeholder files.
3. The fixture host is reachable over HTTPS from the device.

Example fixture layout:

```text
tmp/bundle-fixture/
  catalog.json
  model.gguf
  model-mmproj.gguf
```

Minimal `catalog.json` shape:

```json
{
  "models": [
    {
      "modelId": "bundle-e2e-test",
      "displayName": "Bundle E2E Test",
      "versions": [
        {
          "version": "local-e2e",
          "sourceKind": "HUGGING_FACE",
          "promptProfileId": "hf-chatml",
          "artifacts": [
            {
              "artifactId": "primary",
              "role": "PRIMARY_GGUF",
              "fileName": "model.gguf",
              "downloadUrl": "https://<fixture-host>/model.gguf",
              "expectedSha256": "<sha256>",
              "runtimeCompatibility": "android-arm64-v8a",
              "fileSizeBytes": <bytes>
            },
            {
              "artifactId": "mmproj",
              "role": "MMPROJ",
              "fileName": "model-mmproj.gguf",
              "downloadUrl": "https://<fixture-host>/model-mmproj.gguf",
              "expectedSha256": "<sha256>",
              "runtimeCompatibility": "android-arm64-v8a",
              "fileSizeBytes": <bytes>,
              "required": true
            }
          ]
        }
      ]
    }
  ]
}
```

Execution recipe:

```bash
# 1. Verify device visibility.
python3 tools/devctl/main.py doctor

# 2. Serve the local fixture directory.
python3 -m http.server 8765 --directory tmp/bundle-fixture

# 3. Expose it through an HTTPS-capable tunnel or host.
# Example: ngrok http 8765
# Then replace <fixture-host> below with the generated HTTPS hostname.

# 4. Install the app with the remote manifest override.
./gradlew --no-daemon \
  -Ppocketgpt.modelManifestUrl=https://<fixture-host>/catalog.json \
  :apps:mobile-android:installDebug

# 5. Run a focused Maestro repro that opens the model library and downloads the bundle.
bash scripts/dev/scoped-repro.sh --flow tmp/bundle-download-e2e.yaml --serial your-device-id

# 6. Inspect installed files and runtime signals.
maestro-android device --device your-device-id files models/
maestro-android device --device your-device-id logcat --filter "ModelDownload|MULTIMODAL|RuntimeOrchestrator" --lines 120
```

Success criteria:

1. The download completes without surfacing a checksum/runtime/provenance error.
2. Both primary and auxiliary artifacts appear under device model storage.
3. The installed version is visible in the model library and can be activated.
4. Runtime logs show the auxiliary projector path resolving when the model requires it.

## Runbook: High-Risk PR Verification

Use when PR carries `risk:e2e-lifecycle`, `risk:runtime`, `risk:provisioning`, or touches high-risk runtime/provisioning/chat paths.

```bash
bash scripts/dev/test.sh merge
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane journey --steps instrumentation,send-capture,maestro
python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml
```

## Runbook: Main-Push Blocking Lifecycle Check (CI Equivalent)

Use this when debugging or preflighting the required `lifecycle-e2e-first-run` CI job.
Separate fast harness proof from CI-like proof; do not use PR CI as the main debug loop.

Fast local selector and flow-contract proof:

```bash
maestro-android lint \
  tests/maestro/scenario-first-run-download-chat.yaml \
  tests/maestro/shared/bootstrap-launch-default-model.yaml

maestro-android scoped \
  --flow tests/maestro/scenario-first-run-download-chat.yaml \
  --device <serial>
```

The exact CI target is a clean API 33 x86_64 Pixel 6 Google APIs emulator on
`ubuntu-latest`, with GPU set to `swiftshader_indirect` and animations disabled.
For a CI-like local proof, first start and pin that emulator, then build with the same
native flags CI uses:

```bash
./gradlew --no-daemon \
  -Ppocketgpt.enableNativeBuild=true \
  -Ppocketgpt.nativeAbiFilters=x86_64 \
  :apps:mobile-android:assembleDebug

bash scripts/ci/run_lifecycle_e2e.sh --device <serial> local-manual
```

`scripts/ci/run_lifecycle_e2e.sh` is the CI retry/artifact/crash-signature wrapper. It
honors `--device`, `ADB_SERIAL`, `ANDROID_SERIAL`, and `DEVICE_SERIAL`; without one of
those, exactly one authorized adb target must be attached.

For a broader local lane without exact CI emulator parity, run:

```bash
python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml
```

## Runbook: Cloud Smoke (Hosted Machine-Verifiable Rerun)

```bash
bash scripts/dev/maestro-cloud-smoke.sh --api-key-env MAESTRO_CLOUD_API_KEY
```

Use this as the hosted rerun path for machine-verifiable smoke coverage while `QA-14` is proving artifact parity against the local/devctl contract.
This runs only flows tagged `cloud-smoke` under `tests/maestro-cloud/`.
Use this before widening to a physical-device canary. Keep one narrow physical-device canary for OEM/runtime issues and the final pre-promotion brush, and keep long-running hosted benchmarks in dedicated scripts such as `bash scripts/dev/maestro-cloud-gpu-benchmark.sh`.
Prefer the wrappers over copying a raw `maestro cloud --api-key ...` command into shell history.

When multiple Maestro Cloud API keys are available, pick the account explicitly:

```bash
bash scripts/dev/maestro-cloud-smoke.sh --api-key-env MAESTRO_CLOUD_API_KEY
bash scripts/dev/maestro-cloud-smoke.sh --api-key-env MAESTRO_CLOUD_API_KEY_2
```

To fan out the same smoke suite across both accounts in parallel:

```bash
bash scripts/dev/maestro-cloud-smoke-parallel.sh
```

To poll a previously launched upload directly:

```bash
bash scripts/dev/maestro-cloud-upload-status.sh \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --project-id <project-id> \
  account-1:<upload-id>
```

Operator notes:

1. Fresh hosted uploads can be `launched=true` and remain `PENDING` for a while; classify that as hosted infrastructure latency unless a real flow verdict exists.
2. If an older preserved upload id polls as blank status or returns `404`, treat it as stale provenance rather than live blocker truth. Start a fresh hosted rerun and preserve the new upload id instead of continuing to cite the old one as current state.
3. Keep the upload id, upload URL, and project id with the run note so polling and external inspection are possible later.
4. Use the parallel wrapper only when you intentionally want both configured accounts to run the same suite. Keep single-account reruns explicit when isolating one issue.

## Runbook: Cloud GPU vs CPU Benchmark (Hosted Benchmark)

Runs a minimal first-run hosted-device benchmark that requires GPU qualification to succeed, then compares GPU-on vs GPU-off send duration on the same cloud device.

```bash
bash scripts/dev/maestro-cloud-gpu-benchmark.sh --api-key-env MAESTRO_CLOUD_API_KEY
```

Single API level:

```bash
bash scripts/dev/maestro-cloud-gpu-benchmark.sh --api-level 34
bash scripts/dev/maestro-cloud-gpu-benchmark.sh --api-key-env MAESTRO_CLOUD_API_KEY_2 --api-level 34
```

## Runbook: Failure Classification Before Rerun

Use this before repeating any local or hosted lane:

1. Read the first failing screenshot.
2. Read the first failing runner output.
3. Classify the failure as one of:
   - `flow drift`
   - `product bug`
   - `device harness`
   - `hosted infrastructure`
4. Change only the thing that matches that class.
5. Rerun only the smallest affected scenario or lane slice.

Do not widen back to full `maestro`, strict `journey`, or dual-account cloud smoke until the smaller failing contract is corrected.

## Runbook: Runtime Tuning Analysis

Use this when validating learned runtime recommendations or explaining a regression after a device run.

```bash
bash scripts/dev/test.sh fast
python3 tools/devctl/main.py lane journey --repeats 3 --mode strict --reply-timeout-seconds 90
```

Or run the benchmark sweep:

```bash
bash scripts/dev/bench.sh stage2 --device your-device-id --profile quick --models 0.8b --scenarios a
```

Then:

1. Export diagnostics from the app's advanced settings sheet.
2. In the diagnostic message, inspect `RUNTIME_TUNING|...`, `RUNTIME_TUNING_SAMPLE|...`, `RUNTIME_RESIDENCY|...`, and `PREFIX_CACHE_DIAG|...`.
3. Correlate them with files under `scripts/benchmarks/runs/YYYY-MM-DD/your-device-id/` for stage-2 benchmark sweeps or `tmp/devctl-artifacts/YYYY-MM-DD/your-device-id/journey/<stamp>/` for `devctl lane journey` runs:
   - `summary.json`
   - `runtime-log-signals.md`
   - `scenario-a.csv` / `scenario-b.csv`
   - `meminfo-*.txt`
   - `logcat.txt`
4. Use `runtime-log-signals.md` as the first-pass summary for `MMAP|`, `FLASH_ATTN|`, `SPECULATIVE|`, and `PREFIX_CACHE|` issues; then use `docs/testing/runtime-tuning-debugging.md` to decide whether a promotion or demotion was correct.
5. When TTFT or decode throughput still looks wrong, inspect the matching logcat window for `MMAP|`, `FLASH_ATTN|`, `SPECULATIVE|`, `PREFIX_CACHE|`, and `PROMPT_TRIM|`.
6. When the regression appears only after switching conversations, inspect raw logcat for `PREFIX_CACHE|stage=store_state` and `PREFIX_CACHE|stage=restore_state`. `success=true` confirms real slot-state reuse; `reason=over_budget` or `reason=empty` explains why switch-back fell back to re-decode.

## Runbook: Send/Runtime Journey Validation

Artifacts to check after the lane completes:

1. `journey-report.json`
2. `journey-summary.md`
3. each send-window `*-runtime-log-signals.md` linked from the journey summary
4. the original send-window logcat if the summarized finding needs raw-line confirmation
5. when the lane requested `send-capture`, inspect `run-01/screenshots/instrumentation/<stamp>/journey-send-capture.json` first; strict `journey` now prefers the instrumentation-produced send-capture artifact and only falls back to the Maestro kickoff path if that artifact is absent


```bash
python3 tools/devctl/main.py lane journey --repeats 1 --mode strict --reply-timeout-seconds 90
```

Use `--mode valid-output` for slower devices where terminal output validation is required over SLA-oriented strictness.

If the emulator path crashes the app under memory pressure during instrumentation send-capture, do not treat that emulator run as final launch authority. Preserve the artifact for diagnosis, then re-run the strict lane on the physical-device path that the launch gate actually uses.

If you only need to inspect the latest output, use the report helper:

```bash
python3 tools/devctl/main.py report journey
```

## Runbook: Screenshot Contract Validation

```bash
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py governance screenshot-inventory-check
```

For the latest screenshot inventory bundle:

```bash
python3 tools/devctl/main.py report screenshot-pack
```

If the goal is promotion triage and known harness-noise signatures should be treated as non-blocking caveats:

```bash
python3 tools/devctl/main.py lane screenshot-pack --product-signal-only
```

## Runbook: Stage-2 Runtime Closure

```bash
bash scripts/dev/bench.sh stage2 --profile closure --device your-device-id --models both --scenarios both --install-mode auto
```

Attach evidence note and run:

```bash
python3 tools/devctl/main.py governance evidence-check docs/operations/evidence/wp-xx/YYYY-MM-DD-note.md
```
