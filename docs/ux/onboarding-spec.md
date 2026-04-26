# Onboarding Specification (UX-ONBOARD-01)

Last updated: 2026-03-08
Owner: Android + Product
Lifecycle: Implemented (MVP v1)

## Objective

Explain privacy guarantees, capability surface, and runtime readiness in the first 30 seconds.

## Current Simple-First Contract

1. Onboarding introduces users to privacy, capabilities, and readiness checks.
2. `Tools` and `Advanced` entry points are visible by default (not follow-up gated).
3. First-session telemetry still tracks progression (`simple_first_entered`, `get_ready_started`, first answer/follow-up events).
4. `Get ready` remains the primary blocked-state CTA when runtime is not ready.

## Flow

1. **Page 1 - Privacy promise**
   - Message: chats and memory stay local by default.
2. **Page 2 - Capability preview**
   - Message: text chat, image analysis, and local tools run in one timeline.
3. **Page 3 - Get ready**
   - Message: use `Get ready` to download the default model, or open model library if you want to import your own.

## Actions

- `Next` advances to next page.
- `Skip` closes onboarding immediately.
- `Get started` closes onboarding on final page.

## Persistence

1. Onboarding completion is persisted (`onboardingCompleted`).
2. Returning users do not see onboarding unless state is reset.
3. First-session stage and telemetry events are persisted with chat/session state.

## UX Guardrails

1. Onboarding never blocks core app render.
2. Privacy wording must stay aligned with `docs/security/privacy-model.md`.
3. Runtime-readiness guidance must reference in-app recovery (`Advanced` -> `Open model library`).
4. Onboarding copy must not imply hidden cloud dependency for core flows.
