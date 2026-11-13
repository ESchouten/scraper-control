// The robot driver's C API (core/scraper_c.h), seen from Kotlin. All robot logic lives in the
// driver; this file only turns its callbacks into suspend functions.
package nl.eschouten.scraper.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/** Result codes of the core (scraper_c.h). */
enum class CoreResult { Ok, ConnectFailed, PinRejected, NoScreen, Busy, Unexpected, StartedNoAnswer, InProgress;

  val code: Int get() = if (this == InProgress) 100 else ordinal

  companion object {
    fun of(code: Int): CoreResult = if (code == 100) InProgress else entries.getOrElse(code) { Unexpected }
  }
}

/** Progress while a command runs (scraper_c.h SCRAPER_STEP_*). */
enum class CoreStep {
  Linked, // Bluetooth link open
  Granted, // PIN accepted
  ScreenReady, // panel screen received
  PlanningOff, // time planning switched off
  PlanningOn, // time planning switched on
  Menu, // HANDM. ROUTE open
  Selected, // route selected
  StartPressed, // Start pressed, question shown
  Answered, // question answered: route is running
  Restored, // dry run / abort: back as before
  Syncing, // the robot sends its image set (first connection), again per part: CoreStatus.syncPercent
}

/** Panel keys (docs/PROTOCOL.md). */
enum class RobotKey(val code: Int) {
  Back(1), Soft2(2), Soft1(4), Up(8), Prog(16), Down(32), Soft3(64), Enter(128), StartStop(256)
}

/** What the core read from the robot's screen (scraper_status_json). */
data class CoreStatus(
  /** Time planning on/off; null = not on the home screen. */
  val planning: Boolean? = null,
  val nextRide: String = "",
  val route: String = "",
  val action: String = "",
  val batteryMv: Int? = null,
  val busy: Boolean = true,
  /** A route runs (driving screen): stopRoute() can stop it. */
  val driving: Boolean = false,
  /** After a stop the robot asks "finish the route?": answerStop() passes the user's choice on. */
  val stopQuestion: Boolean = false,
  val screen: String = "",
  /** Selected menu row as on the display, "" if none. */
  val highlighted: String = "",
  /** The robot's own language: "nl", "en" or "" if not known. */
  val language: String = "",
  /** How far the robot is with sending its image set (0-100), null when it is not. */
  val syncPercent: Int? = null,
) {
  companion object {
    fun fromJson(json: String): CoreStatus {
      val d = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return CoreStatus()
      fun s(k: String) = d[k]?.jsonPrimitive?.content ?: ""
      fun i(k: String) = d[k]?.jsonPrimitive?.intOrNull
      fun b(k: String) = d[k]?.jsonPrimitive?.booleanOrNull
      return CoreStatus(
        planning = when (i("planning")) { 1 -> true; 0 -> false; else -> null },
        nextRide = s("nextRide"), route = s("route"), action = s("action"),
        batteryMv = i("batteryMv")?.takeIf { it >= 0 },
        busy = b("busy") ?: true, driving = b("driving") ?: false, stopQuestion = b("stopQuestion") ?: false,
        screen = s("screen"), highlighted = s("highlighted"), language = s("language"),
        syncPercent = i("sync")?.takeIf { it >= 0 },
      )
    }
  }
}

/** Callbacks from the core's own thread. They must not block. */
internal interface CoreCallbacks {
  fun onWrite(frame: ByteArray)
  fun onDone(command: Int, result: Int)
  fun onProgress(step: Int)
}

/** One scraper_session, per platform: JNI (Android, desktop) or cinterop (iOS). */
internal interface CoreNative {
  fun feed(data: ByteArray)
  fun linkDown()
  fun connect(): Int
  fun startRoute(letter: Char, dryRun: Boolean): Int
  fun setPlanning(on: Boolean): Int
  fun stopRoute(): Int
  fun answerStop(finish: Boolean): Int
  fun readRoutes(): Int
  fun routesJson(): String
  fun press(key: Int): Int
  fun finish(): Int
  fun setOwedPlanning(owed: Int)
  fun owedPlanning(): Int
  fun statusJson(): String
  fun lastError(): String
  /** 9 bytes of image version, then 1 if the robot sent a new one. */
  fun imageVersion(): ByteArray
  fun free()
}

internal expect fun newCoreNative(pin: String, imageVersion: ByteArray?, language: String, callbacks: CoreCallbacks): CoreNative

internal expect fun nativeResultText(code: Int, language: String): String

/** The PIN as the core sends it (for the fake robots in tests). */
internal expect fun nativeEncryptPin(pin: String): ByteArray

/** How the driver's robots are reached (scraper_links_json): a JSON array, preferred first. */
internal expect fun nativeLinksJson(): String

/** The result in words, "nl" or "en". */
internal fun coreResultText(result: CoreResult, language: String): String = nativeResultText(result.code, language)

/** One connection's worth of robot logic in the core. Commands run one at a time. */
class CoreSession(
  onWrite: (ByteArray) -> Unit,
  pin: String? = null,
  imageVersion: ByteArray? = null,
  onStep: ((CoreStep) -> Unit)? = null,
  language: String = "nl",
) {
  @Volatile private var pending: CompletableDeferred<CoreResult>? = null
  @Volatile private var native: CoreNative? = null

  init {
    native = newCoreNative(pin ?: "", imageVersion?.takeIf { it.size == 9 }, language, object : CoreCallbacks {
      override fun onWrite(frame: ByteArray) = onWrite(frame)
      override fun onDone(command: Int, result: Int) {
        val p = pending
        pending = null
        p?.complete(CoreResult.of(result))
      }
      override fun onProgress(step: Int) {
        if (onStep != null && step in 1..CoreStep.entries.size) onStep(CoreStep.entries[step - 1])
      }
    })
  }

  /** Bytes from the robot (TX notifications). */
  fun feed(data: ByteArray) {
    if (data.isNotEmpty()) native?.feed(data)
  }

  fun linkDown() { native?.linkDown() }

  suspend fun connect() = command { it.connect() }
  suspend fun startRoute(letter: Char, dryRun: Boolean = false) = command { it.startRoute(letter.uppercaseChar(), dryRun) }
  suspend fun setPlanning(on: Boolean) = command { it.setPlanning(on) }
  suspend fun stopRoute() = command { it.stopRoute() }
  /** The user's answer to "route afmaken?" after a stop: true = Ja (drives on), false = Nee. */
  suspend fun answerStop(finish: Boolean) = command { it.answerStop(finish) }
  suspend fun readRoutes() = command { it.readRoutes() }

  /** After [readRoutes]: route letter -> its sub-routes, e.g. "C" -> "1 2 3". */
  fun routes(): Map<String, String> = native?.let { n ->
    runCatching { Json.parseToJsonElement(n.routesJson()).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrNull()
  } ?: emptyMap()
  suspend fun press(key: Int) = command { it.press(key) }
  suspend fun finish() = command { it.finish() }

  /** Planning owed after a dropped link: 1 / 0, -1 = none (see scraper_c.h). Hand a remembered
   *  value to the next session before [connect]; read it after each command and keep it. */
  var owedPlanning: Int
    get() = native?.owedPlanning() ?: -1
    set(v) { native?.setOwedPlanning(v) }

  private suspend fun command(start: (CoreNative) -> Int): CoreResult {
    val n = native ?: return CoreResult.ConnectFailed
    if (pending != null) return CoreResult.InProgress
    val d = CompletableDeferred<CoreResult>()
    pending = d
    val r = start(n)
    if (r != 0) {
      pending = null
      return CoreResult.of(r)
    }
    // The core completes [d] on its own thread. Resume on Default, never on that thread: a
    // caller without a dispatcher (Swift, a UI test) would otherwise run on and could close
    // the session from the core's thread (it joins itself and aborts).
    return withContext(Dispatchers.Default) { d.await() }
  }

  fun status(): CoreStatus = native?.let { CoreStatus.fromJson(it.statusJson()) } ?: CoreStatus()
  fun lastError(): String = native?.lastError() ?: ""

  /** The 9-byte image version, and whether the robot sent a new one this time. */
  fun imageVersion(): Pair<ByteArray, Boolean> {
    val v = native?.imageVersion() ?: return ByteArray(9) to false
    return v.copyOf(9) to (v[9].toInt() == 1)
  }

  /** Stops the core's thread (waits for a running command). Safe to call twice. */
  fun close() {
    val n = native ?: return
    native = null
    n.free()
    pending?.complete(CoreResult.ConnectFailed)
    pending = null
  }
}
