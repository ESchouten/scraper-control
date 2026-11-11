// Home-screen widgets (iOS 17+, interactive): routes A-D of a robot (medium) and one route
// as a tile (small). What they show comes from the shared WidgetBridge (the same as the
// Android widgets); a tap runs RouteIntent. Robot and route: "Edit widget".
import AppIntents
import Shared
import SwiftUI
import WidgetKit

private let brandRed = Color(red: 0xC8 / 255, green: 0x10 / 255, blue: 0x2E / 255)

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
    VStack(alignment: .leading, spacing: 8) {
      Text(entry.bar.title).font(.caption.bold()).foregroundStyle(brandRed)
      HStack(spacing: 8) {
        ForEach(entry.bar.routes, id: \.route) { r in
          Button(intent: RouteIntent(route: r.route, robot: entry.bar.robotUid ?? "")) {
            Text(r.route).font(.system(size: 26, weight: .bold)).frame(maxWidth: .infinity, maxHeight: .infinity)
          }
          .buttonStyle(.borderedProminent)
          .tint(brandRed)
          .accessibilityLabel(TextsKt.tr.startRoute(title: r.title))
        }
      }
      Text(entry.bar.status).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
    }
    .containerBackground(.fill.tertiary, for: .widget)
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
      VStack(spacing: 6) {
        Text(entry.tile.route).font(.system(size: 30, weight: .bold)).foregroundStyle(.white)
          .frame(width: 58, height: 58).background(RoundedRectangle(cornerRadius: 16).fill(brandRed))
        Text(entry.tile.title).font(.footnote.bold()).lineLimit(1)
        Text(entry.tile.detail).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    .buttonStyle(.plain)
    .containerBackground(.fill.tertiary, for: .widget)
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
