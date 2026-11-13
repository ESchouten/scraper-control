// iOS side of the core: scraper_c.h through cinterop, the robot driver linked in as libcore.a.
@file:OptIn(ExperimentalForeignApi::class)

package nl.eschouten.scraper.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import nl.eschouten.scraper.core.c.scraper_connect
import nl.eschouten.scraper.core.c.scraper_encrypt_pin
import nl.eschouten.scraper.core.c.scraper_feed
import nl.eschouten.scraper.core.c.scraper_finish
import nl.eschouten.scraper.core.c.scraper_free
import nl.eschouten.scraper.core.c.scraper_image_version
import nl.eschouten.scraper.core.c.scraper_owed_planning
import nl.eschouten.scraper.core.c.scraper_read_routes
import nl.eschouten.scraper.core.c.scraper_routes_json
import nl.eschouten.scraper.core.c.scraper_set_owed_planning
import nl.eschouten.scraper.core.c.scraper_last_error
import nl.eschouten.scraper.core.c.scraper_link_down
import nl.eschouten.scraper.core.c.scraper_new
import nl.eschouten.scraper.core.c.scraper_on_progress
import nl.eschouten.scraper.core.c.scraper_press
import nl.eschouten.scraper.core.c.scraper_hold
import nl.eschouten.scraper.core.c.scraper_release
import nl.eschouten.scraper.core.c.scraper_unhold
import nl.eschouten.scraper.core.c.scraper_result_text_lang
import nl.eschouten.scraper.core.c.scraper_set_language
import nl.eschouten.scraper.core.c.scraper_set_planning
import nl.eschouten.scraper.core.c.scraper_start_route
import nl.eschouten.scraper.core.c.scraper_links_json
import nl.eschouten.scraper.core.c.scraper_status_json
import nl.eschouten.scraper.core.c.scraper_stop_route
import nl.eschouten.scraper.core.c.scraper_answer_stop

private fun callbacks(user: COpaquePointer?) = user!!.asStableRef<CoreCallbacks>().get()

private val onWriteC = staticCFunction { user: COpaquePointer?, data: CPointer<UByteVar>?, len: Int ->
  val bytes = data!!.reinterpret<ByteVar>().readBytes(len)
  scraper_release(data)
  callbacks(user).onWrite(bytes)
}
private val onDoneC = staticCFunction { user: COpaquePointer?, command: Int, result: Int ->
  callbacks(user).onDone(command, result)
}
private val onProgressC = staticCFunction { user: COpaquePointer?, step: Int ->
  callbacks(user).onProgress(step)
}

private class IosCore(
  private val s: CPointer<cnames.structs.scraper_session>,
  private val ref: StableRef<CoreCallbacks>,
) : CoreNative {
  override fun feed(data: ByteArray) = data.usePinned { scraper_feed(s, it.addressOf(0).reinterpret(), data.size) }
  override fun linkDown() = scraper_link_down(s)
  override fun connect() = scraper_connect(s)
  override fun startRoute(letter: Char, dryRun: Boolean) = scraper_start_route(s, letter.code.toByte(), if (dryRun) 1 else 0)
  override fun setPlanning(on: Boolean) = scraper_set_planning(s, if (on) 1 else 0)
  override fun stopRoute() = scraper_stop_route(s)
  override fun answerStop(finish: Boolean) = scraper_answer_stop(s, if (finish) 1 else 0)
  override fun readRoutes() = scraper_read_routes(s)
  override fun routesJson() = text { buf, size -> scraper_routes_json(s, buf, size) }
  override fun press(key: Int) = scraper_press(s, key)
  override fun hold(key: Int) = scraper_hold(s, key)
  override fun unhold() = scraper_unhold(s)
  override fun finish() = scraper_finish(s)
  override fun setOwedPlanning(owed: Int) = scraper_set_owed_planning(s, owed)
  override fun owedPlanning() = scraper_owed_planning(s)
  override fun statusJson() = text { buf, size -> scraper_status_json(s, buf, size) }
  override fun lastError() = text { buf, size -> scraper_last_error(s, buf, size) }

  override fun imageVersion(): ByteArray = memScoped {
    val out = allocArray<UByteVar>(9)
    val changed = scraper_image_version(s, out) == 1
    ByteArray(10) { i -> if (i < 9) out[i].toByte() else if (changed) 1 else 0 }
  }

  override fun free() {
    scraper_free(s) // waits for the core's thread: no callbacks after this
    ref.dispose()
  }

  private fun text(fn: (CPointer<ByteVar>?, Int) -> Int): String {
    val n = fn(null, 0)
    return memScoped {
      val buf = allocArray<ByteVar>(n + 1)
      fn(buf, n + 1)
      buf.toKString()
    }
  }
}

internal actual fun newCoreNative(pin: String, imageVersion: ByteArray?, language: String, callbacks: CoreCallbacks): CoreNative {
  val ref = StableRef.create(callbacks)
  val version = imageVersion?.let { v -> UByteArray(v.size) { v[it].toUByte() }.toCValues() }
  val s = scraper_new(pin, version, onWriteC, onDoneC, ref.asCPointer()) ?: error("scraper_new failed")
  scraper_on_progress(s, onProgressC)
  scraper_set_language(s, language)
  return IosCore(s, ref)
}

internal actual fun nativeResultText(code: Int, language: String): String =
  scraper_result_text_lang(code, language)?.toKString() ?: ""

internal actual fun nativeEncryptPin(pin: String): ByteArray = memScoped {
  val buf = allocArray<UByteVar>(64)
  val n = scraper_encrypt_pin(pin, buf, 64).coerceIn(0, 64)
  buf.reinterpret<ByteVar>().readBytes(n)
}

internal actual fun nativeLinksJson(): String = memScoped {
  val buf = allocArray<ByteVar>(1024)
  scraper_links_json(buf, 1024)
  buf.toKString()
}
