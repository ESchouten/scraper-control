// Classic Bluetooth serial on the desktop, through the system's own Bluetooth in the core's JNI
// library (core/jni/bt_serial.h, one file per OS). Where the OS has none it finds nothing.
package nl.eschouten.scraper.link

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.eschouten.scraper.core.RobotLink
import nl.eschouten.scraper.core.loadCoreLibrary

internal object DesktopBt {
  init { loadCoreLibrary() }

  val supported: Boolean by lazy { runCatching { nativeBtSupported() }.getOrDefault(false) }

  external fun nativeBtSupported(): Boolean
  external fun nativeBtFind(prefix: String, ms: Int): String
  external fun nativeBtOpen(address: String, service: String): Long
  external fun nativeBtRead(handle: Long, buf: ByteArray): Int
  external fun nativeBtWrite(handle: Long, data: ByteArray): Boolean
  external fun nativeBtClose(handle: Long)
}

/** How long a search listens for classic devices, like Android. */
private const val DISCOVERY_MS = 8_000

internal actual suspend fun findClassicRobots(spec: LinkSpec.BtSerial): List<FoundRobot> {
  if (!DesktopBt.supported) return emptyList()
  val json = withContext(Dispatchers.IO) { DesktopBt.nativeBtFind(spec.addressPrefix, DISCOVERY_MS) }
  return Json.parseToJsonElement(json).jsonArray.map {
    val o = it.jsonObject
    FoundRobot(o["address"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["rssi"]!!.jsonPrimitive.int, spec.kind)
  }
}

internal actual fun btSerialLink(spec: LinkSpec.BtSerial, id: String): RobotLink? {
  if (!DesktopBt.supported) return null
  return StreamLink(id) {
    val h = DesktopBt.nativeBtOpen(id.lowercase(), spec.service)
    StreamLink.Streams(
      input = object : InputStream() {
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
          val buf = ByteArray(len)
          val n = DesktopBt.nativeBtRead(h, buf)
          if (n > 0) buf.copyInto(b, off, 0, n)
          return n
        }
      },
      output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
          if (!DesktopBt.nativeBtWrite(h, b.copyOfRange(off, off + len))) throw IOException("link closed")
        }
      },
      close = { DesktopBt.nativeBtClose(h) },
    )
  }
}
