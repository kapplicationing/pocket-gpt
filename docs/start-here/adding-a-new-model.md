# Adding a New Model to PocketGPT

This guide covers the end-to-end process for integrating a new on-device LLM into the PocketGPT codebase.

## Prerequisites

Before touching code, research the model online:

1. **Chat template format** - What prompt format does the model use? (ChatML, Llama3, Phi, etc.)
2. **GGUF availability** - Is a quantized GGUF available on HuggingFace (preferably from `unsloth`)?
3. **Quantization variant** - Which quant provides the best size/quality tradeoff for mobile? (Q4_K_M is typical for 3B+ models, Q4_0 for smaller)
4. **RAM requirements** - How much RAM does the quantized model need at inference time?
5. **Context window** - What context lengths does it support?
6. **Special capabilities** - Thinking tokens, tool calling, vision, multilingual, structured output?
7. **License** - Apache 2.0, MIT, etc.

## Step-by-step Integration

### 1. Add RoutingMode enum value

**File:** `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/RoutingMode.kt`

Add a new enum value for explicit model routing (e.g., `LLAMA_3_2_1B`).

### 2. Add ModelDescriptor to the catalog

**File:** `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ModelCatalog.kt`

For built-in models, this is the authoritative source of bundled defaults. Runtime consumers now read merged specs through `ModelSpecProvider`, so built-in catalog data can coexist with manifest-backed and local-imported variants without extra runtime wiring.

Add:
- A `const val` for the model ID string
- A `ModelDescriptor` entry in the `descriptors` list

Key descriptor fields:
| Field | Description |
|---|---|
| `modelId` | Unique string ID (e.g., `llama-3.2-1b-instruct-q4_k_m`) |
| `tier` | `BASELINE` for production models, `DEBUG` for test/draft models |
| `bridgeSupported` | `true` only after a real JNI/device-path test proves the vendored native runtime can load its format |
| `autoRoutingEnabled` | `true` to include in adaptive routing policy |
| `capabilities` | Set of `SHORT_TEXT`, `LONG_TEXT`, `REASONING`, `IMAGE` |
| `minRamGb` | Minimum device RAM for safe inference |
| `qualityRank` | Higher = better quality (affects routing decisions) |
| `speedRank` | Higher = faster (affects routing decisions) |
| `fallbackPriority` | Lower = preferred fallback (used for priority ordering) |
| `startupCandidate` | `true` to include in startup readiness checks |
| `templateFamily` | Must match a `PromptTemplateFamily` enum value (`CHATML`, `LLAMA3`, `PHI`, `GEMMA`, `GEMMA4`) |
| `envKeyToken` | Uppercase identifier for environment variable overrides |
| `includeAutoRoutingMode` | `true` to include `RoutingMode.AUTO` for this model |
| `explicitRoutingModes` | Set of `RoutingMode` values that select this model explicitly |

The `templateFamily` field drives prompt profile selection automatically through `PromptProfileRegistry` — you do not need to touch `ModelRegistry.kt`, `ModelInteractionRegistry`, or runtime composition wiring for standard template mapping.

### 3. Add chat template (only if the model uses a new template format)

If the model uses an existing template family, set `templateFamily` accordingly and skip this step.

For a **new** template format:

1. Add a new value to `PromptTemplateFamily` enum in:
   `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/model/NormalizedModelSpec.kt`

2. Add a prompt profile entry in:
   `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/model/PromptProfileRegistry.kt`

3. Add a render method and `when` branch in:
   `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatTemplateRenderer.kt`

4. Update the `valid_template_ids` set in `model_audit` governance check:
   `tools/devctl/governance.py`

### 4. Add distribution catalog entry

**File:** `apps/mobile-android/src/main/assets/model-distribution-catalog.json`

Add a JSON entry with:
- `modelId` matching the const in ModelCatalog
- `displayName` for UI display
- `versions` array with download URL, SHA256 hash, file size, and verification policy
- `promptProfileId` only when the manifest/local-import variant needs to declare prompt semantics explicitly instead of inheriting bundled defaults

To get the SHA256 hash from HuggingFace:
```bash
# Use the HuggingFace API tree response and read lfs.oid for GGUF files.
# The top-level oid is not the content SHA256 for LFS-backed blobs.
curl -s "https://huggingface.co/api/models/{org}/{repo}/tree/main" | \
  python3 -c "import sys,json; [print(f['lfs']['oid']) for f in json.load(sys.stdin) if f['path'].endswith('.gguf') and 'lfs' in f]"
```

If provenance signatures are unavailable, use `"verificationPolicy": "INTEGRITY_ONLY"`.

### 5. Update maestro GPU matrix script

**File:** `scripts/dev/maestro-gpu-matrix-common.sh`

Add a case in `pocketgpt_gpu_matrix_model_spec()` for the new model's key.

### 6. Update documentation

- **PRD:** `docs/prd/phase-0-prd.md` — add routing mode to the list
- **Governance manifest:** `docs/governance/docs-accuracy-manifest.json` — add routing mode to `must_contain` arrays

### 7. Run the model-audit governance check

```bash
python3 tools/devctl/main.py governance model-audit
```

This validates:
- Every startup-candidate and bridge-supported model has a distribution catalog entry
- Distribution entries reference known catalog models
- All RoutingMode enum values are bound to descriptors
- All explicit `templateFamily` values reference valid prompt template families
- Bridge-supported models are expected to have runtime capability validation, not just catalog coverage

### 8. Run tests

```bash
bash scripts/dev/test.sh fast
```

Run the full `bash scripts/dev/test.sh` when the change is broad, changes routing behavior, or introduces a new template family.

Because the tests are **catalog-driven**, adding a new model to the descriptor list automatically covers:
- `ModelCatalogTest` — bridge support, routing lookups, env key tokens
- `ModelRegistryTest` — metadata presence, startup candidates, routing modes, template families
- `ModelCatalogIntegrationTest` — prompt profile coverage, routing bindings, distribution catalog coverage

You should **not** need to edit any of these test files when adding a standard model.

The only test that may need manual updates is `AdaptiveRoutingPolicyTest`, since routing case expectations depend on the relative quality/speed ranks of all models in the catalog.

For specialized runtimes or quantizations such as Bonsai-style 1-bit GGUFs, catalog-driven tests are not enough. Add or update a real Android instrumentation smoke that seeds the model path and exercises JNI load before marking the model as `bridgeSupported`.

## Architecture Notes

- `ModelCatalog.kt` defines bundled model defaults.
- Runtime and Android surfaces consume merged normalized specs through `ModelSpecProvider` and `CompositeModelSpecProvider`.
- `PromptProfileRegistry.kt` is the canonical prompt-profile data registry for `PromptTemplateFamily`.
- `NormalizedModelCatalogRegistry.kt` merges bundled, manifest-backed, and local-imported variants; standard built-in model additions do not require manual registry wiring.

## Files touched (typical new model with existing template)

| File | Change |
|---|---|
| `RoutingMode.kt` | Add enum value |
| `ModelCatalog.kt` | Add const + descriptor |
| `model-distribution-catalog.json` | Add download entry |
| `maestro-gpu-matrix-common.sh` | Add case |
| `docs/prd/phase-0-prd.md` | Add routing mode |
| `docs/governance/docs-accuracy-manifest.json` | Add routing mode |
| `AdaptiveRoutingPolicyTest.kt` | Recalculate expectations (if routing ranks change) |

## Files touched (new model with new template format)

All of the above, plus:

| File | Change |
|---|---|
| `NormalizedModelSpec.kt` | Add `PromptTemplateFamily` enum value |
| `PromptProfileRegistry.kt` | Add prompt profile entry |
| `ChatTemplateRenderer.kt` | Add render method + `when` branch |
| `governance.py` | Add to `valid_template_ids` set |

## Gap analysis checklist

After adding a model, document which of its capabilities are not yet supported by the runtime:

- [ ] Thinking mode (`<think>`/`</think>` tokens)
- [ ] Tool calling (model-specific tool call format)
- [ ] Vision / multimodal input
- [ ] Long context (>8K tokens)
- [ ] Multilingual prompting
- [ ] Structured output / JSON mode
- [ ] Speculative decoding (requires a compatible draft model)
