# Launch Program Learnings

Last updated: 2026-04-26  
Owner: Tech Lead + Product

This document records repeated patterns from the current launch-unblock effort so the team can keep improving the program while work is still active.

Mutable status remains in `docs/operations/execution-board.md`. This file is for patterns and controls, not ticket state.

## Current Learnings

### 1. The blocker chain moves as soon as the first real defect is cleared

What happened:

1. The retained provisioning `SIGILL` class stopped being the first live failure after the host-side mitigation landed.
2. The next deterministic blocker immediately surfaced in the Maestro/runtime-ready lifecycle path.

What to do again:

1. Re-baseline the board and handover docs as soon as the first failing step changes.
2. Do not keep leading with a historical blocker once the active first failure has moved.

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

1. Provisioning `SIGILL`, stale runtime metadata, onboarding selector drift, and the current Maestro/runtime-ready `Unloaded` path were not isolated bugs; each one blocked multiple downstream gate rows at once.
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

## Current Operating Rules Derived From These Learnings

1. Code and contract closure first.
2. Cloud-first machine-verifiable reruns second.
3. Narrow physical-device canary and final brush third.
4. Human-required moderation fourth.
5. Release-date planning only after both evidence tracks are materially complete.
