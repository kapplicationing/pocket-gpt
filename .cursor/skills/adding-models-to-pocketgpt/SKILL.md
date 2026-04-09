---
name: adding-models-to-pocketgpt
description: Add or update on-device PocketGPT models, including built-in catalog entries, distribution manifest records, prompt template families, routing modes, and validation. Use when the user asks to add, replace, update, or research PocketGPT models, GGUF variants, prompt templates, or model download entries.
---

# Adding Models to PocketGPT

Start with `docs/start-here/adding-a-new-model.md`, then follow this condensed workflow.

## Workflow

1. Establish a baseline:
   ```bash
   python3 tools/devctl/main.py governance model-audit
   ```
2. Research the model:
   - GGUF repo, quant names, file sizes, license, context window
   - prompt format / template family
   - thinking, tool calling, vision, long-context quirks
3. Choose the integration path:
   - existing template family -> standard built-in model addition
   - new template family -> add a new `PromptTemplateFamily` and prompt profile
   - manifest-only experiment -> do not add a bundled descriptor unless the user wants built-in support
4. Implement the required files.
5. Re-run governance and tests.

## Architecture Facts

- `ModelCatalog.kt` defines bundled model defaults.
- Runtime and Android consumers use merged specs through `ModelSpecProvider`.
- Prompt semantics are driven by `templateFamily` plus `PromptProfileRegistry`.
- Standard built-in model additions usually do not require runtime composition rewiring.

## Files Usually Touched

### Standard built-in model

- `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/RoutingMode.kt`
- `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ModelCatalog.kt`
- `apps/mobile-android/src/main/assets/model-distribution-catalog.json`
- `scripts/dev/maestro-gpu-matrix-common.sh`
- `docs/prd/phase-0-prd.md`
- `docs/governance/docs-accuracy-manifest.json`

### New template family

All of the above, plus:

- `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/model/NormalizedModelSpec.kt`
- `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/model/PromptProfileRegistry.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatTemplateRenderer.kt`
- `tools/devctl/governance.py`

## Rules

- Use `templateFamily`, not `chatTemplateId`.
- For Hugging Face GGUF files, use `lfs.oid` as the SHA-256 from the tree API response.
- Keep `bridgeSupported = false` until real device validation proves the runtime can load the model safely.
- Treat `qualityRank` and `speedRank` as routing behavior changes, not cosmetic metadata.
- If auto-routing candidates or ranks change, inspect `AdaptiveRoutingPolicyTest.kt`.

## Validation

Minimum:

```bash
python3 tools/devctl/main.py governance model-audit
./gradlew :packages:inference-adapters:test :packages:app-runtime:test :apps:mobile-android:testDebugUnitTest
```

Preferred wider gate:

```bash
bash scripts/dev/test.sh fast
```
