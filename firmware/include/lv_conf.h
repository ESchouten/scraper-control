// LVGL settings for the buttons' screens (LVGL 9.2, see platformio.ini). Only what differs from
// LVGL's defaults (lv_conf_internal.h). The same file is used for the PC preview and tests.
#if 1   // LVGL reads this file only when this is 1
#ifndef LV_CONF_H
#define LV_CONF_H

#define LV_COLOR_DEPTH 16              // the M5Stack's ILI9342C: RGB565

// memory and text functions from the C library (the ESP32's heap); no LVGL pool to size
#define LV_USE_STDLIB_MALLOC LV_STDLIB_CLIB
#define LV_USE_STDLIB_STRING LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF LV_STDLIB_CLIB

#define LV_USE_OS LV_OS_NONE           // the firmware guards LVGL with its own mutex (ui::Lock)
#define LV_DEF_REFR_PERIOD 16          // ms: smooth enough for the short fades and presses
#define LV_DPI_DEF 130
#define LV_DRAW_LAYER_SIMPLE_BUF_SIZE (12 * 1024)   // fades draw through a layer; no PSRAM here

#define LV_USE_LOG 0
#define LV_USE_ASSERT_NULL 1
#define LV_USE_ASSERT_MALLOC 1

// fonts: Inter (src/ui, made by make_ui_assets.py); none of LVGL's own
#define LV_FONT_MONTSERRAT_14 0
#define LV_FONT_CUSTOM_DECLARE LV_FONT_DECLARE(ui_font_body)
#define LV_FONT_DEFAULT &ui_font_body

// no theme: every object is styled by the firmware (ui:: in Ui.h)
#define LV_USE_THEME_DEFAULT 0
#define LV_USE_THEME_SIMPLE 0
#define LV_USE_THEME_MONO 0

// widgets: only labels, bars and images are used
#define LV_USE_ANIMIMG 0
#define LV_USE_ARC 0
#define LV_USE_BUTTON 0
#define LV_USE_BUTTONMATRIX 0
#define LV_USE_CALENDAR 0
#define LV_USE_CANVAS 0
#define LV_USE_CHART 0
#define LV_USE_CHECKBOX 0
#define LV_USE_DROPDOWN 0
#define LV_USE_IMAGEBUTTON 0
#define LV_USE_KEYBOARD 0
#define LV_USE_LED 0
#define LV_USE_LINE 0
#define LV_USE_LIST 0
#define LV_USE_MENU 0
#define LV_USE_MSGBOX 0
#define LV_USE_ROLLER 0
#define LV_USE_SCALE 0
#define LV_USE_SLIDER 0
#define LV_USE_SPAN 0
#define LV_USE_SPINBOX 0
#define LV_USE_SPINNER 0
#define LV_USE_SWITCH 0
#define LV_USE_TEXTAREA 0
#define LV_USE_TABLE 0
#define LV_USE_TABVIEW 0
#define LV_USE_TILEVIEW 0
#define LV_USE_WIN 0
#define LV_USE_GRID 0
#define LV_USE_OBSERVER 0

#define LV_BUILD_EXAMPLES 0

#endif  // LV_CONF_H
#endif
