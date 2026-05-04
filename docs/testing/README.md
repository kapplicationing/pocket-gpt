# Testing Docs Index

Canonical ownership by concern:

1. Engineering workflow and command contract: `scripts/dev/README.md`
2. Canonical test strategy and release gates: `docs/testing/test-strategy.md`
3. Task-focused execution runbooks: `docs/testing/runbooks.md`
4. Screenshot capture/manual regression workflow: `docs/testing/screenshot-regression-workflow.md`
5. Weekly QA UI regression matrix (`UI-01..UI-15`): `docs/testing/wp-09-ui-regression-matrix.md`
6. Maestro flow assets and lifecycle flow contract: `tests/maestro/`
7. CI required lifecycle gate job: `lifecycle-e2e-first-run` in `.github/workflows/ci.yml`
8. Agent-level testing policy and scoped debug loop guardrails: `AGENTS.md`
9. `devctl gate` wrappers for merge-unblock/promotion policy: `tools/devctl/gates.py`
10. Runtime tuning debug workflow and diagnostics interpretation: `docs/testing/runtime-tuning-debugging.md`
11. Pocket-GPT vs PocketPal parity benchmark runbook: `docs/testing/pocketpal-parity-benchmark.md`
12. Maestro report helpers: `python3 tools/devctl/main.py report journey` / `python3 tools/devctl/main.py report screenshot-pack`
13. Standalone Maestro Android CLI (installed from the separate `maestro-android` repo): `maestro-android init|doctor|devices|start-device|lane|scoped|lint|audit-selectors|device|report|trace|cloud ...`
14. Standalone-vs-wrapper distinction and PocketGPT integration: `docs/testing/maestro-android-companion-cli.md`
15. Repo-local compatibility wrapper reference: `tools/maestro_android/README.md`
16. Repo-local skill for agent/test context: `.claude/skills/maestro-android-cli/SKILL.md`
17. Repo-local testing ladder: `.claude/skills/maestro-android-cli/references/testing-map.md`
18. Runtime performance E2E playbook and time-saver commands: `docs/testing/runtime-performance-e2e-playbook.md`
19. Cloud-first QA operating model and human-vs-machine gate split: `docs/testing/cloud-first-qa-operating-model.md`
20. QA operating doctrine for the team: `docs/testing/qa-operating-principles.md`
21. Launch readiness snapshot runbook: `bash scripts/dev/launch-readiness.sh` and `docs/testing/runbooks.md`
22. Generated contract index: `docs/testing/generated/README.md`
23. Generated QA selector/copy truth: `docs/testing/generated/launch-flow-truth.md`

Consolidation rule:

- Avoid duplicate command/process docs outside this index, `scripts/dev/README.md`, and the canonical strategy/runbook docs.
