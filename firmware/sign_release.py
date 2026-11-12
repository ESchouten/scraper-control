"""Signs a firmware build for the devices' own updates: prints its block for update.ini.

    python3 firmware/sign_release.py out/scraper-control-m5stack-core-1.6.412.bin --key key.pem \\
        --version 1.6.412 --build 412 >> out/update.ini
    python3 firmware/sign_release.py --message out/update.ini     # prints what the signatures cover

update.ini holds one block per board ([m5stack-core], ...) and is the only file with a fixed
name; each block names its firmware file.

The key is the ECDSA P-256 release key (CI secret FIRMWARE_SIGNING_KEY); the devices have the
public half in src/ReleaseKey.h. Same format as src/Manifest.h.
"""
import argparse
import hashlib
import os
import subprocess
import sys
import tempfile

BOARD = "m5stack-core"
FIELDS = ("version", "build", "size", "sha256")


def message(m):
    return f"scraper-knop|{m['board']}|{m['version']}|{m['build']}|{m['size']}|{m['sha256']}"


def parse(text):
    """{board: fields} of update.ini."""
    blocks, cur = {}, None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("[") and line.endswith("]"):
            cur = blocks.setdefault(line[1:-1], {"board": line[1:-1]})
        elif "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            if cur is not None:
                cur[k] = v
    return blocks


def sign(text, key):
    with tempfile.TemporaryDirectory() as tmp:
        if "BEGIN" in key:
            path = os.path.join(tmp, "key.pem")
            with open(path, "w") as f:
                f.write(key)
            key = path
        msg = os.path.join(tmp, "msg")
        with open(msg, "w") as f:
            f.write(text)
        return subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", key, msg]).hex()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bin", nargs="?")
    ap.add_argument("--key", help="PEM file, or the PEM text itself")
    ap.add_argument("--version")
    ap.add_argument("--build", type=int)
    ap.add_argument("--board", default=BOARD)
    ap.add_argument("--message", help="print the signed message of every board in this manifest")
    a = ap.parse_args()
    if a.message:
        for m in parse(open(a.message).read()).values():
            print(message(m))
        return
    data = open(a.bin, "rb").read()
    m = {"board": a.board, "version": a.version, "build": a.build, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    sig = sign(message(m), a.key)
    print(f"[{a.board}]")
    print(f"file={os.path.basename(a.bin)}")
    for k in FIELDS:
        print(f"{k}={m[k]}")
    print(f"sig={sig}")


if __name__ == "__main__":
    sys.exit(main())
