"""The robot API in Python. This is the only layer scripts need.

    from scraper_control import Robot

    async with Robot(pin="1234") as robot:          # finds the robot, ~2-3 s
        print(robot.status())                       # Status(planning=True, next_ride='21:00:00', ...)
        await robot.start_route("A")                # stored route A
        await robot.set_planning(True)
        await robot.press("enter")                  # raw panel key, for debugging

All robot logic (handshake, screens, route steps, safety rules) is in the robot driver
(drivers/), the same code that runs on the M5Stack and in the apps. This module only finds
the robot, opens the Bluetooth link and moves bytes.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass

from . import core
from . import discover as disc
from .transport import BleTransport

KEYS = {"back": 1, "esc": 1, "soft2": 2, "soft1": 4, "up": 8, "prog": 16, "down": 32,
        "soft3": 64, "enter": 128, "start_stop": 256}


class RobotError(RuntimeError):
    """A step did not show the expected screen; the robot was put back."""


class RobotBusy(RobotError):
    """The robot is not on the home screen or route menu (driving?); nothing pressed."""


class PinRejected(RobotError, PermissionError):
    """The robot refused the PIN."""


@dataclass
class Status:
    planning: bool | None       # time planning ON/OFF (None = not on home screen)
    clock: str
    next_ride: str
    route: str
    action: str
    action_no: str
    ride_no: str
    battery_mv: int | None
    busy: bool                  # not on home screen / route menu
    screen: str
    stop_question: bool = False  # after a stop the robot asks "finish the route?"

    @classmethod
    def from_core(cls, d: dict) -> "Status":
        p = d.get("planning", -1)
        mv = d.get("batteryMv", -1)
        return cls(planning=None if p < 0 else bool(p), clock=d.get("clock", ""),
                   next_ride=d.get("nextRide", ""), route=d.get("route", ""), action=d.get("action", ""),
                   action_no=d.get("actionNo", ""), ride_no=d.get("rideNo", ""),
                   battery_mv=None if mv < 0 else mv, busy=d.get("busy", True), screen=d.get("screen", ""),
                   stop_question=d.get("stopQuestion", False))


class Robot:
    def __init__(self, pin: str | None = None, address: str | None = None, *,
                 name: str | None = None) -> None:
        self.pin, self.address, self.name = pin, address, name
        self._link = None
        self._core: core.CoreSession | None = None
        self._writer: asyncio.Task | None = None

    # -- lifecycle ------------------------------------------------------------
    async def connect(self) -> None:
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[bytes] = asyncio.Queue()
        feed = lambda data: self._core.feed(data) if self._core else None   # noqa: E731
        await self._open_link(feed)
        self._core = core.CoreSession(lambda frame: loop.call_soon_threadsafe(queue.put_nowait, frame),
                                      self.pin, disc.load_image_version(self.address))
        self._writer = asyncio.create_task(self._write_loop(queue))
        r = await asyncio.to_thread(self._core.connect)
        self._save_version()
        if r != core.OK:
            err = self._error(r)
            await self.disconnect()
            raise err

    async def disconnect(self) -> None:
        if self._core:
            try:   # leave the robot as found: home screen, planning as at connect
                await asyncio.wait_for(asyncio.to_thread(self._core.finish), 30)
            except Exception:
                pass
            await asyncio.to_thread(self._core.close)
            self._core = None
        if self._writer:
            self._writer.cancel()
            self._writer = None
        if self._link:
            try:
                await self._link.disconnect()
            finally:
                self._link = None

    async def __aenter__(self) -> "Robot":
        await self.connect()
        return self

    async def __aexit__(self, *exc) -> None:
        await self.disconnect()

    # -- reading --------------------------------------------------------------
    def status(self) -> Status:
        return Status.from_core(self._core.status())

    # -- commands -------------------------------------------------------------
    async def start_route(self, letter: str, *, dry_run: bool = False) -> None:
        await self._run(self._core.start_route, letter, dry_run)

    async def stop_route(self) -> None:
        """Safety stop: one tap on Start/Stop, only while a route runs (RobotBusy otherwise)."""
        await self._run(self._core.stop_route)

    async def answer_stop(self, finish: bool) -> None:
        """The user's answer to "finish the route?" after a stop: True = yes (drives on), False = no."""
        await self._run(self._core.answer_stop, finish)

    async def set_planning(self, on: bool) -> None:
        await self._run(self._core.set_planning, on)

    async def press(self, key: str) -> None:
        await self._run(self._core.press, KEYS[key.lower()])

    # -- internals ------------------------------------------------------------
    async def _run(self, fn, *args) -> None:
        r = await asyncio.to_thread(fn, *args)
        if r != core.OK:
            raise self._error(r)

    def _error(self, r: int) -> Exception:
        why = f"{core.result_text(r)}: {self._core.last_error()}" if self._core else core.result_text(r)
        if r == core.BUSY:
            return RobotBusy(why)
        if r == core.PIN_REJECTED:
            return PinRejected(why)
        if r == core.CONNECT_FAILED:
            return ConnectionError(why)
        return RobotError(why)

    def _save_version(self) -> None:
        version, changed = self._core.image_version()
        if changed:
            disc.save_image_version(self.address, version)

    async def _write_loop(self, queue: asyncio.Queue) -> None:
        while True:
            frame = await queue.get()
            try:
                await self._link.write(frame)
            except Exception:
                if self._core:
                    self._core.link_down()
                return

    def _make_link(self, address: str, on_data):
        return BleTransport(address, on_data)

    async def _open_link(self, on_data) -> None:
        """Explicit address, else the remembered one, else scan (and remember)."""
        candidates = [self.address] if self.address else [a for a in [disc.load_cached()] if a]
        for addr in candidates:
            try:
                self._link = self._make_link(addr, on_data)
                await self._link.connect()
                self.address = addr
                return
            except Exception:
                if self.address:
                    raise
        addr = await disc.resolve(self.name)
        self._link = self._make_link(addr, on_data)
        await self._link.connect()
        self.address = addr
        disc.save_cached(addr)
