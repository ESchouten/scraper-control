// The driver's list of links, and the stream link (classic Bluetooth) over fake streams.
package nl.eschouten.scraper.link

import java.io.Closeable
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LinkTest {
  @Test fun theDriverListsBleFirstThenClassic() {
    assertEquals(listOf(LinkSpec.BLE, LinkSpec.BT), driverLinks.map { it.kind })
    val bt = driverLinks[1] as LinkSpec.BtSerial
    assertEquals("00001101-0000-1000-8000-00805f9b34fb", bt.service)
    assertEquals((driverLinks[0] as LinkSpec.BleSerial).addressPrefix, bt.addressPrefix)
  }

  @Test fun unknownKindsAreLeftOut() {
    val l = LinkSpec.parseList("""[{"kind":"wifi","host":"x"},{"kind":"bt-serial","service":"s","addressPrefix":"00:AB"}]""")
    assertEquals(listOf(LinkSpec.BtSerial("s", "00:ab")), l)
  }

  @Test fun streamLinkCarriesBytesBothWaysAndCloses() = runBlocking {
    val fromRobot = PipedOutputStream()
    val toRobot = PipedInputStream()
    val input = PipedInputStream(fromRobot)
    val output = PipedOutputStream(toRobot)
    val link = StreamLink("00:12:FF:00:00:01") { StreamLink.Streams(input, output, Closeable { fromRobot.close() }) }
    link.connect()
    fromRobot.write(byteArrayOf(1, 2, 3))
    fromRobot.flush()
    assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(2_000) { link.notifications.first() })
    link.write(byteArrayOf(9, 8))
    assertContentEquals(byteArrayOf(9, 8), ByteArray(2).also { toRobot.read(it) })
    fromRobot.close() // the robot goes away
    withTimeout(2_000) { link.awaitClosed() }
    link.disconnect()
  }
}
