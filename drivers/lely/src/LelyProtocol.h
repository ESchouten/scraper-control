// Lely Discovery protocol: frames, parser and screen decoding. Pure C++17,
// no Bluetooth and no Arduino, so it also builds and runs on a PC for tests.
// Wire format and screen model (see ../PROTOCOL.md).
#pragma once
#include <cstdint>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace lely {

// How the robot is reached over BLE: the Nordic UART Service (write RX, notifications from TX).
// The robot does not advertise the service; its address starts with Lely's prefix 00:12:FF.
struct BleInfo { const char* service; const char* rx; const char* tx; const char* addressPrefix; };
constexpr BleInfo kBle = {"6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                          "6e400003-b5a3-f393-e0a9-e50e24dcca9e", "00:12:ff"};
// Older robots speak classic Bluetooth instead: the serial port profile (SPP), the same
// address prefix and the same frames.
constexpr const char* kSppService = "00001101-0000-1000-8000-00805f9b34fb";

using Bytes = std::vector<uint8_t>;

// Panel keys.
enum class Key : uint16_t {
  Back = 1, Soft2 = 2, Soft1 = 4, Up = 8, Prog = 16, Down = 32,
  Soft3 = 64, Enter = 128, StartStop = 256,
};

// ---- outgoing frames --------------------------------------------------------
Bytes lfw(uint16_t msgId, uint16_t address, uint16_t sender, const Bytes& payload = {});
Bytes computerType();                         // 2010
Bytes getNodeSetting();                       // 2000
Bytes askPermission(const Bytes& encPin);     // 2018 (empty = no PIN set)
Bytes connectWithLevel(uint16_t level = 1);   // CAN [2,6,level] + [2,5]
Bytes connectPanel();                         // CAN [2,2] + [2,5]
Bytes reconnect();                            // CAN [2,1..] answer to robot [2,0]
Bytes alive();                                // CAN [6,1]
Bytes aliveWithLevel(uint16_t level = 1);     // CAN [6,2,level]
Bytes canReady();                             // CAN [5,3]
Bytes imageVersion(const uint8_t v[9]);       // CAN [5,1,0,v0..4] + [5,1,1,v5..8]
Bytes contrast();                             // CAN [3,20,1,255]
Bytes key(uint16_t event, Key k);             // event 0 = down, 7 = up

// PIN for the 2018 permission request (AES-128-CBC, zero IV, PKCS7, app key). "" = none.
Bytes encryptPin(const char* pin);
Bytes aes128CbcEncrypt(const uint8_t key[16], const Bytes& plain);

// ---- incoming stream --------------------------------------------------------
struct Message {
  uint16_t msgId, address, sender;
  Bytes payload;
};

class Parser {
 public:
  // Feed raw notification bytes; returns complete messages.
  std::vector<Message> feed(const uint8_t* data, size_t len);
 private:
  Bytes buf_;
};

// ---- screen -----------------------------------------------------------------
// HANDBEDIENING screen (seen 2026-11-05): the L and R boxes are inverted (op 37) while that
// motor is on; the image in the middle box is 16 forward, 15 backward. 1 / 0, -1 unknown.
struct ManualState { int left = -1, right = -1, forward = -1; };

class Screen {
 public:
  void feed(const uint8_t d[8]);              // one CAN record with d[0] == 3
  void clear();
  std::vector<std::string> lines() const;     // top to bottom
  std::string text() const;                   // lines joined by '\n'
  bool contains(const char* needle) const;    // case-insensitive
  std::string highlighted() const;            // selected menu row, "" if unknown
  int highlightedRow() const;                 // its y, -1 if none or more than one
  int topRow() const;                         // y of the first text row below the title, -1 none
  std::string valueAt(int y) const;           // a row's text right of its label (x > 8)
  int planning() const;                       // 1 = ON, 0 = OFF, -1 = not shown
  ManualState manual() const;                 // only meaningful on the HANDBEDIENING screen
  // The robot's own language, from words on the screen: "nl", "en", or "" if not known
  // (another language, or a screen without known words). Only for choosing the language
  // of a user interface: screen recognition never depends on it.
  std::string language() const;

  // Which screen this is, by layout only, so it works in every robot language
  // (Dutch and English captured 2026-11-05: same positions, other words).
  bool isHome() const;        // clock "hh:mm:ss ON|OFF" on top + label rows at x = 4
  bool isRouteMenu() const;   // HANDM. ROUTE: four rows "A ->" .. "D ->"
  // On the route menu: what each route consists of, e.g. "1", "2 3", "1 2 3" (the sub-routes
  // after "->", '-' left out). Index 0..3 = A..D; empty strings when not on the menu.
  std::vector<std::string> routeContents() const;
  bool isMainMenu() const;    // Werken/Routes/...: six rows from the top, no title, no clock
  bool isQuestion() const;    // two answers on the bottom row, left (soft1) and right (soft3)
  // After Start/Stop while driving (real robot 2026-11-11): "ROUTE GESTOPT / Route stop gezet:
  // route afmaken?" with three answers on the bottom row: Ja (soft1) / Pauze (soft2) / Nee (soft3).
  bool isStopQuestion() const;
  bool isManual() const;      // HANDBEDIENING: L/R boxes and the direction image at the bottom
  bool isDriving() const;     // ROUTE->TIJDPAD: title with "->" on top, no clock, label rows at x = 4
  uint32_t version() const { return version_; }   // counts redraws (not the clock and battery)
  // The robot draws each screen in a fixed order (captures/): true once the last piece of the
  // home screen, the route menu, the main menu or a question is there, so the robot is done
  // drawing it. False for other screens and once a screen changed in place (a moved selection
  // bar): where those end is not known.
  bool drawn() const;

 private:
  struct Area { uint8_t op, y, x, h, w, c; };
  std::map<std::pair<int, int>, std::string> items_;   // (y, x) -> text
  std::vector<Area> areas_;
  std::map<std::pair<int, int>, int> images_;          // (y, x) -> image number (op 36)
  int rowItems(int y0, int y1, int x0, int x1) const;  // text items with y0<=y<=y1, x0<=x<=x1
  bool inText_ = false;
  int curX_ = 0, curY_ = 0;
  std::string buf_;
  uint32_t version_ = 0;
  bool whole_ = false;   // only the first drawing after a clear (op 1), nothing replaced since
};

// Parsed home screen, same fields as the Python Status.
struct Status {
  int planning = -1;              // 1 ON, 0 OFF, -1 unknown
  std::string clock, nextRide, route, action, actionNo, rideNo;
  int batteryMv = -1;
  bool busy = true;               // not on home screen / route menu
  bool driving = false;           // a route runs (Screen::isDriving): stopRoute() can stop it
  bool stopQuestion = false;      // stopped route asks "finish?": Robot::answerStop()
  std::string language;           // Screen::language()
};
Status parseStatus(const Screen& s);

}  // namespace lely
