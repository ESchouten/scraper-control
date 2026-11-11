#!/usr/bin/env python3
"""Command line for the robot, on top of the robot driver.

    python cli.py --scan                        # welke robots zijn er?
    python cli.py --pin 1234                    # verbinden, status en scherm tonen (drukt niets)
    python cli.py --pin 1234 --route A --dry-run    # alles behalve Start
    python cli.py --pin 1234 --route A          # robot gaat echt rijden
    python cli.py --pin 1234 --planning on
    python cli.py --pin 1234 -i                 # interactief: paneelknoppen drukken

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
    planning = {True: "AAN", False: "UIT", None: "?"}[st.planning]
    accu = f"{st.battery_mv / 1000:.1f} V" if st.battery_mv else "-"
    print(f"Planning: {planning}   Volgende rit: {st.next_ride or '-'}   Route: {st.route or '-'}   Accu: {accu}")
    print("--- scherm ---")
    print(st.screen or "(geen schermtekst)")


async def interactive(robot: Robot) -> None:
    print("Toetsen: " + "  ".join(f"{k}={v}" for k, v in SHORT.items()) + "  (Enter = scherm, q = stop)")
    while True:
        line = (await asyncio.to_thread(sys.stdin.readline)).strip().lower()
        if line in ("q", "quit"):
            return
        if line:
            key = SHORT.get(line, line)
            if key not in KEYS:
                print("onbekende toets"); continue
            await robot.press(key)
        print(robot.status().screen, "\n")


async def main(a) -> int:
    if a.scan:
        for f in await find_robots(name=a.name, include_all=a.all):
            print(f"{f.address}  {f.name or '?':20} {f.rssi} dBm  ({f.why})")
        return 0
    async with Robot(a.pin, a.address, name=a.name) as robot:
        print(f"Verbonden met {robot.address}")
        try:
            if a.route:
                await robot.start_route(a.route, dry_run=a.dry_run)
                print("Proefrun klaar, niet gestart." if a.dry_run else f"Route {a.route.upper()} gestart.")
            elif a.stop:
                await robot.stop_route()
                print("Route gestopt.")
            elif a.planning:
                await robot.set_planning(a.planning == "on")
            for k in a.press or []:
                await robot.press(k)
            if a.i:
                await interactive(robot)
        except RobotError as e:
            print(f"AFGEBROKEN: {e}")
            show(robot)
            return 1
        show(robot)
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Scraper Control: the robot via Bluetooth")
    ap.add_argument("--pin", help="pincode van de robot")
    ap.add_argument("--address", help="adres (macOS: UUID); standaard zelf zoeken")
    ap.add_argument("--name", help="robotnaam, bij meerdere robots")
    ap.add_argument("--scan", action="store_true", help="alleen zoeken")
    ap.add_argument("--all", action="store_true", help="met --scan: alle BLE-apparaten")
    ap.add_argument("--route", choices=list("ABCDabcd"), help="gecombineerde route starten")
    ap.add_argument("--dry-run", action="store_true", help="met --route: alles behalve Start")
    ap.add_argument("--planning", choices=["on", "off"], help="tijdsplanning aan/uit")
    ap.add_argument("--stop", action="store_true", help="rijdende route stoppen (Start/Stop op het rijscherm)")
    ap.add_argument("--press", action="append", choices=sorted(KEYS), help="paneelknop (herhaalbaar)")
    ap.add_argument("-i", action="store_true", help="interactief bedienen")
    sys.exit(asyncio.run(main(ap.parse_args())))
