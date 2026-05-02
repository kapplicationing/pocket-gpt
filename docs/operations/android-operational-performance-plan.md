# Android Operational Performance Plan

This plan generalizes the 2026-05-02 typing-jank RCA into a broader execution
plan for the app. It is intentionally implementation-plan level: it names the
operations, files, risks, and reason for change, but does not prescribe exact code
patches.

## What We Learned

The typing-jank issue was not a single bug. It was a class of failures:

1. **The harness can be the dominant bottleneck.** Measuring Compose performance on
  `debug` inflated recompose and frame timings by roughly 30-50%. Any performance
   investigation must start on the `benchmark` variant, run multiple samples, and
   compare medians.
2. **Thread ownership must be explicit at every boundary.** Disk, SharedPreferences,
  package-manager work, filesystem scans, native diagnostics, model metadata reads,
   and admission/eligibility checks are safe only when the caller contract guarantees
   a non-main dispatcher.
3. **State fanout is a product performance contract, not a style preference.** A hot
  UI surface must observe the narrowest state slice it needs. God-object flows are
   allowed only behind visibility gates or cold paths.
4. **Compose stability must be declared and verified.** `@Immutable`,
  `compose-stability.conf`, and compose compiler reports are required guardrails.
   Custom property getters on `@Immutable` classes hide work and can break skipping.
5. **High-frequency UI needs local interaction state.** Composer typing proved that
  text fields, streaming lists, search fields, markdown rendering, and auto-scroll
   can become frame-budget problems even when the business logic is correct.
6. **No-change ablations are useful evidence.** If reasonable code-level suspects
  produce no measurable improvement, keep moving and inspect the harness, thermal
   state, build variant, instrumentation overhead, or another larger cost.
7. **Regression prevention must live in docs, skills, source audits, and commands.**
  Human memory is not enough; every lesson needs at least one cheap guard.

## Unified Performance Model

Use this model for every Android operation:


| Dimension         | Question                                                                                    | Good answer                                                    |
| ----------------- | ------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| Measurement       | Is this being measured on `benchmark`, with 3+ runs?                                        | Yes; debug-only evidence is not used for perf decisions.       |
| Thread ownership  | Could this operation touch disk, prefs, package manager, native diagnostics, or filesystem? | It is `suspend`/IO-only or guarded by `MainThreadGuard`.       |
| State fanout      | Does a UI surface observe only what it renders?                                             | Hot paths use narrow flows and stable DTOs.                    |
| Cache lifetime    | Is cached state process-lifetime, TTL, mutation-invalidated, or explicit-refresh?           | Lifetime is documented and invalidated by owning mutations.    |
| Compose stability | Are UI state types stable and skip-friendly?                                                | `@Immutable`, no hidden getters, compose reports reviewed.     |
| Device evidence   | Is there a reproducible command proving the risk?                                           | Static audit for cheap rules; benchmark perf for frame budget. |


## Operation Audit And Execution Plan

### Phase 1: Make Hidden Synchronous Work Explicit

#### 1. Provisioning storage and installed-version queries

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/AndroidRuntimeProvisioningStore.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppRuntimeDependencies.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/ProvisioningGateway.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelProvisioningViewModel.kt`

What we learned:

- The prior lag class came from hidden main-thread cost and broad UI invalidation.
Provisioning has several sync-looking operations that can read preferences,
list installed model files, evaluate model admission, or compute storage summaries.

Why change is needed:

- `listInstalledVersions`, `storageSummary`, active-version writes, and last-loaded
model preference updates can become the next `activeSession`-style hidden cost:
cheap at first, then janky when catalog size, model count, or download state grows.
- Admission and eligibility checks can pull GPU diagnostics or provisioning snapshots.
If any UI-triggered path calls them synchronously on main, the app regresses into
the same class of problem.

Execution plan:

1. Classify every provisioning API as one of: pure in-memory, IO-only sync with guard,
  or suspend/async UI-facing API.
2. Add a documented caller contract for installed-version listing, storage summaries,
  active-version mutation, last-loaded model mutation, and admission checks.
3. Treat `storageSummary` as a heavy read unless proven otherwise; tie cache invalidation
  to import/remove/download mutations instead of relying only on TTL.
4. Split UI-facing APIs from store-facing sync APIs so Compose callbacks and ViewModel
  intents never need to know whether a store method is cheap.
5. Add static audit coverage for new UI calls into known provisioning sync methods.

Acceptance criteria:

- Every public provisioning method that can touch prefs/disk has a visible non-main
contract or is suspend/IO-only.
- `PerformanceContractAuditTest` or a sibling audit fails if UI files call known
blocking provisioning methods directly.
- Model library, onboarding download, and model-management flows still pass on the
canonical Maestro lanes.

#### 2. Sidecar metadata and model metadata parsing

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/StoredModelSidecarMetadata.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ModelRuntimeMetadataSidecar.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/`

What we learned:

- File reads that are "small" today become frame problems when they happen in the
wrong place. A JSON sidecar or metadata parse is still disk and CPU work.

Why change is needed:

- Sidecar reads/writes and metadata parsing can be triggered by model library,
load/offload, eligibility, or import paths. Without a uniform IO contract they can
reintroduce main-thread file reads.
- The Android store has a cache, but its lifetime and invalidation need to be part
of the operation contract, not incidental implementation detail.

Execution plan:

1. Document sidecar metadata as IO-only across Android and app-runtime callers.
2. Align Android and KMP cache semantics: file timestamp/length invalidation where
  possible, explicit invalidation after writes/removes, process-lifetime stated.
3. Add guards or audits around Android-side sidecar read/write entry points.
4. Make metadata parsing part of model import/load profiling, not an invisible helper.

Acceptance criteria:

- No sidecar read/write can be reached from UI without an IO boundary.
- Cache semantics are described in the performance contract or model-management docs.
- Model import and model load profiling include metadata read time when diagnosing
slow paths.

#### 3. GPU qualification and model eligibility

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/GpuOffloadQualification.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/GpuProbeService.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/ModelCatalogEligibility.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/ModelAdmissionPolicy.kt`

What we learned:

- "Just checking eligibility" can hide native diagnostics, provisioning snapshots,
package/build signatures, filesystem state, and GPU probe cache reads.

Why change is needed:

- `currentSignals()` and admission evaluation are conceptually read-only, but read-only
is not the same as cheap. If these are called from UI or during composition-adjacent
state mapping, they can reproduce the hidden sync-cost problem.
- GPU probe request resolution should avoid full provisioning snapshots if a narrower
model-path query is enough.

Execution plan:

1. Declare eligibility and admission as IO-only unless explicitly documented as
  pure in-memory.
2. Create a narrower conceptual boundary for "GPU probe status" and "model admission
  readiness" so UI surfaces do not need full provisioning snapshots.
3. Memoize short-lived eligibility signals where safe, with mutation invalidation
  when GPU prefs, installed models, or active version changes.
4. Add tracing around eligibility/admission during model sheet and model switch flows.

Acceptance criteria:

- UI model library and onboarding surfaces consume eligibility as a narrow flow or
prepared UI state, not by invoking sync checks.
- A perf investigation can answer how much time was spent in eligibility/admission.
- GPU probe state changes do not recompute unrelated provisioning UI state.

### Phase 2: Reduce State Fanout In UI Operations

#### 4. Model library and provisioning sheet

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelProvisioningViewModel.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelLibrarySheetHost.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelSheet.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelLibraryActions.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt`

What we learned:

- Full-state collection is acceptable only behind visibility gates, but a visible
sheet can still recompose heavily if download progress, catalog data, eligibility,
import state, and lifecycle are all bundled together.

Why change is needed:

- Download ticks can churn the entire model sheet even when only a progress row
needs updating.
- Opening the model library during active download/model load is a realistic user
path and likely to combine the same class of problems: broad state fanout plus
hidden provisioning reads.

Execution plan:

1. Define separate UI contracts for catalog snapshot, download progress, storage
  summary, eligibility/admission, and transient action state.
2. Keep the existing visibility gate, but avoid passing large aggregate state deeper
  than the first sheet host.
3. Add a benchmark scenario for opening model library while a download is active.
4. Add a reviewer checklist item: model sheet changes must state which slice changes
  at download tick frequency.

Acceptance criteria:

- Download progress updates do not require recomputing catalog/admission state.
- Model sheet changes have either static audit coverage or benchmark evidence.
- Model-management Maestro flows remain functional evidence only; frame evidence
comes from benchmark baseline or a new benchmark-specific script.

#### 5. Session drawer and session search

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/SessionDrawer.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/ChatUiState.kt`

What we learned:

- The composer text-field issue generalizes to any high-frequency text input. Search
fields are lower risk than the composer, but they combine text input with filtering,
sorting, grouping, and row gestures.

Why change is needed:

- Session search can become a jank source as session count grows. Sorting/grouping
on every keystroke is acceptable only if measured and bounded.
- `OutlinedTextField` `String` overload is now a known anti-pattern for hot input
paths; session search should either be explicitly exempted by risk or follow the
same `TextFieldValue` pattern.

Execution plan:

1. Decide whether session search is a high-frequency input path under the performance
  contract. If yes, bring it under the same `TextFieldValue` guidance.
2. Define expected session-count scale for local UX. If the target is high, plan for
  indexed/grouped derivation or paging.
3. Add a many-sessions search stress path to benchmark/manual perf runbooks.
4. Expand static audits only after a second high-frequency text field exists; avoid
  overfitting the audit to one implementation too early.

Acceptance criteria:

- Search typing with many sessions has benchmark evidence or an explicit documented
risk classification.
- Session grouping cost is bounded by a stated UX scale target.
- Drawer behavior remains covered by Maestro smoke/selector audits.

#### 6. Message list, streaming, markdown, and auto-scroll

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatScreen.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatMarkdownContent.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/MessageBubbleComponents.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/StreamStateReducer.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModelSendWorkflow.kt`

What we learned:

- A hot path is not always typing. Streaming token updates, markdown parsing, message
list layout, and auto-scroll can consume frame budget in the same way.

Why change is needed:

- Streaming can trigger frequent `LazyColumn` invalidation, markdown splitting/parsing,
animated scroll, accessibility announcements, and bubble measurement.
- Long markdown replies are likely to stress CPU and layout more than plain text.
This is the next most likely UI jank surface after composer input.

Execution plan:

1. Define a streaming performance budget separate from typing: long plain-text stream,
  long markdown stream, and near-bottom auto-scroll.
2. Review high-frequency `LaunchedEffect` keys and auto-scroll behavior; classify
  which effects may run per token vs per message.
3. Decide whether markdown should use an incremental or idle/settled rendering policy
  for active streams.
4. Add benchmark evidence for long-message streaming on the benchmark variant.
5. Add a static audit if high-frequency string keys or markdown parsing regressions
  recur.

Acceptance criteria:

- Streaming a long response does not exceed the same benchmark thresholds for visible
chat interaction.
- Markdown parsing/rendering cost is measurable in perfetto when diagnosing jank.
- Auto-scroll is not triggered more frequently than the intended UX requires.

#### 7. Onboarding and first-run download UX

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/OnboardingScreen.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModalOrchestrator.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/DownloadTransitionHandler.kt`
- `tests/maestro/scenario-onboarding.yaml`
- `tests/maestro/scenario-first-run-download-chat.yaml`

What we learned:

- Feedback loops between UI state and ViewModel state can create extra frames even
when every individual operation is cheap.

Why change is needed:

- Pager state can echo through ViewModel and back to `animateScrollToPage`.
- Download transition handling refreshes provisioning snapshots when downloads change;
if download emissions are noisy, this can create UI-triggered provisioning churn.

Execution plan:

1. Classify onboarding page sync and download transition refresh as lifecycle effects,
  not frame-by-frame effects.
2. Audit download transition triggers for noisy emissions and ensure refresh work is
  deduplicated or debounced at the operation boundary.
3. Add benchmark or perfetto guidance for first-run onboarding with active download.
4. Keep functional first-run flows in Maestro, but do not treat them as frame-budget
  evidence.

Acceptance criteria:

- First-run flow has separate functional evidence and performance evidence.
- Download-completion refresh happens once per meaningful transition, not every
download tick.
- Onboarding pager updates cannot oscillate between UI and ViewModel.

### Phase 3: Standardize Runtime Operation Boundaries

#### 8. App startup and runtime warmup

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/MainActivity.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/AppForegroundRuntimeServices.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppRuntimeGraphManager.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeWarmupSupport.kt`

What we learned:

- Lazy initialization is safe only if first touch is deliberately placed on IO.
Otherwise, cold-start or first interaction can pay dependency construction cost
on the UI thread.

Why change is needed:

- Warmup spans Android services, runtime graph, native bridge, provisioning, GPU
qualification, and model cache state. It needs a single owner and clear ordering.
- A synchronous warmup helper is easy to call from the wrong thread later.

Execution plan:

1. Document the one legal Android warmup entry point and dispatcher.
2. Classify warmup sub-operations as dependency construction, cache warming, native
  bridge warmup, or UI readiness.
3. Add startup tracing markers for each phase so future launch regressions are not
  diagnosed from symptoms.
4. Add a static or unit guard preventing warmup from being called on main.

Acceptance criteria:

- A new engineer can identify the only legal warmup path from docs.
- Perfetto/logcat can show where startup time is spent.
- Warmup cannot be accidentally moved back to main without a failing test/guard.

#### 9. Model load, offload, switch, and keepalive

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppRuntimeLifecycleCoordinator.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeModelLifecycleState.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeResidencyManager.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ModelLifecycleCoordinator.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/LlamaRuntimeExecutionGate.kt`

What we learned:

- Runtime lifecycle operations can be correctly off-main but still produce UI jank
if they publish too many state updates or contend with generation/probe gates.

Why change is needed:

- Model loading is one of the app's most expensive user-visible operations. It can
combine native work, lifecycle state updates, provisioning reads, GPU eligibility,
and UI status updates.
- Keepalive and offload policies influence both performance and memory pressure.

Execution plan:

1. Define the operation sequence for load, switch, offload, and keepalive touch:
  validation, admission, native load/offload, lifecycle publish, UI notification.
2. Add trace/log markers around gate wait time, native load time, lifecycle publish
  count, and UI readiness.
3. Ensure lifecycle UI updates are narrow and not emitted more frequently than the
  user can perceive.
4. Add benchmark/perfetto runbook for switching models while composer remains visible.

Acceptance criteria:

- A slow model switch can be attributed to admission, file I/O, native load, gate wait,
or UI publish fanout.
- Model load/offload entry points are non-main by contract.
- UI status text during load stays responsive under benchmark measurement.

#### 10. Chat send, inference, and token streaming

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModelSendWorkflow.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/AndroidChatConversationService.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/SendMessageUseCase.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/InferenceExecutor.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RemoteLlamaCppRuntimeBridge.kt`

What we learned:

- Blocking execute paths are acceptable only behind a dispatcher boundary. Token
callbacks and UI updates need their own budget.

Why change is needed:

- Prompt building, memory preparation, tool schema construction, tokenization,
native streaming, and stop-sequence checks can all grow with conversation size.
- As context and tools expand, the time before first token can regress without being
visible in typing-only baselines.

Execution plan:

1. Define send/generation as a multi-stage operation with separate timing markers:
  UI intent, session mutation, prompt planning, native prefill, first token, streaming,
   terminal persistence.
2. Require first-token and terminal metrics for performance investigations.
3. Add a long-context / tool-schema benchmark scenario separate from composer typing.
4. Review stop-sequence and markdown rendering costs as output length increases.

Acceptance criteria:

- Slow generation can be separated into prefill, decode, UI streaming, or persistence.
- Send path cannot block main by construction or static audit.
- Long-context regressions have a stable reproducible runbook.

#### 11. Voice catalog and voice runtime

Relevant files:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/voice/OffasVoiceStack.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/voice/VoiceActivation.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/AndroidLocalToolRuntime.kt`

What we learned:

- File existence checks and native initialization are the same class of hidden cost
as model metadata and provisioning. "Voice status" must not be treated as free.

Why change is needed:

- Voice model catalogs check bundled/downloaded files. Native recognizer or keyword
spotter construction can become expensive when triggered by UI state or activation.

Execution plan:

1. Treat voice catalog status as IO-backed state with a documented TTL/invalidation.
2. Publish voice readiness through a narrow flow prepared off main.
3. Add tracing around native voice component initialization.
4. Add a functional + performance runbook for enabling voice while chat remains usable.

Acceptance criteria:

- Voice status checks cannot run on main.
- Voice readiness changes do not fan out through unrelated chat UI state.
- Voice activation has clear evidence for startup/init cost.

### Phase 4: Strengthen Gates And Tooling

#### 12. Benchmark build correctness

Relevant files:

- `apps/mobile-android/build.gradle.kts`
- `scripts/dev/perf-baseline.sh`
- `config/devctl/lanes.yaml`
- `.github/workflows/ci.yml`
- `docs/testing/runbooks.md`

What we learned:

- We fixed the script, but CI still mainly proves debug builds and functional tests.
A broken benchmark build could slip through until someone tries to measure.

Why change is needed:

- The benchmark variant is now part of the performance contract. It deserves at
least a low-cost build check independent of device availability.

Execution plan:

1. Add an `assembleBenchmark` check to a local lane or CI job when Android SDK is
  available.
2. Keep device frame thresholds optional/offline unless reference hardware exists.
3. Make devctl suggestions mention `perf-baseline.sh --build` when UI hot-path files
  change.

Acceptance criteria:

- Benchmark APK compile/signing regressions fail before manual performance work.
- Devctl recommendations surface benchmark evidence for hot UI/runtime paths.

#### 13. Compose report governance

Relevant files:

- `apps/mobile-android/build.gradle.kts`
- `apps/mobile-android/compose-stability.conf`
- `apps/mobile-android/src/test/kotlin/com/pocketagent/android/audit/PerformanceContractAuditTest.kt`
- `docs/testing/runbooks.md`

What we learned:

- Stability regressions accumulate silently unless someone reads compiler reports.
The audit catches UI state data classes, but not every unstable parameter.

Why change is needed:

- New composable parameters, non-data state holders, ViewModel references, collections
outside the current config, and third-party types can introduce unstable hot paths
without failing existing tests.

Execution plan:

1. Add a documented compose-report review step for any PR touching hot composables.
2. Consider a script that regenerates reports and highlights new unstable hot-path
  parameters against a committed or generated baseline.
3. Extend audits only where patterns are stable enough to avoid false positives.

Acceptance criteria:

- Reviewers have a repeatable command and interpretation guide for Compose reports.
- Hot composable instability is visible before device jank appears.

#### 14. Device performance scenarios

Relevant files:

- `scripts/dev/perf-baseline.sh`
- `tests/maestro/scenario-typing-jank-smoke.yaml`
- `tests/maestro/`
- `docs/testing/runbooks.md`
- `tools/devctl/`

What we learned:

- Functional Maestro flows are valuable but not frame-budget evidence. We need a small
set of benchmark-variant scenarios tied to actual operations.

Why change is needed:

- The next performance issue may not be composer typing. It may be model sheet during
download, streaming markdown, drawer search, onboarding download, model switch, or
voice activation.

Execution plan:

1. Keep `perf-baseline.sh` as the fast typing baseline.
2. Add or document follow-up benchmark scenarios one at a time, starting with:
  streaming markdown, model library during download, and model switching.
3. Store evidence consistently under `docs/operations/evidence/` or build artifacts.
4. Keep thresholds ratcheting; do not loosen without written rationale.

Acceptance criteria:

- Every high-risk UI operation has either functional evidence, static audit coverage,
or benchmark evidence. Frame-sensitive paths have benchmark evidence.
- Performance evidence is not confused with debug Maestro pass/fail.

## Recommended Execution Order

1. **Guard hidden sync work first.** Provisioning storage, sidecar metadata,
  eligibility/admission, storage summaries, and voice catalog probes are the closest
   analogs to prior main-thread I/O issues.
2. **Split high-churn state second.** Model library/download state and streaming UI
  can create broad recomposition even when thread ownership is correct.
3. **Add operation metrics third.** Startup, model switch, send/generation, and
  voice activation need stage-level timing to avoid future guesswork.
4. **Expand gates last.** Once operation contracts are stable, add benchmark build
  checks, compose-report diff tooling, and focused benchmark scenarios.

## Proposed Workstreams

### Workstream A: Thread Ownership And Hidden Sync Cost

Scope:

- Provisioning store APIs
- Storage summaries
- Sidecar metadata
- GPU eligibility/admission
- Voice catalog status
- Runtime warmup entry points

Why first:

- These are the most likely to recreate "everything is slow" by pinning or blocking
the main thread.

Evidence required:

- `bash scripts/dev/test.sh fast`
- Main-thread guard tests or source audits
- Targeted scoped device repro only if behavior changes

### Workstream B: UI State Fanout And Compose Stability

Scope:

- Model library/provisioning sheet
- Session drawer search/grouping
- Message list and streaming markdown
- Onboarding pager/download transitions
- Modal props contracts

Why second:

- Once hidden sync work is controlled, broad invalidation becomes the next dominant
class of jank.

Evidence required:

- Compose reports after each surface change
- Existing performance audits
- Benchmark baseline for risky hot paths

### Workstream C: Operation-Level Observability

Scope:

- Startup/warmup
- Model load/offload/switch
- Chat send/generation/first-token
- GPU probe/admission
- Voice init

Why third:

- Future RCA speed depends on knowing which stage is slow without adding diagnostic
logs from scratch every time.

Evidence required:

- Log/trace markers by operation stage
- Perfetto trace interpretation guide updates
- Runbook examples for each operation

### Workstream D: Test And Governance Upgrade

Scope:

- Benchmark build CI check
- Devctl recommended lane hints
- Compose report script or diff helper
- Benchmark scenario expansion
- Selector/screenshot lint gating where appropriate

Why fourth:

- These gates should codify the settled contracts, not churn while the contracts are
still being shaped.

Evidence required:

- Fast lane remains green
- CI does not require physical devices for compile-only benchmark checks
- Device perf remains manually runnable and documented

## Non-Goals For The Next Implementation Pass

- Do not centralize every file read behind one giant abstraction.
- Do not turn all synchronous APIs into suspend APIs if they are truly pure in-memory.
- Do not make Maestro functional flows responsible for frame-budget assertions.
- Do not add noisy static audits before a pattern has repeated at least twice.
- Do not loosen perf thresholds to match current behavior; ratchet only downward or
document a temporary exception.

## Definition Of Done For This Plan

The plan is complete when:

- Every operation above has an explicit thread-owner contract.
- Every hot UI surface has a documented state budget and narrow-flow boundary.
- Every cache used by UI/runtime operations declares lifetime and invalidation.
- Benchmark variant builds in a repeatable lane.
- Typing, streaming, model library/download, and model switch have clear benchmark
evidence paths.
- Skills and docs point future agents/developers to this plan before they debug by
guesswork.