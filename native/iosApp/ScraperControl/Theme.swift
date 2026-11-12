// Scraper Control design system: the same values as the Compose app (Theme.kt). Brand red for brand surfaces and the one primary action; the rest is warm
// neutral. Status is never colour alone: always an icon and a word as well.
import Shared
import SwiftUI

extension Color {
  init(hex: UInt32) {
    self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
  }

  /// Light and dark variant.
  init(light: UInt32, dark: UInt32) {
    self.init(UIColor { t in
      let v = t.userInterfaceStyle == .dark ? dark : light
      return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    })
  }
}

enum Brand {
  static let red = Color(hex: 0xC8102E)
  static let redDeep = Color(hex: 0x9B0C24)
  static let redLight = Color(hex: 0xE0243F)
}

/// The colour scheme, named as in Material so both apps read the same.
enum C {
  static let primary = Color(light: 0xC8102E, dark: 0xE5394F)
  static let primaryContainer = Color(light: 0xFFE5E7, dark: 0x5B1320)
  static let onPrimaryContainer = Color(light: 0x5F0013, dark: 0xFFDADC)
  static let secondaryContainer = Color(light: 0xF3E2E2, dark: 0x3A2A2A)
  static let surface = Color(light: 0xF6F4F3, dark: 0x141212)
  static let onSurface = Color(light: 0x1C1B1B, dark: 0xEDE7E6)
  static let onSurfaceVariant = Color(light: 0x5E5857, dark: 0xB8B0AF)
  static let containerLow = Color(light: 0xFFFFFF, dark: 0x1E1B1B)
  static let container = Color(light: 0xF0EDEC, dark: 0x231F1F)
  static let containerHigh = Color(light: 0xE9E5E4, dark: 0x2B2726)
  static let outlineVariant = Color(light: 0xE4DFDE, dark: 0x3B3535)
  static let error = Color(light: 0xA8200D, dark: 0xFFB4A8)
  static let errorContainer = Color(light: 0xFCE8E4, dark: 0x4A120A)
  static let inverseSurface = Color(light: 0x1C1B1B, dark: 0xEDE7E6)
  static let inverseOnSurface = Color(light: 0xFFFFFF, dark: 0x1C1B1B)
  // status
  static let ok = Color(light: 0x1B7F3B, dark: 0x7EDC9A)
  static let okSoft = Color(light: 0xE3F4E8, dark: 0x16351F)
  static let warn = Color(light: 0x8F5B00, dark: 0xFFC66B)
  static let warnSoft = Color(light: 0xFFF1D6, dark: 0x3A2A0C)
  static let idle = Color(light: 0x8A8584, dark: 0x9C9695)
}

/// A text size that follows the phone's text size (Dynamic Type), like sp in Compose. Capped at
/// 1.6x so labels in fixed shapes (badges, the facts row) stay inside; the panel's keys and the
/// robot's display keep their fixed sizes on purpose.
func scaled(_ size: CGFloat) -> CGFloat { min(UIFontMetrics.default.scaledValue(for: size), size * 1.6) }

/// Spacing (4-pt grid) and radii, so screens line up.
enum Space {
  static let xs: CGFloat = 4, s: CGFloat = 8, m: CGFloat = 12, l: CGFloat = 16, xl: CGFloat = 24, xxl: CGFloat = 32
  static let gutter: CGFloat = 20
}

enum Radii {
  static let m: CGFloat = 14, l: CGFloat = 20
}

/// Foreground and soft background of a shared tone.
func toneColors(_ t: Tone) -> (Color, Color) {
  switch t {
  case .ok: return (C.ok, C.okSoft)
  case .warn: return (C.warn, C.warnSoft)
  case .idle: return (C.idle, C.container)
  case .error: return (C.error, C.errorContainer)
  case .brand: return (C.primary, C.primaryContainer)
  default: return (C.onSurface, C.container)
  }
}

func toneColor(_ t: Tone) -> Color { toneColors(t).0 }

/// Letter badge for a route (A-D).
struct RouteBadge: View {
  let letter: String
  var size: CGFloat = 52
  var solid = false
  var muted = false
  var body: some View {
    let bg = muted ? C.containerHigh : solid ? C.primary : C.primaryContainer
    let fg = muted ? C.onSurfaceVariant : solid ? Color.white : C.onPrimaryContainer
    Text(letter)
      .font(.system(size: size * 0.46, weight: .heavy))
      .foregroundStyle(fg)
      .frame(width: size, height: size)
      .background(bg, in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
  }
}
