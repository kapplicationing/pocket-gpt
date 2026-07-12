# Controlled-MVP 0.1.0 Package Preflight

Date: 2026-07-11
Owner: Release Ops
Classification: Partial preflight only; not a publishable release artifact

## Scope

This run checked the frozen controlled-MVP identity and native release packaging before a signing identity, physical canary, and enrolled Play Console account are available. It does not replace the clean-tree signed release command in `scripts/release/build-controlled-mvp.sh`.

The preflight started from baseline commit `6015bad1ce1b4b5929a692966400cf4892c085a2` plus the uncommitted release-closeout change set in an isolated clone. The canonical vendor bootstrap resolved llama.cpp tag `b9951` at commit `082b326fc76f6e9bbb835b3920a3022bfdb6691c`.

## Current Proof

Direct Gradle release compilation and APK packaging completed far enough to produce a structurally valid, explicitly unsigned inspection APK.

- Package: `com.pocketagent.android`
- Version name: `0.1.0`
- Version code: `20260711`
- Minimum SDK: `26`
- Target SDK: `35`
- APK SHA-256: `23a47d2bff6ce1b52778805540df3be97ed0dcbc9356c05638d697b5e9e7b21f`
- APK archive check: passed
- Packaged ABI: `arm64-v8a`
- Native libraries: ten `.so` files, including `libpocket_llama.so`, the optimized llama variants, ONNX Runtime, and Sherpa ONNX JNI

Verification commands:

```bash
aapt dump badging apps/mobile-android/build/outputs/apk/release/mobile-android-release-unsigned.apk
unzip -tq apps/mobile-android/build/outputs/apk/release/mobile-android-release-unsigned.apk
unzip -l apps/mobile-android/build/outputs/apk/release/mobile-android-release-unsigned.apk
shasum -a 256 apps/mobile-android/build/outputs/apk/release/mobile-android-release-unsigned.apk
```

## Interrupted Output

The host stopped the shared Gradle daemon during `signReleaseBundle`. The partially written AAB failed ZIP validation, was removed from the build output, and was quarantined outside the repository so it cannot be mistaken for an upload candidate. The valid unsigned APK remains local build output only and is not retained in Git.

This was host/process interference after native compilation, not a passing bundle build. Do not publish or distribute either preflight output.

## Required Publication Proof

Release Ops must still complete all of the following from a reviewed clean `main`:

1. provide the four `POCKETAGENT_RELEASE_*` upload-signing variables;
2. run `bash scripts/release/build-controlled-mvp.sh --device <physical-canary-serial>`;
3. confirm the signed AAB and APK checksums and provenance packet;
4. install and launch-check the signed APK on the pinned physical canary;
5. upload the same verified AAB to an enrolled Play Console internal-testing track.

Until those steps pass, `PROD-13` remains `In Progress` and publication remains blocked.
