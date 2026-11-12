package nl.eschouten.scraper.link

import com.juul.kable.Peripheral
import com.juul.kable.PlatformScanner
import com.juul.kable.Scanner
import nl.eschouten.scraper.core.RobotLink

actual fun bluetoothProblem(): BluetoothProblem? = null

/** Desktop (btleplug) needs a scan first. */
internal actual fun peripheralById(id: String): Peripheral? = null

internal actual fun robotScanner(): PlatformScanner = Scanner()

internal actual suspend fun afterConnect(p: Peripheral) {}

/** No classic Bluetooth serial here: robots that only speak it need Android. */
internal actual suspend fun findClassicRobots(spec: LinkSpec.BtSerial): List<FoundRobot> = emptyList()

internal actual fun btSerialLink(spec: LinkSpec.BtSerial, id: String): RobotLink? = null
