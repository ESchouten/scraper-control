// Logbook, settings, a robot's settings, the device picker and adding a robot
// (models: shared ui/Lists.kt, ui/SettingsScreens.kt, ui/Onboarding.kt).
import Shared
import SwiftUI

/// Scroll view with the app's background and an inline title: the frame of pushed screens.
struct Pushed<Content: View>: View {
  let title: String
  let testId: String
  @ViewBuilder var content: () -> Content
  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 0) { content() }
        .padding(.horizontal, Space.gutter).padding(.bottom, Space.xxl)
        .frame(maxWidth: 640).frame(maxWidth: .infinity)
    }
    .background(C.surface)
    .navigationTitle(title)
    .navigationBarTitleDisplayMode(.inline)
    .screenId(testId)
  }
}

// ---- logbook ----

struct LogbookView: View {
  @EnvironmentObject var app: AppModel
  @State private var ask = false
  var body: some View {
    let v = ListsKt.logbookScreen(s: app.state, now: app.now)
    Pushed(title: v.title, testId: "logbook") {
      if v.days.isEmpty {
        VStack(spacing: Space.s) {
          Glyph.logbook.image.font(.system(size: scaled(48))).foregroundStyle(C.onSurfaceVariant).padding(.bottom, Space.s)
          Text(v.emptyTitle).font(.system(size: scaled(22), weight: .bold)).multilineTextAlignment(.center).testId("logbook-empty")
          Text(v.emptyHelp).foregroundStyle(C.onSurfaceVariant).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.top, 96)
      }
      ForEach(v.days, id: \.title) { d in
        SectionHeader(d.title)
        GroupCard {
          ForEach(Array(d.rows.enumerated()), id: \.offset) { i, r in
            if i > 0 { Hairline() }
            HStack(spacing: Space.l) {
              if let route = r.route {
                RouteBadge(letter: route, size: 36, solid: !r.muted, muted: r.muted)
              } else {
                let (fg, bg) = toneColors(r.tone)
                IconTile(glyph: r.glyph, color: fg, background: bg)
              }
              VStack(alignment: .leading, spacing: 2) {
                Text(r.title).font(.system(size: scaled(16), weight: .semibold))
                HStack(spacing: 4) {
                  if r.route != nil { r.glyph.image.font(.system(size: scaled(11), weight: .bold)).foregroundStyle(toneColor(r.tone)) }
                  Text(r.subtitle).font(.system(size: scaled(13.5))).foregroundStyle(C.onSurfaceVariant)
                }
              }
              Spacer()
              Text(r.time).font(.system(size: scaled(15), weight: .semibold)).monospacedDigit().foregroundStyle(C.onSurfaceVariant)
            }
            .padding(.horizontal, Space.l).padding(.vertical, Space.m)
            .accessibilityElement(children: .combine)
          }
        }
      }
    }
    .toolbar {
      if !v.days.isEmpty {
        Button { ask = true } label: { Image(systemName: "trash") }.accessibilityLabel(v.clearLabel).testId("logbook-clear")
      }
    }
    .confirm(v.clearConfirm, isPresented: $ask) { Actions.shared.clearLogbook(c: app.controller) }
  }
}

// ---- settings ----

/// A settings row from the shared model.
struct SettingsRow<Trailing: View>: View {
  let r: Shared.Row
  let onTap: (() -> Void)?
  @ViewBuilder var trailing: () -> Trailing

  var body: some View {
    // The trailing control (a Toggle) stays outside the row's Button: inside it, accessibility merges
    // both into one switch and a tap on the row also flips the switch.
    let main = HStack(spacing: Space.l) {
      if let route = r.route {
        RouteBadge(letter: route, size: 36, solid: r.routeShown, muted: !r.routeShown)
      } else if let g = r.glyph {
        IconTile(glyph: g, color: r.checked ? C.primary : C.onSurface, background: r.checked ? C.primaryContainer : C.container)
      } else {
        AppLogo(size: 36)
      }
      VStack(alignment: .leading, spacing: 2) {
        Text(r.title).font(.system(size: scaled(16), weight: .semibold)).foregroundStyle(C.onSurface)
        if !r.subtitle.isEmpty {
          Text(r.subtitle).font(.system(size: scaled(13.5))).foregroundStyle(C.onSurfaceVariant).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
        }
      }
      Spacer(minLength: 0)
      if Trailing.self == EmptyView.self {
        if r.busy {
          ProgressView()
        } else if r.checked {
          Image(systemName: "checkmark").foregroundStyle(C.primary)
        } else if onTap != nil && r.enabled {
          Chevron()
        }
      }
    }
    .padding(.leading, Space.l).padding(.trailing, Trailing.self == EmptyView.self ? Space.l : Space.s).padding(.vertical, 12)
    .contentShape(Rectangle())
    HStack(spacing: 0) {
      if let onTap, r.enabled {
        Button(action: onTap) { main }.buttonStyle(.plain).testId(r.testId)
      } else {
        main.testId(r.testId)
      }
      if Trailing.self != EmptyView.self {
        trailing().padding(.trailing, Space.l)
      }
    }
  }
}

extension SettingsRow where Trailing == EmptyView {
  init(r: Shared.Row, onTap: (() -> Void)?) { self.init(r: r, onTap: onTap) { EmptyView() } }
}

struct SectionView<RowView: View>: View {
  let sec: Shared.Section
  @ViewBuilder var row: (Shared.Row) -> RowView
  var body: some View {
    SectionHeader(sec.title)
    GroupCard {
      ForEach(Array(sec.rows.enumerated()), id: \.element.testId) { i, r in
        if i > 0 { Hairline() }
        row(r)
      }
    }
    if let f = sec.footer {
      Text(f).font(.system(size: scaled(13))).foregroundStyle(C.onSurfaceVariant).padding(.horizontal, Space.xs).padding(.top, Space.s)
    }
  }
}

struct SettingsView: View {
  @EnvironmentObject var app: AppModel
  @State private var pickLanguage = false
  @State private var askReset = false
  var body: some View {
    let v = SettingsScreensKt.settingsScreen(s: app.state, now: app.now, platform: app.platform)
    Pushed(title: v.title, testId: "settings") {
      ForEach(v.sections, id: \.title) { sec in
        SectionView(sec: sec) { r in SettingsRow(r: r, onTap: tap(r)) }
      }
    }
    .confirmationDialog(v.languageTitle, isPresented: $pickLanguage, titleVisibility: .visible) {
      ForEach(v.languages, id: \.id) { l in
        Button(l.selected ? "✓ " + l.label : l.label) {
          Task { try? await Actions.shared.setLanguage(c: app.controller, code: l.id) }
        }
        .testId("language-" + (l.id.isEmpty ? "phone" : l.id))
      }
    }
    .confirm(v.resetConfirm, isPresented: $askReset) {
      app.home()
      Task { try? await Actions.shared.resetSetup(c: app.controller) }
    }
  }

  private func tap(_ r: Shared.Row) -> (() -> Void)? {
    if r.id.hasPrefix("robot-") { return { app.push(.robotSettings(String(r.id.dropFirst(6)))) } }
    switch r.id {
    case RowId.shared.ADD_ROBOT: return { app.push(.addRobot) }
    case RowId.shared.LOGBOOK: return { app.push(.logbook) }
    case RowId.shared.LANGUAGE: return { pickLanguage = true }
    case RowId.shared.RESET: return { askReset = true }
    default: return nil
    }
  }
}

// ---- one robot ----

struct RobotSettingsView: View {
  @EnvironmentObject var app: AppModel
  let uid: String
  @State private var dialog: DialogItem?
  @State private var askRemove = false

  struct DialogItem: Identifiable { let id: String }

  var body: some View {
    if let v = SettingsScreensKt.robotSettingsScreen(s: app.state, uid: uid), let p = app.state.robots.first(where: { $0.uid == uid }) {
      Pushed(title: v.title, testId: "robot-settings") {
        ForEach(v.sections, id: \.title) { sec in
          SectionView(sec: sec) { r in
            if let on = r.toggle?.boolValue, let route = r.route {
              SettingsRow(r: r, onTap: { dialog = DialogItem(id: r.id) }) {
                Toggle("", isOn: Binding(get: { on }, set: { x in Task { try? await Actions.shared.showRoute(c: app.controller, uid: uid, route: route, shown: x) } }))
                  .labelsHidden().tint(C.primary).testId(r.testId + "-switch")
              }
            } else {
              SettingsRow(r: r, onTap: tap(r))
            }
          }
        }
        if let a = v.remove {
          ActionButton(a: a, glyph: .delete_, danger: true) { askRemove = true }.padding(.top, Space.xl)
        }
      }
      .sheet(item: $dialog) { d in
        switch d.id {
        case RowId.shared.NAME:
          TextInputSheet(d: v.nameDialog) { x in Task { try? await Actions.shared.rename(c: app.controller, uid: uid, name: x) } }
        case RowId.shared.PIN:
          TextInputSheet(d: SettingsScreensKt.pinDialog(p: p), help: p.type.pinHelp) { x in Task { try? await Actions.shared.setPin(c: app.controller, uid: uid, pin: x) } }
        default:
          let r = String(d.id.dropFirst(6))
          TextInputSheet(d: SettingsScreensKt.routeNameDialog(p: p, r: r)) { x in Task { try? await Actions.shared.renameRoute(c: app.controller, uid: uid, route: r, name: x) } }
        }
      }
      .confirm(v.removeConfirm, isPresented: $askRemove) {
        app.back()
        Task { try? await Actions.shared.removeRobot(c: app.controller, uid: uid) }
      }
    }
  }

  private func tap(_ r: Shared.Row) -> (() -> Void)? {
    switch r.id {
    case RowId.shared.NAME, RowId.shared.PIN: return { dialog = DialogItem(id: r.id) }
    case RowId.shared.DEVICE: return { app.push(.chooseDevice(uid)) }
    case RowId.shared.TEST: return { Task { app.show(try? await Actions.shared.testConnection(c: app.controller, uid: uid)) } }
    case RowId.shared.READ_ROUTES: return { Task { app.show(try? await Actions.shared.readRoutes(c: app.controller, uid: uid)) } }
    default: return r.route != nil ? { dialog = DialogItem(id: r.id) } : nil
    }
  }
}

struct ChooseDeviceView: View {
  @EnvironmentObject var app: AppModel
  @StateObject private var held: Held<ScanFlow?>
  private var flow: ScanFlow? { held.value }
  let uid: String

  init(controller: RobotController, uid: String) {
    self.uid = uid
    _held = StateObject(wrappedValue: Held(SettingsScreensKt.deviceScan(s: controller.state.value as! ControllerState, uid: uid, platform: .ios)))
  }

  var body: some View {
    Pushed(title: tr.bluetooth, testId: "choose-device") {
      if let flow {
        ScanView(flow: flow) { id in
          flow.select(id: id)
          guard let f = flow.selected else { return }
          Task {
            try? await Actions.shared.chooseDevice(c: app.controller, uid: uid, f: f)
            app.back()
          }
        }
        .padding(.top, Space.l)
      }
    }
  }
}

// ---- add a robot ----

struct AddRobotView: View {
  @EnvironmentObject var app: AppModel
  @StateObject private var held: Held<AddRobotFlow>
  private var flow: AddRobotFlow { held.value }
  @StateObject private var st: Live<AddRobotState>
  @StateObject private var scan: Live<ScanState>

  init(controller: RobotController) {
    let f = AddRobotFlow(controller: controller, platform: .ios)
    _held = StateObject(wrappedValue: Held(f))
    _st = StateObject(wrappedValue: Live(f.state.value as! AddRobotState) { cb in f.watch(onChange: cb) })
    _scan = StateObject(wrappedValue: Live(f.scan.state.value as! ScanState) { cb in f.scan.watch(onChange: cb) })
  }

  var body: some View {
    let v = flow.page(st: st.value, scan: scan.value)
    Pushed(title: v.title, testId: "add-robot") {
      Text(v.help).font(.system(size: scaled(15))).foregroundStyle(C.onSurfaceVariant).padding(.top, Space.s)
      if v.types.count > 1 {
        SectionHeader(v.typeLabel)
        Picker(v.typeLabel, selection: Binding(get: { st.value.modelId }, set: { flow.setModel(id: $0) })) {
          ForEach(v.types, id: \.id) { t in Text(t.label).tag(t.id) }
        }
        .pickerStyle(.segmented)
      }
      SectionHeader(tr.bluetooth)
      ScanView(flow: flow.scan) { id in flow.scan.select(id: id) }
      SectionHeader(v.detailsTitle)
      VStack(spacing: Space.m) {
        TextField(v.nameHint, text: Binding(get: { st.value.label }, set: { flow.setLabel(v: $0) }))
          .padding(.horizontal, 16).frame(minHeight: 56).card().testId("add-robot-name")
        SecureField(v.pinLabel, text: Binding(get: { st.value.pin }, set: { flow.setPin(v: $0.filter(\.isNumber)) }))
          .keyboardType(.numberPad)
          .padding(.horizontal, 16).frame(minHeight: 56).card().testId("add-robot-pin")
        Text(v.pinHelp).font(.system(size: scaled(13))).foregroundStyle(C.onSurfaceVariant).frame(maxWidth: .infinity, alignment: .leading)
      }
      ActionButton(a: v.add) {
        Task {
          let m = try? await flow.save()
          app.show(m)
          app.home()
        }
      }
      .padding(.top, Space.xl)
    }
  }
}
