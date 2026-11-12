// Draws the firmware's screens on a PC with the real LVGL code (Ui.h): every screen is
// rendered into a 320x240 frame and written as raw RGB565, which render.py makes into PNG.
// Run: python3 firmware/test_host/preview/render.py [out_dir]
#include <cstdio>
#include <string>

#include "../../src/main.cpp"

M5T M5;
SerialT Serial;
// main.cpp's globals that only exist on the M5 (the loader wants their data, not their code)
WiFiT WiFi;
EspT ESP;
UpdateT Update;

static uint32_t now = 0;
uint32_t millis() { return now; }
void delay(uint32_t ms) { now += ms; }

static uint16_t frame[240][320];
void Disp::setBrightness(uint8_t) {}
void Disp::pushImage(int x, int y, int w, int h, const m5gfx::rgb565_t* px) {
  for (int j = 0; j < h; j++)
    for (int i = 0; i < w; i++)
      if (y + j >= 0 && y + j < 240 && x + i >= 0 && x + i < 320) frame[y + j][x + i] = px[j * w + i].raw;
}
bool m5::Button_Class::isPressed() { return false; }

static std::string out = ".";
static const char* lang = "nl";
static void shot(const char* name) {
  ui::refresh();
  std::string path = out + "/" + lang + "-" + name + ".rgb565";
  FILE* f = std::fopen(path.c_str(), "wb");
  std::fwrite(frame, sizeof frame, 1, f);
  std::fclose(f);
  std::printf("%s\n", path.c_str());
}

// Every screen once per language (nl-1-home, en-1-home, ...).
static void all() {
  String rb = String(tx->route) + " B";
  haveSeen = false;
  idle(); shot("1-home");
  seen = Status(); seen.planning = 1; seen.nextRide = "21:00"; seen.clock = "08:00:00"; seen.batteryMv = 25400;
  seenAt = now; haveSeen = true; now += 12 * 60000;
  idle(); shot("1b-home-seen");
  idle(); ui::fillHold(0, 900); shot("1c-home-holding");   // left held 0.9 s: filling up
  syncing = true; idle(); shot("1d-home-reading"); syncing = false;
  haveSeen = false;
  drawProgress(rb, nextStep(Kind::Route, int(Step::ScreenReady), true), 3, 8); shot("2-busy");
  Status st;
  st.planning = 1; st.nextRide = "21:00"; st.batteryMv = 25400;
  drawResult(rb, true, rb + " " + tx->started, st, ""); shot("3-done");
  bool en = tx == &kEn;
  drawResult(rb, false, en ? "Robot is busy" : "Robot is bezig", st,
             String(en ? "not on home screen or route menu; nothing pressed" : "niet op beginscherm of routemenu; niets gedrukt")
             + "\nROUTE->TIJDPAD\nRoute: B"); shot("4-error");
  st = Status(); st.planning = 0; st.nextRide = "21:00"; st.route = ""; st.action = en ? "Charging" : "Laden"; st.batteryMv = 25600;
  drawResult("Status", true, tx->robotStatus, st, ""); shot("5-status");
  ManualState m; m.left = 1; m.right = 0; m.forward = 1;
  drawManual(m, false); shot("7-manual");
  drawManual(m, false); ui::fillHold(0, 1100); shot("7b-manual-holding");
  m.right = 1;
  drawManual(m, true); shot("8-driving");
  PinEditor e("4321"); e.next();
  drawPin(e, tx->enterPin); shot("6-pin");
  drawSettings(); shot("9-settings");
  drawSettings(tx->settingsWifiLooking); shot("9b-settings-update");
  drawFinishQuestion(); shot("15-finish-question");
  drawResult(rb, true, tx->routeCancelled, st, ""); shot("16-cancelled");
  std::vector<FoundRobot> found = {{"00:12:ff:51:4d:00", "stal", -58}, {"00:12:ff:51:4d:01", "", -77}};
  drawList(tx->robotWord, robotLines(found), 1); shot("10-choose-robot");
  wifiSsid = "Stal-wifi";
  drawWifi(); shot("11-wifi");
  drawList(tx->networkWord, {String("Stal-wifi  -48 dBm"), String("Thuis  -67 dBm"), String("Gast  -80 dBm")}, 0); shot("12-network");
  TextEditor te("geheim");
  te.next(); te.next(); te.next();
  drawTyping(te, "Stal-wifi"); shot("13-password");
  busy(tx->wpsWord, tx->wpsPress); ui::hint(tx->pressToStop); shot("14-wps");
}

int main(int argc, char** argv) {
  if (argc > 1) out = argv[1];
  ui::animate = false;   // still pictures
  ui::begin();
  tx = &kNl; lang = "nl"; all();
  tx = &kEn; lang = "en"; all();
}
