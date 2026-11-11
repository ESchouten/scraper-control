package nl.eschouten.scraper.link

import com.juul.kable.Peripheral

/** iOS asks for Bluetooth permission itself; a refusal shows up as a failed connect. */
actual fun bluetoothProblem(): BluetoothProblem? = null

/** iOS: find the robot by a short scan (by its UUID, else by name). */
internal actual fun peripheralById(id: String): Peripheral? = null

internal actual suspend fun afterConnect(p: Peripheral) {}
