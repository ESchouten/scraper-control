package nl.eschouten.scraper.link

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import nl.eschouten.scraper.app.AndroidPlatform

actual fun bluetoothProblem(): BluetoothProblem? {
  if (!AndroidPlatform.isReady) return null
  val c = AndroidPlatform.context
  val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return BluetoothProblem.Unsupported
  if (Build.VERSION.SDK_INT >= 31) {
    val ok = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
      .all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    if (!ok) return BluetoothProblem.Denied
  }
  return if (adapter.isEnabled) null else BluetoothProblem.Off
}

/** Android connects straight to a remembered MAC address. */
internal actual fun peripheralById(id: String): Peripheral? = runCatching { Peripheral(id) }.getOrNull()

internal actual suspend fun afterConnect(p: Peripheral) {
  (p as? AndroidPeripheral)?.requestMtu(247) // robot reports 236; bigger frames = fewer packets
}
