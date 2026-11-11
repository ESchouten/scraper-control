"""ctypes binding to the robot API in C (core/scraper_c.h), built from the robot driver.

The core holds all robot logic (screens, route steps, safety rules); this module only
moves bytes between it and a Bluetooth link. The same core runs on the M5Stack and in
the apps.

The library is $SCRAPER_CORE_LIB, or the one next to this file, which is built on first
use and after a change (needs a C++17 compiler). Or build it yourself:  python -m scraper_control.build_core
"""
from __future__ import annotations

import ctypes
import json
import os
import sys
import threading
from pathlib import Path

OK, CONNECT_FAILED, PIN_REJECTED, NO_SCREEN, BUSY, UNEXPECTED, STARTED_NO_ANSWER = range(7)
IN_PROGRESS = 100
RESULT_NAMES = {0: "ok", 1: "connect_failed", 2: "pin_rejected", 3: "no_screen", 4: "busy",
                5: "unexpected", 6: "started_no_answer", 100: "in_progress"}

WRITE_FN = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint8), ctypes.c_int32)
DONE_FN = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_int32, ctypes.c_int32)

HERE = Path(__file__).resolve().parent


def library_path() -> Path:
    from .build_core import LIBNAME, build, sources
    env = os.environ.get("SCRAPER_CORE_LIB")
    if env and Path(env).exists():
        return Path(env)
    p = HERE / LIBNAME
    # rebuild when the driver or the build secrets changed
    if p.exists() and p.stat().st_mtime >= max(f.stat().st_mtime for f in sources()):
        return p
    try:   # first use: build it next to this package (needs g++ or clang++)
        return build()
    except Exception as e:
        raise FileNotFoundError(f"{LIBNAME} niet gevonden en bouwen lukte niet ({e}); "
                                "bouw hem met: python -m scraper_control.build_core") from e


_lib = None


def lib():
    global _lib
    if _lib is None:
        L = ctypes.CDLL(str(library_path()))
        S = ctypes.c_void_p
        L.scraper_new.restype = S
        L.scraper_new.argtypes = [ctypes.c_char_p, ctypes.c_char_p, WRITE_FN, DONE_FN, ctypes.c_void_p]
        L.scraper_free.argtypes = [S]
        L.scraper_feed.argtypes = [S, ctypes.c_char_p, ctypes.c_int32]
        L.scraper_release.argtypes = [ctypes.POINTER(ctypes.c_uint8)]
        L.scraper_link_down.argtypes = [S]
        for name, extra in (("scraper_connect", []), ("scraper_start_route", [ctypes.c_char, ctypes.c_int32]),
                            ("scraper_set_planning", [ctypes.c_int32]), ("scraper_press", [ctypes.c_int32]),
                            ("scraper_finish", []), ("scraper_stop_route", []), ("scraper_wait", []),
                            ("scraper_answer_stop", [ctypes.c_int32])):
            getattr(L, name).argtypes = [S] + extra
            getattr(L, name).restype = ctypes.c_int32
        for name in ("scraper_status_json", "scraper_last_error"):
            getattr(L, name).argtypes = [S, ctypes.c_char_p, ctypes.c_int32]
            getattr(L, name).restype = ctypes.c_int32
        L.scraper_image_version.argtypes = [S, ctypes.c_char_p]
        L.scraper_image_version.restype = ctypes.c_int32
        L.scraper_result_text.argtypes = [ctypes.c_int32]
        L.scraper_result_text.restype = ctypes.c_char_p
        L.scraper_result_text_lang.argtypes = [ctypes.c_int32, ctypes.c_char_p]
        L.scraper_result_text_lang.restype = ctypes.c_char_p
        L.scraper_set_language.argtypes = [S, ctypes.c_char_p]
        L.scraper_link_json.argtypes = [ctypes.c_char_p, ctypes.c_int32]
        L.scraper_link_json.restype = ctypes.c_int32
        _lib = L
    return _lib


def link_info() -> dict:
    """How the driver's robot is reached: {"kind": "ble-serial", ...} (see core/scraper_c.h)."""
    buf = ctypes.create_string_buffer(512)
    lib().scraper_link_json(buf, len(buf))
    return json.loads(buf.value.decode())


def result_text(r: int, language: str = "nl") -> str:
    return lib().scraper_result_text_lang(r, language.encode()).decode()


class CoreSession:
    """One connection's worth of robot logic. `write(bytes)` is called (from the core's
    thread) for every frame to send; call `feed(bytes)` for every notification.

    Commands are blocking here (`connect()`, `start_route()`, ...) and return a result
    code; run them in a thread (e.g. asyncio.to_thread) when on an event loop."""

    def __init__(self, write, pin: str | None = None, image_version: bytes | None = None, language: str = "nl"):
        self._write = write
        self._done = threading.Event()
        self._wcb = WRITE_FN(self._on_write)
        self._dcb = DONE_FN(lambda _u, _cmd, _res: None)
        ver = image_version if image_version and len(image_version) == 9 else None
        self._s = lib().scraper_new((pin or "").encode(), ver, self._wcb, self._dcb, None)
        lib().scraper_set_language(self._s, language.encode())   # of last_error(): "nl" or "en"

    def _on_write(self, _user, data, n) -> None:
        frame = ctypes.string_at(data, n)
        lib().scraper_release(data)
        self._write(frame)

    def close(self) -> None:
        if self._s:
            lib().scraper_free(self._s)
            self._s = None

    def __del__(self):
        self.close()

    # bytes in / link state
    def feed(self, data: bytes) -> None:
        lib().scraper_feed(self._s, data, len(data))

    def link_down(self) -> None:
        lib().scraper_link_down(self._s)

    # commands (blocking)
    def _run(self, started: int) -> int:
        if started == IN_PROGRESS:
            return IN_PROGRESS
        return lib().scraper_wait(self._s)

    def connect(self) -> int:
        return self._run(lib().scraper_connect(self._s))

    def start_route(self, letter: str, dry_run: bool = False) -> int:
        return self._run(lib().scraper_start_route(self._s, letter.upper().encode(), int(dry_run)))

    def stop_route(self) -> int:
        return self._run(lib().scraper_stop_route(self._s))

    def answer_stop(self, finish: bool) -> int:
        return self._run(lib().scraper_answer_stop(self._s, int(finish)))

    def set_planning(self, on: bool) -> int:
        return self._run(lib().scraper_set_planning(self._s, int(on)))

    def press(self, key: int) -> int:
        return self._run(lib().scraper_press(self._s, key))

    def finish(self) -> int:
        """Back home from a safe menu, planning as at connect (before close)."""
        return self._run(lib().scraper_finish(self._s))

    # state
    def _text(self, fn) -> str:
        n = fn(self._s, None, 0)
        buf = ctypes.create_string_buffer(n + 1)
        fn(self._s, buf, n + 1)
        return buf.value.decode(errors="replace")

    def status(self) -> dict:
        return json.loads(self._text(lib().scraper_status_json))

    def last_error(self) -> str:
        return self._text(lib().scraper_last_error)

    def image_version(self) -> tuple[bytes, bool]:
        buf = ctypes.create_string_buffer(9)
        changed = lib().scraper_image_version(self._s, buf)
        return buf.raw, bool(changed)
