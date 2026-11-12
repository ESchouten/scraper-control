// The buttons' screens, drawn with LVGL in the app's look (red header, light cards, Inter).
//
// The screen code in main.cpp keeps its simple shape: a draw function starts with
// `ui::Screen s(title);` and places text, cards and button labels at fixed positions. The
// screen is built off-screen and shown, whole, when `s` goes out of scope: never half a
// screen, no flicker. A new title fades in; the same title (a counter, a cursor) is swapped
// at once. Drawn after that (a hint, a download's progress) goes onto the shown screen.
//
// LVGL runs in its own task (draws, animates, shows the presses on the labels above the
// three buttons); every call from the firmware takes the same lock (ui::Lock).
#pragma once
#include <M5Unified.h>

#include <cstring>

#include "Icons.h"
#include "lvgl.h"

#ifdef ARDUINO
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#endif

extern "C" {
LV_FONT_DECLARE(ui_font_body)
LV_FONT_DECLARE(ui_font_bold)
LV_FONT_DECLARE(ui_font_title)
LV_FONT_DECLARE(ui_font_big)
LV_FONT_DECLARE(ui_font_icon)
LV_FONT_DECLARE(ui_font_small)
LV_IMAGE_DECLARE(ui_logo);
}

namespace ui {
// ---- the app's colours (0xRRGGBB) and fonts -----------------------------------------------
using Color = uint32_t;
const Color kRed = 0xC8102E, kRedDeep = 0x9B0C24, kRedSoft = 0xFFE5E7, kRedFill = 0xFFCCD2;
const Color kBg = 0xF6F4F3, kCard = 0xFFFFFF, kLine = 0xE4DFDE, kChip = 0xF0EDEC;
const Color kInk = 0x1C1B1B, kMuted = 0x5E5857, kWhite = 0xFFFFFF;
const Color kOk = 0x1B7F3B, kOkSoft = 0xE3F4E8, kErr = 0xA8200D, kErrSoft = 0xFCE8E4;
const Color kNone = 0xFF000000;   // no fill / no border

using Font = const lv_font_t*;
// body, bold and title also hold the icons (Icons.h); kIcon is icons only, large; kSmall
// is for captions (no icons)
static const Font kBody = &ui_font_body, kBold = &ui_font_bold, kTitle = &ui_font_title, kBig = &ui_font_big,
                  kIcon = &ui_font_icon, kSmall = &ui_font_small;

// A hold action fires once its button is down this long (main.cpp uses the same).
const uint32_t kHoldMs = 1500;

const int kW = 320, kH = 240, kHeaderH = 48, kFooterY = 200;
const int kBtnX[3] = {56, 160, 264};   // centres above the three physical buttons

auto& D = M5.Display;
static bool animate = true;   // fades and slides; off in the PC preview (still pictures)

// ---- the lock: LVGL is not thread-safe ----------------------------------------------------
#ifdef ARDUINO
static SemaphoreHandle_t lvMutex = nullptr;
struct Lock {
  Lock() { xSemaphoreTakeRecursive(lvMutex, portMAX_DELAY); }
  ~Lock() { xSemaphoreGiveRecursive(lvMutex); }
};
#else
struct Lock {   // one thread on a PC; a constructor so `Lock l;` is not "unused"
  Lock() {}
  ~Lock() {}
};
#endif

// ---- what is being built, and what is shown ----------------------------------------------
static lv_obj_t* building = nullptr;   // the screen being built (not on the display yet)
static lv_obj_t* shown = nullptr;      // the screen on the display
static char buildTitle[48], shownTitle[48];
static lv_obj_t *buildPill[3], *shownPill[3];   // labels above the buttons, for the presses
static bool armed[3];                            // a press is shown only once the button was up
// Hold keys (holdKey): the outline and the fill that grows while the button is held.
struct Hold {
  lv_obj_t *key = nullptr, *fill = nullptr, *caption = nullptr;
  int w = 0, full = 0;
  uint32_t ms = kHoldMs;
};
static Hold buildHold[3], shownHold[3];
static lv_obj_t *buildBar = nullptr, *shownBar = nullptr;
static int buildBarV = -1, buildBarT = -1, shownBarV = -1, shownBarT = -1;

static lv_obj_t* parent() { return building ? building : shown ? shown : lv_screen_active(); }

static lv_color_t col(Color c) { return lv_color_hex(c & 0xFFFFFF); }

static lv_obj_t* plain(lv_obj_t* p) {
  lv_obj_t* o = lv_obj_create(p);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_CLICKABLE);
  return o;
}

// ---- text ---------------------------------------------------------------------------------
static int textW(const String& s, Font f) { return (int)lv_text_get_width(s.c_str(), (uint32_t)std::strlen(s.c_str()), f, 0); }
static int fontH(Font f) { return (int)lv_font_get_line_height(f); }

static lv_obj_t* text(const String& s, int x, int y, Font f, Color c) {
  Lock l;
  lv_obj_t* o = lv_label_create(parent());
  lv_label_set_text(o, s.c_str());
  lv_obj_set_style_text_font(o, f, 0);
  lv_obj_set_style_text_color(o, col(c), 0);
  lv_obj_set_pos(o, x, y);
  return o;
}

static void centred(const String& s, int cx, int y, Font f, Color c) { text(s, cx - textW(s, f) / 2, y, f, c); }

// Text from (x, y) inside width w, wrapped at spaces and at '\n'. Stops above maxY.
// Returns the y below the last line.
static int wrap(const String& s, int x, int y, int w, Font f, Color c, int maxY) {
  const int lineH = fontH(f);   // Inter's line height already has room between lines
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

// ---- shapes -------------------------------------------------------------------------------
// A rounded box: fill and/or border (kNone = without).
static lv_obj_t* box(int x, int y, int w, int h, int r, Color fill, Color border = kNone, int bw = 1) {
  Lock l;
  lv_obj_t* o = plain(parent());
  lv_obj_set_pos(o, x, y);
  lv_obj_set_size(o, w, h);
  lv_obj_set_style_radius(o, r, 0);
  if (fill != kNone) {
    lv_obj_set_style_bg_color(o, col(fill), 0);
    lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
  }
  if (border != kNone) {
    lv_obj_set_style_border_color(o, col(border), 0);
    lv_obj_set_style_border_width(o, bw, 0);
  }
  return o;
}

// A card: white ones float a little (soft shadow), tinted ones (ok / error) lie flat.
static void card(int x, int y, int w, int h, Color fill, bool border = true) {
  lv_obj_t* o = box(x, y, w, h, 12, fill, border ? kLine : kNone);
  if (border) {
    lv_obj_set_style_shadow_width(o, 12, 0);
    lv_obj_set_style_shadow_offset_y(o, 2, 0);
    lv_obj_set_style_shadow_color(o, lv_color_black(), 0);
    lv_obj_set_style_shadow_opa(o, LV_OPA_10, 0);
  }
}

// The app's mark (src/ui/ui_logo.c, 30 px).
static void logo(int x, int y) {
  Lock l;
  lv_obj_t* o = lv_image_create(parent());
  lv_image_set_src(o, &ui_logo);
  lv_obj_set_pos(o, x, y);
}

// ---- a screen -----------------------------------------------------------------------------
// Red header with the logo and the title on a light background; shown when it goes out of scope.
struct Screen {
  Lock lock;
  explicit Screen(const String& title) {
    std::strncpy(buildTitle, title.c_str(), sizeof buildTitle - 1);
    building = lv_obj_create(nullptr);
    lv_obj_remove_flag(building, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_bg_color(building, col(kBg), 0);
    lv_obj_set_style_bg_opa(building, LV_OPA_COVER, 0);
    for (auto& p : buildPill) p = nullptr;
    for (auto& h : buildHold) h = Hold();
    buildBar = nullptr;
    lv_obj_t* h = box(0, 0, kW, kHeaderH, 0, kRed);   // brand gradient, like the app's header
    lv_obj_set_style_bg_grad_color(h, col(kRedDeep), 0);
    lv_obj_set_style_bg_grad_dir(h, LV_GRAD_DIR_VER, 0);
    logo(10, 9);
    text(title, 50, (kHeaderH - fontH(kTitle)) / 2, kTitle, kWhite);
  }
  ~Screen() {
    bool same = std::strcmp(buildTitle, shownTitle) == 0;
    shown = building;
    building = nullptr;
    std::strcpy(shownTitle, buildTitle);
    for (int i = 0; i < 3; i++) {
      shownPill[i] = buildPill[i];
      shownHold[i] = buildHold[i];
      armed[i] = false;
    }
    shownBar = buildBar;
    shownBarV = buildBarV;
    shownBarT = buildBarT;
    bool fade = animate && !same;
    lv_screen_load_anim(shown, fade ? LV_SCR_LOAD_ANIM_FADE_IN : LV_SCR_LOAD_ANIM_NONE, fade ? 140 : 0, 0, true);
  }
  Screen(const Screen&) = delete;
  Screen& operator=(const Screen&) = delete;
};

// ---- the labels above the buttons ----------------------------------------------------------
enum class Pill { Primary, Neutral };

// Labels above the three buttons. Default: small keys at the bottom; the home screen uses
// taller route keys (y, h, font). A label may start with an icon (Icons.h).
static void pills(const String (&label)[3], const Pill (&style)[3], int y = kFooterY + 6, int h = 30,
                  Font font = kBold) {
  Lock l;
  static const lv_style_prop_t props[] = {LV_STYLE_BG_COLOR, LV_STYLE_TRANSLATE_Y, LV_STYLE_PROP_INV};
  static lv_style_transition_dsc_t trans;
  static bool init = false;
  if (!init) {
    lv_style_transition_dsc_init(&trans, props, lv_anim_path_ease_out, 90, 0, nullptr);
    init = true;
  }
  for (int i = 0; i < 3; i++) {
    if (label[i].isEmpty()) continue;
    bool primary = style[i] == Pill::Primary;
    lv_obj_t* o = box(kBtnX[i] - 47, y, 94, h, h >= 40 ? 14 : h / 2, primary ? kRed : kCard, primary ? kNone : kLine);
    if (primary) {   // a little depth, like the app's buttons
      lv_obj_set_style_bg_grad_color(o, col(0xB00D28), 0);
      lv_obj_set_style_bg_grad_dir(o, LV_GRAD_DIR_VER, 0);
      lv_obj_set_style_bg_grad_color(o, col(kRedDeep), LV_STATE_PRESSED);
    }
    // held down: a shade darker and 2 px lower, as if pressed into the screen
    lv_obj_set_style_bg_color(o, col(primary ? kRedDeep : kChip), LV_STATE_PRESSED);
    lv_obj_set_style_translate_y(o, 2, LV_STATE_PRESSED);
    lv_obj_set_style_transition(o, &trans, 0);
    lv_obj_set_style_transition(o, &trans, LV_STATE_PRESSED);
    // too wide for the pill: drop the icon, keep the word
    String l = label[i];
    if (textW(l, font) > 84 && l.length() > 4) {
      const uint8_t* b = (const uint8_t*)l.c_str();
      if (b[0] == 0xEF) l = l.substring(3);
      else if (b[l.length() - 3] == 0xEF) l = l.substring(0, l.length() - 3);
      l.trim();
    }
    lv_obj_t* t = lv_label_create(o);
    lv_label_set_text(t, l.c_str());
    lv_obj_set_style_text_font(t, font, 0);
    lv_obj_set_style_text_color(t, col(primary ? kWhite : kInk), 0);
    lv_obj_center(t);
    if (building) buildPill[i] = o;
    else shownPill[i] = o;
  }
}

static void hint(const String& s) { centred(s, kW / 2, kFooterY + 12, kBody, kMuted); }

// Under a result: the hint, and a thin bar that empties over `ms`, when the screen closes
// by itself.
static void closeHint(const String& s, uint32_t ms) {
  Lock l;
  centred(s, kW / 2, kFooterY + 6, kBody, kMuted);
  lv_obj_t* b = lv_bar_create(parent());
  lv_obj_remove_flag(b, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_pos(b, kW / 2 - 60, kFooterY + 30);
  lv_obj_set_size(b, 120, 4);
  lv_obj_set_style_radius(b, 2, 0);
  lv_obj_set_style_bg_color(b, col(kLine), 0);
  lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(b, 2, LV_PART_INDICATOR);
  lv_obj_set_style_bg_color(b, col(kMuted), LV_PART_INDICATOR);
  lv_obj_set_style_bg_opa(b, LV_OPA_COVER, LV_PART_INDICATOR);
  lv_bar_set_range(b, 0, 1000);
  lv_bar_set_value(b, 1000, LV_ANIM_OFF);
  if (animate) {
    lv_obj_set_style_anim_duration(b, ms, 0);
    lv_bar_set_value(b, 0, LV_ANIM_ON);
  }
}

// A hold key above button i: what the button does when held. Outlined, with a small
// caption (Texts::holdWord); while the button is held it fills up from the left and is full
// after `ms`, when the action fires. Let go early: it empties and the tap action counts.
// Tall (h >= 50): caption, icon, word stacked. Low: caption and word on one line.
static void holdKey(int i, int y, int h, const char* caption, const char* icon, const String& word, uint32_t ms = kHoldMs) {
  Lock l;
  String w = *icon && h < 50 ? String(icon) + " " + word : word;
  int width = 94;
  if (h < 50) {
    int need = textW(caption, kSmall) + 8 + textW(w, kBold) + 24;
    if (need > width) width = need;
  }
  int x = kBtnX[i] - width / 2, r = h >= 50 ? 12 : h / 2;
  Hold k;
  k.ms = ms;
  k.key = box(x, y, width, h, r, kCard, kLine);
  k.fill = box(x + 1, y + 1, 0, h - 2, r - 1, kRedFill);   // inside the outline
  k.full = width - 2;
  lv_obj_add_flag(k.fill, LV_OBJ_FLAG_HIDDEN);
  if (h >= 50) {
    k.caption = text(caption, 0, y + 6, kSmall, kMuted);
    lv_obj_set_x(k.caption, kBtnX[i] - textW(caption, kSmall) / 2);
    if (*icon) centred(icon, kBtnX[i], y + 21, kBold, kRed);
    centred(w, kBtnX[i], y + (*icon ? 39 : 26), kBold, kInk);
  } else {
    int cw = textW(caption, kSmall), tw = textW(w, kBold), x0 = kBtnX[i] - (cw + 8 + tw) / 2;
    k.caption = text(caption, x0, y + (h - fontH(kSmall)) / 2 + 1, kSmall, kMuted);
    text(w, x0 + cw + 8, y + (h - fontH(kBold)) / 2, kBold, kInk);
  }
  if (building) buildHold[i] = k;
  else shownHold[i] = k;
}

// A large round icon (results, questions): white icon on a coloured disc of size d.
static void disc(int x, int y, int d, const char* icon, Color fill, Color ink = kWhite) {
  Lock l;
  lv_obj_t* o = box(x, y, d, d, LV_RADIUS_CIRCLE, fill);
  lv_obj_t* t = lv_label_create(o);
  lv_label_set_text(t, icon);
  lv_obj_set_style_text_font(t, d >= 40 ? kIcon : kBold, 0);
  lv_obj_set_style_text_color(t, col(ink), 0);
  lv_obj_center(t);
}

// An icon and a text after it, the icon in its own colour.
static int iconText(const char* icon, const String& s, int x, int y, Font f, Color c, Color ic, int gap = 8) {
  text(icon, x, y, f, ic);
  int w = textW(icon, f);
  int iw = w < 18 ? 18 : w;   // icons differ in width: keep the texts in line
  text(s, x + iw + gap, y, f, c);
  return x + iw + gap + textW(s, f);
}

// A small round dot (schedule on / off).
static void dot(int cx, int cy, int r, Color c) { box(cx - r, cy - r, 2 * r, 2 * r, LV_RADIUS_CIRCLE, c); }

// A busy indicator that turns (LVGL spinner) in brand red on a light ring.
static void spinner(int x, int y, int d) {
  Lock l;
  lv_obj_t* o = lv_spinner_create(parent());
  lv_spinner_set_anim_params(o, 1000, 240);
  lv_obj_set_pos(o, x, y);
  lv_obj_set_size(o, d, d);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_width(o, 5, LV_PART_MAIN);
  lv_obj_set_style_arc_color(o, col(kChip), LV_PART_MAIN);
  lv_obj_set_style_arc_width(o, 5, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(o, col(kRed), LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(o, true, LV_PART_INDICATOR);
}

// A progress bar. On a screen being built with the same title and steps as the shown one, it
// slides on from where that one was; drawn on the shown screen, it moves the bar already there.
static void progressBar(int x, int y, int w, int done, int total) {
  Lock l;
  int t = total > 0 ? total : 1;
  int v = done < 0 ? 0 : done < t ? done : t;
  if (!building && shownBar) {
    lv_bar_set_range(shownBar, 0, t);
    lv_bar_set_value(shownBar, v, animate ? LV_ANIM_ON : LV_ANIM_OFF);
    shownBarV = v;
    shownBarT = t;
    return;
  }
  lv_obj_t* b = lv_bar_create(parent());
  lv_obj_remove_flag(b, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_pos(b, x, y);
  lv_obj_set_size(b, w, 10);
  lv_obj_set_style_radius(b, 5, 0);
  lv_obj_set_style_bg_color(b, col(kChip), 0);
  lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(b, 5, LV_PART_INDICATOR);
  lv_obj_set_style_bg_color(b, col(kRed), LV_PART_INDICATOR);
  lv_obj_set_style_bg_opa(b, LV_OPA_COVER, LV_PART_INDICATOR);
  lv_obj_set_style_anim_duration(b, 350, 0);
  lv_bar_set_range(b, 0, t);
  bool goesOn = building && shownBar && shownBarT == t && shownBarV <= v && std::strcmp(buildTitle, shownTitle) == 0;
  if (goesOn && animate) {
    lv_bar_set_value(b, shownBarV, LV_ANIM_OFF);
    lv_bar_set_value(b, v, LV_ANIM_ON);
  } else {
    lv_bar_set_value(b, v, LV_ANIM_OFF);
  }
  if (building) {
    buildBar = b;
    buildBarV = v;
    buildBarT = t;
  } else {
    shownBar = b;
    shownBarV = v;
    shownBarT = t;
  }
}

// ---- LVGL itself ---------------------------------------------------------------------------
alignas(4) static uint8_t drawBuf[kW * 24 * 2];   // 24 lines of RGB565 at a time (15 KB)

static void flush(lv_display_t* d, const lv_area_t* a, uint8_t* px) {
  D.pushImage(a->x1, a->y1, a->x2 - a->x1 + 1, a->y2 - a->y1 + 1, (const m5gfx::rgb565_t*)px);
  lv_display_flush_ready(d);
}

// A hold key's fill for how long its button has been held (0 = empty).
static void fillHold(int i, uint32_t heldMs) {
  Hold& k = shownHold[i];
  if (!k.key) return;
  int w = heldMs >= k.ms ? k.full : (int)(heldMs * k.full / k.ms);
  if (w == k.w) return;
  bool was = k.w > 0;
  k.w = w;
  lv_obj_set_width(k.fill, w);
  if (w > 0 && !was) {
    lv_obj_remove_flag(k.fill, LV_OBJ_FLAG_HIDDEN);
    lv_obj_set_style_border_color(k.key, col(kRed), 0);
    lv_obj_set_style_text_color(k.caption, col(kRed), 0);
  } else if (w == 0) {
    lv_obj_add_flag(k.fill, LV_OBJ_FLAG_HIDDEN);
    lv_obj_set_style_border_color(k.key, col(kLine), 0);
    lv_obj_set_style_text_color(k.caption, col(kMuted), 0);
  }
}

// The labels above the buttons follow the buttons: pressed while held; hold keys fill up.
[[maybe_unused]] static void showPresses() {
  static uint32_t since[3];
  m5::Button_Class* b[3] = {&M5.BtnA, &M5.BtnB, &M5.BtnC};
  for (int i = 0; i < 3; i++) {
    bool down = b[i]->isPressed();
    if (!down) armed[i] = true;   // the press that opened this screen does not count
    if (down && armed[i] && !since[i]) since[i] = millis() | 1;
    if (!down || !armed[i]) since[i] = 0;
    fillHold(i, since[i] ? millis() - since[i] : 0);
    lv_obj_t* p = shownPill[i];
    if (!p) continue;
    bool show = down && armed[i];
    if (show != lv_obj_has_state(p, LV_STATE_PRESSED)) {
      if (show) lv_obj_add_state(p, LV_STATE_PRESSED);
      else lv_obj_remove_state(p, LV_STATE_PRESSED);
    }
  }
}

#ifdef ARDUINO
static void task(void*) {
  for (;;) {
    {
      Lock l;
      showPresses();
      lv_timer_handler();
    }
    vTaskDelay(pdMS_TO_TICKS(5));
  }
}
#endif

// After M5.begin(), before the first screen.
static void begin() {
#ifdef ARDUINO
  lvMutex = xSemaphoreCreateRecursiveMutex();
#endif
  lv_init();
  lv_tick_set_cb([]() -> uint32_t { return millis(); });
  lv_display_t* d = lv_display_create(kW, kH);
  lv_display_set_flush_cb(d, flush);
  lv_display_set_buffers(d, drawBuf, nullptr, sizeof drawBuf, LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_obj_set_style_bg_color(lv_screen_active(), col(kBg), 0);
  lv_obj_set_style_bg_opa(lv_screen_active(), LV_OPA_COVER, 0);
#ifdef ARDUINO
  // core 1 with the firmware; a little above it so presses and fades stay smooth
  xTaskCreatePinnedToCore(task, "lvgl", 8192, nullptr, 2, nullptr, 1);
#endif
}

// Draws what is due now (the PC preview has no task).
[[maybe_unused]] static void refresh() {
  Lock l;
  lv_refr_now(nullptr);
}
}  // namespace ui
