"""Render the M5Stack screens to PNG on a PC (no device needed).

Builds preview.cpp (which includes ../../src/main.cpp), runs it and paints its display
calls with GNU FreeSans, the font the M5GFX FreeSans fonts were made from.
  python3 firmware/test_host/preview/render.py [out_dir]
"""
import os, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from driver import core_sources, host_flags  # noqa: E402
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FW = os.path.dirname(os.path.dirname(HERE))
ROOT = os.path.dirname(FW)
FF = "/usr/share/fonts/truetype/freefont/"
# id: (file, points); Adafruit fontconvert renders at 141 dpi
FONTS = {0: ("FreeSans.ttf", 9), 1: ("FreeSansBold.ttf", 9), 2: ("FreeSansBold.ttf", 12), 3: ("FreeSansBold.ttf", 18)}
SCALE = 2


def font(i, scale=1):
    f, pt = FONTS[i]
    return ImageFont.truetype(FF + f, round(pt * 141 / 72 * scale))


def metrics(path):
    chars = [chr(c) for c in range(32, 127)]
    with open(path, "w") as out:
        out.write("static const int kAdvance[4][95] = {\n")
        for i in FONTS:
            f = font(i)
            out.write("  {" + ",".join(str(round(f.getlength(c))) for c in chars) + "},\n")
        out.write("};\nstatic const int kHeight[4] = {")
        out.write(",".join(str(ascent(i) + font(i).getbbox("gjpqy", anchor="ls")[3]) for i in FONTS) + "};\n")


def ascent(i, scale=1):
    return -min(font(i, scale).getbbox(chr(c), anchor="ls")[1] for c in range(33, 127))


def rgb(c):
    c = int(c)
    return ((c >> 11) * 255 // 31, ((c >> 5) & 63) * 255 // 63, (c & 31) * 255 // 31)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "m5-preview")
    os.makedirs(out_dir, exist_ok=True)
    tmp = tempfile.mkdtemp()
    metrics(os.path.join(tmp, "metrics.h"))
    cfg = os.path.join(tmp, "config.h")
    real = os.path.join(FW, "include", "config.h")
    src = real if os.path.exists(real) else os.path.join(FW, "include", "config.example.h")
    with open(src) as a, open(cfg, "w") as b:
        b.write(a.read())
    exe = os.path.join(tmp, "preview")
    subprocess.check_call(["g++", "-std=c++17", "-w", "-I", tmp, "-I", os.path.join(FW, "test_host", "stubs"),
                           *host_flags(), os.path.join(HERE, "preview.cpp"), *core_sources(),
                           "-no-pie", "-Wl,--unresolved-symbols=ignore-all", "-o", exe])
    ops = subprocess.check_output([exe], text=True).splitlines()
    S = SCALE
    img = Image.new("RGB", (320 * S, 240 * S))
    d = ImageDraw.Draw(img)
    for line in ops:
        k, *a = line.split(" ", 5 if line.startswith("text") else 99)
        if k == "shot":
            img.save(os.path.join(out_dir, a[0] + ".png"))
            print(os.path.join(out_dir, a[0] + ".png"))
            continue
        if k == "text":
            f, c, x, y, s = int(a[0]), a[1], int(a[2]), int(a[3]), a[4] if len(a) > 4 else ""
            d.text((x * S, (y + ascent(f)) * S), s, font=font(f, S), fill=rgb(c), anchor="ls")
            continue
        n = [int(v) for v in a]
        if k in ("rect", "frame"):
            x, y, w, h, r, c = n
            box = [x * S, y * S, (x + w) * S - 1, (y + h) * S - 1]
            if k == "rect":
                d.rounded_rectangle(box, r * S, fill=rgb(c))
            else:
                d.rounded_rectangle(box, r * S, outline=rgb(c), width=S)
        elif k == "disc":
            x, y, r, c = n
            d.ellipse([(x - r) * S, (y - r) * S, (x + r) * S, (y + r) * S], fill=rgb(c))
        elif k == "ring":
            x, y, r, c = n
            d.ellipse([(x - r) * S, (y - r) * S, (x + r) * S, (y + r) * S], outline=rgb(c), width=S)
        elif k == "tri":
            d.polygon([(n[0] * S, n[1] * S), (n[2] * S, n[3] * S), (n[4] * S, n[5] * S)], fill=rgb(n[6]))
        elif k == "line":
            d.line([(n[0] * S, n[1] * S), (n[2] * S, n[3] * S)], fill=rgb(n[4]), width=S)


if __name__ == "__main__":
    main()
