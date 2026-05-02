# PocketGPT Performance Contract & Hot-Path Discipline — Implementation Plan

## What this plan is and is not

This is **not** a "wrap all I/O in a layer" framework. It is a *hot-path contract* enforced at three points: the type system (suspend/dispatcher), the build (`assemble`/`check` source audits), and on-device evidence (`perf-baseline.sh`).

The goal is to make the *next* PocketGPT performance regression mechanically impossible to merge, without paying for a heavyweight DI/abstraction tax.

## Diagnosis recap (after the May 2 fixes)

On-device measurements on the Galaxy S22 after the user's implementation:

| Metric | Before fixes | After fixes | Industry target | Verdict |
|---|---|---|---|---|
| Janky frames (>16.7ms) | 90.60% | 82.02% | <5% | ❌ still bad |
| Janky frames (>33ms) | 23.93% | 30.34% | <1% | ❌ still bad |
| 50th percentile frame | 22 ms | 17 ms | ≤8ms | 🟡 borderline |
| 95th percentile frame | 57 ms | 38 ms | ≤16ms | ❌ still bad |
| High input latency events | 124 | 81 | <5 | ❌ still bad |
| StrictMode app-origin violations | 44 | 0 | 0 | ✅ fixed |
| Choreographer >100-frame skips | 2 | 0 | 0 | ✅ fixed |

The cliff-edge stalls (1.35 s startup hitch, 221-frame skip) are gone. What remains is structural — *every keystroke* still touches paths that recompose more than they should. We are no longer on the "OS thinks we are unresponsive" cliff, but we are not yet at "premium chat app" smoothness.

## Root cause synthesis (across all the perf fixes the team has done)

Looking across every perf fix in this branch and the prior "separate from main thread" pass the user mentioned, every individual bug has a different shape (sync store, broad observation, eager construction, inefficient list copy) but they all share one structural property:

> **In PocketGPT, "where does this run?" and "what does it cost?" are decided by tribal knowledge, not by types or build rules.**

Concretely, the codebase has *three* implicit invariants that can be silently broken by a single innocuous edit:

1. **Thread invariant** — "this method must not run on Main." Today this is enforced by: `Dispatchers.IO` annotations *if the author remembers*, hand-written `withContext(ioDispatcher)` *if the author remembers*, and `StrictMode.penaltyLog()` *if a developer happens to read logcat*. Nothing prevents calling `gateway.listInstalledVersions(modelId)` from a Compose lambda.

2. **State-fanout invariant** — "this Composable observes only the slice it renders." Today `_uiState` is a god-object, and every screen that calls `viewModel.uiState.collectAsState()` recomposes on any change. We've added narrow flows but kept the broad `uiState` flow as a fallback that's still used (`ModalOrchestratorHost`, `ModelLibrarySheetHost`, `ChatComposerDock`'s `activeSessionFlow`). Nothing prevents a new screen from going broad again.

3. **Cache invariant** — "OS calls (`getSharedPreferences`, `getExternalFilesDir`, `lastModified`, `packageManager.getPackageInfo`) cost real time and must be amortized." Today there are at least four different cache idioms: `AtomicReference<Array<File?>>`, `ConcurrentHashMap<String, CachedRead>`, `@Volatile var lastLoadedCache`, time-bounded TTL. Each store reinvents this. None document invalidation contracts.

So **the root cause is "implicit-vs-enforced invariants"**, not "we lack a framework." The cure is *to take the three invariants we already rely on and lift them from convention to compile-time/build-time enforcement.*

## Decision: lightweight contract + enforcement, not a heavy abstraction

A heavy framework (centralized I/O dispatcher, all-suspend gateway, repository pattern, dispatcher injection container) would:

- Multiply the surface area of every store and gateway
- Force a refactor across every call site
- Add testing complexity (more fakes, more coroutine glue)
- Solve a problem we don't have: PocketGPT's I/O paths are individually small; the bug class comes from *ad hoc accumulation*, not from any one I/O being unsafe in isolation

Instead we adopt a **performance contract** with three artifacts:

- A short doc that's the source of truth for the three invariants.
- A handful of runtime + test-time guards (`assertNotMainThread`, dispatcher fixtures).
- A build task that fails the build when the contract is violated, and an on-device script that fails CI when device frame metrics regress.

Cost is roughly two engineer-days and ~400 lines of code, not a month-long migration.

## Architectural primitives we are introducing

- `MainThreadGuard.assertNotMainThread(operation: String)` — debug-only, throws in debug builds, no-ops in release. Used at the *bottom* of every store/gateway sync method that hits disk/SharedPreferences.
- `AppDispatchers` — single bag holding `main`, `mainImmediate`, `default`, `io`. Replaces the practice of importing `Dispatchers.IO` directly in ViewModels and coordinators. Makes test injection one-line.
- `PerformanceContractAuditTest` — JUnit test in `:apps:mobile-android:testDebugUnitTest` that fails the build when:
  - A file under `src/main/kotlin/com/pocketagent/android/ui/**` calls one of the known sync provisioning/runtime methods.
  - A file under `runtime/**` declares a `val prefs = ... .getSharedPreferences(...)` *or* `File(...).readText()` *or* `getExternalFilesDir(...)` outside an allowlist.
  - A `StateFlow<...>` field in a `ViewModel` named `*UiState` is observed via `.collectAsState()` from a file at the *root* of the `ui` package (i.e. shell-level Compose).
- `perf-baseline.sh` is upgraded to fail when janky-rate or p95 regresses past explicit thresholds (we ratchet, never relax).
- `docs/architecture/android-performance-contract.md` is the single canonical reference.

## What this plan deliberately avoids

- A central `IORepository` or `BackgroundExecutor` abstraction.
- Forcing every read through a `suspend` boundary. Some sync methods (e.g. `presetBackingStore.routingModeForPreset`) are pure in-memory lookups and should stay sync.
- A "platform team" or new module. Everything lives under `apps/mobile-android` and `tools/devctl`.

---

## Tasks

### Task 1: Codify the contract document

**Why:** Without a written reference, every reviewer re-derives the rules from scratch and they drift. Two pages is enough.

**Files:**

- New: `docs/architecture/android-performance-contract.md`
- Modify: `docs/architecture/README.md` (add link)
- Modify: `AGENTS.md` (link from the testing rules section so agents pick it up)

**- [ ] Step 1: Write the contract doc with these sections**

1. *Three invariants*: thread, state-fanout, cache. State each as one sentence and then the rule.
2. *Where each invariant is enforced*: type/lint/build/device, mapped to invariant.
3. *Allowlist rules*: when sync I/O is acceptable (warmup-phase only, behind `assertNotMainThread`, or pure in-memory).
4. *Hot-path checklist for new code* — the three things every reviewer asks: "what dispatcher", "what slice does this observe", "what's the cache key/lifetime".
5. *Escalation*: when to widen perf-baseline thresholds vs. when to push back.

**Verify:** `rg -n "performance-contract" docs/` shows references from `architecture/README.md` and `AGENTS.md`.

---

### Task 2: Introduce `AppDispatchers` and migrate ViewModels/coordinators to it

**Why:** Today `ChatViewModel`, `ModelProvisioningViewModel`, `AppRuntimeLifecycleCoordinator`, `ChatStartupFlow`, `ChatPersistenceQueue`, and several controllers each take an `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`. Each one is correct in isolation but the parameter has slightly different default values and naming, and tests have to inject 5 dispatchers. A single bag standardizes this.

**Files:**

- New: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/AppDispatchers.kt`
- Modify: `ChatViewModel.kt`, `ModelProvisioningViewModel.kt`, `ChatStartupFlow.kt`, `ChatPersistenceQueue.kt`, `AppRuntimeLifecycleCoordinator.kt`, factories
- Test: `ChatViewModelDispatcherContractTest.kt` (new), `ModelProvisioningViewModelDispatcherTest.kt` (new — the original plan called for this and it was missed)

**- [ ] Step 1: Create `AppDispatchers`**

```kotlin
package com.pocketagent.android.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        val DEFAULT = AppDispatchers()
    }
}
```

**- [ ] Step 2: Migrate `ChatViewModel` and `ModelProvisioningViewModel` constructors**

Replace the `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` parameter with `dispatchers: AppDispatchers = AppDispatchers.DEFAULT`. Update internal call sites: `viewModelScope.launch(dispatchers.io) { ... }`. The factories pass through `AppDispatchers.DEFAULT`.

**- [ ] Step 3: Migrate the same three coordinators**

`AppRuntimeLifecycleCoordinator`, `ChatStartupFlow`, `ChatPersistenceQueue`. Same pattern. Keep backward-compatible secondary constructors that delegate, so existing tests don't all need to change in this PR.

**- [ ] Step 4: Add the missing dispatcher contract test**

```kotlin
class ModelProvisioningViewModelDispatcherTest {
    @Test
    fun `aggregate state is collected on io dispatcher`() {
        // Use a verifier dispatcher that records the calling thread name
        // Construct VM with verifier as `io`, run init, push an aggregate emission,
        // assert thread name matches the verifier's pool, not Main.
    }
}
```

**Verify:**

```bash
./gradlew :apps:mobile-android:testDebugUnitTest --tests '*Dispatcher*' --tests '*DispatcherContract*'
rg -n 'Dispatchers\.IO' apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime
```
The `rg` should show very few results (only top-level definitions in `AppDispatchers.kt` itself plus `MainActivity.onCreate`).

---

### Task 3: Add `MainThreadGuard` and instrument the remaining sync hotspots

**Why:** Even with the warmup pass, future code can call `provisioningStore.snapshot()` or `gpuOffloadQualifier.evaluate()` from a Compose lambda. `StrictMode.penaltyLog` only logs after the fact. We want loud, mechanical failure during development.

**Files:**

- New: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/MainThreadGuard.kt`
- Modify: `AndroidRuntimeProvisioningStore.kt` (`snapshot()`, `lastLoadedModel()` after cache miss, `setActiveVersion()`, `removeVersion()`, `migrate*`)
- Modify: `AndroidGpuOffloadQualifier.kt` (`evaluate()`, `delegate` initializer)
- Modify: `AppRuntimeLifecycleCoordinator.kt` (`currentModelLifecycle()` and `reconcileLifecycleState()`)
- Modify: `PresetModelMappingStore`, `RuntimeTuningStore`, `DownloadPreferencesStore` (constructors only — guard in case of cold-path access)
- Test: `MainThreadGuardTest.kt` (new)

**- [ ] Step 1: Create the guard**

```kotlin
package com.pocketagent.android.runtime

import android.os.Looper
import com.pocketagent.android.BuildConfig

object MainThreadGuard {
    fun assertNotMainThread(operation: String) {
        if (!BuildConfig.DEBUG) return
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            throw IllegalStateException(
                "PocketGPT performance contract violated: $operation called on main thread. " +
                    "Wrap the call in withContext(dispatchers.io) or move it into warmUp().",
            )
        }
    }
}
```

**- [ ] Step 2: Instrument hotspots**

Add `MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.snapshot")` as the first line of `snapshot()`. Same for `lastLoadedModel` cache-miss branch, `setActiveVersion`, `removeVersion`, `evaluate()`, etc.

**- [ ] Step 3: Cover with a unit test**

```kotlin
class MainThreadGuardTest {
    @Test
    fun `throws on main thread in debug`() {
        // Use Robolectric or a Looper test fixture; assert IllegalStateException
    }

    @Test
    fun `snapshot guarded`() {
        // Reflectively invoke snapshot() on Main, expect ISE.
    }
}
```

**Verify:**

```bash
./gradlew :apps:mobile-android:testDebugUnitTest --tests 'MainThreadGuardTest'
adb logcat -c && adb shell am start -n com.pocketagent.android/.MainActivity
adb logcat -d | grep "performance contract violated" || echo "PASS: no contract violations on cold start"
```

---

### Task 4: Eliminate the remaining broad-state observation in shell Compose

**Why:** Audit results show `ChatComposerDock` collects `viewModel.activeSessionFlow` *only* to derive `thinkingEnabled` (one Boolean). Every change to active-session messages or settings causes the composer (which holds the `OutlinedTextField`) to recompose. This is exactly the bug class the original plan tried to kill.

**Files:**

- Modify: `ChatViewModel.kt` — add `currentThinkingEnabledFlow: StateFlow<Boolean>`
- Modify: `ChatApp.kt` — `ChatComposerDock` consumes the new boolean flow instead of the full active session
- Modify: `ChatUiState.kt` — remove the `activeSession` *getter* (keep the extension function); the getter computes O(N) per read
- Test: extend `ChatViewModelStateSplitTest.kt` with a "composer does not re-emit when assistant message updates" test

**- [ ] Step 1: Add the narrow flow**

```kotlin
val currentThinkingEnabledFlow: StateFlow<Boolean> = _uiState
    .map { state -> state.activeSession()?.completionSettings?.showThinking == true }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Lazily, false)
```

**- [ ] Step 2: Switch `ChatComposerDock` to it**

Replace the `viewModel.activeSessionFlow.collectAsState()` line with:
```kotlin
val thinkingEnabled by viewModel.currentThinkingEnabledFlow.collectAsState()
```
and remove the `activeSession?.completionSettings?.showThinking == true` derivation.

**- [ ] Step 3: Drop the `activeSession` getter from `ChatUiState`**

Keep the extension function `fun ChatUiState.activeSession(): ChatSessionUiModel?` (callers already use it). Remove the `val activeSession get() = ...` block. This forces every site to use the function and keeps the data class equals/hashCode cheap.

**- [ ] Step 4: Extend the regression test**

```kotlin
@Test
fun `composer does not re-emit when active session messages change`() = runTest(dispatcher) {
    val viewModel = testViewModel()
    advanceUntilIdle()
    // ... seed sessions ...
    val emissions = mutableListOf<ComposerUiState>()
    val job = launch { viewModel.composerFlow.toList(emissions) }
    advanceUntilIdle()
    emissions.clear()

    // Simulate streaming token mutation
    viewModel.updateStreamingMessage("s1", "m1", "Hello")
    advanceUntilIdle()
    assertEquals(emptyList(), emissions)
    job.cancel()
}
```

**Verify:** Manually open the composer, type a paragraph, then trigger a streaming response. Compose layout inspector should not show the composer recomposing per token.

---

### Task 5: Add the `PerformanceContractAuditTest` source-audit gate

**Why:** Static, list-driven build-time check. Catches the next "import `getSharedPreferences` into a constructor" or "collect `provisioningViewModel.uiState` from a root Composable" before it lands.

**Files:**

- New: `apps/mobile-android/src/test/kotlin/com/pocketagent/android/audit/PerformanceContractAuditTest.kt`
- Modify: `apps/mobile-android/build.gradle.kts` — wire the existing `verifyAndroidArchitecture` style scanner to also run for performance rules (or just lean on JUnit and `:apps:mobile-android:testDebugUnitTest`)

**- [ ] Step 1: Author the audit test**

The test reads source files, applies a small DSL of forbidden patterns + allowlists, and `assertTrue(violations.isEmpty()) { violations.joinToString() }`. Patterns to enforce:

| Pattern | Roots | Allowlist |
|---|---|---|
| `viewModel.uiState.collectAsState()` | `ui/` | `ChatApp.ModalOrchestratorHost`, `ModelLibrarySheetHost` (visibility-gated) |
| `provisioningViewModel.uiState.collectAsState()` | `ui/` | same |
| `getSharedPreferences(` | `ui/` | none |
| `\.readText()` / `\.writeText(` | `ui/` | none |
| `getExternalFilesDir(` | `ui/` | none |
| `runBlocking` | `app + ui` | tests only |
| `: ChatRuntimeService` | `ui/` (excluding `controllers/`) | runtime gateway access goes through the ViewModel |

This is not a deep AST analysis; it's a regex source scan. That's fine — it's the same pattern `verifyAndroidArchitecture` already uses successfully.

**- [ ] Step 2: Add to `check`**

This test runs as part of `:apps:mobile-android:testDebugUnitTest`, which is already part of `check`. No build-level changes needed.

**Verify:**

```bash
./gradlew :apps:mobile-android:testDebugUnitTest --tests 'PerformanceContractAuditTest'
```
Then deliberately break the rule (paste `getSharedPreferences` into a UI file) and confirm the test fails.

---

### Task 6: Ratchet `perf-baseline.sh` thresholds and add a streaming-jank scenario

**Why:** The current threshold (jank ≤ 25%, p50 ≤ 18 ms) was the user's improved measurement, not industry-standard. Locking that in cements mediocrity. After Tasks 1–5 land, re-measure, then write the *new* numbers as the threshold and ratchet from there.

We also have no on-device evidence that *streaming-time* jank is bounded — only typing-time. Streaming triggers the heaviest path (token append → flow emission → message bubble re-layout).

**Files:**

- Modify: `scripts/dev/perf-baseline.sh` — add a streaming sub-scenario, tighten thresholds
- Modify: `tests/maestro/scenario-typing-jank-smoke.yaml` — add a follow-up flow `scenario-streaming-jank-smoke.yaml`
- Modify: `docs/testing/runbooks.md` — document the ratcheting policy

**- [ ] Step 1: Add streaming scenario**

`tests/maestro/scenario-streaming-jank-smoke.yaml`: load a tiny model, send a fixed prompt that yields a known-long response, trigger `dumpsys gfxinfo reset` immediately before "send" and capture immediately after generation completes.

**- [ ] Step 2: Tighten thresholds**

After Tasks 1–5 land and we measure again, set thresholds at *the current measurement plus a small headroom*. For example, if measured p95 = 22 ms, set threshold to 28 ms. The script must reject any number worse than that. There is no "soft" mode.

**- [ ] Step 3: Document ratcheting**

In `docs/testing/runbooks.md`: "perf-baseline thresholds are monotonic. Loosening them requires written justification in the PR description and reviewer sign-off."

**Verify:** Run the script in a fresh build and confirm pass; intentionally regress (e.g., add a `Thread.sleep(50)` in `onComposerChanged`) and confirm the script fails with a clear message.

---

### Task 7: Pay off the residual cache and warmup debt

**Why:** The audit found three remaining items that the May 2 implementation missed or only partially addressed:

a) `AppRuntimeLifecycleCoordinator.currentModelLifecycle(context)` still calls `reconcileLifecycleState` synchronously — used by `appRuntimeProvisioningBindings`'s `currentModelLifecycle` binding. Anyone calling that path on Main hits disk-bound work.
b) `AndroidGpuOffloadQualifier`'s `appBuildSignature` runs `packageManager.getPackageInfo` at construction time. PMS calls are usually fast but not free; should be lazy.
c) The four cache idioms in the codebase (`AtomicReference`, `ConcurrentHashMap`, `@Volatile var ... Cache`, time-bounded TTL) should converge on one. Pick `AtomicReference<T?>` for process-lifetime caches and `ConcurrentHashMap<K, V>` for keyed caches.

**Files:**

- Modify: `AppRuntimeLifecycleCoordinator.kt` — `currentModelLifecycle` becomes `currentModelLifecycle(context, scope, dispatchers)`; deprecate the old signature with `@Deprecated`
- Modify: `AndroidGpuOffloadQualifier.kt` — make `appBuildSignature` a `lazy { … }`
- Modify: doc-comments only on the four caches to make the lifetime explicit

**- [ ] Step 1: Refactor `currentModelLifecycle`**

```kotlin
@Deprecated(
    "Use observeModelLifecycle(context, scope) and read the StateFlow value, " +
        "or call currentModelLifecycleAsync. This sync variant performs disk I/O.",
    ReplaceWith("currentModelLifecycleAsync(context, dispatchers.io)"),
)
fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot { /* unchanged */ }

suspend fun currentModelLifecycleAsync(
    context: Context,
    dispatchers: AppDispatchers = AppDispatchers.DEFAULT,
): RuntimeModelLifecycleSnapshot = withContext(dispatchers.io) {
    reconcileLifecycleState(graphProvider(context))
    lifecycleState.value
}
```

Audit callers; route any UI-adjacent call to the async variant.

**- [ ] Step 2: Lazy `appBuildSignature`**

```kotlin
private val appBuildSignature: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    runCatching { /* ... */ }.getOrDefault("${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}")
}
```

**- [ ] Step 3: Document cache contracts**

Add a short comment block above each cache field stating: cache lifetime (process/session/timed), invalidation triggers (none/explicit/timeout), thread-safety class.

**Verify:**

```bash
ANDROID_SERIAL=<S22> bash scripts/dev/perf-baseline.sh
adb logcat -d | grep -i "StrictMode policy violation" | grep -c "com.pocketagent" # expect 0
```

---

### Task 8: Ratchet StrictMode from `penaltyLog` to `penaltyDeath` in debug

**Why:** Today, debug builds still let main-thread disk reads slide as logcat warnings. After Tasks 1–7 land, debug builds should *crash* on app-origin violations so engineers see them at the precise moment they introduce them.

**Files:**

- Modify: `MainActivity.kt` — switch `penaltyLog()` to `penaltyDeath()` *only for app-origin violations* (Android framework violations are noisy and out of our control)

**- [ ] Step 1: Use `penaltyDeathOnFileUriExposure` semantics + a custom listener**

Use `permitDiskReads()` for known-safe init paths if needed, but otherwise:

```kotlin
StrictMode.setThreadPolicy(
    StrictMode.ThreadPolicy.Builder()
        .detectDiskReads()
        .detectDiskWrites()
        .detectNetwork()
        .penaltyLog()
        .penaltyDeath() // crash debug builds on any violation
        .build(),
)
```

If framework noise is too loud, switch to a custom `OnThreadViolationListener` (API 28+) that filters by stack frame contains `com.pocketagent`.

**Verify:** Cold-start the app on a debug build. It must not crash. Then deliberately add a `File("/sdcard").exists()` to a Compose lambda and confirm the app crashes with a clear stack.

---

### Task 9: One-page reviewer checklist in `docs/testing/runbooks.md`

**Why:** Reviewers should not have to re-discover the contract for every PR. The checklist is short enough to paste into a PR template.

**Files:**

- Modify: `docs/testing/runbooks.md`
- Optional: `.github/pull_request_template.md` if/when we adopt one

**- [ ] Step 1: Add a "Hot-path PR checklist" section**

```markdown
## Hot-path PR checklist (mandatory for changes touching ViewModels, runtime/, or Compose shell)

- [ ] No new `Dispatchers.IO` import in UI/ViewModel code; use `AppDispatchers`.
- [ ] No new `viewModel.uiState.collectAsState()` outside visibility-gated hosts.
- [ ] Any new sync method in `runtime/**` opens with `MainThreadGuard.assertNotMainThread(...)` or is documented as in-memory only.
- [ ] Any new `derivedStateOf {}` is wrapped in `remember{}`.
- [ ] If touching a hot path, run `ANDROID_SERIAL=<S22> bash scripts/dev/perf-baseline.sh` and paste the result in the PR description.
- [ ] Threshold loosened? Justification + reviewer sign-off in PR body.
```

**Verify:** the section is reachable from `docs/testing/README.md`.

---

## Tests and acceptance criteria

When all tasks land:

- `./gradlew :apps:mobile-android:testDebugUnitTest` passes including:
  - `PerformanceContractAuditTest`
  - `ChatViewModelStateSplitTest` (extended)
  - `ChatViewModelDispatcherContractTest` (new)
  - `ModelProvisioningViewModelDispatcherTest` (new)
  - `MainThreadGuardTest`
  - `ChatAppDerivedStateAuditTest` (existing)
  - `ModelLibraryActionsAsyncContractTest` (existing)
- `bash scripts/dev/test.sh fast` passes.
- `python3 tools/devctl/main.py lane android-instrumented` passes.
- `ANDROID_SERIAL=<S22> bash scripts/dev/perf-baseline.sh` passes the new tighter thresholds:
  - typing scenario: janky ≤ 15%, p95 ≤ 28 ms (current is 30%/38 ms — Tasks 4 + 7 must close this)
  - streaming scenario: janky ≤ 20%, p95 ≤ 35 ms
- Cold-start logcat on a debug build shows zero app-origin StrictMode violations.
- A debug-build cold start does not crash (StrictMode `penaltyDeath` proves clean).

## Rollout

This is one PR per task, in order. Tasks 1, 4, and 7 are the user-visible improvements; the rest are guardrails. If we have to cut scope, ship 1–4 and 7 first; the audit test and StrictMode upgrade can follow in a second PR.

## What this plan rejects from the junior's plan

- A "MainThreadPerformanceGuard" that only logs — too soft, will get ignored. We use `assertNotMainThread` that throws in debug.
- "Add @Deprecated warnings for sync variants" — agreed in spirit, but only when an async variant exists. Simple sync getters that don't hit disk should stay sync.
- "Use the current improved metrics as baseline (jank ≤ 25%, p50 ≤ 18 ms)" — that's accepting mediocrity. We ratchet down after each pass.
- "Don't centralize every file read behind a single abstraction" — agreed.
- The junior's omission: no streaming-jank measurement, no penaltyDeath, no narrow-flow regression for the leftover `activeSessionFlow` over-observation.

