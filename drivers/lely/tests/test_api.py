"""Offline tests: the Python API (python/, on top of this driver) against the simulated robot.
Run: python3 drivers/lely/tests/test_api.py   (builds the core library first)"""
import asyncio, os, sys, tempfile, types
from pathlib import Path

os.environ["XDG_CONFIG_HOME"] = tempfile.mkdtemp()
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "python"))
sys.path.insert(0, os.path.dirname(__file__))
try:
    import bleak  # noqa
except ImportError:
    m = types.ModuleType("bleak"); m.BleakClient = m.BleakScanner = object; sys.modules["bleak"] = m

from scraper_control import Robot, RobotBusy, api
from fake_robot import FakeRobot

from scraper_control.build_core import LIBNAME, build
os.environ["SCRAPER_CORE_LIB"] = str(build(Path(tempfile.mkdtemp()) / LIBNAME))

ADDR = "00:12:FF:A0:80:01"


def run(coro):
    return asyncio.run(coro)


def make(**kw):
    holder = {}
    def factory(address, on_data):
        holder["r"] = FakeRobot(address, on_data, **kw)
        return holder["r"]
    api.BleTransport = factory
    return holder


def test_status():
    h = make()
    async def go():
        async with Robot(pin="4321", address=ADDR) as robot:
            return robot.status()
    st = run(go())
    assert st.planning is True and st.next_ride == "21:00:00" and st.route == "01"
    assert st.ride_no == "10" and st.battery_mv == 13500 and not st.busy


def test_wrong_pin():
    make()
    async def go():
        async with Robot(pin="0000", address=ADDR):
            pass
    try:
        run(go()); assert False
    except PermissionError:
        pass


def test_route_with_planning_on_answers_ja():
    h = make(planning=True)
    async def go():
        async with Robot(pin="4321", address=ADDR) as robot:
            await robot.start_route("C")
    run(go()); r = h["r"]
    assert r.mode == "driving" and r.sel == 2 and r.answer == "ja"


def test_route_with_planning_off_answers_nee_and_keeps_off():
    h = make(planning=False)
    async def go():
        async with Robot(pin="4321", address=ADDR) as robot:
            await robot.start_route("B")
    run(go()); r = h["r"]
    assert r.mode == "driving" and r.sel == 1 and r.answer == "nee" and "start_stop" not in r.pressed[:1]


def test_dry_run_restores():
    h = make(planning=True)
    async def go():
        async with Robot(pin="4321", address=ADDR) as robot:
            await robot.start_route("D", dry_run=True)
    run(go()); r = h["r"]
    assert r.mode == "home" and r.planning is True and r.answer is None


def test_busy_presses_nothing():
    h = make(mode="driving")
    async def go():
        async with Robot(pin="4321", address=ADDR) as robot:
            await robot.start_route("A")
    try:
        run(go()); assert False
    except RobotBusy:
        pass
    assert h["r"].pressed == [] and h["r"].mode == "driving"


def test_stop_route_while_driving():
    h = make(mode="driving")
    async def go():
        async with Robot(pin="4321", address=ADDR) as robot:
            await robot.stop_route()
    run(go())
    assert h["r"].pressed == ["start_stop"] and h["r"].stopped and h["r"].mode == "abort"   # never Ja: that drives on


def test_image_version_is_remembered():
    from pathlib import Path
    from scraper_control import discover
    discover.CACHE = Path(tempfile.mkdtemp()) / "last.json"
    h = make()
    async def go():
        async with Robot(pin="4321", address=ADDR):
            pass
    run(go()); assert h["r"].images_sent == 52      # first time: robot sends images
    run(go()); assert h["r"].images_sent == 0       # second time: skipped


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn(); print("ok", name)
