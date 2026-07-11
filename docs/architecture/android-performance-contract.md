# Android Performance Contract

PocketGPT's Android UI must stay responsive while model loading, downloads, streaming,
and local storage work happen in the background. The recurring failure mode is not one
expensive operation; it is implicit ownership of thread, state, and cache cost. This
contract makes those rules explicit.

For the operation-by-operation execution plan that applies this contract across
startup, provisioning, model load/switch, streaming, model library, drawer search,
onboarding, voice, and tooling gates, see
`docs/architecture/performance/android-operational-performance-plan.md`.

## Invariants

1. **Thread ownership is explicit.** Disk I/O, network I/O, SharedPreferences first
   reads, native diagnostics, package-manager calls, and filesystem probes must not run
   on the main thread.
2. **State fanout is narrow.** Compose shells observe the smallest `StateFlow` slice
   they render. Full `uiState` collection is allowed only inside visibility-gated hosts.
3. **Hot-path cost is amortized.** Runtime and provisioning stores cache stable OS or
   filesystem results with a documented lifetime and invalidation rule.
4. **Compose stability is declared, not inferred.** Every UI state class is `@Immutable`
   or listed in `apps/mobile-android/compose-stability.conf`. Never put a custom property
   getter on an `@Immutable` class.
5. **Performance is measured on the `benchmark` variant only.** The `debug` build
   carries a ~30-50% Compose recompose tax (no AOT, debug source instrumentation,
   `MainThreadGuard` + `StrictMode` overhead) that swamps real signal.

## Enforcement

- `MainThreadGuard.assertNotMainThread("<operation>")` is called from runtime/provisioning
  sync APIs that can touch disk; debug builds fail fast on violations.
- ViewModels expose narrow flows for hot UI surfaces: composer text, runtime status,
  active session id, thinking toggle, modal surface, streaming text, model-loading
  summaries, completion settings.
- `apps/mobile-android/compose-stability.conf` declares every domain UI state class as
  stable, plus `kotlin.collections.{List,Set,Map,Collection}` so read-only collection
  parameters skip cleanly.
- `PerformanceContractAuditTest` (under `apps/mobile-android/src/test/.../audit/`)
  enforces static rules on every `bash scripts/dev/test.sh fast`, including:
  - No `getSharedPreferences`, `readText`, `writeText`, `getExternalFilesDir`, or
    `runBlocking` in `ui/`.
  - `ChatComposerDock` does not observe `activeSessionFlow`.
  - `PocketAgentApp` root does not collect full `uiState` / `provisioningViewModel.uiState`.
  - `ChatUiState` does not declare an `activeSession` property getter.
  - Every named composer/settings/search hot field binds its own local
    `TextFieldValue`; a declaration elsewhere in the file cannot mask a String overload.
  - Every `data class` in `ui/state/` is `@Immutable` or listed in `compose-stability.conf`.
  - `scripts/dev/perf-baseline.sh` checks `DEBUGGABLE` flag and builds `assembleBenchmark`.
- `scripts/dev/perf-baseline.sh` is the device evidence gate for UI/runtime refactors.
  It refuses to measure debuggable builds without `--allow-debuggable`.
- `scripts/dev/compose-report-hotpath.sh --build` forcibly regenerates benchmark-scoped
  Compose compiler reports, fails on empty/stale/incomplete metrics or missing expected
  hot composables, and highlights instability/non-skippable churn. Use `--strict` only
  after the current instability baseline is intentionally cleaned up.
- `scripts/dev/perf-interaction-gate.sh` requires explicit runtime/download/voice
  declarations and retains device, refresh-rate, thermal, battery, package-compilation,
  and frame-window evidence for each sample. Operators must verify those three
  app states before the group; the gate checks declaration consistency, not app
  telemetry. Acceptance rejects missing
  refresh/compilation provenance, fewer than 20 frames, or any nonzero thermal status.
  One atomic device/package lease covers build, install, and all three samples;
  each child validates the owning token, and end-state checks still fail closed
  against tools that do not honor the lease.
- `bash scripts/dev/test.sh core|merge` includes `:apps:mobile-android:assembleBenchmark`
  when Android SDK is configured, so the benchmark variant remains buildable even
  when no physical device is attached.
- The app ships a physical-device-generated, app-only Baseline Profile. The
  `apps/mobile-android-baselineprofile` generator covers cold launch, the session
  drawer, settings, and model library without model inference. `ProfileInstaller`
  enables profile installation for sideloaded builds, and
  `scripts/dev/baseline-profile.sh verify` proves both benchmark and release APK
  packaging instead of accepting transitive library profiles as app coverage.

## Build Variant Policy

| Variant | Purpose | Debuggable | AOT | Use |
|---|---|---|---|---|
| `debug` | Daily development, hot reload, breakpoints | yes | no | Local iteration only. **Never measure perf on this variant.** |
| `benchmark` | Performance measurement, baseline gates | no | yes | `scripts/dev/perf-baseline.sh --build`, perfetto traces, jank investigations |
| `release` | Production distribution | no | yes | App Store builds, manual smoke after final QA |

The `benchmark` variant is signed with the well-known Android debug keystore so it
installs on developer/CI devices without provisioning a custom signing key. It is
never published.

## Allowed Sync Work

Sync APIs are acceptable only when one of these is true:

- The operation is pure in-memory and documented as such.
- The operation is called only from app warmup or a coroutine already running on IO.
- The operation is a guarded runtime/provisioning sync API and cannot be reached from
  UI callbacks without failing debug builds.

When in doubt, make the method `suspend`, route it through `Dispatchers.IO`, and expose
a narrow UI flow instead of a direct getter.

## Reviewer Checklist (UI/runtime PRs)

- [ ] Does every new hot-path call state which dispatcher it runs on?
- [ ] Does every new Compose collector observe the smallest state slice it needs?
- [ ] Does every new UI state class carry `@Immutable`? If not, is it added to
      `compose-stability.conf`?
- [ ] Are there any new property getters on `@Immutable` data classes? (Forbidden.)
- [ ] Does `OutlinedTextField` for any high-frequency input use the `TextFieldValue`
      overload with local state?
- [ ] Does every new cache document whether it is process-lifetime, TTL-based, or
      explicitly invalidated?
- [ ] Does a hot-path change include at least one guard: unit flow test, source-audit
      test, StrictMode/main-thread guard, or `perf-baseline.sh --build` evidence on
      the benchmark variant?
- [ ] Did you re-generate the Android module Compose compiler reports after the change
      and verify no new `unstable` parameters?

## Current Thresholds

`scripts/dev/perf-baseline.sh` enforces (on the benchmark variant, Pixel/Galaxy class):

- `janky_frames <= 20%`
- `p50 <= 14 ms`
- `p90 <= 25 ms`
- `p99 <= 32 ms`

These are calibrated against measurements taken on 2026-05-02 after the typing-jank
RCA. Run the script three times and compare medians; the first run is always
warmup-skewed.

## Threshold Policy

Performance thresholds should ratchet down over time. Loosening a threshold requires a
written justification in the PR description and reviewer sign-off. Do not accept a
threshold simply because it matches the current app behavior.

## Lessons From the 2026-05-02 RCA

The investigation that produced this contract version went through three failed
ablations before identifying the dominant cost:

1. **Bypassing the god-object `_uiState` write on every keystroke** — no improvement.
2. **Switching `OutlinedTextField` to the `TextFieldValue` overload** — no improvement
   on the debug build (it later mattered on benchmark, but the dominant signal was
   masked).
3. **Holding composer state purely in compose-local `mutableStateOf`** — no improvement.
4. **Building the same code as `release` instead of `debug`** — *50% reduction in jank,
   p99 dropped from 44 ms to 30 ms. The harness was the dominant cost.*

Methodology lesson: when "everything" feels slow and several reasonable code-level
ablations produce no signal, suspect the harness (build variant, thermal state,
debug instrumentation, screen recording overhead). Keep the experiments cheap and
single-variable. A "no-change" result is correct evidence that the suspect is not
the dominant cost — move on, don't pile on.
