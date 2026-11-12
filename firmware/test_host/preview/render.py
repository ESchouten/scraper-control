"""Render the M5Stack screens to PNG on a computer (no device needed), with the firmware's own
LVGL code, fonts and lv_conf.h: what the M5 shows, pixel for pixel.

  python3 firmware/test_host/preview/render.py [out_dir] [--scale N]

Needs a C/C++ compiler and git (LVGL is fetched once if PlatformIO has not got it). No Python
packages: the PNGs are written with zlib.
"""
import os
import struct
import subprocess
import sys
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from driver import core_sources, host_flags  # noqa: E402
import lvgl_host  # noqa: E402

FW = os.path.dirname(os.path.dirname(HERE))
ROOT = os.path.dirname(FW)
W, H = 320, 240


def png(path, rgb, w, h):
    raw = b"".join(b"\0" + rgb[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def to_png(raw_path, png_path, scale):
    data = open(raw_path, "rb").read()
    rgb = bytearray()
    for y in range(H):
        row = bytearray()
        for x in range(W):
            v = data[(y * W + x) * 2] | data[(y * W + x) * 2 + 1] << 8
            row += bytes((((v >> 11) & 31) * 255 // 31, ((v >> 5) & 63) * 255 // 63, (v & 31) * 255 // 31)) * scale
        rgb += bytes(row) * scale
    png(png_path, bytes(rgb), W * scale, H * scale)


def main():
    args = sys.argv[1:]
    scale = 1
    if "--scale" in args:
        i = args.index("--scale")
        scale = int(args[i + 1])
        del args[i:i + 2]
    out_dir = os.path.abspath(args[0] if args else os.path.join(ROOT, "build", "m5-preview"))
    os.makedirs(out_dir, exist_ok=True)
    tmp = tempfile.mkdtemp()
    objs = lvgl_host.objects()
    exe = os.path.join(tmp, "preview")
    subprocess.check_call(["c++", "-std=c++17", "-w", "-I", os.path.join(FW, "test_host", "stubs"),
                           *lvgl_host.flags(), *host_flags(), os.path.join(HERE, "preview.cpp"), *core_sources(),
                           *objs, *lvgl_host.link_flags(), "-o", exe])
    for raw in subprocess.check_output([exe, tmp], text=True).split():
        name = os.path.splitext(os.path.basename(raw))[0]
        to_png(raw, os.path.join(out_dir, name + ".png"), scale)
        print(os.path.join(out_dir, name + ".png"))


if __name__ == "__main__":
    main()
