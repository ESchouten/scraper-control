// The screen models with the demo robot: what both apps show, step by step. The UI tests of
// the apps only check that they draw this; the behaviour is tested here once.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import nl.eschouten.scraper.app.*
import nl.eschouten.scraper.ui.*

import kotlinx.coroutines.runBlocking
import nl.eschouten.scraper.app.AppStart
import nl.eschouten.scraper.app.LinkState
import nl.eschouten.scraper.app.MemoryStore
import nl.eschouten.scraper.app.Problem
import nl.eschouten.scraper.texts.useLanguage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenModelTest {
  private val now = 1_800_000_000_000L

  @AfterTest fun reset() {
    Demo.uninstall()
    useLanguage("nl")
  }

  private fun demo(words: String = "") = AppStart.controller(MemoryStore(), "setup=done delay=0 scan=0 ui=nl $words")

  @Test fun freshStartShowsOnboarding() {
    val c = AppStart.controller(MemoryStore(), "delay=0 scan=0 ui=en")
    assertFalse(c.state.value.onboarded)
    val f = OnboardingFlow(c, Platform.Android)
    assertEquals("onboarding-start", f.welcome().start.testId)
    assertFalse(f.find(c.state.value).next.enabled, "next needs a robot")
    c.dispose()
  }

  @Test fun onboardingFindsAndTests() = runBlocking {
    val c = AppStart.controller(MemoryStore(), "delay=0 scan=0 ui=nl")
    val f = OnboardingFlow(c, Platform.Android)
    val found = f.scan.scan()
    assertEquals(2, found.size)
    f.pick(found.first().id)
    assertTrue(f.find(c.state.value).next.enabled)
    f.setPin("0000")
    var o = f.test()
    assertEquals(Problem.PinRejected, o.problem)
    assertEquals(false, f.pin(c.state.value).result?.ok)
    f.setPin("4321")
    o = f.test()
    assertTrue(o.ok, o.message)
    val page = f.pin(c.state.value)
    assertEquals(true, page.result?.ok)
    assertNotNull(page.done)
    f.finish()
    assertTrue(c.state.value.onboarded)
    c.dispose()
  }

  @Test fun homeBeforeAndAfterRefresh() = runBlocking {
    val c = demo("routes=ABC names=A:Voergang,B:Stal+2")
    var h = homeScreen(c.state.value, now, Platform.Android)
    assertEquals(listOf("route-A", "route-B", "route-C"), h.routes.map { it.testId })
    assertEquals(listOf("Voergang", "Stal 2", "Route C"), h.routes.map { it.title })
    assertEquals("Nog niet verbonden", h.pill.text)
    assertTrue(c.refresh().ok)
    h = homeScreen(c.state.value, now, Platform.Android)
    assertEquals(listOf("fact-planning", "fact-next", "fact-battery"), h.facts.map { it.testId })
    assertEquals("Aan", h.facts[0].value)
    assertEquals("21:00", h.facts[1].value)
    assertEquals("13,5 V", h.facts[2].value)
    assertEquals(Tone.Ok, h.facts[0].dot)
    assertNull(homeScreen(c.state.value, now, Platform.Desktop).widgetTip)
    c.dispose()
  }

  @Test fun runSheetDryRunThenForReal() = runBlocking {
    val c = demo()
    val run = RunFlow(c, "B")
    var v = run.view(c.state.value)
    assertEquals(RunPhase.Confirm, v.phase)
    assertTrue(v.canClose)
    var o = run.start(dryRun = true)
    assertTrue(o.ok, o.message)
    v = run.view(c.state.value)
    assertEquals(RunPhase.Done, v.phase)
    assertEquals(Glyph.Science, v.resultGlyph)
    assertEquals(listOf("run-for-real", "run-close"), v.actions.map { it.testId })
    o = run.start(dryRun = false)
    assertTrue(o.ok, o.message)
    v = run.view(c.state.value)
    assertEquals(Glyph.Check, v.resultGlyph)
    assertEquals(listOf("run-done"), v.actions.map { it.testId })
    assertEquals("driving", Demo.last!!.log.mode)
    c.dispose()
  }

  @Test fun runSheetWhenRobotBusy() = runBlocking {
    val c = demo("mode=manual")
    val run = RunFlow(c, "A")
    val o = run.start(dryRun = false)
    assertEquals(Problem.Busy, o.problem)
    val v = run.view(c.state.value)
    assertEquals(Tone.Warn, v.resultTone)
    assertEquals(listOf("run-close"), v.actions.map { it.testId })
    c.dispose()
  }

  @Test fun planningSwitch() = runBlocking {
    val c = demo()
    c.refresh()
    assertEquals("Tijdsplanning uitzetten?", planningConfirm(c.state.value).title)
    assertTrue(c.setPlanning(false).ok)
    assertEquals(false, c.state.value.snapshot?.planning)
    assertEquals("Tijdsplanning aanzetten?", planningConfirm(c.state.value).title)
    c.dispose()
  }

  @Test fun panelConnectPressDisconnect() = runBlocking {
    val c = demo()
    val p = PanelFlow(c)
    assertFalse(p.view(c.state.value).connected)
    assertEquals("panel-connect", p.view(c.state.value).connect?.testId)
    p.connect()
    assertEquals(LinkState.Connected, c.state.value.link)
    var v = p.view(c.state.value)
    assertTrue(v.connected)
    assertTrue(v.keysEnabled)
    assertTrue(v.lines.any { "Tijdpad" in it || "21:00" in it }, v.lines.toString())
    assertNull(p.press(RobotKey.Enter))
    v = p.view(c.state.value)
    assertTrue(v.highlighted.isNotEmpty() || v.lines.isNotEmpty())
    p.pause()
    assertFalse(c.isConnected)
    p.resume()
    assertTrue(c.isConnected)
    p.disconnect()
    assertFalse(c.isConnected)
    c.dispose()
  }

  @Test fun settingsAndLogbook() = runBlocking {
    val c = demo()
    RunFlow(c, "A").start(dryRun = true)
    val s = settingsScreen(c.state.value, now, Platform.Ios)
    assertEquals(listOf("Robots", "Activiteit", "Widgets", "Taal", "Over"), s.sections.map { it.title })
    assertEquals(listOf("", "nl", "en"), s.languages.map { it.id })
    val log = logbookScreen(c.state.value, now)
    assertEquals(1, log.days.sumOf { it.rows.size })
    assertEquals(Glyph.Science, log.days.first().rows.first().glyph)
    c.dispose()
  }

  @Test fun addRobotPicksTheSecondDemo() = runBlocking {
    val c = demo()
    val a = AddRobotFlow(c, Platform.Desktop)
    assertFalse(a.page().add.enabled)
    val found = a.scan.scan()
    assertEquals(1, found.size, "the first demo robot is already added")
    a.setLabel("Stal 2")
    a.setPin("4321")
    assertTrue(a.page().add.enabled)
    a.save()
    assertEquals(2, c.state.value.robots.size)
    assertEquals("Stal 2", c.state.value.robot.displayName)
    c.dispose()
  }
}

/** Callers without a dispatcher (Swift async, Compose UI tests) must never end up on the
 *  core's own thread: closing the session there made the core join itself and abort. */
class UnconfinedCallerTest {
  @AfterTest fun reset() = Demo.uninstall()

  @Test fun commandsFromAnUnconfinedCaller() = runBlocking {
    val c = AppStart.controller(MemoryStore(), "setup=done delay=5 scan=0 ui=en")
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Unconfined) {
      repeat(3) { assertTrue(c.refresh().ok) }
      assertTrue(RunFlow(c, "A").start(dryRun = true).ok)
      val p = PanelFlow(c)
      p.connect()
      p.press(RobotKey.Enter)
      p.disconnect()
    }
    c.dispose()
  }
}
