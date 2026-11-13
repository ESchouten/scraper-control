// Settings, a robot's own settings, adding a robot, and the Bluetooth scan: the content and
// the flow (scan, pick, test), the same in every app.
package nl.eschouten.scraper.ui

import kotlinx.coroutines.CoroutineScope
import nl.eschouten.scraper.AppVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.eschouten.scraper.app.Cancellable
import nl.eschouten.scraper.app.ControllerState
import nl.eschouten.scraper.app.RobotProfile
import nl.eschouten.scraper.app.Settings
import nl.eschouten.scraper.model.Capability
import nl.eschouten.scraper.model.FoundScraper
import nl.eschouten.scraper.model.ScraperModel
import nl.eschouten.scraper.texts.appLanguages
import nl.eschouten.scraper.texts.tr

/** One row in a settings list. [id] says what a tap does (the app handles it). */
data class Row(
  val id: String,
  val glyph: Glyph?,
  val title: String,
  val subtitle: String,
  val enabled: Boolean = true,
  val checked: Boolean = false,
  /** A route badge instead of a glyph. */
  val route: String? = null,
  val routeShown: Boolean = false,
  /** Has a switch (routes: shown on the home screen). */
  val toggle: Boolean? = null,
  val busy: Boolean = false,
) {
  val testId: String get() = "row-$id"
}

data class Section(val title: String, val rows: List<Row>, val footer: String? = null)

data class Choice(val id: String, val label: String, val selected: Boolean)

data class SettingsScreen(
  val title: String,
  val sections: List<Section>,
  val languageTitle: String,
  val languages: List<Choice>,
  val resetConfirm: Confirm,
)

object RowId {
  const val ADD_ROBOT = "add-robot"
  const val LOGBOOK = "logbook"
  const val WIDGETS = "widgets"
  const val LANGUAGE = "language"
  const val ABOUT = "about"
  const val RESET = "reset"
  const val NAME = "name"
  const val DEVICE = "device"
  const val PIN = "pin"
  const val TEST = "test"
  const val READ_ROUTES = "read-routes"
  fun robot(uid: String) = "robot-$uid"
  fun route(r: String) = "route-$r"
}

fun languageName(code: String) = when (code) {
  "nl" -> "Nederlands"
  "en" -> "English"
  else -> tr.languagePhone
}

fun settingsScreen(s: ControllerState, now: Long, platform: Platform): SettingsScreen {
  val robots = Section(
    tr.robots,
    s.robots.map { r ->
      Row(RowId.robot(r.uid), Glyph.Robot, r.displayName, if (r.uid == s.robot.uid) tr.inUse else (r.robotId ?: tr.notLinkedYet)) // opens its settings: a chevron, "in use" says which one is active
    } + Row(RowId.ADD_ROBOT, Glyph.Add, tr.addRobot, tr.secondRobot, enabled = !s.isBusy),
  )
  val activity = Section(
    tr.activity,
    listOf(
      Row(
        RowId.LOGBOOK, Glyph.Logbook, tr.logbook,
        s.history.firstOrNull()?.let { tr.lastEvent(it.title, Fmt.whenText(it.at, now)) } ?: tr.nothingStarted,
      ),
    ),
  )
  val widgets = if (platform == Platform.Desktop) null else Section(
    tr.widgets,
    listOf(Row(RowId.WIDGETS, Glyph.Widgets, tr.routesOnHome, if (platform == Platform.Ios) tr.widgetHelpIos(APP_NAME) else tr.widgetHelpAndroid(APP_NAME), enabled = false)),
  )
  val language = Section(tr.language, listOf(Row(RowId.LANGUAGE, Glyph.Translate, tr.language, languageName(s.language))))
  val about = Section(
    tr.about,
    listOf(
      Row(RowId.ABOUT, null, APP_NAME, "${tr.version(AppVersion.NAME)}\n${tr.disclaimer}", enabled = false),
      Row(RowId.RESET, Glyph.Restart, tr.resetSetup, tr.resetSetupSub),
    ),
  )
  return SettingsScreen(
    tr.settings,
    listOfNotNull(robots, activity, widgets, language, about),
    tr.language,
    (listOf("") + appLanguages).map { Choice(it, languageName(it), it == s.language) },
    Confirm(Glyph.Restart, tr.resetSetupQ, tr.resetSetupBody, tr.resetSetup, tr.cancel),
  )
}

data class RobotSettingsScreen(
  val title: String,
  val uid: String,
  val sections: List<Section>,
  /** Only with more than one robot. */
  val remove: Action?,
  val removeConfirm: Confirm,
  val nameDialog: TextDialog,
)

data class TextDialog(val title: String, val hint: String, val value: String, val ok: String, val cancel: String, val secret: Boolean = false)

fun robotSettingsScreen(s: ControllerState, uid: String): RobotSettingsScreen? {
  val p = s.robots.firstOrNull { it.uid == uid } ?: return null
  val active = p.uid == s.robot.uid
  val robot = Section(
    tr.robot,
    listOf(
      Row(RowId.NAME, Glyph.Badge, tr.name, p.displayName),
      Row(
        RowId.DEVICE, Glyph.Bluetooth, tr.bluetooth,
        if (p.robotId == null) tr.searchedAutomatically else "${p.robotName.ifEmpty { p.type.fullName }} · ${p.robotId}",
        enabled = !s.isBusy,
      ),
      Row(RowId.PIN, Glyph.Lock, tr.pin, if (p.pin.isEmpty()) tr.noPin else "•".repeat(p.pin.length)),
      Row(RowId.TEST, Glyph.BluetoothSearching, tr.testConnection, if (active) tr.testConnectionSub else tr.makeActive, enabled = !s.isBusy, busy = s.isBusy),
    ),
  )
  val parts = { r: String -> p.routeParts[r]?.let { if (it.isBlank()) tr.routeEmpty else tr.routeParts(it) } }
  val routes = Section(
    tr.routes,
    Settings.allRoutes.map { r ->
      Row(
        RowId.route(r), null, p.routeTitle(r),
        listOfNotNull(if (p.routeName(r).isEmpty()) tr.tapToName else tr.routeTapToChange(r), parts(r)).joinToString(" · "),
        route = r, routeShown = r in p.routes, toggle = r in p.routes,
      )
    } + listOfNotNull(
      if (p.type.can(Capability.RouteContents)) {
        Row(RowId.READ_ROUTES, Glyph.Refresh, tr.readRoutes, if (active) tr.readRoutesSub else tr.makeActive, enabled = !s.isBusy, busy = s.isBusy)
      } else null,
    ),
    footer = tr.routesHelp,
  )
  return RobotSettingsScreen(
    p.displayName, p.uid, listOf(robot, routes),
    remove = if (s.robots.size > 1) Action(tr.removeRobot, ActionKind.Confirm, "robot-remove", primary = false, enabled = !s.isBusy) else null,
    removeConfirm = Confirm(Glyph.Delete, tr.removeRobotQ(p.displayName), tr.removeRobotBody, tr.remove, tr.cancel),
    nameDialog = TextDialog(tr.robotNameTitle, tr.exampleBarn, p.label, tr.save, tr.cancel),
  )
}

fun routeNameDialog(p: RobotProfile, r: String) = TextDialog(tr.routeNameTitle(r), tr.routeNameHint, p.routeName(r), tr.save, tr.cancel)
fun pinDialog(p: RobotProfile) = TextDialog(tr.pin, tr.pin, p.pin, tr.save, tr.cancel, secret = true)

/** Show or hide a route on the home screen; keeps the order A..D. */
fun RobotProfile.withRoute(r: String, shown: Boolean): RobotProfile {
  val set = routes.map { it.toString() }.toMutableSet()
  if (shown) set += r else set -= r
  return copy(routes = Settings.allRoutes.filter { it in set }.joinToString(""))
}

fun RobotProfile.withRouteName(r: String, name: String): RobotProfile =
  copy(routeNames = if (name.isBlank()) routeNames - r else routeNames + (r to name.trim()))

// ---- Bluetooth scan (onboarding, add robot, choose device) ----

data class ScanState(
  val scanning: Boolean = false,
  val scanned: Boolean = false,
  val found: List<FoundScraper> = emptyList(),
  val selectedId: String? = null,
  val name: String = "",
  val error: String? = null,
)

data class FoundRow(val id: String, val name: String, val detail: String, val selected: Boolean, val testId: String)

data class ScanView(
  val scanning: Boolean,
  val headline: String,
  val rows: List<FoundRow>,
  /** Help when nothing was found. */
  val emptyHelp: String?,
  /** iPhone: the robot's name helps to find it. */
  val nameHelp: String?,
  val nameLabel: String,
  val again: Action,
  val error: String?,
)

class ScanFlow(
  private val model: ScraperModel,
  /** Robots that are already added. */
  private val exclude: Set<String?> = emptySet(),
  selectedId: String? = null,
  initialName: String = "",
  private val platform: Platform,
  /** Shared with the onboarding / add-robot flow that owns this scan. */
  private val scope: CoroutineScope = newScope(),
) {

  private val _state = MutableStateFlow(ScanState(selectedId = selectedId, name = initialName))
  val state: StateFlow<ScanState> = _state

  fun watch(onChange: (ScanState) -> Unit): Cancellable = _state.watchIn(scope, onChange)

  fun setName(n: String) {
    _state.value = _state.value.copy(name = n)
  }

  fun select(id: String) {
    _state.value = _state.value.copy(selectedId = id)
  }

  /** Scan; one robot found and none chosen yet: it is chosen. */
  suspend fun scan(): List<FoundScraper> {
    _state.value = _state.value.copy(scanning = true, error = null)
    return try {
      val n = _state.value.name.trim()
      val f = model.discover(n.ifEmpty { null }).filterNot { it.id in exclude }
      _state.value = _state.value.copy(found = f, selectedId = _state.value.selectedId ?: f.singleOrNull()?.id)
      f
    } catch (e: Exception) {
      _state.value = _state.value.copy(error = tr.scanFailed(e.message ?: e.toString()))
      emptyList()
    } finally {
      _state.value = _state.value.copy(scanning = false, scanned = true)
    }
  }

  val selected: FoundScraper? get() = _state.value.let { st -> st.found.firstOrNull { it.id == st.selectedId } }

  fun view(st: ScanState = _state.value) = ScanView(
    scanning = st.scanning,
    headline = when {
      st.scanning -> tr.scanning
      st.found.isEmpty() -> if (st.scanned) tr.noRobotFound else ""
      else -> tr.robotsFound(st.found.size)
    },
    rows = st.found.map { f ->
      FoundRow(
        f.id, f.name.ifEmpty { model.fullName },
        when (val b = bars(f.signal)) {
          null -> if (platform == Platform.Ios) "" else f.id
          else -> if (platform == Platform.Ios) tr.signal(b) else tr.signalWithId(f.id, b)
        },
        f.id == st.selectedId, "found-${f.id}",
      )
    },
    emptyHelp = if (!st.scanning && st.scanned && st.found.isEmpty()) model.scanHelp + (if (platform == Platform.Ios) tr.iphoneNameHelp else "") else null,
    nameHelp = if (platform == Platform.Ios) tr.iphoneNameHelp.trim() else null,
    nameLabel = tr.robotNameOptional,
    again = Action(tr.searchAgain, ActionKind.TryAgain, "scan-again", primary = false, enabled = !st.scanning),
    error = st.error,
  )

  companion object {
    /** Null when the signal is not known (-127: a classic Bluetooth inquiry on the desktop). */
    fun bars(rssi: Int) = when {
      rssi <= -127 -> null
      rssi >= -60 -> tr.signalStrong
      rssi >= -75 -> tr.signalGood
      rssi >= -88 -> tr.signalWeak
      else -> tr.signalVeryWeak
    }
  }
}

/** The device picker of robot [uid]; the other robots' devices are left out. */
fun deviceScan(s: ControllerState, uid: String, platform: Platform): ScanFlow? {
  val p = s.robots.firstOrNull { it.uid == uid } ?: return null
  return ScanFlow(p.type, s.robots.filter { it.uid != uid }.map { it.robotId }.toSet(), p.robotId, p.robotName, platform)
}

/** Save the chosen device on a robot (the device picker in robot settings). */
fun RobotProfile.withDevice(f: FoundScraper): RobotProfile =
  if (f.id == robotId) this else copy(robotId = f.id, robotName = f.name, link = f.link, snapshot = null)
