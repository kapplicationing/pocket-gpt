#!/usr/bin/env python3
"""Fail-closed parsers for PocketGPT's Android performance harness."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence


SCENARIO_FINAL_STATES = {
    "settings-nav": ("completion_system_prompt_input", "SmoothSettingsProbe"),
    "model-sheet": ("model_search_input", "qwen"),
    "drawer-search": ("session_search_input", "chat"),
}
BOUNDS_PATTERN = re.compile(r"^\[(\d+),(\d+)]\[(\d+),(\d+)]$")
PACKAGE_QUALIFIED_ID_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_.]*:id/(.+)$")
COMPONENT_PATTERN = re.compile(
    r"(?:^|\s)([A-Za-z][A-Za-z0-9_.]*)/([A-Za-z0-9_.$]+)(?:\s|}|$)"
)


class HarnessValidationError(ValueError):
    """Raised when UI, focus, or package evidence is ambiguous or inconsistent."""


@dataclass(frozen=True)
class PackageIdentity:
    version_code: str
    version_name: str
    last_update_time: str
    installed_apk_paths: tuple[str, ...]
    debuggable: bool


def _parse_ui(source: str) -> ElementTree.Element:
    try:
        return ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise HarnessValidationError(f"invalid UI hierarchy XML: {error}") from error


def _resource_id_matches(resource_id: str, expected: str) -> bool:
    if resource_id == expected:
        return True
    qualified = PACKAGE_QUALIFIED_ID_PATTERN.fullmatch(resource_id)
    return qualified is not None and qualified.group(1) == expected


def _find_exact_node(source: str, resource_id: str) -> ElementTree.Element:
    matches = [
        node
        for node in _parse_ui(source).iter("node")
        if _resource_id_matches(node.attrib.get("resource-id", ""), resource_id)
    ]
    if not matches:
        raise HarnessValidationError(f"exact resource-id {resource_id!r} was not found")
    if len(matches) != 1:
        raise HarnessValidationError(
            f"exact resource-id {resource_id!r} is ambiguous: found {len(matches)} nodes"
        )
    return matches[0]


def find_node_center(source: str, resource_id: str) -> tuple[int, int]:
    node = _find_exact_node(source, resource_id)
    match = BOUNDS_PATTERN.fullmatch(node.attrib.get("bounds", ""))
    if not match:
        raise HarnessValidationError(f"exact resource-id {resource_id!r} has invalid bounds")
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        raise HarnessValidationError(f"exact resource-id {resource_id!r} has empty bounds")
    return (left + right) // 2, (top + bottom) // 2


def node_text_length(source: str, resource_id: str) -> int:
    return len(_find_exact_node(source, resource_id).attrib.get("text", ""))


def scenario_final_state(scenario: str) -> tuple[str, str]:
    try:
        return SCENARIO_FINAL_STATES[scenario]
    except KeyError as error:
        raise HarnessValidationError(f"unsupported scenario {scenario!r}") from error


def assert_scenario_final_state(source: str, scenario: str) -> None:
    resource_id, expected_text = scenario_final_state(scenario)
    node = _find_exact_node(source, resource_id)
    observed_text = node.attrib.get("text", "")
    if observed_text != expected_text:
        raise HarnessValidationError(
            f"scenario {scenario!r} expected exact text {expected_text!r} in "
            f"{resource_id!r}, observed {observed_text!r}"
        )


def _component_package(line: str) -> str | None:
    match = COMPONENT_PATTERN.search(line)
    return match.group(1) if match else None


def assert_foreground(source: str, package: str) -> dict[str, str | None]:
    current_lines = [line for line in source.splitlines() if "mCurrentFocus=" in line]
    if len(current_lines) != 1:
        raise HarnessValidationError(
            f"expected exactly one mCurrentFocus line, found {len(current_lines)}"
        )
    current_package = _component_package(current_lines[0])
    if current_package is None:
        raise HarnessValidationError("mCurrentFocus does not contain a parseable component")

    focused_lines = [line for line in source.splitlines() if "mFocusedApp=" in line]
    focused_packages = [
        parsed for parsed in (_component_package(line) for line in focused_lines) if parsed
    ]
    if len(set(focused_packages)) > 1:
        raise HarnessValidationError(f"mFocusedApp is ambiguous: {focused_packages}")
    focused_package = focused_packages[0] if focused_packages else None

    observed = [current_package]
    if focused_package is not None:
        observed.append(focused_package)
    if any(value != package for value in observed):
        raise HarnessValidationError(
            f"expected exact foreground package {package!r}, observed "
            f"mCurrentFocus={current_package!r}, mFocusedApp={focused_package!r}"
        )
    return {
        "current_focus_package": current_package,
        "focused_app_package": focused_package,
    }


def parse_package_identity(package_dump: str, pm_path_source: str) -> PackageIdentity:
    def required(pattern: str, label: str) -> str:
        match = re.search(pattern, package_dump, re.MULTILINE)
        if not match or not match.group(1).strip():
            raise HarnessValidationError(f"installed package {label} is missing")
        return match.group(1).strip()

    paths = tuple(
        line.removeprefix("package:").strip()
        for line in pm_path_source.splitlines()
        if line.removeprefix("package:").strip()
    )
    if not paths or any(not path.startswith("/") or not path.endswith(".apk") for path in paths):
        raise HarnessValidationError("installed package APK paths are missing or invalid")
    flags = required(r"^\s*pkgFlags=(.+)$", "pkgFlags")
    return PackageIdentity(
        version_code=required(r"\bversionCode=([^\s]+)", "versionCode"),
        version_name=required(r"^\s*versionName=(.+)$", "versionName"),
        last_update_time=required(r"^\s*lastUpdateTime=(.+)$", "lastUpdateTime"),
        installed_apk_paths=paths,
        debuggable=re.search(r"(?:^|[\s\[])DEBUGGABLE(?:[\s\]]|$)", flags) is not None,
    )


def assert_package_identity_unchanged(
    before_dump: str,
    before_paths: str,
    after_dump: str,
    after_paths: str,
) -> PackageIdentity:
    before = parse_package_identity(before_dump, before_paths)
    after = parse_package_identity(after_dump, after_paths)
    if after != before:
        raise HarnessValidationError(
            "installed package identity changed during the journey: "
            f"before={asdict(before)!r}, after={asdict(after)!r}"
        )
    return after


def run_id(pid: int | None = None) -> str:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    return f"{timestamp}-pid{os.getpid() if pid is None else pid}"


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise HarnessValidationError(f"cannot read {path}: {error}") from error


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    center = subparsers.add_parser("center")
    center.add_argument("--xml", required=True, type=Path)
    center.add_argument("--resource-id", required=True)

    text_length = subparsers.add_parser("text-length")
    text_length.add_argument("--xml", required=True, type=Path)
    text_length.add_argument("--resource-id", required=True)

    state = subparsers.add_parser("scenario-state")
    state.add_argument("--scenario", required=True)

    final = subparsers.add_parser("assert-scenario-final")
    final.add_argument("--xml", required=True, type=Path)
    final.add_argument("--scenario", required=True)

    foreground = subparsers.add_parser("assert-foreground")
    foreground.add_argument("--window", required=True, type=Path)
    foreground.add_argument("--package", required=True)

    package = subparsers.add_parser("assert-package-stable")
    package.add_argument("--before-dump", required=True, type=Path)
    package.add_argument("--before-paths", required=True, type=Path)
    package.add_argument("--after-dump", required=True, type=Path)
    package.add_argument("--after-paths", required=True, type=Path)

    identifier = subparsers.add_parser("run-id")
    identifier.add_argument("--pid", type=int, default=os.getpid())
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        if args.command == "center":
            x, y = find_node_center(_read(args.xml), args.resource_id)
            print(f"{x} {y}")
        elif args.command == "text-length":
            print(node_text_length(_read(args.xml), args.resource_id))
        elif args.command == "scenario-state":
            print("\t".join(scenario_final_state(args.scenario)))
        elif args.command == "assert-scenario-final":
            assert_scenario_final_state(_read(args.xml), args.scenario)
        elif args.command == "assert-foreground":
            observed = assert_foreground(_read(args.window), args.package)
            print(json.dumps(observed, sort_keys=True))
        elif args.command == "assert-package-stable":
            identity = assert_package_identity_unchanged(
                _read(args.before_dump),
                _read(args.before_paths),
                _read(args.after_dump),
                _read(args.after_paths),
            )
            print(json.dumps(asdict(identity), sort_keys=True))
        elif args.command == "run-id":
            print(run_id(args.pid))
    except HarnessValidationError as error:
        print(f"Harness validation error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
