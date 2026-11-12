// Desktop development and tests: the simulated robot (drivers/lely/tests/fake_robot_server.py)
// instead of Bluetooth, so the whole app (with the real C++ core) runs without a robot.
//   SCRAPER_SIMULATOR=../drivers/lely/tests/fake_robot_server.py ./gradlew :composeApp:run
//   SCRAPER_SIMULATOR_ARGS="--planning off"   (optional)
// The tests get the script in SIMULATOR_SCRIPT (build.gradle.kts). The simulator's PIN is 4321.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object Simulator {
  val robots = listOf(FoundRobot("00:12:FF:51:4D:00", "Simulator", -48), FoundRobot("00:12:FF:51:4D:01", "Simulator", -71))

  /** The desktop app's simulator (SCRAPER_SIMULATOR), or null. */
  fun provider(script: String? = System.getenv("SCRAPER_SIMULATOR")?.takeIf { it.isNotBlank() }, args: List<String> = envArgs(), frameDelayMs: Long = 40, scanDelayMs: Long = 2_000): LinkProvider? {
    val s = script ?: return null
    return LinkProvider({ id -> SimulatorLink(s, args, id, frameDelayMs) }, robots, scanDelayMs)
  }

  private fun envArgs() = (System.getenv("SCRAPER_SIMULATOR_ARGS") ?: "").split(' ').filter { it.isNotEmpty() }
}

/** The simulated robot over stdin/stdout: each frame is a 2-byte little-endian length, then
 *  the bytes. [report] gives what it saw (pressed keys, mode, answer, ...) after [disconnect]. */
class SimulatorLink(
  private val script: String = System.getenv("SIMULATOR_SCRIPT") ?: System.getenv("SCRAPER_SIMULATOR") ?: error("SIMULATOR_SCRIPT not set"),
  private val args: List<String> = emptyList(),
  override val id: String = "00:12:FF:51:4D:00",
  /** A little delay per frame, so progress is visible like on the real robot. */
  private val frameDelayMs: Long = 0,
) : RobotLink {
  private var process: Process? = null
  private var stdin: OutputStream? = null
  private val frames = Channel<ByteArray>(Channel.UNLIMITED)
  private val closed = CompletableDeferred<Unit>()
  private val reportLine = CompletableDeferred<String>()

  suspend fun report(): String = reportLine.await()

  override val notifications: Flow<ByteArray> = frames.receiveAsFlow()

  override suspend fun connect() = withContext(Dispatchers.IO) {
    if (frameDelayMs > 0) delay(600) // feels like Bluetooth
    // LELY_PYTHON: a python3 that has the `cryptography` package (fake_robot.py needs it)
    val python = System.getenv("LELY_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
    val p = ProcessBuilder(listOf(python, script) + args).start()
    process = p
    stdin = p.outputStream
    thread(isDaemon = true, name = "simulator-stderr") {
      val last = p.errorStream.bufferedReader().readLines().lastOrNull { it.startsWith("{") }
      reportLine.complete(last ?: "{}")
    }
    thread(isDaemon = true, name = "simulator-stdout") {
      val input = DataInputStream(p.inputStream)
      try {
        while (true) {
          val n = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
          val frame = ByteArray(n)
          input.readFully(frame)
          if (frameDelayMs > 0) Thread.sleep(frameDelayMs)
          frames.trySend(frame)
        }
      } catch (_: Exception) {
      }
      closed.complete(Unit)
    }
    Unit
  }

  override suspend fun awaitClosed() = closed.await()

  override suspend fun write(frame: ByteArray) = withContext(Dispatchers.IO) {
    val out = stdin ?: return@withContext
    synchronized(out) {
      out.write(byteArrayOf((frame.size and 0xff).toByte(), (frame.size shr 8).toByte()))
      out.write(frame)
      out.flush()
    }
  }

  override suspend fun disconnect() = withContext(Dispatchers.IO) {
    val p = process ?: return@withContext
    process = null
    runCatching { stdin?.close() }
    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
    closed.complete(Unit)
    Unit
  }
}

/** SCRAPER_SIMULATOR=<path to fake_robot_server.py>: the desktop app drives the simulator. */
internal actual fun devLinkProvider(): LinkProvider? = Simulator.provider()
