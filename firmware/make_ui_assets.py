"""Makes the screen assets of the buttons: the fonts and the logo, as C files for LVGL.

The output (src/ui/*.c) is committed, so a build needs none of this. Run it again only to
change a font, a size or the logo:

    npm install lv_font_conv@1.5.3 @fontsource/inter@5.1.1      # once, in any folder
    NODE_MODULES=<that folder>/node_modules python3 firmware/make_ui_assets.py

Fonts: Inter (SIL Open Font License 1.1, rsms/inter), ASCII only (the texts in Texts.h
are ASCII), plus three FontAwesome Free symbols (SIL OFL 1.1) that LVGL ships: tick,
play and warning. 4 bits per pixel (anti-aliased). The logo is drawn here with PIL.
"""
import glob
import os
import subprocess
import sys

from PIL import Image, ImageDraw

FW = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(FW, "src", "ui")
NM = os.environ.get("NODE_MODULES", "node_modules")
INTER = os.path.join(NM, "@fontsource", "inter", "files", "inter-latin-{}-normal.woff")
SYMBOLS = "0xF00C,0xF04B,0xF071"   # LV_SYMBOL_OK, LV_SYMBOL_PLAY, LV_SYMBOL_WARNING

# name: (weight, px, symbols)
FONTS = {
    "ui_font_body": (400, 16, False),
    "ui_font_bold": (600, 16, True),
    "ui_font_title": (700, 22, False),
    "ui_font_big": (700, 32, False),
}


def awesome():
    """FontAwesome as shipped with the LVGL version the firmware uses."""
    hits = glob.glob(os.path.join(FW, ".pio", "libdeps", "*", "lvgl", "scripts", "built_in_font",
                                  "FontAwesome5-Solid+Brands+Regular.woff"))
    hits += glob.glob(os.path.join(os.path.dirname(FW), "build", "lvgl-*", "scripts", "built_in_font",
                                   "FontAwesome5-Solid+Brands+Regular.woff"))
    if not hits:
        sys.exit("FontAwesome not found: build the firmware once (pio run) so LVGL is fetched")
    return hits[0]


def fonts():
    conv = os.path.join(NM, ".bin", "lv_font_conv")
    for name, (weight, px, symbols) in FONTS.items():
        cmd = [conv, "--bpp", "4", "--size", str(px), "--no-compress", "--format", "lvgl",
               "--font", INTER.format(weight), "-r", "0x20-0x7E"]
        if symbols:
            cmd += ["--font", awesome(), "-r", SYMBOLS]
        path = os.path.join(OUT, name + ".c")
        cmd += ["--lv-include", "lvgl.h", "--lv-font-name", name, "-o", path]
        subprocess.check_call(cmd)
        # the converter writes its command line into the file: keep only the file names
        with open(path) as f:
            text = f.read()
        for arg in cmd:
            if os.path.isabs(arg):
                text = text.replace(arg, os.path.basename(arg))
        with open(path, "w") as f:
            f.write(text)
        print("wrote", name)


def logo(size=30, scale=8):
    """The app's mark: white rounded tile, red ring, red play triangle (ARGB8888)."""
    S = size * scale
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    red = (0xC8, 0x10, 0x2E, 255)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 27 // 100, fill=(255, 255, 255, 255))
    r, w = S * 30 // 100, max(scale * 2, S * 7 // 100)
    c = S // 2
    d.ellipse([c - r, c - r, c + r, c + r], outline=red, width=w)
    d.polygon([(S * 43 // 100, S * 36 // 100), (S * 43 // 100, S * 64 // 100), (S * 66 // 100, c)], fill=red)
    img = img.resize((size, size), Image.LANCZOS)
    data = bytearray()
    for y in range(size):
        for x in range(size):
            r_, g, b, a = img.getpixel((x, y))
            data += bytes((b, g, r_, a))   # LVGL ARGB8888 is B, G, R, A in memory
    rows = ",\n".join("  " + ",".join("0x%02x" % v for v in data[i:i + 24]) for i in range(0, len(data), 24))
    with open(os.path.join(OUT, "ui_logo.c"), "w") as f:
        f.write(f"""// Made by firmware/make_ui_assets.py: the app's mark, {size}x{size} ARGB8888.
#include "lvgl.h"

static const uint8_t kLogo[] = {{
{rows}
}};

const lv_image_dsc_t ui_logo = {{
  .header = {{.magic = LV_IMAGE_HEADER_MAGIC, .cf = LV_COLOR_FORMAT_ARGB8888, .flags = 0,
             .w = {size}, .h = {size}, .stride = {size * 4}, .reserved_2 = 0}},
  .data_size = sizeof kLogo,
  .data = kLogo,
  .reserved = NULL,
}};
""")
    print("wrote ui_logo")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    fonts()
    logo()
