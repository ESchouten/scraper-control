"""Build secrets: values that are compiled in but never stored in this repository.

    python3 tools/secrets.py            # writes drivers/secrets.h (git-ignored)
    python3 tools/secrets.py --release  # the same, and fails when a required one is missing

Reads $BUILD_SECRETS (NAME=value lines, or a JSON object such as the release workflow's
toJSON(secrets)), else the file ~/.scraper-control/secrets (NAME=value lines). Only the names
a driver declares in its code become a #define in drivers/secrets.h (and so end up in the
apps and the firmware); every other secret, such as the release's signing keys, is ignored:
    // build secret: NAME            optional
    // required build secret: NAME   a release (--release) fails without it
Drivers include drivers/secrets.h when it is there. Without it everything builds and the
tests pass.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HEADER = os.path.join(ROOT, "drivers", "secrets.h")


def read():
    text = os.environ.get("BUILD_SECRETS", "")
    if not text.strip():
        p = os.path.expanduser("~/.scraper-control/secrets")
        if os.path.exists(p):
            text = open(p).read()
    if text.strip().startswith("{"):
        return {k: str(v) for k, v in json.loads(text).items()}
    values = {}
    for line in text.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            name, value = line.split("=", 1)
            values[name.strip()] = value.strip()
    return values


def driver_code():
    files = [f for f in glob.glob(os.path.join(ROOT, "drivers", "*", "src", "*")) if f.endswith((".cpp", ".h"))]
    return {f: open(f, errors="ignore").read() for f in files}


def main():
    code = driver_code()
    declared = {m.group(2): bool(m.group(1)) for text in code.values()
                for m in re.finditer(r"^\s*// (required )?build secret: (\w+)\s*$", text, re.M)}
    values = {k: v for k, v in read().items() if k in declared}
    required = {k for k, req in declared.items() if req}
    if "--release" in sys.argv and required - values.keys():
        raise SystemExit("missing build secret(s): " + ", ".join(sorted(required - values.keys())))
    with open(HEADER, "w") as f:
        f.write("// Written by tools/secrets.py; not in git.\n#pragma once\n")
        for name, value in sorted(values.items()):
            f.write('#define %s "%s"\n' % (name, "".join("\\x%02x" % b for b in value.encode())))
    # build tools do not notice a header that only __has_include looked for: touch its users
    for path, text in code.items():
        if "secrets.h" in text:
            os.utime(path)
    print("wrote drivers/secrets.h (%d values)" % len(values))


if __name__ == "__main__":
    main()
