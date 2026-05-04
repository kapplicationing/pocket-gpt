# SEC-02 Privacy Claim Parity Audit

Last updated: 2026-05-04
Owner: Product + Security
Support: QA, Marketing
Status: Done

## Objective

Map every privacy claim used in product and marketing materials to an implemented control and evidence artifact.

## Claim Parity Table

| Claim ID | Claim Text | Current Control | Evidence Link | Parity State |
|---|---|---|---|---|
| P-01 | Inference runs on-device by default | Native JNI runtime path + startup checks | `docs/operations/evidence/wp-12/2026-03-05-eng-13-native-runtime-rerun.md` | Verified |
| P-02 | No hidden cloud upload in MVP workflows | Policy/network gating in runtime path | `docs/operations/evidence/wp-12/2026-03-04-eng-17-network-policy-wiring.md` | Verified |
| P-03 | Diagnostics are privacy-safe by default | Diagnostics redaction checks + UX copy | `docs/operations/evidence/index.md` (WP-04 summary), `docs/ux/implemented-behavior-reference.md` | Verified |
| P-04 | Users can manage retention/reset/per-tool privacy controls | Declared in privacy model; full UI parity not yet evidenced in MVP packet | `docs/security/privacy-model.md` | Partial (internal-only claim) |
| P-05 | Runtime/model state is transparent to user | Runtime status + backend visibility in app | `docs/operations/evidence/wp-13/2026-03-05-eng-p1-model-manager-phase2-closure.md` | Verified |

## Publish Rule

1. Only `Verified` claims are publishable externally.
2. `Partial` claims are internal-only until control + evidence parity is complete.

## Acceptance

1. Marketing claim freeze (`MKT-10`) references this table.
2. `PROD-10` matrix claim rows reference only publish-safe claims.
