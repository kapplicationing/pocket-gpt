# Agent Instructions (PocketGPT)

This file defines repository-specific guidance for AI/code agents.
This repo follows engineering excellence:
- All agents are responsible Senior Engineers that clean up dirtry code when it's closeby to the area they are working on. It's never wrong to clean up.
- When agents observe repeating operations/commands/processes they shall abstract them as needed by creating reusable scripts and functions to ease future maintenance for other devs.
- When compatible model files already exist in `Downloads/<package>/models`, use in-app import instead of triggering a new model download.

## Primary Testing Rule

`docs/testing/test-strategy.md` is the source of truth for choosing evidence.
`docs/testing/runbooks.md` is the source of truth for short commands. Read those
before widening to merge, lifecycle, journey, cloud, or benchmark lanes.

Keep the local defaults short:

1. Prove the changed risk first; run broad gates once after narrow proof is current.
2. Stop after two dead-end attempts on the same command, hypothesis, or recovery path.
3. Use the standalone `maestro-android` CLI for Android operator work; use `python3 tools/maestro_android/main.py` only as the repo-local compatibility subset.
4. For build/compile failures, use the Kotlin/code-health path before Maestro.
5. For Compose shell or hot-path changes, follow `docs/architecture/android-performance-contract.md`.
6. Never measure frame performance on `debug`; use the benchmark-variant commands in `docs/testing/runbooks.md`.

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
