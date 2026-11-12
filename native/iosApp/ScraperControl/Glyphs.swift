// The shared Glyph words as SF Symbols (Compose: Material icons in Glyphs.kt).
import Shared
import SwiftUI

extension Glyph {
  var symbol: String {
    switch self {
    case .check: return "checkmark"
    case .stop: return "stop.fill"
    case .science: return "flask"
    case .bluetoothoff: return "antenna.radiowaves.left.and.right.slash"
    case .bluetoothsearching: return "dot.radiowaves.left.and.right"
    case .bluetooth: return "antenna.radiowaves.left.and.right"
    case .lock: return "lock"
    case .pending: return "ellipsis.circle"
    case .visibility: return "eye"
    case .error: return "exclamationmark.circle"
    case .schedule: return "clock"
    case .shield: return "checkmark.shield"
    case .touch: return "hand.tap"
    case .robot: return "cpu"
    case .add: return "plus"
    case .widgets: return "square.grid.2x2"
    case .translate: return "character.bubble"
    case .logbook: return "clock.arrow.circlepath"
    case .restart: return "arrow.counterclockwise"
    case .badge: return "person.text.rectangle"
    case .delete_: return "trash"
    case .dialpad: return "circle.grid.3x3"
    case .phone: return "iphone"
    case .play: return "play.fill"
    case .refresh: return "arrow.clockwise"
    default: return "questionmark"
    }
  }

  var image: Image { Image(systemName: symbol) }
}
