// Small building blocks shared by all screens (Compose: Common.kt).
import Shared
import SwiftUI

/// A Kotlin flow's state as SwiftUI state: subscribes with the flow's watch().
/// Keeps a shared flow for the whole life of a view. A view's init runs again on every update from
/// above; a flow made there would be a new one each time, no longer the one its Live state watches.
final class Held<T>: ObservableObject {
  let value: T
  init(_ value: T) { self.value = value }
}

@MainActor final class Live<T>: ObservableObject {
  @Published var value: T
  private var handle: (any Cancellable)?

  init(_ initial: T, _ watch: (@escaping (T) -> Void) -> any Cancellable) {
    value = initial
    handle = watch { [weak self] v in
      if Thread.isMainThread { self?.value = v } else { DispatchQueue.main.async { self?.value = v } }
    }
  }

  deinit { handle?.cancel() }
}

/// Now in Kotlin's milliseconds, ticking every 20 s for "3 min ago".
@MainActor final class Ticker: ObservableObject {
  @Published var now: Int64 = Ticker.ms()
  private var timer: Timer?
  init() {
    timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
      Task { @MainActor in self?.now = Ticker.ms() }
    }
  }
  static func ms() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

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

/// Title above a group of rows.
struct SectionHeader<Trailing: View>: View {
  let title: String
  @ViewBuilder var trailing: () -> Trailing
  var body: some View {
    HStack {
      Text(title.uppercased()).font(.system(size: scaled(12.5), weight: .heavy)).kerning(0.8).foregroundStyle(C.onSurfaceVariant)
      Spacer()
      trailing()
    }
    .padding(.leading, Space.xs).padding(.top, Space.xl).padding(.bottom, Space.s)
  }
}

extension SectionHeader where Trailing == EmptyView {
  init(_ title: String) { self.init(title: title) { EmptyView() } }
}

/// Card look used everywhere: no shadow, hairline border.
struct CardStyle: ViewModifier {
  var fill: Color = C.containerLow
  func body(content: Content) -> some View {
    content
      .background(fill, in: RoundedRectangle(cornerRadius: Radii.l, style: .continuous))
      .overlay(RoundedRectangle(cornerRadius: Radii.l, style: .continuous).stroke(C.outlineVariant.opacity(0.7), lineWidth: 1))
  }
}

extension View {
  func card(_ fill: Color = C.containerLow) -> some View { modifier(CardStyle(fill: fill)) }
  func testId(_ id: String) -> some View { accessibilityIdentifier(id) }
  /// A screen or block that holds other test ids: stays a container, so its children keep their own ids
  /// (an identifier on a plain stack is handed down to every child and hides theirs).
  func screenId(_ id: String) -> some View { accessibilityElement(children: .contain).accessibilityIdentifier(id) }
}

/// Inset grouped list: rows in one card with hairlines between them.
struct GroupCard<Content: View>: View {
  @ViewBuilder var content: () -> Content
  var body: some View {
    VStack(spacing: 0) { content() }.clipShape(RoundedRectangle(cornerRadius: Radii.l, style: .continuous)).card()
  }
}

struct Hairline: View {
  var body: some View { Rectangle().fill(C.outlineVariant).frame(height: 1).padding(.leading, 68) }
}

/// Leading icon in a soft square, for list rows.
struct IconTile: View {
  let glyph: Glyph
  var color: Color = C.onSurface
  var background: Color = C.container
  var body: some View {
    glyph.image.font(.system(size: scaled(17), weight: .medium)).foregroundStyle(color)
      .frame(width: 36, height: 36).background(background, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
  }
}

/// Icon + text callout.
struct Callout: View {
  let glyph: Glyph
  let text: String
  var tone: Tone = .neutral
  var title: String? = nil
  var body: some View {
    let (fg, bg) = toneColors(tone)
    HStack(alignment: .top, spacing: Space.m) {
      glyph.image.foregroundStyle(fg)
      VStack(alignment: .leading, spacing: 2) {
        if let title { Text(title).fontWeight(.heavy).foregroundStyle(C.onSurface) }
        Text(text).font(.system(size: scaled(14.5))).foregroundStyle(C.onSurface).fixedSize(horizontal: false, vertical: true)
      }
      Spacer(minLength: 0)
    }
    .padding(Space.l).background(bg, in: RoundedRectangle(cornerRadius: Radii.m, style: .continuous))
  }
}

struct Chevron: View {
  var body: some View { Image(systemName: "chevron.right").font(.system(size: scaled(14), weight: .semibold)).foregroundStyle(C.onSurfaceVariant) }
}

struct Dot: View {
  let color: Color
  var size: CGFloat = 8
  var body: some View { Circle().fill(color).frame(width: size, height: size) }
}

/// A shared Action as a full-width button: filled when primary, else outlined.
struct ActionButton: View {
  let a: Shared.Action
  var glyph: Glyph? = nil
  var danger = false
  let onTap: () -> Void
  var body: some View {
    Button(action: onTap) {
      HStack(spacing: Space.s) {
        if let glyph { glyph.image }
        Text(a.label).font(.system(size: scaled(16), weight: .bold))
      }
      .frame(maxWidth: .infinity, minHeight: 54)
      .foregroundStyle(a.primary ? Color.white : danger ? C.error : C.onSurface)
      .background(a.primary ? C.primary : Color.clear, in: RoundedRectangle(cornerRadius: Radii.m, style: .continuous))
      .overlay {
        if !a.primary { RoundedRectangle(cornerRadius: Radii.m, style: .continuous).stroke(C.outlineVariant, lineWidth: 1.5) }
      }
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .disabled(!a.enabled)
    .opacity(a.enabled ? 1 : 0.45)
    .testId(a.testId)
  }
}

/// App mark: a rounded square with a play button in a ring. [inverse]: white tile, red mark.
struct AppLogo: View {
  var size: CGFloat = 96
  var inverse = false
  var body: some View {
    Canvas { ctx, sz in
      let w = sz.width
      let tile = Path(roundedRect: CGRect(origin: .zero, size: sz), cornerRadius: w * 0.27, style: .continuous)
      let fg: Color = inverse ? Brand.red : .white
      if inverse {
        ctx.fill(tile, with: .color(.white))
      } else {
        ctx.fill(tile, with: .linearGradient(Gradient(colors: [Brand.redLight, Brand.red, Brand.redDeep]), startPoint: .zero, endPoint: CGPoint(x: w, y: w)))
      }
      let r = w * 0.29
      ctx.stroke(Path(ellipseIn: CGRect(x: w / 2 - r, y: w / 2 - r, width: 2 * r, height: 2 * r)), with: .color(fg), lineWidth: w * 0.065)
      var t = Path()
      t.move(to: CGPoint(x: w * 0.435, y: w * 0.375))
      t.addLine(to: CGPoint(x: w * 0.435, y: w * 0.625))
      t.addLine(to: CGPoint(x: w * 0.64, y: w * 0.50))
      t.closeSubpath()
      ctx.fill(t, with: .color(fg))
      ctx.stroke(t, with: .color(fg), style: StrokeStyle(lineWidth: w * 0.05, lineJoin: .round))
    }
    .frame(width: size, height: size)
  }
}

/// The robot's control panel display, as the core read it.
struct Lcd: View {
  let lines: [String]
  let highlighted: String
  let dimmed: Bool
  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
        let hl = !highlighted.isEmpty && l.trimmingCharacters(in: .whitespaces) == highlighted
        Text(l.isEmpty ? " " : l)
          .font(.system(size: 14, weight: .medium, design: .monospaced))
          .foregroundStyle(Color.white.opacity(dimmed ? 0.35 : 1))
          .lineLimit(1)
          .padding(.horizontal, 2)
          .background(hl ? Brand.red : Color.clear)
      }
      Spacer(minLength: 0)
    }
    .padding(.horizontal, 12).padding(.vertical, 10)
    .frame(maxWidth: .infinity, alignment: .topLeading)
    .aspectRatio(1.9, contentMode: .fit)
    .background(Color(hex: 0x4A4A4A), in: RoundedRectangle(cornerRadius: Radii.l - 3, style: .continuous))
    .overlay(RoundedRectangle(cornerRadius: Radii.l - 3, style: .continuous).stroke(Color(hex: 0x6A6A6A), lineWidth: 1.5))
    .padding(3)
    .background(Color(hex: 0x141211), in: RoundedRectangle(cornerRadius: Radii.l, style: .continuous))
    .accessibilityElement(children: .combine)
    .testId("lcd")
  }
}

/// A shared yes/no question as an alert.
extension View {
  func confirm(_ q: Confirm?, isPresented: Binding<Bool>, onOk: @escaping () -> Void) -> some View {
    alert(q?.title ?? "", isPresented: isPresented) {
      Button(q?.cancel ?? "", role: .cancel) {}.testId("confirm-cancel")
      Button(q?.ok ?? "") { onOk() }.testId("confirm-ok")
    } message: {
      Text(q?.body ?? "")
    }
  }
}

/// After a stop the robot asks whether to finish the route: the user decides, the answer goes
/// to the robot 1:1 (shared HomeKt.finishQuestion). Only one view may show it at a time: an
/// alert under a presented sheet does not appear, so pass active = false there.
extension View {
  func finishQuestion(_ app: AppModel, active: Bool = true) -> some View {
    let q = HomeKt.finishQuestion(s: app.state)
    return alert(q?.title ?? "", isPresented: Binding(get: { active && q != nil }, set: { _ in })) {
      Button(q?.yes ?? "") { app.controller.answerFinish(finish: KotlinBoolean(bool: true)) }.testId("choice-yes")
      Button(q?.no ?? "", role: .destructive) { app.controller.answerFinish(finish: KotlinBoolean(bool: false)) }.testId("choice-no")
      Button(q?.later ?? "", role: .cancel) { app.controller.finishLater() }.testId("choice-later")
    } message: {
      Text(q?.body ?? "")
    }
  }
}

/// Free text (a name) or, with secret, a PIN.
struct TextInputSheet: View {
  let d: TextDialog
  var help: String? = nil
  let onSave: (String) -> Void
  @Environment(\.dismiss) private var dismiss
  @State private var value = ""
  @State private var show = false
  var body: some View {
    NavigationStack {
      VStack(spacing: Space.l) {
        if let help { Text(help).multilineTextAlignment(.center).foregroundStyle(C.onSurfaceVariant) }
        HStack {
          Group {
            if d.secret && !show { SecureField(d.hint, text: $value) } else { TextField(d.hint, text: $value) }
          }
          .font(d.secret ? .system(size: scaled(24), weight: .bold) : .body)
          .keyboardType(d.secret ? .numberPad : .default)
          .textInputAutocapitalization(.sentences)
          .testId("dialog-field")
          if d.secret {
            Button { show.toggle() } label: { Image(systemName: show ? "eye.slash" : "eye") }.accessibilityLabel(show ? tr.hide : tr.show)
          }
        }
        .padding(.horizontal, 16).frame(minHeight: 56).card()
        Spacer()
      }
      .padding(Space.gutter)
      .background(C.surface)
      .navigationTitle(d.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button(d.cancel) { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button(d.ok) {
            onSave(value)
            dismiss()
          }.fontWeight(.bold).testId("dialog-save")
        }
      }
    }
    .onAppear { value = d.value }
    .presentationDetents([.medium])
  }
}

/// A short message at the bottom.
struct ToastView: View {
  let text: String
  var body: some View {
    Text(text).font(.system(size: scaled(15), weight: .semibold)).foregroundStyle(C.inverseOnSurface)
      .padding(.horizontal, 16).padding(.vertical, 14)
      .frame(maxWidth: .infinity, alignment: .leading)
      .background(C.inverseSurface, in: RoundedRectangle(cornerRadius: Radii.m, style: .continuous))
      .padding(.horizontal, 16).padding(.bottom, 8)
      .testId("toast")
  }
}
