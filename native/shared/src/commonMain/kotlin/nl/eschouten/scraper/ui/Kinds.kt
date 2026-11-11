// Words for what a screen shows that each app draws in its own way: a tone (colour) and a
// glyph (icon). Compose maps them to Material icons, SwiftUI to SF Symbols.
package nl.eschouten.scraper.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The scope of a screen's flow. */
internal fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

enum class Tone { Ok, Warn, Idle, Error, Brand, Neutral }

enum class Glyph {
  Check, Stop, Science, BluetoothOff, BluetoothSearching, Bluetooth, Lock, Pending, Visibility, Error,
  Schedule, Shield, Touch, Robot, Add, Widgets, Translate, Logbook, Restart, Badge, Delete, Info, Dialpad,
  Phone, Play, Refresh, Chevron,
}

enum class Platform { Android, Ios, Desktop }

/** A button: what it says, what it does (the app handles [action]), and its test id. */
data class Action(val label: String, val action: ActionKind, val testId: String, val primary: Boolean = true, val enabled: Boolean = true)

enum class ActionKind {
  StartForReal, Close, Done, ChangePin, TryAgain, // run sheet
  Confirm, Cancel, // dialogs
}

/** A question with three answers: [yes], [no] and [later] (leave it for now). */
data class AnswerQuestion(val glyph: Glyph, val title: String, val body: String, val yes: String, val no: String, val later: String)

/** A question before something that changes the robot. */
data class Confirm(val glyph: Glyph, val title: String, val body: String, val ok: String, val cancel: String)
