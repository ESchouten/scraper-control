#include "LelyProtocol.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <set>

namespace lely {
namespace {

constexpr uint16_t kCanMsg = 1536, kCanAddr = 245, kApp = 244, kNode = 57;
constexpr uint32_t kPanel = 1022;

void put16(Bytes& b, uint16_t v) { b.push_back(v & 0xFF); b.push_back(v >> 8); }

// One 16-byte CAN record: id u32, 8 data bytes, length, 3 padding.
void canRecord(Bytes& out, const Bytes& data, uint8_t length) {
  out.push_back(kPanel & 0xFF); out.push_back((kPanel >> 8) & 0xFF);
  out.push_back(0); out.push_back(0);
  for (size_t i = 0; i < 8; i++) out.push_back(i < data.size() ? data[i] : 0);
  out.push_back(length); out.push_back(0); out.push_back(0); out.push_back(0);
}

Bytes canMsg(std::initializer_list<std::pair<Bytes, uint8_t>> recs) {
  Bytes payload;
  for (auto& r : recs) canRecord(payload, r.first, r.second);
  return lfw(kCanMsg, kCanAddr, kApp, payload);
}

Bytes u16pair(uint16_t a, uint16_t b) { Bytes x; put16(x, a); put16(x, b); return x; }
const std::pair<Bytes, uint8_t> kTail = {u16pair(1282, 0), 2};   // [2,5]

std::string lower(std::string s) {
  for (auto& c : s) c = (char)std::tolower((unsigned char)c);
  return s;
}

std::string trim(const std::string& s) {
  size_t a = s.find_first_not_of(' '), b = s.find_last_not_of(' ');
  return a == std::string::npos ? "" : s.substr(a, b - a + 1);
}

}  // namespace

Bytes lfw(uint16_t msgId, uint16_t address, uint16_t sender, const Bytes& payload) {
  Bytes b;
  put16(b, msgId); put16(b, address); put16(b, sender); put16(b, (uint16_t)payload.size());
  b.insert(b.end(), payload.begin(), payload.end());
  return b;
}

Bytes computerType() { return lfw(2010, kNode, kNode, {1, 1}); }
Bytes getNodeSetting() { return lfw(2000, kNode, kApp); }
Bytes askPermission(const Bytes& encPin) { return lfw(2018, kNode, kApp, encPin); }
Bytes connectWithLevel(uint16_t level) { return canMsg({{u16pair(1538, level), 3}, kTail}); }
Bytes connectPanel() { return canMsg({{u16pair(514, 0), 2}, kTail}); }
Bytes reconnect() { return canMsg({{u16pair(258, 0), 3}, kTail}); }
Bytes alive() { return canMsg({{u16pair(262, 0), 2}}); }
Bytes aliveWithLevel(uint16_t level) { return canMsg({{u16pair(518, level), 3}}); }
Bytes canReady() { return canMsg({{Bytes{5, 3}, 2}}); }
Bytes contrast() { return canMsg({{Bytes{3, 20, 1, 255}, 4}}); }
Bytes imageVersion(const uint8_t v[9]) {
  return canMsg({{Bytes{5, 1, 0, v[0], v[1], v[2], v[3], v[4]}, 8},
                 {Bytes{5, 1, 1, v[5], v[6], v[7], v[8], 0}, 7}});
}
Bytes key(uint16_t event, Key k) { return canMsg({{u16pair(event, (uint16_t)k), 4}}); }

std::vector<Message> Parser::feed(const uint8_t* data, size_t len) {
  buf_.insert(buf_.end(), data, data + len);
  std::vector<Message> out;
  while (buf_.size() >= 8) {
    uint16_t n = buf_[6] | (buf_[7] << 8);
    if (buf_.size() < 8u + n) break;
    Message m{(uint16_t)(buf_[0] | buf_[1] << 8), (uint16_t)(buf_[2] | buf_[3] << 8),
              (uint16_t)(buf_[4] | buf_[5] << 8), Bytes(buf_.begin() + 8, buf_.begin() + 8 + n)};
    out.push_back(std::move(m));
    buf_.erase(buf_.begin(), buf_.begin() + 8 + n);
  }
  return out;
}

// ---- Screen -------------------------------------------------------------------
void Screen::clear() { items_.clear(); areas_.clear(); images_.clear(); version_++; whole_ = false; }

void Screen::feed(const uint8_t d[8]) {
  if (d[0] != 3) return;
  uint8_t op = d[1];
  // version() counts redraws only. The home screen rewrites its clock every second and the
  // battery voltage about every 250 ms (captures/, 2026-11-05): a text written again in the
  // same place with the same length is such a live value and does not count.
  // whole_ ends when something on the screen is taken away or replaced (a moved selection
  // bar, another image): from then on the screen is no longer just its first drawing.
  auto put = [this](int y, int x, const std::string& t) {
    auto it = items_.find({y, x});
    if (it == items_.end()) version_++;
    else if (it->second.size() != t.size()) { version_++; whole_ = false; }
    items_[{y, x}] = t;
  };
  if (op == 1) {
    items_.clear(); areas_.clear(); images_.clear();
    version_++;
    whole_ = true;
  } else if (op == 36) {                       // image from the robot's image set: y, x, number
    auto it = images_.find({d[2], d[3]});
    if (it == images_.end()) version_++;
    else if (it->second != d[4]) { version_++; whole_ = false; }
    images_[{d[2], d[3]}] = d[4];
  } else if (op == 30) {                       // text start: y, x, font
    inText_ = true; curY_ = d[2]; curX_ = d[3]; buf_.clear();
    // A redrawn row loses its selection bar (seen 2026-11-05 in Instellingen/Taal: the robot
    // redraws the rows, then inverts only the new one). The header bar (y <= 1) stays.
    for (auto it = areas_.begin(); it != areas_.end();)
      it = it->op == 37 && it->y > 1 && curY_ >= it->y - 2 && curY_ < it->y + it->h - 1 ? (version_++, whole_ = false, areas_.erase(it)) : std::next(it);
  } else if (op == 31 && inText_) {            // text chunk, 0-terminated
    for (int i = 2; i < 8; i++) {
      if (d[i] == 0) {
        // multi-line labels: '\r' separated, 8 px per line
        size_t start = 0; int line = 0;
        while (true) {
          size_t cr = buf_.find('\r', start);
          put(curY_ + 8 * line, curX_, buf_.substr(start, cr == std::string::npos ? std::string::npos : cr - start));
          if (cr == std::string::npos) break;
          start = cr + 1; line++;
        }
        inText_ = false;
        break;
      }
      buf_.push_back((char)d[i]);
    }
  } else if (op == 32) {                       // single char
    put(d[2], d[3], std::string(1, (char)d[5]));
  } else if (op == 13 || op == 15 || op == 33 || op == 37) {
    version_++;
    Area a{op, d[2], d[3], d[4], d[5], d[6]};
    auto inside = [&a](int y, int x) { return x >= a.x && x < a.x + a.w && y >= a.y - 2 && y <= a.y + a.h; };
    if (op == 13) {                            // wipe area: text and selection bars inside it go
      for (auto it = items_.begin(); it != items_.end();)
        it = inside(it->first.first, it->first.second) ? (whole_ = false, items_.erase(it)) : std::next(it);
      for (auto it = areas_.begin(); it != areas_.end();)
        it = (it->op == 15 || it->op == 37) && inside(it->y, it->x) ? (whole_ = false, areas_.erase(it)) : std::next(it);
      return;
    }
    auto same = [&a](const Area& b) { return b.op == a.op && b.y == a.y && b.x == a.x && b.h == a.h && b.w == a.w; };
    auto it = std::find_if(areas_.begin(), areas_.end(), same);
    if (op == 37 && it != areas_.end()) {      // invert (XOR): the same area again undoes it
      areas_.erase(it);
      whole_ = false;
      return;
    }
    if (it != areas_.end()) areas_.erase(it);  // other ops: same place again keeps one
    areas_.push_back(a);
  }
}

std::vector<std::string> Screen::lines() const {
  std::map<int, std::vector<std::pair<int, std::string>>> rows;
  for (auto& kv : items_) rows[kv.first.first].push_back({kv.first.second, kv.second});
  std::vector<std::string> out;
  for (auto& r : rows) {
    std::sort(r.second.begin(), r.second.end());
    std::string line;
    for (auto& t : r.second) { if (!line.empty()) line += ' '; line += t.second; }
    line = trim(line);
    if (!line.empty()) out.push_back(line);
  }
  return out;
}

std::string Screen::text() const {
  std::string s;
  for (auto& l : lines()) { if (!s.empty()) s += '\n'; s += l; }
  return s;
}

bool Screen::contains(const char* needle) const {
  return lower(text()).find(lower(needle)) != std::string::npos;
}

std::string Screen::highlighted() const {
  int y = highlightedRow();
  if (y < 0) return "";
  std::vector<std::pair<int, std::string>> row;
  for (auto& kv : items_) if (kv.first.first == y) row.push_back({kv.first.second, kv.second});
  std::sort(row.begin(), row.end());
  std::string line;
  for (auto& t : row) { if (!line.empty()) line += ' '; line += t.second; }
  return trim(line);
}

int Screen::topRow() const {
  for (auto& kv : items_) if (kv.first.first >= 4 && !trim(kv.second).empty()) return kv.first.first;
  return -1;
}

std::string Screen::valueAt(int y) const {
  std::string v;
  for (auto& kv : items_)
    if (kv.first.first == y && kv.first.second > 8) { if (!v.empty()) v += ' '; v += kv.second; }
  return v;
}

int Screen::highlightedRow() const {
  std::set<int> rows;
  for (auto& a : areas_) {
    if ((a.op != 15 && a.op != 37) || !a.w || !a.h) continue;
    if (a.y <= 1 && a.h >= 10 && a.w >= 120) continue;        // header bar
    for (auto& kv : items_) {
      int y = kv.first.first, x = kv.first.second;
      if (x >= a.x && x < a.x + a.w && y >= a.y - 2 && y < a.y + a.h) rows.insert(y);
    }
  }
  if (rows.size() != 1) return -1;
  return *rows.begin();
}

static bool compactHasArrow(const std::string& t) {
  std::string c;
  for (char ch : t) if (ch != ' ') c += ch;
  return c.find("->") != std::string::npos;
}

int Screen::planning() const {
  // header line: "20:21:19  ON" / "... OFF"
  for (auto& l : lines()) {
    if (l.size() < 10 || l[2] != ':' || l[5] != ':') continue;
    std::string rest = trim(l.substr(8));
    if (rest == "ON") return 1;
    if (rest == "OFF") return 0;
  }
  return -1;
}

ManualState Screen::manual() const {
  ManualState m;
  m.left = m.right = 0;
  for (auto& a : areas_) {   // inverted box at the bottom: L at x ~1, R at x ~86
    if (a.op != 37 || a.y < 44 || a.w < 20) continue;
    if (a.x < 20) m.left = 1;
    else if (a.x >= 70) m.right = 1;
  }
  for (auto& kv : images_) {  // direction arrow, bottom middle
    int y = kv.first.first, x = kv.first.second;
    if (y >= 44 && x >= 40 && x < 80) m.forward = kv.second == 16 ? 1 : kv.second == 15 ? 0 : -1;
  }
  return m;
}

// ---- screen recognition by layout ---------------------------------------------------
int Screen::rowItems(int y0, int y1, int x0, int x1) const {
  int n = 0;
  for (auto& kv : items_) {
    int y = kv.first.first, x = kv.first.second;
    if (y >= y0 && y <= y1 && x >= x0 && x <= x1 && !trim(kv.second).empty()) n++;
  }
  return n;
}

bool Screen::isHome() const {
  if (planning() < 0) return false;            // the driving screen has no clock + ON/OFF
  int rows = 0;
  for (int y = 14; y <= 54; y += 8) rows += rowItems(y, y, 0, 8) > 0;
  return rows >= 4;
}

bool Screen::isRouteMenu() const {
  int rows = 0;
  for (auto& l : lines()) {
    std::string c;
    for (char ch : l) if (ch != ' ') c += ch;
    if (c.size() >= 3 && c[0] >= 'A' && c[0] <= 'D' && c[1] == '-' && c[2] == '>') rows++;
  }
  return rows >= 4;
}

std::vector<std::string> Screen::routeContents() const {
  std::vector<std::string> out(4);
  for (auto& l : lines()) {
    size_t arrow = l.find("->");
    if (arrow == std::string::npos) continue;
    std::string head;
    for (size_t i = 0; i < arrow; i++) if (l[i] != ' ') head += l[i];
    if (head.size() != 1 || head[0] < 'A' || head[0] > 'D') continue;
    std::string parts, tok;
    auto flush = [&] {
      if (!tok.empty() && tok != "-") parts += (parts.empty() ? "" : " ") + tok;
      tok.clear();
    };
    for (size_t i = arrow + 2; i < l.size(); i++) {
      if (l[i] == ' ') flush(); else tok += l[i];
    }
    flush();
    out[head[0] - 'A'] = parts;
  }
  return out;
}

bool Screen::isMainMenu() const {
  if (planning() >= 0 || isRouteMenu() || isManual() || rowItems(0, 3, 0, 127)) return false;   // no title
  int rows = 0;
  for (int y = 4; y <= 44; y += 8) rows += rowItems(y, y, 0, 8) > 0;
  return rows >= 5;
}

// The direction arrow (image 16 forward / 15 backward) in the bottom middle box. The L/R
// boxes alone are not enough: the route menu and the questions have the same boxes.
bool Screen::isManual() const {
  for (auto& kv : images_)
    if (kv.first.first >= 44 && kv.first.second >= 40 && kv.first.second < 80 && (kv.second == 15 || kv.second == 16))
      return true;
  return false;
}

// While a route runs (seen 2026-11-03, NL): title "ROUTE->TIJDPAD" (EN "ROUTE->TIMEPATH")
// on top instead of the clock, then label rows at x = 4 (route, ride no., action, ...).
bool Screen::isDriving() const {
  if (planning() >= 0 || isRouteMenu() || isManual() || isMainMenu()) return false;
  bool arrow = false;
  for (auto& kv : items_)
    if (kv.first.first <= 5 && compactHasArrow(kv.second)) arrow = true;
  if (!arrow) return false;
  int rows = 0;
  for (int y = 14; y <= 46; y += 8) rows += rowItems(y, y, 0, 8) > 0;
  return rows >= 3 && !isQuestion();
}

bool Screen::isQuestion() const {
  if (isHome() || isRouteMenu() || isManual()) return false;
  int left = rowItems(52, 56, 0, 40), right = rowItems(52, 56, 60, 127), mid = rowItems(52, 56, 41, 59);
  return left == 1 && right == 1 && mid == 0;
}

bool Screen::isStopQuestion() const {
  if (isHome() || isRouteMenu() || isManual()) return false;
  return rowItems(52, 56, 0, 40) == 1 && rowItems(52, 56, 41, 84) == 1 && rowItems(52, 56, 85, 127) == 1;
}

bool Screen::drawn() const {
  if (!whole_) return false;
  if (isHome()) return !valueAt(54).empty();                         // the battery voltage
  if (isRouteMenu() || isMainMenu()) return highlightedRow() >= 0;   // the selection bar
  return isQuestion();                                               // the right-hand answer
}

std::string Screen::language() const {
  // Words seen on the real robot (2026-11-05), Dutch and English. Lower case.
  static const char* nl[] = {"vlg rit", "actienr", "ritnr", "accu", "handm. route", "werken", "testen",
                             "instellingen", "alarmen", "handbediening", "veiligheid", "tijdpad", "nee"};
  static const char* en[] = {"nextrun", "act.nr", "runnr", "battery", "manual route", "settings",
                             "alarms", "manual control", "safety warning", "timepath", "confirm?"};
  int n = 0, e = 0;
  for (auto w : nl) n += contains(w) ? 1 : 0;
  for (auto w : en) e += contains(w) ? 1 : 0;
  if (n > e) return "nl";
  if (e > n) return "en";
  return "";
}

Status parseStatus(const Screen& s) {
  Status st;
  st.language = s.language();
  st.driving = s.isDriving();
  st.stopQuestion = s.isStopQuestion();
  st.planning = s.planning();
  st.busy = !(s.isHome() || s.isRouteMenu());
  for (auto& l : s.lines())
    if (l.size() >= 10 && l[2] == ':' && l[5] == ':' && st.clock.empty()) st.clock = l.substr(0, 8);
  auto battery = [&st](const std::string& v) {
    if (!v.empty() && std::all_of(v.begin(), v.end(), ::isdigit)) st.batteryMv = std::stoi(v);
  };
  if (s.isHome()) {
    // Home screen: the value of each row, whatever the label says (any language).
    // Rows: next ride, route, action, action no., ride no., battery (mV).
    std::string* field[5] = {&st.nextRide, &st.route, &st.action, &st.actionNo, &st.rideNo};
    for (int i = 0; i < 6; i++) {
      std::string v = trim(s.valueAt(14 + 8 * i));
      if (i < 5) *field[i] = v;
      else battery(v);
    }
    return st;
  }
  // Other screens (driving: ROUTE->TIJDPAD): by label, Dutch and English.
  for (auto& l : s.lines()) {
    size_t c = l.find(':');
    if (c == std::string::npos) continue;
    std::string label = lower(trim(l.substr(0, c))), val = trim(l.substr(c + 1));
    if (label == "vlg rit" || label == "nextrun") st.nextRide = val;
    else if (label == "route") st.route = val;
    else if (label == "actie" || label == "action") st.action = val;
    else if (label == "actienr" || label == "act.nr") st.actionNo = val;
    else if (label == "ritnr" || label == "runnr") st.rideNo = val;
    else if (label == "accu" || label == "battery") battery(val);
  }
  return st;
}

}  // namespace lely
