// The in-app fake robot must behave like tests/fake_robot.py for the core: same flows.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val stored = "DIS0018T4".encodeToByteArray()

class FakeRobotTest {
  private fun run(o: FakeRobotOptions = FakeRobotOptions(), pin: String = "4321", version: ByteArray = stored, job: suspend (Robot) -> Unit = {}): FakeRobotLink = runBlocking {
    val link = FakeRobotLink(o = o)
    val r = Robot(link, pin = pin, imageVersion = version)
    try {
      r.connect()
      job(r)
    } finally {
      if (r.isConnected) r.disconnect()
    }
    link
  }

  @Test fun status() {
    run { r ->
      val s = r.status()
      assertEquals(true, s.planning)
      assertEquals("21:00:00", s.nextRide)
      assertEquals(13500, s.batteryMv)
      assertEquals("nl", s.language)
    }
  }

  @Test fun wrongPin() {
    assertFailsWith<PinRejected> { run(pin = "0000") }
  }

  @Test fun routeCWithPlanningOn() {
    val steps = mutableListOf<CoreStep>()
    val link = runBlocking {
      val l = FakeRobotLink()
      val r = Robot(l, pin = "4321", imageVersion = stored, onStep = { synchronized(steps) { steps += it } })
      r.connect(); r.startRoute('C'); r.disconnect()
      l
    }
    assertEquals("driving", link.log.mode)
    assertEquals(2, link.log.sel)
    assertEquals("ja", link.log.answer)
    assertEquals(CoreStep.Answered, steps.last())
  }

  @Test fun routeBPlanningOffAnswersNee() {
    val l = run(FakeRobotOptions(planning = false)) { it.startRoute('B') }
    assertEquals("nee", l.log.answer)
    assertEquals("enter", l.log.pressed.first())
  }

  @Test fun dryRunPutsBack() {
    val l = run { it.startRoute('D', dryRun = true) }
    assertEquals("home", l.log.mode)
    assertTrue(l.log.planning)
  }

  @Test fun safetyStop() {
    val l = run(FakeRobotOptions(mode = "driving")) { r ->
      assertTrue(r.status().driving)
      r.stopRoute()
    }
    assertEquals(listOf("start_stop"), l.log.pressed)   // never Ja on "route afmaken?": that drives on
    assertTrue(l.log.stopped)
    assertEquals("abort", l.log.mode)
  }

  @Test fun manualMenuPressesNothing() {
    val l = run(FakeRobotOptions(mode = "manual")) { r -> assertFailsWith<RobotBusy> { r.startRoute('A') } }
    assertTrue("enter" !in l.log.pressed)
  }

  @Test fun holdDrivesUntilReleased() {
    val l = run(FakeRobotOptions(mode = "manual")) { r ->
      val up = CompletableDeferred<Unit>()
      coroutineScope {
        launch { delay(400); up.complete(Unit) }
        r.hold(RobotKey.StartStop, up)
      }
    }
    val d = l.log.drive
    assertEquals(0, d.first())
    assertEquals(7, d.last())
    assertTrue(d.count { it == 1 } >= 2, d.toString()) // repeats while held, as the robot's own panel
  }

  @Test fun manualStateFromTheImages() {
    run(FakeRobotOptions(mode = "manual")) { r ->
      assertEquals(ManualState(left = false, right = false, forward = true), r.status().manual)
      assertEquals("", r.status().highlighted) // the motor boxes are no menu selection
      r.press(RobotKey.Soft1)
      assertEquals(ManualState(left = true, right = false, forward = true), r.status().manual)
    }
    run { r -> assertEquals(null, r.status().manual) }
  }

  @Test fun quickTapStillHoldsBriefly() {
    val l = run(FakeRobotOptions(mode = "manual")) { r -> r.hold(RobotKey.StartStop, CompletableDeferred(Unit)) }
    assertEquals(listOf(0, 7), listOf(l.log.drive.first(), l.log.drive.last()))
  }

  @Test fun englishRobot() {
    run(FakeRobotOptions(lang = "en")) { r ->
      assertEquals("en", r.status().language)
      r.startRoute('A')
    }
  }

  @Test fun owedPlanningIsPaidBackAtConnect() {
    val seen = mutableListOf<Int>()
    val link = runBlocking {
      val l = FakeRobotLink(o = FakeRobotOptions(planning = false, mode = "menu"))
      val r = Robot(l, pin = "4321", imageVersion = stored, owedPlanning = 1, onOwed = { synchronized(seen) { seen += it } })
      r.connect()
      assertEquals(-1, r.owedPlanning)
      r.disconnect()
      l
    }
    assertEquals("home", link.log.mode)
    assertTrue(link.log.planning)
    assertEquals(listOf(-1), seen)
  }

  @Test fun panelKeysOweUntilFinish() {
    val seen = mutableListOf<Int>()
    runBlocking {
      val r = Robot(FakeRobotLink(), pin = "4321", imageVersion = stored, onOwed = { synchronized(seen) { seen += it } })
      r.connect()
      r.press(RobotKey.StartStop) // planning off by hand on the panel
      assertEquals(1, r.owedPlanning)
      r.disconnect() // finish() puts it back
    }
    assertEquals(listOf(1, -1), seen)
  }

  @Test fun imageDownload() {
    val l = run(version = ByteArray(9))
    assertEquals(52, l.log.imagesSent)
  }
}
