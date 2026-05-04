# QA Operating Principles

Last updated: 2026-04-26  
Owner: QA + Engineering

This is the short working doctrine for PocketAgent QA.

Use it with:

1. `docs/testing/test-strategy.md` for the canonical test strategy and gate contract
2. `docs/testing/runbooks.md` for runnable procedures
3. `docs/testing/cloud-first-qa-operating-model.md` for the cloud-vs-human split

## Principles

### 0. Default Android evidence is emulator + connected device + cloud

Use:

1. emulator for fast harness/bootstrap proof
2. connected device for real hardware/storage/permission proof
3. cloud for hosted contract proof

Do not silently substitute one surface for another when the changed risk touches startup, provisioning, runtime readiness, selectors, or release confidence.

### 1. Test the real contract, not a remembered contract

Before changing a flow or rerunning a broad lane:

1. read the current UI/runtime code
2. inspect one first-failure screenshot and one first-failure log
3. confirm what the app actually exposes now

Do not trust stale flow wording, old tickets, or older screenshots over the current app code.

### 2. Keep one flow equal to one contract

Examples:

1. `runtime loaded` is not `send ready`
2. shell navigation is not model-management behavior
3. setup recovery is not send/streaming evidence

If one flow tries to prove multiple contracts, the failure will be harder to classify and slower to fix.

### 3. Classify the failure before rerunning

Every failure should be labeled as one of:

1. `flow drift`
2. `product bug`
3. `device harness`
4. `hosted infrastructure`

Only rerun after something in that failure class has changed.

If the same path gets stuck twice, stop and pivot. Choose the smallest higher-signal command or another surface in the matrix before widening again.

### 4. Preserve first-failure artifacts

The first screenshot, logcat, and runner output usually contain the most useful signal.

Retries are for confirmation, not discovery.

### 5. Cloud first for machine-verifiable coverage

Use hosted smoke first when the outcome is deterministic and parallelizable.

Then use local/device lanes for:

1. authoritative gate rows
2. OEM-only behavior
3. final brush

### 6. Local devices are narrow tools, not the default evidence engine

Keep:

1. one authoritative canary
2. one secondary debug/feature device

Do not use multiple attached phones as an excuse to rediscover the same contract manually.

### 7. Authoritative evidence and debugging evidence are different

Use:

1. `devctl lane android-instrumented` and strict `journey` as authoritative gate inputs
2. `devctl lane maestro` as canonical local workflow evidence when the harness is healthy
3. `maestro-android scoped` or targeted runs as debugging tools

Do not promote a scoped repro into gate evidence without moving the signal back into canonical lanes.

### 8. Device pinning is part of correctness

If a command says `--device`, then build, install, and execution must all target that same device.

Cross-device contamination turns useful QA signal into noise.

### 9. Provenance matters

Hosted evidence is only current if the uploaded APK is known to come from the current branch tip.

For every hosted run, record:

1. account label
2. project id
3. upload id
4. upload URL
5. app binary id

### 10. Humans come last, not first

Use people for:

1. moderated usability
2. trust/privacy comprehension
3. ambiguous release judgment

Do not spend human sessions rediscovering broken machine-verifiable flows.

### 11. Agents should own bounded QA slices

The best agent tasks are:

1. one cloud flow family
2. one local harness issue
3. one docs/tooling slice
4. one evidence-sync task

Every agent result should return:

1. files changed
2. validations run
3. unresolved blockers

## Default QA Ladder

1. `doctor`
2. host/unit checks
3. code-truth review for the flow under test
4. one focused hosted or scoped scenario
5. hosted smoke fan-out
6. local authoritative lanes
7. physical-device final brush
8. moderation-backed closure (`human-moderated` preferred, disclosed `AI human-proxy` fallback when needed)

## Anti-Patterns

Do not:

1. rerun full suites while shared helpers are still stale
2. use `send_button enabled=true` as a proxy for runtime readiness
3. treat wireless device harness failures as product failures before the first app-owned step
4. assume download completion means runtime loaded
5. assume the model-chip always opens the full library instead of a preset menu
6. trust old onboarding text or old flow assumptions without checking code
