// The robot API, same names as Python and the M5Stack. All logic runs in the robot driver
// (C++); this class only joins it to a [RobotLink].
package nl.eschouten.scraper.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The link to the robot: bytes to it and bytes from it. Each platform provides one per kind
 *  of link the drivers ask for (link/LinkSpec.kt). */
interface RobotLink {
  /** Identifier to remember the robot by (MAC on Android, UUID on iOS). */
  val id: String

  /** Open the link and subscribe to notifications. */
  suspend fun connect()

  /** Bytes from the robot, from [connect] until the link drops. */
  val notifications: Flow<ByteArray>

  /** Returns when the link drops (or after [disconnect]). */
  suspend fun awaitClosed()

  suspend fun write(frame: ByteArray)
  suspend fun disconnect()
}

open class RobotException(val result: CoreResult, message: String) : Exception(message)

/** Robot is not on the home screen or route menu (driving?): nothing was pressed. */
class RobotBusy(result: CoreResult, message: String) : RobotException(result, message)

class PinRejected(result: CoreResult, message: String) : RobotException(result, message)

class Robot(
  val link: RobotLink,
  val pin: String? = null,
  /** 9 bytes remembered from last time; skips the robot's ~75 s image download. */
  var imageVersion: ByteArray? = null,
  /** Optional progress callback (connect, PIN ok, route menu, Start, ...). */
  val onStep: ((CoreStep) -> Unit)? = null,
  /** Language of error messages: "nl" or "en". */
  val language: String = "nl",
  /** Planning an earlier session still owed after its link dropped (1 / 0, -1 none). */
  owedPlanning: Int = -1,
  /** The owed planning changed (1 / 0 / -1): keep it with the robot for the next session. */
  val onOwed: ((Int) -> Unit)? = null,
) {
  /** See scraper_c.h: what the robot must be put back to if this link drops now. */
  var owedPlanning: Int = owedPlanning
    private set

  private var core: CoreSession? = null
  private var scope: CoroutineScope? = null
  private var writes: Channel<ByteArray>? = null
  private var writer: Job? = null

  val isConnected: Boolean get() = core != null

  /** Set after [connect] when the robot reported a new image version: store it. */
  var newImageVersion: ByteArray? = null
    private set

  suspend fun connect() {
    link.connect()
    val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val out = Channel<ByteArray>(Channel.UNLIMITED)
    val c = CoreSession(onWrite = { out.trySend(it) }, pin = pin, imageVersion = imageVersion, onStep = onStep, language = language)
    core = c
    scope = s
    writes = out
    s.launch { link.notifications.collect { c.feed(it) } }
    s.launch {
      link.awaitClosed()
      c.linkDown()
    }
    writer = s.launch {
      for (frame in out) {
        try {
          link.write(frame)
        } catch (e: Exception) {
          c.linkDown()
        }
      }
    }
    c.owedPlanning = owedPlanning
    val r = c.connect()
    noteOwed()
    val (version, changed) = c.imageVersion()
    if (changed) {
      newImageVersion = version
      imageVersion = version
    }
    if (r != CoreResult.Ok) {
      val e = error(r)
      disconnect()
      throw e
    }
  }

  fun status(): CoreStatus = core?.status() ?: CoreStatus()

  suspend fun startRoute(letter: Char, dryRun: Boolean = false) = run { it.startRoute(letter, dryRun) }
  suspend fun setPlanning(on: Boolean) = run { it.setPlanning(on) }

  /** Safety stop: one tap on Start/Stop, only while a route runs ([CoreStatus.driving]). */
  suspend fun stopRoute() = run { it.stopRoute() }

  /** The user's answer to "route afmaken?" after a stop ([CoreStatus.stopQuestion]). */
  suspend fun answerStop(finish: Boolean) = run { it.answerStop(finish) }

  /** What routes A..D consist of on the robot, e.g. "C" -> "1 2 3". Only reads. */
  suspend fun readRoutes(): Map<String, String> {
    run { it.readRoutes() }
    return core?.routes() ?: emptyMap()
  }
  suspend fun press(key: RobotKey) = run { it.press(key.code) }

  /** [key] held down as on the robot's own panel until [up] completes: in manual control the
   *  robot drives while Start/Stop is held. Returns after the key went up. */
  suspend fun hold(key: RobotKey, up: Deferred<*>) = run { it.hold(key.code, up) }

  /** Leaves the robot as it was found: back to the home screen from a safe menu and the
   *  planning as it was at connect (unless [setPlanning] changed it on purpose). */
  suspend fun disconnect() {
    val c = core
    if (c != null) withTimeoutOrNull(30_000) { c.finish() }
    noteOwed()
    writes?.close()
    withTimeoutOrNull(1_000) { writer?.join() } // let the last frames (e.g. Back) go out
    c?.close()
    core = null
    scope?.cancel()
    scope = null
    writes = null
    writer = null
    link.disconnect()
  }

  private suspend fun run(command: suspend (CoreSession) -> CoreResult) {
    val c = core ?: throw RobotException(CoreResult.ConnectFailed, coreResultText(CoreResult.ConnectFailed, language))
    val r = command(c)
    noteOwed()
    if (r != CoreResult.Ok) throw error(r)
  }

  private fun noteOwed() {
    val v = core?.owedPlanning ?: return
    if (v == owedPlanning) return
    owedPlanning = v
    onOwed?.invoke(v)
  }

  private fun error(r: CoreResult): RobotException {
    val why = "${coreResultText(r, language)}: ${core?.lastError() ?: ""}".trim().trimEnd(':')
    return when (r) {
      CoreResult.Busy -> RobotBusy(r, why)
      CoreResult.PinRejected -> PinRejected(r, why)
      else -> RobotException(r, why)
    }
  }
}
