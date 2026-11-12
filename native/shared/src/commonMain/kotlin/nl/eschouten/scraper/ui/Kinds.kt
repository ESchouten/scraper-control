// Words for what a screen shows that each app draws in its own way: a tone (colour) and a
// glyph (icon). Compose maps them to Material icons, SwiftUI to SF Symbols.
package nl.eschouten.scraper.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.eschouten.scraper.app.Cancellable
import nl.eschouten.scraper.app.immediateOrDefault

/** The scope of a screen's flow. */
internal fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** The flows' watch() (for Swift, which has no Flow): [onChange] on the main thread with the
 *  current state and every change, until cancelled. */
internal fun <T> StateFlow<T>.watchIn(scope: CoroutineScope, onChange: (T) -> Unit): Cancellable {
  val job = scope.launch(Dispatchers.Main.immediateOrDefault()) { collect { onChange(it) } }
  return Cancellable { job.cancel() }
}

enum class Tone { Ok, Warn, Idle, Error, Brand, Neutral }

enum class Glyph {
  Check, Stop, Science, BluetoothOff, BluetoothSearching, Bluetooth, Lock, Pending, Visibility, Error,
  Schedule, Shield, Touch, Robot, Add, Widgets, Translate, Logbook, Restart, Badge, Delete, Dialpad,
  Phone, Play, Refresh,
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
