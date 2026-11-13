// The Lely Discovery's implementation of the generic C API (core/scraper_c.h).
// One worker thread per session runs the blocking C++ Robot API; bytes from the
// host are queued by scraper_feed() and handed to the robot inside Transport::pump().
#include "scraper_c.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

#include "LelyRobot.h"

using namespace lely;

namespace {

class HostTransport : public Transport {
 public:
  HostTransport(scraper_write_fn w, void* user) : write_(w), user_(user) {}

  bool connect() override { return up_; }            // the host already opened BLE
  void disconnect() override {}                       // the host closes BLE after scraper_free
  bool linkUp() override { return up_ && !stop_; }
  bool write(const Bytes& f) override {
    if (!linkUp()) return false;
    uint8_t* copy = (uint8_t*)malloc(f.empty() ? 1 : f.size());   // released by the host
    if (!copy) return false;
    memcpy(copy, f.data(), f.size());
    write_(user_, copy, (int32_t)f.size());
    return true;
  }
  uint32_t now() override {
    using namespace std::chrono;
    return (uint32_t)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
  }
  void pump(uint32_t ms) override {
    Bytes chunk;
    {
      std::unique_lock<std::mutex> l(mu_);
      cv_.wait_for(l, std::chrono::milliseconds(ms), [this] { return !inbox_.empty() || stop_ || !up_; });
      chunk.swap(inbox_);
    }
    if (!chunk.empty() && onData) onData(chunk.data(), chunk.size());
  }

  void feed(const uint8_t* d, size_t n) {
    std::lock_guard<std::mutex> g(mu_);
    inbox_.insert(inbox_.end(), d, d + n);
    cv_.notify_all();
  }
  void down() { std::lock_guard<std::mutex> g(mu_); up_ = false; cv_.notify_all(); }
  void stop() { std::lock_guard<std::mutex> g(mu_); stop_ = true; cv_.notify_all(); }

 private:
  scraper_write_fn write_;
  void* user_;
  std::mutex mu_;
  std::condition_variable cv_;
  Bytes inbox_;
  bool up_ = true, stop_ = false;
};

std::string jsonString(const std::string& s) {
  std::string o = "\"";
  for (char c : s) {
    switch (c) {
      case '"': o += "\\\""; break;
      case '\\': o += "\\\\"; break;
      case '\n': o += "\\n"; break;
      case '\r': o += "\\r"; break;
      case '\t': o += "\\t"; break;
      default:
        if ((unsigned char)c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else if ((unsigned char)c >= 0x80) o += '?';   // robot font is ASCII; keep JSON valid UTF-8
        else o += c;
    }
  }
  return o + "\"";
}

std::string statusJson(const Status& st, const std::string& screen, const std::string& highlighted, int sync) {
  std::string o = "{";
  o += "\"planning\":" + std::to_string(st.planning);
  o += ",\"clock\":" + jsonString(st.clock);
  o += ",\"nextRide\":" + jsonString(st.nextRide);
  o += ",\"route\":" + jsonString(st.route);
  o += ",\"action\":" + jsonString(st.action);
  o += ",\"actionNo\":" + jsonString(st.actionNo);
  o += ",\"rideNo\":" + jsonString(st.rideNo);
  o += ",\"batteryMv\":" + std::to_string(st.batteryMv);
  o += std::string(",\"busy\":") + (st.busy ? "true" : "false");
  o += std::string(",\"driving\":") + (st.driving ? "true" : "false");
  o += std::string(",\"stopQuestion\":") + (st.stopQuestion ? "true" : "false");
  o += ",\"screen\":" + jsonString(screen);
  o += ",\"highlighted\":" + jsonString(highlighted);
  o += ",\"language\":" + jsonString(st.language);
  o += ",\"sync\":" + std::to_string(sync);
  return o + "}";
}

int32_t copyOut(const std::string& s, char* buf, int32_t size) {
  if (buf && size > 0) {
    size_t n = s.size() < (size_t)size - 1 ? s.size() : (size_t)size - 1;
    memcpy(buf, s.data(), n);
    buf[n] = 0;
  }
  return (int32_t)s.size();
}

}  // namespace

struct scraper_session {
  scraper_session(const char* pin, const uint8_t* ver, scraper_write_fn w, scraper_done_fn d, void* u)
      : link(w, u), done(d), user(u), robot(link, encryptPin(pin), version) {
    if (ver) memcpy(version, ver, 9);
    auto robotHandler = link.onData;                 // set by Robot; add a snapshot after it
    link.onData = [this, robotHandler](const uint8_t* b, size_t n) {
      robotHandler(b, n);
      snapshot();
    };
    worker = std::thread([this] { run(); });
  }

  ~scraper_session() {
    {
      std::lock_guard<std::mutex> g(mu);
      quit = true;
    }
    link.stop();
    cv.notify_all();
    worker.join();
  }

  int32_t submit(int32_t cmd, std::function<Result()> job) {
    std::lock_guard<std::mutex> g(mu);
    if (busy) return SCRAPER_IN_PROGRESS;
    busy = true;
    pendingCmd = cmd;
    pending = std::move(job);
    cv.notify_all();
    return SCRAPER_OK;
  }

  int32_t wait() {
    std::unique_lock<std::mutex> l(mu);
    cv.wait(l, [this] { return !busy; });
    return lastResult;
  }

  void snapshot() {   // worker thread
    std::string js = statusJson(robot.status(), robot.screen.text(), robot.screen.highlighted(), robot.syncPercent());
    std::lock_guard<std::mutex> g(mu);
    status = js;
    error = robot.lastError();
    versionChanged = robot.imageVersionChanged();
    memcpy(versionCopy, version, 9);
    owed = robot.owedPlanning();
  }

  void run() {   // worker thread
    for (;;) {
      std::function<Result()> job;
      int32_t cmd = 0;
      {
        std::lock_guard<std::mutex> g(mu);
        if (quit) break;
        if (pending) { job.swap(pending); cmd = pendingCmd; }
      }
      if (!job) {
        robot.idle(50);   // keep-alive while connected and idle
        continue;
      }
      Result r = job();
      if (cmd == SCRAPER_CMD_CONNECT && r == Result::Ok) connected = true;
      snapshot();
      {
        std::lock_guard<std::mutex> g(mu);
        lastResult = (int32_t)r;
        busy = false;
      }
      cv.notify_all();
      if (done) done(user, cmd, (int32_t)r);
    }
    if (connected) robot.disconnect();
  }

  HostTransport link;
  scraper_done_fn done;
  void* user;
  uint8_t version[9] = {0};
  Robot robot;

  std::mutex mu;
  std::condition_variable cv;
  std::function<Result()> pending;
  int32_t pendingCmd = 0, lastResult = SCRAPER_OK;
  bool busy = false, quit = false, connected = false, versionChanged = false;
  int32_t owed = -1, owedIn = -1;
  std::atomic<int> holdGen{0}, releasedGen{0};   // a hold runs until its number is released
  std::string status = "{}", error, routes = "{}";
  uint8_t versionCopy[9] = {0};
  std::thread worker;
};

extern "C" {

scraper_session* scraper_new(const char* pin, const uint8_t* ver, scraper_write_fn w, scraper_done_fn d, void* user) {
  if (!w) return nullptr;
  return new scraper_session(pin, ver, w, d, user);
}

void scraper_free(scraper_session* s) { delete s; }

void scraper_on_progress(scraper_session* s, scraper_progress_fn fn) {
  if (!s) return;
  void* user = s->user;
  if (fn) s->robot.onStep = [s, fn, user](Step st) { s->snapshot(); fn(user, (int32_t)st); };
  else s->robot.onStep = nullptr;
}

void scraper_set_language(scraper_session* s, const char* code) {
  if (!s) return;
  std::lock_guard<std::mutex> g(s->mu);
  s->robot.lang = langFromCode(code);
}

void scraper_release(const uint8_t* data) { free((void*)data); }

void scraper_feed(scraper_session* s, const uint8_t* data, int32_t len) {
  if (s && data && len > 0) s->link.feed(data, (size_t)len);
}

void scraper_link_down(scraper_session* s) { if (s) s->link.down(); }

int32_t scraper_connect(scraper_session* s) {
  return s->submit(SCRAPER_CMD_CONNECT, [s] {
    int32_t owed;
    {
      std::lock_guard<std::mutex> g(s->mu);
      owed = s->owedIn;
    }
    s->robot.setOwedPlanning(owed);
    return s->robot.connect();
  });
}

void scraper_set_owed_planning(scraper_session* s, int32_t owed) {
  if (!s) return;
  std::lock_guard<std::mutex> g(s->mu);
  s->owedIn = owed;
}

int32_t scraper_owed_planning(scraper_session* s) {
  if (!s) return -1;
  std::lock_guard<std::mutex> g(s->mu);
  return s->owed;
}

int32_t scraper_start_route(scraper_session* s, char letter, int32_t dry_run) {
  return s->submit(SCRAPER_CMD_ROUTE, [s, letter, dry_run] { return s->robot.startRoute(letter, dry_run != 0); });
}

int32_t scraper_read_routes(scraper_session* s) {
  return s->submit(SCRAPER_CMD_ROUTES, [s] {
    std::vector<std::string> out;
    Result r = s->robot.readRoutes(out);
    if (r == Result::Ok) {
      std::string js = "{";
      for (int i = 0; i < 4; i++) {
        std::string v;
        for (char c : out[i]) if (c != '"' && c != '\\' && (unsigned char)c >= 32) v += c;
        js += std::string(i ? "," : "") + "\"" + char('A' + i) + "\":\"" + v + "\"";
      }
      js += "}";
      std::lock_guard<std::mutex> g(s->mu);
      s->routes = js;
    }
    return r;
  });
}

int32_t scraper_routes_json(scraper_session* s, char* buf, int32_t size) {
  std::lock_guard<std::mutex> g(s->mu);
  return copyOut(s->routes, buf, size);
}

int32_t scraper_stop_route(scraper_session* s) {
  return s->submit(SCRAPER_CMD_STOP, [s] { return s->robot.stopRoute(); });
}

int32_t scraper_answer_stop(scraper_session* s, int32_t finish) {
  return s->submit(SCRAPER_CMD_ANSWER_STOP, [s, finish] { return s->robot.answerStop(finish != 0); });
}

int32_t scraper_set_planning(scraper_session* s, int32_t on) {
  return s->submit(SCRAPER_CMD_PLANNING, [s, on] { return s->robot.setPlanning(on != 0); });
}

int32_t scraper_press(scraper_session* s, int32_t key) {
  return s->submit(SCRAPER_CMD_PRESS, [s, key] { return s->robot.press((Key)key); });
}

int32_t scraper_hold(scraper_session* s, int32_t key) {
  int gen = ++s->holdGen;
  return s->submit(SCRAPER_CMD_HOLD, [s, key, gen] {
    uint32_t start = s->link.now();
    s->robot.holdBegin((Key)key);
    // the robot needs a press of some length, also for a quick tap on a touch screen
    while ((s->releasedGen < gen || s->link.now() - start < 80) && s->robot.linked() && s->link.now() - start < 120000)
      s->robot.driveHold(20);
    s->robot.holdEnd();
    return Result::Ok;
  });
}

void scraper_unhold(scraper_session* s) {
  if (s) s->releasedGen = s->holdGen.load();
}

int32_t scraper_finish(scraper_session* s) {
  return s->submit(SCRAPER_CMD_FINISH, [s] { return s->robot.finish(); });
}

int32_t scraper_wait(scraper_session* s) { return s->wait(); }

int32_t scraper_status_json(scraper_session* s, char* buf, int32_t size) {
  std::lock_guard<std::mutex> g(s->mu);
  return copyOut(s->status, buf, size);
}

int32_t scraper_last_error(scraper_session* s, char* buf, int32_t size) {
  std::lock_guard<std::mutex> g(s->mu);
  return copyOut(s->error, buf, size);
}

int32_t scraper_image_version(scraper_session* s, uint8_t* out9) {
  std::lock_guard<std::mutex> g(s->mu);
  if (out9) memcpy(out9, s->versionCopy, 9);
  return s->versionChanged ? 1 : 0;
}

int32_t scraper_encrypt_pin(const char* pin, uint8_t* out, int32_t size) {
  Bytes b = encryptPin(pin ? pin : "");
  if (out) memcpy(out, b.data(), b.size() < (size_t)size ? b.size() : (size_t)size);
  return (int32_t)b.size();
}

const char* scraper_result_text_lang(int32_t r, const char* code) {
  Lang l = langFromCode(code);
  if (r == SCRAPER_IN_PROGRESS) return l == Lang::En ? "already busy" : "al bezig";
  return resultText((Result)r, l);
}

}  // extern "C"

int32_t scraper_links_json(char* buf, int32_t size) {
  std::string j = std::string("[{\"kind\":\"ble-serial\",\"service\":\"") + kBle.service + "\",\"rx\":\"" + kBle.rx + "\",\"tx\":\"" + kBle.tx +
                  "\",\"addressPrefix\":\"" + kBle.addressPrefix + "\"}," +
                  "{\"kind\":\"bt-serial\",\"service\":\"" + kSppService + "\",\"addressPrefix\":\"" + kBle.addressPrefix + "\"}]";
  return copyOut(j, buf, size);
}
