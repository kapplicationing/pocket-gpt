# PocketGPT Maestro Android Testing Map

Use the smallest standard command that proves the change.
The canonical emulator/device/cloud evidence policy lives in `docs/testing/test-strategy.md`.

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
6. `python3 tools/devctl/main.py lane journey`
   - Use for strict send/runtime journey evidence.
   - Add `--steps instrumentation,send-capture,maestro` only when you explicitly want Maestro replay in the same lane.
7. `python3 tools/devctl/main.py lane screenshot-pack`
   - Use for screenshot inventory and reference-pack validation.
   - Add `--product-signal-only` when harness-noise should be caveated, not blocking.
8. `bash scripts/dev/scoped-repro.sh --flow tmp/<name>.yaml`
   - Use only for one short runtime crash/hang/regression path.
   - Keep the flow minimal, with `title` and `description` comments in the first two lines.
9. `maestro-android scoped --type instrumented --device <serial> --test-class com.example.Test[#method]`
   - Use for one connected Android test class or method without rerunning the full lane.
   - Add `--runner-arg key=value` for screenshot-pack or other instrumentation args.
10. `maestro-android cloud smoke`
    - Hosted supplemental smoke coverage only.
11. `maestro-android cloud benchmark`
    - Hosted GPU-vs-CPU benchmark coverage only.
12. `maestro-android cloud status label:upload-id`
    - Poll upload ids when a cloud run has already been started.

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
- Treat cloud coverage as supplemental. Do not use it as the only merge gate.
- Preserve first-failure artifacts when a bounded retry is part of the gate.
- Treat `--clear-state` as an opt-in reset for app-private data only; keep large model downloads in shared/external storage so normal runs do not re-download them.
- Use `maestro-android lane <name> --device <serial>` when you want a repo lane on a specific phone without manually exporting `ANDROID_SERIAL`.
- For PocketGPT model/download debugging, inspect `Android/media` with `maestro-android device files --storage media ...`.

## Targeted Device Loops

For this repo, these are the preferred short loops before rerunning `android-instrumented` or `journey`:

1. `maestro-android scoped --type instrumented --device <serial> --test-class com.pocketagent.android.ChatQuickLoadFlowInstrumentationTest`
2. `maestro-android scoped --type instrumented --device <serial> --test-class com.pocketagent.android.MainActivityUiSmokeTest --runner-arg screenshot_pack_dir=tmp/screens`
3. `maestro-android device ui`
4. `maestro-android device logcat --follow --filter "PocketLlama|RuntimeOrchestrator|MULTIMODAL"`

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
