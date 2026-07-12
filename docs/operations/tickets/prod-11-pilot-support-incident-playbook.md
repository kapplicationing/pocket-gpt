# PROD-11 Pilot Support and Incident UX-Ops Playbook

Last updated: 2026-07-11
Owner: Product Ops
Support: QA, Marketing, Engineering
Status: Done

## Objective

Give controlled-MVP testers one safe support path and give the release team a repeatable incident, escalation, claim-pause, and weekly-report process.

## Published Intake

1. Public product issues: `https://github.com/kapplicationing/pocket-gpt/issues`
2. Support and private contact: `mohamad.kamar.msk@gmail.com`
3. User guidance: `SUPPORT.md`
4. Public issue form: `.github/ISSUE_TEMPLATE/support.md`
5. Privacy policy: `PRIVACY.md`

Public issues must not contain prompts, responses, images, audio, model files, credentials, private URLs, or unreviewed diagnostics. Suspected privacy/security incidents go to email with subject `PocketAgent private report`, never a public issue.

## Severity And Response Targets

| Severity | Definition | Acknowledge | Mitigation / Update |
|---|---|---:|---:|
| `S0` | Suspected security/privacy breach or user-data loss | 1 hour | Assign incident owner immediately; pause affected rollout/claim |
| `S1` | Setup, chat, or another core launch workflow is blocked without safe recovery | 4 hours | Workaround or status update within 24 hours |
| `S2` | Degraded but usable behavior | 1 business day | Prioritize in weekly triage |
| `S3` | Question, cosmetic defect, or improvement request | 2 business days | Route to normal backlog |

These are controlled-pilot operating targets, not contractual service guarantees.

## Triage Flow

1. Product Ops acknowledges the report, removes or asks the reporter to remove public sensitive content, and assigns an incident id.
2. QA records version name/code, device/Android version, affected workflow, runtime state/error code, reproduction steps, and redacted evidence.
3. Classify the first failure as `product`, `harness/bootstrap`, `device transport`, `hosted/infra`, or `selector/flow drift`.
4. Product assigns severity, accountable owner, next-update time, and any tester workaround.
5. Engineering or Security handles the narrow proof and fix. Do not ask the reporter to uninstall until local-data consequences are explicit.
6. Marketing pauses any copy block whose claim parity is uncertain. Product pauses rollout for every `S0` and every unmitigated `S1`.
7. Close only after the reproduction no longer fails, recovery guidance is current, affected claims are safe, and the reporter-facing update is sent.

## User-Facing Copy Rules

1. State the known condition and next action; avoid speculation.
2. Include the stable error code when available.
3. Prefer `Retry`, `Refresh runtime checks`, and `Fix model library` over restart/uninstall guidance.
4. Say when model discovery/download needs network access; do not weaken the on-device inference claim.
5. Never request raw user content when redacted diagnostics or deterministic reproduction is enough.

## Weekly Rollout Review

Use `docs/operations/templates/support/weekly-rollout-summary.md`. Product Ops owns the rollup and records:

1. incident count by severity and product area;
2. open owner, mitigation, and next-update time;
3. SLA misses and repeat incidents;
4. paused claims or rollout changes;
5. send completion/timeout signal and first-week activation signal when available; and
6. `continue`, `pause`, or `rollback` recommendation.

Promotion or expansion requires zero open `S0` incidents and zero unmitigated `S1` incidents. Release Ops must check both public issues and the private support inbox immediately before rollout because Git cannot prove the live incident count.

## Acceptance Evidence

1. Public support page, email, issue template, and privacy policy are defined in the repository.
2. Severity, response, escalation, data-handling, claim-pause, rollback, and closure rules are explicit.
3. A reusable weekly rollout summary exists.
4. `PROD-13` and Play metadata reference the same support values.
