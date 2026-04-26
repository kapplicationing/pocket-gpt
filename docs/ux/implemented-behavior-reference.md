# Implemented UX Behavior Reference

Last updated: 2026-04-26  
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

## Voice Activation (Limited Beta)

1. `Advanced` settings include a voice activation section with:
   - enable toggle
   - model-readiness copy
   - listener-status line
   - setup/help CTAs for assistant role, battery settings, and app settings
2. Voice activation is a limited-beta surface for controlled cohorts, not the default onboarding or broad public launch path.
3. Public launch copy must stay bounded to the core chat surface, prompt-first tools, and single-image attach/Q&A until voice evidence, privacy wording, and device-tier language are ready.

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
2. `Get ready` is the primary blocked-state CTA and defaults to the `0.6B` download path.
3. Tools and Advanced entry points are available by default (no follow-up unlock gate).
4. Follow-up completion emits first-session telemetry and cue state.
5. Stage/unlock flags are persisted across app restarts.

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

1. The app now uses a single `ModelLibrary` bottom sheet with `Library` and `Runtime` tabs.
2. `Library` includes explicit sections for:
   - required models
   - downloads
   - installed versions
   - storage summary
3. `Runtime` includes runtime load state, last-used model affordance, and installed versions that can be loaded now.
4. Import path remains available in all builds and writes versioned model records.
5. Download path is available in the primary app build and supports queue/pause/resume/retry.
6. Active downloads can be cancelled from the library tab; cancellation also cleans temporary files.
7. Manual download completion result is `verified, activation pending`.
8. The `Get ready` path can auto-activate and load the matching version when a pending activation is set.
9. Active version removal is guarded; the cleanup flow can clear the sole installed version when it is safe to do so.
10. Runtime unlock is only confirmed after activation + refresh startup checks.

## Manifest Outage Behavior

1. If manifest fetch fails/returns no usable entries, setup UX keeps import path visible as primary recovery.
2. Download state is treated as degraded; runtime remains usable if required active models are already verified.
3. Recovery copy includes issue state plus next action (`import`, `retry manifest`, or `refresh runtime checks`).
