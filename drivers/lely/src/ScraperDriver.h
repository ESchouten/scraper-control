// What the M5Stack buttons use of this driver: the robot API (LelyRobot.h) as driver::, reached
// over BLE (driver::kBle) or classic Bluetooth (driver::kSppService): firmware/lib/BtLink.
#pragma once
#include "LelyRobot.h"

namespace driver = lely;

// Flash namespace of the buttons' settings (PIN, robot, wifi): kept from the first version.
#define DRIVER_STORE "lely"
