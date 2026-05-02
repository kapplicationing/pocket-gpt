# Testing Runbooks

Last updated: 2026-04-26

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

Use this before weekly launch review, PM planning, or release-date discussion.
It compiles the current execution board, `PROD-10`, and key launch-ticket statuses into two launch-readiness artifacts under `build/devctl/launch-readiness/`:

1. `launch-readiness-report.json`
2. `launch-readiness-report.md`

Launch review ordering stays strict:

1. Close code/contract blockers first.
2. Run cloud-first machine-verifiable reruns second.
3. Use one narrow physical-device canary third.
4. Run moderated/human-required review last.

## Runbook: Android UI/Runtime Smoke

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane maestro --include-tags smoke
python3 tools/devctl/main.py lane maestro --include-tags model-management
```

## Runbook: Performance Regression Check

Before UI/runtime refactors, or after touching `ChatViewModel` / `ChatApp`, run:

```bash
ANDROID_SERIAL=<serial> scripts/dev/perf-baseline.sh
python3 tools/devctl/main.py lane maestro --include-tags perf
```

Pass criteria: jank rate <= 25% and 50th-percentile frame <= 18 ms.

## Runbook: Local Lifecycle E2E (First-Run Download -> Chat)

```bash
python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml
```

Use the hardened lane entry instead of raw `maestro test` so the lifecycle run inherits the release-gate clear-state, seeded-runtime, and flow-materialization behavior.

## Runbook: Scoped Device Crash Repro (Maestro + Logcat Fast Loop)

Use this when debugging a specific runtime crash/hang on one device. Keep the flow minimal and local under `tmp/`.

```bash
bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml
```

Speed loop options:

1. Re-run without rebuilding/installing app:
   - `bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml --no-build --no-install`
2. Target a specific device:
   - `bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml --serial your-device-id`
3. Override crash signature regex:
   - `bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml --pattern "<regex>"`

Promotion rule:

1. After fix confirmation, add or update a stable flow under `tests/maestro/` if the journey is a recurring risk.
2. Add targeted unit/contract tests for the logic branch that caused the failure.
3. Run canonical lanes (`fast`, and risk-appropriate `android-instrumented`/`maestro`/`journey`) before merge.

### Local Maestro Bootstrap Recovery

If `lane maestro` or `lane journey --mode strict` fails with a `localhost:7001`
gRPC error before any flow logic runs, the Maestro local driver did not bind.
Run:

```bash
bash scripts/dev/maestro-local-bootstrap.sh --serial <serial>
```

Then re-run the lane. If the bootstrap probe fails twice in a row, switch to
the wired USB path or the emulator-backed local smoke; do not retry the
wireless serial in a loop.

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

```bash
python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml
```

For CI-equivalent raw Maestro + crash-signature guard behavior, run:

```bash
bash scripts/ci/run_lifecycle_e2e.sh local-manual
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

1. Hosted uploads can be `launched=true` but still remain `PENDING` for a while; classify that as hosted infrastructure latency unless a real flow verdict exists.
2. Keep the upload id, upload URL, and project id with the run note so polling and external inspection are possible later.
3. Use the parallel wrapper only when you intentionally want both configured accounts to run the same suite. Keep single-account reruns explicit when isolating one issue.

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


```bash
python3 tools/devctl/main.py lane journey --repeats 1 --mode strict --reply-timeout-seconds 90
```

Use `--mode valid-output` for slower devices where terminal output validation is required over SLA-oriented strictness.

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
