#!/usr/bin/env python3
"""Fail closed unless an APK packages a generated PocketGPT Baseline Profile."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


PACKAGED_PROFILE_ENTRIES = (
    "assets/dexopt/baseline.prof",
    "assets/dexopt/baseline.profm",
)


@dataclass(frozen=True)
class VerificationResult:
    apk: str
    generated_profile: str
    merged_profile: str
    app_rule_count: int
    packaged_entry_sizes: dict[str, int]


class BaselineProfileVerificationError(RuntimeError):
    pass


def _profile_rules(path: Path) -> set[str]:
    if not path.is_file():
        raise BaselineProfileVerificationError(f"Profile does not exist: {path}")
    rules = {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    if not rules:
        raise BaselineProfileVerificationError(f"Profile is empty: {path}")
    return rules


def verify_baseline_profile(
    apk: Path,
    generated_profile: Path,
    merged_profile: Path,
    package_descriptor_prefix: str = "Lcom/pocketagent/",
) -> VerificationResult:
    if not apk.is_file():
        raise BaselineProfileVerificationError(f"APK does not exist: {apk}")

    try:
        with zipfile.ZipFile(apk) as archive:
            packaged_entry_sizes = {}
            for entry in PACKAGED_PROFILE_ENTRIES:
                try:
                    size = archive.getinfo(entry).file_size
                except KeyError as exc:
                    raise BaselineProfileVerificationError(
                        f"APK is missing {entry}: {apk}",
                    ) from exc
                if size <= 0:
                    raise BaselineProfileVerificationError(
                        f"APK contains an empty {entry}: {apk}",
                    )
                packaged_entry_sizes[entry] = size
    except zipfile.BadZipFile as exc:
        raise BaselineProfileVerificationError(f"Invalid APK zip: {apk}") from exc

    generated_rules = _profile_rules(generated_profile)
    app_rules = {
        rule
        for rule in generated_rules
        if package_descriptor_prefix in rule
    }
    if not app_rules:
        raise BaselineProfileVerificationError(
            "Generated profile has no PocketGPT app rules; transitive library profiles are not sufficient: "
            f"{generated_profile}",
        )

    merged_rules = _profile_rules(merged_profile)
    missing_rules = sorted(app_rules - merged_rules)
    if missing_rules:
        sample = ", ".join(missing_rules[:3])
        raise BaselineProfileVerificationError(
            f"Generated app rules were not merged into the APK profile input ({len(missing_rules)} missing; sample: {sample})",
        )

    return VerificationResult(
        apk=str(apk),
        generated_profile=str(generated_profile),
        merged_profile=str(merged_profile),
        app_rule_count=len(app_rules),
        packaged_entry_sizes=packaged_entry_sizes,
    )


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--generated-profile", type=Path, required=True)
    parser.add_argument("--merged-profile", type=Path, required=True)
    parser.add_argument("--json-output", type=Path)
    parser.add_argument(
        "--package-descriptor-prefix",
        default="Lcom/pocketagent/",
        help="ART descriptor prefix required in the generated profile.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv or sys.argv[1:])
    try:
        result = verify_baseline_profile(
            apk=args.apk,
            generated_profile=args.generated_profile,
            merged_profile=args.merged_profile,
            package_descriptor_prefix=args.package_descriptor_prefix,
        )
    except BaselineProfileVerificationError as exc:
        print(f"baseline profile verification failed: {exc}", file=sys.stderr)
        return 1

    payload = {
        "status": "pass",
        "apk": result.apk,
        "generated_profile": result.generated_profile,
        "merged_profile": result.merged_profile,
        "app_rule_count": result.app_rule_count,
        "packaged_entry_sizes": result.packaged_entry_sizes,
    }
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
