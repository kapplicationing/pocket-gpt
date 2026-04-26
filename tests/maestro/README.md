# Maestro Flows

Maestro E2E flow assets for Android UI/journey validation.

Source of truth for execution commands and cloud guidance:

- `scripts/dev/README.md`
- `docs/testing/test-strategy.md`
- `docs/testing/runbooks.md`

## Flow Set

1. `scenario-onboarding.yaml`: onboarding completion + runtime-ready baseline bootstrap
2. `scenario-a.yaml`: post-onboarding send-message chat loop smoke
3. `scenario-b.yaml`: post-onboarding advanced controls/tools/diagnostics journey
4. `scenario-c.yaml`: post-onboarding continuity + image-aware journey
5. `scenario-activation-send-smoke.yaml`: activation + send recovery smoke
6. `scenario-download-settings-smoke.yaml`: clean install -> onboarding -> advanced settings -> download controls + model library/runtime split smoke
7. `scenario-first-run-download-chat.yaml`: clean install -> first-run download -> runtime ready -> send smoke
8. `scenario-first-run-gpu-chat.yaml`: clean install -> first-run setup -> enable GPU acceleration -> send smoke
9. `scenario-gpu-probe-status.yaml`: open Advanced controls and drive GPU probe status updates for log-based reason validation
10. `scenario-session-drawer-smoke.yaml`: onboarding skip -> session drawer delete/replacement smoke

## Contract Notes

1. `scenario-onboarding.yaml` is the only onboarding flow in the default maestro lane sequence; scenario A/B/C intentionally assume post-onboarding state and assert onboarding is not visible.
2. The default lane flows are now bootstrapped with shared helper flows instead of depending on cross-flow state leakage. Keep stable contract flows hermetic unless the product claim is explicitly about persistence across app restarts.
3. Screenshot checkpoints feed `tests/ui-screenshots/inventory.yaml` through `lane screenshot-pack`.
4. Release-gate usage currently remains through `devctl` lanes and the physical-device canary; hosted cloud execution is the planned default for machine-verifiable reruns under `QA-14`, but it is not closed parity yet.
5. CI lifecycle gate flow is `scenario-first-run-download-chat.yaml` (`lifecycle-e2e-first-run` job in CI).
6. Prefer the most stable selector that Maestro can actually see. The current Pocket GPT Android build exposes selected Compose `testTag` values as Android resource IDs, so stable controls such as `session_drawer_button`, `composer_input`, and `send_button` should use `id:` selectors. Use visible text when there is no durable resource-id surface.
7. Keep split-surface validation concentrated in dedicated model-management flows and focused Compose/instrumentation tests; do not re-assert the same library/runtime separation in unrelated long journeys.
8. Tag every stable flow. `devctl lane maestro` now supports `--include-tags`, `--exclude-tags`, and `--flows` so you can run one risk slice without cloning/renaming files.
9. Use `python3 tools/devctl/main.py report journey` or `python3 tools/devctl/main.py report screenshot-pack` to inspect the latest structured artifacts without hunting through `tmp/` or `scripts/benchmarks/runs/`.

## Selection Examples

```bash
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane maestro --include-tags smoke
python3 tools/devctl/main.py lane maestro --include-tags model-management
python3 tools/devctl/main.py lane maestro --include-tags smoke --exclude-tags long-running
```

## Scoped Debug Flows

1. For short-lived bug investigations, create minimal one-path flows in `tmp/` and pair them with explicit logcat capture (see `docs/testing/runbooks.md` "Scoped Device Crash Repro").
2. Scoped `tmp/` flows should start with two YAML comment lines (`title` and `description`) so intent is obvious in reruns/artifacts.
3. GPU qualification UI strings can be reason-specific (for example `GPU acceleration unavailable (<reason>)`) or generic build/device text; scoped assertions should account for both when validating compatibility outcomes.
4. Keep `tests/maestro/` for stable, repeatable contract flows that are expected to run in lanes/CI and during release validation.
5. If a scoped debug flow proves a recurring product risk, promote it into `tests/maestro/` and keep selectors/assertions deterministic.
