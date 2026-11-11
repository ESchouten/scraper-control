// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
typedef int esp_err_t;
enum wps_type_t { WPS_TYPE_DISABLE, WPS_TYPE_PBC, WPS_TYPE_PIN };
struct wps_factory_information_t { char manufacturer[65]; char model_number[33]; char model_name[33]; char device_name[33]; };
struct esp_wps_config_t { wps_type_t wps_type; wps_factory_information_t factory_info; };
esp_err_t esp_wifi_wps_enable(const esp_wps_config_t*); esp_err_t esp_wifi_wps_disable(); esp_err_t esp_wifi_wps_start(int);
#define ESP_OK 0
