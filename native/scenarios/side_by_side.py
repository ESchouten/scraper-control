#!/usr/bin/env python3
"""Puts the screenshots of both apps next to each other, one image per language.

  python3 scenarios/side_by_side.py <compose dir> <ios dir> <out dir>

<compose dir> and <ios dir> hold nl/ and en/ with 01-welcome.png ... (the scenario tests
write them: composeApp/build/screenshots, and TEST_RUNNER_SCREENSHOT_DIR for iOS).
Needs Pillow (pip install pillow).
"""
import json
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

root = pathlib.Path(__file__).resolve().parent
shots = [s["id"] for s in json.loads((root / "scenarios.json").read_text())["screenshots"]]
compose, ios, out = (pathlib.Path(a) for a in sys.argv[1:4])
out.mkdir(parents=True, exist_ok=True)
H = 900  # height of each screenshot in the sheet
PAD = 24


def font(size):
    for f in ("/System/Library/Fonts/SFNS.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def load(p):
    if not p.exists():
        im = Image.new("RGB", (414, H), "#eeeeee")
        ImageDraw.Draw(im).text((20, H // 2), "missing", fill="#999999", font=font(28))
        return im
    im = Image.open(p).convert("RGB")
    return im.resize((round(im.width * H / im.height), H))


for lang in ("nl", "en"):
    cols = []
    for sid in shots:
        a, b = load(compose / lang / f"{sid}.png"), load(ios / lang / f"{sid}.png")
        cols.append((sid, a, b))
    w = sum(a.width + b.width + 3 * PAD for _, a, b in cols) + PAD
    sheet = Image.new("RGB", (w, H + 140), "white")
    d = ImageDraw.Draw(sheet)
    x = PAD
    for sid, a, b in cols:
        d.text((x, 20), sid, fill="#1c1b1b", font=font(30))
        d.text((x, 64), "Android / desktop (Compose)", fill="#5e5857", font=font(20))
        d.text((x + a.width + PAD, 64), "iOS (SwiftUI)", fill="#5e5857", font=font(20))
        sheet.paste(a, (x, 110))
        sheet.paste(b, (x + a.width + PAD, 110))
        x += a.width + b.width + 3 * PAD
    sheet.save(out / f"side-by-side-{lang}.png", optimize=True)
    print(out / f"side-by-side-{lang}.png")
