"""Links to the robot. The driver says which kind it needs (core.link_info()); each kind is
implemented here once. So far: "ble-serial" with bleak (write to RX, notifications from TX)."""
from __future__ import annotations

import asyncio
from collections.abc import Callable

from bleak import BleakClient

from .core import link_info

LINK = link_info()
if LINK["kind"] != "ble-serial":
    raise NotImplementedError(f"link kind {LINK['kind']!r} is not implemented in Python yet")
SERVICE, RX, TX = LINK["service"], LINK["rx"], LINK["tx"]   # RX: write to robot, TX: notifications


class BleTransport:
    def __init__(self, address: str, on_data: Callable[[bytes], None]) -> None:
        self._client = BleakClient(address)
        self._on_data = on_data

    async def connect(self) -> None:
        await self._client.connect()
        try:
            await self._client.get_services()  # type: ignore[attr-defined]
        except Exception:
            pass
        await self._client.start_notify(
            TX, lambda _sender, data: self._on_data(bytes(data))
        )

    async def write(self, frame: bytes) -> None:
        # write without response, as the robot expects
        await self._client.write_gatt_char(RX, frame, response=False)

    async def disconnect(self) -> None:
        try:
            await self._client.stop_notify(TX)
        except Exception:
            pass
        await self._client.disconnect()

    @property
    def is_connected(self) -> bool:
        return self._client.is_connected
