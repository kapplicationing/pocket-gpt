# Dynamic Hugging Face Models Pre-Investigation

Last updated: 2026-06-29
Owner: Runtime + Android + Product
Current state: Tracer-bullet implementation in progress

## Recommendation

Use PocketGPT's existing model distribution pipeline as the seam for dynamic Hugging Face work. Do not add a second downloader or a second model-selection state.

The first implementation should be a narrow public-HF tracer bullet:

1. Accept a Hugging Face `.gguf` resolve URL or repo/file reference.
2. Resolve file metadata from the Hugging Face API.
3. Validate HTTPS, non-sharded GGUF, file size, LFS SHA-256, runtime tag, prompt profile, and supported model identity.
4. Materialize a `ModelDistributionVersion` with `sourceKind = HUGGING_FACE` and `verificationPolicy = INTEGRITY_ONLY`.
5. Enqueue through `ProvisioningGateway.enqueueDownload(version)`.
6. Render active download tasks in a task-backed `Download queue`.
7. After install, show the model only in `Downloaded models`; `Load` remains the only user-facing selection action.

Do not ship arbitrary Hugging Face model browsing as v1. Runtime load is still gated by static bridge-supported model IDs, and trust/provenance for arbitrary sources is not complete.

## Subagent Findings

| Stream | Main finding | Implementation consequence |
|---|---|---|
| PocketPal HF flow | PocketPal uses light search, detail enrichment, warnings, token toggle, and a single load/offload model state. | Copy the product flow shape, not MobX/global-store implementation or raw token persistence. |
| PocketGPT provisioning | `ModelDistributionVersion -> enqueueDownload -> install -> snapshot -> LoadVersion` is already source-agnostic. | Dynamic HF should adapt into this seam. |
| Runtime compatibility | Arbitrary HF IDs are blocked by static `ModelCatalog.bridgeSupportedModels()` and prompt-template/runtime gates. | V1 must use known supported model IDs or include a separate runtime-gate spike. |
| Product UX | Model Library should own acquisition. Dynamic downloads need a task-backed queue because they may not appear in manifest rows. | Add `Add from Hugging Face`, candidate preview, and `Download queue`; keep top bar as runtime state only. |
| Security/trust | Integrity verification is real; current strict provenance is not real signed provenance. Private/gated HF has no safe token path. | Public integrity-only HF first; defer private/gated and signed provenance. |
| Download reliability | Scheduler, task store, resume, cancel, retry, bundles, storage preflight, and notifications are reusable. | Add stable HF identity, auth/header abstraction later, and explicit HTTP 416 partial cleanup. |

## Current Source-Truth Maps

### PocketPal HF Flow

PocketPal's flow is:

`ModelsScreen -> HFModelSearch -> HFStore.fetchModels -> DetailsView -> ModelFileCard -> ModelStore.downloadHFModel -> common model card load/offload/delete`.

Useful behaviors:

- Debounced Hugging Face search with pagination.
- Detail enrichment before download: file sizes, GGUF specs, storage fit, memory warning.
- Valid GGUF filtering that excludes sharded GGUF files.
- Hidden mmproj files in the main file list, with projection pairing for vision models.
- Secure token storage plus an explicit "use token" preference.
- One visible selected model state: loading selects, manual offload clears.

Do not copy:

- Raw auth token persistence in Android download database.
- Timing-based model insertion before download.
- Global store coupling between UI, API, download, and runtime.

### PocketGPT Provisioning Flow

PocketGPT's existing path is:

`model-distribution-catalog.json / remote manifest -> ModelDistributionManifestProvider -> ModelSheet Available models -> DownloadVersion -> ModelProvisioningViewModel.enqueueDownload -> ProvisioningGateway -> AppRuntimeDependencies -> ModelDownloadManager -> ModelDownloadExecutor -> AndroidRuntimeProvisioningStore.installDownloadedModel -> sidecar metadata -> RuntimeProvisioningSnapshot -> ModelSheet Downloaded models -> LoadVersion`.

This path already supports:

- `ModelSourceKind.HUGGING_FACE`.
- Artifact bundles with `PRIMARY_GGUF` and `MMPROJ`.
- SHA-256 verification and runtime compatibility checks.
- Pause, resume, retry, cancel, metered warnings, storage preflight, and notifications.
- Installed sidecar metadata with source kind, prompt profile, artifacts, and GGUF parameters.
- User-facing `Load` as the runtime selection action.

## Architecture Options

| Option | Summary | Fit | Recommendation |
|---|---|---:|---|
| Dynamic HF to `ModelDistributionVersion` | Convert validated HF file metadata into the existing distribution version type. | High | Use for tracer bullet. |
| User HF catalog merged into manifest | Persist validated user-added HF entries and merge them into available/downloaded flows. | High | Add after tracer bullet, or include if redownload UX is required immediately. |
| Direct HF download gateway | Add a new `enqueueHuggingFaceDownload` path that bypasses manifest types. | Low | Avoid; duplicates task identity, validation, and install behavior. |
| Import-only first | Improve existing local GGUF import and document external download. | Medium | Keep as fallback, not the main answer. |
| Curated remote manifest | Server-side curated HF entries only. | High | Safest production path for broad catalog growth. |
| Search plus validation gate | In-app HF search, but downloadable only after metadata/trust validation. | Medium | Target v2 after tracer bullet. |
| Paste HF URL | Direct URL input, validate, preview, enqueue. | High | Best v1 product slice. |
| Multimodal/MMPROJ | Pair primary GGUF and projector artifact. | Medium | Curate first; defer arbitrary pairing. |

## Proposed Flow

### Phase 1: Public HF URL Tracer Bullet

User flow:

1. User opens `Model library`.
2. User taps `Add from Hugging Face`.
3. User pastes a public HF model file URL.
4. App validates and shows a candidate preview.
5. User taps `Queue download`.
6. Download appears in `Download queue`.
7. After install, row appears in `Downloaded models`.
8. User taps `Load`.

Validation defaults:

- Accept only public Hugging Face model file URLs or repo/file references.
- Accept only `.gguf` files.
- Reject sharded GGUF files.
- Require LFS metadata with 64-hex `oid` and positive size.
- Treat valid LFS `oid` as the expected SHA-256 only when the file is LFS-backed and size matches.
- Store canonical `/resolve/{revision}/{path}` URLs, not transient CDN URLs.
- Require explicit prompt profile selection or known mapping.
- Require known bridge-supported PocketGPT model identity for v1.
- Use `INTEGRITY_ONLY`; do not use current `PROVENANCE_STRICT` for arbitrary HF.

UI defaults:

- Add a task-backed `Download queue` section above `Downloaded models`.
- Do not persist "bookmarked but not downloaded" rows in v1.
- Do not show HF entries as selected after download.
- Keep `Load` as the only selection action.
- Disabled preview and queue buttons must expose concrete reasons through `stateDescription`.
- Add stable tags: `model_library_add_hugging_face`, `model_library_hf_url_input`, `model_library_hf_check_url`, `model_library_hf_candidate_card`, `model_library_hf_queue_download`, and `model_library_hf_error`.

### Phase 2: Durable User HF Catalog

Persist validated HF entries so users can redownload after removal:

- Store repo, revision, artifact path, display name, prompt profile, size, expected SHA-256, LFS oid, source kind, model-card URL, license id, and validation timestamp.
- Merge user entries with bundled/remote manifest entries for the available list.
- Never persist bearer tokens or signed CDN URLs.

### Phase 3: HF Search

Add search only after URL validation and user catalog are proven:

- Search with GGUF-focused filters.
- Fetch repo details before enabling download.
- Show model-card/license/gated/private state.
- Hide mmproj files from the primary file list but detect curated required companions.
- Keep unsupported results visible with a disabled reason.

### Phase 4: Private/Gated HF

Defer private/gated repositories until these are implemented:

- Keystore-backed token storage.
- Token alias in task state, not token material.
- Header provider at download time.
- Strict log redaction.
- Accepted terms state.
- Clear 401/403 error mapping.
- Fine-grained token guidance in UI copy.

### Phase 5: Arbitrary Model Runtime Support

Arbitrary HF model IDs need a runtime compatibility project:

- Decide whether native bridge can load explicit model paths without static catalog registration.
- Replace unknown prompt fallback with explicit prompt profile choice or chat-template support.
- Require GGUF metadata extraction before load.
- Add launch/admission policy for arbitrary architectures, quants, context windows, and memory estimates.

## Security And Trust Defaults

| Source | V1 policy | Notes |
|---|---|---|
| Public HF URL | `INTEGRITY_ONLY` after LFS SHA and size validation | Viable first path. |
| Curated remote manifest | `INTEGRITY_ONLY`; signed only after real trust anchor exists | Current "strict provenance" is not signed provenance. |
| Private/gated HF | Unsupported in v1 | Requires secure token and terms plumbing. |
| Local import | User-trusted local artifact | Keep distinct from publisher-trusted sources. |
| Arbitrary search result | Preview-only until validation passes | No silent install. |

External references checked:

- Hugging Face Hub API docs: `https://huggingface.co/docs/hub/api`
- Hugging Face OpenAPI markdown: `https://huggingface.co/.well-known/openapi.md`
- Hugging Face token docs: `https://huggingface.co/docs/hub/security-tokens.md`

Relevant Hugging Face API facts:

- Repository tree endpoints support recursive file listing.
- Resolve endpoints support redirects and byte-range downloads.
- Gated repositories have explicit access-request endpoints.
- Fine-grained tokens are recommended for production use.
- Read tokens can read public/private repositories available to the token owner.
- Token leakage is explicitly called out as high impact by Hugging Face.

## Test Plan

Narrow unit tests first:

- `ModelDistributionManifestProviderTest`: parse dynamic/user HF source metadata, reject missing SHA/size/non-HTTPS/sharded files.
- `NormalizedModelCatalogRegistryTest`: merge user HF catalog records without colliding with bundled/remote variants.
- `ModelRuntimeLaunchPlannerTest`: block missing required companions and unsupported arbitrary IDs.
- `ModelProvisioningViewModelTest`: enqueue validated HF `ModelDistributionVersion` with `sourceKind = HUGGING_FACE`.
- `ModelLibraryUxDecisionsTest`: dynamic downloaded rows still render `READY/LOADED/SWITCHING` from runtime state only.
- `ModelManagementSheetComposeContractTest`: `Add from Hugging Face` emits validation/queue intents; downloaded rows emit `LoadVersion` only.

Reliability tests:

- `DownloadTaskStateTest`: stable artifact identity for repo/revision/path.
- `ModelDownloadExecutor` focused test or fake host fixture: HTTP 416 clears stale partial and retries cleanly.
- Fake HF API fixture: public file, missing LFS oid, sharded file, gated/private response, bad size, checksum mismatch.

Device and hosted proof:

- Use the existing remote-manifest fixture runbook in `docs/testing/runbooks.md`.
- Add a focused Maestro smoke for `Add from Hugging Face -> preview -> queue -> Download queue`.
- Keep lifecycle gate on `tests/maestro/scenario-first-run-download-chat.yaml`.
- Run cloud only after fake-host and emulator proofs pass.

Suggested first proof command:

```bash
GRADLE_USER_HOME=.gradle-home ./gradlew :apps:mobile-android:testDebugUnitTest --tests '*ModelDistributionManifestProviderTest' --tests '*ModelRuntimeLaunchPlannerTest' --tests '*ModelProvisioningViewModelTest'
```

## Open Decisions

1. Should v1 require a supported PocketGPT model ID mapping, or should the runtime bridge project happen first?
2. Should redownload after delete be required in v1? If yes, include durable user HF catalog in the first slice.
3. Should curated remote manifest be the only production catalog expansion path while paste-URL remains a developer/advanced-user feature?
4. Should current `PROVENANCE_STRICT` be renamed or fixed before dynamic HF ships, to avoid overstating trust?
5. Should private/gated HF be excluded from product copy until token storage and redaction land?

## Final Tracer Bullet

Implement `Paste public HF URL -> validate -> create ModelDistributionVersion -> enqueue existing download -> show task in Download queue -> installed row -> Load`.

Success means a dynamic Hugging Face artifact travels through the same provisioning, download, install, sidecar, snapshot, and runtime-load path as current catalog models, with no new selected/default model state.

## Implementation Shape

The v1 code path is intentionally narrow:

- `HuggingFaceModelReference` parses public HF `resolve`/`blob` URLs and rejects invalid hosts, non-GGUF files, and sharded GGUF names.
- `HuggingFaceHubClient` resolves file metadata from the Hub tree API; tests use a fake client.
- `HuggingFaceModelAcquisition` validates target model support, file size, LFS SHA-256, and text-only artifact policy before creating a `ModelDistributionVersion`.
- `ModelProvisioningViewModel` stores only transient acquisition state: idle, resolving, ready, blocked, and enqueueing.
- `DownloadTaskState`, task persistence, and installed sidecars carry `displayName`, `sourceRef`, and sidecar parameter snapshots so dynamic downloads remain readable without becoming a second catalog truth.
- `ModelSheet` owns the paste URL UI and a task-backed `Download queue`; installed rows still expose `Load` as the only user-facing selection action.
