package nl.eschouten.scraper.link

import com.juul.kable.Peripheral
import com.juul.kable.PlatformScanner
import com.juul.kable.Scanner

actual fun bluetoothProblem(): BluetoothProblem? = null

/** Desktop (btleplug) needs a scan first. */
internal actual fun peripheralById(id: String): Peripheral? = null

internal actual fun robotScanner(): PlatformScanner = Scanner()

internal actual suspend fun afterConnect(p: Peripheral) {}

