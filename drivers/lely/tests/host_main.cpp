// PC test harness: runs the C++ Robot API against the Python simulator
// (fake_robot.py) through stdin/stdout. Driven by test_core.py.
//   stdout frames: 'C' (connect) | 'D' (disconnect) | 'W' u16 len + bytes
//   stdin  frames: u16 len + bytes (notifications from the robot)
#include <poll.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "LelyRobot.h"

using namespace lely;

class PipeTransport : public Transport {
 public:
  bool connect() override { return send('C', {}); }
  bool write(const Bytes& f) override { return send('W', f); }
  void disconnect() override { send('D', {}); }
  uint32_t now() override {
    using namespace std::chrono;
    return (uint32_t)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
  }
  void pump(uint32_t ms) override {
    uint32_t end = now() + ms;
    do {
      int left = (int)(end - now());
      pollfd p{0, POLLIN, 0};
      if (poll(&p, 1, left > 0 ? left : 0) <= 0) continue;
      uint8_t hdr[2];
      if (!readAll(hdr, 2)) return;
      Bytes b(hdr[0] | hdr[1] << 8);
      if (!readAll(b.data(), b.size())) return;
      if (onData) onData(b.data(), b.size());
    } while ((int32_t)(end - now()) > 0);
  }

 private:
  static bool readAll(uint8_t* p, size_t n) {
    while (n) { ssize_t r = ::read(0, p, n); if (r <= 0) return false; p += r; n -= r; }
    return true;
  }
  bool send(char type, const Bytes& f) {
    uint8_t hdr[3] = {(uint8_t)type, (uint8_t)(f.size() & 0xFF), (uint8_t)(f.size() >> 8)};
    fwrite(hdr, 1, 3, stdout);
    if (!f.empty()) fwrite(f.data(), 1, f.size(), stdout);
    fflush(stdout);
    return true;
  }
};

static Bytes fromHex(const std::string& h) {
  Bytes b;
  for (size_t i = 0; i + 1 < h.size(); i += 2) b.push_back((uint8_t)std::stoi(h.substr(i, 2), nullptr, 16));
  return b;
}

int main(int argc, char** argv) {
  // argv: <encPinHex|-> <versionHex|-> <command> [arg]
  if (argc < 4) return 2;
  PipeTransport t;
  uint8_t ver[9] = {0};
  if (std::string(argv[2]) != "-") { Bytes v = fromHex(argv[2]); for (int i = 0; i < 9 && i < (int)v.size(); i++) ver[i] = v[i]; }
  Robot r(t, std::string(argv[1]) == "-" ? Bytes{} : fromHex(argv[1]), ver);
  r.log = [](const std::string& s) { fprintf(stderr, "LOG %s\n", s.c_str()); };
  if (const char* owed = getenv("LELY_OWED")) r.setOwedPlanning(atoi(owed));   // from a dropped session
  Result res = r.connect();
  std::string cmd = argv[3];
  if (res == Result::Ok) {
    if (cmd == "status") {
      Status s = r.status();
      fprintf(stderr, "STATUS planning=%d next=%s route=%s ride=%s batt=%d busy=%d stopq=%d\n", s.planning,
              s.nextRide.c_str(), s.route.c_str(), s.rideNo.c_str(), s.batteryMv, s.busy, s.stopQuestion);
    } else if (cmd == "routes") {
      std::vector<std::string> parts;
      res = r.readRoutes(parts);
      if (res == Result::Ok) fprintf(stderr, "ROUTES A=%s|B=%s|C=%s|D=%s\n", parts[0].c_str(), parts[1].c_str(), parts[2].c_str(), parts[3].c_str());
    } else if (cmd == "stop") {
      res = r.stopRoute();
    } else if (cmd == "stop-answer" || cmd == "answer") {   // M5: stop, show the question, pass the choice on
      if (cmd == "stop-answer") res = r.stopRoute();
      fprintf(stderr, "STOPQ %d\n", r.stopQuestion());
      char c = argv[4][0];
      if (res == Result::Ok) res = r.answerStop(c == 'f');
    } else if (cmd == "button") {   // M5 route button: stop a running route, else start
      res = r.status().driving ? r.stopRoute() : r.startRoute(argv[4][0]);
    } else if (cmd == "route" || cmd == "dry") {
      res = r.startRoute(argv[4][0], cmd == "dry");
    } else if (cmd == "manual" || cmd == "manual-drop") {   // M5 middle-button hold
      res = r.enterManual();
      auto show = [&r](const char* when) {
        ManualState m = r.manualState();
        fprintf(stderr, "MANUAL %s left=%d right=%d forward=%d\n", when, m.left, m.right, m.forward);
      };
      if (res == Result::Ok) show("start");
      if (res == Result::Ok) res = r.manualKey(Key::Soft1);
      if (res == Result::Ok) res = r.manualKey(Key::Soft2);
      if (res == Result::Ok) show("L+dir");
      if (res == Result::Ok) res = r.manualKey(Key::Soft3);
      if (res == Result::Ok) res = r.manualKey(Key::Soft1);
      if (res == Result::Ok) show("R, L again");
      if (res == Result::Ok) res = r.driveBegin();
      if (res == Result::Ok) {
        r.driveHold(450);
        fprintf(stderr, "INMANUAL %d\n", r.inManual());
        if (cmd == "manual") {
          r.driveEnd();
          res = r.exitManual();
        }   // manual-drop: disconnect while driving, which must still send "up"
      }
    } else if (cmd == "keys" || cmd == "keys-drop") {   // raw panel keys, like the app's panel screen: s=start/stop e=enter b=back u/d
      for (const char* k = argv[4]; *k; k++)
        r.press(*k == 's' ? Key::StartStop : *k == 'e' ? Key::Enter : *k == 'b' ? Key::Back : *k == 'u' ? Key::Up : Key::Down);
    } else if (cmd == "toggle") {  // same as the firmware's left-button hold
      int now = r.status().planning;
      res = now < 0 ? Result::Busy : r.setPlanning(now == 0);
    }
    if (cmd != "manual-drop" && cmd != "keys-drop" && cmd != "nofinish") r.finish();
    r.disconnect();
  }
  fprintf(stderr, "RESULT %s\n", resultText(res));
  fprintf(stderr, "OWED %d\n", r.owedPlanning());
  char vhex[19] = {0};
  for (int i = 0; i < 9; i++) snprintf(vhex + 2 * i, 3, "%02x", ver[i]);
  fprintf(stderr, "VERSION %s changed=%d\n", vhex, r.imageVersionChanged());
  return res == Result::Ok ? 0 : 1;
}
