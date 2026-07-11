# Testing Runbooks

Last updated: 2026-07-11

Use these recipes after choosing the evidence type in
`docs/testing/test-strategy.md`. Full command syntax stays in
`scripts/dev/README.md`.

## Quick Picker

| Need | Run |
|---|---|
| Fast local confidence | `bash scripts/dev/test.sh fast` |
| Merge-equivalent local gate | `bash scripts/dev/test.sh merge` |
| Environment health | `python3 tools/devctl/main.py doctor` |
| Android startup/runtime smoke | `python3 tools/devctl/main.py lane android-instrumented` |
| Local UI smoke | `python3 tools/devctl/main.py lane maestro` |
| Strict send/runtime journey | `python3 tools/devctl/main.py lane journey` |
| Screenshot contract | `python3 tools/devctl/main.py lane screenshot-pack` |
| One UI repro with artifacts | `maestro-android scoped --flow tmp/<flow>.yaml --device <serial>` |
| Selector/flow health | `maestro-android lint` or `maestro-android audit-selectors` |
| Non-generation UI jank | `ANDROID_SERIAL=<serial> bash scripts/dev/perf-interaction-gate.sh --scenario <name> --runtime-state <state> --download-state <state> --voice-state <state>` |
| Composer typing jank | `ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh --build` |
| Regenerate app Baseline Profile | `ANDROID_SERIAL=<api-33+-serial> bash scripts/dev/baseline-profile.sh generate` |

## Fast Engineer Loop

```bash
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh fast
cat build/devctl/recommended-lanes.txt
```

Run recommended follow-up lanes only when they match the changed risk. Do not run
every recommendation blindly if a focused proof already failed.

## Merge Readiness

```bash
bash scripts/dev/test.sh merge
python3 tools/devctl/main.py governance docs-health
python3 tools/devctl/main.py governance docs-accuracy
```

Use this when code is ready for integration. Use focused tests before this while
debugging.

## Android UI Or Runtime Smoke

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
```

Use `android-instrumented` first when startup, runtime readiness, provisioning, or
model-management Compose contracts changed. Use `maestro` when user-visible flow
coverage changed.

For tag-scoped UI smoke:

```bash
python3 tools/devctl/main.py lane maestro --include-tags smoke
python3 tools/devctl/main.py lane maestro --include-tags model-management
```

If selectors, labels, or test tags changed:

```bash
python3 tools/qa-agents/code_truth_manifest.py
maestro-android lint
maestro-android audit-selectors
```

`audit-testtags` may report existing inventory debt. Treat `lint` or
`audit-selectors` as the required selector gate unless the task explicitly owns
testTag inventory cleanup.

## Scoped Device Repro

Use this for one crash, hang, selector drift, or device-only behavior.

```bash
maestro-android scoped --flow tmp/<short-repro>.yaml --device <serial>
```

Rules:

1. Keep the flow in `tmp/`.
2. Put a title and description in the first two lines.
3. Use `--no-build --no-install` only when code and APK did not change.
4. Stop after two failed reruns on the same hypothesis.
5. Promote recurring risks into `tests/maestro/` after the fix.

Legacy fallback:

```bash
bash scripts/dev/scoped-repro.sh --flow tmp/<short-repro>.yaml --serial <serial>
```

Use the legacy wrapper only when an old artifact path or script dependency needs it.

## Lifecycle E2E

Use this only when first-run, download, load-last-used, runtime readiness, or
composer readiness changed.

Fast local flow proof:

```bash
maestro-android lint tests/maestro/scenario-first-run-download-chat.yaml
python3 tools/devctl/main.py lane maestro --flows tests/maestro/scenario-first-run-download-chat.yaml
```

CI-equivalent wrapper:

```bash
bash scripts/ci/run_lifecycle_e2e.sh --device <serial> local-manual
```

The CI wrapper owns clean-state retry, crash-signature capture, and first-failure
artifacts. If it fails, inspect artifacts before changing product code.

## High-Risk PR Verification

Use when the PR has `risk:e2e-lifecycle`, `risk:runtime`,
`risk:provisioning`, or touches startup/download/chat/runtime paths.

```bash
bash scripts/dev/test.sh merge
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane journey --steps instrumentation,send-capture,maestro
bash scripts/ci/run_lifecycle_e2e.sh --device <serial> risk-manual
```

Run this once after focused proofs are green. Do not make each parallel agent run
this whole set.

## Merge And Promotion Gates

Merge-unblock:

```bash
python3 tools/devctl/main.py gate merge-unblock
```

Promotion:

```bash
python3 tools/devctl/main.py gate promotion
python3 tools/devctl/main.py gate promotion --include-screenshot-pack
```

Gate reports land under `build/devctl/gates/`.

## Performance Regression Check

Use the `benchmark` build variant. Never use `debug` for frame-budget evidence.

Confirm the real attached serial first:

```bash
adb devices
```

Preflight before accepting a performance sample:

1. Use the exact serial from `adb devices`.
2. Let the wrapper clean-install the isolated `com.pocketagent.android.benchmark`
   package; never replace the base app for performance evidence.
3. Confirm the app, not SystemUI or Android Settings, is foreground.
4. Collapse notification shade and dismiss keyguard before the run.
5. Do not leave the keyboard open unless the scenario is explicitly measuring typing.
6. Keep battery/thermal state stable when comparing before/after medians.

Composer typing:

```bash
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh --build
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh --build
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh --build
```

Each `perf-baseline.sh --build` invocation is one clean-install diagnostic: it
holds the same device/package lease, activates the profile, measures, and removes
the isolated package. Run it three times only to compare composer medians manually;
the interaction gate below is the automated acceptance gate.

Non-generation UI journeys:

```bash
ANDROID_SERIAL=<serial> bash scripts/dev/perf-interaction-gate.sh --scenario settings-nav --runtime-state unloaded --download-state idle --voice-state inactive
ANDROID_SERIAL=<serial> bash scripts/dev/perf-interaction-gate.sh --scenario model-sheet --runtime-state unloaded --download-state idle --voice-state inactive
ANDROID_SERIAL=<serial> bash scripts/dev/perf-interaction-gate.sh --scenario drawer-search --runtime-state unloaded --download-state idle --voice-state inactive
```

Run only the scenario that matches the changed risk. The gate builds and installs
one native-enabled `benchmark` APK under an isolated application ID, activates
its packaged Baseline Profile, captures three samples without rebuilding,
validates that scenario, device, package, installed-build identity, refresh rate,
compilation mode, and declared runtime/download/voice conditions stayed constant,
rejects debuggable, empty-frame, or incomplete samples, and fails when a metric
median exceeds its target. Thermal and battery state remain per-sample evidence in
`evaluation.json` instead of being silently averaged away. Acceptance also requires
at least 20 rendered frames per sample, unchanged refresh rate before/after,
exact `speed-profile`/`cmdline` compilation, and thermal status 0 before and
after every journey. The first visible Activity launch dismisses onboarding in
an untimed setup pass; frame windows measure configured steady-state navigation.

Use `perf-interaction.sh` directly only for one diagnostic sample that will not be
used as acceptance evidence:

```bash
ANDROID_SERIAL=<serial> bash scripts/dev/perf-interaction.sh --scenario settings-nav --runtime-state unloaded --download-state idle --voice-state inactive --build
```

Compare medians, not one run. Current targets:

| Metric | Target |
|---|---:|
| Janky frames | `<= 20%` |
| p50 | `<= 14 ms` |
| p90 | `<= 25 ms` |
| p99 | `<= 32 ms` |

Artifacts land under `tmp/perf-interaction/...`; each gate root contains three
sample directories and `evaluation.json`. Run separate three-sample groups for
`loaded-idle`, active downloads, or active voice. Never mix conditions inside one
median group.

The three workload flags are operator declarations, not app-observed telemetry.
Confirm the runtime, download queue, and voice listener in the app before the
group. The gate records and consistency-checks the declarations, while its
device checks prove package, foreground, journey, refresh, thermal, and
compilation state.

The gate owns an atomic lock for the selected device and package across build,
clean install, profile activation, all three samples, and cleanup uninstall. A
contention error prints the lock path and its owner PID, start time, and command.
If `SIGKILL` leaves a stale lock under
`${TMPDIR}/pocketgpt-android-perf-locks` (or
`POCKETGPT_ANDROID_PERF_LOCK_ROOT`), verify that exact owner process is dead
before removing only the reported hashed lock directory. Never bypass a live
owner.

## Baseline Profile Generation

Regenerate the app profile only when startup or critical normal-navigation code
changes. Use a disposable/profile-generation API 33+ device with no PocketGPT
data to preserve. The producer targets the base `com.pocketagent.android`
`nonMinifiedRelease` package, and AndroidX can uninstall/reinstall or clear that
package while resetting profile state. The isolated `.benchmark` package protects
performance measurement only; it does not protect generation. The generator
cold-starts the app and opens the session drawer, general settings, completion
settings, and model library without loading a model or running inference:

```bash
ANDROID_SERIAL=<serial> bash scripts/dev/baseline-profile.sh generate
```

The generated app-only rules are committed at
`apps/mobile-android/src/main/generated/baselineProfiles/baseline-prof.txt`.
Generation also assembles `benchmark` and `release`, then fails unless each APK
contains non-empty `assets/dexopt/baseline.prof` and `baseline.profm` files and
the generated PocketGPT rules reached the merged ART profile input. Recheck
packaging without a device using:

```bash
bash scripts/dev/baseline-profile.sh verify
```

No CI lane currently assembles `release`; this local verifier remains the release
packaging proof. CI's native benchmark packaging job runs the same verifier against
its already-built benchmark APK and merged ART input without rebuilding.

Raw generation and verification output stays under `tmp/baseline-profile/`.

## Perfetto Capture For Worst Jank

Use this after benchmark medians are bad. Capture only the worst journey first.

```bash
mkdir -p tmp/perfetto
adb -s <serial> shell cmd statusbar collapse
adb -s <serial> shell rm -f /data/misc/perfetto-traces/settings-nav.perfetto-trace
adb -s <serial> shell perfetto \
  --background-wait \
  -o /data/misc/perfetto-traces/settings-nav.perfetto-trace \
  -t 30s \
  -b 64mb \
  --app com.pocketagent.android.benchmark \
  sched/sched_switch sched/sched_wakeup sched/sched_waking \
  power/suspend_resume power/cpu_frequency power/cpu_idle \
  am wm gfx view binder_driver hal dalvik

ANDROID_SERIAL=<serial> bash scripts/dev/perf-interaction.sh \
  --scenario settings-nav \
  --runtime-state unloaded \
  --download-state idle \
  --voice-state inactive

adb -s <serial> pull \
  /data/misc/perfetto-traces/settings-nav.perfetto-trace \
  tmp/perfetto/settings-nav.perfetto-trace
```

If `trace_processor_shell` is available, inspect the trace locally. Otherwise open
the `.perfetto-trace` in the Perfetto UI and correlate it with the matching
`tmp/perf-interaction/.../summary.json` and `gfxinfo.txt`.

Add AndroidX JankStats only when Perfetto/gfxinfo show sustained jank but do not
identify the composable/state boundary. JankStats should log named UI states, not
replace benchmark samples.

## Compose Hot-Path Checklist

For ViewModel, provisioning, runtime, or Compose shell changes:

1. No disk, network, package-manager, native diagnostics, or filesystem probes on
   the main thread.
2. Sync runtime/provisioning methods that can touch disk are guarded with
   `MainThreadGuard.assertNotMainThread(...)`.
3. Root/shell Compose observes narrow flows.
4. Full `uiState` collection stays behind visibility gates.
5. High-frequency `OutlinedTextField`s use local `TextFieldValue`.
6. New UI state `data class` types are `@Immutable` or listed in stability config.
7. New `derivedStateOf {}` usages are wrapped in `remember`.
8. Cache lifetimes and invalidation rules are explicit.

Proof commands:

```bash
./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest \
  --tests com.pocketagent.android.audit.PerformanceContractAuditTest \
  --tests com.pocketagent.android.ui.ChatAppDerivedStateAuditTest

bash scripts/dev/compose-report-hotpath.sh --build
```

## Cloud Smoke

Use hosted smoke for supplemental machine-verifiable coverage after local contract
proof is stable.

```bash
bash scripts/dev/maestro-cloud-smoke.sh --api-key-env MAESTRO_CLOUD_API_KEY
```

When comparing accounts:

```bash
bash scripts/dev/maestro-cloud-smoke-parallel.sh
```

Poll sparingly:

```bash
bash scripts/dev/maestro-cloud-upload-status.sh \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --project-id <project-id> \
  account-1:<upload-id>
```

Pending or stale hosted uploads are infrastructure evidence, not product evidence.
Start one fresh hosted rerun if the old upload cannot produce a verdict.

## Launch And Moderation Evidence

Launch readiness:

```bash
bash scripts/dev/launch-readiness.sh
```

Moderation and AI human-proxy workflows are specialized launch evidence. Use:

1. `docs/testing/cloud-first-qa-operating-model.md`
2. `docs/testing/qa-operating-principles.md`
3. `docs/operations/publication-closeout-checklist.md`

Do not mix moderation evidence with machine-verifiable lane evidence. They answer
different questions.

## Failure Classification Before Rerun

Before repeating any lane:

1. Read the first failing screenshot or UI dump.
2. Read runner output and logcat.
3. Classify the failure:
   - `product`
   - `harness/bootstrap`
   - `device transport`
   - `hosted/infra`
   - `selector/flow drift`
4. Change only the matching layer.
5. Rerun the smallest affected command.

Do not widen back to full `maestro`, strict `journey`, lifecycle E2E, or cloud
fan-out until the smaller contract is corrected.
