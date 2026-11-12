// Empty on purpose: LVGL is set up by lv_conf.h, not by ESP-IDF Kconfig. Without this LVGL
// includes esp_attr.h for every file, also for its assembly files, which do not get the
// ESP-IDF include paths (platformio.ini: LV_CONF_KCONFIG_EXTERNAL_INCLUDE).
#pragma once
