// What the M5Stack buttons use of this driver: the robot API (LelyRobot.h) as driver::, reached
// over the "ble-serial" link (driver::kBle, firmware/lib/BleSerialLink).
#pragma once
#include "LelyRobot.h"

namespace driver = lely;

// Flash namespace of the buttons' settings (PIN, robot, wifi): kept from the first version.
#define DRIVER_STORE "lely"
