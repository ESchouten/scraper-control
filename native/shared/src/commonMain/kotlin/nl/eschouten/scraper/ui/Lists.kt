// The logbook and the robot switcher: lists that both apps draw as they are.
package nl.eschouten.scraper.ui

import nl.eschouten.scraper.app.ControllerState
import nl.eschouten.scraper.app.EventKind
import nl.eschouten.scraper.app.HistoryEvent
import nl.eschouten.scraper.app.RobotProfile
import nl.eschouten.scraper.texts.tr

// ---- logbook ----

data class LogRow(
  /** Route letter for the badge, or null (planning): then [glyph]. */
  val route: String?,
  /** Badge drawn muted (not a real start). */
  val muted: Boolean,
  val title: String,
  val glyph: Glyph,
  val tone: Tone,
  /** "Gestart · Stal 2 · via widget". */
  val subtitle: String,
  val time: String,
)

data class LogDay(val title: String, val rows: List<LogRow>)

data class LogbookScreen(
  val title: String,
  val clearLabel: String,
  val clearConfirm: Confirm,
  val days: List<LogDay>,
  /** Shown when there is nothing yet. */
  val emptyTitle: String,
  val emptyHelp: String,
)

fun logbookScreen(s: ControllerState, now: Long): LogbookScreen {
  // the history is newest first, so a day's events are together
  val days = s.history.groupBy { Fmt.dayTitle(it.at, now) }.map { (day, events) -> LogDay(day, events.map { logRow(s, it) }) }
  return LogbookScreen(
    tr.logbook, tr.clearLogbook,
    Confirm(Glyph.Delete, tr.clearLogbookQ, tr.clearLogbookBody, tr.clear, tr.cancel),
    days, tr.logbookEmpty, tr.logbookEmptyHelp,
  )
}

private fun logRow(s: ControllerState, e: HistoryEvent): LogRow {
  val (glyph, tone, label) = when (e.kind) {
    EventKind.Route -> Triple(Glyph.Check, Tone.Ok, tr.started)
    EventKind.DryRun -> Triple(Glyph.Science, Tone.Neutral, tr.dryRun)
    EventKind.Planning -> Triple(Glyph.Schedule, Tone.Neutral, tr.planningShort)
    EventKind.Failed -> Triple(Glyph.Error, Tone.Error, tr.failed)
    EventKind.Stopped -> Triple(Glyph.Stop, Tone.Neutral, tr.stoppedShort)
  }
  val robotName = if (s.robots.size < 2 || e.robot == null) null else s.robots.firstOrNull { it.uid == e.robot }?.displayName
  val sub = listOfNotNull(
    label, robotName,
    e.detail.takeIf { e.kind == EventKind.Failed && it.isNotEmpty() },
    tr.viaWidget.takeIf { e.viaWidget },
  ).joinToString(" · ")
  return LogRow(e.route, e.kind != EventKind.Route, e.title, glyph, tone, sub, Fmt.hhmm(e.at))
}

// ---- robot switcher ----

data class RobotRow(val uid: String, val name: String, val summary: String, val active: Boolean, val testId: String)

data class SwitcherSheet(val title: String, val robots: List<RobotRow>, val addLabel: String)

fun switcherSheet(s: ControllerState, now: Long) = SwitcherSheet(
  tr.robots,
  s.robots.map { RobotRow(it.uid, it.displayName, robotSummary(it, now), it.uid == s.robot.uid, "robot-${it.uid}") },
  tr.addRobot,
)

fun robotSummary(r: RobotProfile, now: Long): String {
  val s = r.snapshot ?: return if (r.robotId == null) tr.notConnectedYet else tr.noStatusYet
  val plan = when (s.planning) { true -> tr.planningOnLower; false -> tr.planningOffLower; null -> "" }
  return listOfNotNull(
    plan.ifEmpty { null },
    if (s.nextRide.isNotEmpty()) tr.nextRideLower(Fmt.hm(s.nextRide)) else null,
    Fmt.ago(s.at, now),
  ).joinToString(" · ")
}
