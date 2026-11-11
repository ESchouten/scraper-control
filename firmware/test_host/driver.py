"""Where the buttons' robot driver is, for builds on a computer: read from platformio.ini.

A driver folder has src/ with its code and ScraperDriver.h (what the buttons use of it).
"""
import glob
import os
import re

FW = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def driver_dir():
    with open(os.path.join(FW, "platformio.ini")) as f:
        m = re.search(r"symlink://\.\./(drivers/[^/\s]+)", f.read())
    return os.path.join(os.path.dirname(FW), m.group(1))


def host_flags():
    """Include path for main.cpp on a computer: the driver's code."""
    return ["-I", os.path.join(driver_dir(), "src")]


def core_sources():
    """The driver's robot API (without its C interface, which the buttons do not use)."""
    return sorted(p for p in glob.glob(os.path.join(driver_dir(), "src", "*.cpp")) if not p.endswith("scraper_c.cpp"))
