// What a tap does, where it changes something: both apps call these, so a tap does the same
// thing everywhere. Each returns the message for a toast (or null for none).
package nl.eschouten.scraper.ui

import nl.eschouten.scraper.app.RobotController
import nl.eschouten.scraper.app.RobotProfile
import nl.eschouten.scraper.model.FoundScraper
import nl.eschouten.scraper.texts.tr

object Actions {
  /** The state pill and pull-to-refresh: read the robot once. */
  suspend fun refresh(c: RobotController): String? = c.refresh().let { if (it.ok) null else it.message }

  /** After [planningConfirm] said yes. */
  suspend fun togglePlanning(c: RobotController): String {
    val on = c.state.value.snapshot?.planning ?: false
    return c.setPlanning(!on).message
  }

  suspend fun dismissWidgetTip(c: RobotController) = c.updateSettings { widgetTipSeen = true }

  suspend fun setLanguage(c: RobotController, code: String) {
    if (code != c.state.value.language) c.updateSettings { language = code }
  }

  /** Back to the welcome screen (the robots stay). */
  suspend fun resetSetup(c: RobotController) = c.updateSettings { onboarded = false }

  /** "Test connection" in a robot's settings: makes it the active robot first. */
  suspend fun testConnection(c: RobotController, uid: String): String {
    c.selectRobot(uid)
    val o = c.refresh()
    return if (o.ok) tr.connectionWorks else o.message
  }

  /** Read what routes A..D consist of on the robot; the answer for a toast. */
  suspend fun readRoutes(c: RobotController, uid: String): String {
    c.selectRobot(uid)
    return c.readRoutes().message
  }

  suspend fun selectRobot(c: RobotController, uid: String) = c.selectRobot(uid)

  suspend fun removeRobot(c: RobotController, uid: String) = c.removeRobot(uid)

  private fun profile(c: RobotController, uid: String): RobotProfile? = c.state.value.robots.firstOrNull { it.uid == uid }

  suspend fun rename(c: RobotController, uid: String, name: String) {
    profile(c, uid)?.let { c.updateRobot(it.copy(label = name.trim())) }
  }

  suspend fun setPin(c: RobotController, uid: String, pin: String) {
    profile(c, uid)?.let { c.updateRobot(it.copy(pin = pin.trim())) }
  }

  suspend fun renameRoute(c: RobotController, uid: String, route: String, name: String) {
    profile(c, uid)?.let { c.updateRobot(it.withRouteName(route, name)) }
  }

  suspend fun showRoute(c: RobotController, uid: String, route: String, shown: Boolean) {
    profile(c, uid)?.let { c.updateRobot(it.withRoute(route, shown)) }
  }

  /** The device picker in a robot's settings. */
  suspend fun chooseDevice(c: RobotController, uid: String, f: FoundScraper) {
    profile(c, uid)?.let { c.updateRobot(it.withDevice(f)) }
  }

  fun clearLogbook(c: RobotController) = c.clearHistory()
}
