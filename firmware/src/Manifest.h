// The update manifest next to firmware.bin in a release (firmware.txt), no hardware code
// (tested on a PC). Lines "key=value":
//   board=m5stack-core  version=0.7.0  build=412  size=2145195  sha256=<64 hex>  sig=<DER hex>
// sig is ECDSA P-256 over SHA-256 of signedMessage(): made by firmware/sign_release.py with
// the release key, checked on the M5 with the public key in ReleaseKey.h. The M5 installs
// only a signed build that is newer than its own, for its own board.
#pragma once
#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>

struct Manifest {
  std::string board, version, sha256, sig;
  long build = 0, size = 0;

  bool complete() const { return !board.empty() && !version.empty() && build > 0 && size > 0 && sha256.size() == 64 && !sig.empty(); }
};

inline Manifest parseManifest(const std::string& text) {
  Manifest m;
  size_t from = 0;
  while (from < text.size()) {
    size_t nl = text.find('\n', from);
    std::string line = text.substr(from, nl == std::string::npos ? std::string::npos : nl - from);
    from = nl == std::string::npos ? text.size() : nl + 1;
    while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
    size_t eq = line.find('=');
    if (eq == std::string::npos) continue;
    std::string k = line.substr(0, eq), v = line.substr(eq + 1);
    if (k == "board") m.board = v;
    else if (k == "version") m.version = v;
    else if (k == "build") m.build = std::atol(v.c_str());
    else if (k == "size") m.size = std::atol(v.c_str());
    else if (k == "sha256") m.sha256 = v;
    else if (k == "sig") m.sig = v;
  }
  return m;
}

// What the signature covers: everything but the signature itself.
inline std::string signedMessage(const Manifest& m) {
  return "scraper-knop|" + m.board + "|" + m.version + "|" + std::to_string(m.build) + "|" + std::to_string(m.size) + "|" + m.sha256;
}

inline bool fromHex(const std::string& hex, std::vector<uint8_t>& out) {
  if (hex.size() % 2) return false;
  out.clear();
  for (size_t i = 0; i < hex.size(); i += 2) {
    char* end = nullptr;
    std::string b = hex.substr(i, 2);
    long v = std::strtol(b.c_str(), &end, 16);
    if (*end) return false;
    out.push_back(uint8_t(v));
  }
  return true;
}

inline std::string toHex(const uint8_t* p, size_t n) {
  static const char* d = "0123456789abcdef";
  std::string s;
  for (size_t i = 0; i < n; i++) { s += d[p[i] >> 4]; s += d[p[i] & 15]; }
  return s;
}

// Install only for this board and only a newer build.
inline bool isUpdate(const Manifest& m, const char* board, long ownBuild) {
  return m.complete() && m.board == board && m.build > ownBuild;
}
