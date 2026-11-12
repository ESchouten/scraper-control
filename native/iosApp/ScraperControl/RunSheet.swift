// The start sheet of one route (model and flow: shared ui/RunSheet.kt), the robot switcher,
// and slide to confirm.
import Shared
import SwiftUI

struct RunSheet: View {
  @EnvironmentObject var app: AppModel
  @Environment(\.dismiss) private var dismiss
  @StateObject private var held: Held<RunFlow>
  private var flow: RunFlow { held.value }
  @StateObject private var st: Live<RunFlowState>
  @State private var editPin = false
  @State private var details = false

  init(controller: RobotController, route: String) {
    let f = RunFlow(controller: controller, route: route)
    _held = StateObject(wrappedValue: Held(f))
    _st = StateObject(wrappedValue: Live(f.state.value as! RunFlowState) { cb in f.watch(onChange: cb) })
  }

  var body: some View {
    let v = flow.view(cs: app.state, st: st.value)
    ScrollView {
      VStack(alignment: .leading, spacing: 0) {
        HStack(spacing: Space.l) {
          RouteBadge(letter: v.route, size: 56, solid: true)
          VStack(alignment: .leading, spacing: 2) {
            Text(v.title).font(.system(size: scaled(24), weight: .heavy)).kerning(-0.3).lineLimit(1)
            Text(v.subtitle).font(.system(size: scaled(15))).foregroundStyle(C.onSurfaceVariant).testId("run-subtitle")
          }
        }
        .padding(.bottom, Space.xl)
        switch v.phase {
        case .confirm: confirm(v)
        case .running: running(v)
        default: done(v)
        }
      }
      .padding(.horizontal, Space.xl).padding(.top, Space.xl).padding(.bottom, Space.xl)
      .animation(.easeOut(duration: 0.22), value: v.phase)
    }
    .background(C.containerLow)
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
    .interactiveDismissDisabled(!v.canClose)
    .screenId("run-sheet")
    .finishQuestion(app, active: !editPin)
    .sheet(isPresented: $editPin) {
      TextInputSheet(d: SettingsScreensKt.pinDialog(p: app.state.robot), help: app.state.model.pinHelp) { pin in
        Task {
          try? await Actions.shared.setPin(c: app.controller, uid: app.state.robot.uid, pin: pin)
          flow.back()
        }
      }
    }
  }

  private func start(dryRun: Bool) {
    Task {
      let o = try? await flow.start(dryRun: dryRun)
      UINotificationFeedbackGenerator().notificationOccurred(o?.ok == true ? .success : .error)
    }
  }

  private func confirm(_ v: RunSheetView) -> some View {
    VStack(alignment: .leading, spacing: 0) {
      ForEach(v.facts, id: \.text) { f in
        HStack(alignment: .top, spacing: Space.m) {
          IconTile(glyph: f.glyph)
          Text(f.text).font(.system(size: scaled(14.5))).foregroundStyle(C.onSurface).padding(.top, 7).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, Space.m)
      }
      SlideToConfirm(label: v.slideLabel) { start(dryRun: false) }.padding(.top, Space.m).testId("run-slide")
      Button { start(dryRun: true) } label: {
        Label(v.dryRunLabel, systemImage: Glyph.science.symbol).font(.system(size: scaled(16), weight: .bold)).frame(maxWidth: .infinity, minHeight: 48)
      }
      .padding(.top, Space.s)
      .testId("run-dry")
    }
  }

  private func running(_ v: RunSheetView) -> some View {
    VStack(alignment: .leading, spacing: Space.l) {
      if let p = v.progress { StepsProgress(p: p) }
      HStack(spacing: Space.s) {
        Glyph.phone.image.font(.system(size: scaled(14))).foregroundStyle(C.onSurfaceVariant)
        Text(v.keepOpen).font(.system(size: scaled(13))).foregroundStyle(C.onSurfaceVariant)
      }
    }
    .screenId("run-progress")
  }

  private func done(_ v: RunSheetView) -> some View {
    let (fg, bg) = toneColors(v.resultTone ?? .ok)
    return VStack(spacing: 0) {
      (v.resultGlyph ?? .check).image.font(.system(size: scaled(40), weight: .semibold)).foregroundStyle(fg)
        .frame(width: 84, height: 84).background(bg, in: Circle())
        .transition(.scale(scale: 0.6).combined(with: .opacity))
      Text(v.resultTitle).font(.system(size: scaled(24), weight: .heavy)).multilineTextAlignment(.center).padding(.top, Space.l).testId("run-result-title")
      Text(v.resultBody).font(.system(size: scaled(15))).foregroundStyle(C.onSurfaceVariant).multilineTextAlignment(.center).padding(.top, Space.s)
      if !v.detailsTitle.isEmpty {
        DisclosureGroup(isExpanded: $details) {
          VStack(alignment: .leading, spacing: 10) {
            ForEach(v.details, id: \.self) { d in stepLine("checkmark", C.ok, d) }
            if let s = v.stoppedAt { stepLine("xmark", C.error, s) }
            if let m = v.detailMessage { Text(m).font(.system(size: scaled(13))).foregroundStyle(C.onSurfaceVariant) }
          }
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(.top, Space.s)
        } label: {
          Text(v.detailsTitle).font(.system(size: scaled(14), weight: .bold)).foregroundStyle(C.onSurfaceVariant)
        }
        .padding(.top, Space.m)
        .screenId("run-details")
      }
      VStack(spacing: Space.s) {
        ForEach(v.actions, id: \.testId) { a in
          ActionButton(a: a, glyph: a.action == .startforreal ? .play : nil) {
            switch a.action {
            case .startforreal, .tryagain: flow.back()
            case .changepin: editPin = true
            default: dismiss()
            }
          }
        }
      }
      .padding(.top, Space.xl)
    }
    .frame(maxWidth: .infinity)
    .screenId("run-result")
  }

  private func stepLine(_ icon: String, _ color: Color, _ text: String) -> some View {
    HStack(spacing: Space.m) {
      Image(systemName: icon).font(.system(size: scaled(14), weight: .bold)).foregroundStyle(color).frame(width: 18)
      Text(text).font(.system(size: scaled(14))).foregroundStyle(C.onSurfaceVariant)
    }
  }
}

/// Headline, progress bar and the steps so far (also onboarding's PIN test).
struct StepsProgress: View {
  let p: Shared.Progress
  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text(p.headline).font(.system(size: scaled(22), weight: .bold)).testId("progress-headline")
      ProgressBar(value: Double(p.value))
      .padding(.top, Space.m)
      .animation(.easeOut(duration: 0.35), value: p.value)
      VStack(alignment: .leading, spacing: 10) {
        ForEach(p.done, id: \.self) { d in
          HStack(spacing: Space.m) {
            Image(systemName: "checkmark").font(.system(size: scaled(14), weight: .bold)).foregroundStyle(C.ok).frame(width: 18)
            Text(d).font(.system(size: scaled(14.5))).foregroundStyle(C.onSurfaceVariant)
          }
        }
      }
      .padding(.top, Space.l)
    }
  }
}

/// Drag the thumb to the end to confirm: harder to trigger by accident than a button, which
/// matters for something that makes a robot drive. VoiceOver gets a plain activate action.
struct SlideToConfirm: View {
  let label: String
  let onConfirmed: () -> Void
  @State private var x: CGFloat = 0
  @State private var done = false
  private let h: CGFloat = 64

  var body: some View {
    GeometryReader { g in
      let maxX = max(g.size.width - h, 1)
      let p = min(max(x / maxX, 0), 1)
      ZStack(alignment: .leading) {
        Capsule().fill(C.primaryContainer)
        HStack(spacing: 6) {
          Text(label).font(.system(size: scaled(16), weight: .heavy)).foregroundStyle(C.onPrimaryContainer).lineLimit(1)
          Image(systemName: "chevron.right.2").foregroundStyle(C.onPrimaryContainer.opacity(0.6))
        }
        .frame(maxWidth: .infinity).padding(.leading, h * 0.8).opacity(1 - p)
        Capsule().fill(C.primary.opacity(0.25 * p)).frame(width: x + h)
        Circle().fill(C.primary).padding(5)
          .overlay(Image(systemName: "play.fill").font(.system(size: scaled(24))).foregroundStyle(.white))
          .frame(width: h, height: h)
          .shadow(color: .black.opacity(0.2), radius: 3, y: 2)
          .offset(x: x)
          .gesture(
            DragGesture()
              .onChanged { d in if !done { x = min(max(d.translation.width, 0), maxX) } }
              .onEnded { _ in
                guard !done else { return }
                if x >= maxX * 0.9 {
                  x = maxX
                  done = true
                  UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                  onConfirmed()
                } else {
                  withAnimation(.easeOut(duration: 0.25)) { x = 0 }
                }
              },
          )
          .accessibilityIdentifier("slide-thumb")
      }
    }
    .frame(height: h)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(label)
    .accessibilityAddTraits(.isButton)
    .accessibilityAction { onConfirmed() }
  }
}

/// Pick the robot to work with, or add one.
struct RobotSwitcher: View {
  @EnvironmentObject var app: AppModel
  @Environment(\.dismiss) private var dismiss
  var body: some View {
    let v = ListsKt.switcherSheet(s: app.state, now: app.now)
    VStack(alignment: .leading, spacing: Space.m) {
      Text(v.title).font(.system(size: scaled(22), weight: .bold))
      GroupCard {
        ForEach(Array(v.robots.enumerated()), id: \.element.testId) { i, r in
          if i > 0 { Hairline() }
          Button {
            dismiss()
            Task { try? await Actions.shared.selectRobot(c: app.controller, uid: r.uid) }
          } label: {
            HStack(spacing: Space.l) {
              IconTile(glyph: .robot)
              VStack(alignment: .leading, spacing: 2) {
                Text(r.name).font(.system(size: scaled(16), weight: .semibold)).foregroundStyle(C.onSurface)
                Text(r.summary).font(.system(size: scaled(13.5))).foregroundStyle(C.onSurfaceVariant)
              }
              Spacer()
              if r.active { Image(systemName: "checkmark.circle.fill").foregroundStyle(C.primary) }
            }
            .padding(.horizontal, Space.l).padding(.vertical, Space.m).contentShape(Rectangle())
          }
          .buttonStyle(.plain)
          .testId(r.testId)
        }
        Hairline()
        Button {
          dismiss()
          app.push(.addRobot)
        } label: {
          HStack(spacing: Space.l) {
            IconTile(glyph: .add)
            Text(v.addLabel).font(.system(size: scaled(16), weight: .semibold)).foregroundStyle(C.onSurface)
            Spacer()
          }
          .padding(.horizontal, Space.l).padding(.vertical, Space.m).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .testId("switcher-add")
      }
      Spacer()
    }
    .padding(Space.gutter)
    .padding(.top, Space.s)
    .background(C.surface)
    .presentationDetents([.medium])
    .presentationDragIndicator(.visible)
    .screenId("switcher")
  }
}
