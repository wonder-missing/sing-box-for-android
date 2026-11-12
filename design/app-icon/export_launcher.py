#!/usr/bin/env python3
"""Rasterize wondersing-icon.svg into the Android launcher densities.

Android VectorDrawable cannot express this SVG's masks, so the launcher
bitmaps are generated. Run from the repo root:

    python3 design/app-icon/export_launcher.py

Foreground is the SVG without the canvas fill. Legacy and Play icons keep
the #ECEFF1 background. Round icons are the legacy image clipped to a
circle. Monochrome is a white cube with the S and bevels knocked out.
"""

from __future__ import annotations

import math
import struct
import subprocess
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SVG = ROOT / "design" / "app-icon" / "wondersing-icon.svg"
RES = ROOT / "app" / "src" / "main" / "res"

FOREGROUND = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}
LEGACY = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
PLAY = [
    ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png",
    ROOT / "app" / "src" / "other" / "play" / "listings" / "en-US" / "graphics" / "icon" / "ic_launcher-playstore.png",
]


def read_png(path: Path) -> tuple[int, int, bytearray]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path} is not a PNG")
    pos = 8
    width = height = color = None
    idat = b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        kind = data[pos + 4 : pos + 8]
        chunk = data[pos + 8 : pos + 8 + length]
        if kind == b"IHDR":
            width, height, _bit, color = struct.unpack(">IIBB", chunk[:10])
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
        pos += 12 + length
    if width is None or color not in (2, 6):
        raise SystemExit(f"{path} has an unsupported PNG color type {color}")
    raw = zlib.decompress(idat)
    stride = 4 if color == 6 else 3
    rows = []
    index = 0
    previous = bytearray(width * stride)
    for _y in range(height):
        filter_type = raw[index]
        index += 1
        row = bytearray(raw[index : index + width * stride])
        index += width * stride
        if filter_type == 1:
            for x in range(len(row)):
                left = row[x - stride] if x >= stride else 0
                row[x] = (row[x] + left) & 255
        elif filter_type == 2:
            for x in range(len(row)):
                row[x] = (row[x] + previous[x]) & 255
        elif filter_type == 3:
            for x in range(len(row)):
                left = row[x - stride] if x >= stride else 0
                row[x] = (row[x] + ((left + previous[x]) // 2)) & 255
        elif filter_type == 4:
            for x in range(len(row)):
                left = row[x - stride] if x >= stride else 0
                up = previous[x]
                up_left = previous[x - stride] if x >= stride else 0
                row[x] = (row[x] + _paeth(left, up, up_left)) & 255
        elif filter_type != 0:
            raise SystemExit(f"{path} uses PNG filter {filter_type}")
        rows.append(row)
        previous = row
    pixels = bytearray(width * height * 4)
    for y, row in enumerate(rows):
        for x in range(width):
            src = x * stride
            dst = (y * width + x) * 4
            pixels[dst : dst + 3] = row[src : src + 3]
            pixels[dst + 3] = 255 if color == 2 else row[src + 3]
    return width, height, pixels


def _paeth(left: int, up: int, up_left: int) -> int:
    estimate = left + up - up_left
    distances = (abs(estimate - left), abs(estimate - up), abs(estimate - up_left))
    if distances[0] <= distances[1] and distances[0] <= distances[2]:
        return left
    if distances[1] <= distances[2]:
        return up
    return up_left


def write_png(path: Path, width: int, height: int, pixels: bytearray, drop_alpha: bool = False) -> None:
    if drop_alpha:
        raw = bytearray()
        for y in range(height):
            raw.append(0)
            for x in range(width):
                src = (y * width + x) * 4
                raw.extend(pixels[src : src + 3])
        color = 2
    else:
        raw = bytearray()
        for y in range(height):
            raw.append(0)
            raw.extend(pixels[y * width * 4 : (y + 1) * width * 4])
        color = 6
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, color, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def render(svg: str, size: int, dest: Path) -> tuple[int, int, bytearray]:
    dest.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["rsvg-convert", "-w", str(size), "-h", str(size), "-o", str(dest)],
        input=svg.encode(),
        check=True,
    )
    width, height, pixels = read_png(dest)
    if (width, height) != (size, size):
        raise SystemExit(f"{dest} rendered at {width}x{height}, expected {size}")
    return width, height, pixels


def circle_mask(width: int, height: int, pixels: bytearray) -> bytearray:
    out = bytearray(pixels)
    radius = width / 2
    for y in range(height):
        for x in range(width):
            dx = x + 0.5 - width / 2
            dy = y + 0.5 - height / 2
            distance = math.hypot(dx, dy)
            if distance <= radius - 0.5:
                coverage = 255
            elif distance >= radius + 0.5:
                coverage = 0
            else:
                coverage = int(round((radius + 0.5 - distance) * 255))
            index = (y * width + x) * 4
            out[index + 3] = pixels[index + 3] * coverage // 255
    return out


def knock_out(cube: bytearray, holes: bytearray) -> bytearray:
    out = bytearray(len(cube))
    for index in range(0, len(cube), 4):
        alpha = cube[index + 3] * (255 - holes[index + 3]) // 255
        out[index : index + 3] = b"\xff\xff\xff"
        out[index + 3] = alpha
    return out


def main() -> None:
    source = SVG.read_text()
    if 'fill="#ECEFF1"' not in source or 'transform="translate(0,-25)"' not in source:
        raise SystemExit("SVG is missing the background fill or the -25 shift")
    foreground = source.replace('<rect width="1024" height="1024" fill="#ECEFF1"/>', "", 1)
    holes = foreground
    for color in ("#42555F", "#232F37", "#34444F"):
        holes = holes.replace(f'fill="{color}"', 'fill="none"')
    for color in ("#E1E8ED", "#CCD6DD", "#99AAB5", "#445963", "#DCE4E9"):
        holes = holes.replace(f'stroke="{color}"', 'stroke="#FFFFFF"')
    cube = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
  <g transform="translate(0,-25)">
    <path d="M512,262 754.5,402 754.5,672 512,812 269.5,672 269.5,402Z" fill="#FFFFFF"/>
  </g>
</svg>
"""
    scratch = Path("/tmp/wondersing-icon")
    scratch.mkdir(exist_ok=True)

    for bucket, size in FOREGROUND.items():
        _w, _h, pixels = render(foreground, size, scratch / f"fg-{size}.png")
        write_png(RES / bucket / "ic_launcher_foreground.png", size, size, pixels)
        _w, _h, cube_px = render(cube, size, scratch / f"cube-{size}.png")
        _w, _h, hole_px = render(holes, size, scratch / f"holes-{size}.png")
        write_png(RES / bucket / "ic_launcher_monochrome.png", size, size, knock_out(cube_px, hole_px))

    for bucket, size in LEGACY.items():
        _w, _h, pixels = render(source, size, scratch / f"legacy-{size}.png")
        write_png(RES / bucket / "ic_launcher.png", size, size, pixels, drop_alpha=True)
        write_png(RES / bucket / "ic_launcher_round.png", size, size, circle_mask(size, size, pixels))

    _w, _h, play = render(source, 512, scratch / "play-512.png")
    for path in PLAY:
        write_png(path, 512, 512, play, drop_alpha=True)
    print("exported A11 launcher bitmaps")


if __name__ == "__main__":
    main()
