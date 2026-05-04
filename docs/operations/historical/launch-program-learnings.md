# Launch Program Learnings

Last updated: 2026-04-26  
Owner: Tech Lead + Product

This document records repeated patterns from the current launch-unblock effort so the team can keep improving the program while work is still active.

This file is historical. Use `docs/operations/execution-board.md`, `docs/roadmap/current-release-plan.md`, and `docs/operations/play-store-launch-program.md` for the active controlled-MVP state.

Mutable status remains in `docs/operations/execution-board.md`. This file is for patterns and controls, not ticket state.

## Current Learnings

### 1. The blocker chain moves as soon as the first real defect is cleared

What happened:

1. The retained provisioning `SIGILL` class stopped being the first live failure after the host-side mitigation landed.
2. The next retained blocker also turned out to be misframed: the active provisioning/setup failure was missing multimodal projector (`mmproj`) sync in `devctl` preflight for claim-safe single-image coverage, not a new generic native-runtime mystery.
3. Once the local `mmproj` sync path was corrected, the next deterministic blocker moved again into the local wireless Maestro bootstrap path on Samsung canaries.

What to do again:

1. Re-baseline the board and handover docs as soon as the first failing step changes.
2. Do not keep leading with a historical blocker once the active first failure has moved.
3. Treat setup/runtime packaging bugs and harness/bootstrap bugs as different blocker classes; collapsing them together slows down triage.

### 2. Canonical wrappers are more trustworthy than raw tool entrypoints

What happened:

1. Hardened `devctl` lane entrypoints preserved preflight, clear-state handling, and artifact contracts better than direct raw tool invocation.
2. Raw exploratory runs were still useful for fast local debugging, but they were easier to misread as gate evidence.

What to do again:

1. Use canonical lane wrappers for any evidence that might influence merge, promotion, or launch status.
2. Keep scoped/raw repro loops clearly marked as debugging-only.

### 3. Cloud-first works best for deterministic coverage fan-out

What happened:

1. Hosted runs are the easiest way to expand machine-verifiable coverage in parallel and keep artifacts comparable.
2. Cloud runs became much more valuable once the flows were tag-scoped, short, and aligned to the same pass-id/report/screenshot/logcat contract expected by launch gates.
3. The current cloud/device work is useful because it is converging on the same launch-readiness packet shape rather than inventing a second reporting path.

What to do again:

1. Run cloud-hosted machine-verifiable coverage before widening to manual device confirmation.
2. Keep smoke flows narrow and move benchmark/qualification work into separate hosted scripts.

### 4. Real devices still matter, but they should be narrow

What happened:

1. Physical devices exposed OEM/accessibility behavior that hosted runs could not fully explain.
2. Broad device-first exploration consumed time without improving the deterministic evidence contract.
3. The current rerun path showed the value of the canary rule: the device confirmed the blocker chain had moved beyond provisioning preflight, but it was not the right place to rediscover the whole matrix.

What to do again:

1. Keep one narrow canary device path for OEM/runtime confirmation and final brush.
2. Do not treat ad hoc device runs as the main evidence engine when cloud can prove the same contract first.

### 5. Agents are most effective when the scope is confined and the output contract is explicit

What happened:

1. Subagents were most useful when each one owned a narrow surface area such as cloud flow hardening, gate alignment, or a single Maestro bootstrap problem.
2. Review cost stayed manageable when each subagent returned concrete files changed, validation run, and unresolved risks.
3. Parallel subagent work was most productive when one owner handled launch canon/claims, one handled cloud-first QA docs/tooling, and one handled runtime/blocker analysis instead of mixing all three in one lane.

What to do again:

1. Give each subagent one bounded domain and a written evidence/output contract.
2. Require every subagent result to state what changed, how it was verified, and what still blocks completion.

### 6. First-failure artifacts matter more than retries

What happened:

1. Environment-sensitive lifecycle failures were easier to understand from the first failing screenshot/log pair than from later reruns.
2. Retrying too early risked hiding the most informative failure mode.

What to do again:

1. Preserve first-failure screenshots, logcat, and runner output before retrying.
2. Teach gate docs and triage loops to prefer the earliest valid failure artifact set.

### 7. Readiness/bootstrap failures hide more downstream signal than they first appear

What happened:

1. Provisioning `SIGILL`, stale runtime metadata, missing `mmproj` sync in preflight, onboarding selector drift, and wireless Maestro bootstrap instability were not isolated bugs; each one blocked multiple downstream gate rows at once.
2. Teams repeatedly lost time when a downstream send/recovery failure was discussed before the upstream readiness/bootstrap contract was green.

What to do again:

1. Treat readiness/bootstrap defects as matrix multipliers and clear them before debating downstream release evidence.
2. Update the board, gate matrix, and handover docs as soon as the first failing step moves.

### 8. Human-required evidence must stay narrow and late

What happened:

1. Moderated usability becomes noisy when the deterministic path is still unstable.
2. Automation and agent analysis can remove a large amount of repetitive QA work, but cannot answer trust/comprehension questions.

What to do again:

1. Keep WP-13 focused on onboarding, recovery, privacy comprehension, and final ambiguity resolution.
2. Start moderated sessions only after the machine-verifiable path is materially green.

### 9. Claim-safe launch docs need a second lens beyond implementation truth

What happened:

1. The repo can drift into describing engine capability instead of the locked launch promise, especially around voice, tool richness, and image scope.
2. The launch-safe statement is often narrower than the implementation reality: prompt-first tools are the claim surface, single-image attach is the image boundary, and voice is limited beta rather than broad launch copy.

What to do again:

1. Keep launch canon explicit about what is in scope, what is limited beta, and what is intentionally excluded from public claims.
2. Recheck claim-sensitive docs whenever implementation capability expands faster than launch readiness.

### 10. Flow automation must follow code-truth, not stale ticket or doc wording

What happened:

1. The test harness had drifted toward a split model-management contract that no longer matches the shipped UI.
2. The actual app flow is a unified `Model library` sheet entered from the top-bar model chip, the composer `Setup` path, or `Open model library` recovery actions.
3. The most reliable way to correct the drift was reading `PocketAgentTopBar.kt`, `ModelSheet.kt`, and `ChatComposerBar.kt`, then confirming with a real screenshot artifact before changing the flows.

What to do again:

1. Audit the real UI code and one first-failure screenshot before rewriting Maestro flows.
2. Prefer text contracts that match visible user actions when resource IDs are not consistently exposed to Maestro.
3. Treat stale flow docs as a hypothesis until the current app code confirms them.

### 11. Manual model-library download is not the same contract as `Get ready`

What happened:

1. The app uses a simple-first setup contract where `Get ready` is the primary blocked-state CTA and the unified `Model library` is the broader management/recovery surface.
2. The app auto-activates and auto-loads through the `Get ready` path by setting `pendingGetReadyActivation`.
3. Manual download from the unified model library does not automatically follow the same load path.
4. The runtime-ready helpers initially assumed download completion alone would make `Unloaded` disappear, which was too optimistic.

What to do again:

1. Distinguish between the simple-first `Get ready` path and the broader `Model library` management path when writing setup automation or launch docs.
2. After manual download, explicitly refresh the library or runtime checks and look for the real next action instead of assuming the runtime is already loaded.
3. Keep contract tests on the helper so it does not slide back to the wrong activation model.

### 12. Wireless physical-device Maestro is currently a harness risk on Samsung canaries

What happened:

1. The Samsung canaries exposed local Maestro instability that is separate from the app-under-test.
2. One run lost app foreground entirely during the flow; later canonical/scoped reruns consistently failed with `io.grpc.StatusRuntimeException: UNAVAILABLE` and `Connection refused: localhost:7001` before app logic began.
3. `devctl lane maestro` is still valuable here because it proves device preflight, install, and runtime seeding can complete before the wireless Maestro bootstrap fails.
4. This means the current physical-device failures are not clean evidence against the corrected launch flows.

What to do again:

1. Keep hosted/cloud Maestro as the primary authority for machine-verifiable flow closure.
2. Treat wireless physical-device Maestro failures as harness/environment blockers until the first failing step is clearly inside the app.
3. Do not let local wireless Maestro overwrite the authority split: `android-instrumented` and strict `journey` remain required gate sources, but the Samsung wireless canaries are currently only suitable for diagnosis and final brush once the harness is stable.
4. Use physical devices for final brush and OEM confirmation only after the cloud path is materially green.

### 12A. Claim-safe multimodal coverage depends on packaging, not just UI exposure

What happened:

1. Single-image attach stayed in scope, but the launch program initially treated multimodal coverage as if the main `.gguf` alone proved the feature.
2. The actual deterministic blocker in the setup/provisioning path was missing `mmproj` sync in `devctl` preflight, which meant local evidence could under-prove the launch claim even when the UI surface existed.
3. Once that packaging gap was understood, the blocker chain became easier to reason about and no longer looked like a generic provisioning mystery.

What to do again:

1. Treat multimodal companion-artifact sync as part of the core launch contract for single-image attach.
2. Keep feature docs, gate docs, and PM handover explicit that the image claim is only safe when both the model artifact and its required multimodal companion are covered by setup/provisioning evidence.
3. Prefer precise packaging language over broad "vision is implemented" shorthand in launch-readiness reviews.

### 13. Branch hygiene needs to stay linear during a launch program

What happened:

1. The launch program work was isolated into `codex/launch-readiness-implementation` while the stack was still moving, but local `main` was later fast-forwarded to the same tip.
2. That shifted the operational risk from "did we merge the launch branch back?" to "are we describing local publication truth correctly versus `origin/main`?"
3. Older `codex/*` branches were already subsumed, while one unrelated omnibus `cursor/*` branch remained separate and unsafe to merge wholesale.
4. Without an explicit branch audit, it would have been easy to overstate branch sprawl, miss the local-vs-remote publication gap, or keep docs describing a fake unmerged state after local `main` had already absorbed the stack.

What to do again:

1. Keep the launch branch linear while the stack is volatile, then fast-forward local `main` to it early once the stack stabilizes.
2. Audit local `main` versus `origin/main` before describing work as merged or published.
3. Audit other visible branches before cherry-picking anything during release closeout.
4. Preserve the launch stack as one linear unit instead of recreating it through ad hoc cherry-picks at the end.
5. Once local `main` already contains the launch stack, describe the remaining operation as publication to `origin/main`, not another internal merge.

### 14. Simple-first completion is not the same thing as `advancedUnlocked` proof

What happened:

1. The `S-D1` launch row combines two related contracts: the user finishes the simple-first lane, and the app exposes the advanced unlock path cleanly enough for first-session evidence.
2. Onboarding selector drift was fixed, but that did not close the row because the runtime-ready path can still leave the app `Unloaded` before the first-session journey reaches a trustworthy advanced-controls state.
3. That made it too easy to talk about `S-D1` as mostly an onboarding row when the live blocker had already shifted into startup/readiness.

What to do again:

1. Keep `S-D1` phrased as a combined simple-first plus advanced-unlock contract, not as pure onboarding completion.
2. Do not mark the row materially healthy until one current-window evidence packet proves both halves of the contract.
3. Update the row text as soon as the first live blocker moves so PM and ops keep triaging the right part of the experience.

### 15. Cloud evidence is only trustworthy when it uses a rebuilt APK from the branch tip

What happened:

1. Hosted reruns became much more trustworthy once the cloud path used an APK rebuilt from the branch tip under review instead of assuming an older uploaded build still represented current code.
2. A stale uploaded artifact can make cloud evidence look current when it is actually proving an earlier state.
3. That risk is easy to miss because the cloud runner can still return crisp artifacts even when the binary is outdated.

What to do again:

1. Treat rebuilt branch-tip artifacts as part of the cloud evidence contract, not as optional hygiene.
2. Do not count hosted pass/fail output as current-window launch evidence unless the uploaded APK is known to come from the branch tip under review.
3. Keep the artifact provenance note with the pass id/report bundle so PM, QA, and release review can tell which code state the cloud run actually proved.

### 16. Maestro Cloud polling failures are infrastructure blockers until hosted results exist

What happened:

1. Multiple hosted uploads completed successfully, but Maestro Cloud intermittently either returned `Failed to fetch the status of an upload ... Status code = null` before any hosted flow results or JUnit output were returned, or accepted corrected uploads and then left them stuck at `running` during bounded polling with no hosted verdict returned.
2. In at least one earlier run, the CLI then fell through into a local debug-log packaging exception instead of leaving a clean pass/fail artifact.
3. That means the upload exists, but the run is not yet trustworthy product evidence because the hosted result never came back.

What to do again:

1. Classify this pattern as hosted infrastructure blocking, not as a product-flow failure.
2. Preserve the upload id, upload URL, and app binary id so the hosted run can still be inspected externally if needed.
3. Keep the cloud/device split explicit in status updates so a missing hosted verdict is not confused with a failed app flow.

### 17. Local Maestro bootstrap on wireless Samsung devices needed both IPv4 binding and stale-process cleanup

What happened:

1. The first live local Maestro failure on Samsung was a gRPC bootstrap refusal at `localhost:7001` before app logic.
2. Forcing Java to prefer IPv4 localhost binding inside the Maestro harness changed the failure immediately from bootstrap refusal to actual app-flow execution.
3. Old `maestro.cli.AppKt` processes on the same wireless serial could remain resident across reruns and poison fresh attempts if they were not cleared first.
4. Once the harness cleaned stale Maestro processes before each attempt, the retry path became materially more stable.

What to do again:

1. When a local Maestro run fails before app logic, test the launcher/transport before touching app code.
2. Clear stale Maestro/java sessions for the same serial before retrying a flow.
3. Prefer an explicit Java IPv4 localhost preference when the driver bootstrap keeps falling over `localhost/[::1]:7001`.

### 18. Readiness means the send button is visible, not that it is enabled before typing

What happened:
1. Shared helpers had drifted into treating `send_button enabled=true` as a proxy for runtime readiness.
2. In the real app, readiness and sendability are separate: the shell can be healthy while the composer is empty, and the button state changes with text entry and gate status.
3. That mismatch created false negatives in both local and hosted flows.

What to do again:
1. Treat `runtime loaded`, `shell visible`, and `send ready` as separate contracts.
2. Only assert `send_button enabled=true` inside flows that already typed text and are explicitly testing send behavior.
3. Keep shared runtime helpers focused on the absence of blocking states, not on post-typing action state.

### 19. Contract-lock before reruns saves more time than broad “try again” loops

What happened:
1. Several repeated failures were not new app regressions; they were stale assumptions inside shared helpers or scenario glue.
2. Broad reruns before fixing those assumptions created churn without improving confidence.
3. Once the team paused to read the UI code, inspect one screenshot, and correct the shared contract, the next reruns became much more informative.

What to do again:
1. Stop after the first repeated failure and verify the contract against code-truth before widening.
2. Rerun only the smallest affected scenario set after the contract fix.
3. Do not use full-lane reruns as the primary debugging tool.

### 20. macOS Bash compatibility matters for local operator tooling

What happened:
1. The new dual-account cloud wrappers initially used `mapfile` and `local -n`, which are not available in the macOS Bash version on the operator machine.
2. The scripts looked correct in isolation but failed at runtime on the actual workstation.
3. This delayed the intended cloud parallelism until the wrappers were rewritten to portable Bash.

What to do again:
1. Treat macOS Bash 3 compatibility as part of the local tooling contract unless the script explicitly requires a newer shell.
2. Run syntax and one real command-path validation on the operator machine, not just static review.
3. Prefer simpler shell patterns when a wrapper is intended for frequent engineering use.

### 21. Device pinning must apply to install as well as execution

What happened:
1. Narrow `maestro-android test --device ...` runs correctly targeted the requested serial for Maestro execution, but Gradle install still went to both attached devices.
2. That caused cross-device contamination and made local diagnosis noisier than it needed to be.
3. The fix was to inject `-Pandroid.injected.device.serial=<serial>` during install, not just export `ADB_SERIAL` and `ANDROID_SERIAL`.

What to do again:
1. Treat build, install, and execution as one device-pinning contract.
2. Audit every wrapper that says “--device” to ensure it pins install as well as the test runner.
3. Add regression tests for device-pinned install paths.

### 22. Multi-account cloud parallelism is useful only when the status path is just as clear

What happened:
1. Running hosted smoke across two accounts increased useful concurrency immediately.
2. But the operational value depended on having an equally clear way to poll each upload and associate it with the right project/account.
3. Without the upload id, project id, and label captured together, the parallel runs would have been harder to interpret than a single serial run.

What to do again:
1. Record account label, project id, upload id, and upload URL for every hosted run.
2. Keep single-account reruns explicit when isolating one issue, and use parallel fan-out only for stable smoke suites.
3. Treat hosted queue latency separately from product-flow failures in every status update.

### 23. A corrected local `journey` kickoff can still prove only harness truth, not product truth

What happened:

1. The strict `journey` kickoff generator was corrected to follow the real shipped UI contract (`Get ready`, `Model library`, `Load last used`, `refresh_button`, `send_button`) instead of the stale `Runtime: Ready` wording.
2. After that correction, the canary rerun still failed before app logic in the same Maestro bootstrap layer with `io.grpc.StatusRuntimeException: UNAVAILABLE` and `Connection refused: localhost:7001`.
3. That means the new rerun was still useful, but only as confirmation that the blocker class remains harness/bootstrap rather than a product-flow regression.

What to do again:

1. When a corrected flow still fails before app logic, record that as stronger evidence about the harness class instead of misreporting it as a fresh product failure.
2. Preserve the corrected kickoff artifact alongside the failure log so reviewers can see that stale selector drift is no longer the reason the lane is red.
3. Keep strict `journey` open as missing current-window proof until the harness-class blocker is cleared, but do not reopen already-fixed app contracts without new app-side evidence.

### 24. Local authoritative onboarding proof and hosted send proof are different closure steps

What happened:

1. The launch program was slowed by treating first-session onboarding proof as if it lived only in Maestro/cloud flows.
2. The current codebase now carries a dedicated authoritative onboarding instrumentation contract (`MainActivityAuthoritativeOnboardingInstrumentationTest`) and that changes the blocker chain: local onboarding proof is now real even while hosted `send-after-ready` still lacks a clean hosted verdict.
3. That means `S-D1` and the broader launch hold should not be described as if onboarding contract coverage is still missing from the codebase.

What to do again:

1. Separate "the app has an authoritative local contract for onboarding" from "the current-window lane set is fully green."
2. When hosted `send-after-ready` lacks a clean verdict, classify it as incomplete machine evidence, not as proof that onboarding or the setup contract regressed.
3. Keep PM and launch-gate docs explicit about which part is implemented in code, which part has local authoritative proof, and which part still lacks current-window hosted or lane evidence.
4. Use the repo-side cloud artifact parser to separate real hosted flow failures from Maestro Cloud polling failures before updating the board or PM handover.

### 25. A current-window pass should be named by artifact root, not just described in prose

What happened:

1. Once local `android-instrumented` went green, several docs were still saying only that "a current-window pass exists" without pointing PM or ops to the exact artifact root.
2. That made the truth technically correct but operationally weaker, because reviewers still had to guess which run was the preserved authority versus which evidence remained open.

What to do again:

1. When a current-window pass becomes the preserved authority, name its artifact root in the board, PM handover, or launch-program summary.
2. Separate "this pass exists and should be preserved" from "the whole promotion evidence set is now complete."

### 26. A lane is not authoritative if it only passes by assumption-skip

What happened:

1. The `android-instrumented` lane initially went green because `MainActivityUiSmokeTest#onboardingFlowCanProgressAndComplete` exited through `AssumptionViolatedException` outside screenshot-pack mode.
2. That created a dangerous false sense of closure: the lane was green, but the first-run onboarding contract was not actually being executed in the authoritative path.
3. The fix was not broadening the lane. The fix was adding one tiny dedicated onboarding smoke that can run outside screenshot-pack while keeping the lane intentionally narrow.

What to do again:

1. Treat assumption-skipped tests as non-evidence for launch gates, even if the wrapper exits green.
2. When a lane only needs a small contract, add a tiny dedicated selector instead of trying to repurpose a broader screenshot-oriented class.
3. Keep the authoritative lane small enough that a real executed pass is easy to interpret.

### 27. Voice beta support copy must separate blockers from follow-up guidance

What happened:

1. The shipped voice surface is already user-visible under `Advanced`, so launch planning could not keep treating it as if it were hidden or purely future scope.
2. The real code contract only blocks always-on listening on microphone permission and local voice-model readiness, but support copy can still drift into making assistant-role or battery guidance sound mandatory if those states are all rendered the same way.
3. That kind of drift creates avoidable launch-support noise because users and PMs start reading advisory OEM/setup guidance as a hard product failure.

What to do again:

1. Keep voice positioned as a limited beta, Advanced-only, and outside headline launch claims.
2. Keep visible settings/support copy explicit about the difference between hard blockers and advisory follow-up.
3. Preserve the current bounded device-action allowlist in docs and support guidance instead of implying a broader general voice agent surface.

### 28. First-run reset now requires clearing database-backed state, not just legacy prefs

What happened:

1. The first-run onboarding smoke was initially resetting the old shared-preference state only.
2. The app now persists critical first-session state through the chat-state database as well, so clearing prefs alone can leave onboarding effectively completed.
3. That makes a first-run automation look flaky when the real problem is incomplete state reset.

What to do again:

1. Clear both legacy prefs and the chat-state database for first-run/onboarding authority tests.
2. Re-audit reset helpers whenever persistence moves from one store to another.
3. Prefer one explicit reset helper over scattered ad hoc cleanup logic in instrumentation tests.

### 29. Multiple wireless canaries require explicit serial pinning or the lane will fail before app logic starts

What happened:

1. As soon as both Samsung devices were visible over wireless ADB, canonical wrappers that had previously inferred one target correctly started failing fast with `Multiple adb devices detected`.
2. That failure happens before any app-under-test logic and can be mistaken for a lane regression if the device-selection contract is not explicit.
3. The extra device is still useful, but only when the active authority/canary target is named deliberately.

What to do again:

1. Pin `ADB_SERIAL` / `ANDROID_SERIAL` for any authoritative or reproducibility-sensitive run when more than one transport is visible.
2. Treat the second wireless device as throughput for diagnosis or beta brush, not as a reason to loosen the authority split.
3. Keep artifact notes explicit about which serial produced the evidence packet.

### 30. Launch claims must be enforced in the visible composer behavior, not only in backend capability

What happened:

1. The locked launch claim for image attach is single-image contextual Q&A.
2. The composer still allowed multiple attached images to accumulate or reappear from older message history, which was broader than the claim-safe launch surface.
3. The right fix was not rewriting the whole feature. It was clamping visible composer behavior and edit/regenerate hydration back to one image while preserving the existing chat flow.

What to do again:

1. Check that visible UI behavior matches the launch claim boundary, not just that the backend can technically handle more.
2. Clamp or hide broader capability when the launch promise is intentionally narrower.
3. Add targeted tests for the claim boundary so the surface cannot quietly widen again.

### 31. Multi-account cloud execution needs explicit account intent and secret-safe operator output

What happened:

1. Once the repo supported both `MAESTRO_CLOUD_API_KEY` and `MAESTRO_CLOUD_API_KEY_2`, ad hoc cloud commands became easier to misread and easier to copy in a secret-leaking form.
2. Parallel cloud fan-out is valuable, but only when the operator can tell clearly which account ran which upload and artifact root.

What to do again:

1. Default to wrapper scripts that take `--api-key-env` instead of pasting raw `--api-key` commands.
2. Make wrapper output explicit about which account ran the upload and keep dry-run/help text redacted.
3. Use the parallel wrapper only when both configured accounts are intentionally part of the same hosted rerun.

## Current Operating Rules Derived From These Learnings

1. Code and contract closure first.
2. Cloud-first machine-verifiable reruns second.
3. Narrow physical-device canary and final brush third.
4. Human-required moderation fourth.
5. Release-date planning only after both evidence tracks are materially complete.
