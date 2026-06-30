# Dynamic Hugging Face Roadmap

Last updated: 2026-06-29
Owner: Runtime + Android + Product
Status: Post-tracer-bullet hardening plan

## Current Proof Position

The public-HF URL tracer bullet now uses the managed model pipeline: validate URL metadata, materialize a `ModelDistributionVersion`, enqueue the normal download path, install sidecar metadata, and expose the installed artifact through `Load`.

Current repeatable coverage:

1. Unit tests cover HF URL parsing, Hub metadata validation, unsupported targets, sidecar metadata, task persistence, admission, and provisioning state.
2. Acquisition now uses an endpoint adapter: production keeps strict `huggingface.co` parsing, while debug/test builds may rewrite Hub API and artifact download calls through `-Ppocketgpt.hfFixtureBaseUrl`.
3. Compose contract tests cover the HF acquisition section, candidate queue event, dynamic download queue rendering, and queue pause/resume/cancel/retry dispatch.
4. Local Maestro covers deterministic invalid-HF blocked-state UX and is included in the default `devctl lane maestro` flow list.
5. Local fixture Maestro can prove paste URL -> check -> queue -> download controls -> installed row without live Hugging Face by running `bash scripts/dev/maestro-hf-fixture-smoke.sh --serial <device>`.
6. Maestro Cloud covers deterministic blocked-state UX through `tests/maestro-cloud/ --include-tags cloud-smoke`, which is already wired into nightly cloud validation.

The live Hugging Face download flow remains a manual probe, not a release contract. Use the fixture server for queue/install confidence, and use live Hub only to confirm external compatibility on an explicit device run.

## Tech Roadmap

### 1. Device And Hosted Proof

Keep the proof matrix explicit:

1. Run Android unit and architecture checks on every dynamic-HF change.
2. Run focused connected instrumentation for `ModelManagementSheetComposeContractTest` and task-store migration tests on a pinned device.
3. Run local Maestro `scenario-hf-url-validation-smoke.yaml` on a pinned physical device.
4. Run local fixture Maestro with `bash scripts/dev/maestro-hf-fixture-smoke.sh --serial <device>` before treating queue/install as proven.
5. Run Maestro Cloud `scenario-hf-url-validation-smoke.yaml` through `scripts/dev/maestro-cloud-flow.sh` or `scripts/dev/maestro-cloud-smoke.sh` for hosted confirmation.

Do not move live-HF download flow into default CI. The fixture lane owns deterministic queue/install evidence; live-HF stays tagged `live-hf,long-running`.

### 2. Fake HF Integration Harness

The local fixture harness owns the full happy path:

1. `scripts/dev/hf-fixture-server.py` returns tree metadata with LFS SHA and size, plus blocked modes for missing SHA, missing size, gated, not found, and checksum mismatch.
2. The same server supports artifact range requests, `If-Range`, `ETag`, `Last-Modified`, `206`, and full-response fallback.
3. Android debug/test builds inject the fake base URL with `-Ppocketgpt.hfFixtureBaseUrl=http://127.0.0.1:<port>/`; production validation still accepts only canonical `https://huggingface.co/...` user input.
4. `tests/maestro/scenario-hf-fixture-download-smoke.yaml` proves paste URL -> check -> candidate -> queue -> pause/resume/cancel/retry -> installed row -> visible `Load`.

This is the right place to prove resume/cancel/retry behavior. Live Hub tests remain a small manual compatibility probe.

### 3. HF Naming Boundaries

Clarify or rename the two HF concepts:

1. Acquisition HF: user-provided Hub URL, metadata lookup, validation, and candidate materialization.
2. Catalog-source HF: a manifest source kind that records where a managed version came from.

Document this in the architecture guide, and rename packages/classes if future code starts to blur acquisition with installed-source provenance.

### 4. Metadata Encoding

Move repeated `sourceRef` encode/decode into one shared codec used by:

1. Download task persistence.
2. Installed sidecar metadata.
3. Runtime provisioning snapshot creation.
4. Future user-HF catalog records.

`ModelSourceRefJsonCodec` now owns the shared JSON mapping for task persistence and installed sidecars. Future user-HF catalog records should use this codec instead of introducing another map-key dialect.

### 5. Migration Proof

Keep task-store migration coverage in both unit and device surfaces:

1. Unit test old rows without `displayName`/`sourceRef`.
2. Instrumentation test SQLite open/migrate on device.
3. Nightly instrumentation should keep running this with `connectedDebugAndroidTest`.

### 6. Admission Clarity

All unsupported cases should fail before enqueue:

1. Unsupported target model ID.
2. Target requiring companion artifacts in v1.
3. Sharded GGUF.
4. Missing LFS SHA or size.
5. Private/gated responses.
6. Non-public or non-Hugging Face URLs.

Keep failure reasons visible in the candidate section and test them without live network.

### 7. Docs Cleanup

Keep one obvious path:

1. `docs/start-here/adding-a-new-model.md` covers built-in catalog additions.
2. Dynamic HF docs cover user-managed versions that map to existing runtime identities.
3. Test docs distinguish deterministic HF validation, fake-HF full-flow automation, and live-HF manual probes.

No release flag is planned right now. The app is not shipped, so the guardrail is an advanced acquisition section plus the proof matrix above.

## Product Roadmap

### 1. V1 Validation Release

Ship the advanced paste-URL flow:

1. Public Hugging Face `.gguf` resolve/blob URL.
2. Supported target model picker.
3. Validate metadata.
4. Queue download.
5. Load only from `Downloaded models`.

Keep the mental model simple: URL creates a managed version; `Load` selects it.

### 2. Better Preview

Improve the candidate card before broadening acquisition:

1. File size and storage impact.
2. SHA/checksum status.
3. Target model and prompt profile.
4. Compatibility pass/fail reason.
5. License/model-card link when available.
6. Clear blocked reasons with no partial selection state.

### 3. Recent HF Downloads

Persist successfully validated entries after download:

1. Show recent dynamic entries after delete.
2. Allow redownload without pasting the URL again.
3. Revalidate SHA/size before redownload.
4. Never persist bearer tokens or CDN redirect URLs.

### 4. Curated Recommendations

Add known-good recommendations before open search:

1. Curated HF repos mapped to supported PocketGPT targets.
2. Compatibility and size precomputed.
3. Safer copy for users who do not understand model/runtime mapping.

### 5. HF Search

Add search only after validation and curated UX are solid:

1. Search GGUF repos/files.
2. Filter sharded/unsupported files.
3. Show disabled unsupported results with concrete reasons.
4. Route every successful result through the same validation/download path.

### 6. Private And Gated Models

Defer until the security model is real:

1. Keystore-backed token storage.
2. Token alias in task state, not token material.
3. Auth-aware downloads and redaction.
4. Terms/access state.
5. Clear 401/403 recovery copy.

### 7. Arbitrary Model Support

Do not let arbitrary HF IDs load until runtime gates are ready:

1. Runtime bridge can load explicit paths safely.
2. Prompt profile or chat-template selection exists.
3. GGUF metadata extraction drives admission.
4. Memory/context/architecture checks are explicit.

### 8. Vision And Multimodal

Support primary GGUF + MMPROJ only as an explicit bundle flow:

1. Pairing policy.
2. Artifact roles in preview.
3. Atomic download/install/delete.
4. Load admission that requires all companion artifacts.

## Recommended Order

1. Finish the fake-HF integration harness.
2. Make the live-HF Maestro probe deterministic enough to be useful, but keep it outside CI.
3. Polish candidate preview and blocked reasons.
4. Add recent HF downloads/redownload.
5. Add curated recommendations.
6. Add search, private/gated auth, arbitrary runtime IDs, and multimodal in separate slices.
