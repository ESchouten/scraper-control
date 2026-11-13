// Scraper Control buttons for M5Stack Basic: left / middle / right button start route A / B / C.
// Each press: connect (~2 s), start the route with all checks, disconnect.
// Hold the middle button 1.5 s for manual drive (HANDBEDIENING), see manualSession().
// The screens follow the Scraper Control app's look (red header, light cards), drawn with LVGL (Ui.h).
// Hold the left button 1.5 s to switch the planning (tijdpad) on or off.
// Hold the right button 1.5 s for the settings: PIN, which robot, wifi (set up on the M5
// itself, or WPS) and updates (signed releases from GitHub).
// A rejected PIN opens the PIN screen by itself.
// The screen dims after a minute and never goes off, so a press always acts at once.
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
#include "BtLink.h"
#include "TextEditor.h"
#include "Texts.h"
#include "Ui.h"

#ifndef FW_VERSION  // set by version.py (PlatformIO): the apps' version (1.6.120) and its build (120)
#define FW_VERSION "dev"
#define FW_BUILD 0
#endif
#define FW_BOARD "m5stack-core"
#define FW_REPO "ESchouten/scraper-control"   // where the signed releases are
static const char kRoutes[3] = {'A', 'B', 'C'};   // the route of the left, middle and right button

using namespace driver;

static Preferences prefs;
static uint8_t storedVersion[9] = {'D', 'I', 'S', '0', '0', '1', '8', 'T', '4'};   // learned 2026-11-03
static std::string savedAddress;
static bool savedClassic = false;   // the robot was found over classic Bluetooth
static String pin;                 // from flash; set on the PIN screen
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
// The robot as the M5 last saw it (RAM only): the home screen shows it, with its age.
// Read at every command and every 15 minutes in the background (syncTick).
static Status seen;
static uint32_t seenAt = 0, homeDrawnAt = 0;
static bool haveSeen = false, syncing = false;

// Screen: dims after a minute without a press, never off (Erik 2026-11-11), so a press always
// acts at once (a stop too). The backlight is far from linear: 64 still looked full, so dim low.

static const uint8_t kBright = 160, kDim = 20;
static const uint32_t kResultMs = 20000;   // a result closes by itself after this

// Every hold action works the same (home, manual, PIN, password, wifi): it fires at once when
// its button has been down ui::kHoldMs (`since` = when it went down, 0 = not down), while the
// button is still held, with a short beep. Its hold key on screen is full at that moment.
static bool holdFired(m5::Button_Class& b, uint32_t since) {
  if (!since || !b.isPressed() || millis() - since < ui::kHoldMs) return false;
  M5.Speaker.tone(1500, 60);
  return true;
}
static uint32_t lastActive = 0;
static int dimLevel = 0;   // 0 bright, 1 dim


// ---- screens -----------------------------------------------------------------------
// "HH:MM..." -> minutes since midnight, -1 if not a time.
static int minutesOf(const std::string& t) {
  if (t.size() < 5 || t[2] != ':' || !isdigit(t[0]) || !isdigit(t[1]) || !isdigit(t[3]) || !isdigit(t[4])) return -1;
  return ((t[0] - '0') * 10 + t[1] - '0') * 60 + (t[3] - '0') * 10 + t[4] - '0';
}

// The next ride as last seen, or "" once it has passed (the robot's clock, run on with millis).
static String nextRideNow() {
  int next = minutesOf(seen.nextRide), clock = minutesOf(seen.clock);
  if (next < 0) return "";
  if (clock < 0) return seen.nextRide.c_str();
  int waited = (next - clock + 1440) % 1440;   // minutes from the reading to the ride
  int since = int((millis() - seenAt) / 60000);
  return since < waited ? String(seen.nextRide.substr(0, 5).c_str()) : String();
}

static String ago(uint32_t ms) {
  char b[32];
  uint32_t min = ms / 60000;
  if (min < 1) return tx->justNow;
  if (min < 60) snprintf(b, sizeof b, tx->minAgo, int(min));
  else snprintf(b, sizeof b, tx->hourAgo, int(min / 60));
  return b;
}

// A settings row: icon in a soft square, the label small above the value. Rows 0..2.
static void settingRow(int i, const char* icon, const String& label, const String& value) {
  using namespace ui;
  int y = 64 + i * 42;
  if (i) box(22, y - 5, 276, 1, 0, kLine);
  box(22, y + 2, 30, 30, 9, kRedSoft);
  centred(icon, 37, y + 8, kBold, kRed);
  text(label, 62, y, kBody, kMuted);
  String v = value;
  if (textW(v, kBold) > 232) {   // too long: cut and end in "..."
    auto last = [&] { return v.c_str()[v.length() - 1]; };
    while (v.length() > 1 && (textW(v + "...", kBold) > 232 || last() == '.' || last() == ' '))
      v = v.substring(0, v.length() - 1);
    v += "...";
  }
  text(v, 62, y + 17, kBold, kInk);
}

static String volts(int mv) { return String(mv / 1000) + (tx == &kNl ? "," : ".") + String(mv % 1000 / 100) + " V"; }

// Home: the robot as last seen (status only, no instructions), the three hold keys (outlined,
// they fill up while held) and the three routes (red: a tap starts or stops that route).
// The memory margin in the log (Bluetooth, wifi and the screen share little RAM).
static void logHeap(const char* where) {
  Serial.printf("heap %s: %u free, %u largest\n", where, (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMaxAllocHeap());
}

static void idle() {
  using namespace ui;
  static uint32_t heapAt = 0;
  if (!heapAt || millis() - heapAt > 60000) { heapAt = millis() | 1; logHeap("home"); }
  homeDrawnAt = millis();
  ui::Screen scr("Scraper Control");
  card(10, 56, 300, 54, kCard);
  if (haveSeen) {
    bool on = seen.planning == 1;
    dot(27, 72, 5, on ? kOk : kMuted);
    text(String(tx->planning) + " " + (on ? tx->planningOnWord : tx->planningOffWord), 40, 61, kBold, kInk);
    if (seen.batteryMv > 0) {
      String v = volts(seen.batteryMv);
      int w = textW(v, kBody) + textW(ICON_BATTERY, kBody) + 6;
      iconText(ICON_BATTERY, v, 298 - w, 62, kBody, kMuted, kMuted, 6);
    }
    String next = nextRideNow();
    String a = syncing ? String(ICON_SYNC) : ago(millis() - seenAt);
    int aw = textW(a, kBody);
    if (next.length()) {   // drop the word "next run" when the time and the age would touch
      String n = String(tx->nextRide) + " " + next;
      if (20 + textW(ICON_CLOCK, kBody) + 4 + textW(n, kBody) > 298 - aw - 12) n = next;
      iconText(ICON_CLOCK, n, 20, 84, kBody, kMuted, kMuted, 4);
    }
    text(a, 298 - aw, 84, kBody, syncing ? kRed : kMuted);
  } else {
    disc(20, 64, 38, syncing ? ICON_SYNC : ICON_ROBOT, kRedSoft, kRed);
    text(tx->notReadYet, 68, 61, kBold, kInk);
    text(tx->readEvery, 68, 83, kBody, kMuted);
  }
  const char* hold[3] = {tx->holdPlanning, tx->holdManual, tx->holdPin};
  const char* icon[3] = {ICON_CALENDAR, ICON_HAND, ICON_GEAR};
  for (int i = 0; i < 3; i++) holdKey(i, 116, 64, tx->holdWord, icon[i], hold[i]);
  String label[3];
  Pill style[3] = {Pill::Primary, Pill::Primary, Pill::Primary};
  for (int i = 0; i < 3; i++) label[i] = String(ICON_PLAY) + "  " + kRoutes[i];
  pills(label, style, 192, 42, kTitle);
}

// What a command is doing, with a progress bar (same steps and words as the app).
enum class Kind { Status, Planning, Route, Manual };

static const char* nextStep(Kind job, int last, bool planningOn) {
  switch (last) {
    case 0: return tx->connecting;
    case int(Step::Linked): return tx->checkingPin;
    case int(Step::Granted): return tx->syncing;
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
  ui::Screen scr(title);
  card(10, 56, 300, 136, kCard);
  spinner(24, 70, 42);
  int y = wrap(now, 80, 68, 220, kTitle, kInk, 126);
  text(String(tx->stepOf) + " " + String(done + 1 > total ? total : done + 1) + " " + tx->of + " " + String(total), 80, y + 2, kBody, kMuted);
  progressBar(24, 164, 272, done, total);
}

// Only the fields the robot's current screen shows (while driving, planning and
// next ride are not on it).
static void statusRows(const Status& s, int y, int maxY) {
  using namespace ui;
  auto row = [&](const char* icon, const char* label, const String& value) {
    if (y + 20 > maxY) return;
    iconText(icon, label, 24, y, kBody, kMuted, kMuted, 6);
    text(value, 160, y, kBold, kInk);
    y += 22;
  };
  if (s.planning == 1 || s.planning == 0) row(ICON_CALENDAR, tx->planning, s.planning == 1 ? tx->on : tx->off);
  if (!s.nextRide.empty()) row(ICON_CLOCK, tx->nextRide, s.nextRide.c_str());
  if (!s.route.empty()) row(ICON_ROUTE, tx->routeRow, s.route.c_str());
  if (!s.action.empty()) row(ICON_PLAY, tx->action, s.action.c_str());
  if (s.batteryMv > 0) row(ICON_BATTERY, tx->robotBattery, volts(s.batteryMv));
}

static void drawResult(const String& title, bool ok, const String& heading, const Status& st, const String& detail) {
  using namespace ui;
  ui::Screen scr(title);
  card(10, 56, 300, 136, ok ? kOkSoft : kErrSoft, false);
  disc(22, 66, 40, ok ? ICON_OK : ICON_WARNING, ok ? kOk : kErr);
  int y = wrap(heading, 74, 66, 226, kTitle, ok ? kOk : kErr, 116);
  if (ok) statusRows(st, y < 112 ? 112 : y + 4, 190);
  else wrap(detail, 24, y < 108 ? 108 : y + 2, 272, kBody, kInk, 192);   // four lines fit under a one-line heading
  closeHint(tx->pressAnyButton, kResultMs);
}

// ---- manual drive screen ---------------------------------------------------------
static const uint32_t kDriveAfterMs = 400;   // middle held this long: drive (while held)
// Three tiles above the buttons with what each one switches, the drive state below.
static void drawManual(const ManualState& m, bool driving) {
  using namespace ui;
  ui::Screen scr(tx->manual);
  const char* name[3] = {tx->left, tx->direction, tx->right};
  int val[3] = {m.left, m.forward, m.right};
  for (int i = 0; i < 3; i++) {
    bool on = i == 1 ? val[i] >= 0 : val[i] == 1;
    String v = val[i] < 0 ? String("?") : i == 1 ? String(val[i] ? tx->forward : tx->backward) : String(val[i] ? tx->on : tx->off);
    int x = kBtnX[i] - 47;
    bool green = on && i != 1;
    box(x, 56, 94, 70, 12, green ? kOkSoft : kCard, green ? kOk : kLine, green ? 2 : 1);
    centred(name[i], kBtnX[i], 62, kBody, kMuted);
    const char* ic = i == 1 ? (val[i] < 0 ? "" : val[i] ? ICON_UP : ICON_DOWN) : (on ? ICON_BOLT : ICON_POWER);
    if (*ic) centred(ic, kBtnX[i], 82, kBold, green ? kOk : i == 1 ? kRed : kMuted);
    centred(v, kBtnX[i], 101, kBold, green ? kOk : kInk);
  }
  if (driving) {   // only one thing matters now
    lv_obj_t* b = box(10, 134, 300, 60, 14, kRed);
    lv_obj_set_style_bg_grad_color(b, col(kRedDeep), 0);
    lv_obj_set_style_bg_grad_dir(b, LV_GRAD_DIR_VER, 0);
    centred(String(ICON_PLAY) + "  " + tx->driving, kW / 2, 141, kTitle, kWhite);
    centred(tx->releaseToStop, kW / 2, 168, kBody, kWhite);
  } else {   // hold keys, as on the home screen: left held = done, middle held = drive
    holdKey(0, 134, 60, tx->holdWord, ICON_OK, tx->done);
    holdKey(1, 134, 60, tx->holdWord, ICON_PLAY, tx->drive, kDriveAfterMs);
  }
  String label[3] = {tx->motorL, tx->direction, tx->motorR};
  Pill style[3] = {Pill::Primary, Pill::Primary, Pill::Primary};
  pills(label, style);
}

// ---- PIN entry: left = -, middle = next / OK, right = + (hold middle = cancel) ----
static void drawPin(const PinEditor& e, const String& why) {
  using namespace ui;
  ui::Screen scr(tx->pinTitle);
  card(10, 56, 300, 136, kCard);
  iconText(ICON_KEY, why, 22, 66, kBold, kInk, kRed, 6);
  const std::string& d = e.digits();
  int n = (int)d.size(), okW = 52, gap = 6;
  int w = (276 - okW - gap * n) / (n > 0 ? n : 1);
  if (w > 40) w = 40;
  Font f = w >= 32 ? kBig : kTitle;
  int x = 160 - (n * (w + gap) + okW) / 2;
  for (int i = 0; i <= n; i++) {
    bool cur = i == (int)e.cursor();
    int bw = i < n ? w : okW;
    box(x, 98, bw, 50, 12, cur ? kRedSoft : kBg, cur ? kRed : kLine, cur ? 2 : 1);
    String c = i < n ? String(d[i]) : String("OK");
    int h = fontH(i < n ? f : kBold);
    centred(c, x + bw / 2, 123 - h / 2, i < n ? f : kBold, cur ? kRed : kInk);
    x += bw + gap;
  }
  holdKey(1, 156, 30, tx->holdWord, ICON_CLOSE, tx->cancelWord);
  bool ok = e.onOk();
  String label[3] = {ok ? String(tx->digitMinus) : String(ICON_MINUS), ok ? String(ICON_OK) + " OK" : String(tx->next) + " " + ICON_NEXT,
                     ok ? String(tx->digitPlus) : String(ICON_PLUS)};
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
    bool cancel = millis() - lastInput > 60000 || holdFired(M5.BtnB, middleDown);
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
  BtLink link(savedAddress, savedClassic);
  Robot robot(link, encryptPin(pin.c_str()), storedVersion);
  if (owedPlanning >= 0 && millis() - owedAt < kOwedForMs) robot.setOwedPlanning(owedPlanning);
  robot.onOwed = [](int v) {
    owedPlanning = v;
    owedAt = millis();
  };
  robot.lang = langFromCode(tx->code);
  robot.log = [](const std::string& s) { Serial.println(s.c_str()); };
  robot.onStep = [&](Step s) {
    if (s == Step::Syncing) {   // first connection: the robot sends its image set, part by part
      int p = robot.syncPercent();
      drawProgress(title, p < 0 ? String(tx->syncing) : String(tx->syncing) + " " + p + "%", done, expectedSteps(kind, planningOn));
      return;
    }
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
  logHeap("session");
  if (r == Result::Ok) r = job(robot);
  err = robot.lastError();
  if (r == Result::Ok || r == Result::Unexpected) robot.finish();   // planning as found
  st = robot.status();
  robot.disconnect();
  if (st.planning == 0 || st.planning == 1) {
    seen = st;
    seenAt = millis();
    haveSeen = true;
  }

  if (robot.imageVersionChanged()) prefs.putBytes("imgver", storedVersion, 9);
  if (r != Result::ConnectFailed && (link.address() != savedAddress || link.classic() != savedClassic)) {
    savedAddress = link.address();
    savedClassic = link.classic();
    prefs.putString("addr", savedAddress.c_str());
    prefs.putBool("classic", savedClassic);
  }
  return r;
}

// Show the result until a button is pressed (that press does nothing else) or kResultMs.
static void waitForButton() {
  uint32_t until = millis() + kResultMs;
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
    // The screen first, then the key: the robot drives only while "held" repeats every 100 ms
    // after the key went down, so nothing may come between the two.
    if (!drive && down[1] && M5.BtnB.isPressed() && millis() - down[1] >= kDriveAfterMs && r.inManual()) {
      drawManual(r.manualState(), true);
      drive = r.driveBegin() == Result::Ok;
      driveSince = millis();
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
      if (i == 0 && holdFired(M5.BtnA, down[0])) return r.exitManual();   // left held = done
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
  ui::Screen scr(tx->routeStopped);
  card(10, 56, 300, 136, kCard);
  disc(22, 64, 36, ICON_PAUSE, kRedSoft, kRed);
  text(tx->finishRoute, 70, 70, kTitle, kInk);
  int y = wrap(tx->finishYesHint, 22, 106, 276, kBody, kInk, 150);
  wrap(tx->finishNoHint, 22, y + 2, 276, kBody, kInk, 188);
  String label[3] = {String(ICON_OK) + " " + tx->yesWord, String(ICON_PAUSE) + " " + tx->laterWord, String(ICON_CLOSE) + " " + tx->noWord};
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
  ui::Screen scr(title);
  card(10, 56, 300, 136, kCard);
  int first = sel >= 5 ? sel - 4 : 0;   // five rows fit
  int y = 62;
  for (int i = first; i < (int)lines.size() && y < 186; i++) {
    bool cur = i == sel;
    if (cur) box(16, y - 1, 288, 25, 8, kRedSoft);
    text(lines[i], 26, y + 2, cur ? kBold : kBody, cur ? kRed : kInk);
    if (cur) text(ICON_NEXT, 286, y + 3, kBold, kRed);
    y += 25;
  }
  String label[3] = {String(ICON_DOWN) + " " + tx->nextWord, String(ICON_OK) + " " + tx->choose, String(ICON_PREV) + " " + tx->back};
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
  ui::Screen scr(title);
  card(10, 56, 300, 136, kCard);
  spinner(24, 70, 42);
  wrap(what, 80, 78, 220, kBold, kInk, 186);
}

// A message until a button is pressed. tone: 0 plain, 1 good, 2 wrong.
static void notice(const String& title, const String& what, int tone) {
  using namespace ui;
  {
    ui::Screen scr(title);
    card(10, 56, 300, 136, tone == 1 ? kOkSoft : tone == 2 ? kErrSoft : kCard, tone == 0);
    if (tone) {
      disc(22, 66, 40, tone == 1 ? ICON_OK : ICON_WARNING, tone == 1 ? kOk : kErr);
      wrap(what, 74, 70, 226, kBold, tone == 1 ? kOk : kErr, 186);
    } else {
      wrap(what, 22, 70, 276, kBold, kInk, 186);
    }
    closeHint(tx->pressAnyButton, kResultMs);
  }   // shown here
  if (tone) M5.Speaker.tone(tone == 1 ? 2000 : 400, 120);
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
  std::vector<FoundRobot> found = BtLink::scanAll(5000);
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
  savedClassic = c != 0 && found[c - 1].classic;
  prefs.putString("addr", savedAddress.c_str());
  prefs.putBool("classic", savedClassic);
  M5.Speaker.tone(2000, 100);
  using namespace ui;
  {
    ui::Screen scr(tx->robotWord);
    card(10, 56, 300, 136, kOkSoft, false);
    disc(22, 66, 40, ICON_OK, kOk);
    wrap(tx->robotSaved, 74, 76, 226, kTitle, kOk, 186);
  }
  delay(1200);
}

// ---- wifi, set up on the M5 itself (Erik: no phone, no app) ---------------------------
// Settings > Wifi: Network = choose from a scan and type the password with the three
// buttons (TextEditor), WPS = press the router's WPS button. Kept in flash like the PIN.
static String wifiSsid, wifiPass;

// Wifi on, after the BLE stack gave its memory back: wifi, TLS and BLE next to the screen do
// not fit (updates crashed, Erik 2026-11-13). The next robot session starts BLE again.
static void wifiOn() {
  BtLink::release();
  WiFi.mode(WIFI_STA);
}

static bool wifiUp(uint32_t ms = 20000) {
  if (wifiSsid.isEmpty()) return false;
  wifiOn();
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
// The whole screen each time: it is built off-screen, so only what changed looks different.
static void drawTyping(const TextEditor& e, const String& ssid) {
  using namespace ui;
  ui::Screen scr(tx->wifiRow);
  String label[3] = {ICON_PREV, String(ICON_OK) + " " + tx->choose, ICON_NEXT};
  Pill style[3] = {Pill::Neutral, Pill::Primary, Pill::Neutral};
  pills(label, style);
  card(10, 56, 300, 136, kCard);
  iconText(ICON_WIFI, String(tx->passwordFor) + " " + ssid, 22, 63, kBody, kMuted, kRed, 6);
  String shown = String(e.text().c_str());
  while (shown.length() > 0 && textW(shown + "_", kBold) > 268) shown = shown.substring(1);   // the end if long
  box(18, 84, 284, 28, 8, kChip);
  text(shown + "_", 26, 89, kBold, kInk);
  const int w = 54, gap = 4;
  for (int k = -2; k <= 2; k++) {
    int i = (e.cursor() + k + e.size()) % e.size();
    bool cur = k == 0;
    int x = 160 + k * (w + gap) - w / 2;
    box(x, 118, w, 40, 12, cur ? kRedSoft : kBg, cur ? kRed : kLine, cur ? 2 : 1);
    std::string l = e.label(i);
    String s = l == " " ? String(tx->spaceWord) : String(l.c_str());
    Font f = l.size() == 1 && l != " " ? kTitle : kBold;
    centred(s, x + w / 2, 138 - fontH(f) / 2, f, cur ? kRed : kInk);
  }
  holdKey(1, 162, 26, tx->holdWord, ICON_CLOSE, tx->cancelWord);
}

static bool typePassword(const String& ssid, String& out) {
  TextEditor e;
  drawTyping(e, ssid);
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
    if (now - lastInput > 180000 || holdFired(M5.BtnB, middleDown)) {
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
      drawTyping(e, ssid);
    }
    delay(10);
  }
}

static void chooseNetwork() {
  busy(tx->wifiRow, tx->scanningWifi);
  wifiOn();
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
  wifiOn();
  wpsResult = 0;
  int handler = WiFi.onEvent(onWps);
  esp_wps_config_t cfg;
  std::memset(&cfg, 0, sizeof cfg);
  cfg.wps_type = WPS_TYPE_PBC;
  std::strncpy(cfg.factory_info.manufacturer, "Scraper Control", sizeof cfg.factory_info.manufacturer - 1);
  std::strncpy(cfg.factory_info.model_number, "1", sizeof cfg.factory_info.model_number - 1);
  std::strncpy(cfg.factory_info.model_name, "Scraper-knop", sizeof cfg.factory_info.model_name - 1);
  std::strncpy(cfg.factory_info.device_name, "scraper-control", sizeof cfg.factory_info.device_name - 1);
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
// Settings > Wifi > Update: reads update.ini of the latest release (Manifest.h), takes the
// block for this board, checks its signature with ReleaseKey.h, and only then downloads the
// file it names into the other app slot, checking its SHA-256 on the way. Anything wrong: the current version stays.
static const char kReleaseUrl[] = "https://github.com/" FW_REPO "/releases/latest/download/";
static const char kManifest[] = "update.ini";   // fixed name; the firmware files can change names

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
                 "\r\nUser-Agent: scraper-control\r\nConnection: close\r\n\r\n");
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
      if (out.length() > 16384) break;   // a manifest is a few hundred bytes per board
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
  Serial.printf("update: %u bytes heap free, %u largest\n", (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMaxAllocHeap());
  WiFiClientSecure client;
  long length = -1;
  if (!httpsGet(client, String(String(kReleaseUrl) + m.file.c_str()).c_str(), length) ||
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
  bool got = fetchText(String(kReleaseUrl) + kManifest, body);
  Manifest m = parseManifest(body.c_str(), FW_BOARD);
  String own = FW_VERSION;
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
  {
    ui::Screen scr(tx->updateTitle);
    card(10, 56, 300, 136, kCard);
    disc(22, 66, 40, ICON_DOWNLOAD, kRedSoft, kRed);
    int y = wrap(String(tx->newVersion) + " " + m.version.c_str(), 74, 70, 226, kTitle, kInk, 140);
    text(String(tx->versionRow) + " " + own, 74, y + 4, kBody, kMuted);
    String label[3] = {"", String(ICON_DOWNLOAD) + " " + tx->install, String(ICON_PREV) + " " + tx->back};
    Pill style[3] = {Pill::Neutral, Pill::Primary, Pill::Neutral};
    pills(label, style);
  }
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
  if (!fetchText(String(kReleaseUrl) + kManifest, body)) return;
  Manifest m = parseManifest(body.c_str(), FW_BOARD);
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
    wifiOn();
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

// ---- reading the robot in the background (Erik 2026-11-12) -------------------------------
// So the home screen always shows the robot as it is now: every 15 minutes, on the home
// screen, the M5 connects, reads the robot's screen and disconnects. A few seconds; it never
// presses a key (no finish(): that can press keys) and never answers a question. Not while
// someone uses the M5 (2 minutes after a press), not while a planning is still owed to the
// robot, not while the wifi looks for an update. Robot out of reach, or another app is
// connected (the robot takes one connection at a time): simply the next round, 15 min later.
static const uint32_t kSyncEveryMs = 15UL * 60 * 1000;
static uint32_t nextSync = 15000;   // the first read soon after starting

static bool readRobotQuietly() {
  BtLink link(savedAddress, savedClassic);
  Robot robot(link, encryptPin(pin.c_str()), storedVersion);
  robot.lang = langFromCode(tx->code);
  robot.log = [](const std::string& s) { Serial.println(s.c_str()); };
  Result r = robot.connect();
  Status st = robot.status();
  robot.disconnect();
  if (r != Result::Ok || (st.planning != 0 && st.planning != 1)) return false;
  seen = st;
  seenAt = millis();
  haveSeen = true;
  followRobotLanguage(st);
  if (robot.imageVersionChanged()) prefs.putBytes("imgver", storedVersion, 9);
  return true;
}

static void syncTick() {
  uint32_t now = millis();
  if ((int32_t)(now - nextSync) < 0) return;
  bool inUse = now - lastActive < 120000;
  bool owed = owedPlanning >= 0 && now - owedAt < kOwedForMs;
  if (inUse || owed || autoConnecting || pin.isEmpty()) {
    nextSync = now + 60000;   // look again in a minute
    return;
  }
  syncing = true;
  idle();   // a small sync mark where the age is
  readRobotQuietly();
  syncing = false;
  nextSync = millis() + kSyncEveryMs;
  idle();
}

// ---- settings > wifi: left = network, middle = WPS, right = update ----------------------
static void drawWifi() {
  using namespace ui;
  ui::Screen scr(tx->wifiRow);
  card(10, 56, 300, 136, kCard);
  settingRow(0, ICON_WIFI, tx->networkWord, wifiSsid.isEmpty() ? String(tx->notSet) : wifiSsid);
  settingRow(1, ICON_DOWNLOAD, tx->versionRow, FW_VERSION);
  String label[3] = {String(ICON_SEARCH) + " " + tx->networkWord, tx->wpsWord, String(ICON_DOWNLOAD) + " " + tx->updateWord};
  Pill style[3] = {Pill::Neutral, Pill::Neutral, Pill::Neutral};
  pills(label, style);
}

static void wifiMenu() {
  waitAllReleased();
  drawWifi();
  uint32_t start = millis();
  while (millis() - start < 20000) {
    M5.update();
    if (M5.BtnA.wasReleased()) { chooseNetwork(); return; }
    if (M5.BtnB.wasReleased()) { wpsSetup(); return; }
    if (M5.BtnC.wasReleased()) { selfUpdate(); return; }
    delay(10);
  }
}

// ---- settings (right held 1.5 s): left = PIN, middle = robot, right = wifi ---------------
static void drawSettings(const char* line = nullptr) {
  using namespace ui;
  ui::Screen scr(tx->settings);
  card(10, 56, 300, 136, kCard);
  settingRow(0, ICON_ROBOT, tx->robotRow, savedAddress.empty() ? String(tx->automatic) : String(savedAddress.c_str()));
  settingRow(1, ICON_WIFI, tx->wifiRow, wifiSsid.isEmpty() ? String(tx->notSet) : wifiSsid);
  String version = FW_VERSION;
  if (line) settingRow(2, ICON_SYNC, tx->versionRow, line);   // looking for an update
  else settingRow(2, ICON_DOWNLOAD, tx->versionRow, version);
  String label[3] = {String(ICON_KEY) + " " + tx->pinTitle, String(ICON_ROBOT) + " " + tx->robotWord, String(ICON_WIFI) + " " + tx->wifiRow};
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
    wifiOn();
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
  ui::begin();   // LVGL: the screens (Ui.h)
  Serial.begin(115200);
  prefs.begin(DRIVER_STORE, false);
  if (prefs.getBytesLength("imgver") == 9) prefs.getBytes("imgver", storedVersion, 9);
  savedAddress = prefs.getString("addr", "").c_str();
  savedClassic = prefs.getBool("classic", false);
  pin = prefs.getString("pin", "");
  wifiSsid = prefs.getString("wssid", "");
  wifiPass = prefs.getString("wpass", "");
  tx = &textsFor(prefs.getString("lang", "nl").c_str());
  M5.Display.setBrightness(kBright);
  wake();
  if (pin.isEmpty() && editPin(tx->enterPin)) M5.Speaker.tone(2000, 150);
  String updated = prefs.getString("updated", "");
  if (!updated.isEmpty()) {   // first start after an update
    prefs.putString("updated", "");
    using namespace ui;
    {
      ui::Screen scr(tx->updateTitle);
      card(10, 56, 300, 136, kOkSoft, false);
      disc(22, 66, 40, ICON_OK, kOk);
      wrap(String(tx->updatedTo) + " " + updated, 74, 70, 226, kTitle, kOk, 186);
    }
    delay(3000);
  }
  idle();
}

void loop() {
  // Short press: acts on release (a route). Held 1.5 s: acts at once, while the button is
  // still down (Erik 2026-11-06), with a short beep; its release is then ignored.
  // Middle = manual drive, left switches the planning, right opens the settings (holdFired).
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
  syncTick();
  // the age on the home screen ("12 min ago") stays true; not while a button is down
  if (haveSeen && millis() - homeDrawnAt > 60000 && !M5.BtnA.isPressed() && !M5.BtnB.isPressed() &&
      !M5.BtnC.isPressed())
    idle();
  for (int i = 0; i < 3; i++) {
    if (btn[i]->wasPressed()) pressedAt[i] = millis();
    if (!pressedAt[i]) continue;
    bool longPress = holdFired(*btn[i], pressedAt[i]);
    if (!longPress && !btn[i]->wasReleased()) continue;
    pressedAt[i] = 0;
    if (!longPress) startRoute(kRoutes[i]);
    else {
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
