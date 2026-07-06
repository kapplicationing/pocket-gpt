# Evidence Index

Last updated: 2026-07-06

This index is the canonical retained evidence inventory after prune.

## Retention Policy

1. Keep active evidence for `WP-09`, `WP-12`, and `WP-13`.
2. Prune superseded notes not referenced by active roadmap/PRD/ticket/incident artifacts.
3. Keep one concise note set per active issue stream; avoid duplicate narrative notes for the same closure.
4. Full history remains recoverable via git.

## Retained Evidence Sets

### Current CI And Nightly Baseline (`main`, 2026-07)

- `main` is synchronized with `origin/main` at `0ce97c7f37fdf115d0d4ad2c1f0f71a9e2443522` (`Guard hardware truth lane runner availability (#9)`).
- Latest main push CI passed: [CI run 28741453466](https://github.com/kapplicationing/pocket-gpt/actions/runs/28741453466), created 2026-07-05T12:54:11Z.
- Latest CodeQL run passed: [CodeQL run 28741453204](https://github.com/kapplicationing/pocket-gpt/actions/runs/28741453204), created 2026-07-05T12:54:11Z.
- July 6 scheduled nightly passed at [Nightly Validation run 28769302147](https://github.com/kapplicationing/pocket-gpt/actions/runs/28769302147), created 2026-07-06T05:11:22Z. Emulator instrumentation, Maestro smoke, and first-run lifecycle jobs passed on API 30/33/34 coverage as configured. Treat the Maestro Cloud job in this run as configuration coverage only because `MAESTRO_CLOUD_API_KEY` was absent; a skipped cloud lane is not hosted product evidence.
- Latest hardware-truth scheduled run before the runner guard landed was cancelled at [Hardware Truth Lane run 28731184944](https://github.com/kapplicationing/pocket-gpt/actions/runs/28731184944). PR [#9](https://github.com/kapplicationing/pocket-gpt/pull/9) changed that lane to report runner availability explicitly before claiming hardware evidence.

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
