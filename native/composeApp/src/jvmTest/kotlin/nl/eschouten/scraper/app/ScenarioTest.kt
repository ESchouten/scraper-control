// The shared user scenarios (scenarios/scenarios.json), run against the Compose app on the
// desktop with the demo robot. The iOS app runs the same list in XCUITest with the same test
// ids; scenarios/check.py makes sure neither misses one. Screenshots go to
// composeApp/build/screenshots/<lang>/ for the side-by-side page.
@file:OptIn(ExperimentalTestApi::class)

package nl.eschouten.scraper.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.unit.Density
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import nl.eschouten.scraper.model.scraperModels
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.ui.Platform
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object TestHost : AppHost {
  override val platform = Platform.Android // phone layout (widget tip and all)
}

private const val FIRST = "00:12:FF:51:4D:00"
private const val SECOND = "00:12:FF:51:4D:01"
private const val WAIT = 15_000L

// Test-only variants for the screenshot sheets (same switches as the iOS simulator settings):
// SCRAPER_THEME=dark, SCRAPER_FONT_SCALE=1.35 (larger text), SCRAPER_SHOTS=<dir> (default build/screenshots).
private val THEME = if (System.getenv("SCRAPER_THEME") == "dark") SystemTheme.Dark else SystemTheme.Light
private val FONT_SCALE = System.getenv("SCRAPER_FONT_SCALE")?.toFloatOrNull() ?: 1f
private val SHOTS = System.getenv("SCRAPER_SHOTS") ?: "build/screenshots"

@OptIn(InternalComposeUiApi::class)
class ScenarioTest {
  @AfterTest fun reset() = scraperModels.forEach { it.uninstallDemo() }

  /** The app with the demo robot, at phone size (412 x 892 dp, 2x). */
  private fun scenario(words: String, lang: String = "en", block: ComposeUiTest.(RobotController) -> Unit) =
    runDesktopComposeUiTest(824, 1784) {
      val c = AppStart.controller(MemoryStore(), "$words ui=$lang delay=15 scan=200")
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(2f, FONT_SCALE), LocalSystemTheme provides THEME) { App(c, TestHost) }
      }
      try {
        block(c)
      } finally {
        c.dispose()
      }
    }

  private fun ComposeUiTest.waitFor(tag: String) = waitUntilAtLeastOneExists(hasTestTag(tag), WAIT)
  private fun ComposeUiTest.waitGone(tag: String) = waitUntilDoesNotExist(hasTestTag(tag), WAIT)
  private fun ComposeUiTest.waitEnabled(tag: String) = waitUntilAtLeastOneExists(hasTestTag(tag) and isEnabled(), WAIT)
  /** [tag] shows [text], on itself or a child (a tappable element merges its children's text,
   *  as an iOS button does: both apps check the same way). */
  private fun ComposeUiTest.waitText(tag: String, text: String) =
    waitUntilAtLeastOneExists(hasTestTag(tag) and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))), WAIT)

  private fun ComposeUiTest.tap(tag: String) {
    waitFor(tag)
    onAllNodesWithTag(tag).onFirst().performClick()
  }

  /** Scroll the screen's list until [tag] is there, then tap it. */
  private fun ComposeUiTest.tapInList(tag: String) {
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
    tap(tag)
  }

  private fun ComposeUiTest.slide() {
    waitFor("slide-thumb")
    onNodeWithTag("slide-thumb").performTouchInput { swipe(center, Offset(center.x + 2000f, center.y), 400) }
  }

  private fun ComposeUiTest.shot(id: String, lang: String) {
    waitForIdle()
    val img = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
    val f = File("$SHOTS/$lang/$id.png")
    f.parentFile.mkdirs()
    ImageIO.write(img, "png", f)
  }

  private fun ComposeUiTest.onboardTo(pin: String) {
    tap("onboarding-start")
    tap("found-$FIRST")
    waitEnabled("onboarding-next")
    tap("onboarding-next")
    waitFor("onboarding-pin-field")
    onNodeWithTag("onboarding-pin-field").performTextInput(pin)
    tap("onboarding-test")
    waitFor("onboarding-result")
  }

  // ---- scenarios ----

  @Test fun onboarding_complete() = scenario("") { c ->
    onboardTo("4321")
    tap("onboarding-done")
    waitFor("home")
    assertTrue(c.state.value.onboarded)
    waitText("fact-planning", tr.on)
  }

  @Test fun onboarding_wrong_pin() = scenario("") { c ->
    onboardTo("0000")
    waitText("onboarding-result", tr.thatFailed)
    assertFalse(c.state.value.onboarded)
  }

  @Test fun home_status() = scenario("setup=done") {
    waitText("home-pill", tr.notConnectedYet)
    tap("home-pill")
    waitText("fact-planning", tr.on)
    waitText("fact-next", "21:00")
    waitText("fact-battery", "13.5 V")
  }

  @Test fun route_dry_run() = scenario("setup=done") {
    tap("route-A")
    tap("run-dry")
    waitFor("run-result")
    onNodeWithTag("run-result-title").assertTextEquals(tr.dryRunOk)
    waitFor("run-close")
    tap("run-for-real")
    waitFor("run-slide")
  }

  @Test fun route_start() = scenario("setup=done") {
    tap("route-B")
    slide()
    waitText("run-result-title", tr.routeStartedTitle)
    tap("run-done")
    waitGone("run-sheet")
    waitUntilAtLeastOneExists(hasText(tr.lastStarted(""), substring = true), WAIT)
  }

  @Test fun route_busy() = scenario("setup=done mode=manual") {
    tap("route-A")
    slide()
    waitText("run-result-title", tr.robotBusy)
    waitFor("run-close")
    assertTrue(onAllNodesWithTag("run-try-again").fetchSemanticsNodes().isEmpty())
  }

  @Test fun planning_toggle() = scenario("setup=done") {
    tap("home-pill")
    waitText("fact-planning", tr.on)
    waitEnabled("fact-planning") // editable once the read is done
    tap("fact-planning")
    tap("confirm-ok")
    waitText("fact-planning", tr.off)
  }

  @Test fun panel_keys() = scenario("setup=done planning=off") { c ->
    tapInList("screen-panel")
    tap("panel-connect")
    waitFor("panel-disconnect")
    waitFor("lcd")
    waitUntil(timeoutMillis = WAIT) { lcdText().isNotBlank() }
    val before = lcdText()
    waitEnabled("key-enter")
    tap("key-enter")
    waitUntil(timeoutMillis = WAIT) { lcdText() != before } // what the user sees, as on iOS
    tap("back")
    waitFor("home")
    waitUntil(timeoutMillis = WAIT) { !c.isConnected }
  }

  /** The text on the robot display, line by line (the iOS test reads the same as its label). */
  private fun ComposeUiTest.lcdText(): String =
    onAllNodes(hasAnyAncestor(hasTestTag("lcd")), useUnmergedTree = true).fetchSemanticsNodes()
      .joinToString("\n") { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "" }

  @Test fun settings_language() = scenario("setup=done", lang = "nl") {
    tap("home-settings")
    tapInList("row-language")
    tap("language-en")
    waitUntilAtLeastOneExists(hasText("Settings"), WAIT)
  }

  @Test fun route_rename_hide() = scenario("setup=done") {
    tap("home-edit")
    tapInList("row-route-A")
    waitFor("dialog-field")
    onNodeWithTag("dialog-field").performTextReplacement("Feed alley")
    tap("dialog-save")
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("row-route-D-switch"))
    tap("row-route-D-switch")
    tap("back")
    waitFor("home")
    waitUntilAtLeastOneExists(hasText("Feed alley"), WAIT)
    assertTrue(onAllNodesWithTag("route-D").fetchSemanticsNodes().isEmpty())
  }

  @Test fun read_routes() = scenario("setup=done") {
    tap("home-edit")
    tapInList("row-read-routes")
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("row-route-C"))
    waitText("row-route-C", tr.routeParts("1 2 3"))
  }

  @Test fun add_robot() = scenario("setup=done") {
    tap("home-settings")
    tapInList("row-add-robot")
    tap("found-$SECOND")
    onNodeWithTag("add-robot-name").performTextInput("Barn 2")
    onNodeWithTag("add-robot-pin").performTextInput("4321")
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("add-robot-save"))
    waitEnabled("add-robot-save")
    tap("add-robot-save")
    waitFor("home")
    waitUntilAtLeastOneExists(hasText("Barn 2"), WAIT)
    waitUntilAtLeastOneExists(hasText(tr.robotCount(2)), WAIT)
  }

  @Test fun logbook() = scenario("setup=done") {
    tap("route-A")
    tap("run-dry")
    tap("run-close")
    waitGone("run-sheet")
    tap("home-settings")
    tapInList("row-logbook")
    waitUntilAtLeastOneExists(hasText(tr.dryRun, substring = true), WAIT)
    tap("logbook-clear")
    tap("confirm-ok")
    waitFor("logbook-empty")
  }

  // ---- screenshots, both languages ----

  private fun screenshots(lang: String) {
    scenario("", lang) { shot("01-welcome", lang) }
    scenario("setup=done names=A:Voergang,B:Stal+2", lang) {
      tap("home-pill")
      waitText("fact-planning", tr.on)
      waitEnabled("route-A") // the read is done
      waitEnabled("home-pill")
      shot("02-home", lang)
    }
    scenario("setup=done names=A:Voergang", lang) {
      tap("route-A")
      waitFor("run-slide")
      shot("03-run-sheet", lang)
      tap("run-dry")
      waitFor("run-result")
      waitFor("run-close")
      shot("04-run-done", lang)
    }
    scenario("setup=done", lang) {
      tapInList("screen-panel")
      tap("panel-connect")
      waitFor("panel-disconnect")
      waitUntil(timeoutMillis = WAIT) { lcdText().isNotBlank() } // the robot's screen came in
      shot("05-panel", lang)
    }
    scenario("setup=done", lang) {
      tap("home-settings")
      waitFor("settings")
      shot("06-settings", lang)
    }
  }

  @Test fun screenshots_nl() = screenshots("nl")
  @Test fun screenshots_en() = screenshots("en")
}
