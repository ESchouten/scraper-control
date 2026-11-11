"""Build the robot driver (drivers/<name>/src) with the C API into a library next to this package.

    python -m scraper_control.build_core          # needs a C++17 compiler (g++/clang++)

The driver: $SCRAPER_DRIVER (a folder name in drivers/), or the only one there.
"""
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
LIBNAME = {"darwin": "libscraper_core.dylib", "win32": "scraper_core.dll"}.get(sys.platform, "libscraper_core.so")


def driver_src() -> Path:
    name = os.environ.get("SCRAPER_DRIVER")
    found = [d for d in sorted((ROOT / "drivers").glob("*/src")) if not name or d.parent.name == name]
    if len(found) != 1:
        raise FileNotFoundError("set SCRAPER_DRIVER to one of: " + ", ".join(d.parent.name for d in found))
    return found[0]


def sources() -> list:
    """Everything the library is built from (to rebuild when one of them changed)."""
    files = list(driver_src().glob("*.cpp")) + list(driver_src().glob("*.h")) + [ROOT / "core" / "scraper_c.h"]
    return files + [p for p in [ROOT / "drivers" / "secrets.h"] if p.exists()]


def build(out: Path = HERE / LIBNAME) -> Path:
    src = driver_src()
    cxx = "clang++" if sys.platform == "darwin" else "g++"
    flags = ["-std=c++17", "-O2", "-fPIC", "-fvisibility=hidden", "-pthread"]
    flags += ["-dynamiclib"] if sys.platform == "darwin" else ["-shared"]
    srcs = sorted(str(p) for p in src.glob("*.cpp"))
    subprocess.check_call([cxx, *flags, "-I", str(src), "-I", str(ROOT / "core"), *srcs, "-o", str(out)])
    return out


if __name__ == "__main__":
    print(build())
