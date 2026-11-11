package nl.eschouten.scraper.core

/** Android: libscraper_jni.so is built by the app's NDK build (androidApp, core/CMakeLists.txt). */
internal fun loadCoreLibrary() = System.loadLibrary("scraper_jni")
