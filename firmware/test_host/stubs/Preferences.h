// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
#include "M5Unified.h"
struct Preferences { void begin(const char*,bool); size_t getBytesLength(const char*); void getBytes(const char*,void*,size_t);
 void putBytes(const char*,const void*,size_t); String getString(const char*,const char*); void putString(const char*,const String&); };
