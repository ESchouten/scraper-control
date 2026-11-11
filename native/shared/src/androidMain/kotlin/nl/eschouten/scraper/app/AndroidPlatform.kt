package nl.eschouten.scraper.app

import android.content.Context

/** The app's context, set once by the app (Application.onCreate). */
object AndroidPlatform {
  lateinit var context: Context
    private set

  fun init(c: Context) {
    context = c.applicationContext
  }

  val isReady get() = ::context.isInitialized
}
