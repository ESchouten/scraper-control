// Lely Discovery: the first implementation of the generic scraper model. All robot logic
// (screens, route steps, safety rules) is in the shared C++ core; this file only translates
// between that core (Lely, CoreSession) and model/Scraper.kt.
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import kotlin.time.Clock
import kotlinx.coroutines.delay
import nl.eschouten.scraper.app.RobotProfile
import nl.eschouten.scraper.model.Capability
import nl.eschouten.scraper.model.FoundScraper
import nl.eschouten.scraper.model.ModelScreen
import nl.eschouten.scraper.model.RunIntent
import nl.eschouten.scraper.model.RunStep
import nl.eschouten.scraper.model.ScraperException
import nl.eschouten.scraper.model.ScraperFault
import nl.eschouten.scraper.model.ScraperModel
import nl.eschouten.scraper.model.ScraperSession
import nl.eschouten.scraper.model.ScraperStatus
import nl.eschouten.scraper.texts.tr

/** Replaces Bluetooth with a simulated robot (desktop development, tests): see Simulator. */
class LinkProvider(
  val link: (id: String) -> RobotLink,
  val robots: List<FoundRobot>,
  val scanDelayMs: Long = 2_000,
)

object LelyDiscovery : ScraperModel() {
  /** Set by the demo, tests or the desktop simulator; null = Bluetooth. */
  var linkProvider: LinkProvider? = devLinkProvider()

  const val PANEL = "panel"

  override val id = "lely.discovery"
  override val brand = "Lely"
  override val name = "Discovery"
  override val capabilities = setOf(Capability.Routes, Capability.Planning, Capability.Battery, Capability.NextRun, Capability.Stop, Capability.RouteContents)
  override val pinHelp get() = tr.btPinHelp
  override val reachHelp get() = tr.btReachHelp
  override val scanHelp get() = tr.btScanHelp

  override suspend fun discover(name: String?): List<FoundScraper> {
    linkProvider?.let { sim ->
      delay(sim.scanDelayMs)
      return sim.robots.map { FoundScraper(it.id, it.name, it.rssi) }
    }
    return scanRobots(name).map { FoundScraper(it.id, it.name, it.rssi, it.kind) }
  }

  override fun openSession(p: RobotProfile, onStep: (RunStep) -> Unit, onChanged: (RobotProfile) -> Unit): LelySession =
    LelySession(p, onStep, onChanged)

  // ---- progress ----
  private fun step(s: RunStep) = CoreStep.entries.firstOrNull { it.name == s.id }

  override fun stepLabel(s: RunStep, routeTitle: String): String = when (step(s)) {
    CoreStep.Linked -> tr.stepLinked
    CoreStep.Granted -> tr.stepGranted
    CoreStep.ScreenReady -> tr.stepScreenReady
    CoreStep.PlanningOff -> tr.stepPlanningOff
    CoreStep.PlanningOn -> tr.stepPlanningOn
    CoreStep.Menu -> tr.stepMenu
    CoreStep.Selected -> tr.stepSelected(routeTitle)
    CoreStep.StartPressed -> tr.stepStartPressed
    CoreStep.Answered -> tr.stepAnswered
    CoreStep.Restored -> tr.stepRestored
    CoreStep.Syncing -> tr.syncing(s.percent ?: 0)
    null -> s.id
  }

  override fun nextStep(done: List<RunStep>, intent: RunIntent): String {
    val route = intent.route != null
    val dry = intent.dryRun
    return when (if (done.isEmpty()) null else step(done.last())) {
      null -> tr.connecting
      CoreStep.Linked -> tr.nextCheckPin
      CoreStep.Granted -> tr.nextWaitPanel
      CoreStep.Syncing -> tr.syncing(done.last().percent ?: 0)
      CoreStep.ScreenReady -> when {
        !route -> tr.nextReadStatus
        intent.planningOn == true -> tr.nextPlanningOff
        else -> tr.nextOpenMenu
      }
      CoreStep.PlanningOff -> tr.nextOpenMenu
      CoreStep.PlanningOn -> if (dry) tr.nextRestore else tr.nextOpenMenu
      CoreStep.Menu -> tr.nextChooseRoute
      CoreStep.Selected -> if (dry) tr.nextRestore else tr.nextPressStart
      CoreStep.StartPressed -> tr.nextAnswer
      CoreStep.Answered, CoreStep.Restored -> tr.nextFinish
    }
  }

  override fun expectedSteps(intent: RunIntent): Int {
    if (intent.route == null) return 3
    val on = intent.planningOn ?: true
    return if (intent.dryRun) (if (on) 8 else 6) else (if (on) 8 else 7)
  }

  override val screens get() = listOf(ModelScreen(PANEL, tr.panelTitle, tr.panelSubtitle))

  override val demoRobots get() = Demo.robots.map { FoundScraper(it.id, it.name, it.rssi) }
  override fun installDemo(words: Map<String, String>) = Demo.install(words)
  override fun uninstallDemo() = Demo.uninstall()
}

private const val OWED = "owedPlanning"
private const val OWED_FOR_MS = 6 * 60 * 60 * 1000L // 6 hours

/** One connection to one Discovery, through the C++ core. */
class LelySession(
  private var profile: RobotProfile,
  private val onStep: (RunStep) -> Unit,
  private val onChanged: (RobotProfile) -> Unit,
) : ScraperSession {
  private var robot: Robot? = null

  override val isConnected get() = robot?.isConnected ?: false

  /** The robot's display as the core sees it (for the panel screen). */
  val live: CoreStatus? get() = if (isConnected) robot!!.status() else null

  override suspend fun awaitClosed() {
    robot?.link?.awaitClosed()
  }

  override suspend fun connect() {
    val sim = LelyDiscovery.linkProvider
    if (sim == null) {
      bluetoothProblem()?.let { why ->
        throw ScraperException(
          ScraperFault.BluetoothOff,
          when (why) {
            BluetoothProblem.Off -> tr.btOff
            BluetoothProblem.Denied -> tr.btDenied
            BluetoothProblem.Unsupported -> tr.btUnsupported
          },
        )
      }
    }
    val id = profile.robotId ?: throw ScraperException(ScraperFault.NotFound, tr.noRobotNearby)
    val link = sim?.link?.invoke(id) ?: openLink(profile.link, id, profile.robotName.ifEmpty { null })
    val r = Robot(
      link, pin = profile.pin, imageVersion = imageVersion(), language = tr.code, onStep = { onStep(RunStep(it.name, if (it == CoreStep.Syncing) robot?.status()?.syncPercent ?: 0 else null)) },
      owedPlanning = owedPlanning(), onOwed = ::keepOwed,
    )
    robot = r
    try {
      guard { r.connect() }
    } finally {
      r.newImageVersion?.let { v ->
        profile = profile.copy(extra = profile.extra + ("imageVersion" to v.toHexString()))
        onChanged(profile)
      }
    }
  }

  override suspend fun disconnect() {
    runCatching { robot?.disconnect() }
  }

  override fun status(): ScraperStatus {
    val s = robot?.status() ?: return ScraperStatus()
    return ScraperStatus(
      planning = s.planning, nextRun = s.nextRide, batteryMv = s.batteryMv, activity = s.action,
      route = s.route, busy = s.busy, driving = s.driving, finishQuestion = s.stopQuestion,
    )
  }

  override val revision: Any get() = robot?.status()?.let { "${it.screen}\u0000${it.highlighted}" } ?: ""

  override suspend fun startRoute(slot: String, dryRun: Boolean) = guard { robot!!.startRoute(slot.first(), dryRun) }
  override suspend fun setPlanning(on: Boolean) = guard { robot!!.setPlanning(on) }
  override suspend fun stopRoute() = guard { robot!!.stopRoute() }
  override suspend fun answerStop(finish: Boolean) = guard { robot!!.answerStop(finish) }

  override suspend fun readRoutes(): Map<String, String> {
    var out = emptyMap<String, String>()
    guard { out = robot!!.readRoutes() }
    return out
  }

  /** A raw panel key (panel screen). */
  suspend fun press(key: RobotKey) = guard { robot!!.press(key) }

  /** Planning owed by a session whose link dropped, as "1@<epoch ms>"; too old = forgotten
   *  (someone may have changed the robot on purpose since). */
  private fun owedPlanning(now: Long = Clock.System.now().toEpochMilliseconds()): Int {
    val (v, at) = (profile.extra[OWED] ?: return -1).split('@').let { it[0].toIntOrNull() to it.getOrNull(1)?.toLongOrNull() }
    return if (v != null && at != null && now - at in 0..OWED_FOR_MS) v else -1
  }

  private fun keepOwed(v: Int) {
    val extra = if (v < 0) profile.extra - OWED else profile.extra + (OWED to "$v@${Clock.System.now().toEpochMilliseconds()}")
    if (extra == profile.extra) return
    profile = profile.copy(extra = extra)
    onChanged(profile)
  }

  private fun imageVersion(): ByteArray? {
    val h = profile.extra["imageVersion"] ?: return null
    return runCatching { h.hexToByteArray() }.getOrNull()?.takeIf { it.size == 9 }
  }

  /** Core errors -> generic faults. */
  private suspend fun guard(f: suspend () -> Unit) {
    try {
      f()
    } catch (e: ScraperException) {
      throw e
    } catch (e: PinRejected) {
      throw ScraperException(ScraperFault.AuthRejected, tr.pinRefused)
    } catch (e: RobotBusy) {
      throw ScraperException(ScraperFault.Busy, tr.robotBusyNothingPressed)
    } catch (e: RobotException) {
      throw when (e.result) {
        CoreResult.StartedNoAnswer -> ScraperException(ScraperFault.NoConfirmation, tr.noQuestionAfterStart)
        CoreResult.ConnectFailed -> ScraperException(ScraperFault.NotFound, firstLine(e.message ?: ""))
        else -> ScraperException(ScraperFault.Failed, firstLine(e.message ?: ""))
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      throw ScraperException(ScraperFault.NotFound, tr.noConnection)
    }
  }

  private fun firstLine(s: String): String {
    val t = s.lineSequence().first().trim()
    return if (t.isEmpty()) tr.unknownError else t.replaceFirstChar { it.uppercase() }
  }
}
