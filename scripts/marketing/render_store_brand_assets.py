#!/usr/bin/env python3
"""Render deterministic PocketAgent Play listing brand assets."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_DIR = REPO_ROOT / "docs/operations/assets/mkt-04"
FONT_PATH = REPO_ROOT / "scripts/marketing/assets/fonts/OpenSans-Variable.ttf"
FONT_LICENSE_PATH = REPO_ROOT / "scripts/marketing/assets/fonts/OpenSans-OFL.txt"
PURPLE = (101, 71, 184)
DEEP_PURPLE = (48, 31, 105)
LIGHT_PURPLE = (224, 214, 255)
WHITE = (255, 255, 255)


def _font(size: int, *, bold: bool) -> ImageFont.FreeTypeFont:
    if not FONT_PATH.is_file() or not FONT_LICENSE_PATH.is_file():
        raise FileNotFoundError("Pinned Open Sans font or OFL license is missing")
    font = ImageFont.truetype(str(FONT_PATH), size=size)
    font.set_variation_by_name("Bold" if bold else "Regular")
    return font


def _draw_mark(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], *, foreground: tuple[int, int, int], dot_color: tuple[int, int, int]) -> None:
    left, top, right, bottom = box
    width = right - left
    height = bottom - top
    bubble_bottom = top + int(height * 0.72)
    radius = max(1, int(min(width, height) * 0.13))
    draw.rounded_rectangle((left, top, right, bubble_bottom), radius=radius, fill=foreground)
    tail_left = left + int(width * 0.25)
    tail_right = left + int(width * 0.48)
    tail_tip = (tail_left, bottom)
    draw.polygon(((tail_left, bubble_bottom - 2), (tail_right, bubble_bottom - 2), tail_tip), fill=foreground)

    dot_size = max(2, int(width * 0.11))
    dot_y = top + int(height * 0.31)
    for fraction in (0.27, 0.50, 0.73):
        center_x = left + int(width * fraction)
        draw.rounded_rectangle(
            (
                center_x - dot_size // 2,
                dot_y - dot_size // 2,
                center_x + dot_size // 2,
                dot_y + dot_size // 2,
            ),
            radius=max(1, dot_size // 5),
            fill=dot_color,
        )


def _render_icon() -> Image.Image:
    scale = 4
    image = Image.new("RGB", (512 * scale, 512 * scale), PURPLE)
    draw = ImageDraw.Draw(image)
    _draw_mark(
        draw,
        (96 * scale, 102 * scale, 416 * scale, 426 * scale),
        foreground=WHITE,
        dot_color=PURPLE,
    )
    return image.resize((512, 512), Image.Resampling.LANCZOS)


def _render_feature_graphic() -> Image.Image:
    scale = 2
    width, height = 1024 * scale, 500 * scale
    image = Image.new("RGB", (width, height), DEEP_PURPLE)
    pixels = image.load()
    for y in range(height):
        for x in range(width):
            blend = (x / max(1, width - 1)) * 0.55 + (1 - y / max(1, height - 1)) * 0.10
            pixels[x, y] = tuple(
                round(DEEP_PURPLE[channel] * (1 - blend) + PURPLE[channel] * blend)
                for channel in range(3)
            )

    draw = ImageDraw.Draw(image)
    _draw_mark(
        draw,
        (74 * scale, 92 * scale, 338 * scale, 382 * scale),
        foreground=WHITE,
        dot_color=PURPLE,
    )
    title_font = _font(80 * scale, bold=True)
    subtitle_font = _font(35 * scale, bold=False)
    detail_font = _font(24 * scale, bold=False)
    text_left = 410 * scale
    draw.text((text_left, 126 * scale), "PocketAgent", font=title_font, fill=WHITE)
    draw.text((text_left, 235 * scale), "Local AI, on your phone.", font=subtitle_font, fill=LIGHT_PURPLE)
    draw.text((text_left, 300 * scale), "Chat offline after model setup.", font=detail_font, fill=WHITE)
    return image.resize((1024, 500), Image.Resampling.LANCZOS)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def render(output_dir: Path) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    assets = {
        "play-listing-icon.png": _render_icon(),
        "feature-graphic.png": _render_feature_graphic(),
    }
    written: list[Path] = []
    for name, image in assets.items():
        path = output_dir / name
        image.save(path, format="PNG", optimize=True)
        written.append(path)

    provenance = {
        "schema": "pocketagent-store-brand-assets-v1",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": "PocketAgent adaptive launcher mark and frozen claim-safe copy",
        "approval_status": "product_and_marketing_approval_required",
        "font": {
            "family": "Open Sans",
            "file": str(FONT_PATH.relative_to(REPO_ROOT)),
            "sha256": _sha256(FONT_PATH),
            "license": str(FONT_LICENSE_PATH.relative_to(REPO_ROOT)),
            "license_sha256": _sha256(FONT_LICENSE_PATH),
        },
        "assets": [
            {
                "file": path.name,
                "width": Image.open(path).width,
                "height": Image.open(path).height,
                "sha256": _sha256(path),
            }
            for path in written
        ],
    }
    provenance_path = output_dir / "store-brand-assets-provenance.json"
    provenance_path.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    written.append(provenance_path)
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()
    for path in render(args.output_dir.resolve()):
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
