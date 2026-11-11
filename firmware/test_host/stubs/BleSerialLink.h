// Minimal stand-in for BleSerialLink.h so main.cpp can be built on a PC (not the real library).
#pragma once
#include <vector>
#include "ScraperDriver.h"
struct FoundRobot { std::string address, name; int rssi; };
struct BleSerialLink: driver::Transport { static std::vector<FoundRobot> scanAll(uint32_t ms = 5000); BleSerialLink(std::string, std::string);
 bool connect() override; bool write(const driver::Bytes&) override; void disconnect() override; void pump(uint32_t) override; uint32_t now() override;
 const std::string& address() const; };
