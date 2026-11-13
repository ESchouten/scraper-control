// How a robot is reached, as its driver describes it (scraper_links_json, core/scraper_c.h): a
// list of kinds, preferred first. Each kind is implemented once per platform, or not at all
// there (a scan skips it); a driver that talks another way adds a kind here and its link.
package nl.eschouten.scraper.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.eschouten.scraper.core.RobotLink
import nl.eschouten.scraper.core.nativeLinksJson

sealed class LinkSpec {
  abstract val kind: String

  /** A serial BLE service: write to [rx] without response, notifications from [tx]. */
  data class BleSerial(val service: String, val rx: String, val tx: String, val addressPrefix: String) : LinkSpec() {
    override val kind get() = BLE
  }

  /** Classic Bluetooth: an RFCOMM socket to [service], a byte stream both ways. */
  data class BtSerial(val service: String, val addressPrefix: String) : LinkSpec() {
    override val kind get() = BT
  }

  companion object {
    const val BLE = "ble-serial"
    const val BT = "bt-serial"

    /** The driver's list; a kind the apps do not know is left out. */
    fun parseList(json: String): List<LinkSpec> = Json.parseToJsonElement(json).jsonArray.mapNotNull { e ->
      val o = e.jsonObject
      fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
      when (s("kind")) {
        BLE -> BleSerial(s("service"), s("rx"), s("tx"), s("addressPrefix").lowercase())
        BT -> BtSerial(s("service"), s("addressPrefix").lowercase())
        else -> null
      }
    }
  }
}

/** How this build's robot driver is reached, preferred first. */
val driverLinks: List<LinkSpec> by lazy { LinkSpec.parseList(nativeLinksJson()) }

/** Classic Bluetooth robots nearby (by [spec]'s address prefix); empty where the platform has none. */
internal expect suspend fun findClassicRobots(spec: LinkSpec.BtSerial): List<FoundRobot>

/** A classic Bluetooth link to [id]; null where the platform has none. */
internal expect fun btSerialLink(spec: LinkSpec.BtSerial, id: String): RobotLink?

/** Robots nearby, strongest first: kind by kind in the driver's order, and the first kind that
 *  finds any is the answer. So a robot that speaks more than one goes over the preferred, and a
 *  classic search (which spoils a BLE scan running next to it) only runs when BLE found none.
 *  [name]: the advertised name, if known. */
suspend fun scanRobots(name: String? = null): List<FoundRobot> {
  for (spec in driverLinks) {
    val found = when (spec) {
      is LinkSpec.BleSerial -> findRobots(spec, name)
      is LinkSpec.BtSerial -> findClassicRobots(spec)
    }
    if (found.isNotEmpty()) return found.sortedByDescending { it.rssi }
  }
  return emptyList()
}

/** A link to the robot remembered as [id], found over [kind] ("" = the driver's preferred kind). */
fun openLink(kind: String, id: String, name: String? = null): RobotLink =
  when (val spec = driverLinks.firstOrNull { kind.isEmpty() || it.kind == kind } ?: error("link kind '$kind' is not known to this app")) {
    is LinkSpec.BleSerial -> BleSerialLink(spec, id, name)
    is LinkSpec.BtSerial -> btSerialLink(spec, id) ?: error("classic Bluetooth is not available on this device")
  }
