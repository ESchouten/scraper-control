// The Lely Discovery's own screen: the robot's control panel, live display and the same keys
// (model and flow: shared lely/Panel.kt). Connected only while open and the app is in front.
import Shared
import SwiftUI

struct PanelView: View {
  @EnvironmentObject var app: AppModel
  @Environment(\.scenePhase) private var phase
  @StateObject private var held: Held<PanelFlow>
  private var flow: PanelFlow { held.value }
  @StateObject private var st: Live<PanelState>

  init(controller: RobotController) {
    let f = PanelFlow(controller: controller)
    _held = StateObject(wrappedValue: Held(f))
    _st = StateObject(wrappedValue: Live(f.state.value as! PanelState) { cb in f.watch(onChange: cb) })
  }

  var body: some View {
    let v = flow.view(cs: app.state, st: st.value)
    ScrollView {
      VStack(alignment: .leading, spacing: Space.l) {
        Lcd(lines: v.lines, highlighted: v.highlighted, dimmed: v.dimmed)
        VStack(spacing: 10) {
          ForEach(Array(v.keys.enumerated()), id: \.offset) { _, row in
            HStack(spacing: 10) {
              ForEach(row, id: \.testId) { k in key(k, enabled: v.keysEnabled) }
            }
          }
        }
        if let e = v.error { Callout(glyph: .bluetoothoff, text: e, tone: .error) }
        if let a = v.connect {
          Button { Task { try? await flow.connect() } } label: {
            HStack(spacing: Space.s) {
              if v.connecting { ProgressView().tint(.white) } else { Image(systemName: "antenna.radiowaves.left.and.right") }
              Text(a.label).font(.system(size: scaled(16), weight: .bold))
            }
            .frame(maxWidth: .infinity, minHeight: 54)
            .foregroundStyle(.white)
            .background(C.primary, in: RoundedRectangle(cornerRadius: Radii.m, style: .continuous))
          }
          .buttonStyle(.plain)
          .disabled(!a.enabled)
          .opacity(a.enabled ? 1 : 0.6)
          .testId(a.testId)
        }
        HStack(alignment: .top, spacing: Space.s) {
          Image(systemName: "info.circle").font(.system(size: scaled(14))).foregroundStyle(C.onSurfaceVariant)
          Text(v.info).font(.system(size: scaled(13))).foregroundStyle(C.onSurfaceVariant)
        }
      }
      .padding(.horizontal, Space.gutter).padding(.top, Space.xs).padding(.bottom, Space.xl)
      .frame(maxWidth: 460)
      .frame(maxWidth: .infinity)
    }
    .background(C.surface)
    .navigationTitle(v.title)
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .principal) {
        VStack(spacing: 0) {
          Text(v.title).font(.system(size: scaled(17), weight: .heavy))
          Text(v.robotName).font(.system(size: scaled(12), weight: .semibold)).foregroundStyle(C.onSurfaceVariant)
        }
      }
      if v.connected {
        ToolbarItem(placement: .navigationBarTrailing) {
          Button { Task { try? await flow.disconnect() } } label: {
            HStack(spacing: 6) {
              Dot(color: C.ok)
              Text(v.disconnectLabel).font(.system(size: scaled(13), weight: .semibold))
            }
          }
          .testId("panel-disconnect")
        }
      }
    }
    .screenId("panel")
    .onChange(of: phase) { p in
      Task {
        if p == .active { try? await flow.resume() } else if p == .background { try? await flow.pause() }
      }
    }
    .onDisappear { Task { try? await flow.disconnect() } }
  }

  private func key(_ k: PanelKey, enabled: Bool) -> some View {
    Button {
      flow.keyTapped(key: k.key) { app.show($0) }
    } label: {
      // A flexible shape sets the key's size (a third of the row, 1.25 : 1); the face sits on top.
      // aspectRatio on the face itself shrank every key to its icon inside the ScrollView.
      Color.clear
        .aspectRatio(1.25, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .overlay(face(k.key))
        .contentShape(Rectangle())
        .background(
          LinearGradient(colors: [Color(hex: 0xD8283F), Brand.red, Brand.redDeep], startPoint: .top, endPoint: .bottom),
          in: RoundedRectangle(cornerRadius: Radii.m, style: .continuous),
        )
        .shadow(color: .black.opacity(0.18), radius: 4, y: 3)
    }
    .buttonStyle(HoldStyle { pressed in
      if pressed {
        UISelectionFeedbackGenerator().selectionChanged()
        flow.keyDown(key: k.key) { app.show($0) }
      } else {
        flow.keyUp()
      }
    })
    .disabled(!enabled)
    .opacity(enabled ? 1 : 0.45)
    .accessibilityLabel(k.label)
    .testId(k.testId)
  }

  /// Holds the key down on the robot as long as the finger is on it, as on the robot's own panel
  /// (in manual control the robot drives while Start/Stop is held). The button's action catches
  /// a press too quick for isPressed to change.
  private struct HoldStyle: ButtonStyle {
    let onPressed: (Bool) -> Void
    func makeBody(configuration: Configuration) -> some View {
      configuration.label
        .brightness(configuration.isPressed ? -0.08 : 0)
        .onChange(of: configuration.isPressed) { pressed in onPressed(pressed) }
    }
  }

  @ViewBuilder private func face(_ key: RobotKey) -> some View {
    switch key {
    case .soft1, .soft2, .soft3: Circle().fill(.white).frame(width: 20, height: 20)
    case .up: Image(systemName: "triangle.fill").font(.system(size: 28)).foregroundStyle(.white)
    case .down: Image(systemName: "triangle.fill").font(.system(size: 28)).rotationEffect(.degrees(180)).foregroundStyle(.white)
    case .startstop:
      VStack(spacing: 3) {
        Text("START").font(.system(size: 13, weight: .heavy)).kerning(0.5)
        Rectangle().frame(width: 44, height: 1.5)
        Text("STOP").font(.system(size: 13, weight: .heavy)).kerning(0.5)
      }
      .foregroundStyle(.white)
    case .back: Text("Esc").font(.system(size: 24, weight: .heavy)).foregroundStyle(.white)
    case .prog: Image(systemName: "list.bullet").font(.system(size: 28, weight: .semibold)).foregroundStyle(.white)
    default: Image(systemName: "return").font(.system(size: 28, weight: .semibold)).foregroundStyle(.white)
    }
  }
}
