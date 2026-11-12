// Home-screen widgets (iOS 17+, interactive): routes A-D of a robot (medium) and one route
// as a tile (small). What they show comes from the shared WidgetBridge (the same as the
// Android widgets); a tap runs RouteIntent. Robot and route: "Edit widget".
import AppIntents
import Shared
import SwiftUI
import UIKit
import WidgetKit

private let brandRed = Color(red: 0xC8 / 255, green: 0x10 / 255, blue: 0x2E / 255)

// The app's look (Option 1, Erik 2026-11-12): white or dark card, a soft tile per route.
private func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
  func ui(_ v: UInt32) -> UIColor {
    UIColor(red: CGFloat(v >> 16 & 0xFF) / 255, green: CGFloat(v >> 8 & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
  }
  return Color(UIColor { $0.userInterfaceStyle == .dark ? ui(dark) : ui(light) })
}
private let widgetBg = dynamic(0xFFFFFF, 0x1E1C1C)
private let tileBg = dynamic(0xF6F4F3, 0x2C2928)
private let playBg = dynamic(0xFFE5E7, 0x3A2326)

/// The app's mark, small: red rounded square with a white play triangle.
private struct Mark: View {
  var body: some View {
    RoundedRectangle(cornerRadius: 5, style: .continuous).fill(brandRed)
      .frame(width: 18, height: 18)
      .overlay(Image(systemName: "play.fill").font(.system(size: 8, weight: .bold)).foregroundStyle(.white).offset(x: 0.5))
  }
}

/// A route's letter on a red rounded square, as on the app's route cards.
private struct Letter: View {
  let text: String
  let size: CGFloat
  var body: some View {
    Text(text).font(.system(size: size * 0.5, weight: .heavy)).foregroundStyle(.white)
      .frame(width: size, height: size)
      .background(RoundedRectangle(cornerRadius: size * 0.27, style: .continuous).fill(brandRed))
  }
}

struct RobotEntity: AppEntity {
  let id: String
  let name: String
  static var typeDisplayRepresentation: TypeDisplayRepresentation = "Robot"
  static var defaultQuery = RobotQuery()
  var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct RobotQuery: EntityQuery {
  private func all() -> [RobotEntity] {
    WidgetBridge.shared.robots(store: widgetStore()).map { RobotEntity(id: $0.uid, name: $0.name) }
  }
  func entities(for identifiers: [String]) async throws -> [RobotEntity] { all().filter { identifiers.contains($0.id) } }
  func suggestedEntities() async throws -> [RobotEntity] { all() }
  func defaultResult() async -> RobotEntity? {
    WidgetBridge.shared.robot(store: widgetStore(), uid: nil).map { RobotEntity(id: $0.uid, name: $0.name) }
  }
}

enum RouteLetter: String, AppEnum {
  case A, B, C, D
  static var typeDisplayRepresentation: TypeDisplayRepresentation = "Route"
  static var caseDisplayRepresentations: [RouteLetter: DisplayRepresentation] = [.A: "Route A", .B: "Route B", .C: "Route C", .D: "Route D"]
}

struct RobotConfig: WidgetConfigurationIntent {
  static var title: LocalizedStringResource = "Robot"
  @Parameter(title: "Robot") var robot: RobotEntity?
}

struct RouteConfig: WidgetConfigurationIntent {
  static var title: LocalizedStringResource = "Route"
  @Parameter(title: "Robot") var robot: RobotEntity?
  @Parameter(title: "Route", default: .A) var route: RouteLetter
}

// MARK: routes A-D

struct BarEntry: TimelineEntry {
  let date: Date
  let bar: WidgetBar
}

struct BarProvider: AppIntentTimelineProvider {
  func entry(_ c: RobotConfig) -> BarEntry { BarEntry(date: Date(), bar: WidgetBridge.shared.bar(store: widgetStore(), robotUid: c.robot?.id)) }
  func placeholder(in context: Context) -> BarEntry { entry(RobotConfig()) }
  func snapshot(for c: RobotConfig, in context: Context) async -> BarEntry { entry(c) }
  func timeline(for c: RobotConfig, in context: Context) async -> Timeline<BarEntry> { Timeline(entries: [entry(c)], policy: .never) }
}

struct BarView: View {
  let entry: BarEntry
  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack(spacing: 7) {
        Mark()
        Text(entry.bar.title).font(.system(size: 14, weight: .bold)).lineLimit(1)
        Spacer(minLength: 8)
        Text(entry.bar.status).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
      }
      HStack(spacing: 8) {
        ForEach(entry.bar.routes, id: \.route) { r in
          Button(intent: RouteIntent(route: r.route, robot: entry.bar.robotUid ?? "")) {
            VStack(spacing: 7) {
              Letter(text: r.route, size: 40)
              Text(r.title).font(.system(size: 11.5, weight: .semibold)).foregroundStyle(.primary).lineLimit(1)
                .padding(.horizontal, 4)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(tileBg))
          }
          .buttonStyle(.plain)
          .accessibilityLabel(TextsKt.tr.startRoute(title: r.title))
        }
      }
    }
    .containerBackground(for: .widget) { widgetBg }
  }
}

struct RoutesWidget: Widget {
  var body: some WidgetConfiguration {
    AppIntentConfiguration(kind: "RoutesWidget", intent: RobotConfig.self, provider: BarProvider()) { BarView(entry: $0) }
      .configurationDisplayName(TextsKt.tr.widgetBarName)
      .description(TextsKt.tr.widgetBarDescription)
      .supportedFamilies([.systemMedium])
  }
}

// MARK: one route

struct TileEntry: TimelineEntry {
  let date: Date
  let tile: WidgetTile
}

struct TileProvider: AppIntentTimelineProvider {
  func entry(_ c: RouteConfig) -> TileEntry {
    TileEntry(date: Date(), tile: WidgetBridge.shared.tile(store: widgetStore(), robotUid: c.robot?.id, route: c.route.rawValue))
  }
  func placeholder(in context: Context) -> TileEntry { entry(RouteConfig()) }
  func snapshot(for c: RouteConfig, in context: Context) async -> TileEntry { entry(c) }
  func timeline(for c: RouteConfig, in context: Context) async -> Timeline<TileEntry> { Timeline(entries: [entry(c)], policy: .never) }
}

struct TileView: View {
  let entry: TileEntry
  var body: some View {
    Button(intent: RouteIntent(route: entry.tile.route, robot: entry.tile.robotUid ?? "")) {
      ZStack(alignment: .topTrailing) {
        VStack(alignment: .leading, spacing: 0) {
          Spacer(minLength: 0)
          Letter(text: entry.tile.route, size: 62)
          Text(entry.tile.title).font(.system(size: 15, weight: .bold)).foregroundStyle(.primary).lineLimit(1)
            .padding(.top, 10)
          Text(entry.tile.detail).font(.system(size: 11.5)).foregroundStyle(.secondary).lineLimit(1)
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        Circle().fill(playBg).frame(width: 26, height: 26)
          .overlay(Image(systemName: "play.fill").font(.system(size: 10, weight: .bold)).foregroundStyle(brandRed).offset(x: 1))
      }
    }
    .buttonStyle(.plain)
    .accessibilityLabel(TextsKt.tr.startRoute(title: entry.tile.title))
    .containerBackground(for: .widget) { widgetBg }
  }
}

struct RouteTileWidget: Widget {
  var body: some WidgetConfiguration {
    AppIntentConfiguration(kind: "RouteTileWidget", intent: RouteConfig.self, provider: TileProvider()) { TileView(entry: $0) }
      .configurationDisplayName(TextsKt.tr.widgetTileName)
      .description(TextsKt.tr.widgetTileDescription)
      .supportedFamilies([.systemSmall])
  }
}

@main
struct ScraperWidgets: WidgetBundle {
  var body: some Widget {
    RoutesWidget()
    RouteTileWidget()
  }
}
