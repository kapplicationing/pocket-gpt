# ENG-23 Native Runtime Provisioning SIGILL Unblock

Last updated: 2026-04-25
Owner: Engineering
Support: QA, Product
Status: Done for host-side mitigation; pending final device evidence under `QA-11`

## Objective

Resolve the native `SIGILL` risk in provisioning preflight so required lanes can run as valid release evidence again.

## Failure Signature

`RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks`

Latest retained blocker:

`Fatal signal 4 (SIGILL)` in `libpocket_llama.so` during provisioning preflight / runtime load.

## Scope

1. Root-cause the native crash in the provisioning preflight path.
2. Patch the owning native/runtime layer without weakening the startup-check contract.
3. Validate the fix inside lane preflight and on one narrow physical-device canary.
4. Preserve logcat, crash signature, and artifact roots in retained evidence.

## Implementation

Host-side mitigation landed on 2026-04-25:

1. `NativeJniLlamaCppBridge` now selects the conservative baseline native library first by default.
2. CPU-optimized native-library candidates are gated behind explicit `POCKETGPT_ENABLE_OPTIMIZED_NATIVE_LIBRARIES=1`.
3. Explicit custom library names still bypass auto-selection for targeted experiments.

This targets the retained `SIGILL` class without removing optimized build artifacts or weakening startup checks.

## Acceptance

1. Host tests prove conservative native library selection and fallback ordering.
2. Provisioning preflight no longer crashes the app process on the physical canary.
3. `RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks` passes inside required-lane preflight.
4. `devctl gate merge-unblock` and `promotion` no longer fail at provisioning preflight for this reason.
5. Updated evidence note records the fixed signature, rerun commands, and artifact roots.

## Validation

Completed:

1. `./gradlew :packages:native-bridge:test --tests "com.pocketagent.nativebridge.NativeJniLlamaCppBridgeTest"`
2. `bash scripts/dev/test.sh fast`

Blocked:

1. Active `QA-11` reruns have moved past provisioning preflight, but the broader required-lane proof still stops at the runtime-ready `Unloaded` path in lifecycle/startup flows.
2. Device preflight proof remains under `QA-11`.

## References

1. `docs/operations/evidence/wp-13/2026-03-10-qa-gate-policy-validation-and-a51-rerun.md`
2. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
3. `docs/operations/release-unblock-workstreams.md`
4. `docs/operations/play-store-launch-program.md`
