// Classic Bluetooth serial (RFCOMM to a service UUID, no pairing) through the system's own
// Bluetooth, for the desktop app: one implementation per OS (bt_<os>.cpp), bt_none.cpp where
// there is none. Android uses its own Java API instead.
#pragma once
#include <cstdint>
#include <string>

namespace btserial {

bool supported();

// Devices nearby (and ones the system remembers) whose address starts with prefix (lower
// case "aa:bb:..."), as JSON [{"address","name","rssi"}]; searches for about ms.
std::string find(const std::string& prefix, int ms);

// Connects to service (a UUID) on address; a handle >= 0, or -1 with why in error.
int64_t open(const std::string& address, const std::string& service, std::string& error);

// Blocks until data comes: bytes read, or -1 once the link is closed.
int read(int64_t h, uint8_t* buf, int n);
bool write(int64_t h, const uint8_t* data, int n);
// Also wakes a read that is waiting.
void close(int64_t h);

// Shared helpers for the implementations.
std::string jsonString(const std::string& s);
bool parseAddress(const std::string& text, uint64_t& out);   // "00:12:ff:..." -> 48 bits
std::string addressText(uint64_t a);                         // lower case, with colons
bool parseUuid(const std::string& text, uint8_t out[16]);    // big-endian bytes

}  // namespace btserial
