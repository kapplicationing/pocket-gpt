---
name: testing-android-maestro
description: Use when running, debugging, or triaging Android tests in the PocketGPT repo — Maestro flows, device-pinned lanes, targeted instrumented tests, screenshot-pack suites, selector drift, model-cache inspection, or device state issues. Prefer over raw adb or hand-written Gradle.
---

# Maestro Android CLI for PocketGPT

## When to Use This

Prefer `maestro-android` over raw `adb` or ad-hoc Gradle when you need:

- stable device selection (auto-resolves serial, warns on duplicate transports)
- structured artifacts per run (logcat, JUnit XML, crash signatures, failure hints)
- one-command scoped repro loops
- targeted instrumented runs without hand-writing `-P` runner-arg chains
- PocketGPT model-cache inspection under `Android/media`

Use raw `adb` only for coordinate taps, keyevents, or shell ops the CLI does not wrap.

## Default Evidence Matrix

Treat Android Maestro evidence as a three-surface matrix by default:

- **Emulator** for fast bootstrap, selector, and runtime-readiness checks.
- **Connected device** for real transport, storage, permissions, thermal, and OEM-specific behaviour.
- **Cloud** for hosted supplemental evidence and account-specific hosted smoke validation.

Do not let one surface silently substitute for another. If startup, provisioning, runtime readiness, model library, or release gating changed, the default expectation is emulator + connected device + cloud unless the user explicitly narrows scope.

## Fast Decision Tree

| Situation | Command | Why |
|---|---|---|
| Kotlin logic only | `bash scripts/dev/test.sh fast` | Fast compile + unit confidence |
| Need the default evidence matrix | Emulator: `lane android-instrumented` or a narrow `scoped` loop; Device: `lane maestro`/`journey` on one phone; Cloud: `maestro-android cloud smoke` or a minimal hosted subset | Separates local/runtime issues from hosted/environment issues |
| Compose/string/selectors changed | Above + `maestro-android lint` + `maestro-android audit-selectors` | Catch drift before long runs |
| Need repo lane on one phone | `maestro-android lane <name> --device <serial>` | Pins `ANDROID_SERIAL` for delegated lanes |
| One flaky UI/runtime path | `maestro-android scoped --flow tmp/repro.yaml [--no-build] [--no-install]` | Fast Maestro repro with artifacts |
| One instrumented class or method | `maestro-android scoped --type instrumented --device <serial> --test-class com.example.Test#method` | Short `connectedDebugAndroidTest` loop |
| Instrumented run needs runner args | Add `--runner-arg key=value` | Avoids long `-Pandroid.testInstrumentationRunnerArguments.*` |
| Screenshot-pack harness behavior | `--runner-arg screenshot_pack_dir=...` or `lane screenshot-pack --device <serial>` | Matches gated screenshot suites |
| Model/download cache inspection | `maestro-android device files --storage media models/` | PocketGPT persists model assets in `Android/media` |
| Device/app state triage | `maestro-android device foreground\|info\|logcat\|ui` | Fast runtime/bootstrap triage |
| Not sure what to run | `maestro-android suggest` | Diff-based lane recommendation |

## Core Commands

- `lane <name> --device <serial>` — run a configured lane on one target device
- `scoped --flow tmp/repro.yaml` — one Maestro repro with logcat + artifacts
- `scoped --type instrumented --test-class Class[#method] --runner-arg key=value` — targeted device test without a dummy flow
- `device files|push --storage data|media ...` — inspect app-private or shared app-owned storage
- `device foreground` — show the current top package/activity and classify app vs permission dialog vs external app
- `device logcat --follow --filter REGEX` — stream app logcat
- `device ui` — dump resource ids, labels, and bounds
- `lint`, `audit-selectors`, `audit-testtags` — catch flow/testTag drift before widening
- `report latest`, `trace latest` — find the newest artifact bundle
- `suggest` — recommend lanes based on `git diff`

## PocketGPT-Specific Lessons

- **Android/media**: Persistent model assets live under `Android/media`, not `Android/data`. Use `--storage media` when checking seeded models, downloads, or multimodal companions.
- **clearState**: Use sparingly. It only resets app-private data and can invalidate seeded runtime state in `Android/media`.
- **Default evidence is multi-surface**: Start from emulator + connected device + cloud. Use the minimum subset only when the failure theory is already narrow and explicit.
- **Screenshot-pack gates**: Do not treat a generic `connectedDebugAndroidTest` failure as product evidence until you confirm the required runner args (`screenshot_pack_dir`, `screenshot_pack_fallback_dir`) were present.
- **Selector collisions**: When Maestro says an element is missing, run `device ui` first. Keyboard labels (e.g., `Send`) can collide with app labels. Prefer `id:` selectors.
- **Foreground loss is a first-class failure mode**: If a flow lands on a permission controller, Play Store, or another package, check `device foreground` or the flow artifact’s `failure-context/foreground.json` before changing product code.
- **Two-attempt pivot rule**: If the same command or recovery path gets stuck twice, stop repeating it. Step back, classify the failure as harness/bootstrap/product/transport, then pivot to a higher-signal command.
- **Cloud imports stay inside the cloud tree**: Maestro Cloud uploads `tests/maestro-cloud/` as its own workspace. Do not reference `../../maestro/shared/...` from cloud flows; keep cloud helper imports inside `tests/maestro-cloud/shared/`.
- **Build variant matters for performance flows**: The default `installDebug` APK
  carries a ~30-50% Compose recompose tax. Functional Maestro flows (smoke,
  journey, screenshot-pack) are fine on `debug` because they verify behaviour
  and selectors, not frame budgets. Performance-sensitive flows or anything
  asserting jank/timings must run on the `benchmark` variant
  (`./gradlew :apps:mobile-android:assembleBenchmark` then `adb install -r ...`).
  For typing-frame jank specifically, use `bash scripts/dev/perf-baseline.sh
  --build`, never a Maestro flow on the debug APK.
- **Operation-specific performance evidence**: Use
  `docs/architecture/performance/android-operational-performance-plan.md` to decide when a
  Maestro flow is functional evidence only (selectors, user journey) versus when
  a benchmark-variant frame-budget run is required (typing, streaming markdown,
  model library during downloads, model switching, voice activation).

## When Tests Fail: Triage

The CLI prints failure hints automatically. For manual triage:

| Symptom | Artifact | Likely cause |
|---|---|---|
| `FATAL EXCEPTION` | `logcat.txt` | App crash — read the stack trace |
| `Fatal signal` / `SIGSEGV` | `logcat.txt` | Native crash — check C++ backtrace |
| `Timeout waiting for` | `maestro-stderr.log` | Wrong selector or slow render — run `audit-selectors` |
| `No view found` | `maestro-stderr.log` | Missing element — check testTag via `audit-testtags` or `device ui` |
| App no longer visible / wrong system screen | `failure-context/foreground.json` | System permission dialog, Play Store, or another package took focus |
| `ANR in` | `logcat.txt` | App froze — blocking I/O on main thread |
| `OutOfMemoryError` | `logcat.txt` | Model too large or memory leak |
| Build failure | `gradle-stderr.log` | Kotlin compile error — fix code, not tests |
| Multiple devices error | CLI output | Duplicate ADB transports — pass `--device <serial>` |

## Workflow

1. Start with the lightest command that proves the risk.
2. Place the risk on the right surface in the matrix: emulator for fast local proof, connected device for real hardware proof, cloud for hosted proof.
3. If the failure is narrow, drop to `scoped` instead of rerunning a whole lane.
4. If the same path gets stuck twice, stop repeating it. Check the current foreground owner, inspect the UI dump, classify the problem, then pivot to the smallest command or surface that can falsify the next theory.
5. Read generated artifacts before guessing. Start with `flow-state.json`, then `failure-context/foreground.json`, then `maestro-stderr.log` / `logcat.txt`.
6. Re-run with `--no-build` or `--no-install` only when code/package inputs did not change.
7. Promote repeat repros from `tmp/` into stable flows or test classes.

## References

- [Testing map](references/testing-map.md) — canonical ladder, testTag inventory, refactor shortcuts
- [Command reference](references/command-reference.md) — concrete PocketGPT examples with real device serials
- [PocketGPT companion CLI guide](../../../docs/testing/maestro-android-companion-cli.md)
