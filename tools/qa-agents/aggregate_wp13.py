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
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(IN.glob("*.json"))]

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
        # Artifact dir matches tmp/qa-agents/<tester>/<started_utc>/ (runner uses started stamp).
        out.append(f"- `{r['tester_id']}`: see "
                   f"`tmp/qa-agents/{r['tester_id']}/{r['timestamps']['started_utc']}/`")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out), encoding="utf-8")
    print(f"wrote {OUT}")
    return 0 if recommendation == "promote" else 1

if __name__ == "__main__":
    sys.exit(main())
