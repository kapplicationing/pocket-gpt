# PocketAgent Feature Catalog

Last updated: 2026-07-12

This catalog separates what is implemented from what PocketAgent may claim in
the controlled-MVP release. The controlled-MVP product gate is promoted; store
publication remains blocked on `PROD-14` policy controls, `UX-13` direct retry,
approved assets, and signed package/device/Play execution.

## Controlled-MVP Product Surface

| Feature | Product state | Public claim boundary | Remaining watch item |
|---|---|---|---|
| Offline streaming text chat | Implemented and required gate passed | On-device inference by default; no hidden cloud upload in MVP workflows | First-use latency and sustained thermals on older devices |
| Simple-first setup and recovery | Implemented through `Get ready` plus `Model library` recovery | A normal user can download, import, activate, refresh, and recover models in-app | Keep setup and manifest-outage recovery in recurring device evidence |
| Dynamic Hugging Face discovery and URL download | Implemented | Supported public single-file text GGUF downloads only | Storage, checksum, compatibility, and provider availability |
| Local GGUF import | Implemented with cancellable copy, content-addressed publication, durable metadata, rollback, GGUF identity checks, and bounded metadata parsing | Import only models compatible with a supported PocketAgent runtime target | OEM document-provider behavior and genuinely novel GGUF variants |
| Model routing | Implemented for the supported catalog, including Qwen3 0.6B, Qwen3 1.7B, Llama 3.2 1B, and Qwen 3.5 0.8B | Automatic or user-selected routing within qualified device/model limits | Broader device and model qualification |
| Runtime performance profiles | Implemented with `BATTERY`, `BALANCED`, and `FAST` presets | User-selectable local performance policy | Benchmark before widening device-tier claims |
| Capability-gated GPU acceleration | Implemented with unsupported controls disabled | Acceleration is available only where the packaged backend and device probe qualify it | OEM driver fragmentation |
| Runtime ownership, cancellation, and recovery | Implemented with serialized operation ownership, request/session cancellation, lifecycle drain, and typed recovery mapping | A timed-out or cancelled operation can recover without corrupting the runtime contract | Recurring send-capture and older-device timeout thresholds |
| Runtime telemetry and backend visibility | Implemented in advanced details | First-token, total, prefill, decode, decode rate, peak RSS, and backend status are diagnostic surfaces | Keep labels and redaction aligned with runtime fields |
| Prompt-first local tools | Implemented behind strict schema and allowlists | Tools are entered through prompt shortcuts; do not market a richer direct-tool launcher | Injection and schema-regression coverage |
| Session memory and continuity | Implemented with file-backed retention and pruning | Chats and session context can persist locally | Retention/reset/per-tool controls are not a publishable claim yet |
| Single-image attach and Q&A | Implemented | One attached image can be used in the same conversation | Requires compatible multimodal model plus companion `mmproj`; no broad image/document analysis claim |
| Local-first network policy | Implemented and privacy parity verified | On-device inference by default and no hidden cloud upload in MVP workflows | Model discovery/download is an explicit network action |
| Startup, provisioning, and runtime recovery | Implemented, including stale-metadata repair and structured error mapping | Deterministic status and recovery guidance | Continue OEM and interruption coverage |
| Local-data backup and transfer controls | Implemented with cloud backup disabled, Android 12+ data-extraction exclusions, and a manifest/rules contract test | Cloud backup is disabled; supported app-data domains are excluded from Android 12+ transfer rules, while manufacturer D2D behavior remains device-dependent | Recheck whenever backup/data-extraction configuration changes |
| Generative-AI safety and reporting | Not implemented; `PROD-14` blocks Play upload | No publishable claim until restricted-content prevention and in-app developer reporting pass end to end | Requires approved intake/data contract, privacy/Data Safety update, adversarial tests, and moderation ownership |

## Production Opt-In, Qualification In Progress

| Feature | Product state | Claim and support boundary |
|---|---|---|
| Hands-free Offas and bounded device actions | Available to normal users in production builds after one guided opt-in; no debug flag or device allowlist | Do not promise universal wake, battery, or background reliability until retained 24-hour Samsung, Pixel, and aggressive-background-OEM qualification exists |
| Composer dictation and assistant read-aloud | Implemented with editable English local transcription, in-app voice-pack installation, and Android TTS voices reported as offline | Publish English/TTS/device support tiers from retained evidence; do not turn one-device proof into a universal claim |

## Post-MVP Expansion

| Feature | State | Promotion dependency |
|---|---|---|
| Expanded Android device-tier coverage | Planned | Device qualification, thermal/latency evidence, and support capacity |
| Human-moderated usability replacement for the proxy packet | Planned | Required before using broader non-proxy expansion claims |
| User-facing retention, reset, and per-tool privacy controls | Planned | Implementation plus `SEC-02` evidence parity |
| Rich diagnostics dashboards | Planned | Safe redaction and stable metric semantics |
| Broader image/document workflows | Planned | Model quality, latency, and claim evidence |
| Persistent interruptible voice conversation and qualified hands-free support tiers | Planned | Text/voice continuity plus on-device quality, power, interruption, privacy, language, and multi-OEM evidence |

## Research / Long-Term

1. Wake and quick-action expansion under Android policy constraints
2. Bounded multi-step workflows
3. Optional encrypted multi-device sync
4. Larger model tiers such as 4B and 9B
5. Domain packs and adapters

## Explicitly Out Of Scope For The Controlled MVP

1. Broad video, document, or multi-image analytics
2. On-device training or fine-tuning
3. Unbounded autonomous agent loops
4. A cloud-dependent default inference path
5. Universal hands-free wake, battery, or background-reliability claims

## Prioritization Rules

1. Improve daily utility for the target user.
2. Preserve the verified local-first privacy boundary.
3. Pass narrow risk proof before broad device or release gates.
4. Do not widen the release claim set without matching evidence.
