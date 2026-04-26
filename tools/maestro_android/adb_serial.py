from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Iterable, Sequence


_MDNS_CONNECT_SUFFIX = "._adb-tls-connect._tcp"
_ENDPOINT_PATTERN = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}:\d+\b")
_MDNS_PATTERN = re.compile(rf"[^\s]+\{re.escape(_MDNS_CONNECT_SUFFIX)}")


@dataclass(frozen=True)
class AdbDeviceRecord:
    serial: str
    state: str
    details: str
    aliases: tuple[str, ...]

    @property
    def authorized(self) -> bool:
        return self.state == "device"


@dataclass(frozen=True)
class MdnsServiceRecord:
    service: str
    endpoint: str


def normalize_serial_token(value: str) -> str:
    return value.strip().lower()


def is_mdns_tls_serial(serial: str) -> bool:
    return normalize_serial_token(serial).endswith(_MDNS_CONNECT_SUFFIX)


def is_network_serial(serial: str) -> bool:
    normalized = serial.strip()
    return (":" in normalized and not normalized.startswith("emulator-")) or is_mdns_tls_serial(normalized)


def parse_adb_devices_output(output: str) -> list[AdbDeviceRecord]:
    records: list[AdbDeviceRecord] = []
    for line in output.splitlines()[1:]:
        stripped = line.strip()
        if not stripped:
            continue
        parts = stripped.split()
        serial = parts[0]
        state = parts[1] if len(parts) > 1 else "unknown"
        details = " ".join(parts[2:]) if len(parts) > 2 else ""
        aliases = tuple(sorted(_extract_aliases(serial, details)))
        records.append(
            AdbDeviceRecord(
                serial=serial,
                state=state,
                details=details,
                aliases=aliases,
            )
        )
    return records


def parse_adb_mdns_services_output(output: str) -> list[MdnsServiceRecord]:
    records: list[MdnsServiceRecord] = []
    seen: set[tuple[str, str]] = set()
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line or "_adb-tls-connect._tcp" not in line:
            continue
        parts = line.split()
        service_match = next((token for token in parts if token.endswith(_MDNS_CONNECT_SUFFIX)), None)
        if service_match is None and len(parts) >= 2 and parts[1] == "_adb-tls-connect._tcp":
            service_match = f"{parts[0]}{_MDNS_CONNECT_SUFFIX}"
        endpoint_match = _ENDPOINT_PATTERN.search(line)
        if service_match is None or endpoint_match is None:
            continue
        key = (service_match, endpoint_match.group(0))
        if key in seen:
            continue
        seen.add(key)
        records.append(MdnsServiceRecord(service=key[0], endpoint=key[1]))
    return records


def merge_mdns_aliases(
    devices: Sequence[AdbDeviceRecord],
    mdns_services: Sequence[MdnsServiceRecord],
) -> list[AdbDeviceRecord]:
    merged: list[AdbDeviceRecord] = []
    for device in devices:
        aliases = set(device.aliases)
        aliases.add(device.serial)
        alias_tokens = {normalize_serial_token(token) for token in aliases}
        for service in mdns_services:
            service_aliases = {
                normalize_serial_token(service.service),
                normalize_serial_token(service.endpoint),
            }
            if alias_tokens.intersection(service_aliases):
                aliases.add(service.service)
                aliases.add(service.endpoint)
        merged.append(
            AdbDeviceRecord(
                serial=device.serial,
                state=device.state,
                details=device.details,
                aliases=tuple(sorted(aliases)),
            )
        )
    return merged


def resolve_requested_serial(
    requested_serial: str,
    devices: Sequence[AdbDeviceRecord],
) -> AdbDeviceRecord | None:
    requested = normalize_serial_token(requested_serial)
    if not requested:
        return None
    for device in devices:
        candidate_tokens = {normalize_serial_token(device.serial)}
        candidate_tokens.update(normalize_serial_token(alias) for alias in device.aliases)
        if requested in candidate_tokens:
            return device
    return None


def _extract_aliases(serial: str, details: str) -> set[str]:
    aliases = {serial}
    aliases.update(_MDNS_PATTERN.findall(serial))
    aliases.update(_ENDPOINT_PATTERN.findall(serial))
    if details:
        aliases.update(_MDNS_PATTERN.findall(details))
        aliases.update(_ENDPOINT_PATTERN.findall(details))
        for token in details.split():
            if ":" not in token:
                continue
            _, value = token.split(":", 1)
            if not value:
                continue
            aliases.update(_MDNS_PATTERN.findall(value))
            aliases.update(_ENDPOINT_PATTERN.findall(value))
    return {alias for alias in aliases if alias.strip()}
