"""Signs a firmware build for the M5's own updates: writes firmware.txt next to firmware.bin.

    python3 firmware/sign_release.py firmware.bin --key key.pem --version 0.7.0 --build 412 > firmware.txt
    python3 firmware/sign_release.py --message firmware.txt     # prints what the signature covers

The key is the ECDSA P-256 release key (CI secret FIRMWARE_SIGNING_KEY); the M5 has the public
half in src/ReleaseKey.h. Same format as src/Manifest.h.
"""
import argparse
import hashlib
import os
import subprocess
import sys
import tempfile

BOARD = "m5stack-core"


def message(m):
    return f"scraper-knop|{m['board']}|{m['version']}|{m['build']}|{m['size']}|{m['sha256']}"


def parse(text):
    return dict(l.split("=", 1) for l in text.splitlines() if "=" in l)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bin", nargs="?")
    ap.add_argument("--key", help="PEM file, or the PEM text itself")
    ap.add_argument("--version")
    ap.add_argument("--build", type=int)
    ap.add_argument("--board", default=BOARD)
    ap.add_argument("--message", help="print the signed message of this manifest")
    a = ap.parse_args()
    if a.message:
        print(message(parse(open(a.message).read())))
        return
    data = open(a.bin, "rb").read()
    m = {"board": a.board, "version": a.version, "build": a.build, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    with tempfile.TemporaryDirectory() as tmp:
        key = a.key
        if "BEGIN" in key:
            key = os.path.join(tmp, "key.pem")
            with open(key, "w") as f:
                f.write(a.key)
        msg = os.path.join(tmp, "msg")
        with open(msg, "w") as f:
            f.write(message(m))
        sig = subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", key, msg])
    for k in ("board", "version", "build", "size", "sha256"):
        print(f"{k}={m[k]}")
    print(f"sig={sig.hex()}")


if __name__ == "__main__":
    sys.exit(main())
