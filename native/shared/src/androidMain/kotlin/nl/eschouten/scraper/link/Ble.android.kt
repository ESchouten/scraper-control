package nl.eschouten.scraper.link

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.juul.kable.AndroidPeripheral
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral
import com.juul.kable.PlatformScanner
import com.juul.kable.Scanner
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

/** Listen all the time: the search lasts seconds and the app is on screen. Android's default
 *  (low power) listens a tenth of the time and can miss the robot. */
@OptIn(ObsoleteKableApi::class)
internal actual fun robotScanner(): PlatformScanner = Scanner {
  scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
}

internal actual suspend fun afterConnect(p: Peripheral) {
  (p as? AndroidPeripheral)?.requestMtu(247) // robot reports 236; bigger frames = fewer packets
}
