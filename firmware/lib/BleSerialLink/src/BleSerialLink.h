// The "ble-serial" link on the ESP32 (NimBLE-Arduino 2.x): a serial BLE service whose UUIDs and
// address prefix come from the driver (driver::kBle); write to RX, notifications from TX.
// A driver that talks another way (another BLE service, wifi) gets a link of its own next to it.
#pragma once
#ifdef ARDUINO
#include <Arduino.h>
#include <NimBLEDevice.h>

#include <mutex>
#include <string>
#include <vector>

#include "ScraperDriver.h"

// A robot seen in a scan (the driver's address prefix or its BLE service).
struct FoundRobot {
  std::string address, name;
  int rssi;
};

class BleSerialLink : public driver::Transport {
 public:
  // All robots nearby, strongest first (for choosing one on the device).
  static std::vector<FoundRobot> scanAll(uint32_t ms = 5000);
  // Stops the BLE stack and gives its memory back (for wifi); the next connect or scan starts
  // it again. No link may be in use.
  static void release();

  // address: the robot's address, or "" to scan for one.
  explicit BleSerialLink(std::string address = "");
  bool connect() override;
  bool write(const driver::Bytes& frame) override;
  void disconnect() override;
  void pump(uint32_t ms) override;
  bool linkUp() override { return client_ && client_->isConnected(); }
  uint32_t now() override { return millis(); }

  const std::string& address() const { return address_; }   // found/used address

 private:
  bool scan();
  void onNotify(const uint8_t* d, size_t n);

  std::string address_;
  NimBLEClient* client_ = nullptr;
  NimBLERemoteCharacteristic* rx_ = nullptr;
  std::mutex mu_;
  driver::Bytes inbox_;
};

#endif
