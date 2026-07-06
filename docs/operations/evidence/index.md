# Evidence Index

Last updated: 2026-07-06

This index is the canonical retained evidence inventory after prune.
Machine-readable retained baselines live in `docs/operations/evidence/evidence-ledger.json`.
Live CI/nightly/hardware status belongs in GitHub Actions, not in this mutable index.

## Retention Policy

1. Keep active evidence for `WP-09`, `WP-12`, and `WP-13`.
2. Prune superseded notes not referenced by active roadmap/PRD/ticket/incident artifacts.
3. Keep one concise note set per active issue stream; avoid duplicate narrative notes for the same closure.
4. Full history remains recoverable via git.

## Retained Evidence Sets

### Fixed Baselines (`main`, 2026-07)

- Retained CI, CodeQL, nightly, hardware-runner guard, and controlled-MVP promotion baselines are recorded in `docs/operations/evidence/evidence-ledger.json`.
- Treat those entries as fixed evidence references, not as a moving pointer to current `main`.
- For current run status, inspect GitHub Actions or regenerate local readiness output with `bash scripts/dev/launch-readiness.sh`.

### WP-09 (`docs/operations/evidence/wp-09/`)

- Retained notes support active QA rollout and UX evidence references.
- Template-only triage/summary notes were pruned in this sync pass; canonical templates now live in `docs/operations/templates/wp-09/` and policy canon lives in `docs/operations/policies/wp-09/`.

### WP-12 (`docs/operations/evidence/wp-12/`)

- Production-claim-critical runtime/privacy notes retained.

### WP-13 (`docs/operations/evidence/wp-13/`)

- Retained notes support active UX-quality, runtime journey, and launch-gate references.
- Superseded rerun notes were pruned where newer rerun/gate notes carry the same decision signal.

## Historical Summaries (Pruned)

- `WP-01`: CI/bootstrap baseline completed; detailed notes pruned.
- `WP-02`: first real Android runtime/device pass completed; detailed notes pruned.
- `WP-03`: artifact+benchmark reliability closure completed; detailed notes pruned.
- `WP-04`: routing/policy/diagnostics hardening completed; detailed notes pruned.
- `WP-05`: tool runtime safety closure completed; detailed notes pruned.
- `WP-06`: memory+image productionization closure completed; detailed notes pruned.
- `WP-07`: soak/go-no-go closure completed; detailed notes pruned.
- `WP-08`: positioning/asset lock pass completed; detailed notes pruned.
- `WP-11`: Android MVP UI gate closure completed; detailed notes pruned.
