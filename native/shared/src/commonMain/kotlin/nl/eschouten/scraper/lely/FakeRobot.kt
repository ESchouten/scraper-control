// A simulated Discovery panel inside the app, ported from tests/fake_robot.py (same screens
// and texts, copied from Erik's real robot). For the apps' demo/simulator mode, the screen
// scenarios (Compose UI tests, XCUITest) and screenshots: it runs on every platform, also on
// the iOS simulator where no Python runs. The core's own tests keep using fake_robot.py;
// FakeRobotConformanceTest checks that both answer the core byte for byte the same.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What routes A..D consist of, as on Erik's robot on 2026-11-05 (HANDM. ROUTE). */
private val ROUTE_PARTS = listOf("1  -  -", "2  3  -", "1  2  3", "4  -  -")

class FakeRobotOptions(
  val planning: Boolean = true,
  /** home, menu, mainmenu, manual, driving, question, abort */
  val mode: String = "home",
  val lang: String = "nl",
  /** Delay before each answer, so progress is visible like on the real robot. */
  val delayMs: Long = 0,
  val pin: String = "4321",
)

/** What the fake robot saw, for tests. */
class FakeRobotLog {
  val pressed = mutableListOf<String>()
  var mode = ""
  var planning = false
  var answer: String? = null
  var sel = 0
  var stopped = false
  var imagesSent = 0
}

private val KEYS = mapOf(1 to "back", 2 to "soft2", 4 to "soft1", 16 to "prog", 8 to "up", 32 to "down", 64 to "soft3", 128 to "enter", 256 to "start_stop")


private class T(
  val home: String, val menu: String, val main: List<String>, val safety: Pair<String, String>,
  val question: String, val stopped: String, val abort: String, val pause: String, val yes: String, val no: String, val manual: String, val drive: Pair<String, String>,
)

private val TEXTS = mapOf(
  "nl" to T(
    "Vlg rit:\rRoute  :\rActie  :\rActienr:\rRitnr  :\rAccu   :", "HANDM. ROUTE",
    listOf("Werken", "Routes", "Testen", "Instellingen", "Alarmen", "Service"),
    "VEILIGHEIDWAARSCHUW." to "Handmatige bediening\ris alleen toegestaan\rmet zicht op het\rvoertuig. Bevestig?",
    "Tijdpad na rijden\rroute aanzetten?", "ROUTE GESTOPT", "Route stop gezet:\rroute afmaken?", "Pauze", "Ja", "Nee", "HANDBEDIENING",
    "ROUTE->TIJDPAD" to "Route  :\rRitnr  :\rActie  :",
  ),
  "en" to T(
    "NextRun:\rRoute  :\rAction :\rAct.nr :\rRunnr  :\rBattery:", "MANUAL ROUTE",
    listOf("Work", "Routes", "Test", "Settings", "Alarms", "Service"),
    "SAFETY WARNING" to "Manual operation is\rpermitted only with a\rclear overview of the\rvehicle. Confirm?",
    "Timepath on after\rdriving route?", "ROUTE STOPPED", "Route stopped:\rfinish route?", "Pause", "Yes", "No", "MANUAL CONTROL",
    "ROUTE->TIMEPATH" to "Route  :\rRunnr  :\rAction :",
  ),
)

class FakeRobotLink(
  override val id: String = "00:12:FF:51:4D:00",
  private val o: FakeRobotOptions = FakeRobotOptions(),
  val log: FakeRobotLog = FakeRobotLog(),
) : RobotLink {
  private val version = "DIS0018T4".encodeToByteArray()
  private val t = TEXTS[o.lang] ?: TEXTS.getValue("nl")
  private var mode = o.mode
  private var planning = o.planning
  private var sel = 0
  private var mainSel = 3
  private var left = false
  private var right = false
  private var forward = true
  private var gotV1: ByteArray? = null
  private val buf = mutableListOf<Byte>()
  private val out = Channel<ByteArray>(Channel.UNLIMITED)
  private val closed = CompletableDeferred<Unit>()
  private val lock = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override val notifications: Flow<ByteArray> = out.receiveAsFlow()

  override suspend fun connect() {
    if (o.delayMs > 0) delay(600) // feels like Bluetooth
  }

  override suspend fun awaitClosed() = closed.await()

  override suspend fun disconnect() {
    sync()
    closed.complete(Unit)
    scope.cancel()
  }

  override suspend fun write(frame: ByteArray) = lock.withLock {
    buf.addAll(frame.toList())
    while (buf.size >= 8) {
      val mid = u16(0)
      val n = u16(6)
      if (buf.size < 8 + n) break
      val payload = buf.subList(8, 8 + n).toByteArray()
      repeat(8 + n) { buf.removeAt(0) }
      when (mid) {
        2000 -> emit(msg(2001, 57, byteArrayOf(0xaf.toByte(), 0, 0xa0.toByte(), 0) + "stal".encodeToByteArray() + byteArrayOf(0)))
        2018 -> emit(msg(2020, 57, byteArrayOf(if (pinOk(payload)) 1 else 0)))
        1536 -> if (payload.size % 16 == 0) {
          for (off in payload.indices step 16) can(payload.copyOfRange(off + 4, off + 12), payload[off + 12].toInt())
        }
      }
    }
    sync()
  }

  // The robot's PIN is o.pin: accept exactly what the core makes of it. The core holds the
  // key (the real one, or a test key in a build without tools/secrets.py).
  private fun pinOk(p: ByteArray): Boolean = p.contentEquals(nativeEncryptPin(o.pin))

  private fun u16(i: Int) = (buf[i].toInt() and 0xff) or ((buf[i + 1].toInt() and 0xff) shl 8)

  private fun sync() {
    log.mode = mode
    log.planning = planning
    log.sel = sel
  }

  private fun emit(bytes: ByteArray) {
    if (o.delayMs <= 0) {
      out.trySend(bytes)
    } else {
      scope.launch {
        delay(o.delayMs)
        out.trySend(bytes)
      }
    }
  }

  private fun msg(id: Int, sender: Int, payload: ByteArray): ByteArray {
    fun le(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8).toByte())
    return le(id) + le(244) + le(sender) + le(payload.size) + payload
  }

  private fun send(frames: List<ByteArray>) = emit(msg(1536, 245, frames.fold(ByteArray(0)) { a, b -> a + b }))

  private fun cr(vararg d: Int, ln: Int = 8): ByteArray {
    val r = ByteArray(16)
    r[0] = 0xE1.toByte(); r[1] = 0x03
    d.forEachIndexed { i, v -> r[4 + i] = v.toByte() }
    r[12] = ln.toByte()
    return r
  }

  private fun text(y: Int, x: Int, s: String, font: Int = 0): List<ByteArray> {
    val b = s.encodeToByteArray() + byteArrayOf(0)
    val f = mutableListOf(cr(3, 30, y, x, font))
    for (i in b.indices step 6) f += cr(3, 31, *b.copyOfRange(i, minOf(i + 6, b.size)).map { it.toInt() and 0xff }.toIntArray())
    return f
  }

  private fun menuRows(): List<ByteArray> = "ABCD".flatMapIndexed { i, l -> text(16 + 8 * i, 19, " $l ->  ${ROUTE_PARTS[i]} ") }

  private fun draw() {
    val f = mutableListOf(cr(3, 1))
    when (mode) {
      "home" -> {
        f += text(3, 4, "20:21:19", 1) + text(3, 105, if (planning) " ON" else "OFF", 1)
        f += text(14, 4, t.home)
        f += text(14, 58, "21:00:00") + text(22, 94, "01") + text(30, 48, "------------")
        f += text(38, 76, "    -") + text(46, 76, "   10") + text(54, 76, "13500")
        f += cr(3, 37, 1, 1, 11, 126, 2)
      }
      "mainmenu" -> {
        t.main.forEachIndexed { i, m -> f += text(4 + 8 * i, 0, " " + m.padEnd(15)) }
        f += cr(3, 37, 4 + 8 * mainSel, 0, 8, 128, 3)
      }
      "menu" -> {
        f += text(3, 28, t.menu, 1) + menuRows()
        f += cr(3, 33, 51, 0, 12, 42, 1); f += cr(3, 33, 51, 85, 12, 42, 1)
        f += text(54, 6, "Reset") + text(54, 52, "Save") + text(54, 103, ">")
        f += cr(3, 37, 16 + 8 * sel, 19, 8, 90, 1)
      }
      "safety" -> {
        f += text(3, 4, t.safety.first, 1) + text(14, 1, t.safety.second)
        f += cr(3, 33, 51, 0, 12, 42, 3); f += cr(3, 33, 51, 85, 12, 42, 1)
        f += text(54, 15, t.yes) + text(54, 97, t.no)
      }
      "manual" -> {
        f += text(3, 25, t.manual, 1); f += cr(3, 36, 16, 49, 13)
        f += cr(3, 33, 51, 0, 12, 42, 1); f += cr(3, 33, 51, 85, 12, 42, 1)
        f += text(54, 18, "L") + text(54, 103, "R"); f += cr(3, 36, 53, 58, if (forward) 16 else 15)
        if (left) f += cr(3, 37, 52, 1, 11, 41, 2)
        if (right) f += cr(3, 37, 52, 86, 11, 41, 2)
      }
      // after Stop during a route, as captured 2026-11-11: Ja = finish (drives on) / Pauze / Nee
      "abort" -> {
        f += text(3, 25, t.stopped, 1) + text(16, 4, t.abort)
        f += cr(3, 33, 51, 0, 12, 42, 3); f += cr(3, 33, 39, 42, 12, 43, 1); f += cr(3, 33, 42, 42, 12, 43, 3); f += cr(3, 33, 51, 85, 12, 42, 1)
        f += text(54, 15, t.yes) + text(42, 52, "HAND") + text(54, 49, t.pause) + text(54, 97, t.no)
      }
      "question" -> f += text(3, 28, t.menu, 1) + text(14, 4, t.question) + text(54, 15, t.yes) + text(54, 97, t.no)
      else -> {
        f += text(3, 20, t.drive.first, 1) + text(14, 4, t.drive.second)
        f += text(14, 90, "1") + text(22, 90, "11") + text(30, 48, "WANDVOLG_DST")
      }
    }
    send(f)
  }

  private fun can(d: ByteArray, ln: Int) {
    val b = d.map { it.toInt() and 0xff }
    if (b[0] == 2 && b[1] == 2) send(listOf(cr(5, 0, 4, 0, ln = 2))) // connect -> ask image version
    else if (b[0] == 5 && b[1] == 1 && b[2] == 0) gotV1 = d.copyOfRange(3, 8)
    else if (b[0] == 5 && b[1] == 1 && b[2] == 1) {
      val got = (gotV1 ?: ByteArray(5)) + d.copyOfRange(3, 7)
      gotV1 = null
      if (got.contentEquals(version)) {
        send(listOf(cr(5, 2, 0, 0)))
      } else {
        log.imagesSent = 52
        send(listOf(cr(5, 4, 0, *version.copyOfRange(0, 5).map { it.toInt() }.toIntArray()), cr(5, 4, 1, *version.copyOfRange(5, 9).map { it.toInt() }.toIntArray()), cr(5, 2, 0, 0)))
      }
    } else if (b[0] == 5 && b[1] == 3) draw()
    val ev = b[0] or (b[1] shl 8)
    val key = b[2] or (b[3] shl 8)
    if (ln == 4 && mode == "manual" && KEYS[key] == "start_stop") return
    if (ln != 4 || ev != 0) return
    val k = KEYS[key] ?: return
    log.pressed += k
    when (mode) {
      "home" -> when {
        k == "start_stop" -> planning = !planning
        k == "enter" && !planning -> mode = "menu"
        k == "back" && !planning -> mode = "mainmenu"
      }
      "mainmenu" -> {
        if (k == "up" || k == "down") {
          val old = mainSel
          mainSel = if (k == "down") minOf(5, old + 1) else maxOf(0, old - 1)
          if (old != mainSel) send(listOf(cr(3, 37, 4 + 8 * old, 0, 8, 128, 3), cr(3, 37, 4 + 8 * mainSel, 0, 8, 128, 3)))
          return
        } else if (k == "enter" && mainSel == 0) mode = "home"
      }
      "menu" -> when (k) {
        "up", "down" -> {
          sel = if (k == "down") minOf(3, sel + 1) else maxOf(0, sel - 1)
          send(menuRows() + listOf(cr(3, 37, 16 + 8 * sel, 19, 8, 90, 1)))
          return
        }
        "back" -> mode = "home"
        "start_stop" -> mode = "question"
        "enter" -> mode = "safety"
      }
      "safety" -> if (k == "soft1") mode = "manual" else if (k == "soft3") mode = "menu"
      "manual" -> {
        when (k) {
          "soft1" -> { left = !left; send(listOf(cr(3, 37, 52, 1, 11, 41, 2))) }
          "soft2" -> { forward = !forward; send(listOf(cr(3, 36, 53, 58, if (forward) 16 else 15))) }
          "soft3" -> { right = !right; send(listOf(cr(3, 37, 52, 86, 11, 41, 2))) }
          "back" -> mode = "menu"
        }
        if (k != "back") return
      }
      "question" -> if (k == "soft1" || k == "soft3") {
        log.answer = if (k == "soft1") "ja" else "nee"
        mode = "driving"
      }
      "driving" -> if (k == "back") mode = "STOPPED-BY-ESC" else if (k == "start_stop") { mode = "abort"; log.stopped = true }
      "abort" -> when (k) {
        "soft1" -> { mode = "driving"; log.stopped = false }   // Ja: finish the route
        "soft2" -> mode = "paused"
        "soft3" -> { mode = "home"; planning = false }          // Nee: route over, planning off
      }
    }
    draw()
  }
}

/** The fake robot for every Lely robot: the apps' demo mode, scenarios and screenshots. */
object Demo {
  val robots = listOf(FoundRobot("00:12:FF:51:4D:00", "Demo", -48), FoundRobot("00:12:FF:51:4D:01", "Demo 2", -71))

  /** The last link made, to read what the robot saw. */
  var last: FakeRobotLink? = null
    private set

  fun install(options: FakeRobotOptions = FakeRobotOptions(delayMs = 40), scanDelayMs: Long = 1_200) {
    LelyDiscovery.linkProvider = LinkProvider({ id -> FakeRobotLink(id, options).also { last = it } }, robots, scanDelayMs)
  }

  /** From demo words, e.g. "planning=off mode=driving lang=en" (app launch arguments). */
  fun install(kv: Map<String, String>) {
    install(
      FakeRobotOptions(
        planning = kv["planning"] != "off",
        mode = kv["mode"] ?: "home",
        lang = kv["lang"] ?: "nl",
        delayMs = kv["delay"]?.toLongOrNull() ?: 40,
      ),
      scanDelayMs = kv["scan"]?.toLongOrNull() ?: 1_200,
    )
  }

  fun uninstall() {
    LelyDiscovery.linkProvider = null
  }
}
