// Everything an app does with a robot goes through one RobotController, so route buttons,
// a type's own screens and the home-screen widget never open two connections. It only knows the generic model (model/Scraper.kt); the
// robot logic itself is in the type's implementation.
//
// Screens read [state] (a StateFlow; Swift: watch {}) and call the suspend functions.
package nl.eschouten.scraper.app

import nl.eschouten.scraper.ui.newScope
import nl.eschouten.scraper.ui.watchIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import nl.eschouten.scraper.model.Capability
import nl.eschouten.scraper.model.RunStep
import nl.eschouten.scraper.model.ScraperException
import nl.eschouten.scraper.model.ScraperFault
import nl.eschouten.scraper.model.ScraperModel
import nl.eschouten.scraper.model.ScraperSession
import nl.eschouten.scraper.model.ScraperStatus
import nl.eschouten.scraper.texts.tr
import kotlin.concurrent.Volatile
import kotlin.time.Clock

enum class LinkState { Idle, Searching, Connecting, Connected, Working }

/** What went wrong, so the UI can offer the right way out. */
enum class Problem { None, BluetoothOff, NotFound, PinRejected, Busy, NoAnswer, Failed }

data class Outcome(
  val ok: Boolean,
  val message: String,
  val problem: Problem = Problem.None,
  /** A route button stopped a running route (safety stop). */
  val stopped: Boolean = false,
)

/** Everything a screen needs, as one immutable value. */
data class ControllerState(
  val link: LinkState = LinkState.Idle,
  /** Planning was switched off for a route and comes back on after it ("Pauze"). */
  val planningResumes: Boolean = false,
  /** Newest first; also holds runs from the home-screen widget. */
  val history: List<HistoryEvent> = emptyList(),
  val steps: List<RunStep> = emptyList(),
  val last: Outcome? = null,
  val robots: List<RobotProfile> = emptyList(),
  val robot: RobotProfile = RobotProfile(),
  /** Live status while connected, else null. */
  val live: ScraperStatus? = null,
  val connected: Boolean = false,
  val onboarded: Boolean = false,
  val widgetTipSeen: Boolean = false,
  val language: String = "",
  /** A stopped route asks "finish it?": show [nl.eschouten.scraper.ui.finishQuestion] and
   *  call [RobotController.answerFinish]. The link stays open meanwhile. */
  val finishQuestion: Boolean = false,
  /** Bumped on every change, so even equal-looking states redraw (live panel). */
  val revision: Long = 0,
) {
  val isBusy get() = link == LinkState.Searching || link == LinkState.Connecting || link == LinkState.Working
  val model: ScraperModel get() = robot.type
  val snapshot: RobotSnapshot? get() = robot.snapshot

  /** Last successful start of [route] (not a dry run), for "Laatst gestart ...". */
  fun lastStart(route: String): HistoryEvent? {
    val legacy = robots.firstOrNull()?.uid == robot.uid
    return history.firstOrNull { e ->
      e.route == route && e.kind == EventKind.Route && (e.robot == robot.uid || (e.robot == null && legacy))
    }
  }
}

fun interface Cancellable {
  fun cancel()
}

class RobotController(
  val settings: Settings,
  private val store: Store,
  val viaWidget: Boolean = false,
  private val scope: CoroutineScope = newScope(),
  /** Keep the home-screen widgets in step (robots, names, visible routes). Platform sets it. */
  var onPublish: ((Settings) -> Unit)? = null,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  private val mutex = Mutex()
  private var robot: ScraperSession? = null
  private var holds = 0
  private var poll: Job? = null
  private var seen: Any? = null
  private var link = LinkState.Idle
  private var planningResumes = false
  private var history: List<HistoryEvent> = History.load(store)
  @Volatile private var steps: List<RunStep> = emptyList()
  private var last: Outcome? = null
  @Volatile private var revision = 0L
  @Volatile private var finishAnswer: CompletableDeferred<Boolean?>? = null

  private val _state = MutableStateFlow(build())
  val state: StateFlow<ControllerState> = _state

  /** Swift: observe the state without Flow. Called at once with the current state. */
  fun watch(onChange: (ControllerState) -> Unit): Cancellable = _state.watchIn(scope, onChange)

  val isConnected get() = robot?.isConnected ?: false

  /** The open connection, for a type's own screens (null when not connected). */
  val session: ScraperSession? get() = if (isConnected) robot else null

  // ---- robots ----

  /** Switch to another robot (closes the connection to the current one). */
  suspend fun selectRobot(uid: String) = mutex.withLock {
    if (uid == settings.robot.uid) return@withLock
    holds = 0
    close()
    settings.select(uid)
    planningResumes = false
    steps = emptyList()
    last = null
    save()
    publish()
    set(LinkState.Idle)
  }

  suspend fun addRobot(p: RobotProfile) = mutex.withLock {
    holds = 0
    close()
    settings.add(p)
    planningResumes = false
    save()
    publish()
    set(LinkState.Idle)
  }

  suspend fun removeRobot(uid: String) = mutex.withLock {
    if (uid == settings.robot.uid) {
      holds = 0
      close()
    }
    settings.remove(uid)
    save()
    publish()
    set(LinkState.Idle)
  }

  /** Change a robot's profile (names, routes, PIN, ...) and save. */
  suspend fun updateRobot(p: RobotProfile) = mutex.withLock {
    settings.update(p)
    save()
    publish()
    emit()
  }

  /** Change app flags (onboarded, widget tip, language) and save. */
  suspend fun updateSettings(change: Settings.() -> Unit) = mutex.withLock {
    settings.change()
    nl.eschouten.scraper.texts.useLanguage(settings.language)
    save()
    publish()
    emit()
  }

  /** Reads history and robot snapshots again (the widget may have written them). */
  fun reload() {
    history = History.load(store)
    val fresh = Settings.load(store)
    for (r in settings.robots) {
      val f = fresh.byUid(r.uid)?.snapshot ?: continue
      if (r.snapshot == null || f.at > r.snapshot.at) settings.update(r.copy(snapshot = f))
    }
    emit()
  }

  fun clearHistory() {
    History.clear(store)
    history = emptyList()
    emit()
  }

  // ---- commands ----

  /** [route] "A".."D". One tap: connect, start, disconnect (unless the panel holds the link).
   *  A safety stop when a route already runs (Erik 2026-11-05): a route button pressed during
   *  a route stops it instead (not for a dry run). */
  suspend fun startRoute(route: String, dryRun: Boolean = false): Outcome {
    var stopped = false
    return job(
      work = {
        val r = robot!!
        if (!dryRun && r.status().driving) {
          // A type that cannot stop a route: press nothing, say it is busy.
          if (!settings.robot.type.can(Capability.Stop)) throw ScraperException(ScraperFault.Busy, tr.robotBusyNothingPressed)
          r.stopRoute()
          stopped = true
          planningResumes = false
          return@job askFinish(r)
        }
        if (!dryRun && r.status().finishQuestion) {   // stopped earlier, question still open
          stopped = true
          return@job askFinish(r)
        }
        val wasOn = r.status().planning == true
        r.startRoute(route, dryRun)
        val title = settings.robot.routeTitle(route)
        if (dryRun) return@job Outcome(true, tr.dryRunDone(title))
        planningResumes = wasOn
        Outcome(true, if (wasOn) tr.routeStartedResumes(title) else tr.routeStartedDot(title))
      },
      record = { o ->
        HistoryEvent(
          at = now(),
          kind = when {
            !o.ok -> EventKind.Failed
            stopped -> EventKind.Stopped
            dryRun -> EventKind.DryRun
            else -> EventKind.Route
          },
          title = if (stopped) tr.routeStoppedTitle else settings.robot.routeTitle(route),
          detail = if (o.ok) "" else o.message,
          viaWidget = viaWidget,
          route = route,
          robot = settings.robot.uid,
        )
      },
    )
  }

  /** The robot asks whether to finish the stopped route: the user decides (Erik 2026-11-11),
   *  the answer goes to the robot 1:1. No answer within 2 minutes (or from the widget, which
   *  cannot ask): the question stays on the robot and nothing is pressed. */
  private suspend fun askFinish(r: ScraperSession): Outcome {
    if (!r.status().finishQuestion) return Outcome(true, tr.routeStoppedMsg, stopped = true)
    if (viaWidget) return Outcome(true, tr.finishLeftOpenMsg, stopped = true)
    val answer = CompletableDeferred<Boolean?>()
    finishAnswer = answer
    emit()
    val finish = try {
      withTimeoutOrNull(120_000) { answer.await() }
    } finally {
      finishAnswer = null
      emit()
    }
    if (finish == null || !r.isConnected || !r.status().finishQuestion) return Outcome(true, tr.finishLeftOpenMsg, stopped = true)
    r.answerStop(finish)
    return Outcome(true, if (finish) tr.routeContinuesMsg else tr.routeCancelledMsg, stopped = true)
  }

  /** The user's answer to [ControllerState.finishQuestion]: true = finish, false = don't,
   *  null = later (leave the question on the robot). */
  fun answerFinish(finish: Boolean?) {
    finishAnswer?.complete(finish)
  }

  /** Swift: [answerFinish] without a nullable Boolean. */
  fun finishLater() = answerFinish(null)

  suspend fun setPlanning(on: Boolean): Outcome = job(
    work = {
      robot!!.setPlanning(on)
      Outcome(true, tr.planningIs(on))
    },
    record = { o ->
      HistoryEvent(
        at = now(),
        kind = if (o.ok) EventKind.Planning else EventKind.Failed,
        title = if (o.ok) o.message else tr.planningChangeFailed,
        detail = if (o.ok) "" else o.message,
        viaWidget = viaWidget,
        robot = settings.robot.uid,
      )
    },
  )

  /** Connect, read the home screen, disconnect. */
  suspend fun refresh(): Outcome = job(work = { Outcome(true, tr.statusUpdated) })

  /** Read what each route consists of on the robot and remember it with the robot. */
  suspend fun readRoutes(): Outcome = job(
    work = {
      if (!settings.robot.type.can(Capability.RouteContents)) return@job Outcome(false, tr.notSupported, problem = Problem.Failed)
      val parts = robot!!.readRoutes()
      settings.updateRobot { it.copy(routeParts = parts) }
      save()
      Outcome(true, tr.routesRead)
    },
  )

  /** Panel: keep the connection open until [release]. Shows the live screen. */
  suspend fun hold(): Outcome {
    holds++
    if (isConnected) return Outcome(true, tr.connected)
    val o = job(work = { Outcome(true, tr.connected) })
    if (!o.ok) holds = 0
    return o
  }

  suspend fun release() {
    if (holds == 0) return
    holds--
    if (holds == 0) mutex.withLock {
      close()
      set(LinkState.Idle)
    }
  }

  /** A type-specific action on the open connection (its own screens, while held). */
  suspend fun custom(action: suspend (ScraperSession) -> Unit): Outcome = mutex.withLock {
    val r = robot
    if (r == null || !r.isConnected) return@withLock Outcome(false, tr.notConnected)
    try {
      action(r)
      readStatus()
      Outcome(true, "")
    } catch (e: ScraperException) {
      Outcome(false, e.message ?: "")
    }
  }

  /** Stops polling and closes the link (app goes away). */
  fun dispose() {
    poll?.cancel()
    val r = robot
    robot = null
    scope.launch { runCatching { r?.disconnect() } }
  }

  // ---- internals ----

  private suspend fun job(work: suspend () -> Outcome, record: ((Outcome) -> HistoryEvent)? = null): Outcome = mutex.withLock {
    steps = emptyList()
    last = null
    val out: Outcome = try {
      if (!isConnected) open()
      set(LinkState.Working)
      work().also { readStatus() }
    } catch (e: ScraperException) {
      Outcome(
        false, e.message ?: "",
        problem = when (e.fault) {
          ScraperFault.BluetoothOff -> Problem.BluetoothOff
          ScraperFault.NotFound -> Problem.NotFound
          ScraperFault.AuthRejected -> Problem.PinRejected
          ScraperFault.Busy -> Problem.Busy
          ScraperFault.NoConfirmation -> Problem.NoAnswer
          ScraperFault.Failed -> Problem.Failed
        },
      )
    } catch (e: NoRobot) {
      Outcome(false, tr.noRobotNearby, problem = Problem.NotFound)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      Outcome(false, tr.noConnection, problem = Problem.NotFound)
    }
    last = out
    if (record != null) {
      val e = record(out)
      history = (listOf(e) + history).take(History.MAX)
      runCatching { History.add(store, e) }
    }
    if (holds == 0 || (!out.ok && !isConnected)) close()
    set(if (isConnected) LinkState.Connected else LinkState.Idle)
    out
  }

  private class NoRobot : Exception()

  private suspend fun open() {
    var p = settings.robot
    if (p.robotId == null) {
      set(LinkState.Searching)
      val found = p.type.discover(p.robotName.ifEmpty { null })
      if (found.isEmpty()) throw NoRobot()
      p = p.copy(robotId = found.first().id, robotName = p.robotName.ifEmpty { found.first().name })
      settings.update(p)
      save()
    }
    set(LinkState.Connecting)
    val r = p.type.openSession(
      p,
      onStep = { s ->
        steps = steps + s
        emit()
      },
      onChanged = { changed ->
        settings.update(changed)
        save()
      },
    )
    robot = r
    r.connect()
    scope.launch {
      r.awaitClosed()
      if (robot === r) {
        poll?.cancel()
        poll = null
        robot = null
        holds = 0
        if (link != LinkState.Working) set(LinkState.Idle)
      }
    }
    readStatus()
    poll = scope.launch {
      while (isActive) {
        delay(250)
        readStatus(onlyIfChanged = true)
      }
    }
  }

  private suspend fun close() {
    poll?.cancel()
    poll = null
    val r = robot
    robot = null
    seen = null
    if (r != null) runCatching { r.disconnect() }
  }

  private fun readStatus(onlyIfChanged: Boolean = false) {
    val r = robot
    if (r == null || !r.isConnected) return
    val rev = r.revision
    if (onlyIfChanged && rev == seen) return
    seen = rev
    val st = r.status()
    if (st.planning == true || (st.planning == false && !st.busy && link != LinkState.Working)) planningResumes = false
    if (st.planning != null) {
      val before = settings.robot.snapshot
      settings.updateRobot {
        it.copy(snapshot = RobotSnapshot(now(), st.planning, st.nextRun, st.batteryMv, st.activity, st.busy))
      }
      save()
      if (before?.planning != st.planning || before?.nextRide != st.nextRun) publish()   // the widgets show the schedule
    }
    emit()
  }

  private fun save() {
    runCatching { settings.save(store) }
  }

  private fun publish() {
    if (viaWidget) return
    runCatching { onPublish?.invoke(settings) }
  }

  private fun set(s: LinkState) {
    link = s
    emit()
  }

  private fun build() = ControllerState(
    link = link,
    planningResumes = planningResumes,
    history = history,
    steps = steps,
    last = last,
    robots = settings.robots.ifEmpty { listOf(settings.robot) },
    robot = settings.robot,
    live = robot?.takeIf { it.isConnected }?.status(),
    connected = isConnected,
    onboarded = settings.onboarded,
    widgetTipSeen = settings.widgetTipSeen,
    language = settings.language,
    finishQuestion = finishAnswer != null,
    revision = revision++,
  )

  private fun emit() {
    _state.value = build()
  }
}
