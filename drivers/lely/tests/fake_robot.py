"""A small simulated Discovery panel, good enough to test the API flows offline.

Screens and texts are copied from Erik's real robot (2026-11-03 logs).
It plugs in as a transport (same interface as BleTransport). Self-contained on
purpose: it is an independent check of the core, not a copy of it.
"""
import os
import struct
import threading
import time
from dataclasses import dataclass

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def core_key() -> bytes:
    """The PIN key the core here is built with: the build secret (../../secrets.h), else the
    test key, as in core/src/LelyPin.cpp."""
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "secrets.h")
    if os.path.exists(p):
        for line in open(p):
            if line.startswith("#define LELY_APP_KEY "):
                return bytes.fromhex(line.split('"')[1].replace("\\x", ""))
    return b"ScraperTestKey!!"


APP_KEY = core_key()


def decrypt_pin(blob: bytes) -> str:
    dec = Cipher(algorithms.AES(APP_KEY), modes.CBC(bytes(16))).decryptor()
    data = dec.update(blob) + dec.finalize()
    unpad = padding.PKCS7(128).unpadder()
    return (unpad.update(data) + unpad.finalize()).decode()


def encrypt_pin(pin: str) -> bytes:
    pad = padding.PKCS7(128).padder()
    enc = Cipher(algorithms.AES(APP_KEY), modes.CBC(bytes(16))).encryptor()
    return enc.update(pad.update(pin.encode()) + pad.finalize()) + enc.finalize()


# What routes A..D consist of, as on Erik's robot on 2026-11-05 (HANDM. ROUTE).
ROUTE_PARTS = ["1  -  -", "2  3  -", "1  2  3", "4  -  -"]


@dataclass
class Msg:
    msg_id: int
    address: int
    sender: int
    payload: bytes = b""

    def to_bytes(self) -> bytes:
        return struct.pack("<HHHH", self.msg_id, self.address, self.sender, len(self.payload)) + self.payload


def parse(buf: bytearray):
    out = []
    while len(buf) >= 8:
        mid, addr, snd, n = struct.unpack_from("<HHHH", buf)
        if len(buf) < 8 + n:
            break
        out.append(Msg(mid, addr, snd, bytes(buf[8:8 + n])))
        del buf[:8 + n]
    return out


def can_records(payload: bytes):
    if len(payload) % 16 == 0:
        for o in range(0, len(payload), 16):
            yield payload[o + 4:o + 12], payload[o + 12]

KEY = {1: "back", 2: "soft2", 4: "soft1", 16: "prog", 8: "up", 32: "down", 64: "soft3", 128: "enter", 256: "start_stop"}


def can(d, ln=8):
    return struct.pack("<I", 0x3E1) + bytes(d) + bytes(8 - len(d)) + bytes([ln, 0, 0, 0])


def text(y, x, s, font=0):
    b = s.encode() + b"\0"
    out = [can([3, 30, y, x, font])]
    for i in range(0, len(b), 6):
        out.append(can([3, 31] + list(b[i:i + 6])))
    return out


# Robot texts per language. nl and en as captured from the real robot on 2026-11-05
# (tests/captures/); the English planning question was not captured (made up here). "xx" is
# nonsense of the same shape: the core must work on layout alone.
TEXTS = {
    "nl": dict(home="Vlg rit:\rRoute  :\rActie  :\rActienr:\rRitnr  :\rAccu   :", menu="HANDM. ROUTE",
               main=["Werken", "Routes", "Testen", "Instellingen", "Alarmen", "Service"],
               safety=("VEILIGHEIDWAARSCHUW.", "Handmatige bediening\ris alleen toegestaan\rmet zicht op het\rvoertuig. Bevestig?"),
               question="Tijdpad na rijden\rroute aanzetten?", stopped="ROUTE GESTOPT", abort="Route stop gezet:\rroute afmaken?", pause="Pauze", yes="Ja", no="Nee", manual="HANDBEDIENING",
               drive=("ROUTE->TIJDPAD", "Route  :\rRitnr  :\rActie  :")),
    "en": dict(home="NextRun:\rRoute  :\rAction :\rAct.nr :\rRunnr  :\rBattery:", menu="MANUAL ROUTE",
               main=["Work", "Routes", "Test", "Settings", "Alarms", "Service"],
               safety=("SAFETY WARNING", "Manual operation is\rpermitted only with a\rclear overview of the\rvehicle. Confirm?"),
               question="Timepath on after\rdriving route?", stopped="ROUTE STOPPED", abort="Route stopped:\rfinish route?", pause="Pause", yes="Yes", no="No", manual="MANUAL CONTROL",
               drive=("ROUTE->TIMEPATH", "Route  :\rRunnr  :\rAction :")),
    "xx": dict(home="Qzx wv:\rKlopf  :\rMurx   :\rMurxnr :\rFrp    :\rZzzt   :", menu="QQQQQ QQQQQ",
               main=["Aaaa", "Bbbbbb", "Ccccc", "Ddddddddd", "Eeeeee", "Ffffff"],
               safety=("XXXXXX XXXXXXX", "Blah blah blah\rblah blah blah\rblah blah\rblah. Blah?"),
               question="Blah blah blah\rblah blah?", stopped="QQQQQ QQQQQQ", abort="Blah blah blah:\rblah blah?", pause="Pff", yes="Oui", no="Non", manual="ZZZZZZZZZZ",
               drive=("QQQQ->QQQQ", "Klopf  :\rFrp    :\rMurx   :")),
}


class FakeRobot:
    def __init__(self, address=None, on_data=None, *, planning=True, mode="home", version=b"DIS0018T4", slow=False, lang="nl", stop_asks=True):
        self.on, self.planning, self.mode, self.sel = on_data, planning, mode, 0
        self.version, self.pressed, self.answer, self.images_sent = version, [], None, 0
        self._images_announced = False
        self.ignored = []          # keys that came while the robot was still redrawing (slow mode)
        # manual drive (HANDBEDIENING, seen 2026-11-05): soft keys toggle, Start held = drive
        self.left = self.right = False
        self.forward = True
        self.stopped = False       # a running route was stopped with Start/Stop
        self.stop_asks = stop_asks # Stop during a route asks "route afmaken?" (seen 2026-11-11)
        self.drive_events = []     # Start/Stop events in HANDBEDIENING: 0 down, 1 repeat, 7 up
        self.t = TEXTS[lang]
        self.main_sel = 3          # main menu (Instellingen highlighted, as seen): Werken, Routes, Testen, Instellingen, Alarmen, Service
        self._buf = bytearray()
        # slow=True behaves like the real robot over BLE (seen 2026-11-05): a redraw arrives in
        # pieces over ~0.5 s, and keys pressed before it is done are ignored.
        self.slow, self._busy_until, self._lock = slow, 0.0, threading.Lock()

    # transport interface
    async def connect(self): pass
    async def disconnect(self): pass
    is_connected = True

    def _send(self, *frames):
        self._out(Msg(1536, 244, 245, b"".join(frames)).to_bytes())

    def _out(self, data):
        with self._lock:
            try:
                self.on(data)
            except (BrokenPipeError, ValueError):   # client already gone (slow-mode thread)
                pass

    def draw(self):
        f = [can([3, 1])]
        t = self.t
        if self.mode == "home":
            f += text(3, 4, "20:21:19", 1) + text(3, 105, " ON" if self.planning else "OFF", 1)
            f += text(14, 4, t["home"])
            f += text(14, 58, "21:00:00") + text(22, 94, "01") + text(30, 48, "------------")
            f += text(38, 76, "    -") + text(46, 76, "   10") + text(54, 76, "13500")
            f += [can([3, 37, 1, 1, 11, 126, 2])]   # header bar
        elif self.mode == "mainmenu":
            for i, m in enumerate(t["main"]):
                f += text(4 + 8 * i, 0, f" {m:<15}")
            f += [can([3, 37, 4 + 8 * self.main_sel, 0, 8, 128, 3])]
        elif self.mode == "menu":
            f += text(3, 28, t["menu"], 1) + self._menu_rows()
            f += [can([3, 33, 51, 0, 12, 42, 1]), can([3, 33, 51, 85, 12, 42, 1])]
            f += text(54, 6, "Reset") + text(54, 52, "Save") + text(54, 103, ">")
            f += [can([3, 37, 16 + 8 * self.sel, 19, 8, 90, 1])]   # selection drawn last
        elif self.mode == "safety":
            f += text(3, 4, t["safety"][0], 1) + text(14, 1, t["safety"][1])
            f += [can([3, 33, 51, 0, 12, 42, 3]), can([3, 33, 51, 85, 12, 42, 1])]
            f += text(54, 15, t["yes"]) + text(54, 97, t["no"])
        elif self.mode == "manual":   # as captured 2026-11-05 (op 33 box, op 36 image, op 37 invert)
            f += text(3, 25, t["manual"], 1) + [can([3, 36, 16, 49, 13])]
            f += [can([3, 33, 51, 0, 12, 42, 1]), can([3, 33, 51, 85, 12, 42, 1])]
            f += text(54, 18, "L") + text(54, 103, "R") + [can([3, 36, 53, 58, 16 if self.forward else 15])]
            if self.left: f += [can([3, 37, 52, 1, 11, 41, 2])]
            if self.right: f += [can([3, 37, 52, 86, 11, 41, 2])]
        elif self.mode == "abort":   # after Stop during a route, as captured 2026-11-11
            f += text(3, 25, t["stopped"], 1) + text(16, 4, t["abort"])
            f += [can([3, 33, 51, 0, 12, 42, 3]), can([3, 33, 39, 42, 12, 43, 1]), can([3, 33, 42, 42, 12, 43, 3]),
                  can([3, 33, 51, 85, 12, 42, 1])]
            f += text(54, 15, t["yes"]) + text(42, 52, "HAND") + text(54, 49, t["pause"]) + text(54, 97, t["no"])
        elif self.mode == "question":
            f += text(3, 28, t["menu"], 1) + text(14, 4, t["question"])
            f += text(54, 15, t["yes"]) + text(54, 97, t["no"])
        else:
            f += text(3, 20, t["drive"][0], 1) + text(14, 4, t["drive"][1])
            f += text(14, 90, "1") + text(22, 90, "11") + text(30, 48, "WANDVOLG_DST")
        if not self.slow:
            self._send(*f)
            return
        self._busy_until = time.monotonic() + 0.6

        def pieces():                 # four pieces, 0.12 s apart
            n = (len(f) + 3) // 4
            for i in range(0, len(f), n):
                time.sleep(0.12)
                self._send(*f[i:i + n])
        threading.Thread(target=pieces, daemon=True).start()

    def _menu_rows(self):
        f = []
        for i, L in enumerate("ABCD"):
            f += text(16 + 8 * i, 19, f" {L} ->  {ROUTE_PARTS[i]} ")
        return f

    async def write(self, frame):
        self._buf.extend(frame)
        for msg in parse(self._buf):
            if msg.msg_id == 2000:
                self._out(Msg(2001, 244, 57, b"\xaf\x00\xa0\x00stal\x00").to_bytes())
            elif msg.msg_id == 2018:
                ok = bool(msg.payload) and decrypt_pin(msg.payload) == "4321"
                self._out(Msg(2020, 244, 57, bytes([ok])).to_bytes())
            elif msg.msg_id == 1536:
                for d, ln in can_records(msg.payload):
                    self._can(d, ln)

    def _can(self, d, ln):
        if d[0] == 2 and d[1] == 2:                      # connect -> ask image version
            self._send(can([5, 0, 4, 0], 2))
        elif d[0] == 5 and d[1] == 1 and d[2] == 1:      # 2nd half of our version reply
            self._got_v2 = bytes(d[3:7])
        elif d[0] == 5 and d[1] == 1 and d[2] == 0:
            self._got_v1 = bytes(d[3:8])
        elif d[0] == 5 and d[1] == 3:
            if self._images_announced:            # the ready after the image count asks for the images
                self._images_announced = False
            else:
                self.draw()
        ev, key = struct.unpack_from("<HH", d)
        if hasattr(self, "_got_v2"):
            got = self._got_v1 + self._got_v2
            del self._got_v2
            if got == self.version:
                self._send(can([5, 2, 0, 0]))
            else:
                self.images_sent = 52
                self._images_announced = True
                self._send(can([5, 2, 1, 52]), *[can([5, 6, i, i]) for i in range(52)],
                           can([5, 4, 0] + list(self.version[:5])), can([5, 4, 1] + list(self.version[5:9])), can([5, 2, 0, 0]))
        if ln == 4 and self.mode == "manual" and KEY.get(key) == "start_stop":
            self.drive_events.append(ev)
            return
        if ln != 4 or ev != 0 or key not in KEY:
            return
        k = KEY[key]
        if self.slow and time.monotonic() < self._busy_until:
            self.ignored.append(k)
            return
        self.pressed.append(k)
        if self.mode == "home":
            # the real robot only leaves the home screen while the planning is OFF
            if k == "start_stop": self.planning = not self.planning
            elif k == "enter" and not self.planning: self.mode = "menu"
            elif k == "back" and not self.planning: self.mode = "mainmenu"
        elif self.mode == "mainmenu":
            if k in ("up", "down"):   # real robot: XOR invert at the old and the new row only
                old = self.main_sel
                self.main_sel = min(5, old + 1) if k == "down" else max(0, old - 1)
                if old != self.main_sel:
                    self._send(can([3, 37, 4 + 8 * old, 0, 8, 128, 3]), can([3, 37, 4 + 8 * self.main_sel, 0, 8, 128, 3]))
                return
            elif k == "enter" and self.main_sel == 0: self.mode = "home"
        elif self.mode == "menu":
            if k in ("up", "down"):   # like Instellingen: rows redrawn, then one invert on the new row
                self.sel = min(3, self.sel + 1) if k == "down" else max(0, self.sel - 1)
                self._send(*self._menu_rows(), can([3, 37, 16 + 8 * self.sel, 19, 8, 90, 1]))
                return
            elif k == "back": self.mode = "home"
            elif k == "start_stop": self.mode = "question"
            elif k == "enter": self.mode = "safety"
        elif self.mode == "safety":
            if k == "soft1": self.mode = "manual"
            elif k == "soft3": self.mode = "menu"
        elif self.mode == "manual":
            # the real robot only sends the change, not a new screen
            if k == "soft1":
                self.left = not self.left
                self._send(can([3, 37, 52, 1, 11, 41, 2]))
            elif k == "soft2":
                self.forward = not self.forward
                self._send(can([3, 36, 53, 58, 16 if self.forward else 15]))
            elif k == "soft3":
                self.right = not self.right
                self._send(can([3, 37, 52, 86, 11, 41, 2]))
            elif k == "back":
                self.mode = "menu"
            if k != "back":
                return
        elif self.mode == "question" and k in ("soft1", "soft3"):
            self.answer = "ja" if k == "soft1" else "nee"
            self.mode = "driving"
        elif self.mode == "driving" and k == "back":
            self.mode = "STOPPED-BY-ESC"
        elif self.mode == "driving" and k == "start_stop":
            # Stop during a route, as on the real robot (2026-11-11): it stops at once and asks
            # "Route gestopt. Route afmaken? Ja / Pauze / Nee" (stop_asks).
            self.stopped = True
            if self.stop_asks:
                self.mode = "abort"
            else:
                self.mode, self.planning = "home", False
        elif self.mode == "abort" and k == "soft1":    # Ja = finish the route: drives on
            self.mode, self.stopped = "driving", False
        elif self.mode == "abort" and k == "soft2":    # Pauze (assumed: middle)
            self.mode = "paused"
        elif self.mode == "abort" and k == "soft3":    # Nee: route over, planning off (seen)
            self.mode, self.planning = "home", False
        self.draw()
