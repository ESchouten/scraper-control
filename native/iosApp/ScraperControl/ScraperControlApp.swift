// The iOS app: SwiftUI screens on the shared Kotlin module (Shared.framework). What each
// screen shows and does comes from the shared screen models (ui/), the same ones the Compose
// app draws; this app only lays them out the iOS way.
//
// Demo robot (UI tests, screenshots): environment SCRAPER_DEMO="setup=done ui=en", or the
// launch argument -demo "<words>" (see shared app/AppStart.kt).
import Shared
import SwiftUI

/// The shared texts (same as the Compose app): `tr.cancel`, `tr.routeStarted(title: "A")`.
var tr: Tr { TextsKt.tr }

@MainActor final class AppModel: ObservableObject {
  let controller: RobotController
  let platform = Platform.ios
  let isDemo: Bool
  @Published var state: ControllerState
  @Published var toast: String?
  @Published var path: [Screen] = []
  private var handle: (any Cancellable)?
  private var toastTask: Task<Void, Never>?

  init() {
    let args = ProcessInfo.processInfo.arguments
    var demo = ProcessInfo.processInfo.environment["SCRAPER_DEMO"]
    if let i = args.firstIndex(of: "-demo") { demo = i + 1 < args.count ? args[i + 1] : "" }
    isDemo = demo != nil
    controller = AppStart.shared.controller(store: DefaultsStore(suiteName: "group.nl.eschouten.scraper"), demo: demo, viaWidget: false)
    state = controller.state.value as! ControllerState
    handle = controller.watch { [weak self] s in self?.state = s }
  }

  func show(_ message: String?) {
    guard let m = message, !m.isEmpty else { return }
    toast = m
    toastTask?.cancel()
    toastTask = Task {
      try? await Task.sleep(nanoseconds: 3_000_000_000)
      if !Task.isCancelled { toast = nil }
    }
  }

  func push(_ s: Screen) { path.append(s) }
  func back() { if !path.isEmpty { path.removeLast() } }
  func home() { path = [] }
}

/// The screens above home.
enum Screen: Hashable {
  case settings, logbook, panel, addRobot
  case robotSettings(String)
  case chooseDevice(String)
}

@main
struct ScraperControlApp: App {
  @StateObject private var app = AppModel()
  var body: some Scene {
    WindowGroup {
      RootView().environmentObject(app).tint(C.primary)
    }
  }
}

struct RootView: View {
  @EnvironmentObject var app: AppModel
  var body: some View {
    ZStack(alignment: .bottom) {
      if !app.state.onboarded {
        OnboardingView(controller: app.controller)
      } else {
        NavigationStack(path: $app.path) {
          HomeView()
            .navigationDestination(for: Screen.self) { s in
              switch s {
              case .settings: SettingsView()
              case .logbook: LogbookView()
              case .panel: PanelView(controller: app.controller)
              case .addRobot: AddRobotView(controller: app.controller)
              case .robotSettings(let uid): RobotSettingsView(uid: uid)
              case .chooseDevice(let uid): ChooseDeviceView(controller: app.controller, uid: uid)
              }
            }
        }
        .task { if !app.isDemo { askNotificationsOnce() } }
      }
      if let t = app.toast { ToastView(text: t).transition(.move(edge: .bottom).combined(with: .opacity)) }
    }
    .animation(.easeOut(duration: 0.2), value: app.toast)
    .background(C.surface.ignoresSafeArea())
  }
}
