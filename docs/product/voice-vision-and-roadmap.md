# Voice Vision And Roadmap

Last updated: 2026-07-12
Owner: Product + Android

## Decision

PocketAgent should become a private, offline conversational layer for the phone:

> Speak naturally, know what stays on-device, receive a useful answer or a safely bounded action, and continue the same conversation by voice or text.

Voice is one interface to the normal chat and typed tool runtime. Always-on
activation is an optional invocation layer, not the definition of the voice
product. Foreground dictation still produces an editable draft, while a user who
explicitly turns on hands-free Offas opts into immediate local processing after
the wake phrase.

Hands-free Offas is available in production builds. Android must show the
ongoing listening notification and system microphone privacy indicator, and the
user can stop listening from the notification or by voice. The current
hands-free fallback conversation is still ephemeral; moving it into the normal
persistent chat is migration work, not a reason to hide the invocation feature
behind a debug or device allowlist.

## Original Plan And Current Reality

The original sequence was offline STT, offline or hybrid TTS, conversational
voice, then wake and quick-action research. The April 2026 implementation jumped
to the harder always-on assistant slice first.

The current working tree now includes:

1. foreground English dictation into an editable composer draft
2. local partial and final transcription through Sherpa ONNX
3. assistant-message read-aloud through an Android TTS voice that Android marks
   as not requiring a network connection
4. a visible, cancellable capture-once surface through the Android assistant role
5. a deterministic no-LLM fast path for alarms, timers, exact app launch,
   media volume, and flashlight, with the model used only as fallback
6. strict action schemas, negation and multi-intent rejection, one-action
   limits, source-aware confirmation, and a one-shot execution capability that
   blocks raw foreground-chat mutations
7. Android Clock delegation for real alarms and timers instead of app-owned
   notification approximations
8. a dedicated single-phrase KWS path for "Offas" (pronounced "off us") with
   no continuous-ASR wake fallback
9. in-session AM/PM clarification for ambiguous alarms, with cancellation,
   expiry, and no stale confirmation after silence or dismissal
10. notification access as a hard always-on prerequisite so Stop and Talk now
    controls cannot silently disappear
11. a production `VoiceInteractionService` and session service that Android can
    select as the active assistant and use as the lifecycle anchor
12. a guided production setup: one switch requests Android's notification and
    microphone permissions, opens the assistant picker, then downloads, verifies,
    and atomically installs about 60 MB of pinned voice models when needed
13. runtime pauses for calls, another microphone owner, severe thermal pressure,
    or critical unplugged battery, with bounded recovery instead of a watchdog
14. an isolated, non-debuggable `voiceProof` package for release-semantic physical
    lifecycle and acoustic proof without clearing the user's normal app state
15. Sherpa ONNX `8.5.4` with the pinned production profile
    `offas-int8-sherpa-8.5.4-v2`, using official int8 ASR and KWS model files
16. a quiet-audio energy gate for the resident KWS path, plus lazy ASR creation
    and release after each turn so continuous listening does not keep the larger
    recognizer resident

Assistant gesture and opt-in hands-free Offas are both production paths. A user
can turn on hands-free listening on any compatible Android device after selecting
PocketAgent's assistant service and completing the visible permission and model
setup. Device qualification now supports published reliability guidance and
support tiers; it does not decide whether the switch exists or whether a release
build may start the listener. Normal conversational answers still require a
loaded local LLM.

The selected Android assistant service launches the trusted, visible internal
assistant surface. The separately exported fallback entry remains untrusted, and
ambient wake requests require a spoken preview and confirmation before phone
mutations. Leaving the visible assistant with Back, Home, or screen-off cancels
its capture.

### Evidence Checkpoint

Across 2026-07-11 and 2026-07-12, a Samsung Galaxy A51 running Android 13
provided physical-device evidence for:

1. English TTS file synthesis using a Samsung voice that Android reports as not
   requiring a network connection
2. read-aloud start, explicit stop, and protection from late callback state
3. real Sherpa ONNX decoding of an upstream speech fixture
4. deterministic speaker-to-physical-microphone dictation
5. physical speech-fixture transcription into the actual editable composer with
   no automatic send
6. editable partial-draft and read-aloud Compose contracts
7. missing-model handling, explicit capture stop, background cancellation, and
   the canonical typing smoke journey
8. Android assistant-role selection and assistant-key invocation into the
   visible PocketAgent voice surface
9. an integrity-verified install and successful listener initialization with the
   official int8 ASR/KWS production profile under Sherpa ONNX `8.5.4`
10. short quiet-idle samples showing the energy-gated listener at roughly
    0-2.5% of one CPU core and about 136-137 MB resident memory; this is a
    diagnostic sample, not a 24-hour battery or thermal qualification
11. a typed, authorized two-minute timer handoff into the physical Samsung Clock
    app, verified through both `VOICE_ACTION_RESULT` and the live labeled Clock
    notification, then dismissed and removed
12. a one-minute unplugged wake-soak harness smoke that collected five samples,
    zero unexpected listener samples, and zero wake detections; its verdict was
    correctly `INCOMPLETE`, not a battery qualification claim

The isolated `voiceProof` build is the canonical way to repeat this on a
non-debuggable, release-semantic package. Its setup activity exercises the real
guided enablement and verified in-app model installer; its acoustic activity
plays "Off us. Please stop listening." through the phone speaker so the real microphone,
KWS, command path, and durable stop can be observed together. Record the build,
device, logs, and resulting listener state for every run; the existence of the
probe is not itself a passing result.

This is one-device support evidence, not a supported-device matrix. A single
positive/negative KWS fixture proves model wiring, not wake reliability.
It does not yet prove network-disconnected TTS, natural human speech through the
complete assistant-to-answer journey, headset routing, noisy rooms, screen-off
survival, acceptable false wakes, or sustained battery and thermal behavior.
Those results determine support guidance and known limitations, not production
feature availability.

## Use Cases

### Voice Input And Accessibility

Ship and qualify first:

1. dictate, inspect, edit, and send a prompt
2. read, stop, replay, and later adjust the speed of an answer
3. continue the same chat between text and voice
4. support users for whom typing or reading is difficult
5. capture short private notes locally

### Hands-Busy Conversation

Build after foreground dictation is proven:

1. continuous follow-up questions while walking, cooking, or using a headset
2. concise spoken summaries
3. interruption and correction while PocketAgent is speaking
4. lock-screen and background sessions with explicit consent
5. offline language practice and translation on supported language packs

### Bounded Device Assistant

Expand only behind typed permissions and confirmation policy:

1. alarms and timers
2. app launch, flashlight, and media volume
3. reminders and notes
4. local search over user-selected content
5. small multi-step routines with preview, confirmation, receipt, and undo where
   Android supports it

Sensitive, destructive, or externally visible actions require confirmation.
Open-ended autonomous device control stays out of scope until tool permissions,
audit history, cancellation, and recovery are mature.

## Comparable Product Patterns

Checked against official product documentation on 2026-07-11. The market has
converged on two separate promises:

1. **Dictation:** record, transcribe, inspect or edit, then explicitly send.
2. **Conversation:** keep a visible voice session open, speak responses, allow
   interruption, and preserve the transcript in the normal chat.

[ChatGPT Voice](https://help.openai.com/en/articles/20001274) and
[Claude Voice](https://support.claude.com/en/articles/11101966-use-voice-mode)
separate dictation from two-way voice and support interruption plus text/voice
continuity. [Gemini Live](https://support.google.com/gemini/answer/15274899)
adds captions, background and lock-screen continuation, and connected-app
actions; its [headphone support](https://support.google.com/gemini/answer/15456140)
shows the value of explicit headset invocation and controls. [Home Assistant
Assist](https://www.home-assistant.io/voice_control/android/) is the closest
local-first reference: wake detection can remain local and work in the
background, but its documentation explicitly warns that continuous Android
listening keeps the CPU awake and has a noticeable battery cost.

PocketAgent should borrow the interaction contract, not the cloud dependency.
Its durable differentiation is a visible and auditable local path: optional
local wake detection, local STT, local inference, offline-qualified TTS, no raw
audio retention by default, an editable transcript, and confirmed bounded
actions.

## Delivery Roadmap

### Phase 1: Foreground Voice Foundation

Current implementation slice:

1. editable composer dictation with partial feedback
2. explicit start and stop; never auto-send
3. read-aloud and stop on complete assistant messages
4. exclusive capture/playback ownership
5. lifecycle, session-change, and offline-TTS safety
6. guided download, hash verification, atomic publication, retry, and progress
   for the pinned ASR and Offas KWS bundles

Remaining promotion work:

1. add voice-model removal, update, and storage-management UX around the shipped
   verified installer
2. promote the initial A51 suite and `voiceProof` probe into a retained canonical
   lane, then expand it
   across permission denial and recovery, network-disconnected operation, human
   speech, noise, screen-off interruption, headset/audio focus, and another OEM
3. publish supported English/device/TTS tiers and privacy copy

### Phase 2: Conversational Voice Mode

1. dedicated voice surface with a visible transcript
2. streaming ASR, sentence-level response pipelining, and low-latency TTS
3. voice activity detection and endpoint tuning
4. pause, mute, cancel, replay, and session continuity
5. Bluetooth, headset, call, and Android audio-focus handling
6. interruption and barge-in with acoustic echo control

### Phase 3: Production Opt-In Hands-Free Activation

Implemented safety foundation:

1. dedicated phrase-specific KWS is mandatory; full ASR is never a wake fallback
2. the in-app installer downloads only pinned upstream artifacts, verifies the
   archive and required files, writes the one-phrase keyword graph, and publishes
   complete model directories atomically
3. passive KWS holds no audio focus; capture and TTS use transient focus
4. capture has bounded pre-roll, silence and maximum-duration deadlines, and
   negative microphone reads become typed failures
5. capture-once is non-sticky, task removal does not schedule resurrection, the
   notification is private and has Talk now plus Stop listening actions
6. Android 13+ notification access is mandatory for always-on mode, and rapid
   capture replacement waits for native/audio teardown before reacquiring
7. production availability is gated only by explicit user opt-in, visible
   notification and microphone permissions, the selected PocketAgent assistant
   service, and verified model readiness; there is no device allowlist
8. Android's selected `VoiceInteractionService` owns assistant lifecycle and can
   restore an enabled listener after involuntary process loss while respecting an
   explicit user stop
9. the ongoing private notification exposes Talk now and Stop listening, while
   Android provides its normal microphone privacy indicator
10. the isolated `voiceProof` build exercises release semantics without debug
    bypasses or collision with the normal package

Remaining promotion work:

1. run the retained false-accept, recall, wake-latency, screen-off, Doze,
   battery, thermal, call, media, wired, and Bluetooth matrix
2. collect support evidence on Samsung, Pixel, and one aggressive-background OEM
   across the Android versions in the release support window
3. turn retained results into clear device-tier guidance, recovery advice, and
   staged-rollout monitoring instead of an availability allowlist
4. migrate hands-free fallback turns into persistent normal-chat continuity
5. expand human-speech and noise corpora beyond the deterministic acoustic probe

The canonical no-wake battery/survival probe is
`scripts/dev/voice-wake-soak.sh`. It pairs a listener-off baseline with an
always-on run so whole-phone idle cost is not mislabeled as incremental wake
cost. It produces samples, filtered voice logs, and an explicit
PASS/FAIL/INCOMPLETE summary without resetting Android battery statistics. Its
PASS covers only that pair and contributes to support guidance; it neither
enables nor disables hands-free Offas in the shipped app.

### Phase 4: Safe Tool Expansion

Implemented first slice:

1. strict shared schemas, semantic ranges, Unicode-safe app resolution, and
   one-action-at-a-time enforcement, including negation and contradictory-value
   rejection
2. a trusted internal Talk now invocation can execute one unambiguous bounded
   action; exported assistant and ambient wake sources require deterministic
   yes/no confirmation for every mutation
3. ambiguous alarm periods ask AM/PM and retain the typed partial plan for the
   answer instead of allowing the model to guess or closing the session
4. scheduled effects and app launches require the visible assistant surface
5. alarms and timers delegate to Android Clock; volume verifies the resulting
   index; flashlight has action-specific permission recovery
6. Back, Home, screen-off, explicit Cancel, empty confirmation, and failed
   preview speech clear pending authority before another turn can run

Remaining:

1. durable action ledger, plan hash, idempotency, visible receipts, and audit
2. verified undo for volume/flashlight and cancel for alarms/timers
3. persistent normal-chat continuity instead of an ephemeral fallback session
4. reminders, notes, and local search before broader routines
5. multi-step execution only after single-action reliability is measured

## Release And Support Evidence

Hands-free Offas remains available to users who complete its runtime setup.
Qualification controls the strength of reliability and battery claims, the
support guidance shown for a device class, and staged-rollout decisions. Keep
these applicable evidence gates current before broadening those claims:

1. in-app ASR model provisioning and recovery
2. no network-required voice in an offline-only path
3. no raw-audio persistence unless a separate, explicit feature requires it
4. transcript retention and deletion behavior documented
5. supported language guidance and device-tier evidence matrix
6. real-device permission, noisy-room, headset, lifecycle, and OEM evidence
7. battery, thermal, false-wake, and latency evidence for always-on operation
8. accessibility semantics and visible listening/playback state

Initial experience targets for supported devices:

| Signal | Target |
|---|---:|
| Start-listening feedback | immediate UI state |
| Final transcript after speech | 500-800 ms |
| First useful spoken response | 1.5-2.5 s |
| Stop after interruption | 250 ms |
| Wake acknowledgement p95 | 600 ms |
| Wake recall, quiet at 1 m | at least 95% |
| Wake recall, moderate noise at 3 m | at least 90% |
| False activation | at most 1 per 100 device-hours |
| Incremental always-on drain | at most 75 mW or 0.5 percentage points/hour |
| Always-on temperature rise | below 3 C |

Targets must be reported by device tier and build variant. They are product
budgets, not current evidence claims.
