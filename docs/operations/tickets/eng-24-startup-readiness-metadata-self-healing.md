# ENG-24 Startup Readiness Metadata Self-Healing

Last updated: 2026-04-25
Owner: Engineering
Support: QA
Status: Done

## Objective

Remove startup/readiness failures caused by stale runtime metadata and non-deterministic reinstall/cache-restore behavior.

## Scope

1. Fix `UI-STARTUP-001` and `MODEL_ARTIFACT_CONFIG_MISSING` paths that still wedge startup or required scenario coverage.
2. Make uninstall/reinstall/cache-restore behavior deterministic on the required canary path.
3. Ensure startup checks self-heal stale runtime metadata instead of leaving the app blocked or requiring manual cleanup.
4. Add or tighten regression coverage around the selected behavior.

## Implementation

Host-side implementation landed on 2026-04-25:

1. Runtime config generation no longer lets a missing or stale active pointer dominate when another installed version is loadable.
2. Active-version repair selects the newest loadable installed version with an existing primary file, SHA metadata, provenance, and the expected runtime compatibility tag.
3. Path alias recovery now searches the missing basename, versioned model filename, and canonical model filename before giving up.
4. If no loadable model artifact exists, startup still fails hard; readiness is not faked.

## Acceptance

1. Host tests cover missing active pointer, stale active file, incomplete provenance, incompatible runtime, and stored-entry fallback selection.
2. Startup/readiness paths no longer fail on stale runtime metadata on the canary device.
3. `MainActivityUiSmokeTest` and Maestro startup/readiness flows are stable enough for release reruns.
4. Reinstall/recovery behavior is deterministic on the canary device path.
5. Updated evidence notes identify the healed failure mode and the rerun artifact roots.

## Validation

Completed:

1. `./gradlew :apps:mobile-android:testDebugUnitTest --tests "com.pocketagent.android.runtime.AndroidRuntimeProvisioningStoreSignalsTest"`
2. `./gradlew :packages:app-runtime:test --tests "com.pocketagent.runtime.StartupChecksUseCaseTest"`
3. `bash scripts/dev/test.sh fast`

Final canary and current-window evidence are now preserved under the promoted `QA-11` evidence set, so this ticket is fully closed for the controlled MVP.

## References

1. `docs/operations/evidence/wp-13/2026-03-09-qa-lane-rerun-eng22-a51-revalidation.md`
2. `docs/operations/tickets/eng-20-runtime-cancel-timeout-contract.md`
3. `docs/operations/tickets/prod-10-launch-gate-matrix.md`
4. `docs/operations/play-store-launch-program.md`
