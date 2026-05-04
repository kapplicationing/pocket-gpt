# WP-09 QA Release Promotion Checklist (Run Record)

Date: 2026-03-05  
Owner: QA Lead  
Usage: Cohort expansion and release promotion gate

## Gate A: Build and Test Baseline

- [x] Candidate build id + commit hash recorded (`414e900`).
- [x] `bash scripts/dev/test.sh ci` latest result attached in engineering evidence packet.
- [x] Latest `android-instrumented` run id and status attached.
- [x] Latest `maestro` run id and status attached.
- [x] Release-candidate real-runtime app-path smoke run id attached (runtime claims included).
- [x] No newly introduced `S0`/`S1` defects since prior promotion point.

## Gate B: Device and Reliability Signal

- [x] Required-tier device coverage evidence attached.
- [x] Crash/OOM/ANR signal trend acceptable for current cohort window.
- [x] Best-effort device caveat documented for release notes.

## Gate C: Safety/Policy Guardrails

- [x] Latest routing/policy regression evidence remains valid.
- [x] Latest tool-safety regression evidence remains valid.
- [x] No unresolved privacy/security blocker risk.

## Gate D: Operational Readiness

- [x] Incident triage loop active with named on-call owners.
- [x] User feedback channel intake path active and monitored.
- [x] Rollback/hold procedure documented for this promotion step.

## Gate E: WP-13 UX Promotion Readiness

- [ ] Onboarding completion signal for current cohort window attached.
- [ ] Runtime/model status confusion signal reviewed and within threshold.
- [ ] Privacy comprehension signal reviewed and within threshold.
- [x] Natural-language tool path PASS evidence attached from latest QA matrix run.
- [x] Scenario C context follow-up Maestro evidence attached (`tests/maestro/scenario-c.yaml`).

## Promotion Decision

1. QA recommendation (`promote`/`hold`): `hold`
2. Reason summary: WP-13 moderated cohort usability metrics are not yet collected.
3. Conditions (if hold or conditional promote): complete WP-13 run-01 participant thresholds and attach qualitative notes/proof set.
4. Product concurrence (`yes`/`no`): `yes` (hold)
5. Engineering concurrence (`yes`/`no`): `yes` (hold)
6. Decision date (UTC): `2026-03-05`

## Evidence Links

1. QA checkpoint packet: `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-rollout-quality-checkpoints.md`
2. Incident taxonomy + intake policy: `docs/operations/policies/wp-09/wp-09-ux-feedback-taxonomy-intake-policy.md`
3. Weekly QA matrix run: `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md`
4. WP-13 usability packet decision state: `docs/operations/evidence/wp-13/2026-03-04-wp13-usability-gate-run-01.md`
5. WP-12 runtime closure baseline: `docs/operations/evidence/wp-12/2026-03-05-eng-13-native-runtime-rerun.md`
