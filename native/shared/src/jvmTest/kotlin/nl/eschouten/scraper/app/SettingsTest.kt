package nl.eschouten.scraper.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
  @Test fun pinGoesToTheSecretStoreNotTheSettings() {
    val store = MemoryStore()
    val s = Settings.single(robotId = "00:12:FF:51:4D:00", pin = "4321")
    val uid = s.robot.uid
    s.save(store)
    assertFalse("4321" in store.getString("robots")!!)
    assertEquals("4321", store.getSecret("pin.$uid"))
    assertEquals("4321", Settings.load(store).robot.pin)
  }

  @Test fun plainPinFromAnOlderVersionIsMoved() {
    val old = """[{"uid":"abcd1234","robotId":"00:12:FF:51:4D:00","pin":"4321"}]"""
    val store = MemoryStore(mapOf("robots" to old))
    assertEquals("4321", Settings.load(store).robot.pin)
    assertFalse("4321" in store.getString("robots")!!)
    assertEquals("4321", store.getSecret("pin.abcd1234"))
  }

  @Test fun removedRobotLosesItsPin() {
    val store = MemoryStore()
    val s = Settings.single(pin = "4321")
    val second = s.add(RobotProfile(pin = "9999"))
    s.save(store)
    assertEquals("9999", store.getSecret("pin.${second.uid}"))
    s.remove(second.uid)
    s.save(store)
    assertNull(store.getSecret("pin.${second.uid}"))
    assertTrue(store.getSecret("pin.${s.robot.uid}") == "4321")
  }
}
