"""Find the robot automatically, so no address has to be hard-coded.

How a robot is recognised while scanning (no connection needed), from the driver:
  * the address starts with the driver's prefix, or
  * it advertises the driver's BLE service, or
  * its advertised name contains the name you pass (e.g. "stal").
On Linux/Windows bleak reports real MACs; on macOS it reports a per-Mac UUID,
so there only the service/name checks work.

The last address that worked is remembered in ~/.config/scraper-control/last.json,
so the next connect skips the scan. If that address stops working we scan again.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

from .transport import LINK, SERVICE

PREFIX = LINK["addressPrefix"].upper()
CACHE = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "scraper-control" / "last.json"


@dataclass
class Found:
    address: str
    name: str | None
    rssi: int | None
    why: str


def _has_prefix(addr: str) -> bool:
    return bool(PREFIX) and addr.upper().replace("-", ":").startswith(PREFIX)


async def discover(timeout: float = 8.0, name: str | None = None,
                   include_all: bool = False) -> list[Found]:
    """Scan and return likely robots, strongest signal first."""
    from bleak import BleakScanner

    seen = await BleakScanner.discover(timeout=timeout, return_adv=True)
    out: list[Found] = []
    for addr, (dev, adv) in seen.items():
        adv_name = adv.local_name or dev.name
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        reasons = []
        if _has_prefix(addr):
            reasons.append("adres")
        if SERVICE in uuids:
            reasons.append("UART-service")
        if name and adv_name and name.lower() in adv_name.lower():
            reasons.append("naam")
        if not reasons:
            if include_all:
                out.append(Found(addr, adv_name, adv.rssi, "-"))
            continue
        if name and "naam" not in reasons and adv_name:
            continue  # a name was asked for and this device has a different one
        out.append(Found(addr, adv_name, adv.rssi, "+".join(reasons)))
    # the driver's address prefix beats only the service; then strongest signal.
    out.sort(key=lambda f: ("adres" not in f.why, -(f.rssi or -999)))
    return out


def _load() -> dict:
    try:
        return json.loads(CACHE.read_text())
    except (OSError, ValueError):
        return {}


def _save(d: dict) -> None:
    try:
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_text(json.dumps(d))
    except OSError:
        pass


def load_cached() -> str | None:
    return _load().get("address")


def save_cached(address: str) -> None:
    d = _load(); d["address"] = address; _save(d)


def load_image_version(address: str) -> bytes:
    """Image-set version the robot gave us last time (like the app's version.dat).
    Sending it back on the robot's [5,0] request skips the ~75 s image download."""
    v = _load().get("image_versions", {}).get(address)
    return bytes.fromhex(v) if v else bytes(9)


def save_image_version(address: str, version: bytes) -> None:
    d = _load(); d.setdefault("image_versions", {})[address] = bytes(version).hex(); _save(d)


def forget_cached() -> None:
    try:
        CACHE.unlink()
    except OSError:
        pass


async def _probe_for_nus(candidates: list[Found], limit: int = 5) -> list[Found]:
    """Connect briefly to named devices and keep those that offer the UART service.
    Needed on macOS, where there is no MAC and a robot may not advertise the
    service UUID."""
    from bleak import BleakClient
    ok = []
    for f in candidates[:limit]:
        try:
            async with BleakClient(f.address, timeout=10) as c:
                uuids = [s.uuid.lower() for s in c.services]
            if SERVICE in uuids:
                ok.append(Found(f.address, f.name, f.rssi, "UART-service (na verbinden)"))
        except Exception:
            continue
    return ok


async def resolve(name: str | None = None, timeout: float = 8.0) -> str:
    """Pick one unit from a scan. Raises LookupError if none or ambiguous."""
    found = await discover(timeout, name)
    if not found:
        named = [f for f in await discover(timeout, None, include_all=True) if f.name]
        named.sort(key=lambda f: -(f.rssi or -999))
        found = await _probe_for_nus(named)
    if not found:
        raise LookupError("Geen robot gevonden. Staat Bluetooth aan, ben je dichtbij, "
                          "en is geen andere app met de robot verbonden?")
    sure = [f for f in found if "adres" in f.why or "naam" in f.why]
    pick = sure or found
    if len(pick) > 1 and not name:
        lst = ", ".join(f"{f.address} ({f.name or '?'}, {f.rssi} dBm)" for f in pick)
        raise LookupError(f"Meerdere robots gevonden: {lst}. Kies er een met --name of --address.")
    return pick[0].address
