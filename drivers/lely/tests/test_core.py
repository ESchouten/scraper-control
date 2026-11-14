"""The Lely core (C++) against the simulated robot, through host_main.cpp, and real screens
from the robot through replay.cpp.
Run: python3 drivers/lely/tests/test_core.py"""
import os, struct, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
DRIVER = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from fake_robot import FakeRobot, encrypt_pin

SRC = os.path.join(DRIVER, "src")
BIN = os.path.join(tempfile.gettempdir(), "lely_host_test")


def build():
    subprocess.check_call(["g++", "-std=c++17", "-O1", "-Wall", "-I", SRC,
                           os.path.join(SRC, "LelyProtocol.cpp"), os.path.join(SRC, "LelyRobot.cpp"), os.path.join(SRC, "LelyPin.cpp"),
                           os.path.join(HERE, "host_main.cpp"), "-o", BIN])


def drive(robot_kwargs, pin="4321", version="-", cmd=("status",), owed=None):
    pin_hex = encrypt_pin(pin).hex() if pin else "-"
    env = dict(os.environ)
    if owed is not None:
        env["LELY_OWED"] = str(owed)
    t0 = time.monotonic()
    proc = subprocess.Popen([BIN, pin_hex, version, *cmd], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)

    def to_child(data):
        proc.stdin.write(struct.pack("<H", len(data)) + data); proc.stdin.flush()

    robot = FakeRobot(None, to_child, **robot_kwargs)
    while True:
        hdr = proc.stdout.read(3)
        if len(hdr) < 3:
            break
        typ, n = hdr[:1], struct.unpack("<H", hdr[1:])[0]
        data = proc.stdout.read(n) if n else b""
        if typ == b"W":
            coro = robot.write(data)
            try:
                coro.send(None)
            except StopIteration:
                pass
    with robot._lock:   # the slow robot's clock thread stops writing to the closed pipe
        robot._live = False
        try:
            proc.stdin.close()
        except BrokenPipeError:
            pass
    err = proc.stderr.read().decode()
    proc.wait()
    robot.took = time.monotonic() - t0
    return robot, err


def check(name, cond, err):
    print(("ok   " if cond else "FAIL ") + name)
    if not cond:
        print(err); sys.exit(1)


def replay():
    """Real screens from the robot (Dutch and English, 2026-11-05) through the core's Screen."""
    exe = os.path.join(tempfile.mkdtemp(), "replay")
    subprocess.check_call(["g++", "-std=c++17", "-Wall", "-I", SRC, os.path.join(HERE, "replay.cpp"),
                           os.path.join(SRC, "LelyProtocol.cpp"), "-o", exe])
    with open(os.path.join(HERE, "captures", "2026-11-05-handbediening-en-talen.txt")) as f:
        out = subprocess.run([exe], stdin=f, capture_output=True, text=True, check=True).stdout.split("\n")
    out = [l for l in out if l]
    kinds = [l.split(" | ")[1] for l in out]
    want = (["other", "other"] + ["mainmenu"] * 4 + ["home"] * 3 + ["routemenu", "question"] + ["manual"] * 7
            + ["routemenu", "home"] + ["mainmenu"] * 4 + ["other"] * 8 + ["mainmenu"] * 4 + ["home"] * 3
            + ["routemenu", "question", "routemenu", "home"] + ["mainmenu"] * 4 + ["other"] * 5 + ["mainmenu"] * 4
            + ["home"] * 2)
    check("real screens (nl + en) recognised by layout", kinds == want, "\n".join(out))
    langs = [l.split("lang=")[1].split(" ")[0] for l in out if " | home | " in l or " | mainmenu | " in l]
    check("real screens: robot language nl, then en, then nl", langs and langs[0] == "nl" and "en" in langs
          and langs[-1] == "nl" and "" not in langs, "\n".join(out))
    manual = [l.split(" | ")[-1] for l in out if " | manual | " in l]
    check("real HANDBEDIENING: L, R and direction read", manual == [
        "L=0 R=0 fwd=1", "L=1 R=0 fwd=1", "L=0 R=0 fwd=1", "L=0 R=0 fwd=0", "L=0 R=0 fwd=1", "L=0 R=1 fwd=1",
        "L=0 R=0 fwd=1"], "\n".join(out))
    homes = [l for l in out if " | home | " in l]
    homes = [l.rsplit(" | lang=", 1)[0] for l in homes]
    check("real home screen values, nl and en", all("next=19:30:00 route=01 batt=134" in l for l in homes), "\n".join(homes))
    sel = [l.split("'")[1] for l in out if " | mainmenu | " in l]
    check("real main menu: XOR selection moves", sel[:4] == ["Instellingen", "Testen", "Routes", "Werken"]
          and "Work" in sel, "\n".join(out))


# Every case: its own simulated robot and core, so they run side by side.
CASES = []


def case(name, robot, cmd=("status",), ok=lambda r, e: True, **drive_kw):
    CASES.append((name, robot, cmd, ok, drive_kw))


case("status, image version learned", {}, ok=lambda r, e: "STATUS planning=1 next=21:00:00 route=01 ride=10 batt=13500 busy=0" in e
     and "VERSION 444953303031385434 changed=1" in e and r.images_sent == 52)
case("image version skips download", {}, version="444953303031385434", ok=lambda r, e: r.images_sent == 0 and "changed=0" in e)
case("wrong pin", {}, pin="0000", ok=lambda r, e: "RESULT pincode geweigerd" in e)
case("route C, planning on -> Ja", {"planning": True}, ("route", "C"),
     lambda r, e: r.mode == "driving" and r.sel == 2 and r.answer == "ja" and "RESULT ok" in e)
case("route B, planning off -> Nee", {"planning": False}, ("route", "B"), lambda r, e: r.mode == "driving" and r.sel == 1 and r.answer == "nee")
case("dry run restores planning", {"planning": True}, ("dry", "D"),
     lambda r, e: r.mode == "home" and r.planning and r.answer is None and "RESULT ok" in e)
case("busy: nothing pressed", {"mode": "driving"}, ("route", "A"), lambda r, e: r.pressed == [] and "RESULT robot is bezig" in e)
case("route button while driving: Start/Stop only; 'route afmaken?' (Ja = drive on) is never answered", {"mode": "driving"},
     ("button", "A"), lambda r, e: r.pressed == ["start_stop"] and r.stopped and r.mode == "abort" and "RESULT ok" in e)
case("route button while driving, no question: Start/Stop once", {"mode": "driving", "stop_asks": False}, ("button", "A"),
     lambda r, e: r.pressed == ["start_stop"] and r.stopped and r.mode == "home" and "RESULT ok" in e)
case("slow robot: route button while driving stops it", {"mode": "driving", "slow": True}, ("button", "A"),
     lambda r, e: r.stopped and r.mode == "abort" and "soft1" not in r.pressed and "RESULT ok" in e)
# The user's answer to "route afmaken?" (Erik 2026-11-11), passed on 1:1.
case("stop, then 'niet afmaken': Nee, planning stays off", {"mode": "driving", "planning": True}, ("stop-answer", "c"),
     lambda r, e: r.pressed == ["start_stop", "soft3"] and r.mode == "home" and not r.planning and "STOPQ 1" in e and "RESULT ok" in e)
case("stop, then 'afmaken': Ja, drives on", {"mode": "driving"}, ("stop-answer", "f"),
     lambda r, e: r.pressed == ["start_stop", "soft1"] and r.mode == "driving" and "RESULT ok" in e)
case("status on the stop question: stopq=1, busy", {"mode": "abort"}, ok=lambda r, e: "stopq=1" in e and "busy=1" in e)
case("connect onto the stop question: answer passed on", {"mode": "abort"}, ("answer", "c"),
     lambda r, e: r.pressed == ["soft3"] and r.mode == "home" and not r.planning)
for mode in ("home", "driving", "question", "manual"):
    case(f"answer on {mode}: nothing pressed", {"planning": False, "mode": mode}, ("answer", "f"),
         lambda r, e: "soft1" not in r.pressed and "RESULT robot is bezig" in e)
case("route button on the home screen: starts the route", {"planning": True}, ("button", "C"),
     lambda r, e: r.mode == "driving" and r.sel == 2 and not r.stopped)
for mode in ("home", "menu", "mainmenu", "manual", "question"):
    # finish() may still go home from a menu afterwards, but Start/Stop is never pressed
    case(f"stop on {mode}: Start/Stop not pressed", {"planning": False, "mode": mode}, ("stop",),
         lambda r, e: "start_stop" not in r.pressed and "RESULT robot is bezig" in e)
case("hold left: planning on -> off", {"planning": True}, ("toggle",), lambda r, e: r.mode == "home" and not r.planning and "RESULT ok" in e)
case("hold left: planning off -> on", {"planning": False}, ("toggle",), lambda r, e: r.mode == "home" and r.planning and "RESULT ok" in e)
case("hold left while driving: nothing pressed", {"mode": "driving"}, ("toggle",), lambda r, e: r.pressed == [] and "RESULT robot is bezig" in e)
# Like the real robot (2026-11-05): redraws arrive in pieces, keys during a redraw are ignored,
# and the home screen keeps rewriting its clock and battery voltage. 6 redraws of ~0.5 s, the
# next key right after each one's last piece (was 12 s with 4 s waits on the home screen,
# 5.3 s with 0.4 s after every redraw).
case("slow robot: route B, planning on -> Ja, started in < 5 s", {"planning": True, "slow": True}, ("route", "B"),
     lambda r, e: r.mode == "driving" and r.sel == 1 and r.answer == "ja" and not r.ignored and r.took < 5)
# A robot that still ignores keys for a moment after its last piece: one key is sent again,
# and from then on the core waits for a quiet screen as before.
case("slow robot that needs a moment after drawing: one key repeated, route B started", {"planning": True, "slow": True, "after": 0.12},
     ("route", "B"), lambda r, e: r.mode == "driving" and r.sel == 1 and r.answer == "ja" and len(r.ignored) == 1)
case("slow robot: route C, planning off -> Nee", {"planning": False, "slow": True}, ("route", "C"),
     lambda r, e: r.mode == "driving" and r.sel == 2 and r.answer == "nee" and not r.ignored)
case("slow robot: hold left, planning on -> off", {"planning": True, "slow": True}, ("toggle",),
     lambda r, e: r.mode == "home" and not r.planning and "RESULT ok" in e)
case("slow robot: status complete", {"slow": True}, ok=lambda r, e: "planning=1 next=21:00:00" in e and "batt=13500" in e)
# Manual drive (HANDBEDIENING): planning off, Enter, Enter, Ja; Start held = drive.
for slow in (False, True):
    case(f"manual{' (slow)' if slow else ''}: L toggled, drove, back home, planning on again; state read from the screen",
         {"planning": True, "slow": slow}, ("manual",),
         lambda r, e: not r.left and r.right and not r.forward and r.drive_events[0] == 0 and r.drive_events[-1] == 7
         and r.drive_events.count(1) >= 3 and r.mode == "home" and r.planning and "INMANUAL 1" in e and "RESULT ok" in e
         and "MANUAL start left=0 right=0 forward=1" in e and "MANUAL L+dir left=1 right=0 forward=0" in e
         and "MANUAL R, L again left=0 right=1 forward=0" in e)
case("manual with planning off: stays off afterwards", {"planning": False}, ("manual",),
     lambda r, e: r.mode == "home" and not r.planning and "RESULT ok" in e)
case("manual: disconnect while driving still sends up", {"planning": False}, ("manual-drop",),
     lambda r, e: r.drive_events[0] == 0 and r.drive_events[-1] == 7)
case("manual while driving a route: nothing pressed", {"mode": "driving"}, ("manual",), lambda r, e: r.pressed == [] and "RESULT robot is bezig" in e)
case("robot left in manual control: route started from there", {"planning": False, "mode": "manual"}, ("route", "A"),
     lambda r, e: r.mode == "driving" and r.sel == 0 and r.answer == "nee" and "RESULT ok" in e)
case("robot left in manual control: hold left switches planning on", {"planning": False, "mode": "manual"}, ("toggle",),
     lambda r, e: r.mode == "home" and r.planning and "RESULT ok" in e)
case("robot left in manual control: routes read", {"planning": False, "mode": "manual"}, ("routes",),
     lambda r, e: r.mode == "home" and "ROUTES A=" in e and "RESULT ok" in e)
case("notice at login (route analysis): one Esc, then home and the route starts", {"planning": True, "mode": "notice"}, ("route", "C"),
     lambda r, e: r.pressed[0] == "back" and r.mode == "driving" and r.answer == "ja" and "RESULT ok" in e)
case("notice at login: status read after one Esc", {"planning": True, "mode": "notice"},
     ok=lambda r, e: r.pressed == ["back"] and "STATUS planning=1" in e)
case("no Esc on a question at login", {"planning": False, "mode": "question"}, ok=lambda r, e: "back" not in r.pressed)
case("no Esc on the stop question at login", {"mode": "abort"}, ok=lambda r, e: r.pressed == [])
case("route from the main menu: started", {"planning": False, "mode": "mainmenu", "slow": True}, ("route", "A"),
     lambda r, e: r.mode == "driving" and r.sel == 0 and "RESULT ok" in e)
# Leaving: the robot as found (home screen, planning as at connect), except on purpose.
case("panel left in the route menu: back home, planning on again", {"planning": True}, ("keys", "se"), lambda r, e: r.mode == "home" and r.planning)
case("panel left in the main menu: back home, planning on again", {"planning": True}, ("keys", "sb"), lambda r, e: r.mode == "home" and r.planning)
case("panel switched planning on: off again as found", {"planning": False}, ("keys", "s"), lambda r, e: r.mode == "home" and not r.planning)
case("hold left (on purpose): planning stays off", {"planning": True}, ("toggle",), lambda r, e: not r.planning and "RESULT ok" in e)
for planning in (True, False):
    case(f"read routes (planning {'on' if planning else 'off'}): contents, back home as before", {"planning": planning}, ("routes",),
         lambda r, e, planning=planning: "ROUTES A=1|B=2 3|C=1 2 3|D=4" in e and r.mode == "home" and r.planning == planning
         and r.sel == 0 and not {"soft1", "soft3", "down"} & set(r.pressed) and r.answer is None and "OWED -1" in e)
case("read routes while driving: nothing pressed", {"mode": "driving"}, ("routes",), lambda r, e: r.pressed == [] and "RESULT robot is bezig" in e)
# A dropped link: the host keeps what the robot is owed and the next session pays it back.
case("link drops in the route menu: planning on is owed", {"planning": True}, ("keys-drop", "se"), lambda r, e: r.mode == "menu" and "OWED 1" in e)
case("clean finish: nothing owed", {"planning": True}, ("keys", "se"), lambda r, e: r.mode == "home" and r.planning and "OWED -1" in e)
case("next session pays back: route menu -> home, planning on", {"planning": False, "mode": "menu"}, ("nofinish",), owed=1,
     ok=lambda r, e: r.mode == "home" and r.planning and "OWED -1" in e)
case("next session pays back: HANDBEDIENING -> home, planning on", {"planning": False, "mode": "manual"}, ("nofinish",), owed=1,
     ok=lambda r, e: r.mode == "home" and r.planning and "OWED -1" in e)
case("next session pays back: planning off as it was", {"planning": True}, ("nofinish",), owed=0,
     ok=lambda r, e: r.mode == "home" and not r.planning and "OWED -1" in e)
case("robot drives: nothing pressed, still owed", {"mode": "driving"}, ("nofinish",), owed=1, ok=lambda r, e: r.pressed == [] and "OWED 1" in e)
case("route started: the robot handles the planning, nothing owed", {"planning": True}, ("route", "A"),
     lambda r, e: r.mode == "driving" and "OWED -1" in e)
case("link drops in manual drive: planning on is owed", {"planning": True}, ("manual-drop",), lambda r, e: "OWED 1" in e)
case("planning switched on purpose: nothing owed", {"planning": True}, ("toggle",), lambda r, e: not r.planning and "OWED -1" in e)
# Any robot language: screens are recognised by layout (en as captured, xx = nonsense).
for lang in ("en", "xx"):
    case(f"{lang}: status by position", {"lang": lang}, ok=lambda r, e: "STATUS planning=1 next=21:00:00 route=01 ride=10 batt=13500 busy=0" in e)
    case(f"{lang}: route C, planning on -> yes", {"lang": lang, "planning": True, "slow": True}, ("route", "C"),
         lambda r, e: r.mode == "driving" and r.sel == 2 and r.answer == "ja")
    case(f"{lang}: route B, planning off -> no", {"lang": lang, "planning": False}, ("route", "B"),
         lambda r, e: r.mode == "driving" and r.sel == 1 and r.answer == "nee")
    case(f"{lang}: manual drive and back", {"lang": lang, "planning": True, "slow": True}, ("manual",),
         lambda r, e: r.mode == "home" and r.planning and r.drive_events[-1] == 7 and "MANUAL L+dir left=1 right=0 forward=0" in e and "RESULT ok" in e)
    case(f"{lang}: route from the main menu", {"lang": lang, "planning": False, "mode": "mainmenu"}, ("route", "A"),
         lambda r, e: r.mode == "driving" and r.sel == 0)
    case(f"{lang}: driving: nothing pressed", {"lang": lang, "mode": "driving"}, ("route", "A"), lambda r, e: r.pressed == [] and "RESULT robot is bezig" in e)
    case(f"{lang}: route button while driving stops it", {"lang": lang, "mode": "driving"}, ("button", "B"),
         lambda r, e: r.pressed == ["start_stop"] and r.stopped and r.mode == "abort")


if __name__ == "__main__":
    build()
    with ThreadPoolExecutor(8) as pool:   # the cases wait on the robot's timing, not on the CPU
        runs = list(pool.map(lambda c: drive(c[1], cmd=c[2], **c[4]), CASES))
    for (name, _, _, ok, _), (r, e) in zip(CASES, runs):
        check(name, ok(r, e), e)
    replay()
    print("all core tests passed")
