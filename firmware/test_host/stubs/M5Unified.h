// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
#include <string>
#include <cstdint>
#include <cstdio>
struct String { std::string s; String(){} String(const char*c):s(c?c:""){} String(const std::string&x):s(x){} String(double v,int d){char b[32];snprintf(b,sizeof b,"%.*f",d,v);s=b;} explicit String(int v):s(std::to_string(v)){} String(char c):s(1,c){}
  const char* c_str() const {return s.c_str();} bool isEmpty() const {return s.empty();}
  String operator+(const String&o) const {return String(s+o.s);} String operator+(const char*o) const {return String(s+o);} String operator+(char c) const {return String(s+c);}
  String& operator+=(const String&o){s+=o.s;return *this;}
  unsigned length() const {return s.size();} int indexOf(char c,unsigned from=0) const {auto i=s.find(c,from);return i==std::string::npos?-1:(int)i;}
  int lastIndexOf(char c,unsigned from) const {auto i=s.rfind(c,from);return i==std::string::npos?-1:(int)i;}
  String substring(unsigned a,unsigned b) const {return String(s.substr(a,b-a));} String substring(unsigned a) const {return String(s.substr(a));}
  void trim(){auto a=s.find_first_not_of(' ');auto b=s.find_last_not_of(' ');s=a==std::string::npos?"":s.substr(a,b-a+1);} bool operator!=(const std::string&o) const{return s!=o;} };
inline String operator+(const char*a,const String&b){return String(std::string(a)+b.s);}
uint32_t millis(); void delay(uint32_t);
struct SerialT{ void begin(int){} void println(const char*){} void printf(const char*,...){} }; extern SerialT Serial;
enum { TFT_BLACK, TFT_WHITE, TFT_YELLOW, TFT_GREEN, TFT_RED };
namespace m5 { struct Button_Class { bool wasPressed(); bool wasReleased(); bool isPressed(); };
  struct Power_Class { enum is_charging_t { is_discharging, is_charging, charge_unknown }; int getBatteryLevel(); is_charging_t isCharging(); }; }
struct GFXfont { int id; };
namespace fonts { extern const GFXfont FreeSans9pt7b, FreeSansBold9pt7b, FreeSansBold12pt7b, FreeSansBold18pt7b; }
struct Disp { void fillScreen(uint16_t); void setTextColor(uint16_t); void setTextColor(uint16_t,uint16_t); void setTextSize(int);
  void setCursor(int,int); void println(const String&); void print(const char*); void print(const String&); int width(); int height();
  void printf(const char*,...); void setFont(const GFXfont*); void drawString(const char*,int,int); int textWidth(const char*); int fontHeight();
  void fillRect(int,int,int,int,uint16_t); void fillRoundRect(int,int,int,int,int,uint16_t); void drawRoundRect(int,int,int,int,int,uint16_t);
  void fillCircle(int,int,int,uint16_t); void drawCircle(int,int,int,uint16_t); void fillTriangle(int,int,int,int,int,int,uint16_t);
  void drawLine(int,int,int,int,uint16_t); void drawFastHLine(int,int,int,uint16_t); void setBrightness(uint8_t); };
struct Spk { void tone(int,int); };
struct M5T { Disp Display; Spk Speaker; m5::Power_Class Power; m5::Button_Class BtnA,BtnB,BtnC; void update(); int config(); void begin(int); };
extern M5T M5;
