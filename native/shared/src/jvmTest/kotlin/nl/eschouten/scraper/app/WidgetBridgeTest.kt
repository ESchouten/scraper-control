// The widgets' data and a widget tap, with the demo robot.
package nl.eschouten.scraper.app

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import nl.eschouten.scraper.lely.LelyDiscovery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import nl.eschouten.scraper.lely.Simulator
import nl.eschouten.scraper.lely.SimulatorLink
import nl.eschouten.scraper.lely.LinkProvider
import nl.eschouten.scraper.model.scraperModels
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.texts.useLanguage
import nl.eschouten.scraper.ui.Fmt
import nl.eschouten.scraper.ui.RunFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetBridgeTest {
  @AfterTest fun reset() {
    scraperModels.forEach { it.uninstallDemo() }
    Widgets.report = null
    LelyDiscovery.linkProvider = null
    useLanguage("nl")
  }

  /** A store as the widgets see it, with the simulated robot ([args]) set up in the app. */
  private val links = mutableListOf<SimulatorLink>()

  private fun simulated(vararg args: String): MemoryStore {
    LelyDiscovery.linkProvider = LinkProvider(
      link = { id -> SimulatorLink(args = args.toList(), id = id).also { links += it } },
      robots = Simulator.robots,
      scanDelayMs = 10,
    )
    val store = MemoryStore()
    val s = Settings.single(robotId = "00:12:FF:51:4D:00", pin = "4321")
    s.save(store)
    WidgetBridge.publish(s, store)
    return store
  }

  @Test fun scheduleSwitch() = runBlocking {
    useLanguage("en")
    val store = simulated("--planning", "on")
    val uid = WidgetBridge.robot(store, null)!!.uid
    assertEquals(null, WidgetBridge.bar(store, uid).scheduleOn)   // not known yet: no switch
    var lines = listOf<String>()
    assertTrue(WidgetBridge.setPlanning(store, uid, false) { lines += WidgetBridge.bar(store, uid).status }.ok)
    assertTrue(lines.first().endsWith("Schedule off: connecting..."), lines.first())   // while it runs
    val bar = WidgetBridge.bar(store, uid)
    assertEquals(false, bar.scheduleOn)   // what the robot showed after
    assertEquals("Schedule off", bar.status)
    assertEquals(null, store.getString("w.busy.$uid"))
    assertTrue(WidgetBridge.setPlanning(store, uid, true) {}.ok)
    assertEquals(true, WidgetBridge.bar(store, uid).scheduleOn)
    val h = History.load(store).first()
    assertEquals(EventKind.Planning, h.kind)
    assertTrue(h.viaWidget)
    // a second tap while one runs does nothing
    store.putString("w.busy.$uid", "${kotlin.time.Clock.System.now().toEpochMilliseconds()}")
    store.putString("w.status.$uid", "08:00  Schedule on: connecting...")
    assertEquals("08:00  Schedule on: connecting...", WidgetBridge.bar(store, uid).status)
    val o = WidgetBridge.setPlanning(store, uid, false) {}
    assertFalse(o.ok)
    assertEquals(null, WidgetBridge.planningNotice(o))
    assertEquals(true, WidgetBridge.bar(store, uid).scheduleOn)
  }

  /** Driving, or the robot's "finish the route?" question on screen: nothing pressed, never answered. */
  @Test fun scheduleSwitchOnABusyRobotPressesNothing() = runBlocking {
    useLanguage("en")
    for (mode in listOf("driving", "question")) {
      val store = simulated("--mode", mode)
      val o = WidgetBridge.setPlanning(store, null, false) {}
      assertFalse(o.ok, mode)
      assertEquals(Problem.Busy, o.problem, mode)
      assertEquals(WidgetNotice(tr.planningChangeFailed, o.message), WidgetBridge.planningNotice(o))
      assertEquals(null, store.getString("w.busy.${WidgetBridge.robot(store, null)!!.uid}"))
      val rep = links.last().report()
      assertTrue(Json.parseToJsonElement(rep).jsonObject["pressed"]!!.jsonArray.isEmpty(), rep)
    }
  }

  @Test fun publishTapAndReport() = runBlocking {
    val c = AppStart.controller(MemoryStore(), "setup=done delay=0 scan=0 ui=en routes=ABC names=A:Feed+alley")
    val store = MemoryStore()
    var refreshed = 0
    WidgetBridge.install(c, store) { refreshed++ }
    var bar = WidgetBridge.bar(store, null)
    assertEquals("Demo", bar.title)
    assertEquals(listOf("A", "B", "C"), bar.routes.map { it.route })
    assertEquals("Feed alley", bar.routes[0].title)
    assertEquals(tr.tapRouteToStart, bar.status)
    assertEquals(tr.tapToStart, WidgetBridge.tile(store, null, "B").detail)

    // a run in the app shows on the widget
    RunFlow(c, "A").start(dryRun = false)
    assertTrue(WidgetBridge.tile(store, null, "A").detail.startsWith(tr.started), WidgetBridge.tile(store, null, "A").detail)

    // a widget tap runs the route on its own connection; settings come from the widget's store
    c.settings.save(store)
    val uid = c.state.value.robot.uid
    val o = WidgetBridge.tap(store, uid, "b") { refreshed++ }
    assertTrue(o.ok, o.message)
    bar = WidgetBridge.bar(store, uid)
    val on = bar.scheduleOn
    if (on != null) assertEquals(tr.widgetSchedule(on, WidgetBridge.robot(store, uid)!!.next), bar.status)   // the schedule, once seen
    else assertTrue(bar.status.endsWith(tr.routeStarted("Route B")), bar.status)
    assertTrue(store.getString("w.status.$uid")!!.endsWith(tr.routeStarted("Route B")))
    // the schedule the tap saw is what the widget shows (the tap's own controller does not publish)
    val snap = Settings.load(store).robot.snapshot
    val shown = WidgetBridge.robot(store, uid)!!
    assertEquals(snap?.planning, shown.planning)
    assertEquals(snap?.nextRide.orEmpty(), shown.next)
    assertEquals(snap?.at ?: 0L, shown.seen)
    assertTrue(refreshed >= 4)
    // the logbook says it came from the widget
    val h = History.load(store)
    assertTrue(h.first().viaWidget)
    assertEquals(1, WidgetBridge.choices(store, tile = false).size)
    assertEquals(3, WidgetBridge.choices(store, tile = true).size)
    c.dispose()
  }

  @Test fun barShowsTheScheduleWhenKnown() {
    useLanguage("en")
    val store = MemoryStore()
    store.putString("w.robots", """[{"uid":"r1","name":"Stal","routes":[{"l":"A","t":"Voergang"}]}]""")
    store.putString("w.status.r1", "08:12  Voergang started")
    assertEquals("08:12  Voergang started", WidgetBridge.bar(store, "r1").status)
    assertEquals(null, WidgetBridge.bar(store, "r1").scheduleOn)
    store.putString("w.robots", """[{"uid":"r1","name":"Stal","routes":[{"l":"A","t":"Voergang"}],"planning":true,"next":"21:00"}]""")
    assertEquals("Schedule on · next 21:00", WidgetBridge.bar(store, "r1").status)
    assertEquals(true, WidgetBridge.bar(store, "r1").scheduleOn)
    store.putString("w.robots", """[{"uid":"r1","name":"Stal","routes":[{"l":"A","t":"Voergang"}],"planning":false}]""")
    assertEquals("Schedule off", WidgetBridge.bar(store, "r1").status)
  }

  @Test fun nextRunTimeIsDroppedOnceItHasPassed() {
    useLanguage("en")
    val zone = Fmt.zone
    Fmt.zone = TimeZone.UTC
    try {
      val seen = 1_794_471_120_000L   // 2026-11-12 08:12 UTC
      val nine = seen + (12 * 60 + 48) * 60_000L   // 21:00 the same day
      val store = MemoryStore()
      store.putString("w.robots", """[{"uid":"r1","name":"Stal","routes":[{"l":"A","t":"Voergang"}],"planning":true,"next":"21:00","seen":$seen}]""")
      var bar = WidgetBridge.bar(store, "r1", nine - 60_000)
      assertEquals("Schedule on · next 21:00", bar.status)
      assertEquals(nine, bar.changesAt)   // the widgets redraw then
      bar = WidgetBridge.bar(store, "r1", nine)
      assertEquals("Schedule on", bar.status)
      assertEquals(true, bar.scheduleOn)
      assertEquals(null, bar.changesAt)
      // a time earlier in the day than when it was seen is the next day's run
      assertEquals(seen + (22 * 60 + 48) * 60_000L, WidgetBridge.nextRunAt(seen, "07:00"))
      assertEquals(nine, WidgetBridge.nextRunAt(seen, "21:00:00"))
      assertEquals(null, WidgetBridge.nextRunAt(0, "21:00"))
      assertEquals(null, WidgetBridge.nextRunAt(seen, ""))
      // schedule off stays off; not known when seen (older data): the time stays
      store.putString("w.robots", """[{"uid":"r1","name":"Stal","routes":[],"planning":false,"next":"21:00","seen":$seen}]""")
      assertEquals("Schedule off", WidgetBridge.bar(store, "r1", nine + 1).status)
      store.putString("w.robots", """[{"uid":"r1","name":"Stal","routes":[],"planning":true,"next":"21:00"}]""")
      assertEquals("Schedule on · next 21:00", WidgetBridge.bar(store, "r1", nine + 1).status)
    } finally {
      Fmt.zone = zone
    }
  }

  @Test fun noticeOnlyWhenAStartFailed() {
    val store = MemoryStore()
    store.putString("w.robots", """[{"uid":"r1","name":"Stal","routes":[{"l":"A","t":"Voergang"}]}]""")
    assertEquals(null, WidgetBridge.notice(store, "r1", "a", Outcome(true, "")))
    assertEquals(null, WidgetBridge.notice(store, "r1", "a", Outcome(false, "x", stopped = true)))
    assertEquals(null, WidgetBridge.notice(store, "r1", "a", Outcome(false, ""))) // robot removed in the app
    assertEquals(WidgetNotice(tr.widgetFailedTitle("Voergang"), tr.noConnection), WidgetBridge.notice(store, "r1", "a", Outcome(false, tr.noConnection)))
    assertEquals(WidgetNotice(tr.widgetFailedTitle("Route B"), tr.widgetTimedOut), WidgetBridge.notice(store, "r1", "B", null))
  }
}
