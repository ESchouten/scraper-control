// Start a route without opening the app: widget buttons (iOS 17+), Siri and Shortcuts.
// In both the app and the widget extension. The work is the shared WidgetBridge.tap
// (connect, start with every safety rule, disconnect), the same as the Android widget.
import AppIntents
import Foundation
import Shared
import UserNotifications
import WidgetKit

let appGroup = "group.nl.eschouten.scraper"

/// The store the app and its widgets share (App Group); also sets the app's language.
func widgetStore() -> DefaultsStore {
  let s = DefaultsStore(suiteName: appGroup)
  _ = Shared.Settings.Companion.shared.load(store: s)
  return s
}

@available(iOS 17, *)
struct RouteIntent: AppIntent {
  static var title: LocalizedStringResource = "Start route"
  static var description = IntentDescription("Connects to the robot, starts a manual route and disconnects.")

  @Parameter(title: "Route (A-D)") var route: String
  @Parameter(title: "Robot") var robot: String

  init() {
    route = "A"
    robot = ""
  }

  init(route: String, robot: String) {
    self.route = route
    self.robot = robot
  }

  func perform() async throws -> some IntentResult & ProvidesDialog {
    let store = widgetStore()
    let robotUid: String? = robot.isEmpty ? nil : robot
    let o = try await WidgetBridge.shared.tap(store: store, robotUid: robotUid, route: route) {
      WidgetCenter.shared.reloadAllTimelines()
    }
    // A widget button shows no dialog: a start that did not work also comes as a notification.
    if let n = WidgetBridge.shared.notice(store: store, robotUid: robotUid, route: route, out: o) {
      await notifyFailure(n)
    }
    return .result(dialog: IntentDialog(stringLiteral: o.message.isEmpty ? TextsKt.tr.routeStarted(title: route) : o.message))
  }
}

/// A failed widget start as a notification. The app asks for permission once set up; until
/// then a quiet (provisional) one, which lands in the notification centre.
func notifyFailure(_ n: WidgetNotice) async {
  let center = UNUserNotificationCenter.current()
  _ = try? await center.requestAuthorization(options: [.alert, .sound, .provisional])
  let content = UNMutableNotificationContent()
  content.title = n.title
  content.body = n.body
  content.sound = .default
  try? await center.add(UNNotificationRequest(identifier: "widget-failure", content: content, trigger: nil))
}

/// The app asks once, when set up (not for the demo robot), so failed widget starts can alert.
func askNotificationsOnce() {
  let key = "asked.notifications"
  let d = UserDefaults.standard
  if d.bool(forKey: key) { return }
  d.set(true, forKey: key)
  UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
}

/// "Start route A with Scraper Control" in Siri and Shortcuts.
@available(iOS 17, *)
struct RouteShortcuts: AppShortcutsProvider {
  static var appShortcuts: [AppShortcut] {
    AppShortcut(
      intent: RouteIntent(),
      phrases: ["Start a route with \(.applicationName)", "Start route with \(.applicationName)"],
      shortTitle: "Start route",
      systemImageName: "play.circle",
    )
  }
}
