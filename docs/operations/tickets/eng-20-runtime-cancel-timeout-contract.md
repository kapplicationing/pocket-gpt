# ENG-20 Runtime Cancel/Timeout Contract

Last updated: 2026-04-25
Owner: Engineering
Support: QA, Product
Status: Done

## Objective

Document and harden timeout/cancel behavior across app runtime, orchestrator, and JNI/fallback bridges so user-facing recovery is deterministic.

## Contract

## App-facing behavior

1. `streamUserMessage` accepts `requestTimeoutMs`.
2. Timeout maps to `UI-RUNTIME-001` UX pathway.
3. On timeout/cancellation, UI leaves sending state and preserves session context.

## Runtime/orchestrator behavior

1. Generation timeout guard triggers cancellation for active session.
2. Timeout throws deterministic runtime timeout error.
3. Orchestrator exposes `cancelGeneration(sessionId)`.
4. Request-scoped cancellation path is enabled when `POCKETGPT_STREAM_CONTRACT_V2=1` (default on).

## Bridge behavior

1. `NativeJniLlamaCppBridge` implements active generation cancellation.
2. JNI path uses native cancel hook to stop decode/sampling loop.
3. ADB fallback path reports non-interruptible cancellation (`false`) and returns deterministic fallback semantics.

## Journey telemetry contract

Send-capture stage records:

1. `phase`
2. `elapsed_ms`
3. `runtime_status`
4. `backend`
5. `active_model_id`
6. `placeholder_visible`
7. `request_id`
8. `finish_reason`
9. `terminal_event_seen`
10. `first_token_ms`
11. `completion_ms`

## Acceptance

1. Unit tests cover timeout-to-cancel mapping and session recovery behavior.
2. Native bridge tests cover cancel delegation for JNI + fallback paths.
3. Journey reports include required send-capture fields for every run.
4. Product docs reference this contract in PRD/UX behavior sections.

## 2026-04-25 Update

Host-side contract coverage landed:

1. `ChatStreamCoordinatorTest` now asserts timeout terminal state maps to `UI-RUNTIME-001` and records a terminal event.
2. Journey report serialization backfills required failed send-capture fields so `phase`, `placeholder_visible`, runtime status, backend, and active model are never ambiguous in failure evidence.
3. Existing app/runtime tests and devctl lane/gate tests pass under the fast lane.

The controlled-MVP launch evidence set now preserves strict S22 send-capture completion under `QA-11`, so the remaining launch blocker framing no longer applies to this ticket.
