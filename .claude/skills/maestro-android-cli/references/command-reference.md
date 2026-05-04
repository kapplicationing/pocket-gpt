# Maestro Android CLI Reference

## Device-Pinned Lanes

Use the wrapper when you want repo lanes plus an explicit target device:

```bash
maestro-android lane smoke --device your-device-id
maestro-android lane journey --device your-device-id
maestro-android lane screenshot-pack --device your-device-id
```

This saves the manual `ANDROID_SERIAL=<serial> ...` prefix for delegated lanes.

## Targeted Instrumented Runs

Use `scoped` instead of hand-writing long `connectedDebugAndroidTest` commands:

```bash
maestro-android scoped \
  --type instrumented \
  --device your-device-id \
  --test-class com.pocketagent.android.ChatQuickLoadFlowInstrumentationTest
```

Method-level selection works too:

```bash
maestro-android scoped \
  --type instrumented \
  --device your-device-id \
  --test-class com.pocketagent.android.RealRuntimeJourneyInstrumentationTest#send_flow_reaches_ready
```

Pass runner args without spelling out Gradle properties:

```bash
maestro-android scoped \
  --type instrumented \
  --device your-device-id \
  --test-class com.pocketagent.android.MainActivityUiSmokeTest \
  --runner-arg screenshot_pack_dir=tmp/screenshots \
  --runner-arg screenshot_pack_fallback_dir=tmp/screenshots-fallback \
  --no-build \
  --no-install
```

If you bypass the wrapper, the equivalent direct form is:

```bash
ANDROID_SERIAL=your-device-id ./gradlew --no-daemon \
  :apps:mobile-android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketagent.android.MainActivityUiSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.screenshot_pack_dir=tmp/screenshots
```

## Scoped Maestro Repros

```bash
maestro-android scoped --flow tmp/runtime-ready-repro.yaml
maestro-android scoped --flow tmp/runtime-ready-repro.yaml --no-build --no-install
maestro-android scoped --flow tmp/runtime-ready-repro.yaml -- --debug-output /tmp/maestro-debug
```

Keep scoped flows in `tmp/` and include title/description comments on the first two lines.

## Targeted Hosted Repros

Use the smallest hosted command that can fail authoritatively:

```bash
maestro-android cloud probe --flow tests/maestro-cloud/scenario-runtime-ready-smoke.yaml
maestro-android cloud flow tests/maestro-cloud/scenario-runtime-ready-smoke.yaml
maestro-android cloud flow tests/maestro-cloud/scenario-model-management-split-smoke.yaml
```

## Device Storage Inspection

PocketGPT commonly needs both storage roots:

```bash
# App-private files
maestro-android device files models/

# Shared app-owned media cache (persistent models/downloads)
maestro-android device files --storage media models/
maestro-android device files --storage media runtime-model-downloads/
```

Push a companion artifact to the shared cache:

```bash
maestro-android device push --storage media mmproj-q8_0.gguf models/
```

## Runtime Triage

```bash
maestro-android device foreground
maestro-android device probe --device your-device-id
maestro-android device foreground --json
maestro-android device logcat --follow --filter "MULTIMODAL|SendMessage|PocketLlama|RuntimeOrchestrator"
maestro-android device logcat --filter "FATAL|SIGSEGV|ANR" --lines 80
maestro-android device ui
maestro-android device info
maestro-android report latest
maestro-android trace latest
```

If you run the canonical repo lane directly through `devctl`, pin one device with environment rather than an unsupported flag:

```bash
ANDROID_SERIAL=emulator-5554 ADB_SERIAL=emulator-5554 \
  python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-onboarding.yaml
```

Generated prepared-flow YAML files should not stay in the flow tree:

```bash
maestro-android clean --stale-flows
maestro-android clean --stale-flows --confirm
```

Recent failed Maestro flows also leave `flow-state.json`, `failure-context/foreground.json`, and `failure-context/ui.xml` in the flow artifact directory. Read those before attempting the same recovery a third time.
