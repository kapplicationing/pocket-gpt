# Privacy and Security Model

Last updated: 2026-07-12

## Privacy Promise

PocketAgent is local-first by default:

1. Inference runs on-device.
2. Conversation and memory storage remain on-device.
3. No background upload of user prompts/responses.
4. Voice audio and recognized text stay on-device; production voice capture does
   not write raw-audio files.

## Data Classification

1. Sensitive user content: prompts, responses, notes, image text, recognized
   speech, and spoken action labels
2. Operational metadata: latency, memory, thermal snapshots
3. Policy/runtime state: startup checks, model readiness, offline-only policy state

## Data Flow Rules

1. Sensitive user content stays local unless user explicitly opts into an external action.
2. Tool calls execute through a local sandbox with schema validation.
3. Any network-capable action must pass `PolicyModule` checks.
4. Android cloud backup is disabled. Android 12+ data-extraction rules also exclude every supported app-data domain from device transfer, while manufacturer device-to-device behavior remains an explicit device-dependent boundary.
5. The user-triggered voice-pack installer is an explicit network boundary. It
   downloads a pinned 62,828,797-byte payload (about 60 MiB) from Hugging Face
   and GitHub, verifies its archive and file hashes, then publishes it atomically.
6. Hands-free capture runs only after user opt-in, microphone access, visible
   notification access, and selection of PocketAgent's Android assistant service.

## Voice And Local-Action Flow

1. While hands-free Offas is enabled, a microphone foreground service performs
   local phrase detection. PocketAgent supplies an ongoing private notification
   with Talk now and Stop listening actions; Android supplies its system
   microphone privacy indicator on versions that support it.
2. Audio frames, KWS state, ASR state, and pre-roll stay in memory. Production
   code does not persist a recording.
3. Dictation produces an editable draft. A deterministic phone command is parsed
   and validated locally without creating a chat session.
4. A general hands-free question creates a temporary local runtime session with
   `RuntimeMemoryRetention.EPHEMERAL`; `OffscreenRuntimeClient` deletes that
   session in `finally` after success, cancellation, or failure. It does not add
   the turn to normal chat history.
5. Pending confirmation or clarification state is process-local and cleared on
   completion, cancellation, expiry, capture dismissal, or an unspoken preview.
6. Read-aloud selects only an Android TTS voice that Android reports as not
   requiring a network connection.
7. Alarm and timer actions send the validated time or duration and user label to
   a compatible Clock app through `AlarmClock.ACTION_SET_ALARM` or
   `AlarmClock.ACTION_SET_TIMER`. The Clock app owns the resulting record.
8. CAMERA is requested only for `flashlight_toggle`; the action calls
   `CameraManager.setTorchMode` and does not capture an image or video.

## Threat Model

### Threats

1. Prompt/data exfiltration through hidden network calls
2. Arbitrary execution via malformed model tool output
3. Retention drift (data stored longer than stated policy)
4. Debug log leakage of sensitive content
5. Background microphone use without an obvious user-visible stop path
6. Overbroad CAMERA use or accidental image capture for flashlight control
7. Unclear disclosure when an alarm label or timer label crosses into the Clock app

### Controls

1. Default-deny network policy for assistant actions
2. Strict tool schema validation and allowlisted tool set
3. Local persistence boundaries with explicit file-backed runtime modules
4. Diagnostics redaction for sensitive keys before export
5. Foreground-service notification, supported Android microphone indicator,
   explicit hands-free switch, notification Stop action, and spoken stop command
6. Temporary voice-session deletion in `finally` and no production raw-audio writer
7. Action-specific CAMERA permission recovery and torch-only implementation
8. Typed Clock intents with bounded fields and confirmation policy

## Security Controls

1. Model artifact integrity checks (hash verification)
2. Least-privilege permissions for file/media access
3. Secure update channel for model manifests (when remote catalog is enabled)
4. Pinned voice-pack URLs, exact download sizes, SHA-256 verification, safe archive
   extraction, complete-file verification, and atomic publication

## User-Visible Controls (Implemented)

1. Collapsible Privacy section inside `Advanced` settings with implemented policy summary
2. Model setup and runtime-refresh actions for readiness recovery
3. Diagnostics export with redaction
4. Hands-free Offas switch with visible permission, assistant, voice-pack,
   listener, and battery-guidance state
5. Ongoing listening notification with Talk now and Stop listening actions

## User-Visible Controls (Not Yet Implemented)

1. Per-tool enable/disable settings
2. User retention-window selector
3. Local data reset/delete action in-app

Do not publish these as available controls until implementation and validation are complete.

## Compliance Posture (Foundational)

1. Privacy claims map directly to implemented controls.
2. User consent is required for any optional cloud path.
3. Data inventory and retention behavior are documented and reviewed.

## Implementation Coverage (As Of 2026-07-12)

| Control Area | Planned Guarantee | Current Coverage |
|---|---|---|
| Local inference default | No cloud-required inference path | Implemented (native JNI runtime + startup checks + local model provisioning) |
| Local data persistence | local persistence on-device | Implemented (on-device prefs + SQLite runtime state) |
| Tool safety | strict schema validation + allowlist | Implemented (schema validation + deterministic rejection contracts) |
| Diagnostics privacy | no raw prompt/response by default | Implemented (redaction checks in runtime tests and UI export path) |
| Network gating | explicit policy checks per action | Implemented (policy wiring integrated with Android platform enforcement checks) |
| Platform backup boundary | no cloud backup; Android 12+ supported app-data domains excluded from configured device transfer | Implemented (`android:allowBackup="false"` plus `@xml/data_extraction_rules`, enforced by manifest/rules regression test); OEM D2D behavior remains device-dependent |
| End-user retention controls | user can tune retention/reset in app UI | Not implemented |
| Voice capture and playback | opt-in local KWS/streaming ASR; visible foreground microphone use; no raw-audio file persistence; read-aloud rejects TTS voices marked as network-required | Implemented for production hands-free and foreground dictation; broader device support evidence remains ongoing |
| Voice model setup | transparent user-triggered download with integrity verification | Implemented (about 60 MiB pinned payload, progress, retry, archive/file verification, atomic install) |
| Offscreen voice transcript | no hidden durable chat history | Implemented (ephemeral runtime retention plus unconditional temporary-session deletion); persistent text/voice continuity is intentionally future work |
| Flashlight permission | CAMERA is not used for image capture | Implemented (permission requested on flashlight action; `setTorchMode` only) |
| Alarm and timer handoff | bounded local action delegates to the system Clock | Implemented (`ACTION_SET_ALARM`/`ACTION_SET_TIMER`, typed fields, confirmation policy) |

Use `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md` and `docs/roadmap/current-release-plan.md` as closure references.
