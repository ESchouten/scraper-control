// JNI bridge from Kotlin (Android and desktop JVM) to the C API in core/scraper_c.h.
// Kotlin side: native/shared/src/jniMain/kotlin/.../CoreJni.kt. No logic here, only
// copying bytes and strings across and calling the listener back from the core's thread.
#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include "scraper_c.h"

namespace {

JavaVM* gVm = nullptr;
jmethodID gOnWrite = nullptr, gOnDone = nullptr, gOnProgress = nullptr;

// The core's own thread calls back; attach it once and detach when that thread ends.
struct Attached {
  JNIEnv* env = nullptr;
  bool attachedHere = false;
  ~Attached() {
    if (attachedHere && gVm) gVm->DetachCurrentThread();
  }
};

JNIEnv* env() {
  thread_local Attached a;
  if (a.env) return a.env;
  if (gVm->GetEnv(reinterpret_cast<void**>(&a.env), JNI_VERSION_1_6) == JNI_OK) return a.env;
#ifdef __ANDROID__
  gVm->AttachCurrentThread(&a.env, nullptr);
#else
  gVm->AttachCurrentThread(reinterpret_cast<void**>(&a.env), nullptr);
#endif
  a.attachedHere = true;
  return a.env;
}

struct Session {
  scraper_session* s = nullptr;
  jobject listener = nullptr;  // global ref
};

void onWrite(void* user, const uint8_t* data, int32_t len) {
  auto* js = static_cast<Session*>(user);
  JNIEnv* e = env();
  jbyteArray arr = e->NewByteArray(len);
  e->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
  scraper_release(data);
  e->CallVoidMethod(js->listener, gOnWrite, arr);
  e->DeleteLocalRef(arr);
  if (e->ExceptionCheck()) e->ExceptionClear();
}

void onDone(void* user, int32_t command, int32_t result) {
  auto* js = static_cast<Session*>(user);
  JNIEnv* e = env();
  e->CallVoidMethod(js->listener, gOnDone, command, result);
  if (e->ExceptionCheck()) e->ExceptionClear();
}

void onProgress(void* user, int32_t step) {
  auto* js = static_cast<Session*>(user);
  JNIEnv* e = env();
  e->CallVoidMethod(js->listener, gOnProgress, step);
  if (e->ExceptionCheck()) e->ExceptionClear();
}

std::string str(JNIEnv* e, jstring s) {
  if (!s) return {};
  const char* c = e->GetStringUTFChars(s, nullptr);
  std::string r(c ? c : "");
  if (c) e->ReleaseStringUTFChars(s, c);
  return r;
}

Session* S(jlong h) { return reinterpret_cast<Session*>(h); }

jstring text(JNIEnv* e, scraper_session* s, int32_t (*fn)(scraper_session*, char*, int32_t)) {
  int32_t n = fn(s, nullptr, 0);
  std::vector<char> buf(static_cast<size_t>(n) + 1);
  fn(s, buf.data(), n + 1);
  return e->NewStringUTF(buf.data());
}

}  // namespace

#define JNI_FN(name) JNIEXPORT JNICALL Java_nl_eschouten_scraper_core_CoreJni_##name

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  gVm = vm;
  return JNI_VERSION_1_6;
}

jlong JNI_FN(nativeNew)(JNIEnv* e, jobject, jstring pin, jbyteArray version, jobject listener) {
  if (!gOnWrite) {
    jclass c = e->GetObjectClass(listener);
    gOnWrite = e->GetMethodID(c, "onWrite", "([B)V");
    gOnDone = e->GetMethodID(c, "onDone", "(II)V");
    gOnProgress = e->GetMethodID(c, "onProgress", "(I)V");
    e->DeleteLocalRef(c);
  }
  auto* js = new Session();
  js->listener = e->NewGlobalRef(listener);
  std::string p = str(e, pin);
  uint8_t v[9];
  bool hasVersion = version && e->GetArrayLength(version) == 9;
  if (hasVersion) e->GetByteArrayRegion(version, 0, 9, reinterpret_cast<jbyte*>(v));
  js->s = scraper_new(p.c_str(), hasVersion ? v : nullptr, onWrite, onDone, js);
  scraper_on_progress(js->s, onProgress);
  return reinterpret_cast<jlong>(js);
}

void JNI_FN(nativeFree)(JNIEnv* e, jobject, jlong h) {
  Session* js = S(h);
  scraper_free(js->s);  // waits for the core's thread: no callbacks after this
  e->DeleteGlobalRef(js->listener);
  delete js;
}

void JNI_FN(nativeFeed)(JNIEnv* e, jobject, jlong h, jbyteArray data) {
  jsize n = e->GetArrayLength(data);
  std::vector<uint8_t> b(static_cast<size_t>(n));
  e->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(b.data()));
  scraper_feed(S(h)->s, b.data(), n);
}

void JNI_FN(nativeLinkDown)(JNIEnv*, jobject, jlong h) { scraper_link_down(S(h)->s); }
jint JNI_FN(nativeConnect)(JNIEnv*, jobject, jlong h) { return scraper_connect(S(h)->s); }
jint JNI_FN(nativeStartRoute)(JNIEnv*, jobject, jlong h, jchar letter, jboolean dryRun) {
  return scraper_start_route(S(h)->s, static_cast<char>(letter), dryRun ? 1 : 0);
}
jint JNI_FN(nativeSetPlanning)(JNIEnv*, jobject, jlong h, jboolean on) {
  return scraper_set_planning(S(h)->s, on ? 1 : 0);
}
jint JNI_FN(nativeStopRoute)(JNIEnv*, jobject, jlong h) { return scraper_stop_route(S(h)->s); }
jint JNI_FN(nativeAnswerStop)(JNIEnv*, jobject, jlong h, jboolean finish) { return scraper_answer_stop(S(h)->s, finish ? 1 : 0); }
jint JNI_FN(nativeReadRoutes)(JNIEnv*, jobject, jlong h) { return scraper_read_routes(S(h)->s); }
jint JNI_FN(nativePress)(JNIEnv*, jobject, jlong h, jint key) { return scraper_press(S(h)->s, key); }
jint JNI_FN(nativeFinish)(JNIEnv*, jobject, jlong h) { return scraper_finish(S(h)->s); }
void JNI_FN(nativeSetOwedPlanning)(JNIEnv*, jobject, jlong h, jint owed) { scraper_set_owed_planning(S(h)->s, owed); }
jint JNI_FN(nativeOwedPlanning)(JNIEnv*, jobject, jlong h) { return scraper_owed_planning(S(h)->s); }

jstring JNI_FN(nativeStatusJson)(JNIEnv* e, jobject, jlong h) { return text(e, S(h)->s, scraper_status_json); }
jstring JNI_FN(nativeLastError)(JNIEnv* e, jobject, jlong h) { return text(e, S(h)->s, scraper_last_error); }
jstring JNI_FN(nativeRoutesJson)(JNIEnv* e, jobject, jlong h) { return text(e, S(h)->s, scraper_routes_json); }

// 10 bytes: the 9-byte image version, then 1 if the robot sent a new one.
jbyteArray JNI_FN(nativeImageVersion)(JNIEnv* e, jobject, jlong h) {
  uint8_t out[10];
  out[9] = static_cast<uint8_t>(scraper_image_version(S(h)->s, out) == 1 ? 1 : 0);
  jbyteArray arr = e->NewByteArray(10);
  e->SetByteArrayRegion(arr, 0, 10, reinterpret_cast<const jbyte*>(out));
  return arr;
}

void JNI_FN(nativeSetLanguage)(JNIEnv* e, jobject, jlong h, jstring lang) {
  std::string l = str(e, lang);
  scraper_set_language(S(h)->s, l.c_str());
}

jbyteArray JNI_FN(nativeEncryptPin)(JNIEnv* e, jobject, jstring pin) {
  std::string p = str(e, pin);
  uint8_t buf[64];
  int32_t n = scraper_encrypt_pin(p.c_str(), buf, sizeof buf);
  if (n > (int32_t)sizeof buf) n = sizeof buf;
  jbyteArray a = e->NewByteArray(n);
  e->SetByteArrayRegion(a, 0, n, reinterpret_cast<const jbyte*>(buf));
  return a;
}

jstring JNI_FN(nativeResultText)(JNIEnv* e, jobject, jint result, jstring lang) {
  std::string l = str(e, lang);
  return e->NewStringUTF(scraper_result_text_lang(result, l.c_str()));
}

jstring JNI_FN(nativeLinkJson)(JNIEnv* e, jobject) {
  char buf[512];
  scraper_link_json(buf, sizeof buf);
  return e->NewStringUTF(buf);
}

}  // extern "C"
