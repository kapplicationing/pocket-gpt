# Dynamic Hugging Face Roadmap

Last updated: 2026-07-03
Owner: Runtime + Android + Product
Status: V1 paste-URL flow implemented, HF automation bootstrap stabilized, product expansion staged

## Current Position

The dynamic Hugging Face flow uses the managed model pipeline. A public HF URL is parsed, validated, converted into a `ModelDistributionVersion`, queued through the normal download manager, installed with sidecar/source metadata, and exposed through the existing `Downloaded models` -> `Load` flow.

The model-selection invariant still holds:

1. A Hugging Face URL creates a managed model version.
2. `Load` is the only user-facing selection action.
3. `modelLoadingState.loadedModel` remains the only visible active model truth.
4. Recent HF entries are redownload affordances, not selected/default/bookmarked model state.

## Current Proof

Current green proof:

1. `./gradlew :apps:mobile-android:testDebugUnitTest --tests '*HuggingFace*' --tests '*ModelProvisioningViewModelTest'`
2. `./gradlew :apps:mobile-android:testDebugUnitTest`
3. `./gradlew :apps:mobile-android:compileDebugAndroidTestKotlin`
4. Connected device `ModelManagementSheetComposeContractTest` on SM-A515F.
5. `maestro-android lint`: 50 flows, 0 errors, 1 existing warning for the GPU benchmark `runScript` command.
6. Shell syntax checks for the touched Maestro wrappers.
7. Local fixture wrapper install plus bootstrap probe passed on SM-A515F after installing the debug APK.
8. Connected-device instrumentation proof for download/install through `ModelDownloadManagerInstrumentationTest#executorDownloadsVerifiesAndInstallsDynamicHuggingFaceTaskFromDeviceFixture`.
9. Local debug HF status and queue-status Maestro probes passed on a pinned physical device.
10. Maestro Cloud real-Hugging Face candidate status probe passed with an APK built without `pocketgpt.hfFixtureBaseUrl`.
11. Maestro Cloud real-Hugging Face queue-status probe passed with the same hosted real-HF path.

Known limits:

1. Hosted Devstack fixture endpoints can pass host-side preflight while Maestro Cloud's hosted emulator still cannot reach them. Do not treat Devstack fixture failure as a product failure unless app-side fixture requests are visible in fixture logs.
2. HF Maestro flows use the debug-only action `com.pocketagent.android.DEBUG_OPEN_MODEL_LIBRARY` and wait for `debug_model_library_ready` instead of shared onboarding/bootstrap helpers.
3. Full install is intentionally proven by instrumentation with an in-device fixture, not by Maestro Cloud.
4. Live full HF downloads remain manual long-running compatibility probes, not default CI gates.

## Implemented V1 Surfaces

### Paste URL Acquisition

Implemented:

1. Strict user input parsing for `https://huggingface.co/.../resolve|blob/.../*.gguf`.
2. Public-only V1 policy.
3. Single-file text-only GGUF policy.
4. Supported PocketGPT runtime target picker.
5. HF metadata lookup for file size and LFS SHA.
6. Candidate materialization into `ModelDistributionVersion`.
7. Existing download/install/load path reuse.

Deferred:

1. Private/gated tokens.
2. Arbitrary runtime model IDs.
3. Sharded GGUF.
4. Vision/MMPROJ pairing.
5. Private/gated open HF search.

### Recent HF Downloads

Implemented:

1. A durable recent-HF store backed by Android `SharedPreferences`.
2. Storage of repo, revision, file path, target model, display name, version, SHA-256, size, and enqueue timestamps.
3. Recent rows in the HF section.
4. Recheck action that reuses the canonical HF URL and target, then routes through the same validation path before queueing.
5. Remove action for stale recent entries.
6. Checked/queued relative timestamps in the recent row.
7. Model-card open action for each recent entry.
8. Clear-recent action for removing all redownload affordances without affecting installed or loaded models.
9. Recheck scrolls the sheet back to the HF acquisition section so the candidate preview/checking state is visible.

Intentional boundaries:

1. Recents are not active models.
2. Recents do not auto-queue.
3. Recents do not bypass SHA/size validation.
4. Recents do not store bearer tokens, redirected CDN URLs, or private access state.

### Fixture And Cloud Harness

Implemented:

1. Local fake HF server with metadata and artifact endpoints.
2. Debug/test endpoint adapter through `-Ppocketgpt.hfFixtureBaseUrl`.
3. Local fixture smoke wrapper: `scripts/dev/maestro-hf-fixture-smoke.sh`.
4. Local wrapper bootstrap probe gate, so harness failures fail early.
5. Local wrapper ADB state preflight, so offline wireless transports fail before starting the fixture server.
6. Cloud real-Hugging Face candidate probe: `tests/maestro-cloud/scenario-hf-realhub-candidate-status-probe.yaml`.
7. Cloud real-Hugging Face queue-status probe: `tests/maestro-cloud/scenario-hf-realhub-queue-status-smoke.yaml`.
8. Optional cloud fixture wrapper: `scripts/dev/maestro-cloud-hf-fixture-smoke.sh`.
9. Cloud wrapper fixture preflight for `/health`, HF search, tree metadata, and byte-range artifact download, so an unreachable or incomplete public fixture URL fails before build/upload.
10. Cloud wrapper rejects loopback/private fixture URLs by default because Maestro Cloud cannot reach the developer machine's `localhost`.
11. Documentation for public fixture exposure through Devstack, `ngrok`, Tailscale Funnel, or a tiny hosted VM, with the caveat that host preflight does not prove hosted-emulator reachability.

## Tech Roadmap

### 1. Keep The HF Automation Entrypoint Deterministic

Goal: keep HF UI proof failures tied to product steps, not generic launch/onboarding navigation.

Implemented:

1. Debug-only activity action: `com.pocketagent.android.DEBUG_OPEN_MODEL_LIBRARY`.
2. Debug extras for skipping onboarding, opening Model Library, clearing download tasks, and clearing recent HF entries. The action defaults to skipping onboarding and opening Model Library; the debug build also accepts `pocketagent.debug.open_surface=model_library` when a runner cannot set a custom action.
3. The action uses the real app state path: `ChatViewModel.completeOnboarding()` and `ChatViewModel.showSurface(ModalSurface.ModelLibrary)`.
4. `debug_model_library_ready` marks the sheet-open state for Maestro and instrumentation.
5. HF Maestro flows use `shared/open-model-library-debug.yaml`.

Rules:

1. Release builds must ignore the debug action.
2. Do not add test-only behavior to HF acquisition, download manager, model selection, or load/offload logic.
3. Keep pause/resume/cancel/retry state-machine proof in instrumentation; Maestro should prove user-visible journey slices.

### 2. Keep Local Device Proof Narrow

Goal: keep local fixture proof repeatable on one pinned device or emulator without treating `adb reverse` transport failures as product failures.

Default checks:

1. Pin one stable serial.
2. Run `tests/maestro/scenario-hf-url-validation-smoke.yaml` for visible invalid URL handling.
3. Run `tests/maestro/scenario-hf-search-to-candidate-smoke.yaml` for visible paste/check/candidate reachability.
4. Run `tests/maestro/scenario-hf-fixture-download-smoke.yaml` through `bash scripts/dev/maestro-hf-fixture-smoke.sh --serial <stable-serial>` for queue-status only.
5. Use instrumentation for download/install terminal proof.
6. If local fixture download fails with `127.0.0.1` or `adb reverse` symptoms, classify it as transport evidence and pivot to instrumentation or hosted proof instead of rerunning the same Maestro path.

Do not keep rerunning on a stale or offline wireless transport. The useful local proof requires one stable ADB device.

### 3. Keep Hosted HF Proof Narrow

Goal: prove hosted app networking and queue-status without mixing full install, fixture ingress, keyboard input, and final UI refresh into one smoke.

Default path:

1. Build/upload an APK without `pocketgpt.hfFixtureBaseUrl`.
2. Run `tests/maestro-cloud/scenario-hf-realhub-candidate-status-probe.yaml`.
3. Run `tests/maestro-cloud/scenario-hf-realhub-queue-status-smoke.yaml`.
4. Keep pause/resume/cancel/retry and full install in instrumentation, not the default cloud smoke.

Optional deterministic fixture path:

1. Start `scripts/dev/hf-fixture-server.py` locally or on a hosted VM.
2. Expose it with one public HTTPS or HTTP URL that Maestro Cloud's hosted emulator can reach.
3. Run `scripts/dev/maestro-cloud-hf-fixture-smoke.sh --fixture-base-url <public-url>`.
4. Confirm fixture logs show app-side requests during the Cloud run, not only host-side wrapper preflight.

Simple exposure options:

1. `cloudflared tunnel --url http://127.0.0.1:8765`: simple when corporate egress policy allows it; verify hosted-device traffic before trusting the result.
2. `ngrok http 8765`: simple and inspectable, but requires an ngrok account in many setups.
3. `ssh -R 80:127.0.0.1:8765 nokey@localhost.run`: useful when SSH egress is available and no extra tunnel tool is installed.
4. `tailscale funnel 8765`: good if the machine is already on Tailscale and Funnel is enabled.
5. Tiny hosted VM or short-lived container: most CI-like and stable; run `hf-fixture-server.py` there and point Maestro Cloud at it.
6. Devstack can be used for host preflight, but current evidence showed hosted-emulator reachability failure. Do not rerun Devstack fixture Cloud after two identical `NETWORK_ERROR` results unless fixture logs show app-side traffic.

Do not use plain `localhost` for Maestro Cloud. In cloud runs, `localhost` is the hosted device or runner environment, not the developer machine that runs the fixture server.

Constraint:

1. Running Maestro Cloud uploads the debug APK to an external hosted service.
2. This requires explicit approval in agent sessions.
3. CI should use this only where the organization already permits APK upload to Maestro Cloud.

### 4. Harden Metadata And Naming

Remaining cleanup:

1. Keep acquisition HF and source-provenance HF names distinct in docs and package boundaries.
2. Keep the shared `sourceRef` codec as the only JSON mapping for task state and sidecars.
3. Reuse the same source metadata shape for future recent-HF catalog records if recents move out of `SharedPreferences`.
4. Add device migration proof for task-store rows with and without `displayName`/`sourceRef`.

### 5. Keep Admission Failures Pre-Enqueue

All unsupported cases should fail before a task enters the download queue:

1. Unsupported target model ID.
2. Target requiring companion artifacts in V1.
3. Non-HF or non-public URLs.
4. Non-GGUF files.
5. Sharded GGUF files.
6. Missing or malformed LFS SHA.
7. Missing or zero size.
8. 401/403 gated/private responses.

The UI should show a concrete blocked reason and never create a partial selected/downloaded state.

### 6. CI Shape

Keep default CI deterministic:

1. Unit tests for parsing, policy, recents, task state, sidecars, and provisioning view-model state.
2. Compose contract/instrumentation for UI intent wiring and queue row rendering.
3. Maestro lint for selector/schema drift.
4. Invalid-HF smoke where hosted APK upload is allowed.
5. Fixture smoke only where a public fixture URL can be controlled.

Do not put live HF downloads in default CI.

## Product Roadmap

### 1. V1 Paste URL Flow

Current recommendation: keep this as the advanced-user entry point.

User flow:

1. Paste public HF GGUF URL.
2. Choose supported runtime target.
3. Check file.
4. Review candidate.
5. Queue download.
6. Load only from `Downloaded models`.

This is the right V1 because it preserves one active model truth and avoids a second catalog/search state.

### 2. Candidate Preview Polish

Already present:

1. File size.
2. Full Hugging Face LFS SHA-256 status with checksum prefix.
3. Target model.
4. Prompt profile when available.
5. Compatibility summary.
6. Blocked reason.
7. Storage impact against current free space.
8. Model-card URL with an open action handled by the sheet host.
9. Explicit target-mapping copy.
10. Queueing disabled-state semantics.
11. License when the Hugging Face model-info response exposes it.
12. License link when the Hugging Face model-info response exposes `license_link`.

Next polish:

1. Add richer compatibility detail once GGUF metadata extraction is available before install.

### 3. Recent HF Downloads

Implemented V1 exists now.

Next polish:

1. Consider promoting successfully installed HF versions to a richer local user catalog if users rely on redownload heavily.

### 4. HF Search

Current app has a PocketPal-style public HF search path inside the model sheet. Search results are file candidates only: choosing one copies its canonical Hugging Face URL into the existing validation path, then the normal candidate preview, queue, downloaded row, and `Load` flow take over.

Implemented:

1. Search endpoint adapter for real HF and fixture routes.
2. Search file result model with repo, file path, model-card URL, downloads, likes, license, gated, and private status.
3. Client-side filtering to `.gguf` files and exclusion of sharded GGUF names.
4. Search results remain candidates only; selected files must still route through candidate validation before queueing.
5. Model sheet search UI with query input, result rows, model-card actions, and disabled gated/private result handling.
6. Repo-grouped search result display with file path, filename-derived quantization hints, model-card links, and file URL links.
7. Fixture Maestro flows now exercise search before candidate validation.

Next search polish:

1. Add richer file metadata if Hugging Face search starts returning reliable size/LFS fields directly.
2. Keep persisting only successfully queued/downloaded entries as recents.

Search must not introduce a selected/search/bookmarked model truth. A result is only a candidate until validated and queued; an installed model is only active after `Load`.

### 5. Curated Recommendations

Out of current scope per product direction.

If revived later, recommendations should be managed versions with known SHA/size and target model mappings, not a separate model selection system.

### 6. Private/Gated And Arbitrary Model IDs

Keep out of scope.

Reasons:

1. Token storage and redaction are a separate security project.
2. Auth-aware download retries add new task-state risk.
3. Arbitrary model IDs require prompt, runtime, memory, architecture, and GGUF metadata admission work.

### 7. Vision And Multimodal

Recommended product shape:

1. Show vision as an explicit bundle flow, not a hidden extra file.
2. The user chooses a primary GGUF and a matching MMPROJ file.
3. The preview shows both artifact roles, sizes, SHA status, and target model capability.
4. Download/install/delete are atomic across the bundle.
5. `Load` is disabled unless all required artifacts are installed and sidecar metadata matches.
6. Image attachment capability derives from the loaded runtime model only.

Implementation shape:

1. Reuse existing artifact roles: `PRIMARY_GGUF` and `MMPROJ`.
2. Extend HF candidate validation from one artifact to a bundle candidate.
3. Add pairing policy before arbitrary search results can create a bundle.
4. Keep companion-artifact failures pre-enqueue until the pairing UI exists.

## Recommended Next Order

1. Keep the proven local matrix current: unit tests, connected-device instrumentation install proof, and local candidate/queue-status Maestro smoke on one pinned device.
2. Keep the proven hosted matrix current: real-HF candidate probe first, then real-HF queue-status probe.
3. Run fixture Cloud only when the fixture endpoint is proven reachable from Maestro Cloud's hosted emulator and fixture logs show app-side requests.
4. Decide whether recent HF downloads need a richer user catalog after actual redownload use.
5. Defer private/gated, arbitrary model IDs, and multimodal until the current proof matrix stays stable.
