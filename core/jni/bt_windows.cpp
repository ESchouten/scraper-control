// Classic Bluetooth serial on Windows: an inquiry through Winsock's Bluetooth name space and an
// RFCOMM socket to the service UUID (Windows looks up the channel). No pairing: the socket asks
// for no authentication, like Android's insecure RFCOMM.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2bth.h>

#include <cstring>
#include <vector>

#include "bt_serial.h"

namespace btserial {
namespace {

bool wsa() {
  static const bool ok = [] {
    WSADATA d;
    return WSAStartup(MAKEWORD(2, 2), &d) == 0;
  }();
  return ok;
}

std::string utf8(const wchar_t* w) {
  if (!w) return "";
  int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
  if (n <= 1) return "";
  std::string s((size_t)n - 1, '\0');
  WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
  return s;
}

std::string lastError(const char* what) { return std::string(what) + " failed (" + std::to_string(WSAGetLastError()) + ")"; }

}  // namespace

bool supported() { return wsa(); }

std::string find(const std::string& prefix, int ms) {
  std::string out = "[";
  if (!wsa()) return "[]";
  WSAQUERYSETW q{};
  q.dwSize = sizeof q;
  q.dwNameSpace = NS_BTH;
  BTH_QUERY_DEVICE inquiry{};
  inquiry.length = (UCHAR)(ms < 1000 ? 1 : ms > 48000 ? 48 : ms / 1000);   // seconds
  BLOB blob{sizeof inquiry, (BYTE*)&inquiry};
  q.lpBlob = &blob;
  const DWORD flags = LUP_CONTAINERS | LUP_FLUSHCACHE | LUP_RETURN_NAME | LUP_RETURN_ADDR;
  HANDLE h;
  if (WSALookupServiceBeginW(&q, flags, &h) != 0) return "[]";
  std::vector<uint64_t> seen;
  alignas(8) char buf[4096];
  for (;;) {
    DWORD len = sizeof buf;
    auto* r = (WSAQUERYSETW*)buf;
    if (WSALookupServiceNextW(h, flags, &len, r) != 0) break;
    if (!r->lpcsaBuffer || !r->lpcsaBuffer->RemoteAddr.lpSockaddr) continue;
    uint64_t a = ((SOCKADDR_BTH*)r->lpcsaBuffer->RemoteAddr.lpSockaddr)->btAddr;
    std::string text = addressText(a);
    if (text.rfind(prefix, 0) != 0) continue;
    bool dup = false;
    for (uint64_t s : seen) dup |= s == a;
    if (dup) continue;
    seen.push_back(a);
    if (out.size() > 1) out += ",";
    // an inquiry gives no signal strength
    out += "{\"address\":" + jsonString(text) + ",\"name\":" + jsonString(utf8(r->lpszServiceInstanceName)) + ",\"rssi\":-127}";
  }
  WSALookupServiceEnd(h);
  return out + "]";
}

int64_t open(const std::string& address, const std::string& service, std::string& error) {
  SOCKADDR_BTH sa{};
  uint8_t u[16];
  if (!parseAddress(address, sa.btAddr) || !parseUuid(service, u)) {
    error = "bad address or service";
    return -1;
  }
  if (!wsa()) {
    error = "Winsock is not available";
    return -1;
  }
  sa.addressFamily = AF_BTH;
  sa.serviceClassId.Data1 = (unsigned long)u[0] << 24 | u[1] << 16 | u[2] << 8 | u[3];
  sa.serviceClassId.Data2 = (unsigned short)(u[4] << 8 | u[5]);
  sa.serviceClassId.Data3 = (unsigned short)(u[6] << 8 | u[7]);
  memcpy(sa.serviceClassId.Data4, u + 8, 8);
  sa.port = 0;   // look the channel up by the service
  SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
  if (s == INVALID_SOCKET) {
    error = lastError("socket");
    return -1;
  }
  if (connect(s, (sockaddr*)&sa, sizeof sa) == SOCKET_ERROR) {
    error = lastError("connect");
    closesocket(s);
    return -1;
  }
  return (int64_t)s;
}

int read(int64_t h, uint8_t* buf, int n) {
  int r = recv((SOCKET)h, (char*)buf, n, 0);
  return r > 0 ? r : -1;
}

bool write(int64_t h, const uint8_t* data, int n) {
  while (n > 0) {
    int r = send((SOCKET)h, (const char*)data, n, 0);
    if (r == SOCKET_ERROR) return false;
    data += r;
    n -= r;
  }
  return true;
}

void close(int64_t h) {
  shutdown((SOCKET)h, SD_BOTH);
  closesocket((SOCKET)h);
}

}  // namespace btserial
