// Every scraper type the apps support. To add one: implement ScraperModel and ScraperSession
// (see Scraper.kt) and list it here. The first one is the default.
package nl.eschouten.scraper.model

import nl.eschouten.scraper.lely.LelyDiscovery

val scraperModels: List<ScraperModel> = listOf(LelyDiscovery)

val defaultModelId: String get() = scraperModels.first().id

fun modelFor(id: String): ScraperModel = scraperModels.firstOrNull { it.id == id } ?: scraperModels.first()
