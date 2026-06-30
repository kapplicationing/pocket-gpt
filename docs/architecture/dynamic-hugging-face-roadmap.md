# Dynamic Hugging Face Roadmap

Last updated: 2026-06-30
Owner: Runtime + Android + Product
Status: V1 paste-URL flow implemented, fixture proof hardened, product expansion staged

## Current Position

The dynamic Hugging Face flow uses the managed model pipeline. A public HF URL is parsed, validated, converted into a `ModelDistributionVersion`, queued through the normal download manager, installed with sidecar/source metadata, and exposed through the existing `Downloaded models` -> `Load` flow.

The model-selection invariant still holds:

1. A Hugging Face URL creates a managed model version.
2. `Load` is the only user-facing selection action.
3. `modelLoadingState.loadedModel` remains the only visible active model truth.
4. Recent HF entries are redownload affordances, not selected/default/bookmarked model state.

## Current Proof

Green proof from 2026-06-30:

1. `./gradlew :apps:mobile-android:testDebugUnitTest --tests '*HuggingFace*' --tests '*ModelProvisioningViewModelTest'`
2. `./gradlew :apps:mobile-android:testDebugUnitTest`
3. `./gradlew :apps:mobile-android:compileDebugAndroidTestKotlin`
4. Connected device `ModelManagementSheetComposeContractTest`: 19/19 passed on SM-A515F, serial `192.168.246.27:40781`.
5. `maestro-android lint`: 50 flows, 0 errors, 1 existing warning for the GPU benchmark `runScript` command.
6. Shell syntax checks for the touched Maestro wrappers.
7. Local fixture wrapper install plus bootstrap probe passed on SM-A515F after installing the debug APK.

Blocked or pending proof:

1. Local fixture Maestro is not fully green yet. The standalone probe failures were caused by probing before the app was installed; the wrapper-installed probe passed. The first full fixture flow then failed because it anchored on the `Download queue` header while the tiny fixture completed. The flow now targets the queue card id instead. The rerun was interrupted by wireless ADB going `offline` before the flow could reach the fake server beyond `/health`. Latest interrupted run: `tmp/hf-fixture-smoke/20260630T074038Z`.
2. Maestro Cloud execution requires uploading the private debug APK to Maestro Cloud. The repo has a wrapper and flow, but an agent should not run it without explicit approval for that external upload.
3. Live HF download remains a manual long-running compatibility probe, not a default CI gate.

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
5. Open HF search.

### Recent HF Downloads

Implemented:

1. A durable recent-HF store backed by Android `SharedPreferences`.
2. Storage of repo, revision, file path, target model, display name, version, SHA-256, size, and enqueue timestamps.
3. Recent rows in the HF section.
4. Recheck action that reuses the canonical HF URL and target, then routes through the same validation path before queueing.
5. Remove action for stale recent entries.
6. Checked/queued relative timestamps in the recent row.

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
5. Cloud fixture flow: `tests/maestro-cloud/scenario-hf-fixture-download-smoke.yaml`.
6. Cloud fixture wrapper: `scripts/dev/maestro-cloud-hf-fixture-smoke.sh`.
7. Documentation for public fixture exposure through `cloudflared`, `ngrok`, `localhost.run`, Tailscale Funnel, or a tiny hosted VM.

## Tech Roadmap

### 1. Close The Local Maestro Harness Gap

Goal: make local fixture smoke repeatable on one pinned device or emulator.

Next checks:

1. Recover or re-pair wireless ADB for `192.168.246.27:40781`, or use USB for the fixture smoke.
2. Re-run `bash scripts/dev/maestro-hf-fixture-smoke.sh --serial <stable-serial>`.
3. If the queue id appears and pause/resume/cancel/retry pass, record the run root as local fixture evidence.
4. If the flow fails again with app UI evidence, inspect the new `maestro-debug` output captured by the wrapper.
5. If a known-good emulator becomes available, run the same wrapper there to separate device transport from flow logic.

Do not keep rerunning on the offline wireless transport. The next useful local proof requires a stable ADB device.

### 2. Finish Cloud Fixture Proof With Explicit Approval

Goal: prove hosted paste/check/queue/pause/resume/cancel/retry/install-row behavior without live Hugging Face.

Default path:

1. Start `scripts/dev/hf-fixture-server.py` locally.
2. Expose it with a public tunnel, preferably `cloudflared tunnel --url http://127.0.0.1:8765`.
3. Run `scripts/dev/maestro-cloud-hf-fixture-smoke.sh --fixture-base-url <public-url>`.

Constraint:

1. Running Maestro Cloud uploads the debug APK to an external hosted service.
2. This requires explicit approval in agent sessions.
3. CI should use this only where the organization already permits APK upload to Maestro Cloud.

### 3. Harden Metadata And Naming

Remaining cleanup:

1. Keep acquisition HF and source-provenance HF names distinct in docs and package boundaries.
2. Keep the shared `sourceRef` codec as the only JSON mapping for task state and sidecars.
3. Reuse the same source metadata shape for future recent-HF catalog records if recents move out of `SharedPreferences`.
4. Add device migration proof for task-store rows with and without `displayName`/`sourceRef`.

### 4. Keep Admission Failures Pre-Enqueue

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

### 5. CI Shape

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
2. SHA prefix.
3. Target model.
4. Prompt profile when available.
5. Compatibility summary.
6. Blocked reason.

Next polish:

1. Storage impact against current free space.
2. Full checksum status wording.
3. Model card link.
4. License link when Hub metadata exposes it.
5. Clearer copy for target-model mapping.
6. Disabled queue state that explains the exact pending/blocked reason.

### 3. Recent HF Downloads

Implemented V1 exists now.

Next polish:

1. Let a recent row open the candidate preview without scrolling surprises.
2. Consider promoting successfully installed HF versions to a richer local user catalog if users rely on redownload heavily.
3. Add a “clear all recents” action only if the list becomes noisy.

### 4. HF Search

Current app does not have PocketPal-style HF search. It has a paste-URL HF view.

Recommended search plan:

1. Add an “Explore Hugging Face” subview inside the model sheet or a dedicated sheet route.
2. Search repos/files through a small HF search client.
3. Filter to `.gguf` results first.
4. Group results by repo, then show file cards.
5. Show file size, quantization-looking filename, repo downloads/likes when available, license, and gated/private status.
6. Require target-model selection before validation.
7. Route every chosen file through the existing candidate validation path.
8. Persist only successfully queued/downloaded entries as recents.

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

1. Prove app launch outside Maestro and classify the Samsung Maestro bootstrap issue.
2. Run the cloud fixture smoke only after explicit APK-upload approval.
3. Add candidate preview storage/license/model-card polish.
4. Add recent-entry delete and timestamp polish.
5. Design HF search against the existing candidate pipeline.
6. Defer private/gated, arbitrary model IDs, and multimodal until the current proof matrix is stable.
