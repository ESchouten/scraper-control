// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
#include <functional>
struct ArduinoOTAT {
  ArduinoOTAT& setHostname(const char*); ArduinoOTAT& setPassword(const char*);
  ArduinoOTAT& onStart(std::function<void()>); ArduinoOTAT& onEnd(std::function<void()>);
  ArduinoOTAT& onProgress(std::function<void(unsigned, unsigned)>); ArduinoOTAT& onError(std::function<void(int)>);
  void begin(); void handle(); void end();
};
extern ArduinoOTAT ArduinoOTA;
