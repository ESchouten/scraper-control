// Scraper Control buttons for M5Stack Basic: left / middle / right button start route A / B / C.
// Each press: connect (~2 s), start the route with all checks, disconnect.
// Hold the middle button 1.5 s for manual drive (HANDBEDIENING), see manualSession().
// The screens follow the Scraper Control app's look (red header, light cards).
// Hold the left button 1.5 s to switch the planning (tijdpad) on or off.
// Hold the right button 1.5 s for the settings: PIN, which robot, wifi (set up on the M5
// itself, or WPS) and updates (signed releases from GitHub).
// A rejected PIN opens the PIN screen by itself.
// The screen dims after a minute and never goes off, so a press always acts at once.
#include <ArduinoOTA.h>
#include <M5Unified.h>
#include <Preferences.h>
#include <Update.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <esp_wps.h>
#include <mbedtls/pk.h>
#include <mbedtls/sha256.h>

#include <algorithm>
#include <cctype>
#include <cstring>
#include <functional>
#include <vector>

#include "HttpLite.h"
#include "Manifest.h"
#include "PinEditor.h"
#include "ReleaseKey.h"
#include "ScraperDriver.h"   // the robot driver (platformio.ini)
#include "BleSerialLink.h"
#include "TextEditor.h"
#include "Texts.h"
#include "config.h"

#ifndef ROBOT_PIN
#define ROBOT_PIN ""
#endif
#ifndef ROBOT_ADDRESS
#define ROBOT_ADDRESS ""
#endif
#ifndef ROBOT_NAME
#define ROBOT_NAME ""
#endif
#ifndef FW_VERSION  // set by version.py (PlatformIO): the apps' version and the commit count
#define FW_VERSION "dev"
#define FW_BUILD 0
#endif
#define FW_BOARD "m5stack-core"
#ifndef FW_REPO     // where the signed releases are (public GitHub repo)
#define FW_REPO "ESchouten/scraper-control"
#endif
#ifndef WIFI_SSID   // optional first default; normally set on the M5 (settings > Wifi)
#define WIFI_SSID ""
#endif
#ifndef WIFI_PASS
#define WIFI_PASS ""
#endif
#ifndef OTA_PASS    // the password pio asks for an update over wifi (upload_flags --auth)
#define OTA_PASS ""
#endif

using namespace driver;

static Preferences prefs;
static uint8_t storedVersion[9] = {'D', 'I', 'S', '0', '0', '1', '8', 'T', '4'};   // learned 2026-11-03
static std::string savedAddress;
static String pin;                 // from flash; ROBOT_PIN in config.h is only the first default
// What the last route button did besides starting: stopped a running route, and then the
// user's answer to the robot's "route afmaken?" (Erik 2026-11-11).
enum class Stopped { No, Yes, LeftOpen, Continues, Cancelled };
static Stopped routeStopped = Stopped::No;
static const Texts* tx = &kNl;     // screen language: the robot's, seen at the last connect
// Planning the robot is still owed by a session whose link dropped (see Robot::owedPlanning);
// paid back at the next connect if not older than 6 hours.
static int owedPlanning = -1;
static uint32_t owedAt = 0;
static const uint32_t kOwedForMs = 6UL * 3600UL * 1000UL;
// Screen: dim after a minute, off after ten; any press wakes it.
// Idle: dimmed, never off (Erik 2026-11-11), so a press always acts at once (a stop too).
static const uint8_t kBright = 160, kDim = 64;
static uint32_t lastActive = 0;
static int dimLevel = 0;   // 0 bright, 1 dim, 2 off

// ---- look: same colours and shapes as the Scraper Control app (app/lib/theme.dart) ----
namespace ui {
constexpr uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) {
  return uint16_t(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}
// uint16_t on purpose: M5GFX reads a uint16_t as RGB565 (a uint32_t would be RGB888)
const uint16_t kRed = rgb(0xC8, 0x10, 0x2E), kRedDeep = rgb(0x9B, 0x0C, 0x24), kRedSoft = rgb(0xFF, 0xE5, 0xE7);
const uint16_t kBg = rgb(0xF6, 0xF4, 0xF3), kCard = rgb(0xFF, 0xFF, 0xFF), kLine = rgb(0xE4, 0xDF, 0xDE);
const uint16_t kChip = rgb(0xF0, 0xED, 0xEC), kInk = rgb(0x1C, 0x1B, 0x1B), kMuted = rgb(0x5E, 0x58, 0x57);
const uint16_t kOk = rgb(0x1B, 0x7F, 0x3B), kOkSoft = rgb(0xE3, 0xF4, 0xE8);
const uint16_t kErr = rgb(0xA8, 0x20, 0x0D), kErrSoft = rgb(0xFC, 0xE8, 0xE4), kWhite = rgb(0xFF, 0xFF, 0xFF);

using Font = decltype(&fonts::FreeSans9pt7b);
const Font kTitle = &fonts::FreeSansBold12pt7b, kBody = &fonts::FreeSans9pt7b, kBold = &fonts::FreeSansBold9pt7b,
           kBig = &fonts::FreeSansBold18pt7b;

const int kW = 320, kHeaderH = 48, kFooterY = 200;
const int kBtnX[3] = {56, 160, 264};   // centres above the three physical buttons

auto& D = M5.Display;

static int textW(const String& s, Font f) { D.setFont(f); return D.textWidth(s.c_str()); }

static void text(const String& s, int x, int y, Font f, uint16_t c) {
  D.setFont(f);
  D.setTextColor(c);
  D.drawString(s.c_str(), x, y);
}

static void centred(const String& s, int cx, int y, Font f, uint16_t c) { text(s, cx - textW(s, f) / 2, y, f, c); }

// Text from (x, y) inside width w, wrapped at spaces and at '\n'. Stops above maxY.
// Returns the y below the last line.
static int wrap(const String& s, int x, int y, int w, Font f, uint16_t c, int maxY) {
  D.setFont(f);
  const int lineH = D.fontHeight() + 2;
  int from = 0;
  while (from <= (int)s.length()) {
    int nl = s.indexOf('\n', from);
    String para = s.substring(from, nl < 0 ? s.length() : nl);
    do {
      String line = para;
      while (line.length() > 1 && textW(line, f) > w) {
        int cut = line.lastIndexOf(' ', line.length() - 2);
        line = line.substring(0, cut > 0 ? cut : line.length() - 1);
      }
      para = para.substring(line.length());
      para.trim();
      if (y + lineH > maxY) return y;
      text(line, x, y, f, c);
      y += lineH;
    } while (para.length() > 0);
    if (nl < 0) break;
    from = nl + 1;
  }
  return y;
}

// The app's mark: white tile with a red ring and play triangle (for on the red header).
static void logo(int x, int y, int s) {
  D.fillRoundRect(x, y, s, s, s * 27 / 100, kWhite);
  int cx = x + s / 2, cy = y + s / 2, r = s * 29 / 100;
  for (int i = 0; i < 2; i++) D.drawCircle(cx, cy, r - i, kRed);
  D.fillTriangle(x + s * 43 / 100, y + s * 37 / 100, x + s * 43 / 100, y + s * 63 / 100, x + s * 65 / 100, cy, kRed);
}

// Red header with the logo and a title, light background below it.
static void screen(const String& title) {
  for (int y = 0; y < kHeaderH; y++) {   // brand gradient, like the app's header
    int t = y * 255 / (kHeaderH - 1);
    D.drawFastHLine(0, y, kW, rgb(0xC8 - (0xC8 - 0x9B) * t / 255, 0x10 - (0x10 - 0x0C) * t / 255, 0x2E - (0x2E - 0x24) * t / 255));
  }
  logo(10, 9, 30);
  text(title, 50, 12, kTitle, kWhite);
  D.fillRect(0, kHeaderH, kW, 240 - kHeaderH, kBg);
}

static void card(int x, int y, int w, int h, uint16_t fill, bool border = true) {
  D.fillRoundRect(x, y, w, h, 12, fill);
  if (border) D.drawRoundRect(x, y, w, h, 12, kLine);
}

enum class Pill { None, Primary, Neutral };

// Labels above the physical buttons.
static void pills(const String (&label)[3], const Pill (&style)[3]) {
  for (int i = 0; i < 3; i++) {
    if (style[i] == Pill::None || label[i].isEmpty()) continue;
    bool primary = style[i] == Pill::Primary;
    D.fillRoundRect(kBtnX[i] - 47, kFooterY + 6, 94, 30, 15, primary ? kRed : kCard);
    if (!primary) D.drawRoundRect(kBtnX[i] - 47, kFooterY + 6, 94, 30, 15, kLine);
    centred(label[i], kBtnX[i], kFooterY + 12, kBold, primary ? kWhite : kInk);
  }
}

static void hint(const String& s) { centred(s, kW / 2, kFooterY + 12, kBody, kMuted); }

// Round status icon: tick (ok) or exclamation mark (error).
static void badge(int cx, int cy, bool ok) {
  D.fillCircle(cx, cy, 13, ok ? kOk : kErr);
  if (ok) {
    for (int i = -1; i <= 1; i++) {
      D.drawLine(cx - 6, cy + i, cx - 2, cy + 4 + i, kWhite);
      D.drawLine(cx - 2, cy + 4 + i, cx + 6, cy - 4 + i, kWhite);
    }
  } else {
    D.fillRoundRect(cx - 2, cy - 8, 4, 11, 2, kWhite);
    D.fillCircle(cx, cy + 6, 2, kWhite);
  }
}

static void progressBar(int x, int y, int w, int done, int total) {
  D.fillRoundRect(x, y, w, 10, 5, kChip);
  int fw = total > 0 ? w * (done < total ? done : total) / total : 0;
  if (fw > 0) D.fillRoundRect(x, y, fw < 10 ? 10 : fw, 10, 5, kRed);
}
}  // namespace ui

// ---- screens -----------------------------------------------------------------------
static void idle() {
  using namespace ui;
  screen("Scraper Control");
  // legend: the two kinds of labels above the buttons
  card(10, 58, 300, 80, kCard);
  D.fillRoundRect(22, 72, 26, 14, 7, kRed);
  text(tx->shortPress, 58, 68, kBold, kInk);
  D.fillRoundRect(22, 106, 26, 14, 4, kCard);
  D.drawRoundRect(22, 106, 26, 14, 4, kMuted);
  text(tx->holdPress, 58, 102, kBold, kInk);

  const char* hold[3] = {tx->holdPlanning, tx->holdManual, tx->holdPin};
  for (int i = 0; i < 3; i++) {
    D.fillRoundRect(kBtnX[i] - 47, 152, 94, 34, 8, kCard);
    D.drawRoundRect(kBtnX[i] - 47, 152, 94, 34, 8, kMuted);
    centred(hold[i], kBtnX[i], 159, kBold, kInk);
  }
  const char r[3] = {ROUTE_BTN_A, ROUTE_BTN_B, ROUTE_BTN_C};
  String label[3];
  Pill style[3];
  for (int i = 0; i < 3; i++) {
    label[i] = r[i] == '-' ? String() : String(tx->route) + " " + r[i];
    style[i] = r[i] == '-' ? Pill::None : Pill::Primary;
  }
  pills(label, style);
}

// What a command is doing, with a progress bar (same steps and words as the app).
enum class Kind { Status, Planning, Route, Manual };

static const char* nextStep(Kind job, int last, bool planningOn) {
  switch (last) {
    case 0: return tx->connecting;
    case int(Step::Linked): return tx->checkingPin;
    case int(Step::Granted): return tx->waitingPanel;
    case int(Step::ScreenReady):
      if (job == Kind::Status) return tx->readingStatus;
      if (job == Kind::Planning) return tx->switchingPlanning;
      return planningOn ? tx->planningOffForNow : tx->openingRouteMenu;   // route, manual
    case int(Step::PlanningOff): return job == Kind::Route || job == Kind::Manual ? tx->openingRouteMenu : tx->finishing;
    case int(Step::Menu): return job == Kind::Manual ? tx->safetyYes : tx->choosingRoute;
    case int(Step::Selected): return tx->pressingStart;
    case int(Step::StartPressed): return tx->answeringQuestion;
    default: return tx->finishing;
  }
}

static int expectedSteps(Kind job, bool planningOn) {
  if (job == Kind::Manual) return planningOn ? 6 : 5;
  return job == Kind::Status ? 3 : job == Kind::Planning ? 4 : planningOn ? 8 : 7;
}

static void drawProgress(const String& title, const String& now, int done, int total) {
  using namespace ui;
  screen(title);
  card(10, 58, 300, 134, kCard);
  wrap(now, 22, 72, 276, kTitle, kInk, 130);
  text(String(tx->stepOf) + " " + String(done + 1 > total ? total : done + 1) + " " + tx->of + " " + String(total), 22, 132, kBody, kMuted);
  progressBar(22, 162, 276, done, total);
  hint(tx->pleaseWait);
}

// Only the fields the robot's current screen shows (while driving, planning and
// next ride are not on it).
static void statusRows(const Status& s, int y, int maxY) {
  using namespace ui;
  auto row = [&](const char* label, const String& value) {
    if (y + 22 > maxY) return;
    text(label, 22, y, kBody, kMuted);
    text(value, 138, y, kBold, kInk);
    y += 24;
  };
  if (s.planning == 1 || s.planning == 0) row(tx->planning, s.planning == 1 ? tx->on : tx->off);
  if (!s.nextRide.empty()) row(tx->nextRide, s.nextRide.c_str());
  if (!s.route.empty()) row(tx->routeRow, s.route.c_str());
  if (!s.action.empty()) row(tx->action, s.action.c_str());
  if (s.batteryMv > 0) row(tx->robotBattery, String(s.batteryMv / 1000) + (tx == &kNl ? "," : ".") + String(s.batteryMv % 1000 / 100) + " V");
}

static void drawResult(const String& title, bool ok, const String& heading, const Status& st, const String& detail) {
  using namespace ui;
  screen(title);
  card(10, 58, 300, 134, ok ? kOkSoft : kErrSoft, false);
  badge(35, 82, ok);
  wrap(heading, 56, 72, 244, kBold, ok ? kOk : kErr, 116);
  if (ok) statusRows(st, 106, 190);
  else wrap(detail, 22, 106, 276, kBody, kInk, 190);
  hint(tx->pressAnyButton);
}

// ---- manual drive screen ---------------------------------------------------------
// Three tiles above the buttons with what each one switches, the drive state below.
static void drawManual(const ManualState& m, bool driving) {
  using namespace ui;
  screen(tx->manual);
  const char* name[3] = {tx->left, tx->direction, tx->right};
  int val[3] = {m.left, m.forward, m.right};
  for (int i = 0; i < 3; i++) {
    bool on = i == 1 ? val[i] >= 0 : val[i] == 1;
    String v = val[i] < 0 ? String("?") : i == 1 ? String(val[i] ? tx->forward : tx->backward) : String(val[i] ? tx->on : tx->off);
    int x = kBtnX[i] - 47;
    D.fillRoundRect(x, 58, 94, 62, 10, on && i != 1 ? kOkSoft : kCard);
    D.drawRoundRect(x, 58, 94, 62, 10, on && i != 1 ? kOk : kLine);
    centred(name[i], kBtnX[i], 66, kBody, kMuted);
    centred(v, kBtnX[i], 90, kBold, on && i != 1 ? kOk : kInk);
  }
  if (driving) {
    D.fillRoundRect(10, 128, 300, 30, 15, kRed);
    centred(tx->drivingRelease, kW / 2, 134, kBold, kWhite);
  } else {
    centred(tx->holdToDrive, kW / 2, 134, kBody, kMuted);
  }
  // hold labels (white) and short-press labels (red), as on the home screen
  const char* hold[3] = {tx->done, tx->drive, ""};
  for (int i = 0; i < 2; i++) {
    D.fillRoundRect(kBtnX[i] - 47, 164, 94, 34, 8, kCard);
    D.drawRoundRect(kBtnX[i] - 47, 164, 94, 34, 8, kMuted);
    centred(hold[i], kBtnX[i], 171, kBold, kInk);
  }
  String label[3] = {tx->motorL, tx->direction, tx->motorR};
  Pill style[3] = {Pill::Primary, Pill::Primary, Pill::Primary};
  pills(label, style);
}

// ---- PIN entry: left = -, middle = next / OK, right = + (hold middle = cancel) ----
static void drawPin(const PinEditor& e, const String& why) {
  using namespace ui;
  screen(tx->pinTitle);
  card(10, 58, 300, 134, kCard);
  wrap(why, 22, 66, 276, kBold, kInk, 90);
  const std::string& d = e.digits();
  int n = (int)d.size(), okW = 52, gap = 6;
  int w = (276 - okW - gap * n) / (n > 0 ? n : 1);
  if (w > 40) w = 40;
  Font f = w >= 32 ? kBig : kTitle;
  int x = 160 - (n * (w + gap) + okW) / 2;
  for (int i = 0; i <= n; i++) {
    bool cur = i == (int)e.cursor();
    int bw = i < n ? w : okW;
    D.fillRoundRect(x, 98, bw, 50, 10, cur ? kRedSoft : kCard);
    D.drawRoundRect(x, 98, bw, 50, 10, cur ? kRed : kLine);
    if (cur) D.drawRoundRect(x + 1, 99, bw - 2, 48, 9, kRed);
    String c = i < n ? String(d[i]) : String("OK");
    D.setFont(i < n ? f : kBold);
    int h = D.fontHeight();
    centred(c, x + bw / 2, 123 - h / 2, i < n ? f : kBold, cur ? kRed : kInk);
    x += bw + gap;
  }
  text(tx->holdToCancel, 22, 162, kBody, kMuted);
  bool ok = e.onOk();
  String label[3] = {ok ? tx->digitMinus : "-", ok ? "OK" : tx->next, ok ? tx->digitPlus : "+"};
  Pill style[3] = {Pill::Neutral, Pill::Primary, Pill::Neutral};
  pills(label, style);
}

// Returns true when a new PIN was confirmed and saved.
static bool editPin(const String& why) {
  PinEditor e(pin.c_str());
  drawPin(e, why);
  uint32_t middleDown = 0, lastInput = millis();
  for (;;) {
    M5.update();
    if (M5.BtnA.wasPressed()) { e.minus(); lastInput = millis(); drawPin(e, why); }
    if (M5.BtnC.wasPressed()) { e.plus(); lastInput = millis(); drawPin(e, why); }
    if (M5.BtnB.wasPressed()) { middleDown = millis(); lastInput = middleDown; }
    bool cancel = millis() - lastInput > 60000 ||
                  (middleDown && M5.BtnB.isPressed() && millis() - middleDown >= 1500);
    if (cancel) {
      while (M5.BtnB.isPressed()) { M5.update(); delay(10); }
      return false;
    }
    if (M5.BtnB.wasReleased() && middleDown) {
      middleDown = 0;
      if (e.next()) {
        pin = e.digits().c_str();
        prefs.putString("pin", pin);
        return true;
      }
      drawPin(e, why);
    }
    delay(10);
  }
}

// The M5 speaks the robot's language: Dutch or English (English for any other language,
// seen on the home screen or route menu). Kept in flash for the next start.
static void followRobotLanguage(const Status& st) {
  const char* code = !st.language.empty() ? st.language.c_str() : st.busy ? nullptr : "en";
  if (!code || std::strcmp(code, tx->code) == 0) return;
  tx = &textsFor(code);
  prefs.putString("lang", tx->code);
}

// ---- one connection ------------------------------------------------------------
using Job = std::function<Result(Robot&)>;

// Connects, runs `job`, disconnects. Remembers address + image version in flash.
static Result runOnce(const String& title, Kind kind, const Job& job, Status& st, std::string& err) {
  int done = 0, last = 0;
  bool planningOn = true;   // until the robot's screen tells
  drawProgress(title, nextStep(kind, 0, planningOn), 0, expectedSteps(kind, planningOn));
  BleSerialLink link(savedAddress.empty() ? ROBOT_ADDRESS : savedAddress, ROBOT_NAME);
  Robot robot(link, encryptPin(pin.c_str()), storedVersion);
  if (owedPlanning >= 0 && millis() - owedAt < kOwedForMs) robot.setOwedPlanning(owedPlanning);
  robot.onOwed = [](int v) {
    owedPlanning = v;
    owedAt = millis();
  };
  robot.lang = langFromCode(tx->code);
  robot.log = [](const std::string& s) { Serial.println(s.c_str()); };
  robot.onStep = [&](Step s) {
    if (s == Step::ScreenReady) {
      Status now = robot.status();
      planningOn = now.planning != 0;
      followRobotLanguage(now);
      robot.lang = langFromCode(tx->code);
    }
    done++;
    last = int(s);
    drawProgress(title, nextStep(kind, last, planningOn), done, expectedSteps(kind, planningOn));
  };

  Result r = robot.connect();
  if (r == Result::Ok) r = job(robot);
  err = robot.lastError();
  if (r == Result::Ok || r == Result::Unexpected) robot.finish();   // planning as found
  st = robot.status();
  robot.disconnect();

  if (robot.imageVersionChanged()) prefs.putBytes("imgver", storedVersion, 9);
  if (r != Result::ConnectFailed && link.address() != savedAddress) {
    savedAddress = link.address();
    prefs.putString("addr", savedAddress.c_str());
  }
  return r;
}

// Show the result until a button is pressed (that press does nothing else) or 20 s.
static void waitForButton() {
  uint32_t until = millis() + 20000;
  while ((int32_t)(millis() - until) < 0) {
    M5.update();
    if (M5.BtnA.wasPressed() || M5.BtnB.wasPressed() || M5.BtnC.wasPressed()) {
      while (M5.BtnA.isPressed() || M5.BtnB.isPressed() || M5.BtnC.isPressed()) { M5.update(); delay(10); }
      break;
    }
    delay(10);
  }
}

// Like runOnce, but a rejected PIN opens the PIN screen and then tries once more.
static void withRobot(const String& title, Kind kind, const Job& job) {
  Status st;
  std::string err;
  Result r = runOnce(title, kind, job, st, err);
  if (r == Result::PinRejected) {
    M5.Speaker.tone(400, 150);
    if (!editPin(tx->pinRejected)) { idle(); return; }
    r = runOnce(title, kind, job, st, err);
  }

  if (r == Result::Ok) {
    String heading = kind == Kind::Route      ? (routeStopped == Stopped::No          ? title + " " + tx->started
                                                 : routeStopped == Stopped::Continues ? String(tx->routeContinues)
                                                 : routeStopped == Stopped::Cancelled ? String(tx->routeCancelled)
                                                                                      : String(tx->routeStopped))
                     : kind == Kind::Planning ? String(tx->planningIs) + " " + (st.planning == 1 ? tx->planningOnWord : tx->planningOffWord)
                     : kind == Kind::Manual   ? String(tx->manualDone)
                                              : String(tx->robotStatus);
    drawResult(title, true, heading, st, routeStopped == Stopped::LeftOpen ? String(tx->questionLeftOpen) : String());
  } else {
    String why = resultText(r, langFromCode(tx->code));
    why = String(char(toupper(why.c_str()[0]))) + why.substring(1);
    String detail = err.c_str();
    if (r == Result::StartedNoAnswer) detail = String(tx->checkPlanning) + "\n" + detail;
    drawResult(title, false, why, st, detail);
  }
  M5.Speaker.tone(r == Result::Ok ? 2000 : 400, 150);
  waitForButton();
  idle();
}

// Planning on <-> off. Only from the home screen: if the robot shows something else
// (driving, a menu), nothing is pressed.
static void togglePlanning() {
  withRobot(tx->planning, Kind::Planning, [](Robot& r) {
    int now = r.status().planning;
    if (now < 0) return Result::Busy;
    return r.setPlanning(now == 0);
  });
}

// Manual drive, while connected. Short: left = left motor, middle = direction, right =
// right motor (settings only). Middle held (> 0.4 s) = drive while held. Left held 1.5 s,
// or 2 minutes without a press = done: back to the home screen, planning as before.
static Result manualSession(Robot& r) {
  Result res = r.enterManual();
  if (res != Result::Ok) return res;
  bool drive = false;
  drawManual(r.manualState(), drive);
  uint32_t down[3] = {0, 0, 0}, last = millis(), driveSince = 0;
  m5::Button_Class* btn[3] = {&M5.BtnA, &M5.BtnB, &M5.BtnC};
  for (;;) {
    M5.update();
    if (!r.linked()) return Result::ConnectFailed;
    for (int i = 0; i < 3; i++)
      if (btn[i]->wasPressed()) { down[i] = millis(); last = down[i]; }
    // middle held: drive while it stays down
    if (!drive && down[1] && M5.BtnB.isPressed() && millis() - down[1] >= 400 && r.driveBegin() == Result::Ok) {
      drive = true;
      driveSince = millis();
      drawManual(r.manualState(), drive);
    }
    if (drive && (!M5.BtnB.isPressed() || millis() - driveSince > 120000)) {   // released (or 2 min)
      r.driveEnd();
      drive = false;
      down[1] = 0;
      last = millis();
      drawManual(r.manualState(), drive);
    }
    if (drive) { r.driveHold(20); continue; }
    for (int i = 0; i < 3; i++) {
      // left held 1.5 s = done, at once while still held (like the home screen)
      if (i == 0 && down[0] && M5.BtnA.isPressed() && millis() - down[0] >= 1500) {
        M5.Speaker.tone(1500, 60);
        return r.exitManual();
      }
      if (!btn[i]->wasReleased() || !down[i]) continue;
      down[i] = 0;
      Key k = i == 0 ? Key::Soft1 : i == 1 ? Key::Soft2 : Key::Soft3;
      r.manualKey(k);
      drawManual(r.manualState(), drive);
    }
    if (millis() - last > 120000 || !r.inManual()) return r.exitManual();
    r.idle(20);
  }
}

// The robot's question after a stop, "route afmaken?", on the M5 (Erik 2026-11-11): the
// user's answer is passed on 1:1. Left = Ja (the route goes on), right = Nee (route over,
// the robot leaves the planning off). Middle, or 2 minutes without a press = leave the
// question on the robot. The link stays up meanwhile. The robot's middle choice (HAND /
// Pauze submenu) is not offered.
static void waitAllReleased();
static void busy(const String& title, const String& what);
static void drawFinishQuestion() {
  using namespace ui;
  screen(tx->routeStopped);
  card(10, 58, 300, 134, kCard);
  text(tx->finishRoute, 22, 68, kTitle, kInk);
  int y = wrap(tx->finishYesHint, 22, 104, 276, kBody, kInk, 150);
  wrap(tx->finishNoHint, 22, y + 4, 276, kBody, kInk, 186);
  String label[3] = {tx->yesWord, tx->laterWord, tx->noWord};
  Pill style[3] = {Pill::Primary, Pill::Neutral, Pill::Primary};
  pills(label, style);
}

static Result askFinish(Robot& r) {
  drawFinishQuestion();
  M5.Speaker.tone(1200, 120);
  waitAllReleased();
  uint32_t t0 = millis();
  int choice = -1;   // 1 Ja, 0 Nee, -1 leave it open
  while (millis() - t0 < 120000 && r.linked() && r.stopQuestion()) {
    M5.update();
    if (M5.BtnA.wasReleased()) { choice = 1; break; }
    if (M5.BtnC.wasReleased()) { choice = 0; break; }
    if (M5.BtnB.wasReleased()) break;
    r.idle(20);
  }
  if (choice < 0 || !r.stopQuestion()) { routeStopped = Stopped::LeftOpen; return Result::Ok; }
  busy(tx->routeStopped, tx->pleaseWait);
  Result res = r.answerStop(choice == 1);
  if (res == Result::Ok) routeStopped = choice == 1 ? Stopped::Continues : Stopped::Cancelled;
  return res;
}

// A route button while a route runs stops it (Erik 2026-11-05, a safety stop): one tap on
// Start/Stop on the driving screen, as on the robot, then the robot's question on the M5.
// If the robot still shows that question (left open earlier), the question again.
// Otherwise it starts the route.
static void startRoute(char letter) {
  if (letter == '-') return;
  routeStopped = Stopped::No;
  withRobot(String(tx->route) + " " + letter, Kind::Route, [letter](Robot& r) {
    if (r.drivingRoute() || r.stopQuestion()) {
      routeStopped = Stopped::Yes;
      if (r.drivingRoute()) {
        Result res = r.stopRoute();
        if (res != Result::Ok) return res;
      }
      return r.stopQuestion() ? askFinish(r) : Result::Ok;
    }
    return r.startRoute(letter);
  });
}

// ---- screen brightness -------------------------------------------------------------
static void wake() {
  if (dimLevel) ui::D.setBrightness(kBright);
  dimLevel = 0;
  lastActive = millis();
}

static void dimWhenIdle() {
  uint32_t quiet = millis() - lastActive;
  if (dimLevel < 1 && quiet > 60000) { ui::D.setBrightness(kDim); dimLevel = 1; }
}

static void waitAllReleased() {
  while (M5.BtnA.isPressed() || M5.BtnB.isPressed() || M5.BtnC.isPressed()) { M5.update(); delay(10); }
  M5.update();
}

// ---- lists: left = next, middle = choose, right = back --------------------------------
static void drawList(const String& title, const std::vector<String>& lines, int sel) {
  using namespace ui;
  screen(title);
  card(10, 58, 300, 134, kCard);
  int first = sel >= 5 ? sel - 4 : 0;   // five rows fit
  int y = 66;
  for (int i = first; i < (int)lines.size() && y < 186; i++) {
    bool cur = i == sel;
    if (cur) D.fillRoundRect(16, y - 2, 288, 24, 8, kRedSoft);
    text(lines[i], 24, y + 2, cur ? kBold : kBody, cur ? kRed : kInk);
    y += 24;
  }
  String label[3] = {tx->nextWord, tx->choose, tx->back};
  Pill style[3] = {Pill::Neutral, Pill::Primary, Pill::Neutral};
  pills(label, style);
}

// The chosen line, or -1 (back, or a minute without a press).
static int chooseFrom(const String& title, const std::vector<String>& lines, int sel) {
  drawList(title, lines, sel);
  uint32_t last = millis();
  while (millis() - last < 60000) {
    M5.update();
    if (M5.BtnA.wasPressed()) { sel = (sel + 1) % (int)lines.size(); drawList(title, lines, sel); last = millis(); }
    if (M5.BtnC.wasPressed()) { waitAllReleased(); return -1; }
    if (M5.BtnB.wasPressed()) { waitAllReleased(); return sel; }
    delay(10);
  }
  return -1;
}

static void busy(const String& title, const String& what) {
  using namespace ui;
  screen(title);
  card(10, 58, 300, 134, kCard);
  wrap(what, 22, 70, 276, kBold, kInk, 180);
}

// A message until a button is pressed. tone: 0 plain, 1 good, 2 wrong.
static void notice(const String& title, const String& what, int tone) {
  using namespace ui;
  screen(title);
  card(10, 58, 300, 134, tone == 1 ? kOkSoft : tone == 2 ? kErrSoft : kCard, tone == 0);
  if (tone == 1) {
    badge(35, 82, true);
    wrap(what, 56, 72, 244, kBold, kOk, 180);
  } else {
    wrap(what, 22, 70, 276, kBold, tone == 2 ? kErr : kInk, 180);
  }
  if (tone) M5.Speaker.tone(tone == 1 ? 2000 : 400, 120);
  hint(tx->pressAnyButton);
  waitForButton();
}

// ---- choose the robot ----------------------------------------------------------------
static std::vector<String> robotLines(const std::vector<FoundRobot>& found) {
  std::vector<String> lines{tx->strongest};
  for (auto& r : found)
    lines.push_back(String(r.name.empty() ? r.address.c_str() : r.name.c_str()) + "  " + String(r.rssi) + " dBm");
  return lines;
}

static void chooseRobot() {
  busy(tx->robotWord, tx->searching);
  std::vector<FoundRobot> found = BleSerialLink::scanAll(5000);
  if (found.size() > 4) found.resize(4);
  if (found.empty()) {
    notice(tx->robotWord, tx->noneFound, 2);
    return;
  }
  int sel = 0;
  for (int i = 0; i < (int)found.size(); i++)
    if (found[i].address == savedAddress) sel = i + 1;
  int c = chooseFrom(tx->robotWord, robotLines(found), sel);
  if (c < 0) return;
  savedAddress = c == 0 ? std::string() : found[c - 1].address;   // empty: the strongest at the next connect
  prefs.putString("addr", savedAddress.c_str());
  M5.Speaker.tone(2000, 100);
  using namespace ui;
  screen(tx->robotWord);
  card(10, 58, 300, 134, kOkSoft, false);
  badge(35, 82, true);
  wrap(tx->robotSaved, 56, 72, 244, kBold, kOk, 116);
  delay(1200);
}

// ---- wifi, set up on the M5 itself (Erik: no phone, no app) ---------------------------
// Settings > Wifi: Network = choose from a scan and type the password with the three
// buttons (TextEditor), WPS = press the router's WPS button. Kept in flash like the PIN;
// WIFI_SSID / WIFI_PASS in config.h are only the first default.
static String wifiSsid, wifiPass;

static bool wifiUp(uint32_t ms = 20000) {
  if (wifiSsid.isEmpty()) return false;
  WiFi.mode(WIFI_STA);
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < ms) delay(100);
  return WiFi.status() == WL_CONNECTED;
}

static void wifiDown() {
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
}

// Connects with these; saves them only if that works.
static void tryWifi(const String& ssid, const String& pass) {
  busy(tx->wifiRow, tx->wifiConnecting);
  String oldSsid = wifiSsid, oldPass = wifiPass;
  wifiSsid = ssid;
  wifiPass = pass;
  bool ok = wifiUp();
  wifiDown();
  if (!ok) {
    wifiSsid = oldSsid;
    wifiPass = oldPass;
    notice(tx->wifiRow, tx->wifiFailed, 2);
    return;
  }
  prefs.putString("wssid", ssid);
  prefs.putString("wpass", pass);
  notice(tx->wifiRow, String(tx->wifiSaved) + "\n" + ssid, 1);
}

// Typing the password: left / right = previous / next slot (held = fast), middle = use it.
static void drawTyping(const TextEditor& e, const String& ssid, bool full) {
  using namespace ui;
  if (full) {
    screen(tx->wifiRow);
    String label[3] = {"<", tx->choose, ">"};
    Pill style[3] = {Pill::Neutral, Pill::Primary, Pill::Neutral};
    pills(label, style);
  }
  card(10, 58, 300, 134, kCard);
  text(String(tx->passwordFor) + " " + ssid, 22, 64, kBody, kMuted);
  String shown = String(e.text().c_str());
  while (shown.length() > 0 && textW(shown + "_", kBold) > 268) shown = shown.substring(1);   // the end if long
  D.fillRoundRect(18, 86, 284, 30, 8, kChip);
  text(shown + "_", 26, 93, kBold, kInk);
  const int w = 54, gap = 4;
  for (int k = -2; k <= 2; k++) {
    int i = (e.cursor() + k + e.size()) % e.size();
    bool cur = k == 0;
    int x = 160 + k * (w + gap) - w / 2;
    D.fillRoundRect(x, 124, w, 42, 10, cur ? kRedSoft : kCard);
    D.drawRoundRect(x, 124, w, 42, 10, cur ? kRed : kLine);
    if (cur) D.drawRoundRect(x + 1, 125, w - 2, 40, 9, kRed);
    std::string l = e.label(i);
    String s = l == " " ? String(tx->spaceWord) : String(l.c_str());
    Font f = l.size() == 1 && l != " " ? kTitle : kBold;
    D.setFont(f);
    centred(s, x + w / 2, 145 - D.fontHeight() / 2, f, cur ? kRed : kInk);
  }
  text(tx->holdToCancel, 22, 172, kBody, kMuted);
}

static bool typePassword(const String& ssid, String& out) {
  TextEditor e;
  drawTyping(e, ssid, true);
  uint32_t middleDown = 0, lastInput = millis(), heldA = 0, heldC = 0, lastRepeat = 0;
  for (;;) {
    M5.update();
    uint32_t now = millis();
    bool changed = false;
    if (M5.BtnA.wasPressed()) { e.prev(); heldA = now; changed = true; }
    if (M5.BtnC.wasPressed()) { e.next(); heldC = now; changed = true; }
    if (heldA && M5.BtnA.isPressed() && now - heldA > 450 && now - lastRepeat > 90) { e.prev(); lastRepeat = now; changed = true; }
    if (heldC && M5.BtnC.isPressed() && now - heldC > 450 && now - lastRepeat > 90) { e.next(); lastRepeat = now; changed = true; }
    if (!M5.BtnA.isPressed()) heldA = 0;
    if (!M5.BtnC.isPressed()) heldC = 0;
    if (M5.BtnB.wasPressed()) { middleDown = now; lastInput = now; }
    if (now - lastInput > 180000 || (middleDown && M5.BtnB.isPressed() && now - middleDown >= 1500)) {
      waitAllReleased();
      return false;
    }
    if (M5.BtnB.wasReleased() && middleDown) {
      middleDown = 0;
      if (e.press()) {
        out = String(e.text().c_str());
        return true;
      }
      changed = true;
    }
    if (changed) {
      lastInput = now;
      drawTyping(e, ssid, false);
    }
    delay(10);
  }
}

static void chooseNetwork() {
  busy(tx->wifiRow, tx->scanningWifi);
  WiFi.mode(WIFI_STA);
  struct Net {
    String ssid;
    int rssi;
    bool open;
  };
  std::vector<Net> nets;
  int n = WiFi.scanNetworks();
  for (int i = 0; i < n; i++) {
    String s = WiFi.SSID(i);
    if (s.isEmpty()) continue;   // hidden network
    bool seen = false;
    for (auto& x : nets) seen = seen || x.ssid.c_str() == std::string(s.c_str());
    if (!seen) nets.push_back({s, (int)WiFi.RSSI(i), WiFi.encryptionType(i) == WIFI_AUTH_OPEN});
  }
  WiFi.scanDelete();
  wifiDown();
  std::sort(nets.begin(), nets.end(), [](const Net& a, const Net& b) { return a.rssi > b.rssi; });
  if (nets.size() > 8) nets.resize(8);
  if (nets.empty()) {
    notice(tx->wifiRow, tx->noNetworks, 2);
    return;
  }
  std::vector<String> lines;
  int sel = 0;
  for (int i = 0; i < (int)nets.size(); i++) {
    lines.push_back(nets[i].ssid + "  " + String(nets[i].rssi) + " dBm");
    if (nets[i].ssid.c_str() == std::string(wifiSsid.c_str())) sel = i;
  }
  int c = chooseFrom(tx->networkWord, lines, sel);
  if (c < 0) return;
  String pass;
  if (!nets[c].open && !typePassword(nets[c].ssid, pass)) return;
  tryWifi(nets[c].ssid, pass);
}

// WPS push button: the router hands over its network and password.
static volatile int wpsResult = 0;   // 0 waiting, 1 got it, 2 failed
static void onWps(arduino_event_id_t e) {
  if (e == ARDUINO_EVENT_WPS_ER_SUCCESS) wpsResult = 1;
  else if (e == ARDUINO_EVENT_WPS_ER_FAILED || e == ARDUINO_EVENT_WPS_ER_TIMEOUT || e == ARDUINO_EVENT_WPS_ER_PIN) wpsResult = 2;
}

static void wpsSetup() {
  using namespace ui;
  busy(tx->wpsWord, tx->wpsPress);
  hint(tx->pressToStop);
  WiFi.mode(WIFI_STA);
  wpsResult = 0;
  int handler = WiFi.onEvent(onWps);
  esp_wps_config_t cfg;
  std::memset(&cfg, 0, sizeof cfg);
  cfg.wps_type = WPS_TYPE_PBC;
  std::strncpy(cfg.factory_info.manufacturer, "Scraper Control", sizeof cfg.factory_info.manufacturer - 1);
  std::strncpy(cfg.factory_info.model_number, "1", sizeof cfg.factory_info.model_number - 1);
  std::strncpy(cfg.factory_info.model_name, "Scraper-knop", sizeof cfg.factory_info.model_name - 1);
  std::strncpy(cfg.factory_info.device_name, "scraper-knop", sizeof cfg.factory_info.device_name - 1);
  bool started = esp_wifi_wps_enable(&cfg) == ESP_OK && esp_wifi_wps_start(0) == ESP_OK;
  bool cancelled = false;
  uint32_t t0 = millis();
  while (started && wpsResult == 0 && millis() - t0 < 125000) {
    M5.update();
    if (M5.BtnA.wasPressed() || M5.BtnB.wasPressed() || M5.BtnC.wasPressed()) { cancelled = true; break; }
    delay(20);
  }
  esp_wifi_wps_disable();
  WiFi.removeEvent(handler);
  waitAllReleased();
  if (wpsResult == 1) {
    busy(tx->wpsWord, tx->wifiConnecting);
    WiFi.begin();   // with what the router gave
    uint32_t t1 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t1 < 20000) delay(100);
    if (WiFi.status() == WL_CONNECTED) {
      String s = WiFi.SSID(), p = WiFi.psk();
      wifiDown();
      wifiSsid = s;
      wifiPass = p;
      prefs.putString("wssid", s);
      prefs.putString("wpass", p);
      notice(tx->wpsWord, String(tx->wifiSaved) + "\n" + s, 1);
      return;
    }
  }
  wifiDown();
  if (!cancelled) notice(tx->wpsWord, tx->wpsFailed, 2);
}

// ---- the M5's own updates: signed releases on GitHub ----------------------------------
// Settings > Wifi > Update: reads firmware.txt of the latest release (Manifest.h), checks
// the signature with ReleaseKey.h, and only then downloads firmware.bin into the other app
// slot, checking its SHA-256 on the way. Anything wrong: the current version stays.
// Holding Update 1.5 s instead waits for an update from a Mac (pio, m5stack-core-ota).
static const char kReleaseUrl[] = "https://github.com/" FW_REPO "/releases/latest/download/";

// One line of the response, without "\r\n" ("" at the end of the headers or on a timeout).
static String readLine(WiFiClientSecure& c, uint32_t ms = 15000) {
  String s;
  uint32_t t0 = millis();
  while (millis() - t0 < ms) {
    if (c.available()) {
      char ch = (char)c.read();
      if (ch == '\n') return s;
      if (ch != '\r') s += ch;
    } else if (!c.connected()) {
      break;
    } else {
      delay(2);
    }
  }
  return s;
}

// GET over HTTPS (HTTP/1.0: no chunked replies), following up to 5 redirects (GitHub sends
// release downloads on to its file servers). On true `client` is at the body, `length` the
// Content-Length (-1 if not given).
static bool httpsGet(WiFiClientSecure& client, std::string url, long& length) {
  for (int hop = 0; hop < 5; hop++) {
    std::string host, path, value, location;
    if (!parseUrl(url, host, path)) return false;
    client.stop();
    client.setInsecure();   // trust comes from the signature, not from the connection
    if (!client.connect(host.c_str(), 443)) return false;
    client.print(String("GET ") + path.c_str() + " HTTP/1.0\r\nHost: " + host.c_str() +
                 "\r\nUser-Agent: scraper-knop\r\nConnection: close\r\n\r\n");
    int code = statusCode(readLine(client).c_str());
    length = -1;
    for (;;) {
      String line = readLine(client);
      if (line.isEmpty()) break;
      if (headerValue(line.c_str(), "Location", value)) location = value;
      if (headerValue(line.c_str(), "Content-Length", value)) length = std::atol(value.c_str());
    }
    if (isRedirect(code) && !location.empty()) {
      url = location;
      continue;
    }
    return code == 200;
  }
  return false;
}

static bool fetchText(const String& url, String& out) {
  WiFiClientSecure client;
  long length = -1;
  bool ok = httpsGet(client, url.c_str(), length);
  uint32_t t0 = millis();
  while (ok && (length < 0 || (long)out.length() < length) && millis() - t0 < 15000) {
    if (client.available()) {
      out += (char)client.read();
      if (out.length() > 4096) break;   // a manifest is a few hundred bytes
    } else if (!client.connected()) {
      break;
    } else {
      delay(2);
    }
  }
  client.stop();
  return ok;
}

static bool signatureOk(const Manifest& m) {
  std::vector<uint8_t> sig;
  if (!fromHex(m.sig, sig)) return false;
  std::string msg = signedMessage(m);
  unsigned char hash[32];
  mbedtls_sha256((const unsigned char*)msg.data(), msg.size(), hash, 0);
  mbedtls_pk_context pk;
  mbedtls_pk_init(&pk);
  bool ok = mbedtls_pk_parse_public_key(&pk, (const unsigned char*)kReleaseKey, sizeof kReleaseKey) == 0 &&
            mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, hash, sizeof hash, sig.data(), sig.size()) == 0;
  mbedtls_pk_free(&pk);
  return ok;
}

// 0 installed (boots next time), 1 failed, 2 not the signed file, 3 stopped by a button
static int download(const Manifest& m, bool stopOnPress = false) {
  using namespace ui;
  WiFiClientSecure client;
  long length = -1;
  if (!httpsGet(client, String(String(kReleaseUrl) + "firmware.bin").c_str(), length) ||
      (length >= 0 && length != m.size) || !Update.begin((size_t)m.size)) {
    client.stop();
    return 1;
  }
  WiFiClientSecure* s = &client;
  mbedtls_sha256_context sha;
  mbedtls_sha256_init(&sha);
  mbedtls_sha256_starts(&sha, 0);
  static uint8_t buf[2048];
  long done = 0, shown = -1;
  uint32_t last = millis();
  while (done < m.size && millis() - last < 20000) {
    if (stopOnPress) {
      M5.update();
      if (M5.BtnA.wasPressed() || M5.BtnB.wasPressed() || M5.BtnC.wasPressed()) {
        mbedtls_sha256_free(&sha);
        client.stop();
        Update.abort();
        return 3;
      }
    }
    int avail = s->available();
    if (avail <= 0) {
      if (!client.connected()) break;
      delay(2);
      continue;
    }
    size_t want = std::min<size_t>(sizeof buf, std::min<size_t>((size_t)avail, (size_t)(m.size - done)));
    size_t n = s->readBytes(buf, want);
    if (n == 0) continue;
    mbedtls_sha256_update(&sha, buf, n);
    if (Update.write(buf, n) != n) break;
    done += (long)n;
    last = millis();
    if (done / 32768 != shown) {
      shown = done / 32768;
      progressBar(22, 162, 276, int(done / 1024), int(m.size / 1024));
    }
  }
  unsigned char hash[32];
  mbedtls_sha256_finish(&sha, hash);
  mbedtls_sha256_free(&sha);
  client.stop();
  if (done != m.size) {
    Update.abort();
    return 1;
  }
  if (toHex(hash, sizeof hash) != m.sha256) {
    Update.abort();
    return 2;
  }
  return Update.end(true) ? 0 : 1;
}

static bool offerUpdate(bool quiet);
static void selfUpdate() {
  using namespace ui;
  if (wifiSsid.isEmpty()) {
    notice(tx->updateTitle, tx->noWifi, 2);
    return;
  }
  busy(tx->updateTitle, tx->wifiConnecting);
  if (!wifiUp()) {
    wifiDown();
    notice(tx->updateTitle, tx->wifiFailed, 2);
    return;
  }
  offerUpdate(false);
}

// Wifi is up: check the latest release and offer a newer one (Install / Back). quiet: say
// nothing when there is none (the settings menu shows a line instead).
// Returns false only when quiet and nothing was shown; wifi is down afterwards.
static bool offerUpdate(bool quiet) {
  using namespace ui;
  busy(tx->updateTitle, tx->checkingUpdate);
  String body;
  bool got = fetchText(String(kReleaseUrl) + "firmware.txt", body);
  Manifest m = parseManifest(body.c_str());
  String own = String(FW_VERSION) + " (" + String(FW_BUILD) + ")";
  if (!got || !m.complete()) {
    wifiDown();
    notice(tx->updateTitle, tx->noUpdateInfo, 2);
    return true;
  }
  if (!isUpdate(m, FW_BOARD, FW_BUILD)) {
    wifiDown();
    if (quiet) return false;
    notice(tx->updateTitle, String(tx->upToDate) + "\n" + tx->versionRow + " " + own, 0);
    return true;
  }
  if (!signatureOk(m)) {
    wifiDown();
    notice(tx->updateTitle, tx->notTrusted, 2);
    return true;
  }
  screen(tx->updateTitle);
  card(10, 58, 300, 134, kCard);
  int y = wrap(String(tx->newVersion) + " " + m.version.c_str() + " (" + String((int)m.build) + ")", 22, 70, 276, kBold, kInk, 120);
  text(String(tx->versionRow) + " " + own, 22, y + 8, kBody, kMuted);
  String label[3] = {"", tx->install, tx->back};
  Pill style[3] = {Pill::Neutral, Pill::Primary, Pill::Neutral};
  pills(label, style);
  bool go = false;
  uint32_t t0 = millis();
  while (millis() - t0 < 60000) {
    M5.update();
    if (M5.BtnB.wasPressed()) { go = true; break; }
    if (M5.BtnA.wasPressed() || M5.BtnC.wasPressed()) break;
    delay(10);
  }
  waitAllReleased();
  if (!go) {
    wifiDown();
    return true;
  }
  busy(tx->updateTitle, tx->downloading);
  int r = download(m);
  wifiDown();
  if (r != 0) {
    notice(tx->updateTitle, r == 2 ? tx->notTrusted : tx->updateFailed, 2);
    return true;
  }
  prefs.putString("updated", m.version.c_str());
  notice(tx->updateTitle, tx->restarting, 1);
  ESP.restart();
  return true;   // not reached: ESP.restart() does not return, but the compiler does not know that
}

// ---- automatic updates (Erik 2026-11-11) ----------------------------------------------
// An idle M5 tries its saved wifi every 10 minutes, in the background (buttons keep working).
// On every new connection (a phone's hotspot in a barn without wifi) and otherwise once a
// day it checks the latest release; a newer signed version is installed at once and the M5
// restarts. Never while in use (2 minutes after the last press), never while a planning is
// still owed to the robot (that is only in RAM); a button press stops a download.
static const uint32_t kWifiTryEveryMs = 10UL * 60 * 1000, kCheckEveryMs = 24UL * 3600 * 1000;
static uint32_t lastWifiTry = 0, lastCheck = 0, autoStarted = 0;
static bool autoConnecting = false, wifiWasUp = false, checkedOnce = false;

static void autoStop() {
  if (!autoConnecting) return;
  wifiDown();
  autoConnecting = false;
}

static void installIfNewer() {
  String body;
  if (!fetchText(String(kReleaseUrl) + "firmware.txt", body)) return;
  Manifest m = parseManifest(body.c_str());
  if (!isUpdate(m, FW_BOARD, FW_BUILD) || !signatureOk(m)) return;
  int dim = dimLevel;
  if (dim) ui::D.setBrightness(kDim);
  busy(tx->updateTitle, String(tx->newVersion) + " " + m.version.c_str() + "\n" + tx->autoUpdating);
  int r = download(m, true);
  if (r == 0) {
    prefs.putString("updated", m.version.c_str());
    wifiDown();
    ESP.restart();
  }
  waitAllReleased();
  if (dim) ui::D.setBrightness(kDim);
  idle();
}

static void autoUpdateTick() {
  uint32_t now = millis();
  bool inUse = now - lastActive < 120000;
  bool owed = owedPlanning >= 0 && now - owedAt < kOwedForMs;
  if (wifiSsid.isEmpty() || inUse || owed) {
    autoStop();
    return;
  }
  if (!autoConnecting) {
    if (lastWifiTry && now - lastWifiTry < kWifiTryEveryMs) return;
    lastWifiTry = now;
    WiFi.mode(WIFI_STA);
    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
    autoConnecting = true;
    autoStarted = now;
    return;
  }
  if (WiFi.status() == WL_CONNECTED) {
    bool due = !wifiWasUp || !checkedOnce || now - lastCheck >= kCheckEveryMs;
    wifiWasUp = true;
    if (due) {
      lastCheck = now;
      checkedOnce = true;
      installIfNewer();   // restarts the M5 when it installed one
    }
    autoStop();
  } else if (now - autoStarted > 20000) {
    autoStop();
    wifiWasUp = false;   // the next connection is a new one: check then
  }
}

// ---- update from a Mac (ArduinoOTA): pio run -e m5stack-core-ota -t upload --------------
static void otaUpdate() {
  using namespace ui;
  if (wifiSsid.isEmpty()) {
    notice(tx->updateTitle, tx->noWifi, 2);
    return;
  }
  busy(tx->updateTitle, tx->wifiConnecting);
  if (!wifiUp()) {
    wifiDown();
    notice(tx->updateTitle, tx->wifiFailed, 2);
    return;
  }
  ArduinoOTA.setHostname("scraper-knop");
  if (OTA_PASS[0]) ArduinoOTA.setPassword(OTA_PASS);
  ArduinoOTA.onStart([] { busy(tx->updateTitle, tx->updating); });
  ArduinoOTA.onProgress([](unsigned done, unsigned total) { progressBar(22, 162, 276, int(done / 1024), int(total / 1024)); });
  ArduinoOTA.begin();
  screen(tx->updateTitle);
  card(10, 58, 300, 134, kCard);
  int y = wrap(tx->readyForUpdate, 22, 70, 276, kBold, kInk, 120);
  text(String("scraper-knop.local"), 22, y + 6, kBody, kMuted);
  text(String(WiFi.localIP().toString().c_str()), 22, y + 30, kBold, kInk);
  hint(tx->pressToStop);
  uint32_t until = millis() + 600000;   // 10 minutes
  while ((int32_t)(millis() - until) < 0) {
    ArduinoOTA.handle();   // an update reboots the M5 when it is done
    M5.update();
    if (M5.BtnA.wasPressed() || M5.BtnB.wasPressed() || M5.BtnC.wasPressed()) break;
    delay(5);
  }
  waitAllReleased();
  ArduinoOTA.end();
  wifiDown();
}

// ---- settings > wifi: left = network, middle = WPS, right = update (held = from a Mac) ---
static void drawWifi() {
  using namespace ui;
  screen(tx->wifiRow);
  card(10, 58, 300, 134, kCard);
  text(tx->networkWord, 22, 70, kBody, kMuted);
  text(wifiSsid.isEmpty() ? String(tx->notSet) : wifiSsid, 110, 70, kBold, kInk);
  text(tx->versionRow, 22, 96, kBody, kMuted);
  text(String(FW_VERSION) + " (" + String(FW_BUILD) + ")", 110, 96, kBold, kInk);
  String label[3] = {tx->networkWord, tx->wpsWord, tx->updateWord};
  Pill style[3] = {Pill::Neutral, Pill::Neutral, Pill::Neutral};
  pills(label, style);
}

static void wifiMenu() {
  waitAllReleased();
  drawWifi();
  uint32_t start = millis(), rightDown = 0;
  while (millis() - start < 20000) {
    M5.update();
    if (M5.BtnA.wasReleased()) { chooseNetwork(); return; }
    if (M5.BtnB.wasReleased()) { wpsSetup(); return; }
    if (M5.BtnC.wasPressed()) rightDown = millis();
    if (rightDown && M5.BtnC.isPressed() && millis() - rightDown >= 1500) {
      M5.Speaker.tone(1500, 60);
      waitAllReleased();
      otaUpdate();
      return;
    }
    if (rightDown && M5.BtnC.wasReleased()) { selfUpdate(); return; }
    delay(10);
  }
}

// ---- settings (right held 1.5 s): left = PIN, middle = robot, right = wifi ---------------
static void drawSettings(const char* line = nullptr) {
  using namespace ui;
  screen(tx->settings);
  card(10, 58, 300, 134, kCard);
  text(tx->robotRow, 22, 70, kBody, kMuted);
  text(savedAddress.empty() ? String(tx->automatic) : String(savedAddress.c_str()), 110, 70, kBold, kInk);
  text(tx->wifiRow, 22, 96, kBody, kMuted);
  text(wifiSsid.isEmpty() ? String(tx->notSet) : wifiSsid, 110, 96, kBold, kInk);
  text(tx->versionRow, 22, 122, kBody, kMuted);
  text(String(FW_VERSION) + " (" + String(FW_BUILD) + ")", 110, 122, kBold, kInk);
  if (line) text(line, 22, 158, kBody, kMuted);
  String label[3] = {tx->pinTitle, tx->robotWord, tx->wifiRow};
  Pill style[3] = {Pill::Neutral, Pill::Neutral, Pill::Neutral};
  pills(label, style);
}

// Opening the settings also looks for the saved wifi at once (Erik 2026-11-11): turn on a
// phone's hotspot, open the settings, and a newer version is offered (Install / Back).
// Not while a planning is still owed to the robot. A button stops the search.
static void settingsMenu() {
  waitAllReleased();
  autoStop();
  bool owed = owedPlanning >= 0 && millis() - owedAt < kOwedForMs;
  bool looking = !wifiSsid.isEmpty() && !owed;
  if (looking) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  }
  drawSettings(looking ? tx->settingsWifiLooking : nullptr);
  uint32_t start = millis();
  while (millis() - start < 20000) {
    M5.update();
    bool pressed = M5.BtnA.wasReleased() || M5.BtnB.wasReleased() || M5.BtnC.wasReleased();
    if (pressed && looking) { wifiDown(); looking = false; }
    if (M5.BtnA.wasReleased()) { editPin(tx->newPin); break; }
    if (M5.BtnB.wasReleased()) { chooseRobot(); break; }
    if (M5.BtnC.wasReleased()) { wifiMenu(); break; }
    if (looking && WiFi.status() == WL_CONNECTED) {
      looking = false;
      lastCheck = millis();
      checkedOnce = wifiWasUp = true;
      if (offerUpdate(true)) break;   // an update was offered (installed or not)
      drawSettings(tx->settingsUpToDate);
      start = millis();
    } else if (looking && millis() - start > 15000) {
      looking = false;
      wifiDown();
      drawSettings(tx->settingsWifiNone);
      start = millis();
    }
    delay(10);
  }
  if (looking) wifiDown();
  idle();
}

void setup() {
  auto cfg = M5.config();
  M5.begin(cfg);
  Serial.begin(115200);
  prefs.begin(DRIVER_STORE, false);
  if (prefs.getBytesLength("imgver") == 9) prefs.getBytes("imgver", storedVersion, 9);
  savedAddress = prefs.getString("addr", "").c_str();
  pin = prefs.getString("pin", ROBOT_PIN);
  wifiSsid = prefs.getString("wssid", WIFI_SSID);
  wifiPass = prefs.getString("wpass", WIFI_PASS);
  tx = &textsFor(prefs.getString("lang", "nl").c_str());
  M5.Display.setBrightness(kBright);
  wake();
  if (pin.isEmpty() && editPin(tx->enterPin)) M5.Speaker.tone(2000, 150);
  String updated = prefs.getString("updated", "");
  if (!updated.isEmpty()) {   // first start after an update
    prefs.putString("updated", "");
    using namespace ui;
    screen(tx->updateTitle);
    card(10, 58, 300, 134, kOkSoft, false);
    badge(35, 82, true);
    wrap(String(tx->updatedTo) + " " + updated, 56, 72, 244, kBold, kOk, 180);
    delay(3000);
  }
  idle();
}

void loop() {
  // Short press: acts on release (a route). Held 1.5 s: acts at once, while the button is
  // still down (Erik 2026-11-06), with a short beep; its release is then ignored.
  // Middle = manual drive, left switches the planning, right opens the PIN screen.
  static uint32_t pressedAt[3] = {0, 0, 0};
  M5.update();
  m5::Button_Class* btn[3] = {&M5.BtnA, &M5.BtnB, &M5.BtnC};
  bool any = M5.BtnA.wasPressed() || M5.BtnB.wasPressed() || M5.BtnC.wasPressed();
  if (any) {
    autoStop();   // the robot (Bluetooth) goes first
    wake();
  }
  dimWhenIdle();
  autoUpdateTick();
  const char routes[3] = {ROUTE_BTN_A, ROUTE_BTN_B, ROUTE_BTN_C};
  for (int i = 0; i < 3; i++) {
    if (btn[i]->wasPressed()) pressedAt[i] = millis();
    if (!pressedAt[i]) continue;
    bool longPress = btn[i]->isPressed() && millis() - pressedAt[i] >= 1500;
    if (!longPress && !btn[i]->wasReleased()) continue;
    pressedAt[i] = 0;
    if (!longPress) startRoute(routes[i]);
    else {
      M5.Speaker.tone(1500, 60);
      if (i == 1) withRobot(tx->manual, Kind::Manual, manualSession);
      else if (i == 0) togglePlanning();
      else settingsMenu();
    }
    wake();   // the result screen stays readable
    // Ignore anything pressed while we were busy.
    M5.update();
    for (auto& t : pressedAt) t = 0;
    return;
  }
  delay(10);
}
