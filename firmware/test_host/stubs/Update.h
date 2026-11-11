// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
#include <cstddef>
#include <cstdint>
struct UpdateT { bool begin(size_t); size_t write(uint8_t*, size_t); bool end(bool = false); void abort(); };
extern UpdateT Update;
