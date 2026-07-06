# Agent Instructions (PocketGPT)

This file defines repository-specific guidance for AI/code agents.
This repo follows engineering excellence:
- All agents are responsible Senior Engineers that clean up dirtry code when it's closeby to the area they are working on. It's never wrong to clean up.
- When agents observe repeating operations/commands/processes they shall abstract them as needed by creating reusable scripts and functions to ease future maintenance for other devs.
- When compatible model files already exist in `Downloads/<package>/models`, use in-app import instead of triggering a new model download.

## Primary Testing Rule

1. Use canonical lanes for broad confidence and merge/release safety:
   - `bash scripts/dev/test.sh fast|merge`
   - `python3 tools/devctl/main.py lane android-instrumented|maestro|journey|screenshot-pack`
2. Default Android evidence is the three-surface matrix defined in `docs/testing/test-strategy.md`: emulator for fast bootstrap/runtime checks, at least one connected device for transport and real hardware state, and Maestro Cloud for hosted supplemental evidence. Do not treat any single surface as sufficient when the change affects startup, provisioning, runtime readiness, selectors, or release confidence.
3. Use scoped on-demand device flows only for targeted debugging (single crash/hang/regression path), not as a replacement for merge/release gates.
4. If the same Maestro command, recovery path, or cloud rerun gets stuck twice, stop repeating it. Step back, classify the problem as harness/bootstrap/product/transport/cloud, then pivot to the smallest higher-signal command or surface in the matrix.
5. For Android testing context, read `.claude/skills/maestro-android-cli/SKILL.md`, `.claude/skills/maestro-android-cli/references/testing-map.md`, and `docs/testing/maestro-android-companion-cli.md` before making changes to test workflows. Use the standalone installed `maestro-android` CLI for the full command surface; `python3 tools/maestro_android/main.py` is only the repo-local subset.
6. For build/compile failures, use the global `code-health` skill and the Kotlin quality gate before treating the issue as a Maestro/testing problem.
7. After UI selector changes, run `maestro-android lint` or `maestro-android audit-selectors` before widening to full lanes.
8. For Compose UI refactors, use the installed `android-compose-ui-audit` skill before changing shell/leaf ownership, undo flows, resource migrations, or accessibility semantics.
9. For Android UI/runtime hot-path changes, follow `docs/architecture/android-performance-contract.md` and keep thread ownership, state fanout, and cache lifetime explicit. Annotate every UI state class with `@Immutable`, never put property getters on `@Immutable` data classes, and use the `TextFieldValue` overload for high-frequency text fields.
10. **Never measure performance on the `debug` build.** The `debug` variant carries a ~30-50% Compose recompose tax. Always use `bash scripts/dev/perf-baseline.sh --build` (which assembles the `benchmark` variant). Functional tests (Maestro smoke/journey/screenshot-pack) on `debug` are fine — they verify behaviour, not frame budgets.
11. For broad Android performance follow-up work, use `docs/architecture/performance/android-operational-performance-plan.md` to classify the operation first (startup, provisioning, model load/switch, streaming, model library, drawer search, onboarding, voice, tooling) before making localized fixes.

## Scoped On-Demand Device Flow (When And How)

Use this approach when all are true:

1. A failure is device-specific or runtime-specific.
2. You can isolate it to a short user journey.
3. Fast reproduce + immediate log inspection is more valuable than full-lane fan-out.

Search in the existing minimal flows in first 2 lines of the files to see if your case is covered or mostly covered.
- If yes, modify it to fit your exact case.
- If not, create a minimal Maestro flow in `tmp/` that performs only the failing path (ensure to include title and description the first 2 lines).
Use `maestro-android scoped --flow tmp/<scoped-flow>.yaml` for this loop.
The standalone CLI builds/installs, runs the flow, captures Maestro output + logcat under `.maestro-android/runs/`, and leaves `flow-state.json` plus failure-context artifacts for triage.
Use `--no-build --no-install` for fast reruns and `--device your-device-id` for multi-device setups.

`bash scripts/dev/scoped-repro.sh` remains compatibility-only. Prefer the CLI path for new guidance.

Do not use scoped repro alone as release evidence. Promote recurring risks into stable tests under `tests/maestro/` and run canonical lanes afterward.
