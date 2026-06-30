#!/usr/bin/env python3
"""Tiny Hugging Face-compatible fixture server for PocketGPT device tests."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import time
from http import HTTPStatus
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import unquote


REPO_ID = "fixture/tiny-gguf"
REVISION = "main"
FILE_PATH = "tiny.gguf"
ETAG = '"pocketgpt-hf-fixture-v1"'
LAST_MODIFIED = "Mon, 29 Jun 2026 00:00:00 GMT"


def deterministic_bytes(size: int) -> bytes:
    pattern = b"PocketGPT-HF-fixture-GGUF-bytes\n"
    repeats = (size // len(pattern)) + 1
    return (pattern * repeats)[:size]


class FixtureState:
    def __init__(
        self,
        payload: bytes,
        mode: str,
        chunk_size: int,
        chunk_delay_ms: int,
    ) -> None:
        self.payload = payload
        self.mode = mode
        self.chunk_size = max(1, chunk_size)
        self.chunk_delay_s = max(0, chunk_delay_ms) / 1000
        self.sha256 = hashlib.sha256(payload).hexdigest()
        self.reported_sha256 = (
            "0" * 64 if mode == "checksum-mismatch" else self.sha256
        )


class FixtureHandler(BaseHTTPRequestHandler):
    server_version = "PocketGPTHFFixture/1.0"

    @property
    def state(self) -> FixtureState:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("[hf-fixture] " + (fmt % args) + "\n")

    def do_GET(self) -> None:
        path = unquote(self.path.split("?", 1)[0])
        if path == "/health":
            self._write_text(HTTPStatus.OK, "ok\n")
            return
        if path == f"/api/models/{REPO_ID}/tree/{REVISION}":
            self._write_tree()
            return
        if path in {
            f"/api/models/{REPO_ID}",
            f"/api/models/{REPO_ID}/revision/{REVISION}",
        }:
            self._write_model_info()
            return
        if path == f"/{REPO_ID}/resolve/{REVISION}/{FILE_PATH}":
            self._write_artifact()
            return
        self._write_text(HTTPStatus.NOT_FOUND, "not found\n")

    def _write_tree(self) -> None:
        mode = self.state.mode
        if mode == "gated":
            self._write_text(HTTPStatus.FORBIDDEN, "gated\n")
            return
        if mode == "not-found":
            self._write_text(HTTPStatus.NOT_FOUND, "missing\n")
            return

        lfs: dict[str, object] = {}
        if mode != "missing-sha":
            lfs["oid"] = self.state.reported_sha256
        if mode != "missing-size":
            lfs["size"] = len(self.state.payload)
        item: dict[str, object] = {
            "path": FILE_PATH,
            "rfilename": FILE_PATH,
            "lfs": lfs,
        }
        if mode != "missing-size":
            item["size"] = len(self.state.payload)
        body = [item]
        self._write_json(HTTPStatus.OK, body)

    def _write_model_info(self) -> None:
        if self.state.mode == "gated":
            self._write_text(HTTPStatus.FORBIDDEN, "gated\n")
            return
        if self.state.mode == "not-found":
            self._write_text(HTTPStatus.NOT_FOUND, "missing\n")
            return
        self._write_json(
            HTTPStatus.OK,
            {
                "id": REPO_ID,
                "cardData": {
                    "license": "apache-2.0",
                    "license_link": "https://huggingface.co/fixture/tiny-gguf/blob/main/LICENSE",
                },
                "tags": [
                    "license:apache-2.0",
                ],
            },
        )

    def _write_artifact(self) -> None:
        if self.state.mode == "not-found":
            self._write_text(HTTPStatus.NOT_FOUND, "missing\n")
            return
        payload = self.state.payload
        start = 0
        end = len(payload) - 1
        status = HTTPStatus.OK
        range_header = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        if range_header and (not if_range or if_range in {ETAG, LAST_MODIFIED}):
            parsed = self._parse_range(range_header, len(payload))
            if parsed is None:
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", f"bytes */{len(payload)}")
                self.end_headers()
                return
            start, end = parsed
            status = HTTPStatus.PARTIAL_CONTENT

        body = payload[start : end + 1]
        self.send_response(status)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("ETag", ETAG)
        self.send_header("Last-Modified", LAST_MODIFIED)
        self.send_header("Content-Length", str(len(body)))
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(payload)}")
        self.end_headers()
        self._write_throttled(body)

    def _parse_range(self, header: str, total: int) -> tuple[int, int] | None:
        if not header.startswith("bytes="):
            return None
        raw_start, _, raw_end = header.removeprefix("bytes=").partition("-")
        try:
            start = int(raw_start)
            end = int(raw_end) if raw_end else total - 1
        except ValueError:
            return None
        if start < 0 or end < start or start >= total:
            return None
        return start, min(end, total - 1)

    def _write_json(self, status: HTTPStatus, body: object) -> None:
        encoded = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _write_text(self, status: HTTPStatus, body: str) -> None:
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _write_throttled(self, body: bytes) -> None:
        chunk_size = self.state.chunk_size
        delay = self.state.chunk_delay_s
        for offset in range(0, len(body), chunk_size):
            self.wfile.write(body[offset : offset + chunk_size])
            self.wfile.flush()
            if delay:
                time.sleep(delay)


class FixtureServer(ThreadingHTTPServer):
    def __init__(self, server_address: tuple[str, int], state: FixtureState) -> None:
        super().__init__(server_address, FixtureHandler)
        self.state = state


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--size-bytes", type=int, default=4 * 1024 * 1024)
    parser.add_argument("--chunk-size", type=int, default=16 * 1024)
    parser.add_argument("--chunk-delay-ms", type=int, default=10)
    parser.add_argument(
        "--mode",
        choices=(
            "ok",
            "missing-sha",
            "missing-size",
            "gated",
            "not-found",
            "checksum-mismatch",
        ),
        default="ok",
    )
    parser.add_argument("--write-manifest", type=pathlib.Path)
    args = parser.parse_args()

    payload = deterministic_bytes(args.size_bytes)
    state = FixtureState(
        payload=payload,
        mode=args.mode,
        chunk_size=args.chunk_size,
        chunk_delay_ms=args.chunk_delay_ms,
    )
    base_url = f"http://{args.host}:{args.port}"
    manifest = {
        "base_url": base_url,
        "canonical_url": f"https://huggingface.co/{REPO_ID}/resolve/{REVISION}/{FILE_PATH}",
        "download_url": f"{base_url}/{REPO_ID}/resolve/{REVISION}/{FILE_PATH}",
        "mode": args.mode,
        "repo_id": REPO_ID,
        "revision": REVISION,
        "file_path": FILE_PATH,
        "license": "apache-2.0",
        "license_url": f"https://huggingface.co/{REPO_ID}/blob/{REVISION}/LICENSE",
        "sha256": state.sha256,
        "reported_sha256": state.reported_sha256,
        "size_bytes": len(payload),
    }
    if args.write_manifest:
        args.write_manifest.parent.mkdir(parents=True, exist_ok=True)
        args.write_manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(manifest, sort_keys=True), flush=True)
    FixtureServer((args.host, args.port), state).serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
