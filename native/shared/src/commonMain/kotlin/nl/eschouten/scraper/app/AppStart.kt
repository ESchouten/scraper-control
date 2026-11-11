// How every app starts (Android, desktop, iOS, and their UI tests): the settings from the
// platform's store, or the demo robot. The demo is the scraper type's in-app robot
// (ScraperModel.installDemo): the app works exactly as with a robot, without Bluetooth.
//
// Demo words (launch argument "--demo <words>" / environment SCRAPER_DEMO):
//   planning=on|off  mode=home|menu|manual|driving|question|abort  lang=nl|en (robot)
//   delay=<ms per answer>  scan=<ms>   setup=done (skip onboarding: demo robot, PIN 4321)
//   ui=nl|en (app language)  routes=ABC  names=A:Voergang,B:Stal+2 (+ is a space)
// The UI tests of both apps start the same way, with the same words.
package nl.eschouten.scraper.app

import nl.eschouten.scraper.model.scraperModels
import nl.eschouten.scraper.texts.useLanguage

object AppStart {
  /** The controller for this app. [demo] null = the real robot; otherwise demo words. */
  fun controller(store: Store, demo: String? = null, viaWidget: Boolean = false): RobotController {
    if (demo == null) return RobotController(Settings.load(store), store, viaWidget)
    val kv = words(demo)
    scraperModels.forEach { it.installDemo(kv) }
    val mem = MemoryStore()
    val settings = Settings.load(mem)
    kv["ui"]?.let { settings.language = it }
    if (kv["setup"] == "done") {
      val r = scraperModels.first().demoRobots.first()
      settings.updateRobot {
        it.copy(
          robotId = r.id, robotName = r.name, pin = "4321",
          routes = kv["routes"] ?: it.routes,
          routeNames = kv["names"]?.split(',')?.mapNotNull { p -> p.split(':').takeIf { s -> s.size == 2 }?.let { s -> s[0] to s[1].replace('+', ' ') } }?.toMap() ?: it.routeNames,
        )
      }
      settings.onboarded = true
    }
    useLanguage(settings.language)
    settings.save(mem)
    return RobotController(settings, mem, viaWidget)
  }

  fun words(spec: String): Map<String, String> =
    spec.split(' ').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
}
