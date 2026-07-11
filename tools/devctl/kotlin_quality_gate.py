#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

_SUPPORTED_GRADLE_JAVA_MAJORS = (21,)


@dataclass(frozen=True)
class Finding:
    tool: str
    path: Path
    line_number: int
    rule_id: str
    message: str


def _run_gradle_analysis(repo_root: Path) -> None:
    module_paths = [
        ":apps:mobile-android",
        ":apps:mobile-android-host",
        ":packages:core-domain",
        ":packages:inference-adapters",
        ":packages:tool-runtime",
        ":packages:memory",
        ":packages:native-bridge",
        ":packages:app-runtime",
    ]
    gradle_tasks: list[str] = []
    for module_path in module_paths:
        gradle_tasks.append(f"{module_path}:detekt")
        gradle_tasks.append(f"{module_path}:ktlintCheck")
    command = ["./gradlew", "--no-daemon", *gradle_tasks]
    result = subprocess.run(
        command,
        cwd=repo_root,
        check=False,
        capture_output=True,
        text=True,
        env=_gradle_subprocess_env(),
    )
    if result.returncode != 0:
        stdout = (result.stdout or "").strip()
        stderr = (result.stderr or "").strip()
        details = "\n".join(part for part in (stdout, stderr) if part)
        raise RuntimeError(f"Gradle static analysis failed.\n{details}")


def _gradle_subprocess_env() -> dict[str, str]:
    env = os.environ.copy()
    gradle_java_home = _resolve_gradle_java_home()
    if gradle_java_home is not None:
        home = str(gradle_java_home)
        env["JAVA_HOME"] = home
        env["ORG_GRADLE_JAVA_HOME"] = home
    return env


def _resolve_gradle_java_home() -> Path | None:
    java_home = _current_java_home()
    if java_home is None:
        return None
    major = _java_major_version(java_home)
    if major is None or major < 25:
        return None
    return _find_supported_jdk_home()


def _current_java_home() -> Path | None:
    current = os.environ.get("JAVA_HOME", "").strip() or os.environ.get("ORG_GRADLE_JAVA_HOME", "").strip()
    if not current:
        return None
    return Path(current)


def _java_major_version(java_home: Path) -> int | None:
    java_bin = java_home / "bin" / "java"
    if not java_bin.exists():
        return None
    result = subprocess.run(
        [str(java_bin), "-version"],
        cwd=java_home,
        check=False,
        capture_output=True,
        text=True,
    )
    version_output = "\n".join(part for part in ((result.stdout or "").strip(), (result.stderr or "").strip()) if part)
    return _parse_java_major_version(version_output)


def _parse_java_major_version(version_text: str) -> int | None:
    for line in version_text.splitlines():
        match = re.search(r'version\s+"([^"]+)"', line)
        if match:
            return _major_from_version_string(match.group(1))
    return None


def _major_from_version_string(version_string: str) -> int | None:
    cleaned = version_string.strip()
    if not cleaned:
        return None
    parts = cleaned.split(".")
    if parts[0] == "1" and len(parts) > 1:
        return int(parts[1]) if parts[1].isdigit() else None
    first = re.match(r"^(\d+)", parts[0])
    return int(first.group(1)) if first else None


def _find_supported_jdk_home() -> Path | None:
    for major in _SUPPORTED_GRADLE_JAVA_MAJORS:
        candidate = _find_jdk_home(major)
        if candidate is not None:
            return candidate
    return None


def _find_jdk_home(major: int) -> Path | None:
    for env_var in _jdk_env_vars(major):
        candidate_value = os.environ.get(env_var, "").strip()
        if not candidate_value:
            continue
        candidate = Path(candidate_value)
        if _java_major_version(candidate) == major:
            return candidate

    mac_java_home = Path("/usr/libexec/java_home")
    if mac_java_home.exists():
        result = subprocess.run(
            [str(mac_java_home), "-v", str(major)],
            check=False,
            capture_output=True,
            text=True,
        )
        candidate_value = (result.stdout or "").strip()
        if result.returncode == 0 and candidate_value:
            candidate = Path(candidate_value)
            if _java_major_version(candidate) == major:
                return candidate

    for candidate_value in _jdk_common_home_candidates(major):
        candidate = Path(candidate_value)
        if candidate.exists() and _java_major_version(candidate) == major:
            return candidate

    return None


def _jdk_env_vars(major: int) -> tuple[str, ...]:
    return (
        f"JAVA_{major}_HOME",
        f"JDK{major}_HOME",
        f"JAVA{major}_HOME",
        f"OPENJDK{major}_HOME",
    )


def _jdk_common_home_candidates(major: int) -> tuple[str, ...]:
    return (
        f"/opt/homebrew/opt/openjdk@{major}/libexec/openjdk.jdk/Contents/Home",
        f"/usr/local/opt/openjdk@{major}/libexec/openjdk.jdk/Contents/Home",
        f"/Library/Java/JavaVirtualMachines/openjdk-{major}.jdk/Contents/Home",
    )


def _parse_detekt_report(report_path: Path, repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    root = ET.parse(report_path).getroot()
    for file_node in root.findall("file"):
        file_name = file_node.attrib.get("name", "").strip()
        if not file_name:
            continue
        file_path = Path(file_name)
        if not file_path.is_absolute():
            file_path = (repo_root / file_path).resolve()
        try:
            rel = file_path.resolve().relative_to(repo_root.resolve())
        except ValueError:
            rel = file_path.resolve()
        for error in file_node.findall("error"):
            line_number = int(error.attrib.get("line", "1"))
            source = error.attrib.get("source", "detekt")
            message = error.attrib.get("message", "").strip()
            findings.append(
                Finding(
                    tool="detekt",
                    path=rel,
                    line_number=line_number,
                    rule_id=source,
                    message=message,
                ),
            )
    return findings


def _parse_ktlint_report(report_path: Path, repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    root = ET.parse(report_path).getroot()
    for file_node in root.findall("file"):
        name = file_node.attrib.get("name", "").strip()
        if not name:
            continue
        file_path = Path(name)
        if not file_path.is_absolute():
            file_path = (repo_root / file_path).resolve()
        try:
            rel = file_path.resolve().relative_to(repo_root.resolve())
        except ValueError:
            rel = file_path.resolve()
        for error in file_node.findall("error"):
            line_number = int(error.attrib.get("line", "1"))
            rule = error.attrib.get("source", "ktlint")
            message = error.attrib.get("message", "").strip()
            findings.append(
                Finding(
                    tool="ktlint",
                    path=rel,
                    line_number=line_number,
                    rule_id=rule,
                    message=message,
                ),
            )
    return findings


def collect_findings(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for report in repo_root.glob("**/build/reports/detekt/*.xml"):
        findings.extend(_parse_detekt_report(report, repo_root))
    for report in repo_root.glob("**/build/reports/ktlint/**/*.xml"):
        findings.extend(_parse_ktlint_report(report, repo_root))
    findings.sort(key=lambda item: (item.path.as_posix(), item.line_number, item.tool, item.rule_id))
    return findings


def _git_changed_files(repo_root: Path, base_ref: str | None = None) -> list[Path]:
    resolved_base_ref = base_ref
    if not resolved_base_ref:
        github_base_ref = os.environ.get("GITHUB_BASE_REF", "").strip()
        if github_base_ref:
            resolved_base_ref = (
                github_base_ref
                if github_base_ref.startswith("origin/")
                else f"origin/{github_base_ref}"
            )

    def query(command: list[str]) -> list[str] | None:
        result = subprocess.run(command, cwd=repo_root, check=False, capture_output=True, text=True)
        if result.returncode != 0:
            return None
        return [line.strip() for line in (result.stdout or "").splitlines() if line.strip()]

    def kotlin_paths(entries: list[str]) -> list[Path]:
        return [
            path
            for entry in entries
            for path in (Path(entry),)
            if path.suffix in {".kt", ".kts"} and (path.as_posix().startswith("apps/") or path.as_posix().startswith("packages/"))
        ]

    if resolved_base_ref:
        base_entries = query(["git", "diff", "--name-only", f"{resolved_base_ref}...HEAD"])
        if base_entries is not None:
            return kotlin_paths(base_entries)

    worktree_entries: set[str] = set()
    worktree_query_succeeded = False
    for command in (
        ["git", "diff", "--name-only", "HEAD"],
        ["git", "ls-files", "--modified", "--others", "--exclude-standard"],
    ):
        entries = query(command)
        if entries is not None:
            worktree_query_succeeded = True
            worktree_entries.update(entries)
    if worktree_query_succeeded and worktree_entries:
        return kotlin_paths(sorted(worktree_entries))

    previous_commit_entries = query(["git", "diff", "--name-only", "HEAD~1...HEAD"])
    return kotlin_paths(previous_commit_entries or [])


def _format_finding(finding: Finding) -> str:
    return (
        f"- {finding.path.as_posix()}:{finding.line_number}: "
        f"[{finding.tool}] {finding.rule_id} {finding.message}".strip()
    )


def run_gate(
    repo_root: Path,
    *,
    strict_changed_only: bool,
    base_ref: str | None = None,
    skip_gradle: bool = False,
) -> tuple[list[Finding], list[Finding], list[Path]]:
    if not skip_gradle:
        _run_gradle_analysis(repo_root)
    findings = collect_findings(repo_root)
    changed_files = _git_changed_files(repo_root, base_ref=base_ref)
    changed_set = {path.as_posix() for path in changed_files}
    changed_findings = [finding for finding in findings if finding.path.as_posix() in changed_set]
    return findings, changed_findings, changed_files


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Kotlin static analysis gate (detekt + ktlint).")
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Repository root path (default: detected from this script location).",
    )
    parser.add_argument(
        "--strict-changed-only",
        action="store_true",
        help="Fail only when findings are in changed Kotlin files.",
    )
    parser.add_argument(
        "--base-ref",
        type=str,
        default=None,
        help="Optional git base ref (for example origin/main) used to compute changed files.",
    )
    parser.add_argument(
        "--skip-gradle",
        action="store_true",
        help="Skip Gradle execution and only parse existing reports (test helper).",
    )
    parsed = parser.parse_args(argv)
    repo_root = parsed.repo_root.resolve()

    try:
        findings, changed_findings, changed_files = run_gate(
            repo_root,
            strict_changed_only=parsed.strict_changed_only,
            base_ref=parsed.base_ref,
            skip_gradle=parsed.skip_gradle,
        )
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1

    print(
        f"Kotlin static analysis findings: total={len(findings)} changed={len(changed_findings)} "
        f"changed_files={len(changed_files)}"
    )
    if parsed.strict_changed_only and not changed_files:
        print("Warning: no changed Kotlin files detected; strict changed-files gate had nothing to evaluate.")
    if changed_files:
        print("Changed Kotlin files:")
        for path in changed_files:
            print(f"- {path.as_posix()}")

    if parsed.strict_changed_only and changed_findings:
        print("Kotlin static analysis gate failed on changed files:", file=sys.stderr)
        for finding in changed_findings:
            print(_format_finding(finding), file=sys.stderr)
        return 1

    if changed_findings:
        print("Kotlin static analysis warnings on changed files:")
        for finding in changed_findings:
            print(_format_finding(finding))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
