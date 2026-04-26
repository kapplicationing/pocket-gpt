# Feature Catalog and Feasibility Bands

Last updated: 2026-04-26

This catalog lists current and possible features based on known technical constraints (mobile RAM/thermal limits, model/runtime maturity, and privacy requirements).

## Band A: MVP-Critical (Build Now)

| Feature | Why It Matters | Constraints | Engine Status | In-App UX Status |
|---|---|---|---|---|
| Offline text chat | core assistant utility | first-token latency, memory/OOM | Implemented | Implemented (WP-11 gate complete with device evidence) |
| In-app model provisioning + readiness recovery | enables normal-user runtime setup without shell commands | file import UX, artifact verification strictness | Implemented (WP-12 policy reused in app path) | Implemented (release-gated; unified `Model library` import/download/recovery + refresh checks) |
| Streaming responses | perceived speed and UX quality | runtime callback reliability | Implemented | Implemented (instrumented + Maestro flows validated) |
| Model routing (`Qwen3 0.6B` / `Qwen3 1.7B` / `Llama 3.2 1B` / `Qwen 3.5 0.8B`) | battery and thermal control | device-state signal quality | Implemented | Implemented (advanced controls + routing override validated) |
| Runtime performance profiles (`BATTERY`/`BALANCED`/`FAST`) | explicit speed vs battery control with deterministic runtime presets | profile tuning must not break timeout/recovery UX | Implemented (`PerformanceRuntimeConfig` contract) | Implemented (advanced controls profile selector + persisted restore) |
| GPU acceleration toggle (capability-gated) | allows supported devices to opt into faster decode path | OpenCL/Hexagon backend packaging + runtime capability detection on device | Implemented | Implemented (toggle disabled when unsupported, persisted when supported) |
| Runtime telemetry readout labels | faster triage for regressions and support | label correctness and user-facing clarity | Implemented | Implemented (first-token/total/prefill/decode/decode-rate/peak-rss in advanced details) |
| Simple-first first-session lane | improves first-run clarity and reduces setup/control overload | must keep deterministic recovery + no dead-end setup path; launch still needs current-window lane evidence beyond the local authoritative onboarding contract | Implemented | Implemented (`Get ready` workflow via the blocked `Setup` button, unified `Model library` recovery/import surface, advanced/tools not treated as the primary first-session happy path; authoritative onboarding instrumentation now exists in the local lane set) |
| Prompt-first local tools | practical daily utility without exposing a richer direct-tool launch surface | strict validation/sandboxing; launch claims must stay prompt-first even though deeper runtime/controller paths exist | Implemented (WP-05 closed) | Implemented (prompt shortcuts prefill composer; validated success/failure paths) |
| Memory v1 | continuity across sessions | retrieval quality + retention policy | Implemented (file-backed + pruning) | Implemented (session restore/switch continuity validated) |
| Single-image attach + Q&A | multimodal differentiation without expanding to broad document/photo analytics claims | image path latency and correctness; launch claims stay bounded to one attached image in-thread; claim-safe packaging requires the matching multimodal companion artifact (`mmproj`) in setup/provisioning evidence | Implemented (WP-06 closed; companion-artifact sync understood in local setup/preflight path) | Implemented (single-image attach success/failure UX validated) |
| Offline policy-aware network enforcement | trust and privacy claim integrity | strict runtime boundary wiring | Implemented (ENG-17) | Implemented (runtime startup checks + UX messaging) |
| Resilience and startup guards | reduce crash/startup failure support load | guard correctness across OEM behavior | Implemented (WP-07 resilience closeout) | Implemented (runtime error banners + startup status) |
| Runtime backend transparency | support/debug can identify runtime path quickly | backend identity correctness | Implemented | Implemented (backend chip + advanced-sheet backend details) |
| Structured UI error contracts | deterministic support and triage | stable error-code mapping | Implemented | Implemented (`UI-STARTUP-001`, `UI-IMG-VAL-001`, `UI-TOOL-SCHEMA-001`, `UI-RUNTIME-001`) |

## Band B: Near-Term Expansion (Post-MVP)

| Feature | Why It Matters | Constraints | Engine Status | In-App UX Status |
|---|---|---|---|---|
| Expanded Android device-tier coverage | market expansion within Android install base | OEM variance and runtime compatibility qualification | Planned | Planned |
| Voice activation and hands-free command path (limited beta only) | keeps voice in scope for controlled cohorts without turning it into a broad public launch promise | microphone permission and local voice-model install are the only hard blockers for always-on listening; assistant-role setup and OEM battery guidance remain advisory/support follow-up; public positioning must stay Advanced-only and non-headline | Implemented (limited-beta path) | Implemented (Advanced-only toggle + blocker/advisory guidance + bounded device-action tools); user-visible but not a general launch claim |
| Strict tool schema/runtime contract | stronger safety guarantees behind the prompt-first tool surface | parser/runtime contract coverage; should not be marketed as richer direct-tool UX on its own | Implemented | Implemented (runtime/UI contract retained behind prompt-first entry) |
| Rich diagnostics dashboards | faster QA and regression triage | safe redaction + metrics consistency | Planned | Planned |
| Better image workflows | broader use cases (documents/photos) | model quality on edge cases | Planned | Planned |

## Band C: Voice Expansion (Post-Limited-Beta)

| Feature | Why It Matters | Constraints | Engine Status | In-App UX Status |
|---|---|---|---|---|
| Offline STT | natural input modality | model size/latency on-device | Planned | Planned |
| Offline/Hybrid TTS | hands-free output and accessibility | voice quality vs power usage | Planned | Planned |
| Voice conversation mode | stronger assistant UX | interruption handling + latency budgets | Planned | Planned |
| Wake/quick actions | fast invocation | OS policy restrictions and battery impact | Research | Research |

## Band D: Advanced/Long-Term

| Feature | Why It Matters | Constraints | Engine Status | In-App UX Status |
|---|---|---|---|---|
| Bounded multi-step workflows | higher task completion | safety and predictability | Research | Research |
| Optional encrypted sync | multi-device continuity | explicit consent and privacy boundaries | Research | Research |
| Pro model tiers (`4B`/`9B`) | quality for capable devices | thermals + sustained UX | Research | Research |
| Domain packs/adapters | personalization and specialization | quality assurance + policy controls | Research | Research |

## Out of Scope for Current MVP

1. Broad video analytics workflows
2. On-device training/fine-tuning
3. Unbounded autonomous agent loops
4. Cloud-dependent default path

## Feature Prioritization Rules

1. Must improve daily utility for ICP users.
2. Must preserve local-first privacy guarantees.
3. Must pass benchmark, reliability, and UI acceptance gates.
4. Must not destabilize current stage commitments.
