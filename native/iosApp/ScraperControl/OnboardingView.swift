// First start (welcome, find the robot, PIN with a connection test) and the Bluetooth scan
// (models and flows: shared ui/Onboarding.kt, ui/SettingsScreens.kt).
import Shared
import SwiftUI

struct OnboardingView: View {
  @EnvironmentObject var app: AppModel
  @StateObject private var held: Held<OnboardingFlow>
  private var flow: OnboardingFlow { held.value }
  @StateObject private var st: Live<OnboardingState>

  init(controller: RobotController) {
    let f = OnboardingFlow(controller: controller, platform: .ios)
    _held = StateObject(wrappedValue: Held(f))
    _st = StateObject(wrappedValue: Live(f.state.value as! OnboardingState) { cb in f.watch(onChange: cb) })
  }

  var body: some View {
    ZStack {
      switch st.value.page {
      case .welcome: welcome.transition(.move(edge: .leading))
      case .findrobot: find.transition(.move(edge: .trailing))
      default: pin.transition(.move(edge: .trailing))
      }
    }
    .animation(.easeOut(duration: 0.3), value: st.value.page)
  }

  private var welcome: some View {
    let v = flow.welcome()
    return VStack(spacing: 0) {
      ScrollView {
        VStack(alignment: .leading, spacing: 0) {
          AppLogo(size: 72, inverse: true)
          Text(v.title).font(.system(size: scaled(34), weight: .heavy)).kerning(-0.8).foregroundStyle(.white).padding(.top, Space.xl)
          Text(v.lead).font(.system(size: scaled(16))).foregroundStyle(.white.opacity(0.85)).padding(.top, Space.m)
          VStack(alignment: .leading, spacing: Space.xl) {
            ForEach(v.features, id: \.title) { f in
              HStack(alignment: .top, spacing: Space.l) {
                f.glyph.image.font(.system(size: scaled(20))).foregroundStyle(.white)
                  .frame(width: 44, height: 44).background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: Radii.m, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                  Text(f.title).font(.system(size: scaled(16), weight: .heavy)).foregroundStyle(.white)
                  Text(f.body).font(.system(size: scaled(14.5))).foregroundStyle(.white.opacity(0.82)).fixedSize(horizontal: false, vertical: true)
                }
              }
            }
          }
          .padding(.top, 40)
        }
        .padding(.horizontal, 28).padding(.top, 56).padding(.bottom, Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
      }
      Button { flow.goto(p: .findrobot) } label: {
        Text(v.start.label).font(.system(size: scaled(16), weight: .bold)).foregroundStyle(Brand.red)
          .frame(maxWidth: .infinity, minHeight: 54).background(.white, in: RoundedRectangle(cornerRadius: Radii.m, style: .continuous))
      }
      .buttonStyle(.plain)
      .padding(.horizontal, 28).padding(.bottom, Space.xl)
      .testId(v.start.testId)
    }
    .frame(maxWidth: 520)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(LinearGradient(colors: [Brand.red, Brand.redDeep], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
    .screenId("onboarding-welcome")
  }

  private var find: some View {
    let v = flow.find(s: app.state)
    return StepFrame(h: v.header, onBack: { _ = flow.back() }) {
      StepPage(title: v.title, lead: v.lead, testId: "onboarding-find") {
        ScanView(flow: flow.scan) { id in Task { try? await flow.pick(id: id) } }
      } bottom: {
        HStack(spacing: Space.m) {
          Button(v.skip.label) { flow.goto(p: .pin) }.fontWeight(.bold).testId(v.skip.testId)
          ActionButton(a: v.next) { flow.goto(p: .pin) }
        }
      }
    }
  }

  private var pin: some View {
    let v = flow.pin(s: app.state, st: st.value)
    return StepFrame(h: v.header, onBack: { _ = flow.back() }) {
      StepPage(title: v.title, lead: v.lead, testId: "onboarding-pin") {
        HStack {
          Group {
            if st.value.showPin {
              TextField(v.hint, text: Binding(get: { st.value.pin }, set: { flow.setPin(p: $0.filter(\.isNumber)) }))
            } else {
              SecureField(v.hint, text: Binding(get: { st.value.pin }, set: { flow.setPin(p: $0.filter(\.isNumber)) }))
            }
          }
          .font(.system(size: scaled(24), weight: .bold)).keyboardType(.numberPad).testId("onboarding-pin-field")
          Button(v.showLabel) { flow.toggleShowPin() }
        }
        .padding(.horizontal, 16).frame(minHeight: 60).card()
        VStack(alignment: .leading, spacing: 0) {
          if let p = v.progress { StepsProgress(p: p) }
          if let r = v.result {
            Callout(glyph: r.ok ? .check : .error, text: r.body, tone: r.ok ? .ok : .error, title: r.title)
              .accessibilityElement(children: .combine)
              .testId("onboarding-result")
          }
        }
        .padding(.top, Space.xl)
      } bottom: {
        if let done = v.done {
          ActionButton(a: done) { Task { try? await flow.finish() } }
        } else {
          HStack(spacing: Space.m) {
            Button(v.later.label) { Task { try? await flow.finish() } }.fontWeight(.bold).disabled(!v.later.enabled).testId(v.later.testId)
            ActionButton(a: v.test) {
              Task { try? await flow.test() }
            }
          }
        }
      }
    }
  }
}

/// Frame for steps 1 and 2: back, "Stap x van 2" and a thin progress bar.
struct StepFrame<Content: View>: View {
  let h: StepHeader
  let onBack: () -> Void
  @ViewBuilder var content: () -> Content
  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: Space.s) {
        Button(action: onBack) { Image(systemName: "chevron.left").font(.system(size: scaled(18), weight: .semibold)).frame(width: 44, height: 44) }
          .accessibilityLabel(h.backLabel).testId("back")
        Text(h.step).fontWeight(.bold).foregroundStyle(C.onSurfaceVariant)
        GeometryReader { g in
          ZStack(alignment: .leading) {
            Capsule().fill(C.containerHigh)
            Capsule().fill(C.primary).frame(width: g.size.width * CGFloat(h.progress))
          }
        }
        .frame(height: 5)
        .padding(.leading, Space.s)
      }
      .padding(.leading, Space.xs).padding(.trailing, Space.gutter)
      content()
    }
    .frame(maxWidth: 520)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(C.surface.ignoresSafeArea())
  }
}

struct StepPage<Content: View, Bottom: View>: View {
  let title: String
  let lead: String
  let testId: String
  @ViewBuilder var content: () -> Content
  @ViewBuilder var bottom: () -> Bottom
  var body: some View {
    VStack(spacing: 0) {
      ScrollView {
        VStack(alignment: .leading, spacing: 0) {
          Text(title).font(.system(size: scaled(28), weight: .heavy)).kerning(-0.5)
          Text(lead).font(.system(size: scaled(16))).foregroundStyle(C.onSurfaceVariant).padding(.top, Space.s).padding(.bottom, Space.xl)
          content()
        }
        .padding(.horizontal, Space.xl).padding(.top, Space.xl).padding(.bottom, Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
      }
      bottom().padding(.horizontal, Space.xl).padding(.bottom, Space.xl)
    }
    .screenId(testId)
  }
}

/// Radar, the robots found, and "search again". Scans once when it appears.
struct ScanView: View {
  private let flow: ScanFlow
  let onSelected: (String) -> Void
  @StateObject private var st: Live<ScanState>

  init(flow: ScanFlow, onSelected: @escaping (String) -> Void) {
    self.flow = flow
    self.onSelected = onSelected
    _st = StateObject(wrappedValue: Live(flow.state.value as! ScanState) { cb in flow.watch(onChange: cb) })
  }

  var body: some View {
    let v = flow.view(st: st.value)
    VStack(spacing: 0) {
      Radar(scanning: v.scanning)
      if !v.headline.isEmpty {
        Text(v.headline).font(.system(size: scaled(17), weight: .bold)).frame(maxWidth: .infinity).padding(.top, 8).testId("scan-headline")
      }
      if let e = v.error { Text(e).foregroundStyle(C.error).padding(.top, 8) }
      VStack(spacing: 8) {
        ForEach(v.rows, id: \.testId) { f in
          Button { onSelected(f.id) } label: {
            HStack(spacing: Space.l) {
              Image(systemName: f.selected ? "checkmark.circle.fill" : Glyph.robot.symbol).font(.system(size: scaled(20)))
                .foregroundStyle(f.selected ? C.primary : C.onSurfaceVariant)
              VStack(alignment: .leading, spacing: 2) {
                Text(f.name).font(.system(size: scaled(16), weight: .semibold)).foregroundStyle(C.onSurface)
                Text(f.detail).font(.system(size: scaled(13.5))).foregroundStyle(C.onSurfaceVariant)
              }
              Spacer()
            }
            .padding(.horizontal, Space.l).padding(.vertical, 14)
            .card(f.selected ? C.secondaryContainer : C.containerLow)
            .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
          .testId(f.testId)
        }
      }
      .padding(.top, 12)
      if let help = v.emptyHelp {
        Text(help).foregroundStyle(C.onSurfaceVariant).frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 12).testId("scan-empty")
      }
      if v.nameHelp != nil || v.emptyHelp != nil {
        HStack {
          Glyph.badge.image.foregroundStyle(C.onSurfaceVariant)
          TextField(v.nameLabel, text: Binding(get: { st.value.name }, set: { flow.setName(n: $0) })).autocorrectionDisabled()
        }
        .padding(.horizontal, 16).frame(minHeight: 56).card()
        .padding(.bottom, 12)
      }
      ActionButton(a: v.again, glyph: .refresh) { Task { _ = try? await flow.scan() } }
    }
    .task {
      let s = flow.state.value as! ScanState
      if !s.scanned && !s.scanning { _ = try? await flow.scan() }
    }
    .screenId("scan")
  }
}

private struct Radar: View {
  let scanning: Bool
  @State private var t: CGFloat = 0
  var body: some View {
    ZStack {
      TimelineView(.animation(paused: !scanning)) { ctx in
        Canvas { g, size in
          let maxR = min(size.width, size.height) / 2
          let c = CGPoint(x: size.width / 2, y: size.height / 2)
          func ring(_ r: CGFloat, _ a: Double) {
            g.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(C.primary.opacity(a)))
          }
          if !scanning {
            ring(maxR * 0.75, 0.08)
          } else {
            let phase = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.6) / 1.6
            for i in 0 ..< 3 {
              let p = (phase + Double(i) / 3).truncatingRemainder(dividingBy: 1)
              ring(30 + (maxR - 30) * p, 0.22 * (1 - p))
            }
          }
        }
      }
      Circle().fill(C.primary).frame(width: 60, height: 60)
        .overlay(Glyph.bluetoothsearching.image.font(.system(size: scaled(26))).foregroundStyle(.white))
    }
    .frame(height: 150)
  }
}
