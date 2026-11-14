// The apps' fake robot (FakeRobot.kt, also on the iOS simulator) is a port of the one the core
// is tested against (tests/fake_robot.py). This keeps the two the same: the frames the real
// core sends in a few sessions are played to both, and every notification must be identical.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Passes everything through and keeps what the core wrote. */
private class RecordingLink(private val inner: RobotLink) : RobotLink by inner {
  val written: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
  override suspend fun write(frame: ByteArray) {
    written += frame.copyOf()
    inner.write(frame)
  }
}

private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Every notification as one hex string, so a difference shows where it is. */
private suspend fun replay(link: RobotLink, frames: List<ByteArray>, notifications: Flow<ByteArray>): List<String> {
  val got = Collections.synchronizedList(mutableListOf<String>())
  kotlinx.coroutines.coroutineScope {
    val job = launch { notifications.collect { got += hex(it) } }
    link.connect()
    for (f in frames) link.write(f)
    link.disconnect()
    link.awaitClosed()
    withTimeoutOrNull(500) { while (true) kotlinx.coroutines.delay(50) }
    job.cancel()
  }
  return got.toList()
}

class FakeRobotConformanceTest {
  private val stored = "DIS0018T4".encodeToByteArray()

  private fun check(planning: Boolean, mode: String, lang: String = "nl", session: suspend (Robot) -> Unit) = runBlocking {
    val args = listOf("--planning", if (planning) "on" else "off", "--mode", mode, "--lang", lang)
    // 1. what the core writes, against the Python robot
    val rec = RecordingLink(SimulatorLink(args = args))
    val robot = Robot(rec, pin = "4321", imageVersion = stored)
    try {
      robot.connect()
      session(robot)
    } finally {
      if (robot.isConnected) robot.disconnect()
    }
    val frames = rec.written.toList()
    assertTrue(frames.size > 3, "the core wrote ${frames.size} frames")
    // 2. the same frames to both robots, one after the other
    val py = SimulatorLink(args = args)
    val pyOut = replay(py, frames, py.notifications)
    val kt = FakeRobotLink(o = FakeRobotOptions(planning = planning, mode = mode, lang = lang, delayMs = 0))
    val ktOut = replay(kt, frames, kt.notifications)
    assertEquals(pyOut.size, ktOut.size, "number of notifications ($mode, planning $planning, $lang)")
    pyOut.zip(ktOut).forEachIndexed { i, (p, k) -> assertEquals(p, k, "notification $i ($mode, planning $planning, $lang)") }
  }

  @Test fun routeWithPlanningOn() = check(planning = true, mode = "home") { it.startRoute('C') }

  @Test fun routeWithPlanningOffAndDryRun() = check(planning = false, mode = "home") {
    it.startRoute('B', dryRun = true)
    it.startRoute('D')
  }

  @Test fun readRoutesAndPlanning() = check(planning = true, mode = "home") {
    it.readRoutes()
    it.setPlanning(false)
    it.setPlanning(true)
  }

  @Test fun stopWhileDriving() = check(planning = true, mode = "driving") { it.stopRoute() }

  @Test fun manualControlAndMainMenu() = check(planning = false, mode = "home") {
    for (k in listOf(RobotKey.Enter, RobotKey.Enter, RobotKey.Soft1, RobotKey.Soft1, RobotKey.Soft2, RobotKey.Soft3, RobotKey.Back, RobotKey.Down, RobotKey.Back, RobotKey.Back, RobotKey.Down, RobotKey.Up, RobotKey.Up, RobotKey.Up, RobotKey.Enter)) it.press(k)
  }

  @Test fun noticeAtLogin() = check(planning = true, mode = "notice") { it.startRoute('B', dryRun = true) }

  @Test fun english() = check(planning = true, mode = "home", lang = "en") { it.startRoute('A', dryRun = true) }
}
