# Emulator Strict Journey Classification

Last updated: 2026-05-03  
Owner: Engineering

## Scope

Determine whether the emulator-backed strict `journey` lane is still exposing a launch-blocking product defect or only an emulator/harness artifact.

## Commands

1. `ADB_SERIAL=emulator-5554 ADB_MDNS_AUTO_CONNECT=0 python3 tools/devctl/main.py lane android-instrumented`
2. `ADB_SERIAL=emulator-5554 ADB_MDNS_AUTO_CONNECT=0 python3 tools/devctl/main.py lane journey --mode strict --repeats 1 --reply-timeout-seconds 90`

## Preserved Artifacts

1. `tmp/devctl-artifacts/2026-05-03/emulator-5554/android-instrumented/20260503-220509/real-runtime-preflight.json`
2. `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-220817/journey-report.json`
3. `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-220817/journey-summary.md`
4. `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-220817/run-01/screenshots/instrumentation/20260503-220949-1/journey-send-capture.json`
5. `tmp/devctl-artifacts/2026-05-03/emulator-5554/journey/20260503-220817/run-01/screenshots/instrumentation/20260503-220949-1/journey-send-capture-final.png`

## Findings

1. Emulator `android-instrumented` completed its real-runtime preflight successfully, including seeded 0.8B + 1.7B models and required `mmproj` sync.
2. Emulator strict `journey` instrumentation passed.
3. The preserved send-capture report did not show a crash, `Unloaded` runtime, or placeholder stall:
   - `phase=first_token`
   - `first_token_ms=7758`
   - `elapsed_ms=90083`
   - `runtime_status=Ready`
   - `backend=NATIVE_JNI`
   - `active_model_id=qwen3.5-0.8b-q4`
   - `placeholder_visible=false`
4. The lane still returned failure because strict send-capture authority currently requires `phase=completed`; the emulator artifact therefore failed as `Instrumentation send-capture phase=first_token`.

## Classification

Blocker class on emulator: `harness/authority artifact`, not reproduced launch-blocking product behavior.

Rationale:

1. The app booted, provisioned, and reached `Ready` on emulator.
2. The first token arrived well inside the fixed strict timeout window.
3. The remaining failure is that completion was not observed before the 90s strict cutoff, and the lane adapter treats that as a hard failure even when the runtime is already producing output.

## Authority Decision

The emulator should remain a stable parallel diagnostic surface for:

1. provisioning truth,
2. runtime bootstrap truth,
3. first-token/send-liveness truth,
4. and bounded harness regression checks.

The emulator should not remain authoritative for the launch-blocking strict `journey` row until it can repeatedly produce `phase=completed` under the same policy used for the required gate. Current launch authority for that row must stay on preserved hosted/default or physical-device evidence that reaches `phase=completed` with `placeholder_visible=false`.
