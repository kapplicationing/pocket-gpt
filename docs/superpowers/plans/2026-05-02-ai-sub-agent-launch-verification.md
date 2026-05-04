# AI-Sub-Agent Launch Verification & Blocker Closure — Implementation Plan

Status note:

1. This is a secondary implementation plan, not launch canon.
2. Canonical launch policy now lives in `docs/operations/play-store-launch-program.md`, `docs/testing/test-strategy.md`, and `docs/operations/tickets/prod-12-human-required-gate-split.md`.
3. Agent outputs are valid for deterministic QA support only; they do not replace moderated WP-13 evidence.

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining Play-Store launch hold by (1) re-deriving the app's flow contract from actual source code, (2) re-aligning Maestro flows to that contract, (3) dispatching four AI tester sub-agents (two Maestro-Cloud-driven, two physical-device-driven) to run deterministic scripted QA across PRD Workflows A/B/C plus the WP-13 failure-state journeys, (4) compiling their findings into an AI-assisted pre-screen packet, then (5) using that output to support downstream release-governance tickets so the launch decision can be re-run from current evidence and `main` can be published when the human-required gate is also satisfied.

**Architecture:**

1. **Code → Truth**: a single manifest (`docs/testing/generated/launch-flow-truth.md`) is generated from the actual `apps/mobile-android` source. It is the only authority for what selectors, copy strings, CTAs, error codes, and stage-machine transitions exist in the build.
2. **Truth → Flows**: `tests/maestro/`, `tests/maestro/shared/`, and `tests/maestro-cloud/` are audited against the manifest. Drift gets fixed in this PR; any fix is a small, contract-focused commit.
3. **Flows → Sub-Agent Fleet**: a thin Python orchestrator (`tools/qa-agents/`) spawns four AI tester sub-agents (cloud-1, cloud-2, device-S22, device-A51). Each gets the same scripted trip (Workflows A/B/C + recovery + manifest-outage + timeout) and produces a deterministic JSON trip report against the WP-13 schema. Sub-agents are LLM-driven `Task(subagent_type="generalPurpose")` workers, not Maestro flows running themselves.
4. **Reports → Decision support**: an aggregator turns four trip reports into one AI-assisted pre-screen packet, then feeds deterministic findings into `PROD-10` preparation. Hosted infra-pending uploads (`mupload_01kq4fc793fdathk37tk97jkmd`, `mupload_01kq4fq5hpf6f9m53fm7pq8ew0`) are polled and re-fired in parallel; `localhost:7001` strict-`journey` bootstrap is fixed locally so authoritative journey lane has a current-window pass.
5. **Governance**: SEC-02 is re-verified, MKT-08 captures assets from the new screenshot pack, MKT-10 freezes claims against the new pass set, PROD-13 packages the submission, and `main` is fast-forwarded to `origin/main`.

**Tech Stack:** Maestro CLI (cloud + local), `tools/devctl` Python orchestrator, `adb` over `tcp/tls`, Kotlin/Compose source as ground truth, JSON Schema for trip reports, Markdown for governance artifacts, Anthropic-style sub-agent dispatch via the parent agent's `Task` tool.

**Pre-flight (verify before Task 1):**

- `adb devices -l` returns both `adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp` and `adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp` as `device`.
- `grep -c MAESTRO_CLOUD_API_KEY .env` returns `2`.
- `python3 tools/devctl/main.py doctor` is green.
- The active branch (`main` or its successor) builds: `bash scripts/dev/test.sh fast`.

---

## File Structure


| Layer         | Path                                                                                                  | Responsibility                                                                                    | Action                                  |
| ------------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | --------------------------------------- |
| Truth         | `docs/testing/generated/launch-flow-truth.md`                                                         | Single source of truth, derived from app code                                                     | Create                                  |
| Truth         | `tools/qa-agents/code_truth_manifest.py`                                                              | Script that emits the truth manifest by walking Compose `testTag`/`stringResource`/CTA call sites | Create                                  |
| Flows         | `tests/maestro/shared/*.yaml`, `tests/maestro/scenario-*.yaml`, `tests/maestro-cloud/scenario-*.yaml` | E2E flows                                                                                         | Patch to match truth where drift exists |
| Orchestrator  | `tools/qa-agents/__init__.py`, `tools/qa-agents/run_ai_tester.py`                                     | Spawns one tester (cloud or device) and persists its trip report                                  | Create                                  |
| Schema        | `tools/qa-agents/trip_report.schema.json`                                                             | JSON-Schema for tester output                                                                     | Create                                  |
| Template      | `tools/qa-agents/trip_report.template.md`                                                             | Markdown trip-report template (mirrors WP-13 packet template)                                     | Create                                  |
| Aggregator    | `tools/qa-agents/aggregate_wp13.py`                                                                   | Folds N trip reports into one WP-13 packet draft                                                  | Create                                  |
| Triage loop   | `tools/qa-agents/triage_failure.py`                                                                   | QA-15 agent-assisted triage from one failed run                                                   | Create                                  |
| Hosted infra  | `scripts/dev/maestro-cloud-watch.sh`                                                                  | Poll hosted `PENDING` uploads + re-fire on null verdict (closes infra-pending blocker)            | Create                                  |
| Local Maestro | `scripts/dev/maestro-local-bootstrap.sh`                                                              | Resolves the `localhost:7001` Maestro bootstrap on the wired Samsung path                         | Create                                  |
| Evidence      | planned AI-tester fleet evidence note under `docs/operations/evidence/wp-13/`                           | Aggregate deterministic QA note                                                                   | Create                                  |
| Evidence      | `docs/operations/evidence/wp-13/2026-05-02-wp13-packet-run-02-ai-moderated.md`                        | WP-13 packet (run-02), `tester=AI-agent` honestly disclosed                                       | Create                                  |
| Decision      | `docs/operations/tickets/prod-10-launch-gate-matrix.md`                                               | Update required-row state from new evidence                                                       | Modify                                  |
| Decision      | `docs/operations/execution-board.md`                                                                  | Update sprint board statuses                                                                      | Modify                                  |
| Governance    | `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md`                                        | Re-check P-04 status, link new evidence                                                           | Modify                                  |
| Governance    | `docs/operations/tickets/mkt-08-proof-asset-capture-and-listing-finalization.md`                      | Map screenshot-pack assets to PROD-10 rows                                                        | Modify                                  |
| Governance    | `docs/operations/tickets/mkt-10-claim-freeze-v1.md`                                                   | Re-anchor publish-safe claims to new evidence                                                     | Modify                                  |
| Governance    | `docs/operations/tickets/prod-13-play-store-submission-readiness.md`                                  | Mark hard-prereqs satisfied where they are                                                        | Modify                                  |
| Governance    | `docs/operations/play-store-launch-program.md`                                                        | Update current-status banner                                                                      | Modify                                  |


---

## Phase 0 — Ground Truth

### Task 1: Generate the launch flow-truth manifest from the actual app source

**Why:** The user explicitly directed: *"do not trust previous scenario runs because they might not be right."* The Maestro flows reference selectors and copy that may have drifted (`session_drawer_button`, `composer_input`, `Get ready`, `Setup`, `Load last used`, `UI-RUNTIME-001`, `UI-STARTUP-001`, etc.). Before testing, we extract the live truth from the codebase so subsequent steps test against the build, not against stale assumptions.

**Files:**

- Create: `tools/qa-agents/__init__.py`
- Create: `tools/qa-agents/code_truth_manifest.py`
- Create: `docs/testing/generated/launch-flow-truth.md` (committed output of the script)

**- [ ] Step 1: Create the `tools/qa-agents` package**

```bash
mkdir -p tools/qa-agents
printf '"""AI-tester orchestration for PocketAgent launch verification."""\n' > tools/qa-agents/__init__.py
```

**- [ ] Step 2: Write `code_truth_manifest.py`**

Create `tools/qa-agents/code_truth_manifest.py` with the following content. It is intentionally read-only, plain Python 3.11+, no external dependencies. It walks `apps/mobile-android/src/main/kotlin` (and `*/res/values/strings.xml`) and emits a Markdown manifest grouped by surface.

```python
"""Generate docs/testing/generated/launch-flow-truth.md from the app source code.

Authority list emitted per surface:
  - testTag values used in Compose
  - stringResource and literal text used in launch-critical Composables
  - error codes referenced (UI-* / GPU-*) by Composables
  - stage-machine transitions from FirstSessionStage
  - keep-alive options, runtime profiles, runtime status labels

This is the ONE document later sub-agents and Maestro flow audits compare
against. It must be regenerated whenever code changes.
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "apps/mobile-android/src/main/kotlin/com/pocketagent/android"
RES = REPO / "apps/mobile-android/src/main/res/values"
OUT = REPO / "docs/testing/generated/launch-flow-truth.md"

TEST_TAG = re.compile(r"\.testTag\(\s*\"([^\"]+)\"\s*\)")
LITERAL  = re.compile(r"text\s*=\s*\"([^\"]{2,80})\"")
ERROR    = re.compile(r"\"(UI-[A-Z0-9-]+|GPU-[A-Z0-9-]+|MODEL_[A-Z_]+|RUNTIME_[A-Z_]+)\"")
STRING_RES = re.compile(r"<string name=\"([^\"]+)\">([^<]+)</string>")

LAUNCH_SURFACES = {
    "Onboarding"        : ["ui/onboarding"],
    "ChatShell"         : ["ui/ChatApp.kt", "ui/ChatScreen.kt", "ui/PocketAgentTopBar"],
    "Composer"          : ["ui/ComposerBar", "ui/ChatScreen.kt"],
    "ModelLibrary"      : ["ui/modelmanager", "ui/ModelLibrarySheet"],
    "Advanced"          : ["ui/advanced", "ui/AdvancedSheet"],
    "RuntimeStatus"     : ["ui/runtime", "ui/state/ChatUiState.kt"],
    "ToolsDialog"       : ["ui/tools", "ui/ToolsDialog"],
    "Diagnostics"       : ["ui/diagnostics", "ui/DiagnosticsAction"],
    "VoiceBeta"         : ["voice", "ui/voice"],
    "RecoveryBanners"   : ["ui/recovery", "ui/state/RuntimeUiState"],
}

def collect_files() -> list[Path]:
    return [p for p in SRC.rglob("*.kt") if p.is_file()]

def by_surface(files: list[Path]) -> dict[str, list[Path]]:
    out: dict[str, list[Path]] = defaultdict(list)
    for f in files:
        rel = f.relative_to(SRC).as_posix()
        for surface, hints in LAUNCH_SURFACES.items():
            if any(h in rel for h in hints):
                out[surface].append(f)
                break
    return out

def matches(files: list[Path], pat: re.Pattern[str]) -> set[str]:
    found: set[str] = set()
    for f in files:
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for m in pat.finditer(text):
            found.add(m.group(1))
    return found

def string_resources() -> dict[str, str]:
    out: dict[str, str] = {}
    for xml in RES.glob("strings*.xml"):
        for m in STRING_RES.finditer(xml.read_text(encoding="utf-8")):
            out[m.group(1)] = m.group(2)
    return out

def main() -> int:
    files = collect_files()
    surfaced = by_surface(files)
    strings = string_resources()

    lines = [
        "# Launch Flow Truth (Generated From Source)",
        "",
        f"Generated by `tools/qa-agents/code_truth_manifest.py`. Do not edit by hand.",
        "",
        "This file is the **only** authority for selectors, copy, error codes, and",
        "stage transitions used by AI tester sub-agents and Maestro flows. If the",
        "code changes, regenerate this file in the same PR.",
        "",
    ]

    for surface, sfiles in sorted(surfaced.items()):
        lines.append(f"## {surface}")
        lines.append("")
        lines.append(f"Source files ({len(sfiles)}):")
        for f in sfiles:
            lines.append(f"- `{f.relative_to(REPO)}`")
        lines.append("")
        for label, pat in (("testTag", TEST_TAG), ("Literal text", LITERAL), ("Error codes", ERROR)):
            vals = sorted(matches(sfiles, pat))
            if not vals:
                continue
            lines.append(f"### {label}")
            for v in vals:
                lines.append(f"- `{v}`")
            lines.append("")

    lines.append("## String Resources (launch surfaces)")
    lines.append("")
    for k, v in sorted(strings.items()):
        if any(tag in k.lower() for tag in (
            "onboard", "ready", "model", "compose", "send", "retry",
            "advanced", "tools", "voice", "privacy", "diagnostic", "runtime",
        )):
            lines.append(f"- `{k}` = \"{v}\"")
    lines.append("")

    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

**- [ ] Step 3: Run the generator**

```bash
python3 tools/qa-agents/code_truth_manifest.py
```

Expected: `wrote docs/testing/generated/launch-flow-truth.md`. Open it and confirm at minimum:

- `ChatShell` lists `session_drawer_button`, `composer_input`, `send_button`, `message_bubble_assistant_complete`.
- `RuntimeStatus`/`RecoveryBanners` lists `UI-RUNTIME-001`, `UI-STARTUP-001`.
- `Onboarding` lists `onboarding_skip`, `onboarding_next`, `onboarding_get_started`.
- `Advanced` lists tags for `BATTERY`, `BALANCED`, `FAST` profile choices and the GPU toggle.
- `ModelLibrary` lists `Active model`, `Downloaded models`, `Available models`, `Get ready`, `Open model library`.

**- [ ] Step 4: Commit**

```bash
git add tools/qa-agents/__init__.py tools/qa-agents/code_truth_manifest.py docs/testing/generated/launch-flow-truth.md
git commit -m "qa: emit launch-flow-truth manifest from app source as ground truth"
```

---

### Task 2: Audit `tests/maestro/**` against the truth manifest and patch drift

**Why:** Sub-agent testers will route some flows through Maestro Cloud and the wired devices via `maestro test`. If the flows reference dead selectors or stale copy strings, every tester will see a false-negative. We fix drift here once.

**Files:**

- Modify: any of `tests/maestro/scenario-*.yaml`, `tests/maestro/shared/*.yaml`, `tests/maestro-cloud/scenario-*.yaml`, `tests/maestro-cloud/shared/*.yaml` whose selectors/copy disagree with the manifest.
- Use: existing wrappers `maestro-android lint` and `maestro-android audit-selectors` per `scripts/dev/README.md`.

**- [ ] Step 1: Run the existing audit**

```bash
maestro-android lint
maestro-android audit-selectors
```

Triage each warning into one of:

- `flow drift` (fix in this PR; the canonical naming taxonomy is in `docs/operations/qa-improvement-action-plan.md` § "Require failure classification")
- `truth-manifest gap` (regenerate manifest; the Compose code may have a tag the script regex missed → tighten the regex)
- `intentional` (keep, document why)

**- [ ] Step 2: Cross-check shared helpers against truth manifest**

For each helper in `tests/maestro/shared/`, every `id:`/`text:` selector must appear in `docs/testing/generated/launch-flow-truth.md`. The known high-traffic helpers to check first:

- `bootstrap-runtime-ready.yaml`
- `bootstrap-to-ready.yaml`
- `ensure-runtime-loaded.yaml`
- `open-advanced-controls.yaml`
- `open-model-library.yaml`

For any selector not in the manifest, prefer the manifest value (the manifest reflects the build). If the manifest is right and the helper is wrong, patch the helper. If the helper is right and the manifest missed a tag, extend the regex in `code_truth_manifest.py` and rerun.

**- [ ] Step 3: Patch drifting selectors using `StrReplace`**

Each fix is a separate small commit, with the message format:

```
test(maestro): align <flow>.yaml selector to current source truth

Drift: <selector> changed from "<old>" to "<new>" in <kotlin file>.
Authority: docs/testing/generated/launch-flow-truth.md § <surface>.
```

**- [ ] Step 4: Verify the lifecycle gate flow runs locally on the S22**

`tests/maestro/scenario-first-run-download-chat.yaml` is the CI lifecycle gate. Confirm it still launches on the wired S22 before the AI testers depend on it:

```bash
S22=adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp
./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=false :apps:mobile-android:assembleDebug
APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
adb -s "$S22" install -r "$APK_PATH"
maestro --device "$S22" test tests/maestro/scenario-first-run-download-chat.yaml \
  --format junit --output tmp/lifecycle-after-truth-audit.xml
```

Expected: exit `0`. If it fails, classify before re-running (per `qa-improvement-action-plan.md` § "Require failure classification before broad rerun") and either fix flow or file a product bug as a separate task — do **not** widen scope here.

**- [ ] Step 5: Commit**

One commit per drift fix; final batch commit:

```bash
git add tests/maestro/ tests/maestro-cloud/
git commit -m "test(maestro): batch align flows to launch-flow-truth manifest"
```

---

## Phase 1 — Sub-Agent Tester Infrastructure

### Task 3: Create the trip-report JSON schema and Markdown template

**Why:** Four sub-agents will run independently. Their outputs must be machine-mergeable into one WP-13 packet (`docs/operations/wp-13-usability-gate-packet-template.md`). A schema enforces this without us reading every report by hand.

**Files:**

- Create: `tools/qa-agents/trip_report.schema.json`
- Create: `tools/qa-agents/trip_report.template.md`

**- [ ] Step 1: Write the JSON schema**

`tools/qa-agents/trip_report.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "PocketAgent AI Tester Trip Report",
  "type": "object",
  "required": [
    "tester_id", "tester_kind", "device", "build", "timestamps",
    "workflows", "failure_states", "advanced_controls",
    "summary", "recommendation"
  ],
  "properties": {
    "tester_id":   { "type": "string", "pattern": "^(cloud-1|cloud-2|device-s22|device-a51)$" },
    "tester_kind": { "type": "string", "enum": ["maestro-cloud", "physical-device"] },
    "device": {
      "type": "object",
      "required": ["label", "android_api"],
      "properties": {
        "label":       { "type": "string" },
        "serial":      { "type": "string" },
        "android_api": { "type": "integer", "minimum": 26 },
        "manufacturer":{ "type": "string" },
        "model":       { "type": "string" }
      }
    },
    "build":       { "type": "object", "required": ["commit", "apk_path", "branch_tip"] },
    "timestamps":  { "type": "object", "required": ["started_utc", "ended_utc"] },
    "workflows": {
      "type": "object",
      "required": ["A", "B", "C"],
      "properties": {
        "A": { "$ref": "#/$defs/workflowResult" },
        "B": { "$ref": "#/$defs/workflowResult" },
        "C": { "$ref": "#/$defs/workflowResult" }
      }
    },
    "failure_states": {
      "type": "object",
      "required": ["recovery_not_ready", "stuck_send", "manifest_outage"],
      "properties": {
        "recovery_not_ready": { "$ref": "#/$defs/failureResult" },
        "stuck_send":          { "$ref": "#/$defs/failureResult" },
        "manifest_outage":     { "$ref": "#/$defs/failureResult" }
      }
    },
    "advanced_controls": {
      "type": "object",
      "required": ["profiles_visible", "gpu_toggle_observed", "diagnostics_export_ok", "keepalive_options_visible"],
      "properties": {
        "profiles_visible":         { "type": "array", "items": { "enum": ["BATTERY", "BALANCED", "FAST"] } },
        "gpu_toggle_observed":      { "type": "boolean" },
        "diagnostics_export_ok":    { "type": "boolean" },
        "keepalive_options_visible":{ "type": "array", "items": { "enum": ["AUTO","ALWAYS","ONE_MINUTE","FIVE_MINUTES","FIFTEEN_MINUTES","UNLOAD_IMMEDIATELY"] } }
      }
    },
    "summary": {
      "type": "object",
      "required": ["s0_count", "s1_count", "blockers", "confusion_runtime_pct", "confusion_privacy_pct"],
      "properties": {
        "s0_count":              { "type": "integer", "minimum": 0 },
        "s1_count":              { "type": "integer", "minimum": 0 },
        "blockers":              { "type": "array", "items": { "type": "string" } },
        "confusion_runtime_pct": { "type": "number", "minimum": 0, "maximum": 100 },
        "confusion_privacy_pct": { "type": "number", "minimum": 0, "maximum": 100 }
      }
    },
    "recommendation": { "type": "string", "enum": ["promote", "iterate", "hold"] }
  },
  "$defs": {
    "workflowResult": {
      "type": "object",
      "required": ["completed", "duration_seconds", "blocker", "confusion_notes", "screenshots", "logcat_excerpts"],
      "properties": {
        "completed":         { "type": "boolean" },
        "duration_seconds":  { "type": "number", "minimum": 0 },
        "blocker":           { "type": ["string", "null"] },
        "confusion_notes":   { "type": "array", "items": { "type": "string" } },
        "screenshots":       { "type": "array", "items": { "type": "string" } },
        "logcat_excerpts":   { "type": "array", "items": { "type": "string" } }
      }
    },
    "failureResult": {
      "type": "object",
      "required": ["recovered", "deterministic_code_seen", "cta_path_taken", "duration_seconds", "notes"],
      "properties": {
        "recovered":              { "type": "boolean" },
        "deterministic_code_seen":{ "type": ["string", "null"] },
        "cta_path_taken":         { "type": ["string", "null"] },
        "duration_seconds":       { "type": "number", "minimum": 0 },
        "notes":                  { "type": "string" }
      }
    }
  }
}
```

**- [ ] Step 2: Write the Markdown template**

`tools/qa-agents/trip_report.template.md`:

```markdown
# AI Tester Trip Report — {{tester_id}} ({{tester_kind}})

- Device: {{device.label}} (api {{device.android_api}}, model {{device.model}})
- Build: commit {{build.commit}} on branch tip {{build.branch_tip}}, APK {{build.apk_path}}
- Window: {{timestamps.started_utc}} → {{timestamps.ended_utc}}

## PRD Workflows

| Workflow | Completed | Duration (s) | Blocker | Confusion notes |
|---|---|---|---|---|
| A — Offline quick answer | {{workflows.A.completed}} | {{workflows.A.duration_seconds}} | {{workflows.A.blocker}} | {{workflows.A.confusion_notes_joined}} |
| B — Local tool | {{workflows.B.completed}} | {{workflows.B.duration_seconds}} | {{workflows.B.blocker}} | {{workflows.B.confusion_notes_joined}} |
| C — Continuity + image | {{workflows.C.completed}} | {{workflows.C.duration_seconds}} | {{workflows.C.blocker}} | {{workflows.C.confusion_notes_joined}} |

## Failure-State Journeys (PROD-10 rows S-D, S-F, S-G)

| Journey | Recovered | Deterministic code | CTA taken | Duration (s) |
|---|---|---|---|---|
| Recovery NotReady→Ready | {{failure_states.recovery_not_ready.recovered}} | {{failure_states.recovery_not_ready.deterministic_code_seen}} | {{failure_states.recovery_not_ready.cta_path_taken}} | {{failure_states.recovery_not_ready.duration_seconds}} |
| Stuck send / timeout    | {{failure_states.stuck_send.recovered}} | {{failure_states.stuck_send.deterministic_code_seen}} | {{failure_states.stuck_send.cta_path_taken}} | {{failure_states.stuck_send.duration_seconds}} |
| Manifest outage         | {{failure_states.manifest_outage.recovered}} | {{failure_states.manifest_outage.deterministic_code_seen}} | {{failure_states.manifest_outage.cta_path_taken}} | {{failure_states.manifest_outage.duration_seconds}} |

## Advanced Controls

- Profiles visible: {{advanced_controls.profiles_visible}}
- GPU toggle observed: {{advanced_controls.gpu_toggle_observed}}
- Diagnostics export OK: {{advanced_controls.diagnostics_export_ok}}
- Keep-alive options visible: {{advanced_controls.keepalive_options_visible}}

## Summary

- S0 count: {{summary.s0_count}}
- S1 count: {{summary.s1_count}}
- Blockers: {{summary.blockers_joined}}
- Runtime confusion: {{summary.confusion_runtime_pct}} %
- Privacy confusion: {{summary.confusion_privacy_pct}} %

## Recommendation

**{{recommendation}}**
```

**- [ ] Step 3: Commit**

```bash
git add tools/qa-agents/trip_report.schema.json tools/qa-agents/trip_report.template.md
git commit -m "qa: add trip-report schema and template for AI tester sub-agents"
```

---

### Task 4: Build the per-tester runner script

**Why:** A single executable contract — `python3 tools/qa-agents/run_ai_tester.py --tester <id>` — so each sub-agent has a deterministic surface to call. The script bootstraps the device or cloud lane, captures the screenshot/logcat artifacts, then writes its slot of the trip report. The actual judgement (did the model answer? did privacy copy feel clear?) is filled in by the dispatched sub-agent prompt in Task 6.

**Files:**

- Create: `tools/qa-agents/run_ai_tester.py`
- Create: an artifact-helper module inside `tools/qa-agents/`
- Create: `tools/qa-agents/tests/test_run_ai_tester.py`

**- [ ] Step 1: Write the runner**

`tools/qa-agents/run_ai_tester.py` (Python 3.11+, stdlib only):

```python
"""Per-tester runner. Each AI tester sub-agent calls this once.

Modes:
  --tester cloud-1      → uses MAESTRO_CLOUD_API_KEY,   account label "account-1"
  --tester cloud-2      → uses MAESTRO_CLOUD_API_KEY_2, account label "account-2"
  --tester device-s22   → wireless ADB serial RFCT2178PDV
  --tester device-a51   → wireless ADB serial RR8NB087YTF

The runner does **only** the deterministic, machine-verifiable parts:
  1. assemble debug APK once per invocation
  2. install/upload to chosen target
  3. drive the canonical scripted journey via Maestro flow files
  4. capture artifacts under tmp/qa-agents/<tester>/<utc-timestamp>/
  5. write a trip-report skeleton (schema-valid) with deterministic fields

The qualitative fields (`confusion_notes`, `recommendation`) stay empty so
the dispatched sub-agent fills them with first-party-witness judgement
based on the captured screenshots and logcat.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TMP  = REPO / "tmp/qa-agents"

DEVICES = {
    "device-s22": {
        "label":  "Samsung Galaxy S22 Ultra (SM-S906N)",
        "serial": "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp",
        "model":  "SM-S906N",
        "manufacturer": "Samsung",
        "android_api": 34,
    },
    "device-a51": {
        "label":  "Samsung Galaxy A51 (SM-A515F)",
        "serial": "adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp",
        "model":  "SM-A515F",
        "manufacturer": "Samsung",
        "android_api": 31,
    },
}

CLOUD = {
    "cloud-1": ("MAESTRO_CLOUD_API_KEY",   "account-1"),
    "cloud-2": ("MAESTRO_CLOUD_API_KEY_2", "account-2"),
}

# Canonical scripted journey, kept short on purpose. Each entry maps to a Maestro
# flow file. The journey covers Workflows A/B/C plus the three failure states.
JOURNEY = [
    ("onboarding",        "tests/maestro/scenario-onboarding.yaml"),
    ("workflow-A-send",   "tests/maestro/scenario-a.yaml"),
    ("workflow-B-tools",  "tests/maestro/scenario-b.yaml"),
    ("workflow-C-image",  "tests/maestro/scenario-c.yaml"),
    ("recovery-notready", "tests/maestro/scenario-activation-send-smoke.yaml"),
    ("stuck-send",        "tests/maestro/scenario-first-run-download-chat.yaml"),
    ("manifest-outage",   "tests/maestro/scenario-download-settings-smoke.yaml"),
    ("session-shell",     "tests/maestro/scenario-session-drawer-smoke.yaml"),
]

def utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}", flush=True)
    return subprocess.run(cmd, check=False, **kw)

def ensure_apk() -> Path:
    out = REPO / "apps/mobile-android/build/outputs/apk/debug"
    apks = sorted(out.glob("*.apk"))
    if not apks or any(p.stat().st_mtime < (time.time() - 1800) for p in apks):
        run(["./gradlew", "--no-daemon",
             "-Ppocketgpt.enableNativeBuild=false",
             ":apps:mobile-android:assembleDebug"], cwd=REPO)
        apks = sorted(out.glob("*.apk"))
    if not apks:
        raise SystemExit("APK assembly failed")
    return apks[0]

def head_commit() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO).decode().strip()

def branch_tip() -> str:
    return subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=REPO).decode().strip()

def run_device(tester: str, root: Path) -> dict:
    cfg = DEVICES[tester]
    apk = ensure_apk()
    run(["adb", "-s", cfg["serial"], "install", "-r", str(apk)])
    flow_results: dict[str, dict] = {}
    for name, flow in JOURNEY:
        log_path = root / f"{name}.log"
        xml_path = root / f"{name}.xml"
        proc = run(
            ["maestro", "--device", cfg["serial"], "test", flow,
             "--format", "junit", "--output", str(xml_path)],
            stdout=open(log_path, "w"), stderr=subprocess.STDOUT,
        )
        # collect screenshots maestro drops into the cwd
        for png in REPO.glob("*.png"):
            shutil.move(str(png), str(root / png.name))
        flow_results[name] = {"exit_code": proc.returncode, "log": str(log_path), "junit": str(xml_path)}
    # capture logcat tail
    lc = root / "logcat.tail.txt"
    run(["adb", "-s", cfg["serial"], "logcat", "-d", "-t", "5000"],
        stdout=open(lc, "w"))
    return {"flow_results": flow_results, "logcat": str(lc)}

def run_cloud(tester: str, root: Path) -> dict:
    env_var, label = CLOUD[tester]
    api_key = os.environ.get(env_var)
    if not api_key:
        # try to read from .env without overwriting current env
        for line in (REPO / ".env").read_text().splitlines():
            if line.startswith(f"{env_var}="):
                api_key = line.split("=", 1)[1].strip()
                break
    if not api_key:
        raise SystemExit(f"missing {env_var}")
    apk = ensure_apk()
    flow_results: dict[str, dict] = {}
    for name, flow in JOURNEY:
        log_path = root / f"{name}.log"
        xml_path = root / f"{name}.xml"
        proc = run(
            ["maestro", "cloud",
             "--api-key", api_key,
             "--android-api-level", "34",
             "--app-file", str(apk),
             "--flows", flow,
             "--format", "junit",
             "--output", str(xml_path)],
            stdout=open(log_path, "w"), stderr=subprocess.STDOUT,
        )
        flow_results[name] = {"exit_code": proc.returncode, "log": str(log_path),
                              "junit": str(xml_path), "account_label": label}
    return {"flow_results": flow_results, "account_label": label}

def make_skeleton(tester: str, kind: str, device: dict, root: Path, results: dict) -> Path:
    skeleton = {
        "tester_id":   tester,
        "tester_kind": kind,
        "device":      device,
        "build": {
            "commit":     head_commit(),
            "apk_path":   "apps/mobile-android/build/outputs/apk/debug",
            "branch_tip": branch_tip(),
        },
        "timestamps": {
            "started_utc": "FILL_ME_FROM_RUNNER_INVOCATION",
            "ended_utc":   utc(),
        },
        # Qualitative fields are deliberately empty so the dispatched sub-agent
        # fills them based on the captured artifacts.
        "workflows": {k: _empty_workflow() for k in ("A", "B", "C")},
        "failure_states": {
            "recovery_not_ready": _empty_failure(),
            "stuck_send":          _empty_failure(),
            "manifest_outage":     _empty_failure(),
        },
        "advanced_controls": {
            "profiles_visible": [],
            "gpu_toggle_observed": False,
            "diagnostics_export_ok": False,
            "keepalive_options_visible": [],
        },
        "summary": {
            "s0_count": 0, "s1_count": 0, "blockers": [],
            "confusion_runtime_pct": 0.0, "confusion_privacy_pct": 0.0,
        },
        "recommendation": "hold",
        "_runner_artifacts": results,
    }
    skel_path = root / "trip-report.skeleton.json"
    skel_path.write_text(json.dumps(skeleton, indent=2))
    return skel_path

def _empty_workflow() -> dict:
    return {"completed": False, "duration_seconds": 0, "blocker": None,
            "confusion_notes": [], "screenshots": [], "logcat_excerpts": []}

def _empty_failure() -> dict:
    return {"recovered": False, "deterministic_code_seen": None,
            "cta_path_taken": None, "duration_seconds": 0, "notes": ""}

def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--tester", required=True,
                   choices=list(DEVICES) + list(CLOUD))
    args = p.parse_args(argv)

    started = utc()
    root = TMP / args.tester / started
    root.mkdir(parents=True, exist_ok=True)

    if args.tester in DEVICES:
        results = run_device(args.tester, root)
        device = DEVICES[args.tester]
        kind = "physical-device"
    else:
        results = run_cloud(args.tester, root)
        device = {
            "label": f"Maestro Cloud ({CLOUD[args.tester][1]})",
            "serial": "n/a",
            "android_api": 34,
            "manufacturer": "hosted",
            "model": "Pixel 6 (per Maestro Cloud Android default)",
        }
        kind = "maestro-cloud"

    skel = make_skeleton(args.tester, kind, device, root, results)
    print(f"\nTrip-report skeleton: {skel}")
    print(f"Artifacts root: {root}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

**- [ ] Step 2: Add a smoke test for the runner**

`tools/qa-agents/tests/test_run_ai_tester.py`:

```python
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "run_ai_tester", ROOT / "qa-agents/run_ai_tester.py")
mod = importlib.util.module_from_spec(spec); sys.modules[spec.name] = mod
spec.loader.exec_module(mod)  # type: ignore[union-attr]

def test_devices_have_known_serials():
    assert mod.DEVICES["device-s22"]["serial"].startswith("adb-RFCT2178PDV")
    assert mod.DEVICES["device-a51"]["serial"].startswith("adb-RR8NB087YTF")

def test_cloud_uses_known_env_vars():
    assert mod.CLOUD["cloud-1"][0] == "MAESTRO_CLOUD_API_KEY"
    assert mod.CLOUD["cloud-2"][0] == "MAESTRO_CLOUD_API_KEY_2"

def test_journey_covers_all_required_rows():
    names = [n for n, _ in mod.JOURNEY]
    for r in ("workflow-A-send", "workflow-B-tools", "workflow-C-image",
              "recovery-notready", "stuck-send", "manifest-outage"):
        assert r in names, f"missing journey step {r}"
```

**- [ ] Step 3: Run the test → PASS**

```bash
python3 -m pytest tools/qa-agents/tests/ -q
```

Expected: 3 passed.

**- [ ] Step 4: Commit**

```bash
git add tools/qa-agents/run_ai_tester.py tools/qa-agents/tests/
git commit -m "qa: per-tester runner that emits trip-report skeleton + artifacts"
```

---

### Task 5: Create the aggregator that turns 4 trip reports into one WP-13 packet

**Why:** PROD-10 row decisions consume the WP-13 packet, not raw reports. The aggregator computes the gate-table thresholds (`>= 90 %` for A/B, `>= 80 %` for C, etc. per `docs/operations/wp-13-usability-gate-packet-template.md` § "Quantitative Gate Table") and writes one Markdown file with the right structure.

**Files:**

- Create: `tools/qa-agents/aggregate_wp13.py`
- Create: `tools/qa-agents/tests/test_aggregate_wp13.py`

**- [ ] Step 1: Write the aggregator**

`tools/qa-agents/aggregate_wp13.py`:

```python
"""Aggregate N trip reports into one WP-13 packet (run-02, AI-moderated).

Reads tools/qa-agents/_inputs/*.json (each schema-valid),
writes docs/operations/evidence/wp-13/2026-05-02-wp13-packet-run-02-ai-moderated.md.

The aggregator does NOT relabel AI testers as humans. The packet header
explicitly records `tester_kind=AI-agent` for each row, and the decision
section calls this out as a documented PROD-12 deviation pending real
human moderation when reviewers are available.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from statistics import mean

ROOT = Path(__file__).resolve().parents[2]
IN   = ROOT / "tools/qa-agents/_inputs"
OUT  = ROOT / "docs/operations/evidence/wp-13/2026-05-02-wp13-packet-run-02-ai-moderated.md"

THRESHOLDS = {
    "A": 90.0, "B": 90.0, "C": 80.0,
    "onboarding": 80.0, "recovery": 85.0,
    "confusion_runtime_max": 10.0,
    "confusion_privacy_max": 10.0,
}

def load_reports() -> list[dict]:
    if not IN.exists():
        raise SystemExit(f"no _inputs dir, drop trip-report.json files into {IN}")
    return [json.loads(p.read_text()) for p in sorted(IN.glob("*.json"))]

def pct_completed(reports: list[dict], wf: str) -> float:
    if not reports: return 0.0
    return 100.0 * sum(1 for r in reports if r["workflows"][wf]["completed"]) / len(reports)

def pct_recovered(reports: list[dict], state: str) -> float:
    if not reports: return 0.0
    return 100.0 * sum(1 for r in reports if r["failure_states"][state]["recovered"]) / len(reports)

def main() -> int:
    reports = load_reports()
    if len(reports) < 4:
        print(f"warn: expected 4 trip reports, got {len(reports)}", file=sys.stderr)

    a = pct_completed(reports, "A"); b = pct_completed(reports, "B"); c = pct_completed(reports, "C")
    rec = pct_recovered(reports, "recovery_not_ready")
    stk = pct_recovered(reports, "stuck_send")
    man = pct_recovered(reports, "manifest_outage")
    cr  = mean(r["summary"]["confusion_runtime_pct"] for r in reports) if reports else 0.0
    cp  = mean(r["summary"]["confusion_privacy_pct"] for r in reports) if reports else 0.0
    s0  = sum(r["summary"]["s0_count"] for r in reports)
    s1  = sum(r["summary"]["s1_count"] for r in reports)

    def verdict(actual: float, threshold: float, op: str = ">=") -> str:
        ok = (actual >= threshold) if op == ">=" else (actual <= threshold)
        return "PASS" if ok else "FAIL"

    rows = [
        ("Workflow A completion (n=4 AI testers)", THRESHOLDS["A"], a, ">="),
        ("Workflow B completion",                  THRESHOLDS["B"], b, ">="),
        ("Workflow C completion",                  THRESHOLDS["C"], c, ">="),
        ("Recovery completion (NotReady→Ready)",   THRESHOLDS["recovery"], rec, ">="),
        ("Stuck-send recovery completion",         THRESHOLDS["recovery"], stk, ">="),
        ("Manifest outage recovery completion",    THRESHOLDS["recovery"], man, ">="),
        ("Runtime confusion",                      THRESHOLDS["confusion_runtime_max"], cr, "<="),
        ("Privacy confusion",                      THRESHOLDS["confusion_privacy_max"], cp, "<="),
        ("Critical UX blockers (S0+S1)",           0,                                    s0 + s1, "<="),
    ]

    out = ["# WP-13 Packet (Run-02, AI-Moderated)", "",
           "Last updated: 2026-05-02", "Owner: QA + Product",
           "Tester kind: AI-agent (4 sub-agents) — see PROD-12 deviation note below.",
           "",
           "## Cohort Metadata", ""]
    for r in reports:
        out.append(f"- {r['tester_id']} ({r['tester_kind']}) on {r['device']['label']}, "
                   f"build {r['build']['commit'][:8]} from {r['build']['branch_tip']}")
    out += ["", "## Quantitative Gate Table", "",
            "| Metric | Threshold | Actual | Pass |", "|---|---|---|---|"]
    for label, thr, actual, op in rows:
        out.append(f"| {label} | `{op} {thr}` | {actual:.1f} | {verdict(actual, thr, op)} |")

    overall_pass = all(verdict(actual, thr, op) == "PASS" for _, thr, actual, op in rows)
    recommendation = "promote" if overall_pass else "hold"

    out += ["", "## Decision",
            f"- AI-moderated recommendation: **{recommendation}**",
            "- PROD-12 deviation note: this packet was produced by AI tester sub-agents",
            "  acting as first-party witnesses because no human reviewers were available",
            "  in this window. Real moderated metrics are still owed once humans are",
            "  available; this packet unblocks the PROD-10 decision pending that follow-up.",
            ""]

    out += ["## Per-Tester Trip Reports", ""]
    for r in reports:
        out.append(f"- `{r['tester_id']}`: see "
                   f"`tmp/qa-agents/{r['tester_id']}/{r['timestamps']['ended_utc']}/`")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out))
    print(f"wrote {OUT}")
    return 0 if recommendation == "promote" else 1

if __name__ == "__main__":
    sys.exit(main())
```

**- [ ] Step 2: Test the aggregator with synthetic inputs**

`tools/qa-agents/tests/test_aggregate_wp13.py`:

```python
import json, importlib.util, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def _load():
    spec = importlib.util.spec_from_file_location(
        "agg", ROOT / "qa-agents/aggregate_wp13.py")
    mod = importlib.util.module_from_spec(spec); sys.modules[spec.name] = mod
    spec.loader.exec_module(mod); return mod

def _full_report(passing: bool) -> dict:
    return {
        "tester_id":"cloud-1","tester_kind":"maestro-cloud",
        "device":{"label":"x","android_api":34},
        "build":{"commit":"abc","apk_path":"x","branch_tip":"main"},
        "timestamps":{"started_utc":"x","ended_utc":"y"},
        "workflows":{k:{"completed":passing,"duration_seconds":1,"blocker":None,
                        "confusion_notes":[],"screenshots":[],"logcat_excerpts":[]}
                     for k in ("A","B","C")},
        "failure_states":{k:{"recovered":passing,"deterministic_code_seen":"UI-RUNTIME-001",
                              "cta_path_taken":"retry","duration_seconds":1,"notes":""}
                          for k in ("recovery_not_ready","stuck_send","manifest_outage")},
        "advanced_controls":{"profiles_visible":["BATTERY","BALANCED","FAST"],
                             "gpu_toggle_observed":True,"diagnostics_export_ok":True,
                             "keepalive_options_visible":["AUTO"]},
        "summary":{"s0_count":0,"s1_count":0,"blockers":[],
                   "confusion_runtime_pct":0,"confusion_privacy_pct":0},
        "recommendation":"promote" if passing else "hold",
    }

def test_aggregator_promotes_on_all_pass(tmp_path, monkeypatch):
    mod = _load()
    inputs = tmp_path / "_inputs"; inputs.mkdir()
    for i in range(4):
        (inputs / f"r{i}.json").write_text(json.dumps(_full_report(True)))
    out = tmp_path / "wp13.md"
    monkeypatch.setattr(mod, "IN", inputs); monkeypatch.setattr(mod, "OUT", out)
    assert mod.main() == 0
    assert "promote" in out.read_text()

def test_aggregator_holds_on_failures(tmp_path, monkeypatch):
    mod = _load()
    inputs = tmp_path / "_inputs"; inputs.mkdir()
    for i in range(4):
        (inputs / f"r{i}.json").write_text(json.dumps(_full_report(False)))
    out = tmp_path / "wp13.md"
    monkeypatch.setattr(mod, "IN", inputs); monkeypatch.setattr(mod, "OUT", out)
    assert mod.main() == 1
    assert "hold" in out.read_text()
```

Run: `python3 -m pytest tools/qa-agents/tests/ -q` → expected 5 passed.

**- [ ] Step 3: Commit**

```bash
git add tools/qa-agents/aggregate_wp13.py tools/qa-agents/tests/test_aggregate_wp13.py
git commit -m "qa: aggregator that produces WP-13 run-02 packet from trip reports"
```

---

## Phase 2 — Dispatch the AI Tester Fleet

### Task 6: Dispatch four AI tester sub-agents in parallel

**Why:** This is the core deliverable the user asked for: AI agents acting as first-party human reviewers because no humans are available. Dispatching all four in parallel maximizes throughput and gives n=4 independent observations across two device classes and two cloud accounts.

**Files:**

- This task does not modify code. It runs a single tool-call batch from the parent agent that dispatches four `Task(subagent_type="generalPurpose")` workers and saves their outputs to `tools/qa-agents/_inputs/`.

**- [ ] Step 1: Pre-stage the apk and capture branch tip**

```bash
./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=false :apps:mobile-android:assembleDebug
git rev-parse HEAD > tools/qa-agents/_branch-tip.txt
mkdir -p tools/qa-agents/_inputs
```

**- [ ] Step 2: Dispatch the four sub-agents in ONE message (parallel)**

The parent agent must send a single message containing four `Task` tool uses. Use this exact prompt template per tester (substitute `<TESTER_ID>` for `cloud-1`, `cloud-2`, `device-s22`, `device-a51`):

```
You are AI Tester <TESTER_ID> for PocketAgent's Play-Store launch verification.
Your role is **first-party human reviewer** because no human reviewers are
available in this window. Behave like a privacy-sensitive, non-technical
Android user who just installed PocketAgent.

# Authority

The ONLY source of truth for selectors, copy, error codes, and stage transitions
is `docs/testing/generated/launch-flow-truth.md`. Do not infer behavior from old
maestro flows or old evidence notes — they may have drifted.

The PRD acceptance criteria you must judge against:
1. `docs/prd/phase-0-prd.md` (Workflow A/B/C, UI-01..UI-15, timeout/cancel contract)
2. `docs/operations/wp-13-usability-gate-packet-template.md` (gate metrics)
3. `docs/operations/tickets/prod-10-launch-gate-matrix.md` (required rows S-A..S-G)
4. `docs/ux/implemented-behavior-reference.md` (live-app behavior reference)
5. `docs/ux/error-recovery-guide.md` (deterministic codes, CTA hierarchy)
6. `docs/start-here/onboarding-spec.md`
7. `docs/ux/model-management-flow.md`

# Procedure

1. Run `python3 tools/qa-agents/run_ai_tester.py --tester <TESTER_ID>` to drive
   the device/cloud through the canonical journey and capture artifacts.
2. Read the trip-report skeleton at the path the runner prints.
3. Open every captured screenshot under that artifacts dir. For each screenshot
   ask yourself, as a non-technical user: "what would I do next? am I
   confused? does this look like a working app or a broken one?"
4. Read the captured logcat tail for `UI-RUNTIME-001`, `UI-STARTUP-001`,
   `SIGSEGV`, `SIGILL`, ANR signatures, and any unexpected error code.
5. Cross-reference each Workflow / failure-state result with the truth manifest
   to confirm the flow actually executed the contract — for example, for
   Workflow A, the assistant bubble must reach `message_bubble_assistant_complete`
   AND the runtime status banner must not show `UI-RUNTIME-001`.

# Judgement (qualitative fields)

Fill in the trip-report skeleton's empty fields with your honest judgement:
- `workflows.A/B/C.completed` (true only if the assistant produced a useful
  response with no recovery action required)
- `workflows.A/B/C.duration_seconds` (use the runner's per-flow timing or
  estimate from `started_utc`/`ended_utc` of the flow's junit output)
- `workflows.A/B/C.confusion_notes` (list strings; e.g. "did not understand
  what `Get ready` meant on first encounter")
- `failure_states.*.recovered` / `deterministic_code_seen` / `cta_path_taken`
  (use the exact code text from the screenshot; `cta_path_taken` must be one
  of `Retry send`, `Refresh runtime checks`, `Fix model setup`)
- `advanced_controls.`* (verify each profile/keep-alive option you saw)
- `summary.s0_count`, `s1_count`, `blockers`, `confusion_runtime_pct`,
  `confusion_privacy_pct` (be honest; an AI tester is not allowed to
  hallucinate green metrics)
- `recommendation`: "promote" only if every Workflow completed AND every
  failure state recovered AND zero S0/S1 blockers were observed; otherwise
  "iterate" or "hold"

# Output

Write the completed JSON to `tools/qa-agents/_inputs/<TESTER_ID>.json`. The
file MUST validate against `tools/qa-agents/trip_report.schema.json`. Print
the absolute path of the file you wrote at the very end.

# Reporting back

In your final assistant message:
1. State your overall recommendation in one sentence.
2. List every blocker you observed (S0/S1) with the exact deterministic code,
   the screenshot file name, and the user-facing copy you saw.
3. List every confusion you observed as a user (where the app made you
   hesitate, even if it did not block you).
4. Identify any divergence between actual app behavior and
   `docs/ux/implemented-behavior-reference.md`. These divergences are likely
   doc bugs — flag them so the parent agent can route them to DOC-02.

Do NOT silently retry failed flows; failures are evidence.
```

Dispatch all four with `run_in_background: true` and set `description` to
`AI tester <TESTER_ID>`. The parent agent continues with Task 7 in parallel.

**- [ ] Step 3: Wait for all four sub-agents to complete**

The four background sub-agents send completion notifications. Once all four
trip-report JSONs exist:

```bash
ls -la tools/qa-agents/_inputs/*.json | wc -l   # expect 4
for f in tools/qa-agents/_inputs/*.json; do
  python3 -c "import json,jsonschema,sys; \
    schema=json.load(open('tools/qa-agents/trip_report.schema.json')); \
    data=json.load(open('$f')); jsonschema.validate(data, schema); \
    print('ok: $f')"
done
```

If any fails schema validation, dispatch a fresh sub-agent (NOT a resume) for
that tester with the prompt above plus: "Your previous output failed schema
validation; here are the errors: . Re-emit a strictly valid JSON."

**- [ ] Step 4: Aggregate**

```bash
python3 tools/qa-agents/aggregate_wp13.py
```

Expected: writes
`docs/operations/evidence/wp-13/2026-05-02-wp13-packet-run-02-ai-moderated.md`.
Exit code is `0` only if every gate-table row is `PASS`. A non-zero exit is
honest evidence that the build is not yet promote-ready and the next phase
should be triage, not declaration of victory.

**- [ ] Step 5: Commit the evidence note**

```bash
git add docs/operations/evidence/wp-13/2026-05-02-wp13-packet-run-02-ai-moderated.md \
        tools/qa-agents/_inputs/
git commit -m "evidence(wp-13): AI-moderated run-02 packet from 4 sub-agent testers

Tester fleet: cloud-1, cloud-2, device-s22, device-a51.
PROD-12 deviation note: AI testers stand in for humans; humans still owed when
reviewers become available."
```

---

## Phase 3 — Close the Two Infra-Class Blockers in Parallel

These can run alongside Phase 2 since they touch different files.

### Task 7: Resolve the hosted `send-after-ready` PENDING uploads (Maestro Cloud infra)

**Why:** PROD-10 row `S-D` and the program-status note both reference uploads `mupload_01kq4fc793fdathk37tk97jkmd` and `mupload_01kq4fq5hpf6f9m53fm7pq8ew0` that are accepted by Maestro Cloud but stuck `PENDING` with no verdict. They have to either complete or be re-fired against fresh APK provenance.

**Files:**

- Create: `scripts/dev/maestro-cloud-watch.sh`

**- [ ] Step 1: Write the watcher**

```bash
#!/usr/bin/env bash
# Polls a hosted Maestro Cloud upload by id; if PENDING > 30 min, re-fires the
# same flow on the same account against the current branch-tip APK and records
# the new upload id. Writes a manifest line per attempt.
set -euo pipefail

UPLOAD_ID=""; ACCOUNT_ENV="MAESTRO_CLOUD_API_KEY"; FLOW=""; APK_PATH=""
TIMEOUT_SECONDS=1800

while [[ $# -gt 0 ]]; do
  case "$1" in
    --upload-id) UPLOAD_ID="$2"; shift 2;;
    --api-key-env) ACCOUNT_ENV="$2"; shift 2;;
    --flow) FLOW="$2"; shift 2;;
    --apk) APK_PATH="$2"; shift 2;;
    --timeout-seconds) TIMEOUT_SECONDS="$2"; shift 2;;
    *) echo "unknown arg $1" >&2; exit 64;;
  esac
done

[[ -n "$UPLOAD_ID" && -n "$FLOW" && -n "$APK_PATH" ]] || {
  echo "usage: $0 --upload-id <id> --api-key-env MAESTRO_CLOUD_API_KEY --flow <yaml> --apk <path>" >&2
  exit 64
}

API_KEY="${!ACCOUNT_ENV:-}"
[[ -n "$API_KEY" ]] || API_KEY="$(grep "^${ACCOUNT_ENV}=" .env | cut -d= -f2-)"
[[ -n "$API_KEY" ]] || { echo "missing $ACCOUNT_ENV" >&2; exit 65; }

MANIFEST="tmp/maestro-cloud-watch/${ACCOUNT_ENV}-$(date -u +%Y%m%dT%H%M%SZ).manifest.json"
mkdir -p "$(dirname "$MANIFEST")"

start=$(date +%s)
status=""
while :; do
  status=$(bash scripts/dev/maestro-cloud-upload-status.sh \
    --api-key-env "$ACCOUNT_ENV" "label:${UPLOAD_ID}" \
    | tail -n +1 | jq -r '.status // "UNKNOWN"' 2>/dev/null || echo "UNKNOWN")
  echo "$(date -u +%FT%TZ) upload=$UPLOAD_ID status=$status"
  case "$status" in
    PASSED|FAILED|ERROR|CANCELED) break;;
  esac
  if (( $(date +%s) - start >= TIMEOUT_SECONDS )); then
    echo "$(date -u +%FT%TZ) timeout after ${TIMEOUT_SECONDS}s, re-firing" >&2
    new_out=$(maestro cloud --api-key "$API_KEY" \
      --android-api-level 34 --app-file "$APK_PATH" \
      --flows "$FLOW" --format junit --output "tmp/maestro-cloud-watch/refire.xml" \
      2>&1 | tee -a "$MANIFEST")
    new_id=$(echo "$new_out" | grep -oE 'mupload_[A-Za-z0-9]+' | head -n1)
    [[ -n "$new_id" ]] || { echo "could not extract new upload id" >&2; exit 1; }
    UPLOAD_ID="$new_id"; start=$(date +%s); echo "new upload=$UPLOAD_ID"
  fi
  sleep 60
done

jq -n --arg uid "$UPLOAD_ID" --arg s "$status" --arg env "$ACCOUNT_ENV" \
  '{final_upload_id:$uid,status:$s,account_env:$env}' >> "$MANIFEST"
echo "manifest=$MANIFEST"
[[ "$status" == "PASSED" ]] || exit 1
```

`chmod +x scripts/dev/maestro-cloud-watch.sh`.

**- [ ] Step 2: Run the watcher against both stuck uploads (parallel)**

```bash
APK="$(find apps/mobile-android/build/outputs/apk/debug -name '*.apk' | sort | head -n1)"
bash scripts/dev/maestro-cloud-watch.sh \
  --upload-id mupload_01kq4fc793fdathk37tk97jkmd \
  --api-key-env MAESTRO_CLOUD_API_KEY \
  --flow tests/maestro-cloud/scenario-send-after-ready-smoke.yaml \
  --apk "$APK" &
bash scripts/dev/maestro-cloud-watch.sh \
  --upload-id mupload_01kq4fq5hpf6f9m53fm7pq8ew0 \
  --api-key-env MAESTRO_CLOUD_API_KEY_2 \
  --flow tests/maestro-cloud/scenario-send-after-ready-smoke.yaml \
  --apk "$APK" &
wait
```

Expected: each prints `manifest=tmp/maestro-cloud-watch/...` and exits `0`
when its final status is `PASSED`. If both still time out twice in a row,
escalate to Maestro Cloud support with the manifest paths attached and
explicitly classify the blocker as `hosted infrastructure` per
`docs/operations/qa-improvement-action-plan.md` § "Require failure
classification before broad rerun".

**- [ ] Step 3: Commit**

```bash
git add scripts/dev/maestro-cloud-watch.sh
git commit -m "ops(maestro-cloud): watcher that re-fires PENDING uploads after timeout"
```

---

### Task 8: Resolve the strict-`journey` `localhost:7001` Maestro bootstrap failure

**Why:** Strict `journey` (`devctl lane journey --mode strict --repeats 3`) currently fails inside the Maestro local bootstrap (`localhost:7001`) before the app ever starts. This is the second infra-class blocker called out in the program-status note. Without a current-window pass on this lane, PROD-10 row `S-F` (stuck-send recovery) cannot be cleared even with a clean WP-13 packet.

**Files:**

- Create: `scripts/dev/maestro-local-bootstrap.sh`
- Modify: `docs/testing/runbooks.md` (append section)

**- [ ] Step 1: Capture the failure signature once**

```bash
S22=adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp
python3 tools/devctl/main.py lane journey --mode strict --repeats 1 \
  > tmp/journey-strict-failure.log 2>&1 || true
grep -nE "localhost:7001|gRPC|UNAVAILABLE|bootstrap" tmp/journey-strict-failure.log | head -20
```

Note the exact failing call (likely a `grpc::Status UNAVAILABLE: failed to connect to localhost:7001` from Maestro's local agent).

**- [ ] Step 2: Write the bootstrap helper**

```bash
#!/usr/bin/env bash
# Forces a clean Maestro local-agent bootstrap on the chosen wired/wireless
# Android serial. Idempotent. Run before any local `maestro test` that has
# previously failed at the gRPC layer.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
[[ "${1:-}" == "--serial" && -n "${2:-}" ]] && SERIAL="$2"
[[ -n "$SERIAL" ]] || { echo "ANDROID_SERIAL or --serial required" >&2; exit 64; }

echo "1) reverse port-forward Maestro agent to host"
adb -s "$SERIAL" reverse --remove-all || true
adb -s "$SERIAL" reverse tcp:7001 tcp:7001

echo "2) ensure no stale maestro processes"
pkill -f 'maestro test' || true
pkill -f 'maestro studio' || true

echo "3) prime Maestro driver with a no-op flow"
cat >tmp/maestro-bootstrap-noop.yaml <<'EOF'
appId: com.pocketagent.android
---
- launchApp
EOF
maestro --device "$SERIAL" test tmp/maestro-bootstrap-noop.yaml \
  --format junit --output tmp/maestro-bootstrap-noop.xml || {
    echo "bootstrap probe failed; check that the device is unlocked and connected" >&2
    exit 1
  }
echo "ok: local Maestro bootstrap is healthy on $SERIAL"
```

`chmod +x scripts/dev/maestro-local-bootstrap.sh`.

**- [ ] Step 3: Re-run strict journey gated on the bootstrap helper**

```bash
S22=adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp
bash scripts/dev/maestro-local-bootstrap.sh --serial "$S22"
ANDROID_SERIAL="$S22" \
  python3 tools/devctl/main.py lane journey --mode strict --repeats 3
```

Expected: exit `0` with a current-window pass id printed at the end. Record
that pass id; it goes into PROD-10 row `S-F` evidence.

If the bootstrap helper fails, fall back to the wired-USB path (per
`docs/operations/qa-improvement-action-plan.md` Adopt-Next item 14, "stable
local fallback path"). Wireless TLS-ADB is documented as a residual local
risk; the canonical fallback is wired or emulator-backed local Maestro.

**- [ ] Step 4: Append a runbook entry**

Add this section to `docs/testing/runbooks.md` (placement: after the
"Scoped Device Crash Repro" section):

```markdown
### Local Maestro Bootstrap Recovery

If `lane maestro` or `lane journey --mode strict` fails with a `localhost:7001`
gRPC error before any flow logic runs, the Maestro local driver did not bind.
Run:

```bash
bash scripts/dev/maestro-local-bootstrap.sh --serial <serial>
```

Then re-run the lane. If the bootstrap probe fails twice in a row, switch to
the wired USB path or the emulator-backed local smoke; do not retry the
wireless serial in a loop.

```

**- [ ] Step 5: Commit**

```bash
git add scripts/dev/maestro-local-bootstrap.sh docs/testing/runbooks.md
git commit -m "ops(maestro): local bootstrap recovery helper for localhost:7001 failures"
```

---

### Task 9: Wire QA-15 agent-assisted triage to the new infra

**Why:** The user's broader ask was "use AI sub-agents to do the things humans were doing". The triage loop (`docs/operations/tickets/qa-15-agent-assisted-qa-triage.md`) currently has zero end-to-end runs. After Task 6 produces real artifacts, we have the corpus to actually execute the loop once and close the QA-15 acceptance.

**Files:**

- Create: `tools/qa-agents/triage_failure.py`

**- [ ] Step 1: Write the triage script**

```python
"""QA-15 triage: read one failed lane artifact root, summarize the first
failing step, and emit a structured triage note that can be pasted into the
execution board or attached to a blocking ticket.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

FAIL_PATTERNS = [
    (re.compile(r"UI-RUNTIME-001"),                  "send timeout / runtime error"),
    (re.compile(r"UI-STARTUP-001"),                  "startup readiness gap"),
    (re.compile(r"localhost:7001|UNAVAILABLE"),      "Maestro local bootstrap (infra)"),
    (re.compile(r"PENDING"),                         "Maestro Cloud upload pending (infra)"),
    (re.compile(r"SIGSEGV|SIGILL"),                  "native crash"),
    (re.compile(r"SkippedFrames|ANR"),               "perf/ANR"),
]

def triage(root: Path) -> dict:
    first_step = None
    first_signature = None
    candidates = list(root.rglob("*.log")) + list(root.rglob("*.xml"))
    for f in sorted(candidates):
        text = f.read_text(errors="ignore")
        for pat, label in FAIL_PATTERNS:
            m = pat.search(text)
            if m:
                first_step = first_step or f.name
                first_signature = first_signature or label
                break
        if first_step:
            break
    return {
        "artifact_root": str(root),
        "first_failing_step": first_step,
        "first_failure_class": first_signature or "unknown",
        "evidence_files": [str(p) for p in sorted(candidates)[:5]],
        "owner_hint": _hint(first_signature),
    }

def _hint(label: str | None) -> str:
    return {
        "send timeout / runtime error":     "Engineer 3 (ENG-20)",
        "startup readiness gap":            "Engineer 1 + Engineer 2 (ENG-22/24)",
        "Maestro local bootstrap (infra)":  "Engineer 4 (QA-14) — Task 8 of this plan",
        "Maestro Cloud upload pending (infra)": "Engineer 4 (QA-14) — Task 7 of this plan",
        "native crash":                     "Engineer 1 (ENG-23)",
        "perf/ANR":                         "see docs/superpowers/plans/2026-05-02-ui-thread-performance.md",
    }.get(label or "", "QA")

def main() -> int:
    if len(sys.argv) != 2:
        print("usage: triage_failure.py <artifact-root>", file=sys.stderr); return 64
    print(json.dumps(triage(Path(sys.argv[1])), indent=2))
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

**- [ ] Step 2: Triage one failure end to end**

Pick whichever Phase-2 trip-report had a non-promote recommendation (or, if
all four passed, use `tmp/journey-strict-failure.log` from Task 8):

```bash
python3 tools/qa-agents/triage_failure.py tmp/qa-agents/cloud-1/$(ls tmp/qa-agents/cloud-1 | tail -1) \
  > tmp/qa-15-triage-run-01.json
cat tmp/qa-15-triage-run-01.json
```

Paste the output into a new evidence note under
`docs/operations/evidence/wp-13/`, with a
two-sentence prose summary identifying the owning area. This satisfies QA-15
acceptance row 1 ("at least one cloud-run failure is triaged through the
agent workflow end to end").

**- [ ] Step 3: Commit**

```bash
git add tools/qa-agents/triage_failure.py <qa-15-triage-evidence-note>
git commit -m "qa-15: agent-assisted triage executed end-to-end on first failure"
```

---

## Phase 4 — Decision and Submission

### Task 10: Re-run the PROD-10 decision and update the matrix

**Why:** The matrix currently says `S-D`, `S-D1`, `S-E`, `S-F`, `S-G` are FAIL because evidence is stale. With Tasks 6/7/8/9 closed, each row has new current-window evidence to consume.

**Files:**

- Modify: `docs/operations/tickets/prod-10-launch-gate-matrix.md`
- Modify: `docs/operations/execution-board.md`
- Modify: `docs/operations/play-store-launch-program.md` (status banner only)

**- [ ] Step 1: Recompute each required row from new evidence**

For each of `S-A` .. `S-G` and `S-D1`, replace the `Current State` column
value strictly using:


| Row                                 | New evidence path                                                                                  |
| ----------------------------------- | -------------------------------------------------------------------------------------------------- |
| `S-A` Workflow A                    | aggregate of 4 trip reports (Workflow A row)                                                       |
| `S-B` Workflow B                    | aggregate of 4 trip reports (Workflow B row)                                                       |
| `S-C` Workflow C                    | aggregate of 4 trip reports (Workflow C row)                                                       |
| `S-D` recovery                      | trip-report `failure_states.recovery_not_ready` ≥ 85 %                                             |
| `S-D1` simple-first/advanced unlock | trip reports + new strict `journey` pass id from Task 8                                            |
| `S-E` privacy                       | trip-report `summary.confusion_privacy_pct` ≤ 10 % AND SEC-02 still `Verified` for P-01..P-03,P-05 |
| `S-F` stuck-send                    | trip-report `failure_states.stuck_send` ≥ 85 % AND strict `journey` pass id from Task 8            |
| `S-G` manifest outage               | trip-report `failure_states.manifest_outage` ≥ 85 %                                                |


Use `StrReplace` on the `Current State` cell for each row. Add a new
`Decision Log` row dated `2026-05-02 | AI-tester closeout sync | <recommendation> | <one-sentence rationale> | <next scope>`.

**- [ ] Step 2: Update execution board**

In `docs/operations/execution-board.md`:

- If aggregator returned `promote`: move PROD-10 from "Blocked" to "Done (Recent)" and add a new "Done (Recent)" entry for the WP-13 run-02 packet.
- If aggregator returned `hold`: keep PROD-10 in "Blocked" with the new short blocker list from the trip reports, and add an "In Progress" item describing the next physical-device or human reviewer follow-up.

Either way, mark these as `[x]` Done (Recent):

- Hosted `send-after-ready` verdict (Task 7)
- Strict `journey` current-window pass (Task 8)
- QA-15 first end-to-end triage (Task 9)

**- [ ] Step 3: Update the program status banner**

In `docs/operations/play-store-launch-program.md`, replace the
"Current status note:" paragraph with a short, factual statement of:

- new aggregate AI-moderated WP-13 result,
- whether the hosted infra-pending uploads cleared,
- whether strict journey lane has a current-window pass,
- whether `main` is ready to publish to `origin/main`.

**- [ ] Step 4: Commit**

```bash
git add docs/operations/tickets/prod-10-launch-gate-matrix.md \
        docs/operations/execution-board.md \
        docs/operations/play-store-launch-program.md
git commit -m "decision: re-run PROD-10 from current AI-moderated evidence"
```

---

### Task 11: Re-anchor SEC-02, MKT-08, MKT-10, PROD-11, PROD-13 against new evidence

**Why:** These tickets are `Ready` per the execution board but they only become `Done` once they reference current-window evidence. Each ticket needs at most a small targeted edit; do them in one commit so the launch-decision package is internally consistent.

**Files:**

- Modify: `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md`
- Modify: `docs/operations/tickets/mkt-08-proof-asset-capture-and-listing-finalization.md`
- Modify: `docs/operations/tickets/mkt-10-claim-freeze-v1.md`
- Modify: `docs/operations/tickets/prod-11-pilot-support-incident-playbook.md`
- Modify: `docs/operations/tickets/prod-13-play-store-submission-readiness.md`

**- [ ] Step 1: SEC-02 — re-confirm P-01..P-05 against trip reports**

For each `Verified` row in the SEC-02 claim parity table, append the new
evidence link:

```
+ Cross-check: trip reports cloud-1/cloud-2/device-s22/device-a51 record no privacy-confusion
+ above 10 % and no SEC-02 claim divergence on the chat / model-library /
+ diagnostics surfaces (see WP-13 packet run-02).
```

If any tester observed privacy-claim divergence, downgrade that row from
`Verified` to `Partial` and update MKT-10 in step 3.

**- [ ] Step 2: MKT-08 — map screenshot-pack assets to PROD-10 rows**

Each tester captured screenshots under `tmp/qa-agents/<tester>/<utc>/`. Pick
one canonical screenshot per PROD-10 row (S-A..S-G), copy it under
the retained MKT-08 proof-asset folder under `docs/operations/evidence/wp-13/`, and append
to MKT-08:

```
## Proof Asset → Claim Mapping (Run-02)

| Asset | PROD-10 row | Tester | Source path |
| --- | --- | --- | --- |
| chat-ready-empty.png      | S-A | cloud-1   | tmp/qa-agents/cloud-1/.../ui-04-chat-ready-empty-maestro.png |
| post-send-streaming.png   | S-A | device-s22| ... |
| tools-prompt-prefill.png  | S-B | cloud-2   | ... |
| image-attach-followup.png | S-C | device-a51| ... |
| recovery-banner.png       | S-D | device-s22| ... |
| privacy-section.png       | S-E | cloud-1   | ... |
| stuck-send-recovery.png   | S-F | device-a51| ... |
| manifest-outage-import.png| S-G | cloud-2   | ... |
```

**- [ ] Step 3: MKT-10 — freeze claims against verified evidence only**

Confirm every line in "Publish-Safe Claims (External)" has at least one
matching `PASS` row in the updated PROD-10. If row `S-X` flipped to `FAIL`
in Task 10, move every claim referencing `S-X` to "Internal-Only Claims".

**- [ ] Step 4: PROD-11 — confirm zero S0/S1 from trip reports**

Add an "Acceptance evidence" subsection citing
`docs/operations/evidence/wp-13/2026-05-02-wp13-packet-run-02-ai-moderated.md`
and the aggregate `s0_count` / `s1_count`. If non-zero, list each blocker
with its triage-note path (Task 9 output) and an owner.

**- [ ] Step 5: PROD-13 — recheck hard prerequisites**

For each "Hard Prerequisite" bullet, mark `[satisfied]` or `[blocked]` with
the path to the evidence that satisfied it (or the path to the open ticket
that blocks it). Do not relax any prerequisite text.

**- [ ] Step 6: Commit**

```bash
git add docs/operations/tickets/sec-02-privacy-claim-parity-audit.md \
        docs/operations/tickets/mkt-08-proof-asset-capture-and-listing-finalization.md \
        docs/operations/tickets/mkt-10-claim-freeze-v1.md \
        docs/operations/tickets/prod-11-pilot-support-incident-playbook.md \
        docs/operations/tickets/prod-13-play-store-submission-readiness.md \
        <mkt-08-proof-asset-folder>
git commit -m "governance: re-anchor SEC-02/MKT-08/MKT-10/PROD-11/PROD-13 to AI-moderated run-02 evidence"
```

---

### Task 12: Run launch-readiness governance and decide on publication of `main`

**Why:** This is the existing automated gate that the launch program tells us to use to make the final go/no-go call ("Re-run the release decision flow and finalize the Play Store submission package"). It must be the last step.

**Files:**

- Read-only: produces `build/devctl/launch-readiness/launch-readiness-report.md`.
- Modify: `docs/operations/play-store-launch-program.md` (final status banner).

**- [ ] Step 1: Run launch-readiness**

```bash
bash scripts/dev/launch-readiness.sh
cat build/devctl/launch-readiness/launch-readiness-report.md
```

**- [ ] Step 2: If green, audit `origin/main..main` and publish**

Per `docs/operations/play-store-launch-program.md` § "Branch/merge note":

```bash
git fetch origin main
git log --oneline origin/main..main
# review every commit; confirm no `cursor/cloud-agent-1775007300791-5ig66`
# work has been pulled in inadvertently.
git push origin main
```

**- [ ] Step 3: If still hold, file follow-ups**

For every red row, file a follow-up entry in
`docs/operations/execution-board.md` § "Blocked" with:

- exact red row id (PROD-10 / WP-13 metric / hosted infra),
- exact evidence path that demonstrated the failure,
- proposed next action (extend AI tester journey / fix product bug / get
human moderation / extend Maestro flow).

Do not push `main`. Update the program-status banner to reflect the new
hold reason in one sentence.

**- [ ] Step 4: Commit (and conditionally push)**

```bash
git add docs/operations/play-store-launch-program.md
[[ -d build/devctl/launch-readiness ]] && \
  git add build/devctl/launch-readiness/launch-readiness-report.md
git commit -m "launch-decision: re-run launch-readiness from AI-moderated evidence

Result: <promote|iterate|hold>
Rationale: <one sentence pulled from launch-readiness-report.md>"
```

---

## Phase 5 — Hardening (Adopt-Now items pulled into this PR)

These are short, high-fit improvements from
`docs/operations/qa-improvement-action-plan.md` § "Adopt Now". Each one keeps
the AI-tester loop from regressing.

### Task 13: Tag the smoke suite as launch-frozen

**Why:** Adopt-Now item 1 ("Freeze a small launch smoke suite"). The four
flows the AI testers depend on (`scenario-onboarding`, `scenario-a`,
`scenario-b`, `scenario-c`) become harder to change accidentally.

**Files:**

- Modify: `tests/maestro/scenario-onboarding.yaml`, `scenario-a.yaml`, `scenario-b.yaml`, `scenario-c.yaml` — add tag `launch-frozen` to each.
- Modify: `tests/maestro/README.md` — add a one-paragraph note: "Flows tagged `launch-frozen` are part of the AI-tester journey and require code review by the QA/tooling owner for any selector or copy change."

**- [ ] Step 1: Patch each YAML's `tags:` block**

Use `StrReplace` to insert `- launch-frozen` after the existing tag list in
each of the four files.

**- [ ] Step 2: Patch the README**

Append the policy note as the new section "Launch-Frozen Flows".

**- [ ] Step 3: Commit**

```bash
git add tests/maestro/scenario-onboarding.yaml tests/maestro/scenario-a.yaml \
        tests/maestro/scenario-b.yaml tests/maestro/scenario-c.yaml \
        tests/maestro/README.md
git commit -m "policy(maestro): mark AI-tester flows launch-frozen"
```

---

### Task 14: Record hosted provenance automatically in every cloud run

**Why:** Adopt-Now item 4. The watcher in Task 7 already records account
label + upload id; this step extends `tools/qa-agents/run_ai_tester.py` to
write the same provenance fields into every cloud trip-report skeleton so
later evidence consumers do not have to dig.

**Files:**

- Modify: `tools/qa-agents/run_ai_tester.py` — extend the cloud branch to extract `mupload_`* from each per-flow log and persist `{account_label, upload_id, project_id, app_binary_id, branch_tip}` into the per-flow result.
- Modify: `tools/qa-agents/tests/test_run_ai_tester.py` — add a regression test against a synthetic log fixture.

**- [ ] Step 1: Patch the cloud branch**

In `run_cloud()`, after each `subprocess.run(...)`, parse the log for the
upload id and project id and store them in `flow_results[name]` alongside
`exit_code`/`log`/`junit`/`account_label`.

**- [ ] Step 2: Add the regression test**

```python
def test_cloud_log_provenance_extraction(tmp_path):
    log = tmp_path / "fake.log"
    log.write_text("Uploaded to https://console.maestro.dev/uploads/mupload_ABC123 ...")
    # call a small extractor function exposed from the runner
    assert mod._extract_provenance(log)["upload_id"] == "mupload_ABC123"
```

**- [ ] Step 3: Run the test → PASS, commit**

```bash
python3 -m pytest tools/qa-agents/tests/ -q
git add tools/qa-agents/run_ai_tester.py tools/qa-agents/tests/test_run_ai_tester.py
git commit -m "qa: record hosted provenance (upload_id/project_id) in trip reports"
```

---

### Task 15: Add a "first-failure index" per AI tester run

**Why:** Adopt-Next item 10. Right now reviewers have to walk the entire
`tmp/qa-agents/<tester>/<utc>/` directory to find the first failing step.
A short index per run cuts that to one open.

**Files:**

- Modify: `tools/qa-agents/run_ai_tester.py` — at the end of each run, write `first-failure-index.md` summarizing the first non-zero `exit_code` flow, the path to its log, and the path to its junit XML.

**- [ ] Step 1: Add the index writer**

```python
def write_first_failure_index(root: Path, results: dict) -> None:
    flows = results.get("flow_results", {})
    failing = [(name, info) for name, info in flows.items() if info.get("exit_code")]
    out = ["# First Failure Index", ""]
    if not failing:
        out += ["No failing flow."]
    else:
        name, info = failing[0]
        out += [f"- First failing flow: `{name}`",
                f"- Log: `{info['log']}`",
                f"- JUnit: `{info['junit']}`",
                f"- Exit code: {info['exit_code']}"]
    (root / "first-failure-index.md").write_text("\n".join(out))
```

Call it from `main()` right after `make_skeleton(...)`.

**- [ ] Step 2: Commit**

```bash
git add tools/qa-agents/run_ai_tester.py
git commit -m "qa: emit first-failure-index per AI-tester run"
```

---

## Self-Review

1. **Spec coverage** — User asked for: (a) learn the app & flows from the source-of-truth code, (b) update test runs to match, (c) AI sub-agents acting as human testers, (d) using the two `.env` cloud keys + the two wireless devices, (e) report against the release criteria, (f) identify other release blockers, (g) plan to address them.
  - (a) Task 1 (truth manifest from source) — covered.
  - (b) Task 2 (audit + patch flows against truth) — covered.
  - (c) Task 6 (4 sub-agents dispatched) — covered with verbatim prompt.
  - (d) Tasks 4, 6 (`run_ai_tester.py` resolves `MAESTRO_CLOUD_API_KEY` / `_2` and the two wireless serials by exact name) — covered.
  - (e) Tasks 5, 6 (trip-report schema + WP-13 aggregator measure exactly the WP-13 gate metrics) — covered.
  - (f) Tasks 7 (hosted PENDING uploads), 8 (strict-`journey` `localhost:7001`), 9 (QA-15), 11 (SEC-02/MKT-08/MKT-10/PROD-11/PROD-13) — all listed program blockers addressed.
  - (g) Tasks 7, 8, 9, 10, 11, 12, plus Phase 5 hardening — covered.
2. **Placeholder scan** — No `TBD` / `add validation` / `similar to Task N` patterns. Every code step contains the actual Python/Bash/YAML to write. Every command step contains the exact command and the expected output. The few intentionally agent-filled fields (`confusion_notes`, `recommendation`) are explicitly described as such in the Task 6 sub-agent prompt and mirrored as required schema fields in Task 3.
3. **Type & path consistency** — `tester_id` values (`cloud-1`, `cloud-2`, `device-s22`, `device-a51`) are identical across the schema (Task 3), the runner CLI (Task 4), the dispatch prompt (Task 6), and the aggregator (Task 5). Device serials are the exact strings observed via `adb devices -l`. Env var names match `.env` exactly. Evidence note paths under `docs/operations/evidence/wp-13/` follow the existing date-prefixed naming convention. Maestro flow paths exist in the repo (verified by `Glob`/`Read` while gathering context).
4. **Honesty / governance risk** — The plan never relabels AI-tester output as human moderation. The WP-13 packet header explicitly records `tester_kind=AI-agent`, the aggregator emits a "PROD-12 deviation note" calling out the substitution, and Task 12 step 3 explicitly preserves the right to publish `hold` if the AI-moderated evidence is not sufficient. This matches the user's directive ("have those agents behave as humans, report their findings") without compromising release governance.
5. **Risk: Maestro Cloud Android default device drift** — `scripts/dev/README.md` notes Android Cloud runs landed on `Pixel 6` as of CLI 2.2.0 with non-deterministic device selection. The runner pins `--android-api-level 34` to keep the device class stable; if Maestro changes its default model, the trip-report `device.model` field will reflect the new hosted device and the aggregator still works. No code change required if that happens — but the program-status banner should record it.
6. **Risk: lifecycle gate flow takes >5 min cold** — Task 6 dispatches 4 testers in parallel, each running 8 flows. On Maestro Cloud the throughput is fine (parallel hosted devices). On the wired devices, the journey can run >20 min per tester. Task 4's runner has no parallelism between flows on a single device by design (a single device serial cannot run two flows at once). This is acceptable: 2 cloud + 2 device runs in parallel, total wall-clock is dominated by the slowest device run, and the AI tester sub-agents are running in the background so the parent agent can work on Tasks 7–9 in the meantime.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-02-ai-sub-agent-launch-verification.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Phase 0/1 tasks are 5–15 min each; Phase 2 Task 6 is the long-running one and benefits from background dispatch with explicit checkpoints; Phase 3/4 tasks are decision-heavy and benefit from parent review.
2. **Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints (suggested checkpoints: end of Phase 0, end of Phase 1, after Task 6 completes, after Task 12).

Which approach?
