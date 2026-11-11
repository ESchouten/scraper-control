// Draws the firmware's screens on a PC: the display calls are written to stdout and
// render.py paints them with the same FreeSans fonts. Run: python3 render.py
#include <cstdarg>
#include <cstdio>

#include "../../src/main.cpp"
#include "metrics.h"   // made by render.py: glyph advances + heights of the fonts

const GFXfont fonts::FreeSans9pt7b{0}, fonts::FreeSansBold9pt7b{1}, fonts::FreeSansBold12pt7b{2},
    fonts::FreeSansBold18pt7b{3};
M5T M5;
SerialT Serial;
void Disp::setBrightness(uint8_t) {}
static int font = 0;
static uint16_t color = 0;

void Disp::setFont(const GFXfont* f) { font = f->id; }
void Disp::setTextColor(uint16_t c) { color = c; }
void Disp::setTextColor(uint16_t c, uint16_t) { color = c; }
int Disp::fontHeight() { return kHeight[font]; }
int Disp::textWidth(const char* s) {
  int w = 0;
  for (; *s; s++) w += (*s >= 32 && *s < 127) ? kAdvance[font][*s - 32] : 0;
  return w;
}
int Disp::width() { return 320; }
int Disp::height() { return 240; }
void Disp::drawString(const char* s, int x, int y) { ::printf("text %d %d %d %d %s\n", font, color, x, y, s); }
void Disp::fillScreen(uint16_t c) { ::printf("rect 0 0 320 240 0 %d\n", c); }
void Disp::fillRect(int x, int y, int w, int h, uint16_t c) { ::printf("rect %d %d %d %d 0 %d\n", x, y, w, h, c); }
void Disp::fillRoundRect(int x, int y, int w, int h, int r, uint16_t c) { ::printf("rect %d %d %d %d %d %d\n", x, y, w, h, r, c); }
void Disp::drawRoundRect(int x, int y, int w, int h, int r, uint16_t c) { ::printf("frame %d %d %d %d %d %d\n", x, y, w, h, r, c); }
void Disp::fillCircle(int x, int y, int r, uint16_t c) { ::printf("disc %d %d %d %d\n", x, y, r, c); }
void Disp::drawCircle(int x, int y, int r, uint16_t c) { ::printf("ring %d %d %d %d\n", x, y, r, c); }
void Disp::fillTriangle(int a, int b, int c, int d, int e, int f, uint16_t k) { ::printf("tri %d %d %d %d %d %d %d\n", a, b, c, d, e, f, k); }
void Disp::drawLine(int a, int b, int c, int d, uint16_t k) { ::printf("line %d %d %d %d %d\n", a, b, c, d, k); }
void Disp::drawFastHLine(int x, int y, int w, uint16_t c) { ::printf("line %d %d %d %d %d\n", x, y, x + w - 1, y, c); }

static const char* lang = "nl";
static void shot(const char* name) { ::printf("shot %s-%s\n", lang, name); fflush(stdout); }

// Every screen once per language (nl-1-start.png, en-1-start.png, ...).
static void all() {
  String rb = String(tx->route) + " B";
  idle(); shot("1-start");
  drawProgress(rb, nextStep(Kind::Route, int(Step::ScreenReady), true), 3, 8); shot("2-bezig");
  Status st;
  st.planning = 1; st.nextRide = "21:00"; st.batteryMv = 25400;
  drawResult(rb, true, rb + " " + tx->started, st, ""); shot("3-gelukt");
  bool en = tx == &kEn;
  drawResult(rb, false, en ? "Robot is busy" : "Robot is bezig", st,
             String(en ? "not on home screen or route menu; nothing pressed" : "niet op beginscherm of routemenu; niets gedrukt")
             + "\nROUTE->TIJDPAD\nRoute: B"); shot("4-fout");
  st = Status(); st.planning = 0; st.nextRide = "21:00"; st.route = ""; st.action = en ? "Charging" : "Laden"; st.batteryMv = 25600;
  drawResult("Status", true, tx->robotStatus, st, ""); shot("5-status");
  ManualState m; m.left = 1; m.right = 0; m.forward = 1;
  drawManual(m, false); shot("7-handbediening");
  m.right = 1;
  drawManual(m, true); shot("8-rijden");
  PinEditor e("4321"); e.next();
  drawPin(e, tx->enterPin); shot("6-pincode");
  drawSettings(); shot("9-instellingen");
  drawSettings(tx->settingsWifiLooking); shot("9b-instellingen-update");
  drawFinishQuestion(); shot("15-stopvraag");
  drawResult(rb, true, tx->routeCancelled, st, ""); shot("16-afgebroken");
  std::vector<FoundRobot> found = {{"00:12:ff:51:4d:00", "stal", -58}, {"00:12:ff:51:4d:01", "", -77}};
  drawList(tx->robotWord, robotLines(found), 1); shot("10-robot-kiezen");
  wifiSsid = "Stal-wifi";
  drawWifi(); shot("11-wifi");
  drawList(tx->networkWord, {String("Stal-wifi  -48 dBm"), String("Thuis  -67 dBm"), String("Gast  -80 dBm")}, 0); shot("12-netwerk");
  TextEditor te("geheim");
  te.next(); te.next(); te.next();
  drawTyping(te, "Stal-wifi", true); shot("13-wachtwoord");
  busy(tx->wpsWord, tx->wpsPress); ui::hint(tx->pressToStop); shot("14-wps");
}

int main() {
  tx = &kNl; lang = "nl"; all();
  tx = &kEn; lang = "en"; all();
}
