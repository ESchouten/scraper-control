// Classic Bluetooth serial on Linux, straight on the kernel's Bluetooth sockets (no library
// needed): an HCI inquiry, an SDP lookup of the service's RFCOMM channel, an RFCOMM socket.
// No pairing, like Android's insecure RFCOMM.
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <set>
#include <vector>

#include "bt_serial.h"

namespace btserial {
namespace {

// From the kernel's Bluetooth headers (not always installed).
constexpr int kAfBluetooth = 31, kHci = 1, kRfcomm = 3, kL2cap = 0;
#pragma pack(push, 1)
struct BdAddr { uint8_t b[6]; };   // little endian: b[0] is the last byte of "aa:bb:...:ff"
struct InquiryReq { uint16_t dev; uint16_t flags; uint8_t lap[3]; uint8_t length; uint8_t count; };
struct InquiryInfo { BdAddr addr; uint8_t rep, period, mode, cls[3]; uint16_t clock; };
struct SockaddrRc { uint16_t family; BdAddr addr; uint8_t channel; };
struct SockaddrL2 { uint16_t family; uint16_t psm; BdAddr addr; uint16_t cid; uint8_t type; };
#pragma pack(pop)
constexpr unsigned long kHciInquiry = 0x800448f0;   // _IOR('H', 240, int)
constexpr uint16_t kFlushCache = 1;

BdAddr toBd(uint64_t a) {
  BdAddr b;
  for (int i = 0; i < 6; i++) b.b[i] = (uint8_t)(a >> (8 * i));
  return b;
}

uint64_t fromBd(const BdAddr& b) {
  uint64_t a = 0;
  for (int i = 5; i >= 0; i--) a = a << 8 | b.b[i];
  return a;
}

std::string why(const char* what) { return std::string(what) + " failed: " + strerror(errno); }

// The RFCOMM channel of service (16 bytes) on the device, from its SDP server; 0 if not found.
int channelOf(const BdAddr& addr, const uint8_t service[16]) {
  int s = socket(kAfBluetooth, SOCK_SEQPACKET, kL2cap);
  if (s < 0) return 0;
  SockaddrL2 sa{};
  sa.family = kAfBluetooth;
  sa.psm = 1;   // SDP (little endian on every Linux Bluetooth host)
  sa.addr = addr;
  int channel = 0;
  if (connect(s, (sockaddr*)&sa, sizeof sa) == 0) {
    // ServiceSearchAttributeRequest: pattern {uuid128}, all bytes, attribute 0x0004 (protocols)
    std::vector<uint8_t> p = {0x35, 0x11, 0x1c};
    p.insert(p.end(), service, service + 16);
    p.insert(p.end(), {0xff, 0xff, 0x35, 0x03, 0x09, 0x00, 0x04, 0x00});
    std::vector<uint8_t> req = {0x06, 0x00, 0x01, (uint8_t)(p.size() >> 8), (uint8_t)p.size()};
    req.insert(req.end(), p.begin(), p.end());
    uint8_t res[1024];
    if (send(s, req.data(), req.size(), 0) == (ssize_t)req.size()) {
      ssize_t n = recv(s, res, sizeof res, 0);
      for (ssize_t i = 0; i + 4 < n; i++)   // RFCOMM's uuid16 0x0003, then its channel as uint8
        if (res[i] == 0x19 && res[i + 1] == 0x00 && res[i + 2] == 0x03 && res[i + 3] == 0x08) {
          channel = res[i + 4];
          break;
        }
    }
  }
  ::close(s);
  return channel;
}

}  // namespace

bool supported() {
  int s = socket(kAfBluetooth, SOCK_RAW, kHci);
  if (s < 0) return false;
  ::close(s);
  return true;
}

std::string find(const std::string& prefix, int ms) {
  int s = socket(kAfBluetooth, SOCK_RAW, kHci);
  if (s < 0) return "[]";
  constexpr int kMax = 64;
  std::vector<uint8_t> buf(sizeof(InquiryReq) + kMax * sizeof(InquiryInfo));
  auto* req = (InquiryReq*)buf.data();
  req->dev = 0;   // the first adapter
  req->flags = kFlushCache;
  req->lap[0] = 0x33; req->lap[1] = 0x8b; req->lap[2] = 0x9e;   // general inquiry
  req->length = (uint8_t)(ms < 1280 ? 1 : ms / 1280 > 48 ? 48 : ms / 1280);   // units of 1.28 s
  req->count = kMax;
  std::string out = "[";
  if (ioctl(s, kHciInquiry, buf.data()) == 0) {
    auto* info = (InquiryInfo*)(buf.data() + sizeof(InquiryReq));
    std::set<uint64_t> seen;
    for (int i = 0; i < req->count; i++) {
      uint64_t a = fromBd(info[i].addr);
      std::string text = addressText(a);
      if (text.rfind(prefix, 0) != 0 || !seen.insert(a).second) continue;
      if (out.size() > 1) out += ",";
      // an inquiry gives no name or signal strength
      out += "{\"address\":" + jsonString(text) + ",\"name\":\"\",\"rssi\":-127}";
    }
  }
  ::close(s);
  return out + "]";
}

int64_t open(const std::string& address, const std::string& service, std::string& error) {
  uint64_t a;
  uint8_t u[16];
  if (!parseAddress(address, a) || !parseUuid(service, u)) {
    error = "bad address or service";
    return -1;
  }
  SockaddrRc sa{};
  sa.family = kAfBluetooth;
  sa.addr = toBd(a);
  sa.channel = (uint8_t)channelOf(sa.addr, u);
  if (sa.channel == 0) sa.channel = 1;   // no answer from SDP: serial port modules use channel 1
  int s = socket(kAfBluetooth, SOCK_STREAM, kRfcomm);
  if (s < 0) {
    error = why("socket");
    return -1;
  }
  if (connect(s, (sockaddr*)&sa, sizeof sa) != 0) {
    error = why("connect");
    ::close(s);
    return -1;
  }
  return s;
}

int read(int64_t h, uint8_t* buf, int n) {
  ssize_t r = recv((int)h, buf, n, 0);
  return r > 0 ? (int)r : -1;
}

bool write(int64_t h, const uint8_t* data, int n) {
  while (n > 0) {
    ssize_t r = send((int)h, data, n, MSG_NOSIGNAL);
    if (r <= 0) return false;
    data += r;
    n -= (int)r;
  }
  return true;
}

void close(int64_t h) {
  shutdown((int)h, SHUT_RDWR);
  ::close((int)h);
}

}  // namespace btserial
