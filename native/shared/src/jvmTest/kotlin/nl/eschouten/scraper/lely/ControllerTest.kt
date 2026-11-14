// The RobotController (shared by every app) against the simulated robot.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import nl.eschouten.scraper.app.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.eschouten.scraper.ui.finishQuestion
import nl.eschouten.scraper.texts.useLanguage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerTest {
  private val links = mutableListOf<SimulatorLink>()

  private fun simulate(vararg args: String) {
    LelyDiscovery.linkProvider = LinkProvider(
      link = { id -> SimulatorLink(args = args.toList(), id = id).also { links += it } },
      robots = Simulator.robots,
      scanDelayMs = 10,
    )
  }

  private fun controller(pin: String = "4321", robotId: String? = "00:12:FF:51:4D:00", store: Store = MemoryStore()) =
    RobotController(Settings.single(robotId = robotId, pin = pin, routeNames = mapOf("C" to "Voergang")), store)

  @BeforeTest fun nl() = useLanguage("nl")

  @AfterTest fun reset() {
    LelyDiscovery.linkProvider = null
  }

  @Test fun startRouteWithPlanningOnPausesIt() = runBlocking {
    simulate("--planning", "on")
    val c = controller()
    val o = c.startRoute("C")
    assertTrue(o.ok, o.message)
    assertEquals("Voergang gestart. De tijdsplanning gaat na de route weer aan.", o.message)
    val s = c.state.value
    assertTrue(s.planningResumes)
    assertEquals(LinkState.Idle, s.link)
    assertFalse(s.connected)
    assertEquals(EventKind.Route, s.history.first().kind)
    assertEquals("Voergang", s.history.first().title)
    assertNotNull(s.lastStart("C"))
    assertEquals(true, s.robot.snapshot?.planning == false || s.robot.snapshot?.planning == true)
    assertTrue(s.steps.isNotEmpty())
  }

  /** A route button during a route stops it; the robot then asks "route afmaken?" and the
   *  app asks the user (Erik 2026-11-11). [answer]: true / false / null (later). */
  private fun stopAndAnswer(answer: Boolean?): Pair<Outcome, String> = runBlocking {
    simulate("--mode", "driving")
    val c = controller()
    val run = async(Dispatchers.Default) { c.startRoute("A") }
    withTimeout(10_000) { c.state.first { it.finishQuestion } }
    assertNotNull(finishQuestion(c.state.value))
    c.answerFinish(answer)
    val o = withTimeout(10_000) { run.await() }
    assertFalse(c.state.value.finishQuestion)
    assertTrue(o.ok, o.message)
    assertTrue(o.stopped)
    assertEquals(EventKind.Stopped, c.state.value.history.first().kind)
    o to links.last().report()
  }

  @Test fun aRouteButtonDuringARouteStopsItAndTheUserFinishes() {
    val (o, pressed) = stopAndAnswer(true)
    assertTrue("start_stop" in pressed && "soft1" in pressed, pressed)
    assertEquals("De robot rijdt de route verder.", o.message)
  }

  @Test fun aRouteButtonDuringARouteStopsItAndTheUserCancels() {
    val (o, pressed) = stopAndAnswer(false)
    assertTrue("start_stop" in pressed && "soft3" in pressed && "soft1" !in pressed, pressed)
    assertEquals("Route afgebroken. De tijdsplanning blijft uit.", o.message)
  }

  @Test fun laterLeavesTheQuestionOnTheRobot() {
    val (o, pressed) = stopAndAnswer(null)
    assertTrue("start_stop" in pressed && "soft1" !in pressed && "soft3" !in pressed, pressed)
    assertTrue(o.message.contains("staat nog op de robot"), o.message)
  }

  @Test fun dryRunStartsNothing() = runBlocking {
    simulate("--planning", "on")
    val c = controller()
    val o = c.startRoute("B", dryRun = true)
    assertTrue(o.ok, o.message)
    assertEquals(EventKind.DryRun, c.state.value.history.first().kind)
    assertFalse(c.state.value.planningResumes)
    assertNull(c.state.value.lastStart("B"))
  }

  @Test fun wrongPinIsAProblemWithAWayOut() = runBlocking {
    simulate()
    val c = controller(pin = "0000")
    val o = c.startRoute("A")
    assertFalse(o.ok)
    assertEquals(Problem.PinRejected, o.problem)
    assertEquals(EventKind.Failed, c.state.value.history.first().kind)
  }

  @Test fun busyRobotPressesNothing() = runBlocking {
    simulate("--mode", "question") // a screen the app does not know how to leave: press nothing
    val c = controller()
    val o = c.startRoute("A")
    assertFalse(o.ok)
    assertEquals(Problem.Busy, o.problem)
  }

  @Test fun planningOffAndOn() = runBlocking {
    simulate("--planning", "on")
    val c = controller()
    val o = c.setPlanning(false)
    assertTrue(o.ok, o.message)
    assertEquals("Tijdsplanning staat UIT", o.message)
    assertEquals(EventKind.Planning, c.state.value.history.first().kind)
  }

  @Test fun findsTheRobotWhenNoneIsKnown() = runBlocking {
    simulate()
    val store = MemoryStore()
    val c = controller(robotId = null, store = store)
    val o = c.refresh()
    assertTrue(o.ok, o.message)
    assertEquals("00:12:FF:51:4D:00", c.state.value.robot.robotId)
    assertEquals("Simulator", c.state.value.robot.robotName)
    // saved, and the logbook / settings survive a restart
    assertEquals("00:12:FF:51:4D:00", Settings.load(store).robot.robotId)
  }

  @Test fun noRobotNearby() = runBlocking {
    LelyDiscovery.linkProvider = LinkProvider({ error("no link") }, robots = emptyList(), scanDelayMs = 10)
    val c = controller(robotId = null)
    val o = c.refresh()
    assertFalse(o.ok)
    assertEquals(Problem.NotFound, o.problem)
  }

  /** Bluetooth that fails [failures] times, then works (Erik 2026-11-13: the next press did). */
  private fun flaky(failures: Int): () -> Int {
    var opened = 0
    LelyDiscovery.linkProvider = LinkProvider(
      link = { id -> if (opened++ < failures) Unreachable(id) else SimulatorLink(id = id).also { links += it } },
      robots = Simulator.robots,
      scanDelayMs = 10,
    )
    return { opened }
  }

  private class Unreachable(override val id: String) : RobotLink {
    override val notifications = emptyFlow<ByteArray>()
    override suspend fun connect() = error("connection failed")
    override suspend fun awaitClosed() {}
    override suspend fun write(frame: ByteArray) {}
    override suspend fun disconnect() {}
  }

  @Test fun bluetoothThatFailsTwiceIsTriedAgain() = runBlocking {
    val opened = flaky(2)
    val o = controller().startRoute("C")
    assertTrue(o.ok, o.message)
    assertEquals(3, opened())
  }

  @Test fun bluetoothThatKeepsFailingGivesUpAfterThreeTries() = runBlocking {
    val opened = flaky(10)
    val o = controller().startRoute("C")
    assertFalse(o.ok)
    assertEquals(Problem.NotFound, o.problem)
    assertEquals(3, opened())
  }

  @Test fun rememberedImageVersionIsSaved() = runBlocking {
    simulate()
    val store = MemoryStore()
    val c = controller(store = store)
    assertTrue(c.refresh().ok)
    assertEquals("444953303031385434", Settings.load(store).robot.extra["imageVersion"])
  }

  @Test fun holdKeepsTheLinkOpenUntilRelease() = runBlocking {
    simulate()
    val c = controller()
    assertTrue(c.hold().ok)
    assertTrue(c.state.value.connected)
    assertNotNull(c.state.value.live)
    c.release()
    assertFalse(c.state.value.connected)
  }

  @Test fun englishTexts() = runBlocking {
    useLanguage("en")
    simulate("--planning", "off")
    val c = controller()
    val o = c.startRoute("A")
    assertEquals("Route A started.", o.message)
  }

  @Test fun robotsAddSelectRemove() = runBlocking {
    val store = MemoryStore()
    val c = controller(store = store)
    val first = c.state.value.robot.uid
    c.addRobot(RobotProfile(label = "Stal 2"))
    assertEquals("Stal 2", c.state.value.robot.displayName)
    assertEquals(2, c.state.value.robots.size)
    c.selectRobot(first)
    assertEquals(first, c.state.value.robot.uid)
    c.removeRobot(first)
    assertEquals("Stal 2", c.state.value.robot.displayName)
    assertEquals(1, Settings.load(store).robots.size)
  }
}
