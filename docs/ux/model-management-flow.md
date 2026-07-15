# Model Management and Runtime Readiness Flow

Last updated: 2026-07-11
Owner: Runtime + Android
Lifecycle: Phase-2 implemented (versioned install + downloads + activation control)

## Product Defaults (P1)

1. Download channel is enabled by default in the primary app build.
2. Version activation auto-activates only when no active version exists for that model.
3. Active version deletion is guarded; safe cleanup is enforced for temp/failed artifacts and can clear the sole installed version when safe.
4. Download continuation/recovery uses persisted task state plus the scheduler-backed manager.
5. Provisioning registry is `modelId` keyed and supports baseline + dynamically discovered IDs.

## Runtime Controls Defaults

1. Runtime performance profile default: `BALANCED`.
2. Exposed profiles: `BATTERY`, `BALANCED`, `FAST`.
3. Routing default: `AUTO`.
4. GPU toggle is capability-gated and persisted only when supported.
5. Model residency defaults:
   - keep loaded in foreground: enabled
   - idle unload TTL: `15m` (`DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS`)
   - warmup on startup: enabled
6. Keep-alive options:
   - `AUTO` (adaptive base TTL: `15m`)
   - `ALWAYS`
   - `ONE_MINUTE`
   - `FIVE_MINUTES`
   - `FIFTEEN_MINUTES`
   - `UNLOAD_IMMEDIATELY`
7. Runtime detail labels after generation:
   - first-token latency
   - total latency
   - prefill latency
   - decode latency
   - decode rate
   - peak RSS

## Runtime Status Model

Runtime status shown in-app:

1. `Not ready`: required startup constraints not satisfied.
2. `Loading`: runtime/model load or stream in progress.
3. `Ready`: runtime can serve send/tool/image paths.
4. `Error`: startup/runtime failure requiring user action.

Runtime backend labels:

1. `NATIVE_JNI`
2. `REMOTE_ANDROID_SERVICE`
3. `ADB_FALLBACK`
4. `UNAVAILABLE`

Android runtime mode selection:

1. `POCKETGPT_ANDROID_RUNTIME_MODE=remote` -> remote Android runtime service bridge.
2. `POCKETGPT_ANDROID_RUNTIME_MODE=in_process` -> in-process JNI bridge.
3. Default mode is `in_process` when `POCKETGPT_ANDROID_RUNTIME_MODE` is unset.

Send unlock rule:

1. Send path requires startup probe + runtime status both `Ready`.
2. Optional-model-only startup failures may still resolve to `Ready` with warnings.
3. Startup timeout remains blocked until refresh (deterministic timeout guidance).

Provisioning readiness (`RuntimeProvisioningSnapshot`) is separate:

1. `READY`: all startup-candidate models provisioned.
2. `DEGRADED`: at least one verified active model exists, but optional models are missing.
3. `BLOCKED`: no verified active startup-candidate model exists.

## In-App Provisioning Paths

### Simple-first `Get ready` action

1. Onboarding presents the product-default lightweight starter model in plain language.
2. `Download & start chatting` records durable download-and-use intent before starting work.
3. The onboarding surface stays visible through `Downloading your AI`, `Checking the download`, `Finishing setup`, and `Starting your AI`.
4. Transfer progress shows bytes and ETA only while bytes are moving; verification, install, and runtime startup use phase status instead of a fake percentage.
5. On completion, the app activates the version if needed, loads it, and refreshes runtime checks.
6. `Start chatting` appears only when the loaded model also passes the send-readiness gate.
7. Users may continue in the background; the durable intent still completes activation and load after recreation.
8. Setup failure stays actionable in onboarding with retry and `Choose another model`; Model Library remains the expert recovery path.
9. Retryable network interruptions stay queued and resume automatically; only exhausted or explicit failures ask the user to retry.
10. If manifest/download is unavailable, local import remains available.

### A) Local import

1. Open `Advanced` -> `Open model library`.
2. Import at least one required GGUF model (recommended first: `Qwen3 0.6B (Q4)`).
3. App records versioned metadata:
   - absolute path
   - SHA-256
   - provenance issuer/signature metadata
   - runtime compatibility
4. Imported version auto-activates only when no active version exists.
5. Refresh runtime checks.

### B) Download manager

1. Model Library opens on `My models`: current runtime model, every active/recoverable download, and models already ready on the device.
2. Choose `Explore` to search the official catalog and start a download for a model/version.
3. `Advanced sources` inside Explore reveals the Hugging Face URL/search flow; it is not the default starting point.
4. Task states: `Queued -> Downloading -> Verifying -> InstalledInactive/Completed`.
5. Checksum and runtime compatibility are hard gates.
6. A normal catalog or advanced-source download never replaces the current chat model automatically. Once installed, it moves to `My models` as `Ready to use`; `Use now` persists that version as active and loads it into the runtime.
7. Provenance metadata is retained; policy enforcement is determined by version verification policy (`INTEGRITY_ONLY` or `PROVENANCE_STRICT`).
8. Pause/resume/retry/cancel are supported in-app.
9. WorkManager or OS interruption reschedules a transfer; only an explicit user action records `Cancelled`.

### C) Runtime controls

1. Open `Advanced` -> `Open runtime controls` or tap the runtime model chip in chat.
2. Review current runtime load state, requested model, and any lifecycle failure details.
3. Load an installed version or load the last-used model.
4. Offload from the runtime controls surface when a model is loaded.
5. Runtime load/offload is intentionally separated from download/import management.

## Verification and Failure Rules

1. Zero-active-model state stays blocked until provisioning + refresh checks succeed.
2. Checksum/runtime mismatch never installs a version.
3. Concurrent duplicate active non-terminal enqueue returns the same task ID.
4. One active task per model/version.
5. Active version removal is guarded and only allowed through the cleanup flow when it can safely clear the sole installed version.
6. Bundled catalog fallback remains available when remote fetch fails.

## Manifest Outage Fallback

1. Manifest fetch failure or empty response must never hide import flow.
2. Download path is marked degraded until manifest recovers.
3. If a verified active model already exists, runtime remains usable after refresh.
4. Recovery guidance routes to import, retry manifest, or refresh checks.

## Stage-2 Side-Load Path (Bench/Closure)

1. `scripts/android/provision_sideload_models.sh`
2. `bash scripts/dev/bench.sh stage2 ...`
3. `python3 scripts/benchmarks/validate_stage2_runtime_evidence.py ...`
