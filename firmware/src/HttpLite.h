// Pieces of a plain HTTPS GET, no hardware code (tested on a PC). The M5 does its own small
// GET instead of Arduino's HTTPClient: that one parses cookie dates, and the time functions it
// pulls in no longer fit the ESP32's IRAM (seen 2026-11-11, 1.3 KB over).
#pragma once
#include <cctype>
#include <cstdlib>
#include <string>

// "https://host/path" -> host, path ("/" if none). Only https.
inline bool parseUrl(const std::string& url, std::string& host, std::string& path) {
  const std::string pre = "https://";
  if (url.compare(0, pre.size(), pre) != 0) return false;
  size_t slash = url.find('/', pre.size());
  host = url.substr(pre.size(), slash == std::string::npos ? std::string::npos : slash - pre.size());
  path = slash == std::string::npos ? "/" : url.substr(slash);
  return !host.empty();
}

// "HTTP/1.1 302 Found" -> 302, anything else -> 0.
inline int statusCode(const std::string& line) {
  if (line.compare(0, 5, "HTTP/") != 0) return 0;
  size_t sp = line.find(' ');
  return sp == std::string::npos ? 0 : std::atoi(line.c_str() + sp + 1);
}

// The value of header `name` (any case) in `line`, or false.
inline bool headerValue(const std::string& line, const std::string& name, std::string& value) {
  if (line.size() <= name.size() || line[name.size()] != ':') return false;
  for (size_t i = 0; i < name.size(); i++)
    if (std::tolower((unsigned char)line[i]) != std::tolower((unsigned char)name[i])) return false;
  size_t from = name.size() + 1;
  while (from < line.size() && line[from] == ' ') from++;
  value = line.substr(from);
  return true;
}

inline bool isRedirect(int code) { return code == 301 || code == 302 || code == 303 || code == 307 || code == 308; }
