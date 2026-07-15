# Implemented UX Behavior Reference

Last updated: 2026-07-12
Owner: Product + Android

## Purpose

Capture implemented user-facing behavior that is easy to miss when reading only ticket summaries.

## Tool UX (Current)

1. `Tools` opens a prompt-shortcut dialog.
2. Selecting a tool item pre-fills composer text and closes the dialog.
3. Tool shortcuts do not directly dispatch `runTool` from the dialog.
4. No legacy tool-intent parser runs in send flow.
5. Send flow still parses model-emitted tool calls in the runtime path.
6. Direct typed tool execution path (`runTool` + typed tool results) exists in ViewModel/controller code and test contracts, but is not currently bound to a primary user action or treated as a launch claim.

## Composer Voice (Production Opt-In)

1. The composer exposes foreground dictation:
   - tapping the microphone requests permission when needed
   - local English ASR publishes an in-composer partial transcript
   - tap-to-stop drains the recognizer before committing final words
   - final text is appended to the editable draft and never auto-sent
2. Send is disabled while recognition is checking, listening, or finalizing.
3. Dictation is bound to the chat where it started; a result is discarded with visible guidance if the active chat changes.
4. Composer dictation and always-on listening cannot own the microphone together.
5. Dictation stops without committing partial text when the app moves to the background.
6. Complete assistant messages expose read-aloud and stop controls.
7. Read-aloud uses an installed English Android TTS voice only when Android marks that voice as not requiring a network connection.
8. Playback stops when the active chat changes or the app moves to the background, and cannot start during dictation.
9. Composer dictation uses the verified local voice pack installed by the in-app Hands-free Offas setup; users do not copy model files into app storage.

## Voice Activation (Production Opt-In)

1. `Advanced` settings include a voice activation section with:
   - enable toggle
   - blocker/advisory readiness copy
   - listener-status line
   - setup/help CTAs for assistant role, battery settings, and app settings
2. Voice activation is available to users in production builds. It is not gated by a debug flag, device allowlist, or controlled-cohort switch.
3. The first enable is one guided transaction: request visible notifications, request microphone access, select PocketAgent as Android's assistant, download and verify about 60 MB of pinned local voice files when missing, then enable listening automatically if all prerequisites still pass.
4. Setup runs as durable constrained work with visible progress. It survives leaving the settings surface, and turning the switch off cancels queued or active setup.
5. Always-on listening requires:
   - notification access so listening state and Stop remain visible
   - microphone access
   - PocketAgent selected as Android's assistant
   - a verified local voice pack
6. Battery-optimization guidance improves background reliability but does not hide the feature or act as a device allowlist.
7. Voice enable feedback is immediate and supportable:
   - missing prerequisites open the relevant Android setup surface or show concrete recovery copy
   - setup progress remains visible
   - listener start failures surface the stored inline error message and immediate feedback
8. Current voice device-action tool allowlist is intentionally narrow:
   - `alarm_set`
   - `timer_set`
   - `app_open`
   - `volume_set`
   - `flashlight_toggle`
9. Phone mutations use typed schemas, deterministic parsing, source-aware preview and confirmation, and lock-screen visibility policy; model-originated actions always require confirmation.
10. A normal hands-free question runs locally in a temporary ephemeral session and is not added to normal chat history.
11. The frozen public listing does not make cross-device battery or wake-reliability promises until retained Samsung, Pixel, aggressive-background-OEM, and 24-hour evidence exists. That qualification boundary controls support claims, not whether compatible users can turn the feature on.

## Privacy Section Behavior

1. `Advanced` settings include a collapsible Privacy section with the implemented policy summary.
2. Section copy currently states:
   - chats/memory are local,
   - offline-only policy gates runtime network actions,
   - diagnostics export redacts sensitive keys.
3. No controls currently exist for per-tool toggles, retention window selection, or local reset/delete actions.

## Empty-State Prompt Cards

1. Empty chat timeline shows suggested starter prompts for:
   - quick answer
   - image help
   - local search
   - reminder creation
2. Tapping a card pre-fills the composer; user still confirms by tapping `Send`.

## Runtime Status and Stream Phase Semantics

1. Runtime status values:
   - `Not ready`: model artifacts missing/invalid or startup checks failing
   - `Loading`: active runtime work in progress
   - `Ready`: runtime path healthy for current request flow
   - `Error`: runtime/startup failure that requires retry or recovery action
2. Backend identity is shown as `Backend: <value>` (`NATIVE_JNI`, `REMOTE_ANDROID_SERVICE`, `ADB_FALLBACK`, `UNAVAILABLE`) for support triage.
3. Android runtime mode contract:
   - `POCKETGPT_ANDROID_RUNTIME_MODE=remote` uses the remote Android runtime service bridge.
   - `POCKETGPT_ANDROID_RUNTIME_MODE=in_process` uses in-process JNI bridge.
   - Default is `in_process` when `POCKETGPT_ANDROID_RUNTIME_MODE` is unset.
4. Stream event phase labels map to UI detail copy:
   - `CHAT_START` -> `Preparing request...`
   - `MODEL_LOAD` -> `Loading model...`
   - `PROMPT_PROCESSING` -> `Prefill...`
   - `TOKEN_STREAM` -> `Generating...`
   - `CHAT_END` -> `Finalizing...`
   - `ERROR` -> `Runtime error`
5. Runtime error banner includes deterministic code + user message.
6. Error banner CTA hierarchy is fixed:
   - primary: `Fix model library`
   - secondary: `Refresh runtime checks`
   - tertiary: `Show technical details`

## Interaction Transcript Semantics

1. Send path builds `StreamChatRequestV2` from projected timeline transcript.
2. UI assigns request ids to streamed assistant placeholders.
3. `previousResponseId` is inferred from latest assistant request id and attached to the stream request.
4. Local runtime behavior remains transcript-first; `previousResponseId` is continuity metadata.

## Runtime Performance Profiles and GPU Toggle

1. Advanced controls expose exactly three runtime performance profiles:
   - `BATTERY`
   - `BALANCED`
   - `FAST`
2. Profile selection persists with session state restore and is reapplied on send actions.
3. GPU acceleration toggle is visible in advanced controls:
   - enabled only when runtime/backend reports support
   - support signal is queried from native runtime capability (`llama_supports_gpu_offload`) after backend init
   - disabled state renders explicit unavailable copy
4. Profile + GPU settings are applied through the app runtime contract and not by direct UI-only overrides.

## First-Session Contract

1. First-session stage machine is still tracked for telemetry/readiness:
   - `Onboarding`
   - `GetReady`
   - `ReadyToChat`
   - `FirstAnswerDone`
   - `FollowUpDone`
   - `AdvancedUnlocked`
2. `Get ready` is the primary setup workflow and defaults to the `0.6B` download path.
3. In the blocked composer, the user-visible primary button label is `Setup`; that button enters the `Get ready` workflow.
4. The first-session UX is still simple-first even though `Tools` and `Advanced` entry points may remain visible; the default happy path is `Get ready` first, then chat.
5. `Open model library` remains the secondary import/download/recovery path when the simple-first default is not enough.
6. `AdvancedUnlocked` is a telemetry/evidence milestone for first-session progression, not a hard visibility gate for every advanced entry point.
7. Follow-up completion emits first-session telemetry and cue state.
8. Stage/unlock flags are persisted across app restarts.
9. Authoritative local onboarding proof now exists in the `android-instrumented` lane; current launch hold is about current-window evidence closure, not about the first-session contract being absent from the codebase.
10. The final onboarding page now owns the simple-first setup transaction instead of opening Model Library after enqueue.
11. Its user-visible phases are download, check, finish, start, and send-ready; only byte transfer displays determinate progress.
12. The normal completion action appears only after the target model is loaded and the chat send-readiness gate is ready; general smoke tests retain an explicit `Set up later` bypass.

## Runtime Telemetry Labels in UI

1. Advanced controls runtime details include:
   - active model id
   - last first-token latency
   - last total latency
   - last prefill latency
   - last decode latency
   - last decode rate (tokens/sec)
   - last peak RSS (MB)
2. These labels are support-facing transparency signals and should be captured in QA evidence when triaging performance regressions.

## Model Residency Defaults

1. Runtime keeps model loaded while app is foreground by default.
2. Idle unload TTL defaults to 15 minutes (`DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS`).
3. Warmup-on-startup defaults to enabled unless overridden by runtime/test lane controls.

## Keep-Alive Preference Surface

1. Advanced controls expose exactly six keep-alive preferences:
   - `AUTO`
   - `ALWAYS`
   - `ONE_MINUTE`
   - `FIVE_MINUTES`
   - `FIFTEEN_MINUTES`
   - `UNLOAD_IMMEDIATELY`
2. `AUTO` keeps foreground residency and uses adaptive idle TTL behavior with a 15-minute base TTL.
3. `ALWAYS` maps to long-lived residency while app is foreground.
4. `ONE_MINUTE`, `FIVE_MINUTES`, and `FIFTEEN_MINUTES` map to fixed idle unload windows.
5. `UNLOAD_IMMEDIATELY` disables foreground residency and unloads near-immediately after idle.

## Send Timeout and Cancel Semantics

1. Send operations enforce runtime timeout guards; timeout maps to deterministic runtime error UX (`UI-RUNTIME-001`).
2. On timeout, chat send state is released so user can retry without restarting the app.
3. Runtime cancellation is attempted for active session generation on timeout/cancel pathways.
4. JNI runtime path supports active cancel; fallback runtime path is non-interruptible and surfaces deterministic timeout guidance.

## Diagnostics UX

1. `Advanced` sheet includes `Export diagnostics`.
2. Diagnostics output is rendered in timeline as a system message.
3. Support/QA workflows should capture diagnostics output alongside runtime backend + status.

## Model Provisioning and Download Manager UX (Phase-2)

1. The app uses one unified `Model library` bottom sheet rather than separate runtime/library tabs.
2. Entry points into that surface include the top-bar model chip, the blocked composer `Setup` path, and runtime error/recovery actions such as `Open model library` or `Fix model library`.
3. The default `My models` view includes:
   - `Active model`
   - every active, paused, or recoverable download (including catalog downloads)
   - `Ready on this device` model cards
4. `Explore` contains catalog search and available models. Hugging Face URL/search/history is behind its `Advanced sources` disclosure.
5. A downloaded-but-not-loaded model is labeled `Ready to use`; its `Use now` action persists that version as active and loads it. The stable action selector remains `model_library_load_<modelId>_<version>`.
6. System Back closes `Advanced sources` first, then returns `Explore` to `My models`; the next Back dismisses the sheet. Swipe, scrim tap, and explicit Close dismiss the sheet immediately.
7. Import path remains available in all builds and writes versioned model records.
8. Download path is available in the primary app build and supports queue/pause/resume/retry.
9. Active downloads can be cancelled from the unified sheet; cancellation also cleans temporary files.
10. Manual download completion result is `verified, activation pending`; it does not by itself prove the runtime is loaded.
11. The `Get ready` path can auto-activate and load the matching version when a pending activation is set.
12. Active version removal is guarded; the cleanup flow can clear the sole installed version when it is safe to do so.
13. Runtime unlock is only confirmed after activation + refresh startup checks.
14. Single-image attach is only claim-safe when the selected model packaging includes the required multimodal companion artifact (`mmproj`) and setup/provisioning evidence covers that companion sync, not just the primary model file.
15. Pending simple-first activation survives ViewModel recreation. An already-terminal matching task, or an already-installed target without a scheduler task, is reconciled on return before activation/load continues.
16. Scheduler interruption reschedules background work; it is not presented as user cancellation.
17. The target intent is recorded before either an existing-model load or a new download, and clears only when that exact model reaches send-ready or the user abandons it for another choice.
18. Retryable transport failures remain queued for automatic retry instead of showing a false terminal failure; only terminal failure/cancel states require recovery.
19. The simple-first start request and the manager enqueue path both serialize duplicate starts, so rapid taps cannot create parallel large downloads.

## Manifest Outage Behavior

1. If manifest fetch fails/returns no usable entries, setup UX keeps import path visible as primary recovery.
2. Download state is treated as degraded; runtime remains usable if required active models are already verified.
3. Recovery copy includes issue state plus next action (`import`, `retry manifest`, or `refresh runtime checks`).
