// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
#include <cstdint>
#include "M5Unified.h"
enum { WIFI_OFF, WIFI_STA, WL_CONNECTED = 3 };
enum wifi_auth_mode_t { WIFI_AUTH_OPEN, WIFI_AUTH_WPA2_PSK };
enum arduino_event_id_t { ARDUINO_EVENT_WPS_ER_SUCCESS, ARDUINO_EVENT_WPS_ER_FAILED, ARDUINO_EVENT_WPS_ER_TIMEOUT, ARDUINO_EVENT_WPS_ER_PIN, ARDUINO_EVENT_MAX };
typedef void (*WiFiEventCb)(arduino_event_id_t);
struct IPAddressT { String toString() const; };
struct WiFiT {
  void mode(int); void begin(const char*, const char*); void begin(); int status(); IPAddressT localIP(); void disconnect(bool);
  int16_t scanNetworks(); String SSID(uint8_t); int32_t RSSI(uint8_t); wifi_auth_mode_t encryptionType(uint8_t); void scanDelete();
  String SSID(); String psk(); int onEvent(WiFiEventCb, arduino_event_id_t = ARDUINO_EVENT_MAX); void removeEvent(int);
};
extern WiFiT WiFi;
struct EspT { void restart(); uint32_t getFreeHeap() { return 0; } uint32_t getMaxAllocHeap() { return 0; } };
extern EspT ESP;
