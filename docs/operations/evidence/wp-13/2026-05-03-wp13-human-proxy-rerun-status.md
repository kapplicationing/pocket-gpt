# WP-13 AI Human-Proxy Rerun Status

Last updated: 2026-05-03  
Owner: QA + Product

## Scope

Refresh the disclosed AI human-proxy packet against the current flow/bundle setup without manufacturing passes.

## Outcome

Current result: `hold`

This rerun produced fresh provenance and current-path classification, but it did not produce a new full four-tester proxy matrix.

## What Changed

1. `tools/qa-agents/run_ai_tester.py` now resolves the live physical-device serial from `adb devices -l` by model before install/Maestro, instead of depending on stale `_adb-tls-connect._tcp` aliases.
2. The corrected S22 path reached live install and `7001` reverse-port setup on `192.168.1.38:36483`.
3. The device path still stalled in `scripts/dev/maestro-local-bootstrap.sh` before any flow output or proxy skeleton was written.
4. The cloud path preserved fresh upload provenance for the current APK, but the runner still did not return a hosted verdict on the current packet path.

## Preserved Current Artifacts

1. `tmp/qa-agents/device-s22/20260503T152401Z/`
2. `tmp/qa-agents/cloud-1/20260503T152529Z/onboarding.log`
3. `tmp/qa-agents/cloud-1/20260503T153000Z-async/onboarding.log`
4. `tmp/qa-agents/cloud-2/20260503T153000Z-async/onboarding.log`

## Findings

### Device Path

1. `adb devices -l` currently shows the S22 and A51 as connected on live TCP serials (`192.168.1.38:36483`, `192.168.1.44:37643`), so the old runner alias was stale.
2. After live-serial resolution, the S22 runner succeeded at APK install and reverse-port setup.
3. The rerun then stalled inside local Maestro bootstrap before writing `onboarding.log`, `trip-report.skeleton.json`, or a bundle manifest under `tmp/qa-agents/device-s22/20260503T152401Z/`.
4. After two failures on the same device execution path, the rerun pivoted from repetition to classification.

### Cloud Path

1. The current cloud rerun preserved fresh hosted provenance for the current APK in `tmp/qa-agents/cloud-1/20260503T152529Z/onboarding.log`.
2. That log records current app binary id `e573dd0d2a5c26f1f70f355d8f46a178c7e458c5` and upload id `mupload_01kqq747x9ea0ays8hv7cy2nzv`.
3. The command did not return a hosted verdict before manual pivot, so the current packet path cannot yet claim a fresh cloud pass or fail from this rerun.
4. Async follow-up probes showed that the current CLI forbids `--format` with `--async`, which is useful classification truth for the packet path but not a replacement for verdict-bearing evidence.

## Decision Use

Use this note together with `docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md`.

1. The packet still truthfully remains `hold`.
2. The retained last-complete proxy matrix remains the only measured four-tester baseline in the repo.
3. The current rerun adds fresher tool-path truth: current build provenance is fresh, device transport aliases are fixed, but the device bootstrap and cloud verdict-return legs are still blocking a fresh proxy matrix.
