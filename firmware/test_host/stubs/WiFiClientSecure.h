// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
#include "WiFi.h"
struct WiFiClient { int available(); int read(); size_t readBytes(uint8_t*, size_t); bool connected(); void stop(); bool connect(const char*, uint16_t); size_t print(const String&); };
struct WiFiClientSecure : WiFiClient { void setInsecure(); void setHandshakeTimeout(unsigned long); using WiFiClient::connect; int connect(const char*, uint16_t, int32_t); };
