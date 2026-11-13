// High-level Lely Discovery API, the single implementation used by firmware, app and Python:
//   connect(), status(), startRoute('A'), setPlanning(on), press(key), disconnect()
// Blocking, single-threaded: incoming bytes are delivered from Transport::pump().
#pragma once
#include <functional>
#include <string>

#include "LelyProtocol.h"

namespace lely {

// The link to the robot. Buttons: BleSerialLink (firmware/lib). Apps and Python: HostTransport
// (scraper_c.cpp). Tests: a pipe to the simulated robot.
class Transport {
 public:
  virtual ~Transport() {}
  virtual bool connect() = 0;                 // open link, subscribe to TX notifications
  virtual bool write(const Bytes& frame) = 0;
  virtual void disconnect() = 0;
  virtual void pump(uint32_t ms) = 0;         // wait up to ms, calling onData for received bytes
  virtual uint32_t now() = 0;                 // milliseconds
  virtual bool linkUp() { return true; }      // false once the link dropped: waits stop early
  std::function<void(const uint8_t*, size_t)> onData;
};

enum class Result {
  Ok,
  ConnectFailed,   // BLE link could not be opened
  PinRejected,     // robot answered 2020 with "no"
  NoScreen,        // connected, but no panel screen arrived
  Busy,            // not on home screen / route menu (driving?): nothing pressed
  Unexpected,      // a step did not show the expected screen; robot was put back
  StartedNoAnswer, // Start pressed but the planning question did not appear
};
// Language of the texts the core produces (results, error details, log): "nl" or "en".
enum class Lang { Nl, En };
Lang langFromCode(const char* code);              // "en", "en_US", ... -> En; else Nl
const char* resultText(Result r, Lang l = Lang::Nl);

// Progress while a command runs, for a UI (same numbers as SCRAPER_STEP_* in scraper_c.h).
enum class Step {
  Linked = 1,       // Bluetooth link open
  Granted = 2,      // PIN accepted
  ScreenReady = 3,  // panel screen received
  PlanningOff = 4,  // time planning switched off
  PlanningOn = 5,   // time planning switched on
  Menu = 6,         // HANDM. ROUTE open
  Selected = 7,     // wanted route selected
  StartPressed = 8, // Start pressed, question shown
  Answered = 9,     // planning question answered: route is running
  Restored = 10,    // dry run / abort: back on home screen as before
  Syncing = 11,     // the robot sends its image set (first connection): syncPercent()
  Manual = 11,      // HANDBEDIENING open (safety question answered with Ja)
};

class Robot {
 public:
  // encPin: AES-encrypted PIN (lely::encryptPin or precomputed); empty if none.
  // imageVersion: 9 bytes remembered from last time ("DIS0018T4"); updated in place.
  Robot(Transport& t, Bytes encPin, uint8_t imageVersion[9]);

  Result connect();
  void disconnect();
  Status status() const { return parseStatus(screen); }
  Result startRoute(char letter, bool dryRun = false);
  // Read what routes A..D consist of from HANDM. ROUTE (planning off for a moment, Enter,
  // read, back; planning as it was). Only reads: nothing is selected or started.
  Result readRoutes(std::vector<std::string>& out);
  // Safety stop (Erik 2026-11-05: a route button while a route runs = stop it). Only on the
  // driving screen (ROUTE->TIJDPAD): one tap on Start/Stop, as on the robot's own panel.
  // The robot stops at once and asks "route afmaken? Ja / Pauze / Nee"; that question is
  // NOT answered here (Ja drives on): the user chooses, then answerStop().
  // Busy (nothing pressed) on any other screen; Unexpected if the robot keeps driving.
  Result stopRoute();
  bool drivingRoute() const { return screen.isDriving(); }
  bool stopQuestion() const { return screen.isStopQuestion(); }
  // Pass the user's answer to that question on to the robot (Erik 2026-11-11): finish = Ja
  // (the route goes on), otherwise Nee (the robot leaves the planning off, and the core does
  // not switch it back on). The middle choice (HAND / Pauze submenu) is never pressed.
  // Busy (nothing pressed) if the question is not shown.
  Result answerStop(bool finish);
  Result setPlanning(bool on);     // the user's choice: finish() keeps the new state
  // Before disconnecting: leave the robot as it was found. Back to the home screen if it is
  // in a safe menu (route menu, main menu, HANDBEDIENING) and the planning back to what it
  // was at connect. Touches nothing while the robot drives or shows another screen.
  Result finish();
  Result press(Key k);

  // ---- planning owed after a dropped link ----------------------------------------------
  // While the core (or a panel key) has the robot away from how it was found (planning
  // switched off for a route, in a menu, in HANDBEDIENING), owedPlanning() is the planning
  // state it must go back to: 1 / 0, or -1 when nothing is owed. finish() pays it back. If
  // the link drops first, the host keeps the value (onOwed fires on every change) and hands
  // it to the next session with setOwedPlanning() before connect(): connect() then takes the
  // robot back from a safe menu to the home screen and restores the planning, before
  // anything else. A robot that is driving or shows another screen is left alone (the value
  // stays owed). Hosts should drop a value older than a few hours (someone may have changed
  // the robot on purpose since).
  int owedPlanning() const { return owed_; }
  void setOwedPlanning(int v) { owed_ = v < 0 ? -1 : (v ? 1 : 0); }
  std::function<void(int)> onOwed;                // optional

  // ---- manual drive (HANDBEDIENING) ------------------------------------------------
  // Home -> planning off -> Enter (HANDM. ROUTE) -> Enter (safety question) -> Ja.
  // Answering Ja means the user has sight of the robot: only call it on a user's request.
  Result enterManual();
  bool inManual() const;
  // Soft1 = left motor on/off, Soft2 = forward/backward, Soft3 = right motor on/off.
  // Settings only, nothing moves. Busy if HANDBEDIENING is not on the screen.
  Result manualKey(Key k);
  // The robot drives while Start/Stop is held: driveBegin sends "down", driveHold keeps it
  // held (repeat every 100 ms) and must be called in a loop, driveEnd
  // sends "up". disconnect() and exitManual() also end a drive.
  Result driveBegin();
  void driveHold(uint32_t ms);
  void driveEnd();
  bool driving() const { return driving_; }
  ManualState manualState() const { return screen.manual(); }
  bool linked() { return t_.linkUp(); }
  // Leave HANDBEDIENING: Esc back to the home screen (via the main menu if needed) and
  // switch the planning back on if enterManual switched it off.
  Result exitManual();
  void idle(uint32_t ms);          // stay connected: handle incoming data + keep-alive

  const std::string& lastError() const { return err_; }
  bool imageVersionChanged() const { return versionChanged_; }
  // How far the robot is with sending its image set, 0-100; -1 = not sending (or count unknown).
  int syncPercent() const { return imageNr_ < 0 || images_ <= 0 ? -1 : imageNr_ >= images_ ? 100 : imageNr_ * 100 / images_; }

  Screen screen;
  Lang lang = Lang::Nl;                           // of lastError() and the log
  std::function<void(const std::string&)> log;   // optional
  std::function<void(Step)> onStep;               // optional

 private:
  using Pred = std::function<bool(const Screen&)>;
  bool waitUntil(const Pred& p, uint32_t ms);
  bool tap(Key k, const Pred& until, uint32_t ms = 4000);
  // Wait until the screen did not change for `quiet` ms (redraw done), at most `ms`.
  void settle(uint32_t quiet = 400, uint32_t ms = 4000);
  Result goHome();
  Result switchPlanning(bool on);   // setPlanning without changing what finish() restores
  bool onMainMenu() const;
  void mainMenuToHome();
  Result select(char letter);
  Result restore(bool wasOn);
  void owe(int v);
  void track();                     // after a key: nothing owed once back as found
  void payBack();                   // at connect: settle what an earlier session owed
  Result fail(Result r, const std::string& why);
  void onBytes(const uint8_t* d, size_t n);
  void onCan(const uint8_t d[8]);
  void keepAlive();
  void say(const std::string& s) { if (log) log(s); }
  const char* tr(const char* nl, const char* en) const { return lang == Lang::En ? en : nl; }
  void step(Step s) { if (onStep) onStep(s); }

  Transport& t_;
  Bytes pin_;
  uint8_t* ver_;
  Parser parser_;
  std::string err_;
  int granted_ = -1;              // -1 waiting, 0 no, 1 yes
  bool versionChanged_ = false, connected_ = false;
  bool driving_ = false, manualWasOn_ = false;
  int planningAtConnect_ = -1;      // 1 / 0 as found at connect, -1 unknown
  int owed_ = -1;                   // see owedPlanning()
  uint32_t lastRepeat_ = 0;
  uint32_t lastAlive_ = 0;
  int images_ = 0, imageNr_ = -1;   // image set: count, number of the image now coming
  uint32_t lastRx_ = 0;             // when the robot last sent anything
};

}  // namespace lely
