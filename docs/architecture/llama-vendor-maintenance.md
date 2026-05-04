# `llama.cpp` Vendor Maintenance

PocketGPT integrates native inference through the vendored `third_party/llama.cpp` tree and the Android seam in `apps/mobile-android/src/main/cpp/CMakeLists.txt`.

## Vendor stack

Treat the runtime as a layered stack:

1. `ggml-org/llama.cpp` upstream baseline at `c96f608d9cf19d95b28d0e000f0bd3d2f7c6d4e9`
2. PocketGPT overlay patches tracked in `third_party/llama.cpp-patches/`
3. Android integration in `apps/mobile-android/src/main/cpp/`

The app should depend on the patched vendor contents, not on assumptions about which upstream fork happened to contain them.

CI restores the vendored tree from the public upstream base plus the tracked patch series by running `bash scripts/ci/bootstrap_llama_vendor.sh`.

## Current PocketGPT overlay

PocketGPT carries local runtime changes on top of vendored `llama.cpp`, including:

- `52e654729` — Android regex stability and Phi tokenizer support
- `37fea2efc` — optional rotation hook in KV cache
- `0670b510a` — TurboQuant Q rotation and inverse rotation
- `9930c7819` — refine TurboQuant rotation and KV-cache output
- `9c8236b9c` — Q1_0 Bonsai quantization support

## Refresh workflow

When updating the vendor:

1. Start from the desired upstream `llama.cpp` revision.
2. Rebase or regenerate the tracked patch series under `third_party/llama.cpp-patches/`.
3. Keep Android integration changes in `pocket_llama.cpp` and `CMakeLists.txt` small and explicit.
4. Run `bash scripts/ci/bootstrap_llama_vendor.sh` from a clean checkout to prove the vendor can be reconstructed without private refs.
5. Verify that native diagnostics still expose `supports_q1_0` and `supports_q1_0_g128`.

## Bridge support rule

Do not set `ModelCatalog.ModelDescriptor.bridgeSupported = true` only because a model is conceptually compatible with `llama.cpp`.

Set `bridgeSupported = true` only when all are true:

- the vendored native runtime can parse the model artifact format
- the Kotlin/native bridge exposes any required runtime capability flags
- a real JNI/device-path test proves the model loads successfully

Catalog-only or fake-bridge tests are not sufficient evidence for bridge support.

## Required validation for Bonsai-like models

Before shipping a model that depends on a specialized quantization/runtime fork:

- run `bash scripts/dev/test.sh fast`
- run a real Android instrumentation test that seeds the artifact path and performs a JNI load
- confirm the runtime diagnostics payload reports the expected format support flags

This prevents the UI from advertising models that the vendored native runtime cannot actually load.
