# Maestro Cloud Benchmarks

Android cloud coverage here is for hosted emulator/API checks, not physical Samsung GPU qualification.

These flows are intentionally separate from `tests/maestro/`.

Purpose:

1. Keep regular lane/CI Maestro coverage short and deterministic.
2. Hold cloud-only benchmark/qualification flows that are useful for hosted-device performance checks.

Current flow set:

1. `scenario-model-management-split-smoke.yaml` (`cloud-smoke`, `model-management`): launch-default ready-shell contract for opening the unified model library and asserting library-only controls after deterministic startup/bootstrap has already cleared the chat gate.
2. `scenario-session-drawer-smoke.yaml` (`cloud-smoke`, `sessions`): clean-install session-shell contract using the `session_drawer_button` resource-id selector.
3. `scenario-runtime-ready-smoke.yaml` (`cloud-smoke`, `runtime-readiness`): clean-install readiness contract that ends once the runtime is ready and the inline chat gate has cleared.
4. `scenario-send-after-ready-smoke.yaml` (`cloud-send`, `send`): hosted send contract kept outside the default smoke tag so cloud smoke stays a short startup/runtime proof path instead of replacing strict journey authority. This flow now uses `shared/bootstrap-cloud-startup.yaml` plus `shared/bootstrap-launch-default-model.yaml` so hosted send proof stays pinned to the launch-default `qwen3-0.6b-q4_k_m` path instead of heuristic `Load last used` recovery. Post-send success is assistant completion plus the shell returning to the idle `Send` label; it does not require an enabled send button after the composer has been cleared.
5. `scenario-hf-url-validation-smoke.yaml` (`cloud-smoke`, `model-management`, `hf-validation`): hosted model-library contract for the HF paste URL surface and deterministic blocked invalid-host reason. It opens Model Library through the debug-only activity entrypoint.
6. `scenario-hf-search-to-candidate-smoke.yaml` (`cloud-fixture`, `model-management`, `hf-search`): hosted fixture contract for debug-open Model Library -> search fixture -> use URL -> check -> candidate preview.
7. `scenario-hf-download-installed-smoke.yaml` (`cloud-fixture`, `model-management`, `hf-download`): hosted fixture contract for debug-open Model Library -> paste fixture URL -> check -> queue -> installed row -> visible `Load`.
8. `scenario-hf-fixture-download-smoke.yaml` (`cloud-fixture`, `model-management`, `hf-fixture`, `downloads`): optional hosted fixture regression for paste fixture URL -> check -> queue -> pause/resume/cancel/retry -> installed row. This flow requires a debug APK built with `pocketgpt.hfFixtureBaseUrl` pointing at a public fixture server and opens Model Library through the debug-only activity entrypoint.
9. `scenario-gpu-cpu-benchmark.yaml` (`cloud-benchmark`, `benchmark`, `long-running`): clean install, first-run provisioning, GPU-on send benchmark, new-session GPU-off send benchmark, and assertion that GPU completes faster than CPU on the same cloud device.

Recommended command:

```bash
bash scripts/dev/maestro-cloud-smoke.sh
bash scripts/dev/maestro-cloud-gpu-benchmark.sh
```

Dynamic Hugging Face fixture command:

```bash
python3 scripts/dev/hf-fixture-server.py --port 8765
cloudflared tunnel --url http://127.0.0.1:8765
bash scripts/dev/maestro-cloud-hf-fixture-smoke.sh --fixture-base-url https://your-tunnel.example
```

Use the public base URL of `scripts/dev/hf-fixture-server.py` here. The wrapper defaults to `scenario-hf-download-installed-smoke.yaml`, the stable paste/check/queue/install proof. Pass `--flow tests/maestro-cloud/scenario-hf-search-to-candidate-smoke.yaml` to probe the search helper separately. Do not use a Maestro Cloud upload or report URL; those pages only show run results and cannot answer the fake Hugging Face `/health`, `/api/models`, `/tree`, or `/resolve` endpoints.

Validate the hosted fixture without building or uploading an APK:

```bash
bash scripts/dev/maestro-cloud-hf-fixture-smoke.sh \
  --fixture-base-url https://your-tunnel.example \
  --preflight-only
```

Devstack-backed fixture command:

```bash
cd tools/hf-fixture-devstack
devstack validate
devstack deploy --wait --build
devstack permissions grant --all
devstack ps
cd ../..
bash scripts/dev/maestro-cloud-hf-fixture-smoke.sh --fixture-base-url https://your-devstack-endpoint.example
```

Other simple exposure options are `ngrok http 8765`, `ssh -R 80:127.0.0.1:8765 nokey@localhost.run`, Tailscale Funnel, or a tiny hosted VM running `scripts/dev/hf-fixture-server.py`. The fixture URL must be public because Maestro Cloud cannot use `adb reverse` or the host machine's `localhost`. The cloud wrapper rejects loopback/private fixture URLs by default and probes the fake search, tree, and byte-range artifact endpoints before building the APK.

Smoke artifact contract:

1. `scripts/dev/maestro-cloud-smoke.sh` writes a timestamped run root under `tmp/maestro-cloud-smoke/`.
2. Each API-level run writes `run-manifest.json`, `status.json`, `cli-output.log`, and `junit.xml` when available.
3. `status.json` also records upload metadata and parsed failed flows so the first hosted failure is machine-readable without reopening raw logs.
4. If Maestro Cloud is blocked on account activation or the company-name prompt, the run status is recorded as `blocked_external_account_setup` instead of being left as an opaque generic failure.

Cloud suite rules:

1. Keep `cloud-smoke` flows under two minutes and focused on deterministic UI contracts.
2. Keep benchmark/qualification journeys out of smoke by tagging them separately and running them only from dedicated scripts/jobs.
3. Prefer stable selectors, but validate them on hosted devices before broad rollout. The current Pocket GPT Android build now exposes selected Compose `testTag` values as Android resource IDs, so hosted smoke flows should prefer `id:` selectors for stable controls that provide them.
4. Do not duplicate broad local-lane coverage here unless Cloud is adding distinct value, such as hosted-device variance or a clean-install contract.
5. Keep live Hugging Face downloads out of `cloud-smoke`; hosted smoke should prove the deterministic acquisition UI and blocked states, while large real downloads stay in explicit `live-hf` device runs.
6. HF cloud flows must use `shared/open-model-library-debug.yaml`, not generic onboarding/bootstrap helpers. A missing HF control after this point should be treated as a model-sheet or selector issue; before this change, the same symptom was usually launch/bootstrap navigation flake.
