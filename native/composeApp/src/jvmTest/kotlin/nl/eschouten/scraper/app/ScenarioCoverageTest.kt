// Both apps run every scenario of scenarios/scenarios.json, with the same test ids: Compose a
// test per id (ScenarioTest.kt), iOS a test_<id> (iosApp/UITests/ScenarioTests.swift).
package nl.eschouten.scraper.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioCoverageTest {
  private fun ids(path: String, pattern: String): Set<String> =
    Regex(pattern).findAll(File("..", path).readText()).map { it.groupValues[1] }   // tests run in composeApp/
      .filterNot { it.startsWith("screenshots") }.toSet()

  @Test fun bothAppsCoverEveryScenario() {
    val scenarios = ids("scenarios/scenarios.json", """"id":\s*"(\w+)"""")
    assertEquals(scenarios, ids("composeApp/src/jvmTest/kotlin/nl/eschouten/scraper/app/ScenarioTest.kt", """@Test\s+fun\s+(\w+)\s*\("""), "Compose")
    assertEquals(scenarios, ids("iosApp/UITests/ScenarioTests.swift", """func\s+test_(\w+)\s*\("""), "iOS")
  }
}
