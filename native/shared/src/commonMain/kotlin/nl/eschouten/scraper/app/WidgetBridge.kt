// The home-screen widgets (Android AppWidget, iOS WidgetKit) on the shared module: what they
// show and what a tap does. The platform only draws [WidgetBar] / [WidgetTile] and calls [tap].
//
//   bar   routes A-D of one robot, plus its schedule (as last seen by the app) or last result
//   tile  one route of one robot, plus its last result
//
// Kept in the app's store (iOS: the App Group, so the widget extension reads it too):
//   w.robots          JSON [{uid, name, routes: [{l: "A", t: "Voergang"}], planning, next, seen}]
//   w.active          uid of the robot chosen in the app (default for new widgets)
//   w.status.<uid>    last result line for that robot ("07:18  Voergang gestart")
//   w.route.<uid>.<A> last result for that route, short ("Gestart 07:18")
//   w.robot.<id>, w.route.<id>   which robot / route a placed widget shows (Android)
package nl.eschouten.scraper.app

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Instant
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.ui.Fmt
import nl.eschouten.scraper.ui.APP_NAME

@Serializable data class WidgetRoute(val l: String, val t: String)

/** [planning] / [next]: the schedule as the app last saw it (null / "" = not known), [seen]
 *  when (epoch ms; 0 = not known). */
@Serializable data class WidgetRobot(
  val uid: String,
  val name: String,
  val routes: List<WidgetRoute>,
  val planning: Boolean? = null,
  val next: String = "",
  val seen: Long = 0,
) {
  /** Route [l]'s name, or "Route A". */
  fun title(l: String) = routes.firstOrNull { it.l == l }?.t ?: tr.routeLetter(l)
}

data class WidgetButton(val route: String, val title: String)

/** [status]: the schedule when known, else the last result. [scheduleOn]: null when the
 *  status line is not the schedule (no dot shown). [changesAt]: when [status] changes by itself
 *  (the next run has passed: its time is dropped), epoch ms; null = not by itself. */
data class WidgetBar(
  val title: String,
  val robotUid: String?,
  val routes: List<WidgetButton>,
  val status: String,
  val scheduleOn: Boolean? = null,
  val changesAt: Long? = null,
)

data class WidgetTile(val robotUid: String?, val route: String, val title: String, val detail: String)

/** A notification after a widget start that did not work. */
data class WidgetNotice(val title: String, val body: String)

/** A choice when a widget is placed: [route] null for the bar. */
data class WidgetChoice(val label: String, val robotUid: String, val route: String?)

object WidgetBridge {
  /** Keep the widgets in step with the app: robots, names, routes and run results. [refresh]
   *  redraws the platform's widgets. */
  fun install(controller: RobotController, store: Store, refresh: () -> Unit) {
    controller.onPublish = { s ->
      publish(s, store)
      refresh()
    }
    Widgets.report = { r ->
      report(store, r.robotUid, r.route, r.line, r.short)
      refresh()
    }
    publish(controller.settings, store)
    refresh()
  }

  fun publish(s: Settings, store: Store) {
    val list = s.robots.map { r ->
      WidgetRobot(
        r.uid, r.displayName, r.visibleRoutes.map { WidgetRoute(it, r.routeTitle(it)) },
        r.snapshot?.planning, r.snapshot?.nextRide.orEmpty(), r.snapshot?.at ?: 0,
      )
    }
    store.putString("w.robots", json.encodeToString(list))
    store.putString("w.active", s.robot.uid)
  }

  fun robots(store: Store): List<WidgetRobot> =
    store.getString("w.robots")?.let { runCatching { json.decodeFromString<List<WidgetRobot>>(it) }.getOrNull() } ?: emptyList()

  /** The robot a widget was set up for, else the one active in the app. */
  fun robot(store: Store, uid: String?): WidgetRobot? {
    val all = robots(store)
    val want = uid ?: store.getString("w.active")
    return all.firstOrNull { it.uid == want } ?: all.firstOrNull()
  }

  fun report(store: Store, robotUid: String, route: String, line: String, short: String, now: Long = Clock.System.now().toEpochMilliseconds()) {
    val t = Fmt.hhmm(now)
    store.putString("w.status.$robotUid", "$t  $line")
    store.putString("w.route.$robotUid.$route", if (short.isEmpty()) "" else "$short $t")
  }

  fun bar(store: Store, robotUid: String?): WidgetBar = bar(store, robotUid, Clock.System.now().toEpochMilliseconds())

  /** The bar at [now]: once the next run has passed, the schedule shows without its time
   *  (Erik 2026-11-12). */
  fun bar(store: Store, robotUid: String?, now: Long): WidgetBar {
    val r = robot(store, robotUid)
    val planning = r?.planning
    val runAt = if (planning == true) nextRunAt(r.seen, r.next) else null
    val next = if (runAt != null && now >= runAt) "" else r?.next.orEmpty()
    return WidgetBar(
      r?.name ?: APP_NAME,
      r?.uid,
      r?.routes?.map { WidgetButton(it.l, it.t) } ?: Settings.allRoutes.map { WidgetButton(it, tr.routeLetter(it)) },
      if (planning != null) tr.widgetSchedule(planning, next)
      else r?.let { store.getString("w.status.${it.uid}") } ?: tr.tapRouteToStart,
      planning,
      runAt?.takeIf { now < it },
    )
  }

  /** When the next run ("21:00") seen at [seen] takes place: that day if not before the time
   *  it was seen, else the day after (local time). Null when either is not known. */
  fun nextRunAt(seen: Long, next: String): Long? {
    if (seen <= 0) return null
    val p = Fmt.hm(next).split(':')
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    val zone = Fmt.zone
    val at = Instant.fromEpochMilliseconds(seen).toLocalDateTime(zone)
    val time = LocalTime(h, m)
    val day = if (time >= at.time) at.date else at.date.plus(1, DateTimeUnit.DAY)
    return LocalDateTime(day, time).toInstant(zone).toEpochMilliseconds()
  }

  fun tile(store: Store, robotUid: String?, route: String?): WidgetTile {
    val r = robot(store, robotUid)
    val l = route ?: r?.routes?.firstOrNull()?.l ?: "A"
    val last = r?.let { store.getString("w.route.${it.uid}.$l") }.orEmpty()
    return WidgetTile(
      r?.uid, l,
      r?.title(l) ?: tr.routeLetter(l),
      when {
        last.isNotEmpty() -> last
        robots(store).size > 1 && r != null -> r.name
        else -> tr.tapToStart
      },
    )
  }

  /** The notification for a widget tap that did not start the route: [out] null = it took
   *  too long. Null when it started, or when the tap stopped a running route on purpose. */
  fun notice(store: Store, robotUid: String?, route: String, out: Outcome?): WidgetNotice? {
    if (out != null && (out.ok || out.stopped || out.message.isEmpty())) return null
    val letter = route.uppercase()
    val title = robot(store, robotUid)?.title(letter) ?: tr.routeLetter(letter)
    return WidgetNotice(tr.widgetFailedTitle(title), out?.message ?: tr.widgetTimedOut)
  }

  /** What can be chosen when a widget is placed (Android); empty: set up the app first. */
  fun choices(store: Store, tile: Boolean): List<WidgetChoice> {
    val all = robots(store)
    return if (tile) {
      all.flatMap { r -> r.routes.map { rt -> WidgetChoice(if (all.size > 1) "${r.name} · ${rt.t}" else rt.t, r.uid, rt.l) } }
    } else {
      all.map { WidgetChoice(it.name, it.uid, null) }
    }
  }

  /** A widget tap: connect, start [route] (all safety rules of the app), disconnect. Shows
   *  "connecting" first and the result after, through [refresh]. Uses the robot [robotUid]
   *  for this run only (the app's choice stays). */
  suspend fun tap(store: Store, robotUid: String?, route: String, refresh: () -> Unit): Outcome {
    val letter = route.uppercase()
    if (letter !in Settings.allRoutes) return Outcome(false, "")
    val s = Settings.load(store)
    if (robotUid != null && !s.useOnly(robotUid)) return Outcome(false, "") // removed in the app
    val uid = s.robot.uid
    val title = s.robot.routeTitle(letter)
    report(store, uid, letter, tr.widgetConnecting(title), tr.busyDots)
    refresh()
    val c = RobotController(s, store, viaWidget = true)
    val out = try {
      c.startRoute(letter)
    } finally {
      c.dispose()
    }
    report(
      store, uid, letter,
      when {
        out.stopped -> tr.routeStoppedTitle
        out.ok -> tr.routeStarted(title)
        else -> "$title: ${out.message}"
      },
      when {
        out.stopped -> tr.stoppedShort
        out.ok -> tr.started
        out.problem == Problem.BluetoothOff -> tr.bluetoothOffShort
        else -> tr.failed
      },
    )
    publish(Settings.load(store), store)   // the schedule the run saw (the tap's controller does not publish)
    refresh()
    return out
  }
}
