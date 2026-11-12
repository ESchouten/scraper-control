// What the apps remember: the robots, which one is active, and a few flags. Profiles are immutable; change them with copy()
// and Settings.update(), so a screen never sees half a change.
package nl.eschouten.scraper.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.eschouten.scraper.model.ScraperModel
import nl.eschouten.scraper.model.defaultModelId
import nl.eschouten.scraper.model.modelFor
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.texts.useLanguage
import kotlin.random.Random

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Last known state of a robot, shown before (re)connecting. [at] in epoch milliseconds. */
@Serializable
data class RobotSnapshot(
  val at: Long,
  val planning: Boolean? = null,
  val nextRide: String = "",
  val batteryMv: Int? = null,
  val action: String = "",
  val busy: Boolean = false,
)

/** One robot: which type it is, how to reach it and how its routes are shown. */
@Serializable
data class RobotProfile(
  val uid: String = newUid(), // stable id inside the app (widgets refer to it)
  val model: String = defaultModelId, // ScraperModel.id
  val robotId: String? = null, // address: MAC (Android/Windows/Linux) or UUID (iOS/macOS)
  val robotName: String = "", // advertised name; helps on iOS/macOS
  val label: String = "", // own name, e.g. "Stal 2"
  val pin: String = "",
  val routes: String = "ABCD", // which route slots to show, e.g. "ABC"
  val routeNames: Map<String, String> = emptyMap(), // "A" -> "Voergang"
  val routeParts: Map<String, String> = emptyMap(), // "C" -> "1 2 3": what the route is on the robot (read)
  val extra: Map<String, String> = emptyMap(), // whatever the scraper type wants to remember
  val snapshot: RobotSnapshot? = null,
) {
  val type: ScraperModel get() = modelFor(model)

  val displayName: String
    get() = when {
      label.isNotBlank() -> label.trim()
      robotName.isNotEmpty() -> robotName
      else -> type.fullName
    }

  fun routeName(r: String) = (routeNames[r] ?: "").trim()
  fun routeTitle(r: String) = routeName(r).ifEmpty { tr.routeLetter(r) }
  val visibleRoutes: List<String> get() = routes.map { it.toString() }

  companion object {
    fun newUid(): String = (1..8).joinToString("") { Random.nextInt(16).toString(16) }
  }
}

class Settings(
  robots: List<RobotProfile> = emptyList(),
  activeUid: String? = null,
  var onboarded: Boolean = false,
  var widgetTipSeen: Boolean = false, // dismissed the "add the widget" tip
  var language: String = "", // "" = as the phone, or "nl" / "en"
) {
  var robots: List<RobotProfile> = robots
    private set
  private var activeUid: String? = activeUid
  private var override: String? = null // widget: act on this robot without changing the choice

  val robot: RobotProfile
    get() {
      if (robots.isEmpty()) robots = listOf(RobotProfile())
      val want = override ?: activeUid
      return robots.firstOrNull { it.uid == want } ?: robots.first()
    }

  fun byUid(uid: String) = robots.firstOrNull { it.uid == uid }

  fun select(uid: String) {
    activeUid = uid
  }

  /** Use [uid] for this process only (not saved). False if it does not exist. */
  fun useOnly(uid: String): Boolean {
    if (byUid(uid) == null) return false
    override = uid
    return true
  }

  fun add(p: RobotProfile): RobotProfile {
    robots = robots + p
    activeUid = p.uid
    return p
  }

  fun remove(uid: String) {
    robots = robots.filterNot { it.uid == uid }
    if (activeUid == uid) activeUid = robots.firstOrNull()?.uid
  }

  /** Replace the robot with the same uid (or the active one). */
  fun update(p: RobotProfile) {
    robots = if (robots.any { it.uid == p.uid }) robots.map { if (it.uid == p.uid) p else it } else robots + p
  }

  /** Change the active robot. */
  fun updateRobot(change: (RobotProfile) -> RobotProfile) = update(change(robot))

  fun save(store: Store) {
    // The PINs go to the platform's secret store, never into the plain settings.
    for (r in robots) store.putSecret(pinKey(r.uid), r.pin.ifEmpty { null })
    val uids = robots.joinToString(",") { it.uid }
    store.getString("pinUids")?.split(',')?.filter { it.isNotEmpty() && robots.none { r -> r.uid == it } }
      ?.forEach { store.putSecret(pinKey(it), null) } // robots that were removed
    store.putString("pinUids", uids)
    store.putString("robots", json.encodeToString(robots.map { it.copy(pin = "") }))
    activeUid?.let { store.putString("activeRobot", it) }
    store.putString("onboarded", onboarded.toString())
    store.putString("widgetTipSeen", widgetTipSeen.toString())
    store.putString("language", language)
  }

  companion object {
    val allRoutes = listOf("A", "B", "C", "D")

    /** Single-robot shortcut for tests. */
    fun single(
      robotId: String? = null,
      robotName: String = "",
      pin: String = "",
      routes: String = "ABCD",
      routeNames: Map<String, String> = emptyMap(),
      onboarded: Boolean = false,
    ) = Settings(listOf(RobotProfile(robotId = robotId, robotName = robotName, pin = pin, routes = routes, routeNames = routeNames)), onboarded = onboarded)

    /** Loads the settings and switches the texts to their language. */
    fun load(store: Store): Settings {
      val s = read(store)
      useLanguage(s.language)
      return s
    }

    private fun pinKey(uid: String) = "pin.$uid"

    private fun read(store: Store): Settings {
      val stored = store.getString("robots")?.let { runCatching { json.decodeFromString<List<RobotProfile>>(it) }.getOrNull() } ?: emptyList()
      val robots = stored.map { r -> r.copy(pin = store.getSecret(pinKey(r.uid)) ?: r.pin) }
      val plainPin = stored.any { it.pin.isNotEmpty() } // saved by an older version: move it now
      return Settings(
        robots = robots,
        activeUid = store.getString("activeRobot"),
        onboarded = store.getString("onboarded") == "true",
        widgetTipSeen = store.getString("widgetTipSeen") == "true",
        language = store.getString("language") ?: "",
      ).also { if (plainPin) it.save(store) }
    }
  }
}
