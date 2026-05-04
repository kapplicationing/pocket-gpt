# Post-Mortem: 2026-05-02 Typing-Jank Investigation

## Status

Resolved. Performance gates updated. Skills, contract doc, AGENTS.md, and runbooks
updated to prevent recurrence.

## Symptom

Typing into the chat composer produced visible lag. Repeated optimization rounds
(narrow flows, dispatcher discipline, composer-text slice splitting, stability
config baseline) reduced StrictMode noise and main-thread I/O, but the headline
typing-jank metric stayed at `janky=29-35% p50=17ms` — well above what felt
acceptable on a Snapdragon 8 Gen 1 (Galaxy S22).

## Root Cause

**The dominant cost was the build variant, not the code.** All measurement was
happening on `assembleDebug` APKs. The `debug` variant sets `android:debuggable=true`,
which:

1. Disables AOT compilation for many methods, forcing JIT interpret-only mode for
   first invocations.
2. Keeps Compose source-info instrumentation enabled (`@StabilityInferred` markers,
   trace markers around every recompose, and slot-table debug observers).
3. Runs `MainThreadGuard.assertNotMainThread` on every guarded call (we added these
   as a safety net during the 2026-04-15 main-thread-I/O fix).
4. Triggers `StrictMode.penaltyLog()` accounting on every disk/network access.
5. Disables `kotlinc` inlining of certain stdlib calls.

Cumulative impact on Compose recompose: **~30-50% across all percentiles**. On the
exact same source tree, simply rebuilding as `release` (with no other changes)
moved jank from 34.94% → 26.51%, p50 17ms → 13ms, p99 44ms → 30ms.

Three secondary code-level issues compounded the visible jank. Each was real but
small. The problem was that they were being measured against a 30-50% inflated
baseline, so each individual fix appeared to "do nothing":

- `ChatUiState.activeSession` was a custom property getter on an `@Immutable`
  data class — broke strong skipping silently and added an O(N) lookup on every
  access including hot UI flow maps.
- `OutlinedTextField` for the composer used the `String` overload, which forces a
  fresh `TextFieldValue` (with selection/composition span reset) on every
  upstream-driven recomposition — doubling per-keystroke cost and stalling the IME.
- `compose-stability.conf` was incomplete: standard Kotlin collections
  (`List<X>`, `Set<X>`, `Map<K,V>`) and most of the UI state classes were not
  listed, so Compose treated them as `unstable` parameters and re-evaluated
  composables that should have skipped.

## Resolution Timeline

| Step | Action | Outcome |
|---|---|---|
| 1 | Captured perfetto trace; identified two `Compose:recompose` passes per janky frame | Hypothesis: state writes during composition |
| 2 | Ablation 1: bypass god-object `_uiState` write on composer text | No improvement (jank 29.73% → 33.73%). State write was not the dominant cost. |
| 3 | Ablation 2: switch `OutlinedTextField` to `TextFieldValue` overload | No improvement. Hypothesis still alive but masked. |
| 4 | Ablation 3: pure-local `mutableStateOf` (no ViewModel touch at all) | Still 34.94%. The bottleneck was deeper than state propagation. |
| 5 | Ablation 4: rebuild the same source tree as `release` (debuggable=false) | **Jank dropped to 26.51%, p99 44 → 30 ms.** Root cause identified. |
| 6 | Add dedicated `benchmark` variant and refactor `perf-baseline.sh` | Permanent fix to the measurement harness |
| 7 | Apply secondary code-level fixes on top of benchmark variant | Final: jank ~18%, p50 11-12ms, p99 25-29ms |

## Final Numbers (3-run median, benchmark variant, Galaxy S22)

| Metric | Before (debug) | After (benchmark + code fixes) | Reduction |
|---|---|---|---|
| Janky frames | 34.94% | 17–19% | ~50% |
| p50 frame | 17 ms | 11–12 ms | ~30% |
| p90 frame | 38 ms | 20–22 ms | ~45% |
| p99 frame | 44 ms | 25–29 ms | ~40% |

## What Went Wrong

1. **We measured on the wrong variant for two release cycles.** Every prior
   typing-jank baseline was captured on the `debug` build. None of the perf
   thresholds in `perf-baseline.sh` accounted for the debug-build tax, so the
   thresholds themselves were calibrated against contaminated numbers.
2. **We added `MainThreadGuard` and `StrictMode` for safety, then measured perf
   with them on.** Both are necessary in development but contribute meaningfully
   to recompose cost. The contract didn't specify a variant policy, so this was
   easy to miss.
3. **We trusted single-run baselines.** The first `dumpsys gfxinfo` run is always
   warmup-skewed (cold ART, cold IME, cold Compose runtime). Treating one
   ~30%-jank reading as actionable evidence sent us chasing code-level fixes that
   were already overshadowed by the harness cost.
4. **Compose stability was implicit.** `compose-stability.conf` covered enums but
   not data classes or collections. Compose compiler reports were never
   regenerated as part of the PR cycle, so `unstable` parameter regressions
   accumulated silently.

## What Went Right

1. **Perfetto + compose-reports gave us the trace tree we needed.** The "two
   recompose passes per frame" signal in the trace is what motivated the
   ablations. Without that we'd have kept tweaking dispatchers blindly.
2. **The ablation methodology eventually worked.** Each "no change" result was
   correct evidence that the suspect was not the dominant cost. The trap was
   that we had to keep going past three negatives before testing the harness
   itself.
3. **The performance contract scaffolding from the prior round held.** Narrow
   ViewModel flows, IO dispatcher discipline, and StrictMode were all good
   investments — they simply weren't sufficient on their own.

## Action Items (all completed in this session)

### Code

- [x] Add `benchmark` build type (`debuggable=false`, `minifyEnabled=false`,
      `profileable=true`, signed with debug keystore) in
      `apps/mobile-android/build.gradle.kts`.
- [x] Rewrite `scripts/dev/perf-baseline.sh` to:
  - default to the `benchmark` variant
  - support `--build` to rebuild + install the benchmark APK
  - refuse `DEBUGGABLE` installs without `--allow-debuggable`
  - report p50/p90/p99 (was only p50)
  - re-calibrate thresholds for the benchmark baseline
- [x] Mark every UI state `data class` `@Immutable` (`ChatGateState`,
      `ModelProvisioningUiState`, `ModelLibraryUiState`, `RuntimeModelUiState`,
      `UiError`, `StreamReducerState`, `StreamTerminalState`,
      `ModelLoadingState.*`).
- [x] Extend `compose-stability.conf` to declare every UI state class plus
      `kotlin.collections.{List,Set,Map,Collection}`.
- [x] Delete `ChatUiState.activeSession` property getter; migrate every caller
      (15 files) to the existing `activeSession()` extension function.
- [x] Switch `ComposerInputRow` to the `TextFieldValue` overload with
      `mutableStateOf<TextFieldValue>` and forward only text changes upstream.
- [x] Remove redundant `verticalScroll(rememberScrollState())` on the composer
      `Column` (the inner `OutlinedTextField` already scrolls via `maxLines`).
- [x] Add a narrow `currentCompletionSettingsFlow` to `ChatViewModel` so the
      modal sheet host stops calling `state.activeSession()?.completionSettings`
      on the god-object.

### Static enforcement (PerformanceContractAuditTest)

- [x] `chat ui state does not expose activeSession as a property getter`
- [x] `composer text field uses TextFieldValue overload not String overload`
- [x] `every UI state class is annotated Immutable or appears in stability config`
- [x] `perf baseline script refuses to measure debug builds`

### Documentation & skills

- [x] `docs/architecture/android-performance-contract.md` — added build-variant
      policy, current threshold table, lessons-from-RCA section.
- [x] `docs/testing/runbooks.md` — Performance Regression Check now mandates
      `--build` on the benchmark variant; Hot-Path PR Checklist extended with
      stability/composer items.
- [x] `AGENTS.md` — added rule #8 forbidding perf measurement on `debug` and
      pointing to the benchmark variant.
- [x] `.agents/skills/debug-pocket-gpt/SKILL.md` — new "Performance Debugging"
      section with build-variant rule, methodology, common pitfalls.
- [x] `.agents/skills/pocketgpt-coding-best-practices/SKILL.md` — added Compose
      Stability Rules and Performance Contract Quick Checklist sections.
- [x] `.agents/skills/pocketgpt-coding-best-practices/references/repo-conventions.md`
      — added Compose performance anti-patterns subsection.
- [x] `.claude/skills/maestro-android-cli/SKILL.md` — added build-variant lesson.
- [x] `.claude/skills/android-compose-ui-audit/SKILL.md` — extended audit
      checklist with Compose stability section.

## Long-term watchlist

These are not bugs today, but small drifts will erode the gains we just
captured. Watch for them in code review:

- New UI state types added without `@Immutable`. The audit catches `data class`
  but a subtle case is `class` (not `data class`) used as a state holder — the
  audit pattern would miss it. If a non-data class starts flowing into Compose,
  add it manually.
- New `OutlinedTextField` instances on hot input paths (e.g. a search field in
  the model library) using the `String` overload. The current audit only checks
  `ComposerInputRow`. Extend if a second high-frequency text field appears.
- New `derivedStateOf {}` without `remember(...)`. There is currently no audit
  for this; consider adding one if regressions reappear.
- Increasing `n_ctx` or large-prompt tokenization without re-running the perf
  baseline — model-load and tokenizer init can leak into the main thread under
  certain timing conditions.
- A `clean` build before each measurement — if cold ART hits during the perf
  run it skews the first 1-2 readings. We compensate by ignoring run 1 and
  taking 3-run medians; document this in the PR if asked.

## Reviewer-facing summary

The next PR that touches the Compose tree should run, in this order:

```bash
bash scripts/dev/test.sh fast                                          # static audits + units
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh --build      # 1st run (warmup)
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh              # 2nd run
ANDROID_SERIAL=<serial> bash scripts/dev/perf-baseline.sh              # 3rd run; this is the number that matters
```

If any run exceeds the thresholds, capture a 5-second perfetto trace, inspect
the worst frame, and address the dominant slice. Do not move on until medians
clear the bar — every loosening of the threshold is one less guardrail.
