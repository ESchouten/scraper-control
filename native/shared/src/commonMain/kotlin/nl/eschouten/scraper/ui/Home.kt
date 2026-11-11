// Home screen: robot status at a glance and one card per route. Both apps draw exactly this.
package nl.eschouten.scraper.ui

import nl.eschouten.scraper.app.ControllerState
import nl.eschouten.scraper.app.LinkState
import nl.eschouten.scraper.model.Capability
import nl.eschouten.scraper.texts.tr

const val APP_NAME = "Scraper Control"

data class StatePill(val text: String, val dot: Tone?, val spinning: Boolean, val tappable: Boolean)

enum class FactId { Planning, NextRun, Battery }

data class Fact(val id: FactId, val label: String, val value: String, val dot: Tone?, val editable: Boolean, val testId: String)

data class RouteCard(
  val route: String,
  val title: String,
  val subtitle: String,
  val enabled: Boolean,
  /** For screen readers: "Start Voergang". */
  val startLabel: String,
  val testId: String,
)

data class ModelScreenCard(val id: String, val title: String, val subtitle: String, val testId: String)

data class HomeScreen(
  val appName: String,
  val settingsLabel: String,
  val robotName: String,
  /** For screen readers: tap the name to switch robots. */
  val switchLabel: String,
  /** "2 robots · tik op de naam om te wisselen", or null with one robot. */
  val robotCount: String?,
  val pill: StatePill,
  val facts: List<Fact>,
  /** null when the type has no routes. */
  val routesTitle: String?,
  val editLabel: String,
  val routes: List<RouteCard>,
  /** Shown instead of the cards when every route is hidden. */
  val allHidden: String?,
  val modelTitle: String?,
  val modelScreens: List<ModelScreenCard>,
  /** "Put the routes on your home screen ..." until dismissed (phones only). */
  val widgetTip: String?,
  val closeLabel: String,
)

fun homeScreen(s: ControllerState, now: Long, platform: Platform): HomeScreen {
  val m = s.model
  val snap = s.snapshot
  val pill = when (s.link) {
    LinkState.Searching -> StatePill(tr.searchRobot + "...", null, true, false)
    LinkState.Connecting -> StatePill(tr.connecting + "...", null, true, false)
    LinkState.Working -> StatePill(tr.busy + "...", null, true, false)
    LinkState.Connected -> StatePill(tr.connected, Tone.Ok, false, true)
    LinkState.Idle -> when {
      snap == null -> StatePill(tr.notConnectedYet, Tone.Idle, false, true)
      snap.busy -> StatePill(tr.wasBusy(Fmt.ago(snap.at, now)), Tone.Warn, false, true)
      else -> StatePill(tr.updated(Fmt.ago(snap.at, now)), Tone.Neutral, false, true)
    }
  }
  val facts = buildList {
    if (m.can(Capability.Planning)) add(
      Fact(
        FactId.Planning, tr.planning,
        when (snap?.planning) { true -> tr.on; false -> if (s.planningResumes) tr.paused else tr.off; null -> "–" },
        when (snap?.planning) { true -> Tone.Ok; false -> if (s.planningResumes) Tone.Warn else Tone.Idle; null -> null },
        editable = snap?.planning != null && !s.isBusy,
        testId = "fact-planning",
      ),
    )
    if (m.can(Capability.NextRun)) add(
      Fact(FactId.NextRun, tr.nextRide, if (snap == null || snap.nextRide.isEmpty()) "–" else Fmt.hm(snap.nextRide), null, false, "fact-next"),
    )
    if (m.can(Capability.Battery)) add(
      Fact(FactId.Battery, tr.battery, snap?.batteryMv?.let { Fmt.volts(it) } ?: "–", null, false, "fact-battery"),
    )
  }
  val routes = if (m.can(Capability.Routes)) s.robot.visibleRoutes else emptyList()
  return HomeScreen(
    appName = APP_NAME,
    settingsLabel = tr.settings,
    robotName = s.robot.displayName,
    switchLabel = tr.otherRobot,
    robotCount = if (s.robots.size > 1) tr.robotCount(s.robots.size) else null,
    pill = pill,
    facts = facts,
    routesTitle = if (m.can(Capability.Routes)) tr.manualRoutes else null,
    editLabel = tr.edit,
    routes = routes.map { r ->
      val last = s.lastStart(r)
      RouteCard(
        route = r,
        title = s.robot.routeTitle(r),
        subtitle = if (last == null) tr.notStartedYet else tr.lastStarted(Fmt.whenText(last.at, now)),
        enabled = !s.isBusy,
        startLabel = tr.startRoute(s.robot.routeTitle(r)),
        testId = "route-$r",
      )
    },
    allHidden = if (m.can(Capability.Routes) && routes.isEmpty()) tr.allRoutesHidden else null,
    modelTitle = if (m.screens.isNotEmpty()) m.fullName else null,
    modelScreens = m.screens.map { ModelScreenCard(it.id, it.title, it.subtitle, "screen-${it.id}") },
    widgetTip = when {
      s.widgetTipSeen || platform == Platform.Desktop -> null
      platform == Platform.Ios -> tr.widgetTipIos(APP_NAME)
      else -> tr.widgetTipAndroid(APP_NAME)
    },
    closeLabel = tr.close,
  )
}

/** After a stop, while the robot asks "route afmaken?": the app asks the user (Erik
 *  2026-11-11) and passes the answer on (RobotController.answerFinish). Null otherwise. */
fun finishQuestion(s: ControllerState): AnswerQuestion? =
  if (!s.finishQuestion) null
  else AnswerQuestion(Glyph.Stop, tr.finishRouteQ, tr.finishRouteBody, tr.finishRoute, tr.dontFinishRoute, tr.later)

/** Asked before the planning is switched from the home screen. */
fun planningConfirm(s: ControllerState): Confirm {
  val on = s.snapshot?.planning ?: false
  return Confirm(
    Glyph.Schedule,
    if (on) tr.planningOffQ else tr.planningOnQ,
    if (on) tr.planningOffBody else tr.planningOnBody,
    if (on) tr.switchOff else tr.switchOn,
    tr.cancel,
  )
}
