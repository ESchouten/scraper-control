"""The simulated robot over stdin/stdout, for tests in other languages (the Kotlin tests and the desktop simulator).

    python3 drivers/lely/tests/fake_robot_server.py [--mode home|menu|driving] [--planning on|off] [--version HEX] [--lang nl|en]

stdin:  u16-LE length + bytes   (what the client writes to RX)
stdout: u16-LE length + bytes   (notifications from TX)
When stdin closes, prints one JSON line to stderr with what happened
(pressed keys, mode, answer, images_sent), so the test can check it.
"""
import argparse
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fake_robot import FakeRobot  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="home")
    ap.add_argument("--planning", default="on")
    ap.add_argument("--version", default=b"DIS0018T4".hex())
    ap.add_argument("--lang", default="nl")
    a = ap.parse_args()
    out = sys.stdout.buffer

    def send(data):
        out.write(struct.pack("<H", len(data)) + data)
        out.flush()

    robot = FakeRobot(None, send, planning=a.planning == "on", mode=a.mode, version=bytes.fromhex(a.version), lang=a.lang)
    inp = sys.stdin.buffer
    while True:
        hdr = inp.read(2)
        if len(hdr) < 2:
            break
        data = inp.read(struct.unpack("<H", hdr)[0])
        coro = robot.write(data)
        try:
            coro.send(None)
        except StopIteration:
            pass
    sys.stderr.write(json.dumps({"pressed": robot.pressed, "mode": robot.mode, "answer": robot.answer,
                                 "planning": robot.planning, "sel": robot.sel,
                                 "images_sent": robot.images_sent}) + "\n")


if __name__ == "__main__":
    main()
