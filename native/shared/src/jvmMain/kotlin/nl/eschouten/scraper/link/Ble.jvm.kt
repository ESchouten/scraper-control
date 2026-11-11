package nl.eschouten.scraper.link

import com.juul.kable.Peripheral

actual fun bluetoothProblem(): BluetoothProblem? = null

/** Desktop (btleplug) needs a scan first. */
internal actual fun peripheralById(id: String): Peripheral? = null

internal actual suspend fun afterConnect(p: Peripheral) {}
