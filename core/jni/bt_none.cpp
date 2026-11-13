// No classic Bluetooth serial on this system (yet).
#include "bt_serial.h"

namespace btserial {
bool supported() { return false; }
std::string find(const std::string&, int) { return "[]"; }
int64_t open(const std::string&, const std::string&, std::string& error) {
  error = "classic Bluetooth is not available on this system";
  return -1;
}
int read(int64_t, uint8_t*, int) { return -1; }
bool write(int64_t, const uint8_t*, int) { return false; }
void close(int64_t) {}
}  // namespace btserial
