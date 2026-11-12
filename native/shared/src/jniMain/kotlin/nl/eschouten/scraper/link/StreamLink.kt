// A link over a byte stream both ways (classic Bluetooth serial on Android). [open] connects and
// returns the streams; what comes in is read on its own thread into notifications.
package nl.eschouten.scraper.link

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import nl.eschouten.scraper.core.RobotLink

class StreamLink(override val id: String, private val open: () -> Streams) : RobotLink {
  /** What [open] gives: [close] ends both. */
  class Streams(val input: InputStream, val output: OutputStream, val close: Closeable)

  private var streams: Streams? = null
  private val frames = Channel<ByteArray>(Channel.UNLIMITED)
  private val closed = CompletableDeferred<Unit>()

  override val notifications: Flow<ByteArray> = frames.receiveAsFlow()

  override suspend fun connect() {
    val s = withContext(Dispatchers.IO) { open() }
    streams = s
    thread(isDaemon = true, name = "robot-stream") {
      val buf = ByteArray(1024)
      try {
        while (true) {
          val n = s.input.read(buf)
          if (n < 0) break
          if (n > 0) frames.trySend(buf.copyOf(n))
        }
      } catch (_: IOException) { // closed
      }
      closed.complete(Unit)
    }
  }

  override suspend fun awaitClosed() = closed.await()

  override suspend fun write(frame: ByteArray) {
    val s = streams ?: return
    withContext(Dispatchers.IO) {
      s.output.write(frame)
      s.output.flush()
    }
  }

  override suspend fun disconnect() {
    val s = streams
    streams = null
    if (s != null) runCatching { s.close.close() }
    closed.complete(Unit)
  }
}
