// Runs the real C++ core (JNI) and the Kotlin Lely API against the simulated robot from
// tests/fake_robot.py, the same simulator the C++, Python and Dart tests use.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val stored = "DIS0018T4".encodeToByteArray()

private fun drive(args: List<String> = emptyList(), pin: String = "4321", job: suspend (Robot) -> Unit = {}): JsonObject =
  runBlocking {
    val link = SimulatorLink(args = args)
    val robot = Robot(link, pin = pin, imageVersion = stored)
    try {
      robot.connect()
      job(robot)
    } finally {
      if (robot.isConnected) robot.disconnect()
    }
    Json.parseToJsonElement(link.report()).jsonObject
  }

private fun JsonObject.str(k: String) = this[k]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

class LelyTest {
  @Test fun statusFromTheHomeScreen() {
    var st = CoreStatus()
    drive { st = it.status() }
    assertEquals(true, st.planning)
    assertEquals("21:00:00", st.nextRide)
    assertEquals(13500, st.batteryMv)
    assertEquals(false, st.busy)
  }

  @Test fun wrongPin() {
    assertFailsWith<PinRejected> { drive(pin = "0000") }
  }

  @Test fun routeCWithPlanningOnAnswersJaWithProgressSteps() = runBlocking {
    val steps = Collections.synchronizedList(mutableListOf<CoreStep>())
    val link = SimulatorLink(args = listOf("--planning", "on"))
    val robot = Robot(link, pin = "4321", imageVersion = stored, onStep = { steps.add(it) })
    robot.connect()
    robot.startRoute('C')
    robot.disconnect()
    assertEquals(
      listOf(CoreStep.Linked, CoreStep.Granted, CoreStep.ScreenReady, CoreStep.PlanningOff, CoreStep.Menu,
        CoreStep.Selected, CoreStep.StartPressed, CoreStep.Answered),
      steps.toList(),
    )
    val rep = Json.parseToJsonElement(link.report()).jsonObject
    assertEquals("driving", rep.str("mode"))
    assertEquals(2, rep["sel"]!!.jsonPrimitive.int)
    assertEquals("ja", rep.str("answer"))
  }

  @Test fun routeBWithPlanningOffAnswersNeeAndDoesNotToggle() {
    val rep = drive(listOf("--planning", "off")) { it.startRoute('B') }
    assertEquals("nee", rep.str("answer"))
    assertEquals("enter", rep["pressed"]!!.jsonArray.first().jsonPrimitive.content)
  }

  @Test fun dryRunPutsEverythingBack() {
    val rep = drive(listOf("--planning", "on")) { it.startRoute('D', dryRun = true) }
    assertEquals("home", rep.str("mode"))
    assertEquals(true, rep["planning"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(null, rep.str("answer"))
  }

  @Test fun busyRobotNothingPressed() {
    val rep = drive(listOf("--mode", "driving")) { r ->
      assertTrue(r.status().driving)
      assertFailsWith<RobotBusy> { r.startRoute('A') }
    }
    assertTrue((rep["pressed"] as JsonArray).none { it.jsonPrimitive.content == "enter" })
  }

  @Test fun stopWhileDrivingNeverAnswersFinishRoute() {
    val rep = drive(listOf("--mode", "driving")) { it.stopRoute() }
    val pressed = rep["pressed"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("start_stop"), pressed)   // "route afmaken?" Ja would drive on
    assertEquals("abort", rep.str("mode"))
  }

  @Test fun englishErrorText() = runBlocking {
    val link = SimulatorLink()
    val robot = Robot(link, pin = "0000", imageVersion = stored, language = "en")
    val e = assertFailsWith<PinRejected> { robot.connect() }
    assertTrue(e.message!!.isNotBlank())
    Unit
  }

  @Test fun newImageVersionIsReported() = runBlocking {
    val link = SimulatorLink()
    val robot = Robot(link, pin = "4321", imageVersion = ByteArray(9))
    robot.connect()
    robot.disconnect()
    val rep = Json.parseToJsonElement(link.report()).jsonObject
    assertEquals(52, rep["images_sent"]!!.jsonPrimitive.int)
    assertEquals("DIS0018T4", robot.newImageVersion!!.decodeToString())
  }
}
