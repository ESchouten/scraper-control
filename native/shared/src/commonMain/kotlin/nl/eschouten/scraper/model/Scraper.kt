// The generic model: what the apps know about any manure scraper. One implementation per
// scraper type (brand + model), listed in Registry.kt. The rest (controller, screen models,
// widgets) only talks to these.
//
//   ScraperModel      a type of scraper: name, capabilities, how to find it, texts
//   ScraperSession    one short connection to one robot: connect, act, disconnect
//   ScraperStatus     what the robot reports, in generic terms
//   ScraperException  why something failed, in generic terms (ScraperFault)
package nl.eschouten.scraper.model

import nl.eschouten.scraper.app.RobotProfile

/** What a scraper type supports. The apps show only what is supported. */
enum class Capability {
  Routes, // start a stored route by its slot (A, B, ...)
  Planning, // switch the time planning (automatic rides) on/off
  Battery, // reports a battery voltage
  NextRun, // reports the next planned ride
  Stop, // stopRoute(): a route button pressed during a route stops it (safety stop)
  RouteContents, // readRoutes(): what each route slot consists of on the robot
}

/** A robot found nearby. [signal] is RSSI in dBm, -127 if unknown. */
data class FoundScraper(val id: String, val name: String, val signal: Int)

/** Status in generic terms. Fields a type does not report stay null / empty. */
data class ScraperStatus(
  val planning: Boolean? = null, // null = unknown right now
  val nextRun: String = "", // as the robot shows it, e.g. "21:00"
  val batteryMv: Int? = null,
  val activity: String = "", // what it is doing, as the robot says it
  val route: String = "", // route it is driving, "" if none
  val busy: Boolean = false, // not ready for a new command (driving, in a menu, ...)
  val driving: Boolean = false, // a route runs: stopRoute() can stop it
  val finishQuestion: Boolean = false, // a stopped route asks "finish it?": answerStop()
)

/** One step of a running command, for the progress bar. [id] is defined by the type. */
data class RunStep(val id: String)

/** What a command is about to do, so the type can describe its steps. */
data class RunIntent(val route: String? = null, val dryRun: Boolean = false, val planningOn: Boolean? = null)

enum class ScraperFault {
  BluetoothOff, // radio off, no permission or no radio
  NotFound, // robot not reachable
  AuthRejected, // wrong PIN / password
  Busy, // not ready; nothing was done
  NoConfirmation, // command sent but the robot's confirmation was not seen
  Failed, // anything else
}

class ScraperException(val fault: ScraperFault, message: String) : Exception(message)

/** A screen of its own for one scraper type, for things that do not fit the generic model
 *  (for example the robot's own panel). Each app maps [id] to its own screen. */
data class ModelScreen(val id: String, val title: String, val subtitle: String)

/** A type of scraper. Implementations are listed in [scraperModels]. */
abstract class ScraperModel {
  abstract val id: String // stored per robot, "brand.model"; never change it
  abstract val brand: String
  abstract val name: String
  val fullName: String get() = "$brand $name"
  abstract val capabilities: Set<Capability>
  fun can(c: Capability) = c in capabilities

  // ---- connecting ----
  abstract val pinHelp: String // under the PIN field
  abstract val reachHelp: String // when the robot cannot be reached
  abstract val scanHelp: String // when the scan finds nothing

  /** Robots of this type nearby, strongest first. */
  abstract suspend fun discover(name: String? = null): List<FoundScraper>

  /** A new, not yet connected session for [p]. [onChanged]: the session learned something
   *  about the robot (remembered data); save the profile it passes. */
  abstract fun openSession(p: RobotProfile, onStep: (RunStep) -> Unit, onChanged: (RobotProfile) -> Unit): ScraperSession

  // ---- progress texts ----
  abstract fun stepLabel(s: RunStep, routeTitle: String): String // a finished step
  abstract fun nextStep(done: List<RunStep>, intent: RunIntent): String // what is happening now
  abstract fun expectedSteps(intent: RunIntent): Int

  // ---- own screens ----
  open val screens: List<ModelScreen> get() = emptyList()

  // ---- demo (the in-app robot without Bluetooth; UI tests) ----
  /** Robots the demo finds. Empty: the type has no demo. */
  open val demoRobots: List<FoundScraper> get() = emptyList()

  /** Use the demo robot instead of Bluetooth, set up from demo words (see AppStart). */
  open fun installDemo(words: Map<String, String>) {}
  open fun uninstallDemo() {}
}

/** One short connection: connect, a few commands, disconnect. */
interface ScraperSession {
  val isConnected: Boolean

  /** Returns when the link drops. */
  suspend fun awaitClosed()

  /** Throws [ScraperException]. */
  suspend fun connect()
  suspend fun disconnect()

  fun status(): ScraperStatus

  /** Changes whenever the robot's state changed (for polling). */
  val revision: Any

  /** Start the stored route [slot] with all of the type's safety rules. */
  suspend fun startRoute(slot: String, dryRun: Boolean = false)
  suspend fun setPlanning(on: Boolean)

  /** Safety stop of a running route ([ScraperStatus.driving]). Busy on any other screen.
   *  Only called when the type has [Capability.Stop]. */
  suspend fun stopRoute()

  /** After a stop the robot may ask whether to finish the route ([ScraperStatus.finishQuestion]).
   *  Passes the user's answer on: true = finish (it drives on), false = the route ends. */
  suspend fun answerStop(finish: Boolean) {}

  /** [Capability.RouteContents]: route slot -> what it consists of, as the robot shows it,
   *  e.g. "C" -> "1 2 3". Only reads; the robot is left as it was. */
  suspend fun readRoutes(): Map<String, String> = emptyMap()
}
