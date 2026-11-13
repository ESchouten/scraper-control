// JNI for nl.eschouten.scraper.link.DesktopBt (desktop only), over bt_serial.h.
#include <jni.h>

#include <cstdio>
#include <vector>

#include "bt_serial.h"

namespace btserial {

std::string jsonString(const std::string& s) {
  std::string o = "\"";
  for (unsigned char c : s) {
    if (c == '"' || c == '\\') { o += '\\'; o += (char)c; }
    else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
    else o += (char)c;
  }
  return o + "\"";
}

bool parseAddress(const std::string& text, uint64_t& out) {
  unsigned b[6];
  if (sscanf(text.c_str(), "%2x:%2x:%2x:%2x:%2x:%2x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) return false;
  out = 0;
  for (unsigned v : b) out = out << 8 | v;
  return true;
}

std::string addressText(uint64_t a) {
  char b[18];
  snprintf(b, sizeof b, "%02x:%02x:%02x:%02x:%02x:%02x", (unsigned)(a >> 40 & 0xff), (unsigned)(a >> 32 & 0xff),
           (unsigned)(a >> 24 & 0xff), (unsigned)(a >> 16 & 0xff), (unsigned)(a >> 8 & 0xff), (unsigned)(a & 0xff));
  return b;
}

bool parseUuid(const std::string& text, uint8_t out[16]) {
  int n = 0;
  for (size_t i = 0; i < text.size() && n < 32; i++) {
    char c = text[i];
    if (c == '-') continue;
    int v = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    if (v < 0) return false;
    if (n % 2 == 0) out[n / 2] = (uint8_t)(v << 4); else out[n / 2] |= (uint8_t)v;
    n++;
  }
  return n == 32;
}

}  // namespace btserial

namespace {
std::string str(JNIEnv* e, jstring s) {
  const char* c = e->GetStringUTFChars(s, nullptr);
  std::string out(c ? c : "");
  if (c) e->ReleaseStringUTFChars(s, c);
  return out;
}
}  // namespace

#define BT_FN(name) JNIEXPORT JNICALL Java_nl_eschouten_scraper_link_DesktopBt_##name

extern "C" {

jboolean BT_FN(nativeBtSupported)(JNIEnv*, jobject) { return btserial::supported(); }

jstring BT_FN(nativeBtFind)(JNIEnv* e, jobject, jstring prefix, jint ms) {
  return e->NewStringUTF(btserial::find(str(e, prefix), ms).c_str());
}

jlong BT_FN(nativeBtOpen)(JNIEnv* e, jobject, jstring address, jstring service) {
  std::string err;
  int64_t h = btserial::open(str(e, address), str(e, service), err);
  if (h < 0) e->ThrowNew(e->FindClass("java/io/IOException"), err.c_str());
  return (jlong)h;
}

jint BT_FN(nativeBtRead)(JNIEnv* e, jobject, jlong h, jbyteArray buf) {
  std::vector<uint8_t> b((size_t)e->GetArrayLength(buf));
  int n = btserial::read(h, b.data(), (int)b.size());
  if (n > 0) e->SetByteArrayRegion(buf, 0, n, (const jbyte*)b.data());
  return n;
}

jboolean BT_FN(nativeBtWrite)(JNIEnv* e, jobject, jlong h, jbyteArray data) {
  std::vector<uint8_t> b((size_t)e->GetArrayLength(data));
  e->GetByteArrayRegion(data, 0, (jsize)b.size(), (jbyte*)b.data());
  return btserial::write(h, b.data(), (int)b.size());
}

void BT_FN(nativeBtClose)(JNIEnv*, jobject, jlong h) { btserial::close(h); }

}  // extern "C"
