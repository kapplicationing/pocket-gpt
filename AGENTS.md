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
2. Use scoped on-demand device flows only for targeted debugging (single crash/hang/regression path), not as a replacement for merge/release gates.
3. For Android testing context, read `.claude/skills/maestro-android-cli/SKILL.md`, `.claude/skills/maestro-android-cli/references/testing-map.md`, and `docs/testing/maestro-android-companion-cli.md` before making changes to test workflows.
4. For build/compile failures, use the global `code-health` skill and the Kotlin quality gate before treating the issue as a Maestro/testing problem.
5. After UI selector changes, run `maestro-android lint` or `maestro-android audit-selectors` before widening to full lanes.
6. For Compose UI refactors, read `.claude/skills/android-compose-ui-audit/SKILL.md` before changing shell/leaf ownership, undo flows, resource migrations, or accessibility semantics.
7. For Android UI/runtime hot-path changes, follow `docs/architecture/android-performance-contract.md` and keep thread ownership, state fanout, and cache lifetime explicit. Annotate every UI state class with `@Immutable`, never put property getters on `@Immutable` data classes, and use the `TextFieldValue` overload for high-frequency text fields.
8. **Never measure performance on the `debug` build.** The `debug` variant carries a ~30-50% Compose recompose tax. Always use `bash scripts/dev/perf-baseline.sh --build` (which assembles the `benchmark` variant). Functional tests (Maestro smoke/journey/screenshot-pack) on `debug` are fine — they verify behaviour, not frame budgets.
9. For broad Android performance follow-up work, use `docs/operations/android-operational-performance-plan.md` to classify the operation first (startup, provisioning, model load/switch, streaming, model library, drawer search, onboarding, voice, tooling) before making localized fixes.

## Scoped On-Demand Device Flow (When And How)

Use this approach when all are true:

1. A failure is device-specific or runtime-specific.
2. You can isolate it to a short user journey.
3. Fast reproduce + immediate log inspection is more valuable than full-lane fan-out.

Search in the existing minimal flows in first 2 lines of the files to see if your case is covered or mostly covered.
- If yes, modify it to fit your exact case.
- If not, create a minimal Maestro flow in `tmp/` that performs only the failing path (ensure to include title and description the first 2 lines).
Use `bash scripts/dev/scoped-repro.sh --flow tmp/<scoped-flow>.yaml` for this loop.
The script builds/installs, runs the flow, captures Maestro output + logcat under `tmp/`, and scans for crash/runtime signatures with app-context filtering. 
Use `--no-build --no-install` for fast reruns and `--serial your-device-id` for multi-device setups.

Do not use scoped repro alone as release evidence. Promote recurring risks into stable tests under `tests/maestro/` and run canonical lanes afterward.
