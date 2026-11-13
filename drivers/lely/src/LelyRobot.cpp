#include "LelyRobot.h"

#include <cstring>

namespace lely {
namespace {
// Screens are recognised by their layout (Screen::isHome() etc.), not by words, so this
// works in every robot language. Dutch names, for reading: beginscherm ("Vlg rit"),
// routemenu "HANDM. ROUTE", the questions "Tijdpad na rijden route aanzetten?" and
// "VEILIGHEIDWAARSCHUW. ... Bevestig?" (Ja = soft1, Nee = soft3), "HANDBEDIENING", and the
// main menu "Werken, Routes, Testen, Instellingen, Alarmen, Service" (Esc from home).
const char* kRoutes = "ABCD";

int routeIndex(char c) { const char* p = strchr(kRoutes, c); return (p && c) ? int(p - kRoutes) : -1; }

std::string compact(const std::string& s) {
  std::string o;
  for (char c : s) if (c != ' ') o += c;
  return o;
}
}  // namespace

Lang langFromCode(const char* code) {
  return code && (code[0] == 'e' || code[0] == 'E') && (code[1] == 'n' || code[1] == 'N') ? Lang::En : Lang::Nl;
}

const char* resultText(Result r, Lang l) {
  bool en = l == Lang::En;
  switch (r) {
    case Result::Ok: return "ok";
    case Result::ConnectFailed: return en ? "connection failed" : "verbinden mislukt";
    case Result::PinRejected: return en ? "PIN rejected" : "pincode geweigerd";
    case Result::NoScreen: return en ? "no screen received" : "geen scherm ontvangen";
    case Result::Busy: return en ? "robot is busy" : "robot is bezig";
    case Result::Unexpected: return en ? "unexpected screen" : "onverwacht scherm";
    case Result::StartedNoAnswer: return en ? "started, question not seen" : "gestart, vraag niet gezien";
  }
  return "?";
}

Robot::Robot(Transport& t, Bytes encPin, uint8_t imageVersion[9])
    : t_(t), pin_(std::move(encPin)), ver_(imageVersion) {
  t_.onData = [this](const uint8_t* d, size_t n) { onBytes(d, n); };
}

Result Robot::fail(Result r, const std::string& why) {
  err_ = why + "\n" + screen.text();
  say(std::string(tr("FOUT: ", "ERROR: ")) + why);
  return r;
}

// ---- incoming ------------------------------------------------------------------
void Robot::onBytes(const uint8_t* d, size_t n) {
  lastRx_ = t_.now();
  for (auto& m : parser_.feed(d, n)) {
    if (m.msgId == 2020) {
      granted_ = (!m.payload.empty() && m.payload[0] == 1) ? 1 : 0;
    } else if (m.msgId == 1536 && m.payload.size() % 16 == 0) {
      for (size_t o = 0; o < m.payload.size(); o += 16) {
        uint32_t id = m.payload[o] | m.payload[o + 1] << 8;
        if ((id & 0xF00) == 0x300) onCan(&m.payload[o + 4]);
      }
    }
  }
}

void Robot::onCan(const uint8_t d[8]) {
  uint8_t k = d[0], s = d[1];
  if (k == 2 && s == 0) {
    t_.write(reconnect());
  } else if (k == 5 && s == 0) {             // robot asks which image set we have
    t_.write(imageVersion(ver_));
  } else if (k == 5 && s == 4) {             // robot tells its image set version
    if (d[2] == 0) memcpy(ver_, d + 3, 5);
    if (d[2] == 1) { memcpy(ver_ + 5, d + 3, 4); versionChanged_ = true; }
  } else if (k == 5 && s == 2) {             // images done / skipped
    t_.write(canReady());
  } else if (k == 3 && s == 20 && d[2] == 0 && d[3] == 0) {
    t_.write(contrast());
  } else if (k == 3) {
    screen.feed(d);
  }
}

void Robot::keepAlive() {
  if (!connected_) return;
  uint32_t now = t_.now();
  if (now - lastAlive_ >= 1000) {
    lastAlive_ = now;
    t_.write(alive());
    t_.write(aliveWithLevel(1));
  }
}

bool Robot::waitUntil(const Pred& p, uint32_t ms) {
  uint32_t end = t_.now() + ms;
  while (!p(screen)) {
    if ((int32_t)(t_.now() - end) >= 0 || !t_.linkUp()) return false;
    keepAlive();
    t_.pump(20);
  }
  return true;
}

// The real robot redraws its screen in pieces over BLE and ignores keys until it is
// done (seen 2026-11-05 with the M5Stack). So after every key we wait for the expected
// screen and then until the screen is quiet, like the Python script that worked.
// Sometimes the robot does not react to a key at all (no frames back, seen 2026-11-05);
// then the same key is sent once more. Only when nothing at all changed on the screen.
void Robot::owe(int v) {
  if (v == owed_) return;
  owed_ = v;
  if (onOwed) onOwed(v);
}

void Robot::track() {
  if (planningAtConnect_ >= 0 && screen.isHome() && screen.planning() == planningAtConnect_) owe(-1);
}

bool Robot::tap(Key k, const Pred& until, uint32_t ms) {
  // Any key may take the robot away from how it was found; say so before it is sent, so a
  // link that drops right after it still leaves the debt with the host.
  if (planningAtConnect_ >= 0 && !screen.isDriving()) owe(planningAtConnect_);
  struct Track { Robot* r; ~Track() { r->track(); } } after{this};
  for (int attempt = 0; attempt < 2; attempt++) {
    uint32_t v = screen.version();
    t_.write(key(0, k));
    t_.pump(80);
    t_.write(key(7, k));
    bool ok = waitUntil(until, ms);
    settle();
    if (ok || until(screen)) return true;
    if (screen.version() != v) return false;   // the robot did react, just not as expected
    say(tr("geen reactie op toets, nog een keer", "no reaction to key, once more"));
  }
  return false;
}

void Robot::settle(uint32_t quiet, uint32_t ms) {
  uint32_t start = t_.now(), since = start, v = screen.version();
  while ((int32_t)(t_.now() - (start + ms)) < 0 && t_.linkUp()) {
    keepAlive();
    t_.pump(20);
    if (screen.version() != v) { v = screen.version(); since = t_.now(); }
    else if (t_.now() - since >= quiet) return;
  }
}

// ---- lifecycle -------------------------------------------------------------------
Result Robot::connect() {
  granted_ = -1;
  if (!t_.connect()) return fail(Result::ConnectFailed, tr("BLE-verbinding mislukt", "BLE connection failed"));
  step(Step::Linked);
  t_.write(computerType());
  t_.write(getNodeSetting());
  t_.pump(300);
  t_.write(askPermission(pin_));
  uint32_t end = t_.now() + 10000;
  while (granted_ < 0 && t_.linkUp() && (int32_t)(t_.now() - end) < 0) t_.pump(20);
  if (granted_ != 1) {   // only a real "no" (2020 = 00) means a wrong PIN
    t_.disconnect();
    return granted_ == 0 ? fail(Result::PinRejected, tr("pincode geweigerd", "PIN rejected"))
                         : fail(Result::ConnectFailed, tr("geen antwoord op pincode", "no answer to PIN"));
  }
  step(Step::Granted);
  t_.write(connectWithLevel(1));
  t_.write(connectPanel());
  connected_ = true;
  lastAlive_ = t_.now() - 1000;
  // A robot whose image set we lack sends it first (about 75 s over BLE, longer on a slow
  // link): wait 90 s, and on while it is still sending, up to 4 minutes.
  auto shown = [](const Screen& s) { return s.isHome() || s.isRouteMenu() || !s.lines().empty(); };
  uint32_t start = lastRx_ = t_.now();
  bool ok = waitUntil(shown, 90000);
  while (!ok && t_.linkUp() && t_.now() - lastRx_ < 5000 && t_.now() - start < 240000) ok = waitUntil(shown, 5000);
  if (!ok) return fail(Result::NoScreen, tr("geen scherm ontvangen", "no screen received"));
  settle();
  if (!screen.isHome() && !screen.isRouteMenu()) {
    waitUntil([](const Screen& s) { return s.isHome(); }, 1000);
    settle();
  }
  planningAtConnect_ = screen.planning();
  if (planningAtConnect_ < 0 && screen.isRouteMenu()) planningAtConnect_ = 0;   // menu: only with planning OFF
  say(tr("verbonden", "connected"));
  if (owed_ >= 0) payBack();
  step(Step::ScreenReady);
  return Result::Ok;
}

// An earlier session lost its link while the robot was away from how it found it.
void Robot::payBack() {
  int want = owed_;
  planningAtConnect_ = want;   // finish() of this session restores the same state
  bool safe = screen.isHome() || screen.isRouteMenu() || screen.isMainMenu() || screen.isManual();
  if (!safe) { say(tr("tijdpad nog niet hersteld: robot is bezig", "planning not restored yet: robot is busy")); return; }
  say(tr("vorige verbinding viel weg: robot terug zoals hij was", "previous link dropped: putting the robot back as it was"));
  std::string e = err_;
  if (screen.isManual()) { manualWasOn_ = false; exitManual(); }
  if (screen.isRouteMenu() || screen.isMainMenu()) goHome();
  if (screen.isHome() && screen.planning() >= 0 && screen.planning() != want) switchPlanning(want == 1);
  track();
  err_ = e;
}

void Robot::disconnect() {
  if (driving_) driveEnd();
  connected_ = false;
  t_.disconnect();
}

// ---- commands ---------------------------------------------------------------------
Result Robot::press(Key k) {
  tap(k, [](const Screen&) { return false; }, 300);
  return Result::Ok;
}

void Robot::idle(uint32_t ms) {
  if (!connected_) { t_.pump(ms); return; }
  waitUntil([](const Screen&) { return false; }, ms);
}

bool Robot::onMainMenu() const { return screen.isMainMenu(); }

// From the main menu: Up to the top row (Werken / Work), then Enter = home screen.
void Robot::mainMenuToHome() {
  if (screen.highlightedRow() == screen.topRow())
    tap(Key::Enter, [](const Screen& s) { return s.isHome(); });
  else
    tap(Key::Up, [](const Screen& s) { return s.highlightedRow() == s.topRow(); });
}

Result Robot::goHome() {
  for (int i = 0; i < 8; i++) {
    if (screen.isHome()) return Result::Ok;
    if (onMainMenu()) { mainMenuToHome(); continue; }
    if (!screen.isRouteMenu()) return fail(Result::Busy, tr("niet op beginscherm of routemenu; niets gedrukt", "not on home screen or route menu; nothing pressed"));
    tap(Key::Back, [](const Screen& s) { return s.isHome(); });
  }
  return screen.isHome() ? Result::Ok : fail(Result::Unexpected, tr("beginscherm niet bereikt", "home screen not reached"));
}

Result Robot::setPlanning(bool on) {
  // The user's choice: from now on this is the state to leave the robot in, also if the
  // link drops halfway.
  int before = planningAtConnect_;
  planningAtConnect_ = on ? 1 : 0;
  Result r = switchPlanning(on);
  if (r != Result::Ok) {
    planningAtConnect_ = before;
    if (owed_ >= 0) owe(before);
  }
  track();
  return r;
}

Result Robot::finish() {
  if (!connected_ || !t_.linkUp()) return Result::Ok;
  driveEnd();
  if (screen.isManual()) exitManual();
  if (screen.isRouteMenu() || screen.isMainMenu()) goHome();
  if (planningAtConnect_ < 0 || !screen.isHome()) return Result::Ok;
  int now = screen.planning();
  if (now < 0 || now == planningAtConnect_) { track(); return Result::Ok; }
  say(tr("tijdpad terug zoals bij verbinden", "planning back as at connect"));
  return switchPlanning(planningAtConnect_ == 1);
}

Result Robot::switchPlanning(bool on) {
  Result r = goHome();
  if (r != Result::Ok) return r;
  int want = on ? 1 : 0;
  if (screen.planning() == want) return Result::Ok;
  tap(Key::StartStop, [want](const Screen& s) { return s.planning() == want; });
  if (screen.planning() != want) return fail(Result::Unexpected, on ? tr("planning ging niet AAN", "planning did not go ON") : tr("planning ging niet UIT", "planning did not go OFF"));
  say(on ? tr("planning AAN", "planning ON") : tr("planning UIT", "planning OFF"));
  step(on ? Step::PlanningOn : Step::PlanningOff);
  return Result::Ok;
}

Result Robot::select(char letter) {
  tap(Key::Enter, [](const Screen& s) { return s.isRouteMenu(); });
  if (!screen.isRouteMenu()) return fail(Result::Unexpected, tr("routemenu niet gezien", "route menu not seen"));
  // the selection bar can come after the text
  waitUntil([](const Screen& s) { return !s.highlighted().empty(); }, 2000);
  step(Step::Menu);
  for (int i = 0; i < 6; i++) {
    std::string sel = screen.highlighted();
    int cur = sel.empty() ? -1 : routeIndex(sel[0]);
    if (cur < 0) return fail(Result::Unexpected, std::string(tr("geselecteerde regel niet herkend: '", "selected row not recognised: '")) + sel + "'");
    if (compact(sel).rfind(std::string(1, letter) + "->", 0) == 0) { step(Step::Selected); return Result::Ok; }
    int want = routeIndex(letter);
    char next = kRoutes[cur + (cur < want ? 1 : -1)];
    tap(cur < want ? Key::Down : Key::Up,
        [next](const Screen& s) { std::string h = s.highlighted(); return !h.empty() && h[0] == next; });
  }
  return fail(Result::Unexpected, tr("route niet geselecteerd", "route not selected"));
}

Result Robot::restore(bool wasOn) {
  Result r = goHome();
  if (r != Result::Ok) return r;
  return wasOn ? switchPlanning(true) : Result::Ok;
}

Result Robot::stopRoute() {
  if (!screen.isDriving())
    return fail(Result::Busy, tr("rijdt geen route; niets gedrukt", "not driving a route; nothing pressed"));
  tap(Key::StartStop, [](const Screen& s) { return !s.isDriving(); });
  // Seen on the real robot (2026-11-11): the robot stops at once and asks "Route gestopt.
  // Route afmaken? Ja / Pauze / Nee". Ja would FINISH the route (drive on), so nothing is
  // answered here: the question stays on the robot until a person chooses.
  if (screen.isDriving()) return fail(Result::Unexpected, tr("robot rijdt nog na Stop", "robot still driving after Stop"));
  say(tr("route gestopt", "route stopped"));
  return Result::Ok;
}

Result Robot::answerStop(bool finish) {
  if (!screen.isStopQuestion())
    return fail(Result::Busy, tr("geen stopvraag op het scherm; niets gedrukt", "no stop question on screen; nothing pressed"));
  tap(finish ? Key::Soft1 : Key::Soft3, [](const Screen& s) { return !s.isStopQuestion(); });
  if (screen.isStopQuestion()) return fail(Result::Unexpected, tr("stopvraag bleef staan", "stop question stayed"));
  // The robot decided what happens to the planning (Nee leaves it off): nothing owed, and
  // finish() leaves it as the robot set it.
  planningAtConnect_ = -1;
  owe(-1);
  say(finish ? tr("route gaat verder", "route continues") : tr("route afgebroken", "route cancelled"));
  return Result::Ok;
}

Result Robot::readRoutes(std::vector<std::string>& out) {
  Result r = goHome();
  if (r != Result::Ok) return r;
  bool wasOn = screen.planning() == 1;
  if (wasOn && (r = switchPlanning(false)) != Result::Ok) return r;
  tap(Key::Enter, [](const Screen& s) { return s.isRouteMenu(); });
  if (!screen.isRouteMenu()) { restore(wasOn); return fail(Result::Unexpected, tr("routemenu niet gezien", "route menu not seen")); }
  step(Step::Menu);
  settle();
  out = screen.routeContents();
  say(tr("routes gelezen", "routes read"));
  Result rr = restore(wasOn);
  if (rr == Result::Ok) step(Step::Restored);
  return rr;
}

Result Robot::startRoute(char letter, bool dryRun) {
  if (letter >= 'a' && letter <= 'd') letter -= 32;
  if (routeIndex(letter) < 0) return fail(Result::Unexpected, tr("route moet A-D zijn", "route must be A-D"));
  Result r = goHome();
  if (r != Result::Ok) return r;
  bool wasOn = screen.planning() == 1;
  if (wasOn && (r = switchPlanning(false)) != Result::Ok) return r;

  r = select(letter);
  if (r != Result::Ok) { std::string e = err_; restore(wasOn); err_ = e; return r; }
  if (dryRun) {
    say(tr("proefrun: niet gestart", "dry run: not started"));
    Result rr = restore(wasOn);
    if (rr == Result::Ok) step(Step::Restored);
    return rr;
  }

  tap(Key::StartStop, [](const Screen& s) { return s.isQuestion(); });
  if (!screen.isQuestion())   // Start pressed, state unknown: press nothing more
    return fail(Result::StartedNoAnswer, tr("geen tijdpad-vraag na Start (planning staat UIT)", "no planning question after Start (planning is OFF)"));
  step(Step::StartPressed);
  Key answer = wasOn ? Key::Soft1 : Key::Soft3;   // Ja (left) / Nee (right)
  tap(answer, [](const Screen& s) { return !s.isQuestion(); });
  if (screen.isQuestion()) return fail(Result::Unexpected, tr("tijdpad-vraag bleef staan", "planning question stayed"));
  say(std::string("route ") + letter + tr(" gestart", " started"));
  owe(-1);   // the answer told the robot what to do with the planning after the route
  step(Step::Answered);
  return Result::Ok;
}

// ---- manual drive ------------------------------------------------------------------
bool Robot::inManual() const { return screen.isManual(); }

Result Robot::enterManual() {
  Result r = goHome();
  if (r != Result::Ok) return r;
  manualWasOn_ = screen.planning() == 1;
  if (manualWasOn_ && (r = switchPlanning(false)) != Result::Ok) return r;
  tap(Key::Enter, [](const Screen& s) { return s.isRouteMenu(); });
  if (!screen.isRouteMenu()) { restore(manualWasOn_); return fail(Result::Unexpected, tr("routemenu niet gezien", "route menu not seen")); }
  step(Step::Menu);
  tap(Key::Enter, [](const Screen& s) { return s.isQuestion(); });
  if (!screen.isQuestion()) { restore(manualWasOn_); return fail(Result::Unexpected, tr("veiligheidsvraag niet gezien", "safety question not seen")); }
  tap(Key::Soft1, [](const Screen& s) { return s.isManual(); });   // Ja
  if (!screen.isManual()) {
    exitManual();
    return fail(Result::Unexpected, tr("handbediening niet gezien", "manual control not seen"));
  }
  say(tr("handbediening", "manual control"));
  step(Step::Manual);
  return Result::Ok;
}

Result Robot::manualKey(Key k) {
  if (k != Key::Soft1 && k != Key::Soft2 && k != Key::Soft3) return fail(Result::Unexpected, tr("alleen softkeys", "soft keys only"));
  if (!inManual()) return fail(Result::Busy, tr("niet in handbediening; niets gedrukt", "not in manual control; nothing pressed"));
  if (driving_) return fail(Result::Busy, tr("rijdt; eerst loslaten", "driving; release first"));
  tap(k, [](const Screen&) { return false; }, 300);
  return Result::Ok;
}

Result Robot::driveBegin() {
  if (!inManual()) return fail(Result::Busy, tr("niet in handbediening; niets gedrukt", "not in manual control; nothing pressed"));
  if (driving_) return Result::Ok;
  t_.write(key(0, Key::StartStop));
  driving_ = true;
  lastRepeat_ = t_.now();
  say(tr("rijden: start ingedrukt", "drive: start pressed"));
  return Result::Ok;
}

void Robot::driveHold(uint32_t ms) {
  uint32_t end = t_.now() + ms;
  do {
    if (!t_.linkUp()) { driving_ = false; return; }
    if (driving_ && t_.now() - lastRepeat_ >= 100) {
      lastRepeat_ = t_.now();
      t_.write(key(1, Key::StartStop));
    }
    keepAlive();
    t_.pump(10);
  } while ((int32_t)(t_.now() - end) < 0);
}

void Robot::driveEnd() {
  if (!driving_) return;
  t_.write(key(7, Key::StartStop));
  driving_ = false;
  say(tr("rijden: start losgelaten", "drive: start released"));
}

Result Robot::exitManual() {
  driveEnd();
  for (int i = 0; i < 8 && !screen.isHome(); i++) {
    if (onMainMenu()) {
      mainMenuToHome();
    } else {
      tap(Key::Back, [](const Screen& s) { return s.isHome() || s.isMainMenu(); });
    }
  }
  if (!screen.isHome()) return fail(Result::Unexpected, tr("beginscherm niet bereikt", "home screen not reached"));
  if (manualWasOn_) {
    manualWasOn_ = false;
    return switchPlanning(true);
  }
  return Result::Ok;
}

}  // namespace lely
