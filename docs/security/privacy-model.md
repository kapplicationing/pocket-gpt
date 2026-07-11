# Privacy and Security Model

Last updated: 2026-07-11

## Privacy Promise

PocketAgent is local-first by default:

1. Inference runs on-device.
2. Conversation and memory storage remain on-device.
3. No background upload of user prompts/responses.

## Data Classification

1. Sensitive user content: prompts, responses, notes, image text
2. Operational metadata: latency, memory, thermal snapshots
3. Policy/runtime state: startup checks, model readiness, offline-only policy state

## Data Flow Rules

1. Sensitive user content stays local unless user explicitly opts into an external action.
2. Tool calls execute through a local sandbox with schema validation.
3. Any network-capable action must pass `PolicyModule` checks.
4. Android platform backup is disabled so conversations, model/runtime files, caches, and diagnostics are not copied through OS backup or device-transfer backup channels.

## Threat Model

### Threats

1. Prompt/data exfiltration through hidden network calls
2. Arbitrary execution via malformed model tool output
3. Retention drift (data stored longer than stated policy)
4. Debug log leakage of sensitive content

### Controls

1. Default-deny network policy for assistant actions
2. Strict tool schema validation and allowlisted tool set
3. Local persistence boundaries with explicit file-backed runtime modules
4. Diagnostics redaction for sensitive keys before export

## Security Controls

1. Model artifact integrity checks (hash verification)
2. Least-privilege permissions for file/media access
3. Secure update channel for model manifests (when remote catalog is enabled)

## User-Visible Controls (Implemented)

1. Collapsible Privacy section inside `Advanced` settings with implemented policy summary
2. Model setup and runtime-refresh actions for readiness recovery
3. Diagnostics export with redaction

## User-Visible Controls (Not Yet Implemented)

1. Per-tool enable/disable settings
2. User retention-window selector
3. Local data reset/delete action in-app

Do not publish these as available controls until implementation and validation are complete.

## Compliance Posture (Foundational)

1. Privacy claims map directly to implemented controls.
2. User consent is required for any optional cloud path.
3. Data inventory and retention behavior are documented and reviewed.

## Implementation Coverage (As Of 2026-03-08)

| Control Area | Planned Guarantee | Current Coverage |
|---|---|---|
| Local inference default | No cloud-required inference path | Implemented (native JNI runtime + startup checks + local model provisioning) |
| Local data persistence | local persistence on-device | Implemented (on-device prefs + SQLite runtime state) |
| Tool safety | strict schema validation + allowlist | Implemented (schema validation + deterministic rejection contracts) |
| Diagnostics privacy | no raw prompt/response by default | Implemented (redaction checks in runtime tests and UI export path) |
| Network gating | explicit policy checks per action | Implemented (policy wiring integrated with Android platform enforcement checks) |
| Platform backup boundary | no OS backup of local conversations or runtime artifacts | Implemented (`android:allowBackup="false"`, enforced by manifest regression test) |
| End-user retention controls | user can tune retention/reset in app UI | Not implemented |
| Voice privacy (future STT/TTS) | equivalent controls to text/image paths | Planned (post-MVP) |

Use `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md` and `docs/roadmap/current-release-plan.md` as closure references.
