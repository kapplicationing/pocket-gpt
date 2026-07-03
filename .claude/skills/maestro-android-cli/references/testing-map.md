# PocketGPT Maestro Android Testing Map

Use the smallest standard command that proves the change.

## Default Evidence Matrix

Default Android evidence in this repo is:

1. **Emulator**: fast bootstrap/runtime proof and narrow repro loops.
2. **Connected device**: real hardware proof for storage, permissions, transport, thermal, and OEM behaviour.
3. **Cloud**: hosted supplemental proof for account/environment-specific hosted smoke.

Unless the risk is already clearly isolated, do not stop after one surface. Startup, provisioning, runtime readiness, model-library, and release-path changes should be checked across the full matrix.

## Canonical Ladder

1. `bash scripts/dev/test.sh fast`
   - Fast local confidence for changed code.
   - Use first when you are not on-device.
2. `bash scripts/dev/test.sh merge`
   - Merge-equivalent confidence for broader unit/contract coverage.
   - Use before pushing if the change is not device-only.
3. `python3 tools/devctl/main.py doctor`
   - Confirms the local environment and lane prerequisites.
4. `python3 tools/devctl/main.py lane android-instrumented`
   - Validates Android runtime/bridge boot on device or emulator.
5. `python3 tools/devctl/main.py lane maestro`
   - Canonical Maestro UI smoke.
   - Use `--include-tags smoke` or `--include-tags model-management` when you only need one slice.
   - If you must pin a single device on the current repo lane, export `ANDROID_SERIAL` and `ADB_SERIAL` around the command or use `maestro-android lane <name> --device <serial>` when that lane is configured.
6. `python3 tools/devctl/main.py lane journey`
   - Use for strict send/runtime journey evidence.
   - Add `--steps instrumentation,send-capture,maestro` only when you explicitly want Maestro replay in the same lane.
7. `python3 tools/devctl/main.py lane screenshot-pack`
   - Use for screenshot inventory and reference-pack validation.
   - Add `--product-signal-only` when harness-noise should be caveated, not blocking.
8. `maestro-android scoped --flow tmp/<name>.yaml`
   - Use only for one short runtime crash/hang/regression path.
   - Keep the flow minimal, with `title` and `description` comments in the first two lines.
9. `maestro-android device probe --device <serial>`
   - Use when adb is up but you do not trust the local Maestro bootstrap state yet.
10. `maestro-android scoped --type instrumented --device <serial> --test-class com.example.Test[#method]`
   - Use for one connected Android test class or method without rerunning the full lane.
   - Add `--runner-arg key=value` for screenshot-pack or other instrumentation args.
11. `maestro-android cloud smoke`
    - Hosted supplemental smoke coverage only.
12. `maestro-android cloud benchmark`
    - Hosted GPU-vs-CPU benchmark coverage only.
13. `maestro-android cloud status label:upload-id`
    - Poll upload ids when a cloud run has already been started.

## Matrix Defaults By Goal

1. Startup/bootstrap confidence:
   `android-instrumented` on emulator, `maestro` or a narrow `scoped` flow on one device, then hosted `cloud smoke` when the hosted path matters.
2. Selector or shell refactor confidence:
   emulator or device lane plus `lint`/`audit-selectors`, then widen to cloud only if the changed flow is part of hosted smoke.
3. Hosted-smoke regression triage:
   use the smallest hosted subset that can fail authoritatively, and if the same hosted path is still stuck twice, pivot back to emulator/device evidence and inspect the uploaded cloud flow set instead of blindly rerunning.

## CI Lifecycle Reproduction

The required `lifecycle-e2e-first-run` CI job runs `tests/maestro/scenario-first-run-download-chat.yaml`
through `scripts/ci/run_lifecycle_e2e.sh` on a clean API 33 x86_64 Pixel 6 Google APIs
emulator. For the closest local match, build with native x86_64 enabled and pin the target:

```bash
./gradlew --no-daemon \
  -Ppocketgpt.enableNativeBuild=true \
  -Ppocketgpt.nativeAbiFilters=x86_64 \
  :apps:mobile-android:assembleDebug

bash scripts/ci/run_lifecycle_e2e.sh --device <serial> local-manual
```

Use `maestro-android lint ...` and `maestro-android scoped --flow ... --device <serial>` first
for fast selector/harness debugging, then use the CI wrapper as final local proof.

## Flow Health

- `maestro-android lint` checks tmp flow conventions and reports stale tmp flows.
- `maestro-android audit-selectors` inventories id-based vs text-based selector usage.
- `maestro-android clean --stale-flows --confirm` deletes stale tmp flow files after a dry run.

## Refactor Validation (UI changes without new logic)

When a change is purely UI refactoring (string extraction, composable extraction, layout
adjustments) with no new business logic:

1. `bash scripts/dev/test.sh fast` — confirm compile + existing unit tests pass.
2. `./gradlew :apps:mobile-android:installDebug` — deploy to device/emulator.
3. `python3 tools/devctl/main.py lane screenshot-pack --product-signal-only` — capture
   before/after screenshots for visual diff review.
4. `python3 tools/devctl/main.py lane maestro --include-tags smoke` — confirm existing
   Maestro flows still pass with the refactored UI.

This abbreviated ladder skips instrumented and journey lanes (no runtime/bridge changes)
while still catching visual regressions and selector breakage.

## Best Practices

- Keep scoped repros in `tmp/` and promote recurring risks into stable flows under `tests/maestro/`.
- Use report helpers before digging through raw artifact folders.
- Prefer stable selectors (`id:`/resource-id) over fragile text when the app exposes them.
- Default to emulator + connected device + cloud as complementary evidence, not mutually exclusive options.
- Treat cloud coverage as supplemental. Do not use it as the only merge gate.
- Preserve first-failure artifacts when a bounded retry is part of the gate.
- Treat `--clear-state` as an opt-in reset for app-private data only; keep large model downloads in shared/external storage so normal runs do not re-download them.
- Use `maestro-android lane <name> --device <serial>` when you want a repo lane on a specific phone without manually exporting `ANDROID_SERIAL`.
- For PocketGPT model/download debugging, inspect `Android/media` with `maestro-android device files --storage media ...`.
- If the same recovery path fails twice, stop repeating it. Check `device foreground`, inspect `device ui`, run `device probe --device <serial>` if bootstrap is suspect, read `flow-state.json`, classify the issue, and pivot to a narrower or more canonical command or another surface in the matrix.
- Keep Maestro Cloud imports inside `tests/maestro-cloud/shared/`. Hosted uploads treat `tests/maestro-cloud/` as the workspace root, so cross-tree imports into `tests/maestro/shared/` fail at upload time.

## Targeted Device Loops

For this repo, these are the preferred short loops before rerunning `android-instrumented` or `journey`:

1. `maestro-android scoped --type instrumented --device <serial> --test-class com.pocketagent.android.ChatQuickLoadFlowInstrumentationTest`
2. `maestro-android scoped --type instrumented --device <serial> --test-class com.pocketagent.android.MainActivityUiSmokeTest --runner-arg screenshot_pack_dir=tmp/screens`
3. `maestro-android device probe --device <serial>`
4. `maestro-android device ui`
5. `maestro-android device logcat --follow --filter "PocketLlama|RuntimeOrchestrator|MULTIMODAL"`

Use these when a failure is isolated to one bootstrap path, one instrumentation suite, or one device-only state problem.

## Available testTags (resource-ids for Maestro `id:` selectors)

Run `maestro-android audit-testtags` to verify these against the live build.

| testTag | Component | File |
|---------|-----------|------|
| `composer_input` | Text field | ChatComposerBar.kt |
| `send_button` | Send/Cancel button | ChatComposerBar.kt |
| `chat_gate_inline_card` | Gate status card | ChatComposerBar.kt |
| `session_drawer_button` | Drawer toggle in top bar | ChatApp.kt |
| `advanced_sheet_button` | Settings gear in top bar | ChatApp.kt |
| `chat_message_list` | Message LazyColumn | ChatScreen.kt |
| `unified_model_sheet` | Model bottom sheet | ModelSheet.kt |
| `onboarding_skip` | Skip button | OnboardingScreen.kt |
| `onboarding_next` | Next button | OnboardingScreen.kt |
| `onboarding_get_started` | Get Started button | OnboardingScreen.kt |
| `open_models_button` | Open Models in StatusHeader | ChatStatusHeader.kt |
| `refresh_button` | Refresh in StatusHeader | ChatStatusHeader.kt |
| `create_session_button` | "+" in SessionDrawer header | SessionDrawer.kt |
