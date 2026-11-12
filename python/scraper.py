#!/usr/bin/env python3
"""Command line for the robot, on top of the robot driver.

    python scraper.py --scan                        # which robots are there?
    python scraper.py --pin 1234                    # connect, show status and screen (presses nothing)
    python scraper.py --pin 1234 --route A --dry-run    # everything but Start
    python scraper.py --pin 1234 --route A          # the robot really drives
    python scraper.py --pin 1234 --planning on
    python scraper.py --pin 1234 -i                 # interactive: press panel keys

The robot is found automatically (by the driver's address or service, or by name) and remembered;
--address / --name override that. macOS: run from Terminal.app.
"""
from __future__ import annotations

import argparse
import asyncio
import sys

from scraper_control import KEYS, Robot, RobotError, find_robots

SHORT = {"u": "up", "d": "down", "e": "enter", "s": "start_stop", "b": "back", "p": "prog",
         "1": "soft1", "2": "soft2", "3": "soft3"}


def show(robot: Robot) -> None:
    st = robot.status()
    planning = {True: "ON", False: "OFF", None: "?"}[st.planning]
    battery = f"{st.battery_mv / 1000:.1f} V" if st.battery_mv else "-"
    print(f"Schedule: {planning}   Next run: {st.next_ride or '-'}   Route: {st.route or '-'}   Battery: {battery}")
    print("--- screen ---")
    print(st.screen or "(no screen text)")


async def interactive(robot: Robot) -> None:
    print("Keys: " + "  ".join(f"{k}={v}" for k, v in SHORT.items()) + "  (Enter = screen, q = quit)")
    while True:
        line = (await asyncio.to_thread(sys.stdin.readline)).strip().lower()
        if line in ("q", "quit"):
            return
        if line:
            key = SHORT.get(line, line)
            if key not in KEYS:
                print("unknown key"); continue
            await robot.press(key)
        print(robot.status().screen, "\n")


async def main(a) -> int:
    if a.scan:
        for f in await find_robots(name=a.name, include_all=a.all):
            print(f"{f.address}  {f.name or '?':20} {f.rssi} dBm  ({f.why})")
        return 0
    async with Robot(a.pin, a.address, name=a.name) as robot:
        print(f"Connected to {robot.address}")
        try:
            if a.route:
                await robot.start_route(a.route, dry_run=a.dry_run)
                print("Dry run done, not started." if a.dry_run else f"Route {a.route.upper()} started.")
            elif a.stop:
                await robot.stop_route()
                print("Route stopped.")
            elif a.planning:
                await robot.set_planning(a.planning == "on")
            for k in a.press or []:
                await robot.press(k)
            if a.i:
                await interactive(robot)
        except RobotError as e:
            print(f"ABORTED: {e}")
            show(robot)
            return 1
        show(robot)
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Scraper Control: the robot via Bluetooth")
    ap.add_argument("--pin", help="the robot's PIN")
    ap.add_argument("--address", help="address (macOS: UUID); default: search")
    ap.add_argument("--name", help="robot name, when there are several")
    ap.add_argument("--scan", action="store_true", help="only search")
    ap.add_argument("--all", action="store_true", help="with --scan: every BLE device")
    ap.add_argument("--route", choices=list("ABCDabcd"), help="start a stored route")
    ap.add_argument("--dry-run", action="store_true", help="with --route: everything but Start")
    ap.add_argument("--planning", choices=["on", "off"], help="schedule on/off")
    ap.add_argument("--stop", action="store_true", help="stop the running route")
    ap.add_argument("--press", action="append", choices=sorted(KEYS), help="panel key (repeatable)")
    ap.add_argument("-i", action="store_true", help="interactive")
    sys.exit(asyncio.run(main(ap.parse_args())))
