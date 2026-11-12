"""LVGL on a computer, for the firmware's tests and screen previews: the same version as the
firmware (platformio.ini), with the firmware's lv_conf.h and fonts.

Where the source comes from, first found: $LVGL_DIR; PlatformIO's copy (firmware/.pio, after a
firmware build); else build/lvgl-<version>, cloned once from GitHub.
"""
import concurrent.futures
import glob
import hashlib
import json
import os
import re
import subprocess
import sys

FW = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(FW)


def version():
    with open(os.path.join(FW, "platformio.ini")) as f:
        return re.search(r"lvgl/lvgl@([\d.]+)", f.read()).group(1)


def lvgl_dir():
    want = version()
    if os.environ.get("LVGL_DIR"):
        return os.environ["LVGL_DIR"]
    for d in glob.glob(os.path.join(FW, ".pio", "libdeps", "*", "lvgl")):
        try:
            with open(os.path.join(d, "library.json")) as f:
                if json.load(f).get("version") == want:
                    return d
        except (OSError, ValueError):
            pass
    d = os.path.join(ROOT, "build", "lvgl-" + want)
    if not os.path.exists(os.path.join(d, "lvgl.h")):
        subprocess.check_call(["git", "clone", "-q", "--depth", "1", "--branch", "v" + want,
                               "https://github.com/lvgl/lvgl.git", d])
    return d


def flags():
    """Compiler flags for code that includes lvgl.h (as in platformio.ini)."""
    return ["-DLV_CONF_INCLUDE_SIMPLE", "-DLV_LVGL_H_INCLUDE_SIMPLE",
            "-I", os.path.join(FW, "include"), "-I", lvgl_dir()]


def objects(cc="cc"):
    """LVGL and the firmware's fonts and logo, compiled once (build/lvgl-host-<hash>/)."""
    src = lvgl_dir()
    files = sorted(glob.glob(os.path.join(src, "src", "**", "*.c"), recursive=True))
    files += sorted(glob.glob(os.path.join(FW, "src", "ui", "*.c")))
    with open(os.path.join(FW, "include", "lv_conf.h"), "rb") as f:
        key = hashlib.sha1(f.read() + src.encode() + version().encode()).hexdigest()[:10]
    out = os.path.join(ROOT, "build", "lvgl-host-" + key)
    os.makedirs(out, exist_ok=True)

    def one(path):
        obj = os.path.join(out, hashlib.sha1(path.encode()).hexdigest()[:12] + ".o")
        if not os.path.exists(obj) or os.path.getmtime(obj) < os.path.getmtime(path):
            subprocess.check_call([cc, "-c", "-O1", "-w", *flags(), path, "-o", obj])
        return obj

    with concurrent.futures.ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as pool:
        return list(pool.map(one, files))


def link_flags():
    """main.cpp calls into WiFi, OTA and others that only exist on the M5: leave them unresolved
    (the preview never calls them)."""
    if sys.platform == "darwin":
        return ["-Wl,-undefined,dynamic_lookup"]
    return ["-no-pie", "-Wl,--unresolved-symbols=ignore-all"]
