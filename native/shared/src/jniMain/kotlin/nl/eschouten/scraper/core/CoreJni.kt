// JNI side of core/jni/scraper_jni.cpp, shared by Android and the desktop JVM.
package nl.eschouten.scraper.core

internal object CoreJni {
  init { loadCoreLibrary() }

  external fun nativeNew(pin: String, version: ByteArray?, listener: CoreCallbacks): Long
  external fun nativeFree(h: Long)
  external fun nativeFeed(h: Long, data: ByteArray)
  external fun nativeLinkDown(h: Long)
  external fun nativeConnect(h: Long): Int
  external fun nativeStartRoute(h: Long, letter: Char, dryRun: Boolean): Int
  external fun nativeSetPlanning(h: Long, on: Boolean): Int
  external fun nativeStopRoute(h: Long): Int
  external fun nativeAnswerStop(h: Long, finish: Boolean): Int
  external fun nativeReadRoutes(h: Long): Int
  external fun nativeRoutesJson(h: Long): String
  external fun nativePress(h: Long, key: Int): Int
  external fun nativeFinish(h: Long): Int
  external fun nativeSetOwedPlanning(h: Long, owed: Int)
  external fun nativeOwedPlanning(h: Long): Int
  external fun nativeStatusJson(h: Long): String
  external fun nativeLastError(h: Long): String
  external fun nativeImageVersion(h: Long): ByteArray
  external fun nativeSetLanguage(h: Long, language: String)
  external fun nativeResultText(result: Int, language: String): String
  external fun nativeEncryptPin(pin: String): ByteArray
  external fun nativeLinkJson(): String
}

private class JniCore(private val h: Long) : CoreNative {
  override fun feed(data: ByteArray) = CoreJni.nativeFeed(h, data)
  override fun linkDown() = CoreJni.nativeLinkDown(h)
  override fun connect() = CoreJni.nativeConnect(h)
  override fun startRoute(letter: Char, dryRun: Boolean) = CoreJni.nativeStartRoute(h, letter, dryRun)
  override fun setPlanning(on: Boolean) = CoreJni.nativeSetPlanning(h, on)
  override fun stopRoute() = CoreJni.nativeStopRoute(h)
  override fun answerStop(finish: Boolean) = CoreJni.nativeAnswerStop(h, finish)
  override fun readRoutes() = CoreJni.nativeReadRoutes(h)
  override fun routesJson() = CoreJni.nativeRoutesJson(h)
  override fun press(key: Int) = CoreJni.nativePress(h, key)
  override fun finish() = CoreJni.nativeFinish(h)
  override fun setOwedPlanning(owed: Int) = CoreJni.nativeSetOwedPlanning(h, owed)
  override fun owedPlanning() = CoreJni.nativeOwedPlanning(h)
  override fun statusJson() = CoreJni.nativeStatusJson(h)
  override fun lastError() = CoreJni.nativeLastError(h)
  override fun imageVersion() = CoreJni.nativeImageVersion(h)
  override fun free() = CoreJni.nativeFree(h)
}

internal actual fun newCoreNative(pin: String, imageVersion: ByteArray?, language: String, callbacks: CoreCallbacks): CoreNative {
  val h = CoreJni.nativeNew(pin, imageVersion, callbacks)
  CoreJni.nativeSetLanguage(h, language)
  return JniCore(h)
}

internal actual fun nativeResultText(code: Int, language: String): String = CoreJni.nativeResultText(code, language)

internal actual fun nativeEncryptPin(pin: String): ByteArray = CoreJni.nativeEncryptPin(pin)

internal actual fun nativeLinkJson(): String = CoreJni.nativeLinkJson()
