// How a robot is reached, as its driver describes it (scraper_link_json, core/scraper_c.h).
// Each kind is implemented once per platform; a driver that talks another way (another BLE
// service, wifi) adds a kind here and its link next to BleSerialLink.
package nl.eschouten.scraper.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.eschouten.scraper.core.RobotLink
import nl.eschouten.scraper.core.nativeLinkJson

sealed class LinkSpec {
  /** A serial BLE service: write to [rx] without response, notifications from [tx]. */
  data class BleSerial(val service: String, val rx: String, val tx: String, val addressPrefix: String) : LinkSpec()

  companion object {
    fun parse(json: String): LinkSpec {
      val o: JsonObject = Json.parseToJsonElement(json).jsonObject
      fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
      return when (val kind = s("kind")) {
        "ble-serial" -> BleSerial(s("service"), s("rx"), s("tx"), s("addressPrefix").lowercase())
        else -> error("link kind '$kind' is not implemented in the apps yet")
      }
    }
  }
}

/** How this build's robot driver is reached. */
val driverLink: LinkSpec by lazy { LinkSpec.parse(nativeLinkJson()) }

/** Robots of this kind nearby, strongest first. [name]: the advertised name, if known. */
suspend fun LinkSpec.scan(name: String? = null): List<FoundRobot> = when (this) {
  is LinkSpec.BleSerial -> findRobots(this, name)
}

/** A link to the robot remembered as [id]. */
fun LinkSpec.open(id: String, name: String? = null): RobotLink = when (this) {
  is LinkSpec.BleSerial -> BleSerialLink(this, id, name)
}
