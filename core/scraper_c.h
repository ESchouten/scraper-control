/* The robot API in C, the same for every robot driver (drivers/<name>/ implements it), for
 * hosts that do Bluetooth themselves: the apps (JNI, cinterop) and Python (ctypes). The M5Stack
 * uses the driver's C++ API directly.
 *
 * The driver works with bytes in and bytes out; how they travel is the host's job. The host
 * opens the link the driver asks for (scraper_link_json) and then:
 *   s = scraper_new(pin, version, on_write, on_done, user)
 *   bytes from the robot     -> scraper_feed(s, bytes, n)      (any thread)
 *   on_write(user, bytes, n) -> send them to the robot, then scraper_release(bytes)
 *   scraper_connect(s); ... on_done(user, SCRAPER_CMD_CONNECT, SCRAPER_OK)
 *   scraper_start_route(s, 'A', 0); ... on_done(user, SCRAPER_CMD_ROUTE, result)
 *   scraper_free(s); close the link
 * Commands run one at a time on the session's own thread; on_write and on_done are
 * called from that thread. All logic (screens, steps, safety rules) lives in the driver. */
#ifndef SCRAPER_C_H
#define SCRAPER_C_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define SCRAPER_API __declspec(dllexport)
#else
#define SCRAPER_API __attribute__((visibility("default"))) __attribute__((used))
#endif

typedef struct scraper_session scraper_session;
/* data is a copy owned by the host: call scraper_release(data) when done with it, so an
 * asynchronous host (Dart's NativeCallable.listener) can read it later. */
typedef void (*scraper_write_fn)(void* user, const uint8_t* data, int32_t len);
typedef void (*scraper_done_fn)(void* user, int32_t command, int32_t result);

/* results */
enum {
  SCRAPER_OK = 0,
  SCRAPER_CONNECT_FAILED = 1,
  SCRAPER_PIN_REJECTED = 2,
  SCRAPER_NO_SCREEN = 3,
  SCRAPER_BUSY = 4,
  SCRAPER_UNEXPECTED = 5,
  SCRAPER_STARTED_NO_ANSWER = 6,
  SCRAPER_IN_PROGRESS = 100, /* returned by a command call while another one runs */
};

/* progress steps, reported to the progress callback while a command runs */
enum {
  SCRAPER_STEP_LINKED = 1,
  SCRAPER_STEP_GRANTED = 2,
  SCRAPER_STEP_SCREEN_READY = 3,
  SCRAPER_STEP_PLANNING_OFF = 4,
  SCRAPER_STEP_PLANNING_ON = 5,
  SCRAPER_STEP_MENU = 6,
  SCRAPER_STEP_SELECTED = 7,
  SCRAPER_STEP_START_PRESSED = 8,
  SCRAPER_STEP_ANSWERED = 9,
  SCRAPER_STEP_RESTORED = 10,
};
typedef void (*scraper_progress_fn)(void* user, int32_t step);

/* commands, as reported to on_done */
enum {
  SCRAPER_CMD_CONNECT = 1,
  SCRAPER_CMD_ROUTE = 2,
  SCRAPER_CMD_PLANNING = 3,
  SCRAPER_CMD_PRESS = 4,
  SCRAPER_CMD_FINISH = 5,
  SCRAPER_CMD_STOP = 6,
  SCRAPER_CMD_ROUTES = 7,
  SCRAPER_CMD_ANSWER_STOP = 8,
};

/* pin: the robot's PIN ("" or NULL = none). image_version: 9 bytes from last
 * time, or NULL. The link counts as up until scraper_link_down(). */
SCRAPER_API scraper_session* scraper_new(const char* pin, const uint8_t* image_version,
                                scraper_write_fn on_write, scraper_done_fn on_done, void* user);
SCRAPER_API void scraper_free(scraper_session* s); /* stops the thread; waits for a running command */

/* Optional: progress steps for a UI (called from the session thread). Set before commands. */
SCRAPER_API void scraper_on_progress(scraper_session* s, scraper_progress_fn on_progress);

SCRAPER_API void scraper_release(const uint8_t* data); /* frees a buffer passed to on_write */

SCRAPER_API void scraper_feed(scraper_session* s, const uint8_t* data, int32_t len);
SCRAPER_API void scraper_link_down(scraper_session* s); /* BLE dropped: running waits end quickly */

/* Start a command. Returns SCRAPER_OK (started) or SCRAPER_IN_PROGRESS. */
SCRAPER_API int32_t scraper_connect(scraper_session* s);
SCRAPER_API int32_t scraper_start_route(scraper_session* s, char letter, int32_t dry_run);
SCRAPER_API int32_t scraper_set_planning(scraper_session* s, int32_t on);
/* Safety stop: only while a route runs ("driving": true in the status), one tap on
 * Start/Stop. A route button pressed during a route should call this, not start. */
SCRAPER_API int32_t scraper_stop_route(scraper_session* s);
/* The robot then asks "route afmaken?" ("stopQuestion": true in the status). Pass the user's
 * answer on: finish 1 = Ja (drives on), 0 = Nee (route over, planning stays off). Nothing is
 * pressed if the question is not on the screen. */
SCRAPER_API int32_t scraper_answer_stop(scraper_session* s, int32_t finish);
SCRAPER_API int32_t scraper_press(scraper_session* s, int32_t key); /* the driver's panel key codes */
/* Read what routes A..D consist of (HANDM. ROUTE), then back as before. Afterwards
 * scraper_routes_json gives {"A":"1","B":"2 3","C":"1 2 3","D":"4"} ("{}" before). */
SCRAPER_API int32_t scraper_read_routes(scraper_session* s);
SCRAPER_API int32_t scraper_routes_json(scraper_session* s, char* buf, int32_t size);
/* Before scraper_free: back to the home screen from a safe menu and the planning back to how it
 * was at connect (unless scraper_set_planning changed it on purpose). */
SCRAPER_API int32_t scraper_finish(scraper_session* s);

/* Planning owed after a dropped link (see the driver): 1 / 0 = the
 * planning state the robot must be put back to, -1 = nothing owed. Read it after every
 * on_done and keep it (with the time) per robot; hand a recent value (a few hours at most)
 * to the next session with scraper_set_owed_planning before scraper_connect, which then puts the
 * robot back first. */
SCRAPER_API void scraper_set_owed_planning(scraper_session* s, int32_t owed);
SCRAPER_API int32_t scraper_owed_planning(scraper_session* s);

/* Block until the running command is done; returns its result (SCRAPER_OK if none). */
SCRAPER_API int32_t scraper_wait(scraper_session* s);

/* Copies into buf (NUL-terminated, cut to size). Return the full length. */
SCRAPER_API int32_t scraper_status_json(scraper_session* s, char* buf, int32_t size);
SCRAPER_API int32_t scraper_last_error(scraper_session* s, char* buf, int32_t size);
/* Copies the 9-byte image version; returns 1 if the robot sent a new one. */
SCRAPER_API int32_t scraper_image_version(scraper_session* s, uint8_t* out9);
SCRAPER_API const char* scraper_result_text(int32_t result); /* Dutch, for the UI */
/* The PIN as the core sends it into out (for the fake robots in tests);
 * returns its length (16 or 32 bytes for up to 15 / 31 characters). For the apps' fake robot. */
SCRAPER_API int32_t scraper_encrypt_pin(const char* pin, uint8_t* out, int32_t size);
/* The same in a language: "nl" or "en" (anything else: Dutch). */
SCRAPER_API const char* scraper_result_text_lang(int32_t result, const char* language);
/* Language of scraper_last_error and the log: "nl" (default) or "en". The status JSON has
 * "language": the robot's own language ("nl", "en" or "" if not known). */
SCRAPER_API void scraper_set_language(scraper_session* s, const char* language);

/* How to find and reach this driver's robot, as JSON with a "kind" and its details.
 * Hosts implement each kind once; a driver that talks another way names a new kind.
 *   "ble-serial": {"service", "rx", "tx" (UUIDs), "addressPrefix" (lower case, "" = any)}:
 *                 write to rx without response, notifications from tx.
 * Copies into buf like scraper_status_json. */
SCRAPER_API int32_t scraper_link_json(char* buf, int32_t size);

#ifdef __cplusplus
}
#endif
#endif
