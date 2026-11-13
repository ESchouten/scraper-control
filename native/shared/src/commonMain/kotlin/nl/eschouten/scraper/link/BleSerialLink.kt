// The "ble-serial" link (see core/scraper_c.h) via Kable (Apache 2.0): Android, iOS and desktop
// (btleplug). A serial BLE service from the driver's LinkSpec: write to RX, notifications from
// TX. Finds the robot by the driver's address prefix, by name (Apple shows a UUID instead of a
// MAC) or by the service, like the Python and M5 code.
package nl.eschouten.scraper.link

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.PlatformScanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import nl.eschouten.scraper.core.RobotLink


/** A robot found nearby, over link [kind] (LinkSpec.kind; "" = the driver's preferred). */
data class FoundRobot(val id: String, val name: String, val rssi: Int, val kind: String = "")

/** Why Bluetooth cannot be used right now. */
enum class BluetoothProblem { Off, Denied, Unsupported }

/** Null when Bluetooth can be used (or its state is unknown). Per platform. */
expect fun bluetoothProblem(): BluetoothProblem?

/** A peripheral straight from a remembered id, or null if this platform needs a scan first. */
internal expect fun peripheralById(id: String): Peripheral?

/** A scanner for the short searches below. Per platform: Android's default listens only a tenth of the time. */
internal expect fun robotScanner(): PlatformScanner

/** After connecting: on Android a bigger MTU and a short connection interval. */
internal expect suspend fun afterConnect(p: Peripheral)

private fun LinkSpec.BleSerial.matches(a: Advertisement, name: String?): Boolean {
  if (addressPrefix.isNotEmpty() && a.identifier.toString().lowercase().startsWith(addressPrefix)) return true
  val n = (a.name ?: a.peripheralName ?: "").lowercase()
  if (!name.isNullOrEmpty() && n.contains(name.lowercase())) return true
  return Uuid.parse(service) in a.uuids
}

/** Scan for robots of [spec] for [timeoutMs]; strongest signal first. [name]: the robot's
 *  advertised name; needed on iOS/macOS. */
suspend fun findRobots(spec: LinkSpec.BleSerial, name: String? = null, timeoutMs: Long = 5_000): List<FoundRobot> {
  val found = mutableMapOf<String, FoundRobot>()
  withTimeoutOrNull(timeoutMs) {
    robotScanner().advertisements.collect { a ->
      if (spec.matches(a, name)) {
        val id = a.identifier.toString()
        found[id] = FoundRobot(id, a.name ?: a.peripheralName ?: "", a.rssi, spec.kind)
      }
    }
  }
  return found.values.sortedByDescending { it.rssi }
}

class BleSerialLink(
  private val spec: LinkSpec.BleSerial,
  override val id: String,
  private val name: String? = null,
  private val connectTimeoutMs: Long = 10_000,
) : RobotLink {
  private val rx = characteristicOf(Uuid.parse(spec.service), Uuid.parse(spec.rx)) // write to robot
  private val tx = characteristicOf(Uuid.parse(spec.service), Uuid.parse(spec.tx)) // notifications
  private var peripheral: Peripheral? = null
  private val frames = Channel<ByteArray>(Channel.UNLIMITED)
  private val closed = CompletableDeferred<Unit>()

  override val notifications: Flow<ByteArray> = frames.receiveAsFlow()

  override suspend fun connect() {
    val p = peripheralById(id) ?: run {
      // scan for it: by id, else by name
      val adv = withTimeoutOrNull(8_000) {
        robotScanner().advertisements.firstOrNull { a -> a.identifier.toString() == id || (spec.matches(a, name) && !name.isNullOrEmpty()) }
      } ?: throw IllegalStateException("robot $id not found")
      Peripheral(adv)
    }
    peripheral = p
    val conn = withTimeout(connectTimeoutMs) { p.connect() }
    p.scope.launch {
      p.state.drop(1).first { it is State.Disconnected }
      closed.complete(Unit)
    }
    runCatching { afterConnect(p) }
    val subscribed = CompletableDeferred<Unit>()
    conn.launch {
      p.observe(tx, onSubscription = { subscribed.complete(Unit) }).collect { frames.trySend(it) }
    }
    withTimeout(5_000) { subscribed.await() }
  }

  override suspend fun awaitClosed() = closed.await()

  override suspend fun write(frame: ByteArray) {
    val p = peripheral ?: return
    p.write(rx, frame, WriteType.WithoutResponse)
  }

  override suspend fun disconnect() {
    val p = peripheral
    peripheral = null
    if (p != null) {
      runCatching { withTimeoutOrNull(3_000) { p.disconnect() } }
      runCatching { p.close() }
    }
    closed.complete(Unit)
  }
}
