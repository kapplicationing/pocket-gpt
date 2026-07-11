# Android UI RenderThread RCA — 2026-07-11

## Decision

Treat normal-navigation jank as a product rendering problem, not an inference
cost. Historical physical-device diagnostics were red while the model runtime
was unloaded, downloads were idle, and voice activation was inactive.

The first corrective order is:

1. reduce eager Compose scene size on hot sheets;
2. package and verify an Android Baseline Profile;
3. keep runtime replacement and teardown off the UI thread;
4. re-run three thermally valid samples under one package and compilation state.

## Historical diagnostic baseline

The benchmark APK was native-enabled, nondebuggable, and measured on a Samsung
SM-A515F running Android 13 at 60 Hz. These groups predate the current evidence
schema. Their device and workload conditions were inspected manually, so retain
them as diagnostic baselines rather than current acceptance evidence.

| Scenario | Jank samples | Median jank | p50 | p90 | p99 |
| --- | --- | ---: | ---: | ---: | ---: |
| Settings navigation | 40.32%, 40.00%, 24.81% | 40.00% | 24 ms | 40 ms | 250 ms |
| Model sheet | 50.70%, 33.33%, 47.56% | 47.56% | 25 ms | 61 ms | 300 ms |
| Drawer search | 71.15%, 37.29%, 38.71% | 38.71% | 28 ms | 61 ms | 117 ms |

Settings and drawer remained at Android thermal status 0. The model-sheet group
reached thermal status 1 after the run, so it is a strong diagnostic signal but
must not be used alone as acceptance evidence.

The runtime was unloaded, no PocketGPT download job/service/notification was
active, and the voice listener reported disabled. Slow bitmap uploads were zero
in representative samples. Slow UI-thread work and slow draw-command counts
were high.

## Trace finding

One thermally valid settings journey was captured with Perfetto and inspected
with trace processor v57.2. The reproducible SQL, result table, trace hash, and
sanitized device/gfx manifest live in the
[2026-07-11 evidence ledger](../../operations/evidence/android-ui-renderthread-2026-07-11/README.md).

- PocketGPT's main thread had a maximum observed running slice of 12.4 ms and a
  maximum runnable wait of about 1 ms.
- RenderThread repeatedly spent 24–30 ms in full-frame draws.
- Nineteen traced full-screen drawing slices averaged 18.067 ms.
- Skia command flushes averaged 5.366 ms and buffer swaps averaged 7.525 ms.
- The trace reported 45 dropped negative-timestamp packets. The broad
  main-thread-versus-RenderThread conclusion is retained; fine-grained event
  ordering from those packets is not used.

RenderThread-bound full-frame work dominated this capture. Scene breadth is the
first ablation, not a proven root cause; invalidation frequency, overdraw,
surface layering, and GPU/driver contention remain live hypotheses. The trace
also does not rule out ART/JIT cost, which is why Baseline Profile packaging
remains a separate cross-cutting fix.

## Implemented ablation

General settings now uses keyed lazy sections with skippable section boundaries.
The completion-settings text field also sits in a keyed lazy list so changing
the prompt does not keep every slider and hidden advanced control in the active
scene.

The Compose compiler report validates the new boundaries as restartable and
skippable. The first General-settings-only device sample was thermally valid and
recorded 34.88% jank, p50 23 ms, p90 46 ms, and p99 250 ms across 258 frames.
That single sample is not acceptance evidence.

Two subsequent groups were invalidated when unrelated instrumentation killed
PocketGPT, installed a debuggable package, and left the Samsung launcher in the
foreground. The fail-closed performance tooling rejected those runs. No
before/after claim may be made until a clean three-sample group exists.

## Reproduction

Use only the benchmark variant and declare the workload state:

```bash
ANDROID_SERIAL=<serial> bash scripts/dev/perf-interaction-gate.sh \
  --scenario settings-nav \
  --runtime-state unloaded \
  --download-state idle \
  --voice-state inactive
```

Repeat for `model-sheet` and `drawer-search` only after the device is thermal
status 0 and no other instrumentation owns the transport. The gate requires
three chronological samples with one package identity, one refresh rate, one
compilation mode, non-empty frame windows, and thermal status 0 before and
after every sample. Runtime, download, and voice flags are operator declarations
recorded for reproducibility; verify them against the app before starting. The
gate checks their vocabulary and consistency, not their underlying app state.

## Acceptance

The median thresholds remain:

- jank at or below 20%;
- p50 at or below 14 ms;
- p90 at or below 25 ms;
- p99 at or below 32 ms.

Do not weaken these thresholds to make a noisy run green. If the lazy-screen and
Baseline Profile slices do not materially move RenderThread time, inspect
overdraw, Material surface layering, and full-screen invalidation next.
