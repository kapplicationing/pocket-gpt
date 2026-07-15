# Controlled-MVP Risk Register

Last updated: 2026-07-11

This register tracks residual product and release risk after the controlled-MVP
gate reached `Promote`. It does not duplicate closed implementation tickets.

## Active Risks

| ID | Risk | Severity | Likelihood | Current control | Residual action | Owner |
|---|---|---|---|---|---|---|
| R-001 | Mid-tier devices exceed memory or latency targets | High | Medium | Constrained-device defaults, routing caps, runtime profiles, memory telemetry | Set expansion thresholds and qualify the next device tiers | AI Runtime + QA |
| R-002 | Sustained sessions throttle or feel stalled | High | Medium | Soak evidence, thermal downgrade policy, token caps, performance profiles | Track first-use and long-prefill latency in the controlled cohort | Mobile Platform + QA |
| R-003 | GPU/acceleration behavior varies across OEM drivers | High | High | Capability probe, disabled unsupported toggle, robust CPU/native fallback | Keep GPU claims device-bounded and expand qualification deliberately | Android |
| R-004 | Privacy or marketing claims exceed implemented controls | Critical | Low | `SEC-02` verified-only publish rule and claim freeze | Exclude partial `P-04` controls and audit every final asset/copy block | Product + Security + Marketing |
| R-005 | Prompt-first tools accept unsafe or malformed requests | Critical | Low | Strict schema, allowlists, no shell execution, adversarial tests | Preserve schema and injection regression coverage | Platform |
| R-006 | Model acquisition fails because of storage, provider, artifact, or store constraints | High | Medium | In-app download/import, preflight checks, checksum/compatibility validation, manifest fallback | Validate the final release bundle and monitor provider/storage failures | Product + Platform |
| R-007 | One-time launch evidence drifts after promotion | High | Medium | Fixed evidence ledger, generated readiness report, completed `QA-13` weekly send-capture gate | Keep the hardware runner available and triage every fail-closed weekly packet | QA |
| R-008 | Incomplete assets, approval, signing, hardware, or Play setup delays publication | High | High | Completed `PROD-11`, prepared metadata/build package, `MKT-08`, `MKT-10`, `PROD-13`, publication checklist | Obtain the real operator inputs, approve fresh assets, and install-validate the signed bundle | Product + Release Ops |
| R-009 | Scope expands beyond verified controlled-MVP claims | High | Medium | Locked release scope and `PROD-10` claim map | Reject general voice, broad image analysis, and unverified privacy-control claims | Product |
| R-010 | Model or dependency licensing assumptions change | High | Low | Release checklist and dependency/license review | Recheck selected release models and bundle before submission | Product + Legal |
| R-011 | AI human-proxy usability evidence is overgeneralized beyond the controlled MVP | High | Medium | Explicit proxy disclosure and scope boundary | Require human-moderated evidence before broader non-proxy claims/expansion | Product + Research |
| R-012 | Generative-AI output lacks Play-required in-app reporting and evidenced restricted-content prevention | Critical | High | `PROD-14` blocks upload and forbids a decorative/external-only workaround | Approve the intake/data contract, implement product controls, run adversarial and E2E proof, then update Privacy/Data Safety | Product + Security + Android |

## Recently Reduced Engineering Risks

| Area | Current state | Evidence boundary |
|---|---|---|
| Model import interruption/corruption | Reduced by cancellable copy, unique temp files, durable metadata commit/rollback, content-addressed publication, recovery cleanup, and focused tests | Keep OEM document-provider and novel GGUF cases in regression coverage |
| Wrong or malformed GGUF import | Reduced by model identity validation and bounded metadata parsing | Compatibility remains limited to supported PocketAgent runtime targets |
| Concurrent generation/load/unload/cancel ownership | Reduced by the serialized runtime operation coordinator, lifecycle drain, cancellation mapping, and native/runtime tests | Keep recurrence proof when lifecycle or bridge code changes |
| Local chat/model data entering Android backup or OEM transfer | Reduced by `android:allowBackup="false"`, Android 12+ cloud/device-transfer exclusions, and manifest/rules contract coverage | Re-audit OEM transfer behavior and every backup/data-extraction configuration change |
| Setup/readiness metadata drift | Reduced by self-healing provisioning metadata and recovery UI | Continue manifest-outage and device-path evidence |

## Release Impact

1. No active risk currently reverses the `PROD-10` `Promote` decision.
2. `R-008` and `R-012` keep publication readiness blocked until the Play policy
   controls, final package, and real operator metadata exist.
3. `R-001`, `R-002`, `R-003`, and `R-011` constrain rollout size and broader
   claims even after initial publication.

## Review Cadence

1. Review before the final bundle is approved.
2. Review at the end of each 7-day controlled rollout window.
3. Add a new risk only when it changes release, rollout, or claim decisions.
4. Remove or merge mitigated risks instead of accumulating historical status
   prose; git history is the archive.
