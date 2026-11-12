// The shared user scenarios (scenarios/scenarios.json), run against the SwiftUI app with the
// demo robot. The Compose app runs the same list (composeApp/src/jvmTest/.../ScenarioTest.kt)
// with the same test ids; composeApp's ScenarioCoverageTest makes sure neither misses one.
// Texts come from the same shared module (Shared.framework), so both check the same words.
// Screenshots are attachments; run-ui-tests.sh exports them from the test result.
import Shared
import XCTest

private let first = "00:12:FF:51:4D:00"
private let second = "00:12:FF:51:4D:01"
private let waitTime: TimeInterval = 15

final class ScenarioTests: XCTestCase {
  private var app: XCUIApplication!
  private var tr: Tr { TextsKt.tr }

  override func setUp() {
    continueAfterFailure = false
  }

  private func launch(_ words: String, lang: String = "en") {
    TextsKt.useLanguage(choice: lang)
    app = XCUIApplication()
    app.launchEnvironment["SCRAPER_DEMO"] = "\(words) ui=\(lang) delay=15 scan=200"
    app.launch()
  }

  private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }

  @discardableResult private func waitFor(_ id: String, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
    let e = el(id)
    let found = e.waitForExistence(timeout: waitTime)
    if !found { print("UI TREE when '\(id)' was missing:\n\(app.debugDescription)") }
    XCTAssertTrue(found, "no \(id)", file: file, line: line)
    return e
  }

  private func waitGone(_ id: String) {
    let p = NSPredicate(format: "exists == false")
    let x = expectation(for: p, evaluatedWith: el(id))
    wait(for: [x], timeout: waitTime)
  }

  private func waitEnabled(_ id: String) {
    let p = NSPredicate(format: "isEnabled == true")
    let x = expectation(for: p, evaluatedWith: waitFor(id))
    wait(for: [x], timeout: waitTime)
  }

  private func waitLabel(_ id: String, contains text: String) {
    let p = NSPredicate(format: "label CONTAINS %@", text)
    let x = expectation(for: p, evaluatedWith: waitFor(id))
    wait(for: [x], timeout: waitTime)
  }

  private func waitText(_ text: String) {
    let q = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    XCTAssertTrue(q.waitForExistence(timeout: waitTime), "no text \(text)")
  }

  private func tap(_ id: String) { waitFor(id).tap() }

  /// Scroll down until [id] is on screen, then tap it.
  private func tapInList(_ id: String) {
    let e = el(id)
    var n = 0
    // isHittable is already true when only the top of the element is on screen, while tap() aims at
    // its centre; so scroll until the whole element is inside the window, above the home bar.
    let visible = app.windows.firstMatch.frame.insetBy(dx: 0, dy: 40)
    while !(e.exists && e.isHittable && visible.contains(e.frame)) && n < 8 {
      app.swipeUp()
      n += 1
    }
    tap(id)
  }

  private func slide() {
    let thumb = waitFor("slide-thumb")
    let start = thumb.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
    start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 600, dy: 0)))
  }

  private func type(_ id: String, _ text: String, replace: Bool = false) {
    let f = waitFor(id)
    f.tap()
    if replace, let v = f.value as? String, !v.isEmpty {
      f.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: v.count))
    }
    f.typeText(text)
  }

  private func shot(_ id: String, _ lang: String) {
    Thread.sleep(forTimeInterval: 0.6) // let sheet and page transitions finish (else half-faded views)
    let png = XCUIScreen.main.screenshot().pngRepresentation
    let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
    a.name = "\(lang)-\(id)"
    a.lifetime = .keepAlways
    add(a)
  }

  private func onboard(pin: String) {
    tap("onboarding-start")
    tap("found-\(first)")
    waitEnabled("onboarding-next")
    tap("onboarding-next")
    type("onboarding-pin-field", pin)
    tap("onboarding-test")
    waitFor("onboarding-result")
  }

  // ---- scenarios ----

  func test_onboarding_complete() {
    launch("")
    onboard(pin: "4321")
    tap("onboarding-done")
    waitFor("home")
    waitLabel("fact-planning", contains: tr.on)
  }

  func test_onboarding_wrong_pin() {
    launch("")
    onboard(pin: "0000")
    waitLabel("onboarding-result", contains: tr.thatFailed)
  }

  func test_home_status() {
    launch("setup=done")
    waitLabel("home-pill", contains: tr.notConnectedYet)
    tap("home-pill")
    waitLabel("fact-planning", contains: tr.on)
    waitLabel("fact-next", contains: "21:00")
    waitLabel("fact-battery", contains: "13.5 V")
  }

  func test_route_dry_run() {
    launch("setup=done")
    tap("route-A")
    tap("run-dry")
    waitFor("run-result")
    waitLabel("run-result-title", contains: tr.dryRunOk)
    waitFor("run-close")
    tap("run-for-real")
    waitFor("run-slide")
  }

  func test_route_start() {
    launch("setup=done")
    tap("route-B")
    slide()
    waitLabel("run-result-title", contains: tr.routeStartedTitle)
    tap("run-done")
    waitGone("run-sheet")
    waitText(tr.lastStarted(time: ""))
  }

  func test_route_busy() {
    launch("setup=done mode=manual")
    tap("route-A")
    slide()
    waitLabel("run-result-title", contains: tr.robotBusy)
    waitFor("run-close")
    XCTAssertFalse(el("run-try-again").exists)
  }

  func test_planning_toggle() {
    launch("setup=done")
    tap("home-pill")
    waitLabel("fact-planning", contains: tr.on)
    waitEnabled("fact-planning") // editable once the read is done
    tap("fact-planning")
    app.alerts.buttons[tr.switchOff].firstMatch.tap()
    waitLabel("fact-planning", contains: tr.off)
  }

  func test_panel_keys() {
    launch("setup=done planning=off") // with planning on the robot ignores Enter
    tapInList("screen-panel")
    tap("panel-connect")
    waitFor("panel-disconnect")
    let lcd = waitFor("lcd")
    wait(for: [expectation(for: NSPredicate(format: "label != ''"), evaluatedWith: lcd)], timeout: waitTime)
    let before = lcd.label
    waitEnabled("key-enter")
    tap("key-enter")
    let p = NSPredicate(format: "label != %@", before)
    wait(for: [expectation(for: p, evaluatedWith: lcd)], timeout: waitTime)
    app.navigationBars.buttons.element(boundBy: 0).tap()
    waitFor("home")
  }

  func test_settings_language() {
    launch("setup=done", lang: "nl")
    tap("home-settings")
    tapInList("row-language")
    app.buttons["English"].firstMatch.tap()
    XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: waitTime))
  }

  func test_route_rename_hide() {
    launch("setup=done")
    tap("home-edit")
    tapInList("row-route-A")
    type("dialog-field", "Feed alley", replace: true)
    tap("dialog-save")
    tapInList("row-route-D-switch")
    app.navigationBars.buttons.element(boundBy: 0).tap()
    waitFor("home")
    waitText("Feed alley")
    XCTAssertFalse(el("route-D").exists)
  }

  func test_read_routes() {
    launch("setup=done")
    tap("home-edit")
    tapInList("row-read-routes")
    waitLabel("row-route-C", contains: tr.routeParts(p: "1 2 3"))
  }

  func test_add_robot() {
    launch("setup=done")
    tap("home-settings")
    tapInList("row-add-robot")
    tap("found-\(second)")
    type("add-robot-name", "Barn 2")
    type("add-robot-pin", "4321")
    tapInList("add-robot-save")
    waitFor("home")
    waitText("Barn 2")
    waitText(tr.robotCount(n: 2))
  }

  func test_logbook() {
    launch("setup=done")
    tap("route-A")
    tap("run-dry")
    tap("run-close")
    waitGone("run-sheet")
    tap("home-settings")
    tapInList("row-logbook")
    waitText(tr.dryRun)
    tap("logbook-clear")
    app.alerts.buttons[tr.clear].firstMatch.tap()
    waitFor("logbook-empty")
  }

  // ---- screenshots, both languages ----

  private func screenshots(_ lang: String) {
    launch("", lang: lang)
    shot("01-welcome", lang)
    app.terminate()
    launch("setup=done names=A:Voergang,B:Stal+2", lang: lang)
    tap("home-pill")
    waitLabel("fact-planning", contains: tr.on)
    waitEnabled("route-A") // the read is done
    waitEnabled("home-pill")
    shot("02-home", lang)
    app.terminate()
    launch("setup=done names=A:Voergang", lang: lang)
    tap("route-A")
    waitFor("run-slide")
    shot("03-run-sheet", lang)
    tap("run-dry")
    waitFor("run-close")
    shot("04-run-done", lang)
    app.terminate()
    launch("setup=done", lang: lang)
    tapInList("screen-panel")
    tap("panel-connect")
    waitFor("panel-disconnect")
    wait(for: [expectation(for: NSPredicate(format: "label != ''"), evaluatedWith: waitFor("lcd"))], timeout: waitTime) // the robot's screen came in
    shot("05-panel", lang)
    app.terminate()
    launch("setup=done", lang: lang)
    tap("home-settings")
    waitFor("settings")
    shot("06-settings", lang)
  }

  func test_screenshots_nl() { screenshots("nl") }
  func test_screenshots_en() { screenshots("en") }
}
