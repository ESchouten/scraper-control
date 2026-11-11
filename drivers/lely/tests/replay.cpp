// Feeds the raw screen records of a capture from the real robot (captures/*.txt) into
// the core's Screen, block by block, and prints what the core recognises after each block.
// Driven by test_core.py; run by hand: replay < captures/<file>.txt
#include <cstdio>
#include <iostream>
#include <sstream>
#include <string>

#include "LelyProtocol.h"

using namespace lely;

static void report(const Screen& s, const std::string& block) {
  if (block.empty()) return;
  Status st = parseStatus(s);
  ManualState m = s.manual();
  const char* kind = s.isHome() ? "home" : s.isRouteMenu() ? "routemenu" : s.isMainMenu() ? "mainmenu"
                     : s.isManual() ? "manual" : s.isQuestion() ? "question" : "other";
  printf("%s | %s | planning=%d next=%s route=%s batt=%d | sel=%d '%s' top=%d", block.c_str(), kind, st.planning,
         st.nextRide.c_str(), st.route.c_str(), st.batteryMv, s.highlightedRow(), s.highlighted().c_str(), s.topRow());
  printf(" | lang=%s", s.language().c_str());
  if (s.isManual()) printf(" | L=%d R=%d fwd=%d", m.left, m.right, m.forward);
  printf("\n");
}

int main() {
  Screen s;
  std::string line, block;
  int capture = 0;
  while (std::getline(std::cin, line)) {
    if (line.rfind("=== raw capture", 0) == 0) { s = Screen(); capture++; continue; }
    if (line.rfind("===== ", 0) == 0) {   // "===== na enter =====": the records below belong to it
      block = std::to_string(capture) + ":" + line.substr(6, line.size() - 12);
      continue;
    }
    if (line.rfind("raw: ", 0) != 0) continue;
    std::istringstream in(line.substr(5));
    std::string hex;
    while (in >> hex) {
      if (hex.size() != 16) continue;
      uint8_t d[8];
      for (int i = 0; i < 8; i++) d[i] = (uint8_t)std::stoi(hex.substr(2 * i, 2), nullptr, 16);
      s.feed(d);
    }
    report(s, block);
  }
}
