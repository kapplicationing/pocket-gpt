# `llama.cpp` Vendor Maintenance

PocketGPT vendors an unmodified upstream `llama.cpp` revision. Keep every Android-specific policy in the app seam so an upstream refresh remains mechanical and auditable.

## Current pin

- Repository: `https://github.com/ggml-org/llama.cpp`
- Tag: `b9951`
- Commit: `082b326fc76f6e9bbb835b3920a3022bfdb6691c`
- Local path: `third_party/llama.cpp`

PocketGPT carries no downstream changes inside the vendor tree. Do not edit vendored sources or add a local overlay. Put integration code in `apps/mobile-android/src/main/cpp/` and submit generally useful runtime changes upstream.

## Reproducible bootstrap

Run this command from a clean checkout:

```bash
bash scripts/ci/bootstrap_llama_vendor.sh
```

The script recreates `third_party/llama.cpp` directly from the pinned public tag. It deletes the existing vendor directory first, so do not run it while that directory contains uncommitted work.

`POCKETGPT_LLAMA_UPSTREAM_URL` may select another upstream remote for a controlled test. `POCKETGPT_LLAMA_REF` may select another ref temporarily. CI and committed development state must use the default upstream URL and pinned tag.

## Android integration boundary

The Android seam owns PocketGPT-specific behavior:

- `apps/mobile-android/src/main/cpp/CMakeLists.txt` selects the static CPU and accelerator backends, Android dependencies, build flags, and native library variants.
- `apps/mobile-android/src/main/cpp/pocket_llama.cpp` owns JNI contracts, runtime policy, diagnostics, model lifecycle, and multimodal integration.
- Kotlin runtime and catalog code decide which capabilities the app exposes.

Upstream owns model parsing, tokenization, quantization formats, KV-cache behavior, attention rotation, backend kernels, and `mtmd`. Prefer upstream APIs over private headers or internal class casts.

## Refresh workflow

1. Select an upstream release tag and record its full commit SHA.
2. Review upstream API, CMake, backend, tokenizer, KV-cache, and `mtmd` changes that cross the Android seam.
3. Update the bootstrap default, this document, and the submodule gitlink in the same change.
4. Adapt only the Android seam; leave the vendor tree identical to upstream.
5. Run the bootstrap script from a clean checkout and confirm the resulting `HEAD` matches the recorded SHA.
6. Prove the changed native risk with the narrow Android compile command from `docs/testing/runbooks.md`.
7. Validate model load, text generation, context shifting, multimodal input, and each enabled accelerator path on the targets required by `docs/testing/test-strategy.md`.
8. Run the broad gate once the narrow evidence is current.

## Bridge support rule

Do not advertise a model or runtime feature merely because upstream implements the underlying format.

Enable bridge support only when all are true:

- the pinned native runtime parses the exact artifact used by the app
- the Android seam exposes the required typed capability and policy
- a production-like Android runtime test proves model load and generation

Catalog-only and fake-bridge tests do not prove native support. PocketGPT does not preserve compatibility with artifacts produced by removed downstream quantization formats.
