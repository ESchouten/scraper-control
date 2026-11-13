"""The Lely core (C++) against the simulated robot, through host_main.cpp, and real screens
from the robot through replay.cpp.
Run: python3 drivers/lely/tests/test_core.py"""
import os, struct, subprocess, sys, tempfile, time

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


if __name__ == "__main__":
    build()
    r, e = drive({}, cmd=("status",))
    check("status", "STATUS planning=1 next=21:00:00 route=01 ride=10 batt=13500 busy=0" in e, e)
    check("image version learned", "VERSION 444953303031385434 changed=1" in e and r.images_sent == 52, e)
    r, e = drive({}, version="444953303031385434", cmd=("status",))
    check("image version skips download", r.images_sent == 0 and "changed=0" in e, e)
    r, e = drive({}, pin="0000")
    check("wrong pin", "RESULT pincode geweigerd" in e, e)
    r, e = drive({"planning": True}, cmd=("route", "C"))
    check("route C, planning on -> Ja", r.mode == "driving" and r.sel == 2 and r.answer == "ja" and "RESULT ok" in e, e)
    r, e = drive({"planning": False}, cmd=("route", "B"))
    check("route B, planning off -> Nee", r.mode == "driving" and r.sel == 1 and r.answer == "nee", e)
    r, e = drive({"planning": True}, cmd=("dry", "D"))
    check("dry run restores planning", r.mode == "home" and r.planning and r.answer is None and "RESULT ok" in e, e)
    r, e = drive({"mode": "driving"}, cmd=("route", "A"))
    check("busy: nothing pressed", r.pressed == [] and "RESULT robot is bezig" in e, e)
    r, e = drive({"mode": "driving"}, cmd=("button", "A"))
    check("route button while driving: Start/Stop only; 'route afmaken?' (Ja = drive on) is never answered",
          r.pressed == ["start_stop"] and r.stopped and r.mode == "abort" and "RESULT ok" in e, e)
    r, e = drive({"mode": "driving", "stop_asks": False}, cmd=("button", "A"))
    check("route button while driving, no question: Start/Stop once",
          r.pressed == ["start_stop"] and r.stopped and r.mode == "home" and "RESULT ok" in e, e)
    r, e = drive({"mode": "driving", "slow": True}, cmd=("button", "A"))
    check("slow robot: route button while driving stops it", r.stopped and r.mode == "abort" and "soft1" not in r.pressed and "RESULT ok" in e, e)
    # The user's answer to "route afmaken?" (Erik 2026-11-11), passed on 1:1.
    r, e = drive({"mode": "driving", "planning": True}, cmd=("stop-answer", "c"))
    check("stop, then 'niet afmaken': Nee, planning stays off", r.pressed == ["start_stop", "soft3"] and r.mode == "home"
          and not r.planning and "STOPQ 1" in e and "RESULT ok" in e, e)
    r, e = drive({"mode": "driving"}, cmd=("stop-answer", "f"))
    check("stop, then 'afmaken': Ja, drives on", r.pressed == ["start_stop", "soft1"] and r.mode == "driving" and "RESULT ok" in e, e)
    r, e = drive({"mode": "abort"}, cmd=("status",))
    check("status on the stop question: stopq=1, busy", "stopq=1" in e and "busy=1" in e, e)
    r, e = drive({"mode": "abort"}, cmd=("answer", "c"))
    check("connect onto the stop question: answer passed on", r.pressed == ["soft3"] and r.mode == "home" and not r.planning, e)
    for mode in ("home", "driving", "question", "manual"):
        r, e = drive({"planning": False, "mode": mode}, cmd=("answer", "f"))
        check(f"answer on {mode}: nothing pressed", "soft1" not in r.pressed and "RESULT robot is bezig" in e, e)
    r, e = drive({"planning": True}, cmd=("button", "C"))
    check("route button on the home screen: starts the route", r.mode == "driving" and r.sel == 2 and not r.stopped, e)
    for mode in ("home", "menu", "mainmenu", "manual", "question"):
        r, e = drive({"planning": False, "mode": mode}, cmd=("stop",))
        # finish() may still go home from a menu afterwards, but Start/Stop is never pressed
        check(f"stop on {mode}: Start/Stop not pressed", "start_stop" not in r.pressed and "RESULT robot is bezig" in e, e)
    r, e = drive({"planning": True}, cmd=("toggle",))
    check("hold left: planning on -> off", r.mode == "home" and not r.planning and "RESULT ok" in e, e)
    r, e = drive({"planning": False}, cmd=("toggle",))
    check("hold left: planning off -> on", r.mode == "home" and r.planning and "RESULT ok" in e, e)
    r, e = drive({"mode": "driving"}, cmd=("toggle",))
    check("hold left while driving: nothing pressed", r.pressed == [] and "RESULT robot is bezig" in e, e)
    # Like the real robot (2026-11-05): redraws arrive in pieces, keys during a redraw are ignored,
    # and the home screen keeps rewriting its clock and battery voltage.
    t0 = time.monotonic()
    r, e = drive({"planning": True, "slow": True}, cmd=("route", "B"))
    took = time.monotonic() - t0
    check("slow robot: route B, planning on -> Ja", r.mode == "driving" and r.sel == 1 and r.answer == "ja" and not r.ignored, e)
    # 6 redraws of ~0.5 s plus 0.4 s quiet each; the clock and battery must not hold it up (was 12 s).
    check(f"slow robot: route B started in {took:.1f} s (< 8 s)", took < 8, e)
    r, e = drive({"planning": False, "slow": True}, cmd=("route", "C"))
    check("slow robot: route C, planning off -> Nee", r.mode == "driving" and r.sel == 2 and r.answer == "nee" and not r.ignored, e)
    r, e = drive({"planning": True, "slow": True}, cmd=("toggle",))
    check("slow robot: hold left, planning on -> off", r.mode == "home" and not r.planning and "RESULT ok" in e, e)
    r, e = drive({"slow": True}, cmd=("status",))
    check("slow robot: status complete", "planning=1 next=21:00:00" in e and "batt=13500" in e, e)
    # Manual drive (HANDBEDIENING): planning off, Enter, Enter, Ja; Start held = drive.
    for slow in (False, True):
        r, e = drive({"planning": True, "slow": slow}, cmd=("manual",))
        ev = r.drive_events
        check(f"manual{' (slow)' if slow else ''}: L toggled, drove, back home, planning on again",
              not r.left and r.right and not r.forward and ev[0] == 0 and ev[-1] == 7 and ev.count(1) >= 3 and r.mode == "home"
              and r.planning and "INMANUAL 1" in e and "RESULT ok" in e, e)
        check(f"manual{' (slow)' if slow else ''}: state read from the screen",
              "MANUAL start left=0 right=0 forward=1" in e and "MANUAL L+dir left=1 right=0 forward=0" in e
              and "MANUAL R, L again left=0 right=1 forward=0" in e, e)
    r, e = drive({"planning": False}, cmd=("manual",))
    check("manual with planning off: stays off afterwards", r.mode == "home" and not r.planning and "RESULT ok" in e, e)
    r, e = drive({"planning": False}, cmd=("manual-drop",))
    check("manual: disconnect while driving still sends up", r.drive_events[0] == 0 and r.drive_events[-1] == 7, e)
    r, e = drive({"mode": "driving"}, cmd=("manual",))
    check("manual while driving a route: nothing pressed", r.pressed == [] and "RESULT robot is bezig" in e, e)
    r, e = drive({"planning": False, "mode": "mainmenu", "slow": True}, cmd=("route", "A"))
    check("route from the main menu: started", r.mode == "driving" and r.sel == 0 and "RESULT ok" in e, e)
    # Leaving: the robot as found (home screen, planning as at connect), except on purpose.
    r, e = drive({"planning": True}, cmd=("keys", "se"))
    check("panel left in the route menu: back home, planning on again", r.mode == "home" and r.planning, e)
    r, e = drive({"planning": True}, cmd=("keys", "sb"))
    check("panel left in the main menu: back home, planning on again", r.mode == "home" and r.planning, e)
    r, e = drive({"planning": False}, cmd=("keys", "s"))
    check("panel switched planning on: off again as found", r.mode == "home" and not r.planning, e)
    r, e = drive({"planning": True}, cmd=("toggle",))
    check("hold left (on purpose): planning stays off", not r.planning and "RESULT ok" in e, e)
    for planning in (True, False):
        r, e = drive({"planning": planning}, cmd=("routes",))
        check(f"read routes (planning {'on' if planning else 'off'}): contents, back home as before",
              "ROUTES A=1|B=2 3|C=1 2 3|D=4" in e and r.mode == "home" and r.planning == planning and r.sel == 0
              and not {"soft1", "soft3", "down"} & set(r.pressed) and r.answer is None and "OWED -1" in e, e)
    r, e = drive({"mode": "driving"}, cmd=("routes",))
    check("read routes while driving: nothing pressed", r.pressed == [] and "RESULT robot is bezig" in e, e)
    # A dropped link: the host keeps what the robot is owed and the next session pays it back.
    r, e = drive({"planning": True}, cmd=("keys-drop", "se"))
    check("link drops in the route menu: planning on is owed", r.mode == "menu" and "OWED 1" in e, e)
    r, e = drive({"planning": True}, cmd=("keys", "se"))
    check("clean finish: nothing owed", r.mode == "home" and r.planning and "OWED -1" in e, e)
    r, e = drive({"planning": False, "mode": "menu"}, cmd=("nofinish",), owed=1)
    check("next session pays back: route menu -> home, planning on", r.mode == "home" and r.planning and "OWED -1" in e, e)
    r, e = drive({"planning": False, "mode": "manual"}, cmd=("nofinish",), owed=1)
    check("next session pays back: HANDBEDIENING -> home, planning on", r.mode == "home" and r.planning and "OWED -1" in e, e)
    r, e = drive({"planning": True}, cmd=("nofinish",), owed=0)
    check("next session pays back: planning off as it was", r.mode == "home" and not r.planning and "OWED -1" in e, e)
    r, e = drive({"mode": "driving"}, cmd=("nofinish",), owed=1)
    check("robot drives: nothing pressed, still owed", r.pressed == [] and "OWED 1" in e, e)
    r, e = drive({"planning": True}, cmd=("route", "A"))
    check("route started: the robot handles the planning, nothing owed", r.mode == "driving" and "OWED -1" in e, e)
    r, e = drive({"planning": True}, cmd=("manual-drop",))
    check("link drops in manual drive: planning on is owed", "OWED 1" in e, e)
    r, e = drive({"planning": True}, cmd=("toggle",))
    check("planning switched on purpose: nothing owed", not r.planning and "OWED -1" in e, e)
    # Any robot language: screens are recognised by layout (en as captured, xx = nonsense).
    for lang in ("en", "xx"):
        r, e = drive({"lang": lang}, cmd=("status",))
        check(f"{lang}: status by position", "STATUS planning=1 next=21:00:00 route=01 ride=10 batt=13500 busy=0" in e, e)
        r, e = drive({"lang": lang, "planning": True, "slow": True}, cmd=("route", "C"))
        check(f"{lang}: route C, planning on -> yes", r.mode == "driving" and r.sel == 2 and r.answer == "ja", e)
        r, e = drive({"lang": lang, "planning": False}, cmd=("route", "B"))
        check(f"{lang}: route B, planning off -> no", r.mode == "driving" and r.sel == 1 and r.answer == "nee", e)
        r, e = drive({"lang": lang, "planning": True, "slow": True}, cmd=("manual",))
        check(f"{lang}: manual drive and back", r.mode == "home" and r.planning and r.drive_events[-1] == 7
              and "MANUAL L+dir left=1 right=0 forward=0" in e and "RESULT ok" in e, e)
        r, e = drive({"lang": lang, "planning": False, "mode": "mainmenu"}, cmd=("route", "A"))
        check(f"{lang}: route from the main menu", r.mode == "driving" and r.sel == 0, e)
        r, e = drive({"lang": lang, "mode": "driving"}, cmd=("route", "A"))
        check(f"{lang}: driving: nothing pressed", r.pressed == [] and "RESULT robot is bezig" in e, e)
        r, e = drive({"lang": lang, "mode": "driving"}, cmd=("button", "B"))
        check(f"{lang}: route button while driving stops it", r.pressed == ["start_stop"] and r.stopped and r.mode == "abort", e)
    replay()
    print("all core tests passed")
