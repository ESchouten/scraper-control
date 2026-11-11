package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

/** Desktop development: a simulated robot instead of Bluetooth (Simulator.kt); null elsewhere. */
internal expect fun devLinkProvider(): LinkProvider?
