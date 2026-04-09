from __future__ import annotations

import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT, run_subprocess

try:
    import yaml  # type: ignore
except ModuleNotFoundError:  # pragma: no cover - dependency guard is handled at runtime.
    yaml = None

CANONICAL_DOCS = [
    "scripts/dev/README.md",
    "docs/testing/test-strategy.md",
    "docs/testing/runbooks.md",
]

NON_CANONICAL_DOCS = [
    "README.md",
    "docs/README.md",
    "docs/operations/README.md",
]

PR_REQUIRED_PATTERNS: list[tuple[str, str]] = [
    (
        r"- \[x\] I ran `bash scripts/dev/test\.sh` \(or `bash scripts/dev/test\.sh ci`\) and it passed\.",
        "test command checkbox must be checked",
    ),
    (
        r"- \[x\] I used canonical orchestrator lanes \(`python3 tools/devctl/main\.py lane \.\.\.`\) directly or via the `scripts/dev/\*` wrappers\.",
        "orchestrator lane checkbox must be checked",
    ),
    (
        r"- \[x\] I updated docs affected by this change, or confirmed no docs changes are needed\.",
        "docs checkbox must be checked",
    ),
    (
        r"- \[x\] I ran `python3 tools/devctl/main\.py governance docs-health` and it passed\.",
        "docs-health checkbox must be checked",
    ),
    (
        r"- \[x\] I ran `python3 tools/devctl/main\.py governance docs-accuracy` and reviewed `build/devctl/docs-drift-report\.json`\.",
        "docs-accuracy checkbox must be checked",
    ),
    (
        r"- \[x\] For UI-touching changes, I ran `python3 tools/devctl/main\.py lane screenshot-pack` and manually reviewed screenshots \(or documented why not needed\)\.",
        "screenshot workflow checkbox must be checked",
    ),
]

STAGE_EVIDENCE_PATTERN = (
    r"- \[x\] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` "
    r"and linked it below\."
)

CURRENT_DEVCTL_ARTIFACT_ROOT = "tmp/devctl-artifacts"
LEGACY_BENCHMARK_ARTIFACT_ROOT = "scripts/benchmarks/runs"
RUN_PATH_REGEX = (
    r"(?:"
    + re.escape(CURRENT_DEVCTL_ARTIFACT_ROOT)
    + r"|"
    + re.escape(LEGACY_BENCHMARK_ARTIFACT_ROOT)
    + r")/[^\s)`\"]+"
)
MARKDOWN_LINK_REGEX = re.compile(r"\[[^]]+\]\(([^)]+)\)")
BACKTICK_SPAN_REGEX = re.compile(r"(?<!`)`([^`\n]+)`(?!`)")
STATUS_LINE_REGEX = re.compile(r"^Status:\s*", re.MULTILINE)

DOCS_GOVERNANCE_CONFIG_PATH = Path("config/devctl/docs-governance.json")
DOCS_ACCURACY_MANIFEST_PATH = Path("docs/governance/docs-accuracy-manifest.json")
DOCS_DRIFT_REPORT_PATH = Path("build/devctl/docs-drift-report.json")
DOCS_DRIFT_REPORT_SCHEMA = "docs-drift-report-v1"
DOCS_ACCURACY_MANIFEST_SCHEMA = "docs-accuracy-manifest-v1"

DEFAULT_DOCS_GOVERNANCE_CONFIG: dict[str, object] = {
    "required_index_markers": {
        "docs/README.md": [
            "start-here/new-joiner.md",
            "start-here/resource-map.md",
            "start-here/source-of-truth-matrix.md",
            "start-here/documentation-drift-register.md",
        ],
        "docs/operations/README.md": ["execution-board.md", "evidence/index.md", "tickets/"],
        "docs/ux/README.md": ["implemented-behavior-reference.md", "model-management-flow.md"],
        "docs/testing/README.md": ["test-strategy.md", "runbooks.md"],
    },
    "evidence_retention": {
        "allowed_wp_dirs": ["wp-09", "wp-12", "wp-13"],
        "summary_required_labels": ["WP-01", "WP-02", "WP-03", "WP-04", "WP-05", "WP-06", "WP-07", "WP-08", "WP-11"],
        "retained_notes": {
            "wp-12": [
                "2026-03-04-eng-12-model-distribution-implementation.md",
                "2026-03-04-eng-13-native-runtime-proof.md",
                "2026-03-04-eng-17-network-policy-wiring.md",
                "2026-03-04-prod-eng-12-model-distribution-decision.md",
                "2026-03-05-eng-13-native-runtime-rerun.md",
                "2026-03-05-qa-wp12-closeout-rerun.md",
            ]
        },
    },
    "screenshot_inventory": {
        "inventory_path": "tests/ui-screenshots/inventory.yaml",
        "inventory_schema": "ui-screenshot-inventory-v1",
        "reference_dir": "tests/ui-screenshots/reference/sm-a515f-android13",
        "report_glob": "tmp/devctl-artifacts/*/*/screenshot-pack/*/inventory-report.json",
        "report_schema": "ui-screenshot-inventory-report-v2",
        "report_max_age_days": 30,
        "required_report_fields": [
            "generated_at_utc",
            "run_id",
            "device_serial",
            "missing_ids",
            "entries",
        ],
    },
}


def _read_file(path: Path) -> str:
    if not path.exists() or not path.is_file():
        raise DevctlError("CONFIG_ERROR", f"File not found: {path}")
    return path.read_text(encoding="utf-8")


def _load_json_file(path: Path) -> dict[str, object]:
    if not path.exists():
        raise DevctlError("CONFIG_ERROR", f"Missing JSON config: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise DevctlError("CONFIG_ERROR", f"Failed to parse JSON config {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise DevctlError("CONFIG_ERROR", f"Invalid JSON config root object: {path}")
    return payload


def _load_docs_governance_config(repo_root: Path) -> dict[str, object]:
    path = repo_root / DOCS_GOVERNANCE_CONFIG_PATH
    if not path.exists():
        return DEFAULT_DOCS_GOVERNANCE_CONFIG
    payload = _load_json_file(path)
    merged = dict(DEFAULT_DOCS_GOVERNANCE_CONFIG)
    merged.update(payload)
    return merged


def docs_drift_check(repo_root: Path = REPO_ROOT) -> None:
    violations: list[str] = []

    for rel in NON_CANONICAL_DOCS:
        path = repo_root / rel
        if not path.exists():
            continue

        text = path.read_text(encoding="utf-8")
        if "Source of truth" not in text and "Source Of Truth" not in text:
            violations.append(f"Missing source-of-truth pointer in {rel}")

        if "```bash" in text and re.search(
            r"scripts/dev/test\.sh|scripts/dev/device-test\.sh|run_short_loop\.sh|evaluate_thresholds\.py",
            text,
        ):
            violations.append(f"Duplicated runnable command docs detected in non-canonical file: {rel}")

    for rel in CANONICAL_DOCS:
        if not (repo_root / rel).exists():
            violations.append(f"Missing canonical file: {rel}")

    if violations:
        raise DevctlError("CONFIG_ERROR", "\n".join(sorted(violations)))

    print("Docs drift check passed.")


def _iter_docs_health_files(repo_root: Path) -> list[Path]:
    rel_paths = [
        "README.md",
        "docs",
        "scripts/dev/README.md",
        "scripts/android/README.md",
        "tests/maestro/README.md",
        "apps/mobile-android/README.md",
        "apps/mobile-android-host/README.md",
    ]
    files: set[Path] = set()
    for rel in rel_paths:
        path = repo_root / rel
        if not path.exists():
            continue
        if path.is_file() and path.suffix == ".md":
            files.add(path)
            continue
        if path.is_dir():
            for item in path.rglob("*.md"):
                rel_item = item.relative_to(repo_root).as_posix()
                if rel_item.startswith("docs/operations/evidence/"):
                    continue
                files.add(item)
    return sorted(files)


def _is_placeholder_target(target: str) -> bool:
    placeholder_markers = ("YYYY", "<", ">", "{", "}", "...", "wp-xx", "DEVICE_SERIAL_REDACTED")
    return any(marker in target for marker in placeholder_markers)


def _normalize_link_target(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    if " " in target and not target.startswith(("http://", "https://")):
        target = target.split(" ", 1)[0]
    target = target.split("#", 1)[0]
    target = target.split("?", 1)[0]
    return target.strip()


def _resolve_local_target(repo_root: Path, file_path: Path, target: str) -> Path:
    if target.startswith("/"):
        return Path(target)
    if target.startswith("./") or target.startswith("../"):
        return (file_path.parent / target).resolve()

    rel_candidate = (file_path.parent / target).resolve()
    repo_candidate = (repo_root / target).resolve()
    if repo_candidate.exists():
        return repo_candidate
    return rel_candidate


def _iter_backtick_path_refs(text: str) -> list[str]:
    refs: set[str] = set()
    for span in BACKTICK_SPAN_REGEX.findall(text):
        for raw_token in span.split():
            candidate = raw_token.strip().strip("()[]{}:,;\"'")
            if candidate.endswith(".") and "/" in candidate:
                candidate = candidate[:-1]
            if "/" not in candidate:
                continue
            refs.add(candidate)
    return sorted(refs)


def _is_likely_repo_backtick_target(target: str) -> bool:
    if target.startswith("/"):
        return False
    if target.startswith("tmp/"):
        return False
    if target.startswith(f"{LEGACY_BENCHMARK_ARTIFACT_ROOT}/"):
        return False
    if target.startswith(f"{CURRENT_DEVCTL_ARTIFACT_ROOT}/"):
        return False

    normalized = target[2:] if target.startswith("./") else target
    first = normalized.split("/", 1)[0]
    known_roots = {"docs", "scripts", "apps", "packages", "tools", "tests", "config", ".github"}
    if first in known_roots:
        return True

    return normalized.endswith(
        (
            ".md",
            ".json",
            ".yaml",
            ".yml",
            ".kt",
            ".kts",
            ".py",
            ".sh",
            ".xml",
        )
    )


def _docs_health_broken_links(repo_root: Path) -> list[str]:
    violations: list[str] = []
    seen: set[tuple[str, str, str]] = set()
    for file_path in _iter_docs_health_files(repo_root):
        rel_file = file_path.relative_to(repo_root).as_posix()
        text = file_path.read_text(encoding="utf-8")
        for match in MARKDOWN_LINK_REGEX.finditer(text):
            raw_target = match.group(1)
            target = _normalize_link_target(raw_target)
            if not target:
                continue
            if target.startswith(("#", "mailto:", "http://", "https://")):
                continue
            if "://" in target:
                continue
            if _is_placeholder_target(target):
                continue
            if "*" in target:
                continue

            resolved = _resolve_local_target(repo_root=repo_root, file_path=file_path, target=target)
            if resolved.exists():
                continue

            key = ("markdown", rel_file, raw_target)
            if key not in seen:
                seen.add(key)
                violations.append(f"Broken local markdown link in {rel_file}: {raw_target}")

        for raw_target in _iter_backtick_path_refs(text):
            target = _normalize_link_target(raw_target)
            if not target:
                continue
            if target.startswith(("#", "mailto:", "http://", "https://")):
                continue
            if "://" in target:
                continue
            if _is_placeholder_target(target):
                continue
            if "*" in target:
                continue
            if not _is_likely_repo_backtick_target(target):
                continue

            resolved = _resolve_local_target(repo_root=repo_root, file_path=file_path, target=target)
            if resolved.exists():
                continue

            key = ("backtick", rel_file, raw_target)
            if key not in seen:
                seen.add(key)
                violations.append(f"Broken local backtick path reference in {rel_file}: {raw_target}")
    return violations


def _docs_health_status_policy(repo_root: Path) -> list[str]:
    docs_root = repo_root / "docs"
    if not docs_root.exists():
        return ["Missing docs directory for status policy check."]

    allowed_board = repo_root / "docs/operations/execution-board.md"
    allowed_ticket_root = repo_root / "docs/operations/tickets"
    violations: list[str] = []

    for path in docs_root.rglob("*.md"):
        text = path.read_text(encoding="utf-8")
        if not STATUS_LINE_REGEX.search(text):
            continue
        if path == allowed_board:
            continue
        if allowed_ticket_root.exists() and path.is_relative_to(allowed_ticket_root):
            continue
        rel = path.relative_to(repo_root).as_posix()
        violations.append(f"Status line is only allowed in execution board or ticket specs: {rel}")

    return violations


def _docs_health_required_indexes(repo_root: Path, config: dict[str, object]) -> list[str]:
    required_index_markers = config.get("required_index_markers")
    if not isinstance(required_index_markers, dict):
        return ["Invalid docs governance config: required_index_markers must be an object."]

    violations: list[str] = []
    for rel_path, markers in required_index_markers.items():
        path = repo_root / str(rel_path)
        if not path.exists():
            violations.append(f"Missing required index file: {rel_path}")
            continue

        if not isinstance(markers, list) or not all(isinstance(item, str) for item in markers):
            violations.append(f"Invalid marker list for required index file: {rel_path}")
            continue

        text = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text:
                violations.append(f"Missing required index pointer '{marker}' in {rel_path}")
    return violations


def _docs_health_evidence_retention(repo_root: Path, config: dict[str, object]) -> list[str]:
    evidence_root = repo_root / "docs/operations/evidence"
    violations: list[str] = []
    if not evidence_root.exists():
        return ["Missing docs/operations/evidence directory."]

    retention = config.get("evidence_retention")
    if not isinstance(retention, dict):
        return ["Invalid docs governance config: evidence_retention must be an object."]

    allowed_wp_dirs_raw = retention.get("allowed_wp_dirs", [])
    summary_required_labels_raw = retention.get("summary_required_labels", [])
    retained_notes_raw = retention.get("retained_notes", {})

    if not isinstance(allowed_wp_dirs_raw, list) or not all(isinstance(item, str) for item in allowed_wp_dirs_raw):
        violations.append("Invalid evidence_retention.allowed_wp_dirs in docs governance config.")
        allowed_wp_dirs: set[str] = set()
    else:
        allowed_wp_dirs = set(allowed_wp_dirs_raw)

    if not isinstance(summary_required_labels_raw, list) or not all(isinstance(item, str) for item in summary_required_labels_raw):
        violations.append("Invalid evidence_retention.summary_required_labels in docs governance config.")
        summary_required_labels: list[str] = []
    else:
        summary_required_labels = list(summary_required_labels_raw)

    retained_notes: dict[str, set[str]] = {}
    if isinstance(retained_notes_raw, dict):
        for wp, notes in retained_notes_raw.items():
            if isinstance(wp, str) and isinstance(notes, list) and all(isinstance(note, str) for note in notes):
                retained_notes[wp] = set(notes)
            else:
                violations.append("Invalid evidence_retention.retained_notes entry in docs governance config.")
    else:
        violations.append("Invalid evidence_retention.retained_notes in docs governance config.")

    index_path = evidence_root / "index.md"
    if not index_path.exists():
        violations.append("Missing evidence summary index: docs/operations/evidence/index.md")
    else:
        index_text = index_path.read_text(encoding="utf-8")
        for label in summary_required_labels:
            if label not in index_text:
                violations.append(f"Evidence index missing summary for {label}")

    wp_dirs = [item for item in evidence_root.iterdir() if item.is_dir() and item.name.startswith("wp-")]
    for wp_dir in sorted(wp_dirs):
        markdown_files = sorted(wp_dir.glob("*.md"))
        if wp_dir.name not in allowed_wp_dirs and markdown_files:
            rel = wp_dir.relative_to(repo_root).as_posix()
            violations.append(f"Historical evidence markdown should be pruned from {rel}")

    for wp in sorted(allowed_wp_dirs):
        wp_dir = evidence_root / wp
        if not wp_dir.exists():
            violations.append(f"Missing active evidence directory: docs/operations/evidence/{wp}")
            continue
        if not list(wp_dir.glob("*.md")):
            violations.append(f"Active evidence directory has no markdown notes: docs/operations/evidence/{wp}")

    for wp, expected_notes in retained_notes.items():
        wp_dir = evidence_root / wp
        if not wp_dir.exists():
            violations.append(f"Missing retained evidence directory from policy: docs/operations/evidence/{wp}")
            continue
        actual_notes = {path.name for path in wp_dir.glob("*.md")}
        missing = sorted(expected_notes - actual_notes)
        for note in missing:
            violations.append(f"Missing required retained evidence note: docs/operations/evidence/{wp}/{note}")

    return violations


def docs_health_check(repo_root: Path = REPO_ROOT) -> None:
    config = _load_docs_governance_config(repo_root)
    violations: list[str] = []
    violations.extend(_docs_health_broken_links(repo_root))
    violations.extend(_docs_health_status_policy(repo_root))
    violations.extend(_docs_health_required_indexes(repo_root, config))
    violations.extend(_docs_health_evidence_retention(repo_root, config))

    if violations:
        raise DevctlError("CONFIG_ERROR", "\n".join(sorted(violations)))

    print("Docs health check passed.")


def _load_screenshot_inventory_entries(repo_root: Path, config: dict[str, object]) -> tuple[list[tuple[str, str]], dict[str, object]]:
    if yaml is None:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            "PyYAML is required. Install dependencies with: python3 -m pip install -r tools/devctl/requirements.txt",
        )

    screenshot_cfg = config.get("screenshot_inventory")
    if not isinstance(screenshot_cfg, dict):
        raise DevctlError("CONFIG_ERROR", "Invalid docs governance config: screenshot_inventory must be an object.")

    inventory_path_raw = screenshot_cfg.get("inventory_path")
    inventory_schema = screenshot_cfg.get("inventory_schema")
    if not isinstance(inventory_path_raw, str) or not isinstance(inventory_schema, str):
        raise DevctlError("CONFIG_ERROR", "Invalid screenshot inventory config: inventory_path/inventory_schema are required strings.")

    inventory_path = repo_root / Path(inventory_path_raw)
    if not inventory_path.exists():
        raise DevctlError("CONFIG_ERROR", f"Missing screenshot inventory file: {inventory_path}")

    try:
        data = yaml.safe_load(inventory_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise DevctlError("CONFIG_ERROR", f"Failed to parse screenshot inventory {inventory_path}: {exc}") from exc
    if not isinstance(data, dict):
        raise DevctlError("CONFIG_ERROR", f"Invalid screenshot inventory root in {inventory_path}; expected object.")
    if data.get("schema") != inventory_schema:
        raise DevctlError(
            "CONFIG_ERROR",
            f"Unsupported screenshot inventory schema in {inventory_path}. Expected '{inventory_schema}'.",
        )

    screenshots = data.get("screenshots")
    if not isinstance(screenshots, list) or not screenshots:
        raise DevctlError("CONFIG_ERROR", f"Screenshot inventory must define a non-empty screenshots list: {inventory_path}")

    id_pattern = re.compile(r"^ui-\d{2}-[a-z0-9-]+$")
    entries: list[tuple[str, str]] = []
    seen_ids: set[str] = set()
    seen_filenames: set[str] = set()
    for index, item in enumerate(screenshots, start=1):
        if not isinstance(item, dict):
            raise DevctlError("CONFIG_ERROR", f"Invalid screenshot inventory entry #{index} in {inventory_path}.")
        shot_id = str(item.get("id", "")).strip()
        filename = str(item.get("filename", "")).strip()
        if not shot_id or not filename:
            raise DevctlError(
                "CONFIG_ERROR",
                f"Screenshot inventory entry #{index} in {inventory_path} is missing id or filename.",
            )
        if not id_pattern.match(shot_id):
            raise DevctlError("CONFIG_ERROR", f"Invalid screenshot id '{shot_id}' in {inventory_path}.")
        if not filename.endswith(".png"):
            raise DevctlError(
                "CONFIG_ERROR",
                f"Screenshot filename must end with .png in {inventory_path}: {filename}",
            )
        if shot_id in seen_ids:
            raise DevctlError("CONFIG_ERROR", f"Duplicate screenshot id in {inventory_path}: {shot_id}")
        if filename in seen_filenames:
            raise DevctlError("CONFIG_ERROR", f"Duplicate screenshot filename in {inventory_path}: {filename}")
        seen_ids.add(shot_id)
        seen_filenames.add(filename)
        entries.append((shot_id, filename))

    return entries, screenshot_cfg


def _latest_screenshot_inventory_report(repo_root: Path, report_glob: str) -> Path | None:
    candidate_globs = [report_glob]
    if report_glob.startswith(f"{CURRENT_DEVCTL_ARTIFACT_ROOT}/"):
        candidate_globs.append(report_glob.replace(CURRENT_DEVCTL_ARTIFACT_ROOT, LEGACY_BENCHMARK_ARTIFACT_ROOT, 1))
    elif report_glob.startswith(f"{LEGACY_BENCHMARK_ARTIFACT_ROOT}/"):
        candidate_globs.append(report_glob.replace(LEGACY_BENCHMARK_ARTIFACT_ROOT, CURRENT_DEVCTL_ARTIFACT_ROOT, 1))

    reports = sorted(
        {path for pattern in candidate_globs for path in repo_root.glob(pattern)},
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    return reports[0] if reports else None


def _validate_iso8601_utc(value: str) -> bool:
    raw = value.strip()
    if not raw:
        return False
    if raw.endswith("Z"):
        raw = raw[:-1] + "+00:00"
    try:
        datetime.fromisoformat(raw)
    except ValueError:
        return False
    return True


def _payload_value_with_aliases(payload: dict[str, object], canonical: str) -> object | None:
    alias_map: dict[str, tuple[str, ...]] = {
        "generated_at_utc": ("generated_at_utc", "generated_at"),
        "device_serial": ("device_serial", "serial"),
        "run_id": ("run_id", "artifact_root"),
        "schema": ("schema",),
        "inventory_schema": ("inventory_schema",),
        "inventory_digest": ("inventory_digest",),
        "git_commit": ("git_commit",),
        "missing_ids": ("missing_ids",),
        "entries": ("entries",),
    }
    for key in alias_map.get(canonical, (canonical,)):
        if key in payload:
            return payload.get(key)
    return None


def screenshot_inventory_check(repo_root: Path = REPO_ROOT) -> None:
    config = _load_docs_governance_config(repo_root)
    entries, screenshot_cfg = _load_screenshot_inventory_entries(repo_root, config)
    violations: list[str] = []

    reference_dir_raw = screenshot_cfg.get("reference_dir")
    report_glob = screenshot_cfg.get("report_glob")
    report_schema = screenshot_cfg.get("report_schema")
    report_max_age_days = screenshot_cfg.get("report_max_age_days")
    required_report_fields = screenshot_cfg.get("required_report_fields")
    inventory_schema = screenshot_cfg.get("inventory_schema")

    if not isinstance(reference_dir_raw, str):
        raise DevctlError("CONFIG_ERROR", "Invalid screenshot inventory config: reference_dir must be a string.")
    if not isinstance(report_glob, str):
        raise DevctlError("CONFIG_ERROR", "Invalid screenshot inventory config: report_glob must be a string.")
    if not isinstance(report_schema, str):
        raise DevctlError("CONFIG_ERROR", "Invalid screenshot inventory config: report_schema must be a string.")
    if not isinstance(report_max_age_days, int) or report_max_age_days < 0:
        raise DevctlError("CONFIG_ERROR", "Invalid screenshot inventory config: report_max_age_days must be a non-negative integer.")
    if not isinstance(required_report_fields, list) or not all(isinstance(item, str) for item in required_report_fields):
        raise DevctlError("CONFIG_ERROR", "Invalid screenshot inventory config: required_report_fields must be a string array.")
    if not isinstance(inventory_schema, str):
        raise DevctlError("CONFIG_ERROR", "Invalid screenshot inventory config: inventory_schema must be a string.")

    reference_dir = repo_root / Path(reference_dir_raw)
    if not reference_dir.exists():
        violations.append(f"Missing screenshot reference directory: {Path(reference_dir_raw).as_posix()}")
    else:
        for shot_id, filename in entries:
            if not (reference_dir / filename).exists():
                violations.append(f"Missing screenshot reference for {shot_id}: {Path(reference_dir_raw).as_posix()}/{filename}")
        index_path = reference_dir / "index.md"
        if not index_path.exists():
            violations.append(f"Missing screenshot reference gallery index: {Path(reference_dir_raw).as_posix()}/index.md")

    latest_report_path = _latest_screenshot_inventory_report(repo_root, report_glob)
    if latest_report_path is None:
        violations.append(f"No screenshot inventory report found under {report_glob}.")
    else:
        now = datetime.now(timezone.utc)
        age_days = (now - datetime.fromtimestamp(latest_report_path.stat().st_mtime, tz=timezone.utc)).days
        if age_days > report_max_age_days:
            violations.append(
                f"Latest screenshot inventory report is stale ({age_days} days old > {report_max_age_days}): {latest_report_path}"
            )

        try:
            report_payload = json.loads(latest_report_path.read_text(encoding="utf-8"))
        except Exception as exc:
            violations.append(f"Failed to parse latest screenshot inventory report {latest_report_path}: {exc}")
            report_payload = {}

        if isinstance(report_payload, dict):
            for field in required_report_fields:
                if _payload_value_with_aliases(report_payload, field) is None:
                    violations.append(f"Latest screenshot inventory report missing required field '{field}': {latest_report_path}")

            raw_schema = _payload_value_with_aliases(report_payload, "schema")
            if raw_schema is not None and raw_schema != report_schema:
                violations.append(
                    f"Latest screenshot inventory report schema mismatch; expected '{report_schema}': {latest_report_path}"
                )
            raw_inventory_schema = _payload_value_with_aliases(report_payload, "inventory_schema")
            if raw_inventory_schema is not None and raw_inventory_schema != inventory_schema:
                violations.append(
                    f"Latest screenshot inventory report inventory_schema mismatch; expected '{inventory_schema}': {latest_report_path}"
                )

            generated_at = str(_payload_value_with_aliases(report_payload, "generated_at_utc") or "").strip()
            if not _validate_iso8601_utc(generated_at):
                violations.append(f"Latest screenshot inventory report has invalid generated_at_utc: {latest_report_path}")
            else:
                parsed = datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=timezone.utc)
                days_old = (now - parsed).days
                if days_old > report_max_age_days:
                    violations.append(
                        f"Latest screenshot inventory report generated_at_utc is stale ({days_old} days old > {report_max_age_days}): {latest_report_path}"
                    )

            git_commit = str(_payload_value_with_aliases(report_payload, "git_commit") or "").strip()
            run_id = str(_payload_value_with_aliases(report_payload, "run_id") or "").strip()
            device_serial = str(_payload_value_with_aliases(report_payload, "device_serial") or "").strip()
            inventory_digest = str(_payload_value_with_aliases(report_payload, "inventory_digest") or "").strip()
            if not run_id:
                violations.append(f"Latest screenshot inventory report missing run_id value: {latest_report_path}")
            if not device_serial:
                violations.append(f"Latest screenshot inventory report missing device_serial value: {latest_report_path}")
            if not git_commit and not str(report_payload.get("artifact_root", "")).strip():
                violations.append(
                    f"Latest screenshot inventory report missing provenance fields (git_commit/artifact_root): {latest_report_path}"
                )
            if not inventory_digest and not isinstance(report_payload.get("summary"), dict):
                violations.append(
                    f"Latest screenshot inventory report missing inventory identity fields (inventory_digest/summary): {latest_report_path}"
                )

            missing_ids = _payload_value_with_aliases(report_payload, "missing_ids")
            if not isinstance(missing_ids, list):
                violations.append(f"Latest screenshot inventory report field 'missing_ids' must be a list: {latest_report_path}")
            elif missing_ids:
                violations.append(
                    "Latest screenshot inventory report has missing required IDs: "
                    f"{', '.join(str(item) for item in missing_ids)} ({latest_report_path})"
                )

            entry_rows = _payload_value_with_aliases(report_payload, "entries")
            if isinstance(entry_rows, list):
                status_by_id = {
                    str(row.get("id")): str(row.get("status"))
                    for row in entry_rows
                    if isinstance(row, dict) and row.get("id") is not None
                }
                for shot_id, _filename in entries:
                    if status_by_id.get(shot_id) != "PASS":
                        violations.append(
                            f"Latest screenshot report does not mark {shot_id} as PASS: {latest_report_path}"
                        )
            else:
                violations.append(f"Latest screenshot inventory report field 'entries' must be a list: {latest_report_path}")
        else:
            violations.append(f"Latest screenshot inventory report root must be an object: {latest_report_path}")

    if violations:
        raise DevctlError("CONFIG_ERROR", "\n".join(sorted(violations)))

    print("Screenshot inventory check passed.")


def _load_docs_accuracy_manifest(repo_root: Path) -> dict[str, object]:
    manifest_path = repo_root / DOCS_ACCURACY_MANIFEST_PATH
    payload = _load_json_file(manifest_path)
    schema = payload.get("schema")
    if schema != DOCS_ACCURACY_MANIFEST_SCHEMA:
        raise DevctlError(
            "CONFIG_ERROR",
            f"Unsupported docs accuracy manifest schema in {manifest_path}. Expected '{DOCS_ACCURACY_MANIFEST_SCHEMA}'.",
        )
    features = payload.get("features")
    if not isinstance(features, list) or not features:
        raise DevctlError("CONFIG_ERROR", f"Docs accuracy manifest must define non-empty features array: {manifest_path}")
    return payload


def _glob_paths(repo_root: Path, pattern: str) -> list[Path]:
    return sorted(path for path in repo_root.glob(pattern) if path.is_file())


def _rule_text_match(paths: list[Path], needles: list[str]) -> tuple[bool, list[str]]:
    unresolved: list[str] = []
    if not paths:
        return False, needles

    contents = [path.read_text(encoding="utf-8") for path in paths]
    for needle in needles:
        if not any(needle in text for text in contents):
            unresolved.append(needle)

    return not unresolved, unresolved


def _write_docs_drift_report(repo_root: Path, checks: list[dict[str, object]], violations: list[dict[str, str]]) -> Path:
    report_path = repo_root / DOCS_DRIFT_REPORT_PATH
    report_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": DOCS_DRIFT_REPORT_SCHEMA,
        "generated_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "status": "pass" if not violations else "fail",
        "checks": checks,
        "violations": violations,
    }
    report_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return report_path


def docs_accuracy_check(repo_root: Path = REPO_ROOT) -> None:
    manifest = _load_docs_accuracy_manifest(repo_root)
    features = manifest.get("features")
    assert isinstance(features, list)

    violations: list[dict[str, str]] = []
    feature_checks: list[dict[str, object]] = []

    for raw_feature in features:
        if not isinstance(raw_feature, dict):
            raise DevctlError("CONFIG_ERROR", "Invalid docs accuracy feature entry; expected object.")
        feature_id = str(raw_feature.get("id", "")).strip()
        if not feature_id:
            raise DevctlError("CONFIG_ERROR", "Invalid docs accuracy feature entry: missing id.")

        feature_violations: list[dict[str, str]] = []

        doc_rules = raw_feature.get("docs", [])
        if not isinstance(doc_rules, list):
            raise DevctlError("CONFIG_ERROR", f"Invalid docs rules for feature '{feature_id}'.")
        for rule in doc_rules:
            if not isinstance(rule, dict):
                raise DevctlError("CONFIG_ERROR", f"Invalid docs rule for feature '{feature_id}'.")
            rel_path = str(rule.get("path", "")).strip()
            needles = rule.get("must_contain", [])
            if not rel_path or not isinstance(needles, list) or not all(isinstance(item, str) for item in needles):
                raise DevctlError("CONFIG_ERROR", f"Invalid docs rule shape for feature '{feature_id}'.")

            target = repo_root / rel_path
            if not target.exists():
                feature_violations.append(
                    {
                        "feature_id": feature_id,
                        "check": "doc_presence",
                        "target": rel_path,
                        "message": f"Missing doc file: {rel_path}",
                    }
                )
                continue

            text = target.read_text(encoding="utf-8")
            for needle in needles:
                if needle not in text:
                    feature_violations.append(
                        {
                            "feature_id": feature_id,
                            "check": "doc_marker",
                            "target": rel_path,
                            "message": f"Doc marker not found: '{needle}'",
                        }
                    )

        code_rules = raw_feature.get("code", [])
        if not isinstance(code_rules, list):
            raise DevctlError("CONFIG_ERROR", f"Invalid code rules for feature '{feature_id}'.")
        for rule in code_rules:
            if not isinstance(rule, dict):
                raise DevctlError("CONFIG_ERROR", f"Invalid code rule for feature '{feature_id}'.")
            pattern = str(rule.get("glob", "")).strip()
            needles = rule.get("must_contain", [])
            if not pattern or not isinstance(needles, list) or not all(isinstance(item, str) for item in needles):
                raise DevctlError("CONFIG_ERROR", f"Invalid code rule shape for feature '{feature_id}'.")

            matched_paths = _glob_paths(repo_root, pattern)
            if not matched_paths:
                feature_violations.append(
                    {
                        "feature_id": feature_id,
                        "check": "code_glob",
                        "target": pattern,
                        "message": f"Code glob matched no files: {pattern}",
                    }
                )
                continue

            matched, unresolved = _rule_text_match(matched_paths, needles)
            if not matched:
                for needle in unresolved:
                    feature_violations.append(
                        {
                            "feature_id": feature_id,
                            "check": "code_marker",
                            "target": pattern,
                            "message": f"Code marker not found: '{needle}'",
                        }
                    )

        feature_checks.append(
            {
                "feature_id": feature_id,
                "status": "PASS" if not feature_violations else "FAIL",
                "violation_count": len(feature_violations),
            }
        )
        violations.extend(feature_violations)

    report_path = _write_docs_drift_report(
        repo_root=repo_root,
        checks=[{"name": "feature-doc-code", "status": "PASS" if not violations else "FAIL", "features": feature_checks}],
        violations=violations,
    )

    if violations:
        summary = [f"Docs accuracy check failed with {len(violations)} violation(s)."]
        summary.extend(f"- [{row['feature_id']}] {row['check']}: {row['message']} ({row['target']})" for row in violations)
        summary.append(f"Drift report: {report_path.relative_to(repo_root)}")
        raise DevctlError("CONFIG_ERROR", "\n".join(summary))

    print(f"Docs accuracy check passed. Drift report: {report_path.relative_to(repo_root)}")


def evidence_check(evidence_file: str, repo_root: Path = REPO_ROOT) -> None:
    if not evidence_file:
        raise DevctlError("CONFIG_ERROR", "Usage: evidence-check <evidence-markdown-file>")

    path = repo_root / evidence_file
    if not path.exists():
        raise DevctlError("CONFIG_ERROR", f"Evidence file not found: {evidence_file}")

    text = path.read_text(encoding="utf-8")
    run_paths = sorted(set(re.findall(RUN_PATH_REGEX, text)))
    if not run_paths:
        raise DevctlError("CONFIG_ERROR", f"No raw run artifact paths referenced in {evidence_file}")

    missing = [run_path for run_path in run_paths if not (repo_root / run_path).exists()]
    if missing:
        raise DevctlError("CONFIG_ERROR", "\n".join(f"Missing referenced artifact path: {item}" for item in missing))

    print(f"Evidence check passed: {evidence_file}")


def evidence_check_changed(event_name: str | None = None, base_ref: str | None = None, repo_root: Path = REPO_ROOT) -> None:
    event = event_name or os.environ.get("GITHUB_EVENT_NAME", "")
    base = base_ref or os.environ.get("GITHUB_BASE_REF", "")

    if event != "pull_request":
        print("Evidence changed-file check skipped: not a pull_request event.")
        return

    if not base:
        print("Evidence changed-file check skipped: GITHUB_BASE_REF is unset.")
        return

    run_subprocess(["git", "fetch", "--no-tags", "--depth=1", "origin", base], check=False, cwd=repo_root)
    diff_result = run_subprocess(
        ["git", "diff", "--name-only", f"origin/{base}...HEAD"],
        check=False,
        capture_output=True,
        cwd=repo_root,
    )

    changed = [
        line.strip()
        for line in (diff_result.stdout or "").splitlines()
        if re.match(r"^docs/operations/evidence/.+\.md$", line.strip())
    ]

    if not changed:
        print("No changed evidence markdown files detected.")
        return

    failures: list[str] = []
    for rel in changed:
        file_path = repo_root / rel
        if not file_path.exists():
            print(f"Changed evidence file not present in checkout (possibly deleted): {rel}")
            continue

        text = file_path.read_text(encoding="utf-8")
        if re.search(RUN_PATH_REGEX, text):
            try:
                evidence_check(rel, repo_root=repo_root)
            except DevctlError as exc:
                failures.append(exc.message)
        else:
            print(f"No raw run artifact references in {rel}; skipping path validation.")

    if failures:
        raise DevctlError("CONFIG_ERROR", "\n".join(failures))


def validate_pr_body(pr_body_file: str, repo_root: Path = REPO_ROOT) -> None:
    if not pr_body_file:
        raise DevctlError("CONFIG_ERROR", "Usage: validate-pr-body <pr-body-file>")

    path = repo_root / pr_body_file
    text = _read_file(path)

    for pattern, message in PR_REQUIRED_PATTERNS:
        if not re.search(pattern, text):
            raise DevctlError("CONFIG_ERROR", f"PR template requirement failed: {message}")

    if re.search(r"(?i)wp-|work package|stage", text):
        if not re.search(STAGE_EVIDENCE_PATTERN, text):
            raise DevctlError("CONFIG_ERROR", "PR template requirement failed: stage/work-package evidence checkbox must be checked")
        if "docs/operations/evidence/" not in text:
            raise DevctlError("CONFIG_ERROR", "Stage/work-package PR must include evidence note link.")

    print("PR body validation passed.")


def stage_close_gate(pr_body_file: str, repo_root: Path = REPO_ROOT) -> None:
    if not pr_body_file:
        raise DevctlError("CONFIG_ERROR", "Usage: stage-close-gate <pr-body-file>")

    path = repo_root / pr_body_file
    text = _read_file(path)

    if not re.search(r"(?i)stage close:\s*yes", text):
        print("Stage close gate not requested; skipping.")
        return

    evidence_match = re.search(r"docs/operations/evidence/wp-[0-9]{2}/[0-9]{4}-[0-9]{2}-[0-9]{2}[^ )]*\.md", text)
    if not evidence_match:
        raise DevctlError("CONFIG_ERROR", "Stage-close PR must link a WP evidence markdown file.")

    evidence_path = evidence_match.group(0)
    if not (repo_root / evidence_path).exists():
        raise DevctlError("CONFIG_ERROR", f"Linked evidence file does not exist in repo: {evidence_path}")

    evidence_check(evidence_path, repo_root=repo_root)
    print("Stage-close evidence gate passed.")


MODEL_CATALOG_PATH = Path("packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ModelCatalog.kt")
DISTRIBUTION_CATALOG_PATH = Path("apps/mobile-android/src/main/assets/model-distribution-catalog.json")
ROUTING_MODE_PATH = Path("packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/RoutingMode.kt")
MAESTRO_GPU_MATRIX_PATH = Path("scripts/dev/maestro-gpu-matrix-common.sh")

_MODEL_CONST_RE = re.compile(r'const val (\w+)\s*=\s*"([^"]+)"')
_DESCRIPTOR_BLOCK_RE = re.compile(
    r"ModelDescriptor\(\s*(.*?)\),\s*(?=ModelDescriptor\(|\))",
    re.DOTALL,
)
_FIELD_RE = re.compile(r'(\w+)\s*=\s*(?:"([^"]+)"|(\w+))')
_ROUTING_ENUM_RE = re.compile(r"^\s+(\w+),?\s*$", re.MULTILINE)
_INTERACTION_FEATURE_SET_RE = re.compile(r'interactionFeatures\s*=\s*setOf\((.*?)\)', re.DOTALL)
_INTERACTION_FEATURE_VALUE_RE = re.compile(r'"([^"]+)"')
_ALLOWED_INTERACTION_FEATURES = {"TOOL_CALL_XML", "THINKING_TAGS"}


def _parse_model_const_ids(catalog_text: str) -> dict[str, str]:
    """Return {CONST_NAME: model_id_value} for const val declarations."""
    return {m.group(1): m.group(2) for m in _MODEL_CONST_RE.finditer(catalog_text)}


def _resolve_model_id(raw_value: str, const_map: dict[str, str]) -> str:
    """Resolve a modelId field value: either a quoted string or a const reference."""
    return const_map.get(raw_value, raw_value)


def _parse_startup_candidate_ids(catalog_text: str, const_map: dict[str, str]) -> set[str]:
    """Return model IDs where startupCandidate = true in descriptors."""
    ids: set[str] = set()
    for block_match in _DESCRIPTOR_BLOCK_RE.finditer(catalog_text):
        block = block_match.group(1)
        fields = {m.group(1): (m.group(2) or m.group(3)) for m in _FIELD_RE.finditer(block)}
        if fields.get("startupCandidate") == "true":
            ids.add(_resolve_model_id(fields.get("modelId", ""), const_map))
    return ids


def _parse_bridge_supported_ids(catalog_text: str, const_map: dict[str, str]) -> set[str]:
    """Return model IDs where bridgeSupported = true in descriptors."""
    ids: set[str] = set()
    for block_match in _DESCRIPTOR_BLOCK_RE.finditer(catalog_text):
        block = block_match.group(1)
        fields = {m.group(1): (m.group(2) or m.group(3)) for m in _FIELD_RE.finditer(block)}
        if fields.get("bridgeSupported") == "true":
            ids.add(_resolve_model_id(fields.get("modelId", ""), const_map))
    return ids


def model_audit(repo_root: Path = REPO_ROOT) -> None:
    """Cross-reference model catalog, distribution catalog, routing modes, and scripts."""
    errors: list[str] = []

    catalog_path = repo_root / MODEL_CATALOG_PATH
    catalog_text = _read_file(catalog_path)

    dist_path = repo_root / DISTRIBUTION_CATALOG_PATH
    dist_data = _load_json_file(dist_path)
    dist_model_ids = {m["modelId"] for m in dist_data.get("models", []) if isinstance(m, dict)}

    routing_path = repo_root / ROUTING_MODE_PATH
    routing_text = _read_file(routing_path)
    routing_enum_values = set(_ROUTING_ENUM_RE.findall(routing_text)) - {"AUTO"}

    const_map = _parse_model_const_ids(catalog_text)
    startup_ids = _parse_startup_candidate_ids(catalog_text, const_map)
    bridge_ids = _parse_bridge_supported_ids(catalog_text, const_map)

    # Check 1: Every startup-candidate model has a distribution entry
    for model_id in sorted(startup_ids):
        if model_id not in dist_model_ids:
            errors.append(f"Startup-candidate model '{model_id}' missing from distribution catalog ({DISTRIBUTION_CATALOG_PATH})")

    # Check 2: Every bridge-supported model has a distribution entry
    for model_id in sorted(bridge_ids):
        if model_id not in dist_model_ids:
            errors.append(f"Bridge-supported model '{model_id}' missing from distribution catalog ({DISTRIBUTION_CATALOG_PATH})")

    # Check 3: Distribution entries reference known catalog models
    const_ids = _parse_model_const_ids(catalog_text)
    all_catalog_model_ids = set(const_ids.values())
    for model_id in sorted(dist_model_ids):
        if model_id not in all_catalog_model_ids:
            errors.append(f"Distribution model '{model_id}' not found in ModelCatalog constants")

    # Check 4: Every non-AUTO RoutingMode enum value appears as an explicitRoutingModes binding
    for enum_val in sorted(routing_enum_values):
        if f"RoutingMode.{enum_val}" not in catalog_text:
            errors.append(f"RoutingMode.{enum_val} not bound in any ModelDescriptor explicitRoutingModes")

    # Check 5: chatTemplateId values reference valid ModelTemplateProfile enum values
    valid_template_ids = {"CHATML", "LLAMA3", "PHI", "GEMMA", "GEMMA4"}
    for match in re.finditer(r'chatTemplateId\s*=\s*"(\w+)"', catalog_text):
        template_id = match.group(1)
        if template_id not in valid_template_ids:
            errors.append(f"chatTemplateId '{template_id}' is not a valid ModelTemplateProfile value (valid: {valid_template_ids})")

    # Check 6: Maestro GPU matrix has entries for startup-candidate models
    maestro_path = repo_root / MAESTRO_GPU_MATRIX_PATH
    if maestro_path.exists():
        maestro_text = _read_file(maestro_path)
        # We just check that the file isn't empty; model-specific entries are optional
        if not maestro_text.strip():
            errors.append(f"Maestro GPU matrix script is empty ({MAESTRO_GPU_MATRIX_PATH})")

    # Check 7: interactionFeatures values are from the allowlist
    for block_match in _DESCRIPTOR_BLOCK_RE.finditer(catalog_text):
        block = block_match.group(1)
        fields = {m.group(1): (m.group(2) or m.group(3)) for m in _FIELD_RE.finditer(block)}
        model_id = _resolve_model_id(fields.get("modelId", ""), const_map)
        for feature_set_match in _INTERACTION_FEATURE_SET_RE.finditer(block):
            values = _INTERACTION_FEATURE_VALUE_RE.findall(feature_set_match.group(1))
            for value in values:
                if value not in _ALLOWED_INTERACTION_FEATURES:
                    errors.append(
                        f"Model '{model_id}' has unsupported interactionFeatures value '{value}' "
                        f"(allowed: {sorted(_ALLOWED_INTERACTION_FEATURES)})"
                    )

    if errors:
        summary = [f"Model audit failed with {len(errors)} issue(s):"]
        summary.extend(f"  - {e}" for e in errors)
        raise DevctlError("CONFIG_ERROR", "\n".join(summary))

    print(f"Model audit passed. {len(startup_ids)} startup candidates, {len(bridge_ids)} bridge-supported, {len(dist_model_ids)} distribution entries.")


def governance_self_test(repo_root: Path = REPO_ROOT) -> None:
    with tempfile.TemporaryDirectory(prefix="devctl-gov-") as tmp:
        sandbox = Path(tmp)
        date_dir = datetime.now().strftime("%Y-%m-%d")
        run_rel = Path(CURRENT_DEVCTL_ARTIFACT_ROOT) / date_dir / "SELFTEST" / "governance-self-test"
        run_dir = sandbox / run_rel
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "artifact.txt").write_text("selftest\n", encoding="utf-8")

        evidence_note_rel = Path("docs/operations/evidence/wp-03/2026-03-03-selftest.md")
        evidence_note = sandbox / evidence_note_rel
        evidence_note.parent.mkdir(parents=True, exist_ok=True)
        evidence_note.write_text(
            "Evidence note\n"
            f"- raw path: {run_rel}/artifact.txt\n",
            encoding="utf-8",
        )

        evidence_ok = sandbox / "evidence-ok.md"
        evidence_bad = sandbox / "evidence-bad.md"
        pr_ok = sandbox / "pr-ok.md"
        pr_bad = sandbox / "pr-bad.md"
        pr_stage_close = sandbox / "pr-stage-close.md"

        evidence_ok.write_text(
            "Evidence note\n"
            f"- raw path: {run_rel}/artifact.txt\n",
            encoding="utf-8",
        )
        evidence_bad.write_text(
            "Evidence note\n"
            f"- raw path: {run_rel}/missing.txt\n",
            encoding="utf-8",
        )

        evidence_check(str(evidence_ok), repo_root=sandbox)

        try:
            evidence_check(str(evidence_bad), repo_root=sandbox)
        except DevctlError:
            pass
        else:
            raise DevctlError("CONFIG_ERROR", "Expected evidence-check failure did not occur")

        pr_ok.write_text(
            "- [x] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n"
            "- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.\n"
            "- [x] I updated docs affected by this change, or confirmed no docs changes are needed.\n"
            "- [x] I ran `python3 tools/devctl/main.py governance docs-health` and it passed.\n"
            "- [x] I ran `python3 tools/devctl/main.py governance docs-accuracy` and reviewed `build/devctl/docs-drift-report.json`.\n"
            "- [x] For UI-touching changes, I ran `python3 tools/devctl/main.py lane screenshot-pack` and manually reviewed screenshots (or documented why not needed).\n"
            "- [x] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below.\n\n"
            "Stage close: no\n\n"
            f"Evidence note(s): {evidence_note_rel}\n",
            encoding="utf-8",
        )
        validate_pr_body(str(pr_ok), repo_root=sandbox)
        stage_close_gate(str(pr_ok), repo_root=sandbox)

        pr_bad.write_text(
            "- [ ] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n"
            "- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.\n"
            "- [x] I updated docs affected by this change, or confirmed no docs changes are needed.\n",
            encoding="utf-8",
        )
        try:
            validate_pr_body(str(pr_bad), repo_root=sandbox)
        except DevctlError:
            pass
        else:
            raise DevctlError("CONFIG_ERROR", "Expected validate-pr-body failure did not occur")

        pr_stage_close.write_text(
            "- [x] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n"
            "- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.\n"
            "- [x] I updated docs affected by this change, or confirmed no docs changes are needed.\n"
            "- [x] I ran `python3 tools/devctl/main.py governance docs-health` and it passed.\n"
            "- [x] I ran `python3 tools/devctl/main.py governance docs-accuracy` and reviewed `build/devctl/docs-drift-report.json`.\n"
            "- [x] For UI-touching changes, I ran `python3 tools/devctl/main.py lane screenshot-pack` and manually reviewed screenshots (or documented why not needed).\n"
            "- [x] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below.\n\n"
            "Stage close: yes\n\n"
            f"Evidence note(s): {evidence_note_rel}\n"
            f"Raw run artifacts (`tmp/devctl-artifacts/...`): {run_rel}/artifact.txt\n",
            encoding="utf-8",
        )
        validate_pr_body(str(pr_stage_close), repo_root=sandbox)
        stage_close_gate(str(pr_stage_close), repo_root=sandbox)

    print("Governance self-test passed.")
