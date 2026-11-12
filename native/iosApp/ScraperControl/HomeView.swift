// Home: robot status at a glance and one card per route (model: shared ui/Home.kt).
import Shared
import SwiftUI

struct HomeView: View {
  @EnvironmentObject var app: AppModel
  @State private var runRoute: RouteItem?
  @State private var switcher = false
  @State private var askPlanning = false

  struct RouteItem: Identifiable { let id: String }

  var body: some View {
    let s = app.state
    let h = HomeKt.homeScreen(s: s, now: app.now, platform: app.platform)
    ScrollView {
      VStack(spacing: 0) {
        header(h)
        VStack(alignment: .leading, spacing: 0) {
          if let title = h.routesTitle {
            SectionHeader(title: title) {
              Button(h.editLabel) { app.push(.robotSettings(s.robot.uid)) }.fontWeight(.bold).testId("home-edit")
            }
            if let hidden = h.allHidden { Callout(glyph: .visibility, text: hidden) }
            ForEach(h.routes, id: \.testId) { r in
              routeCard(r).padding(.vertical, Space.m / 2)
            }
          }
          if let mt = h.modelTitle {
            SectionHeader(mt)
            ForEach(h.modelScreens, id: \.testId) { m in
              modelCard(m).padding(.vertical, Space.m / 2)
            }
          }
          if let tip = h.widgetTip {
            widgetTip(tip, close: h.closeLabel).padding(.top, Space.m)
          }
        }
        .padding(.horizontal, Space.gutter)
        .padding(.bottom, Space.xxl)
      }
    }
    .refreshable { app.show(try? await Actions.shared.refresh(c: app.controller)) }
    .background(C.surface)
    .ignoresSafeArea(edges: .top)
    .toolbar(.hidden, for: .navigationBar)
    .screenId("home")
    .sheet(item: $runRoute) { r in RunSheet(controller: app.controller, route: r.id) }
    .sheet(isPresented: $switcher) { RobotSwitcher() }
    .finishQuestion(app, active: runRoute == nil && !switcher)
    .confirm(HomeKt.planningConfirm(s: s), isPresented: $askPlanning) {
      Task { app.show(try? await Actions.shared.togglePlanning(c: app.controller)) }
    }
  }

  private func refresh() {
    Task { app.show(try? await Actions.shared.refresh(c: app.controller)) }
  }

  /// Red brand header with the robot's name and state, and the facts card overlapping it.
  private func header(_ h: HomeScreen) -> some View {
    ZStack(alignment: .bottom) {
      VStack(spacing: 0) {
        VStack(alignment: .leading, spacing: 0) {
          HStack(spacing: 10) {
            AppLogo(size: 26, inverse: true)
            Text(h.appName).font(.system(size: scaled(15), weight: .heavy)).foregroundStyle(.white)
            Spacer()
            Button { app.push(.settings) } label: {
              Image(systemName: "gearshape").font(.system(size: scaled(20))).foregroundStyle(.white).frame(width: 44, height: 44)
            }
            .accessibilityLabel(h.settingsLabel).testId("home-settings")
          }
          Button { switcher = true } label: {
            HStack(spacing: 4) {
              Text(h.robotName).font(.system(size: scaled(30), weight: .heavy)).kerning(-0.6).lineLimit(1)
              Image(systemName: "chevron.down").font(.system(size: scaled(20), weight: .bold))
            }
            .foregroundStyle(.white)
          }
          .accessibilityLabel(h.switchLabel).testId("home-robot")
          .padding(.top, Space.l)
          if let count = h.robotCount {
            Text(count).font(.system(size: scaled(12.5))).foregroundStyle(.white.opacity(0.75)).padding(.top, 2)
          }
          pill(h.pill).padding(.top, Space.s)
        }
        .padding(.leading, Space.gutter).padding(.trailing, Space.s).padding(.bottom, 72)
        .padding(.top, topInset() + Space.s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [Brand.red, Brand.redDeep], startPoint: .topLeading, endPoint: .bottomTrailing))
        Color.clear.frame(height: 52)
      }
      facts(h.facts).padding(.horizontal, Space.gutter)
    }
  }

  private func topInset() -> CGFloat {
    (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first?.safeAreaInsets.top ?? 47
  }

  private func pill(_ p: StatePill) -> some View {
    let dot: Color? = {
      guard let d = p.dot else { return nil }
      switch d {
      case .ok: return Color(hex: 0x7EF0A6)
      case .warn: return Color(hex: 0xFFD27A)
      case .idle: return .white.opacity(0.54)
      default: return .white.opacity(0.7)
      }
    }()
    return Button(action: refresh) {
      HStack(spacing: 8) {
        if p.spinning {
          ProgressView().tint(.white).scaleEffect(0.7).frame(width: 12, height: 12)
        } else if let dot {
          Dot(color: dot)
        }
        Text(p.text).font(.system(size: scaled(13.5), weight: .semibold)).foregroundStyle(.white).testId("home-pill-text")
        if !p.spinning { Image(systemName: "arrow.clockwise").font(.system(size: scaled(12), weight: .semibold)).foregroundStyle(.white.opacity(0.7)) }
      }
      .padding(.leading, 10).padding(.trailing, 12).padding(.vertical, 6)
      .background(.white.opacity(0.16), in: Capsule())
    }
    .buttonStyle(.plain)
    .disabled(!p.tappable || app.state.isBusy)
    .testId("home-pill")
  }

  private func facts(_ facts: [Fact]) -> some View {
    HStack(spacing: 0) {
      ForEach(Array(facts.enumerated()), id: \.element.testId) { i, f in
        if i > 0 { Rectangle().fill(C.outlineVariant).frame(width: 1).padding(.vertical, 20) }
        Button { if f.editable { askPlanning = true } } label: {
          VStack(spacing: 6) {
            Text(f.label).font(.system(size: scaled(12.5))).foregroundStyle(C.onSurfaceVariant).lineLimit(1)
            HStack(spacing: 6) {
              if let d = f.dot { Dot(color: toneColor(d)) }
              Text(f.value).font(.system(size: scaled(20), weight: .heavy)).monospacedDigit().kerning(-0.3).foregroundStyle(C.onSurface)
                .lineLimit(1).minimumScaleFactor(0.7).testId(f.testId + "-value")
              if f.editable && f.id == .planning {
                Image(systemName: "chevron.down").font(.system(size: scaled(12), weight: .bold)).foregroundStyle(C.onSurfaceVariant)
              }
            }
          }
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .contentShape(Rectangle())
        }
        .buttonStyle(FactButtonStyle())
        .disabled(!f.editable)
        .testId(f.testId)
      }
    }
    .padding(.horizontal, Space.m)
    .frame(height: 92)
    .background(C.containerLow, in: RoundedRectangle(cornerRadius: Radii.l, style: .continuous))
    .shadow(color: .black.opacity(0.08), radius: 12, y: 8)
  }

  /// A fact that cannot be edited is a disabled button for accessibility and tests, but it must not
  /// look greyed out (Compose: clickable(enabled = false) keeps the colours); .plain dims it.
  private struct FactButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
      configuration.label.opacity(configuration.isPressed ? 0.6 : 1)
    }
  }

  private func routeCard(_ r: RouteCard) -> some View {
    Button { runRoute = RouteItem(id: r.route) } label: {
      HStack(spacing: Space.l) {
        RouteBadge(letter: r.route, solid: true)
        // The text takes all the width between badge and play button (Compose: weight(1f)); with a
        // Spacer next to it, SwiftUI gave it less and wrapped the subtitle onto a second line.
        VStack(alignment: .leading, spacing: 2) {
          Text(r.title).font(.system(size: scaled(17), weight: .bold)).foregroundStyle(C.onSurface).lineLimit(1)
          Text(r.subtitle).font(.system(size: scaled(13.5))).foregroundStyle(C.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        Image(systemName: "play.fill").font(.system(size: scaled(20))).foregroundStyle(r.enabled ? C.primary : C.onSurfaceVariant)
          .frame(width: 48, height: 48).background(r.enabled ? C.primaryContainer : C.containerHigh, in: Circle())
          .accessibilityLabel(r.startLabel)
      }
      .padding(.leading, Space.l).padding(.trailing, Space.m).padding(.vertical, Space.l)
      .card()
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .disabled(!r.enabled)
    .testId(r.testId)
  }

  private func modelCard(_ m: ModelScreenCard) -> some View {
    Button { app.push(.panel) } label: {
      HStack(spacing: Space.l) {
        Glyph.dialpad.image.font(.system(size: scaled(20))).foregroundStyle(C.onSurface)
          .frame(width: 52, height: 52).background(C.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        VStack(alignment: .leading, spacing: 2) {
          Text(m.title).font(.system(size: scaled(17), weight: .bold)).foregroundStyle(C.onSurface)
          Text(m.subtitle).font(.system(size: scaled(13.5))).foregroundStyle(C.onSurfaceVariant).multilineTextAlignment(.leading)
        }
        Spacer()
        Chevron()
      }
      .padding(.leading, Space.l).padding(.trailing, Space.m).padding(.vertical, Space.l)
      .card()
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .testId(m.testId)
  }

  private func widgetTip(_ text: String, close: String) -> some View {
    HStack(spacing: Space.m) {
      IconTile(glyph: .widgets)
      Text(text).font(.system(size: scaled(13.5))).foregroundStyle(C.onSurface).fixedSize(horizontal: false, vertical: true)
      Spacer(minLength: 0)
      Button { Task { try? await Actions.shared.dismissWidgetTip(c: app.controller) } } label: {
        Image(systemName: "xmark").font(.system(size: scaled(14), weight: .semibold)).frame(width: 40, height: 40)
      }
      .accessibilityLabel(close).testId("home-widget-tip-close")
    }
    .padding(.leading, Space.l).padding(.trailing, Space.xs).padding(.vertical, Space.m)
    .background(C.container, in: RoundedRectangle(cornerRadius: Radii.l, style: .continuous))
    .screenId("home-widget-tip")
  }
}
