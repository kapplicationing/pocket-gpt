# Maestro Android Companion CLI

PocketGPT uses two related surfaces. Keep them distinct:

1. `maestro-android`:
   the standalone installed CLI from the separate `maestro-android` repo. This is the full command surface (`init`, `lint`, `audit-selectors`, `suggest`, `device ...`, `cloud probe`, `device probe`, and targeted `scoped --type instrumented` work).
2. `python3 tools/maestro_android/main.py`:
   the repo-local compatibility wrapper. Use this only when you explicitly want the subset shipped inside PocketGPT.

Use the standalone CLI for day-to-day operator work. Use the repo-local wrapper as a fallback or when the repo specifically calls for it.

References:

1. Standalone CLI repo docs: see the separate `maestro-android` repository README.
2. Repo-local wrapper docs: [`tools/maestro_android/README.md`](../../tools/maestro_android/README.md)
3. PocketGPT testing ladder and policy: [`docs/testing/README.md`](README.md), [`docs/testing/test-strategy.md`](test-strategy.md), [`docs/testing/runbooks.md`](runbooks.md)

Default guidance to carry into `CLAUDE.md` / `AGENTS.md`:

1. Default Android evidence is emulator + connected device + cloud.
2. Use the smallest command that proves the theory on the right surface.
3. If the same Maestro path is stuck twice, stop repeating it; step back, classify the failure, and pivot.
