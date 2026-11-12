// Classic Bluetooth on Android, for robots that speak the serial port profile instead of BLE:
// found by a classic discovery next to the BLE scan, reached over an RFCOMM socket as a byte
// stream (StreamLink).
package nl.eschouten.scraper.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import nl.eschouten.scraper.app.AndroidPlatform
import nl.eschouten.scraper.core.RobotLink

/** How long a search listens for classic devices (an inquiry takes about 10 s in full). */
private const val DISCOVERY_MS = 8_000L

private fun adapter(c: Context): BluetoothAdapter? = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

private fun device(i: Intent): BluetoothDevice? =
  if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
  else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

@SuppressLint("MissingPermission") // bluetoothProblem() checks the permissions first
internal actual suspend fun findClassicRobots(spec: LinkSpec.BtSerial): List<FoundRobot> {
  if (!AndroidPlatform.isReady || bluetoothProblem() != null) return emptyList()
  val c = AndroidPlatform.context
  val adapter = adapter(c) ?: return emptyList()
  val found = ConcurrentHashMap<String, FoundRobot>()
  val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, i: Intent) {
      val d = device(i) ?: return
      // a robot that is LE only is the BLE scan's
      if (d.type == BluetoothDevice.DEVICE_TYPE_LE || !d.address.lowercase().startsWith(spec.addressPrefix)) return
      val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, (-127).toShort()).toInt()
      found[d.address] = FoundRobot(d.address, d.name.orEmpty(), rssi, spec.kind)
    }
  }
  val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
  if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(receiver, filter)
  try {
    adapter.startDiscovery()
    delay(DISCOVERY_MS)
  } finally {
    adapter.cancelDiscovery()
    c.unregisterReceiver(receiver)
  }
  return found.values.toList()
}

@SuppressLint("MissingPermission") // the session checks bluetoothProblem() before it opens a link
internal actual fun btSerialLink(spec: LinkSpec.BtSerial, id: String): RobotLink? {
  if (!AndroidPlatform.isReady) return null
  val adapter = adapter(AndroidPlatform.context) ?: return null
  return StreamLink(id) {
    adapter.cancelDiscovery() // a running discovery slows a connect down
    val socket = adapter.getRemoteDevice(id.uppercase()).createInsecureRfcommSocketToServiceRecord(UUID.fromString(spec.service))
    try {
      socket.connect()
    } catch (e: IOException) {
      runCatching { socket.close() }
      throw e
    }
    StreamLink.Streams(socket.inputStream, socket.outputStream, socket)
  }
}
