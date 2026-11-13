// The link to the robot on the ESP32, over the Bluetooth the driver lists (Bluedroid, the
// framework's stack, so both kinds run on one host):
//   BLE      the serial BLE service of driver::kBle: write to RX, notifications from TX
//   classic  the serial port profile (driver::kSppService): an RFCOMM byte stream, insecure (no
//            pairing), for robots without BLE
// Both find the robot by the driver's address prefix; BLE comes first.
#pragma once
#ifdef ARDUINO
#include <Arduino.h>
#include <BLEDevice.h>

#include <mutex>
#include <string>
#include <vector>

#include "ScraperDriver.h"

// A robot seen in a search; classic: found over classic Bluetooth (else BLE).
struct FoundRobot {
  std::string address, name;
  int rssi;
  bool classic;
};

class BtLink : public driver::Transport {
 public:
  // All robots nearby, strongest first: over BLE, and only when BLE finds none, over classic
  // Bluetooth (a classic search spoils a BLE scan next to it).
  static std::vector<FoundRobot> scanAll(uint32_t ms = 5000);
  // Stops Bluetooth and gives its memory back (for wifi); the next search or connect starts it
  // again. No link may be in use.
  static void release();

  // address: the robot's address, or "" to search for one; classic: how it was found.
  explicit BtLink(std::string address = "", bool classic = false);
  bool connect() override;
  bool write(const driver::Bytes& frame) override;
  void disconnect() override;
  void pump(uint32_t ms) override;
  bool linkUp() override;
  uint32_t now() override { return millis(); }

  const std::string& address() const { return address_; }   // found/used address
  bool classic() const { return classic_; }

 private:
  bool search();
  bool connectBle();
  bool connectClassic();
  void received(const uint8_t* d, size_t n);

  std::string address_;
  bool classic_;
  BLERemoteCharacteristic* rx_ = nullptr;
  std::mutex mu_;
  driver::Bytes inbox_;
};

#endif
