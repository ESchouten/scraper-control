// The widgets' data and a widget tap, with the demo robot.
package nl.eschouten.scraper.app

import kotlinx.coroutines.runBlocking
import nl.eschouten.scraper.model.scraperModels
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.texts.useLanguage
import nl.eschouten.scraper.ui.RunFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetBridgeTest {
  @AfterTest fun reset() {
    scraperModels.forEach { it.uninstallDemo() }
    Widgets.report = null
    useLanguage("nl")
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
