# Phase 0 PRD - PocketAgent

## Objective

Build a privacy-first, offline-capable mobile AI assistant that runs locally on user devices with acceptable speed, battery behavior, and thermal stability for everyday use.

## Problem Statement

Users want useful AI assistance when:

- they do not trust cloud upload of personal data,
- connectivity is poor or unavailable,
- they need low-latency response on-device.

Most current products either depend on cloud inference or provide limited offline capability without strong daily utility.

## Target Users (ICP)

1. Privacy-sensitive professionals (journalists, founders, legal and healthcare-adjacent users)
2. Students and researchers needing offline summarization and Q&A
3. Travelers and field workers with intermittent connectivity
4. Developers and power users who value local control

## Jobs To Be Done (JTBD)

1. "Help me think and write quickly without sending my data to the cloud."
2. "Understand this image or document photo while offline."
3. "Execute simple assistant tools (notes, reminders, calculator) safely on my device."
4. "Remember relevant context from prior conversations without storing data remotely."

## Value Proposition

Private by architecture:

- local inference by default,
- explicit offline mode,
- no silent data upload,
- transparent model/runtime behavior.

## Product Principles

1. Local-first inference and storage
2. Explicit user control for any network-dependent action
3. Graceful degradation across device classes
4. Trustworthy, deterministic tool execution
5. Stable UX over peak benchmark numbers

## MVP Scope

Included:

1. Offline text chat with streaming responses
2. Single-image/document-photo understanding
3. 3-5 local tools (calculator, notes lookup, reminder creation, local search, date/time)
4. Model routing between ultra-fast text, higher-quality small text, and one tiny vision tier based on capability rules
5. Rolling context + summarization memory

## MVP UI Product Surface (WP-11)

UI direction: chat-first, WhatsApp-like conversation timeline with advanced controls available via an expandable sheet.

Simple-first first-session lane (direct replace):

1. First session focuses on `prompt -> response -> one follow-up response` with telemetry tracking.
2. One-tap `Get ready` is the primary setup action and defaults to the `0.6B` text download path.
3. Advanced controls and tools remain available by default; no follow-up unlock gate is required.
4. Import fallback remains visible in model setup when download/manifest path is unavailable.

Required MVP UI capabilities:

1. Chat timeline with user/assistant/system bubbles and streaming assistant updates.
2. Composer with send action, disabled/loading states, and image attachment action.
3. Session UX with create/switch/delete and persistence across app restarts.
4. Tool UX with prompt-first shortcuts in the dialog and clear safety/runtime error feedback contracts.
5. Advanced controls sheet with:
  - routing mode: `Auto`, `QWEN3_0_6B`, `QWEN3_1_7B`, `QWEN_0_8B`, `LLAMA_3_2_1B` (availability depends on packaged model support)
   - runtime performance profile: `BATTERY`, `BALANCED`, `FAST`
   - GPU acceleration toggle (enabled only when supported on device/runtime path)
   - diagnostics export action
   - runtime detail readout (active model + first-token/total/prefill/decode/decode-rate snapshots).
6. Product guardrails:
   - explicit offline-first status indicator
   - clear startup/runtime error banners
   - no hidden cloud behavior in MVP flows.
7. First-session telemetry cue:
   - after follow-up completion, app records deterministic first-session cue state/events.

## Canonical MVP UI User Stories

1. As a user, I can send a prompt and see streaming output in a chat bubble timeline.
2. As a user, I can reopen the app and continue prior conversations.
3. As a user, I can attach one image and get a contextual response in the same thread.
4. As a user, I can launch tool prompt shortcuts from the `Tools` entry point and send them from the composer.
5. As a power user, I can override model selection from an advanced sheet.
6. As a privacy-sensitive user, I can export diagnostics without exposing sensitive content.
7. As a user, if send stalls or exceeds timeout, I receive deterministic recovery guidance and can retry without losing context.
8. As a user, if manifest/download path is unavailable, I can still recover through import flow.
9. As a user, I can understand runtime status transitions (`Loading` to `Ready` or deterministic error) without ambiguity.
10. As a user, I can tune speed/battery behavior from advanced controls with deterministic profile labels.

## Timeout/Cancel Recovery Contract (MVP)

1. Runtime generation timeout maps to deterministic UX code `UI-RUNTIME-001`.
2. Timeout recovery path keeps CTA hierarchy explicit:
   - `Retry send`
   - `Refresh runtime checks`
   - `Fix model setup`
3. Session content must remain preserved across timeout/cancel events.
4. Fallback runtime paths that do not support interruption must still surface deterministic timeout messaging.

## Launch Workflow Lock (PROD-01)

Lifecycle: Finalized lock pass on 2026-03-04 (WP-03 confirmed Done on execution board).

Top launch workflows (must be excellent at MVP launch):

1. Workflow A - Offline Quick Answer (text-only)
   - User intent: get a useful answer without network dependency.
   - Entry conditions: offline mode enabled or no connectivity.
   - Testable acceptance:
     - Scenario A threshold report = PASS on required launch devices.
     - Crash/OOM rate <= 5% across 10-run short loop.
     - No network call made during workflow execution (policy/log validation).
2. Workflow B - Offline Task Assist (safe local tools)
   - User intent: complete deterministic utility actions (calculator/date-time/notes/reminder) locally.
   - Entry conditions: supported tool request with allowlisted tool name and schema-valid payload.
   - Testable acceptance:
     - 100% rejection of malformed/non-allowlisted tool calls in tool safety test suite.
     - 100% deterministic success for positive allowlisted fixtures.
     - Zero policy bypass findings in adversarial tool regression tests.
3. Workflow C - Context Follow-up (memory + image-aware continuity)
   - User intent: ask follow-up questions with prior context and optional single-image input.
   - Entry conditions: prior chat context exists; optional image is provided.
   - Testable acceptance:
     - Scenario C benchmark evidence produced for required launch devices.
     - Memory survives app restart in persistence validation tests.
     - Quality rubric notes for image workflow recorded with no blocker defects.

Final lock gate for PROD-01:

1. `WP-03` status is `Done` on `docs/operations/execution-board.md`.
2. Workflow A/B/C evidence links are attached in `docs/operations/evidence/index.md` and mapped in `docs/operations/tickets/prod-10-launch-gate-matrix.md`.
3. Product + QA both mark the launch workflow checklist as complete (PROD-03 handoff).

## UI Acceptance Suite (WP-11)

1. `UI-01` Launch renders chat screen, composer, and empty-state prompt.
2. `UI-02` Send message streams token updates and finalizes assistant bubble.
3. `UI-03` Session switch preserves per-session timeline state.
4. `UI-04` App restart restores session list and last active session.
5. `UI-05` Image attach handles success and invalid-file failure paths.
6. `UI-06` Tool request shows success and schema-rejection error UX.
7. `UI-07` Advanced sheet changes routing mode and reflects active mode.
8. `UI-08` Diagnostics export succeeds and sensitive keys remain redacted.
9. `UI-09` Offline policy remains enforced while UI actions are used.
10. `UI-10` Long-run UI soak includes navigation + send/image/tool loops without ANR/OOM.
11. `UI-11` Send-timeout path maps to deterministic timeout UX copy/code and exits sending state.
12. `UI-12` Internal-download manifest outage path preserves import recovery workflow and clear status messaging.
13. `UI-13` Advanced controls profile switching (`BATTERY`/`BALANCED`/`FAST`) updates runtime contract and persists across restore.
14. `UI-14` GPU acceleration toggle reflects capability state and persists selected behavior.
15. `UI-15` Runtime detail telemetry surfaces first-token/total/prefill/decode/decode-rate labels after send completion.

Release policy update:

- External beta/go-live signoff requires both `WP-07 Done` and `WP-11 Done`.
- Promotion beyond current pilot cohort also requires `WP-13` moderated usability closure and `PROD-10` matrix required rows `PASS`.

Excluded (non-MVP):

1. Broad video analysis workflows
2. On-device model training/fine-tuning (LoRA)
3. Larger-than-small-model default runtime guarantees on all phones
4. Autonomous multi-step open-ended agent loops
5. Cross-device sync and cloud backup by default

## Success Metrics (Phase 0 and MVP Entry)

Feasibility:

1. Stable operation on mid-tier devices without frequent OOM
2. Sustained thermal behavior in 5-10 minute sessions
3. Throughput and latency meet minimum UX thresholds

MVP readiness:

1. P50 first-token latency meets target
2. P50 decode throughput meets target
3. Crash-free benchmark sessions >= 95%
4. Image task completion quality meets acceptance rubric
5. UI acceptance suite (`UI-01` to `UI-10`) passes on the release candidate app.

## Assumptions That Must Hold

1. Quantized `0.6B`, `0.8B`, `1B`, and `1.7B` models provide acceptable utility for key tasks
2. Local image pathway can run within thermal and memory limits
3. `llama.cpp` baseline can be integrated and stabilized on Android within schedule
4. Packaging/downloading model artifacts is compatible with app-store constraints

## Constraints

1. Mobile RAM and thermal budgets are hard limits
2. Device fragmentation on Android requires fallback paths
3. App size and model distribution must be modular/on-demand
4. Privacy claims must match implementation details exactly

## Milestone Definition

Phase 0 complete when all of the following are true:

1. PRD and architecture documents approved
2. ADR set complete and accepted
3. Monorepo skeleton established
4. Benchmark protocol and device matrix finalized
5. Spike execution record produced with go/no-go recommendation
