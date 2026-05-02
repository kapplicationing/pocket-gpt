"""QA-15 triage: read one failed lane artifact root, summarize the first
failing step, and emit structured triage JSON for the execution board.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

FAIL_PATTERNS = [
    (re.compile(r"UI-RUNTIME-001"), "send timeout / runtime error"),
    (re.compile(r"UI-STARTUP-001"), "startup readiness gap"),
    (re.compile(r"localhost:7001|UNAVAILABLE"), "Maestro local bootstrap (infra)"),
    (re.compile(r"PENDING"), "Maestro Cloud upload pending (infra)"),
    (re.compile(r"SIGSEGV|SIGILL"), "native crash"),
    (re.compile(r"SkippedFrames|ANR"), "perf/ANR"),
]


def triage(root: Path) -> dict:
    first_step = None
    first_signature = None
    candidates = list(root.rglob("*.log")) + list(root.rglob("*.xml"))
    for f in sorted(candidates):
        try:
            text = f.read_text(errors="ignore")
        except OSError:
            continue
        for pat, label in FAIL_PATTERNS:
            if pat.search(text):
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
        "send timeout / runtime error": "Engineer 3 (ENG-20)",
        "startup readiness gap": "Engineer 1 + Engineer 2 (ENG-22/24)",
        "Maestro local bootstrap (infra)": "Engineer 4 (QA-14)",
        "Maestro Cloud upload pending (infra)": "Engineer 4 (QA-14)",
        "native crash": "Engineer 1 (ENG-23)",
        "perf/ANR": "see docs/superpowers/plans/2026-05-02-ui-thread-performance.md",
    }.get(label or "", "QA")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: triage_failure.py <artifact-root>", file=sys.stderr)
        return 64
    print(json.dumps(triage(Path(sys.argv[1])), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
