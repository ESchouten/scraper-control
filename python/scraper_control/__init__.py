"""scraper_control: control a manure scraper robot over Bluetooth.

The robot logic lives in the robot driver (drivers/, C++); this package finds the robot,
opens the link and exposes the same API as the M5Stack buttons and the apps.
"""
from .api import KEYS, PinRejected, Robot, RobotBusy, RobotError, Status
from .discover import discover as find_robots   # keep scraper_control.discover = the module

__all__ = ["Robot", "RobotError", "RobotBusy", "PinRejected", "Status", "KEYS", "find_robots"]
