#!/usr/bin/env python3
"""Atomic per-device/package lease for Android performance measurements."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import socket
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence


DEFAULT_ROOT = Path(
    os.environ.get(
        "POCKETGPT_ANDROID_PERF_LOCK_ROOT",
        str(Path(tempfile.gettempdir()) / "pocketgpt-android-perf-locks"),
    )
)
OWNER_FILE = "owner.json"


class LockError(RuntimeError):
    """Raised when a performance device lease cannot be acquired or validated."""


@dataclass(frozen=True)
class LockLease:
    token: str
    path: Path


def _lock_path(serial: str, package: str, root: Path) -> Path:
    digest = hashlib.sha256(f"{serial}\0{package}".encode()).hexdigest()
    return root / digest


def _read_metadata(path: Path) -> dict[str, Any]:
    owner_path = path / OWNER_FILE
    try:
        metadata = json.loads(owner_path.read_text(encoding="utf-8"))
    except OSError as error:
        raise LockError(f"occupied lock has no readable owner metadata at {owner_path}") from error
    except json.JSONDecodeError as error:
        raise LockError(f"occupied lock has invalid owner metadata at {owner_path}") from error
    if not isinstance(metadata, dict):
        raise LockError(f"occupied lock owner metadata must be an object at {owner_path}")
    return metadata


def _owner_description(path: Path) -> str:
    try:
        metadata = _read_metadata(path)
    except LockError as error:
        return str(error)
    return (
        f"owner_pid={metadata.get('owner_pid')!r} "
        f"started_at_utc={metadata.get('started_at_utc')!r} "
        f"owner_command={metadata.get('owner_command')!r}"
    )


def acquire_lock(
    *,
    serial: str,
    package: str,
    owner_pid: int,
    owner_command: str,
    root: Path = DEFAULT_ROOT,
) -> LockLease:
    if not serial or not package or owner_pid <= 0 or not owner_command:
        raise LockError("serial, package, positive owner PID, and owner command are required")
    root = Path(root)
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = _lock_path(serial, package, root)
    try:
        path.mkdir(mode=0o700)
    except FileExistsError as error:
        raise LockError(
            f"performance lock is occupied for serial={serial!r} package={package!r}: "
            f"{_owner_description(path)}"
        ) from error

    token = secrets.token_hex(24)
    metadata = {
        "serial": serial,
        "package": package,
        "token": token,
        "owner_pid": owner_pid,
        "owner_parent_pid": os.getppid(),
        "owner_host": socket.gethostname(),
        "owner_command": owner_command,
        "started_at_utc": datetime.now(timezone.utc).isoformat(timespec="microseconds"),
    }
    temp_path = path / f".{OWNER_FILE}.{owner_pid}.{token}.tmp"
    try:
        with temp_path.open("x", encoding="utf-8") as stream:
            json.dump(metadata, stream, indent=2, sort_keys=True)
            stream.write("\n")
        temp_path.replace(path / OWNER_FILE)
    except Exception:
        temp_path.unlink(missing_ok=True)
        path.rmdir()
        raise
    return LockLease(token=token, path=path)


def validate_lock(
    *,
    serial: str,
    package: str,
    token: str,
    root: Path = DEFAULT_ROOT,
) -> dict[str, Any]:
    path = _lock_path(serial, package, Path(root))
    metadata = _read_metadata(path)
    for field, expected in (("serial", serial), ("package", package), ("token", token)):
        if metadata.get(field) != expected:
            raise LockError(
                f"performance lock {field} does not match the owning lease; "
                f"expected {expected!r}, observed {metadata.get(field)!r}"
            )
    return metadata


def release_lock(
    *,
    serial: str,
    package: str,
    token: str,
    root: Path = DEFAULT_ROOT,
) -> None:
    path = _lock_path(serial, package, Path(root))
    validate_lock(serial=serial, package=package, token=token, root=root)
    unexpected_entries = [entry.name for entry in path.iterdir() if entry.name != OWNER_FILE]
    if unexpected_entries:
        raise LockError(
            f"refusing to release lock directory {path} with unexpected entries: "
            f"{sorted(unexpected_entries)}"
        )
    (path / OWNER_FILE).unlink()
    try:
        path.rmdir()
    except OSError as error:
        raise LockError(f"refusing to remove non-empty lock directory {path}: {error}") from error


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("acquire", "validate", "release"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--token")
    parser.add_argument("--owner-pid", type=int)
    parser.add_argument("--owner-command")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        if args.command == "acquire":
            if args.owner_pid is None or args.owner_command is None:
                raise LockError("acquire requires --owner-pid and --owner-command")
            lease = acquire_lock(
                serial=args.serial,
                package=args.package,
                owner_pid=args.owner_pid,
                owner_command=args.owner_command,
                root=args.root,
            )
            print(f"{lease.token}\t{lease.path}")
        else:
            if args.token is None:
                raise LockError(f"{args.command} requires --token")
            if args.command == "validate":
                validate_lock(
                    serial=args.serial,
                    package=args.package,
                    token=args.token,
                    root=args.root,
                )
            else:
                release_lock(
                    serial=args.serial,
                    package=args.package,
                    token=args.token,
                    root=args.root,
                )
    except (LockError, OSError) as error:
        print(f"[android-perf-lock] {error}", file=sys.stderr)
        return 73
    return 0


if __name__ == "__main__":
    sys.exit(main())
