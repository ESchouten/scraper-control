// The robot's panel, the Lely Discovery's own screen: connected only while the screen is open
// and the app is in front.
// The flow (connect, keys, drop on leaving, reconnect on coming back) lives here, so both apps
// behave the same; they draw [PanelScreen].
package nl.eschouten.scraper.lely

import nl.eschouten.scraper.core.*
import nl.eschouten.scraper.link.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.eschouten.scraper.app.Cancellable
import nl.eschouten.scraper.app.ControllerState
import nl.eschouten.scraper.app.Outcome
import nl.eschouten.scraper.app.RobotController
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.ui.Action
import nl.eschouten.scraper.ui.ActionKind
import nl.eschouten.scraper.ui.newScope
import nl.eschouten.scraper.ui.watchIn

data class PanelState(val held: Boolean = false, val error: String? = null, val resume: Boolean = false)

class PanelFlow(private val controller: RobotController) {
  private val scope = newScope()

  private val _state = MutableStateFlow(PanelState())
  val state: StateFlow<PanelState> = _state

  fun watch(onChange: (PanelState) -> Unit): Cancellable = _state.watchIn(scope, onChange)

  /** The connect button. */
  suspend fun connect() {
    if (_state.value.held) return
    _state.value = PanelState(held = true)
    val o = controller.hold()
    if (!o.ok) _state.value = PanelState(error = o.message)
  }

  /** The "live · disconnect" chip, and leaving the screen. */
  suspend fun disconnect() {
    if (!_state.value.held) return
    _state.value = _state.value.copy(held = false)
    controller.release()
  }

  /** The app went to the background: drop the link, connect again when it comes back. */
  suspend fun pause() {
    val held = _state.value.held
    disconnect()
    _state.value = _state.value.copy(resume = held)
  }

  suspend fun resume() {
    if (_state.value.resume) {
      _state.value = _state.value.copy(resume = false)
      connect()
    }
  }

  /** A key; the message for a toast when it failed, else null. */
  suspend fun press(key: RobotKey): String? {
    val o: Outcome = controller.custom { (it as LelySession).press(key) }
    return if (o.ok) null else o.message
  }

  fun view(cs: ControllerState, st: PanelState = _state.value): PanelScreen {
    val live = (controller.session as? LelySession)?.live
    return panelScreen(cs, st.held, st.error, live?.screen, live?.highlighted)
  }
}

// ---- what the apps draw ----

data class PanelKey(val key: RobotKey, val label: String, val testId: String)

data class PanelScreen(
  val title: String,
  val robotName: String,
  /** The robot's display, line by line, as the core read it. */
  val lines: List<String>,
  /** The selected row (drawn in brand red), "" if none. */
  val highlighted: String,
  val dimmed: Boolean,
  val keysEnabled: Boolean,
  /** 3 rows of 3, in the robot's own layout. */
  val keys: List<List<PanelKey>>,
  val connected: Boolean,
  val disconnectLabel: String,
  /** The connect button while not connected (null when connected). */
  val connect: Action?,
  val connecting: Boolean,
  val error: String?,
  val info: String,
)

/** [held]: this screen asked for the connection; [error]: why that failed. */
fun panelScreen(s: ControllerState, held: Boolean, error: String?, screen: String?, highlighted: String?): PanelScreen {
  val connected = s.connected && held
  val connecting = held && !s.connected
  val text = when {
    connected && screen != null -> screen
    connecting -> "\n   ${tr.connectingDots}"
    else -> "\n   ${tr.notConnected}"
  }
  return PanelScreen(
    title = tr.panel,
    robotName = s.robot.displayName,
    lines = text.split('\n'),
    highlighted = if (connected) (highlighted ?: "").trim() else "",
    dimmed = !connected,
    keysEnabled = connected,
    keys = listOf(
      listOf(PanelKey(RobotKey.Soft1, tr.softLeft, "key-soft1"), PanelKey(RobotKey.Soft2, tr.softMiddle, "key-soft2"), PanelKey(RobotKey.Soft3, tr.softRight, "key-soft3")),
      listOf(PanelKey(RobotKey.Up, tr.keyUp, "key-up"), PanelKey(RobotKey.StartStop, "Start/Stop", "key-startstop"), PanelKey(RobotKey.Back, "Esc", "key-back")),
      listOf(PanelKey(RobotKey.Down, tr.keyDown, "key-down"), PanelKey(RobotKey.Prog, "Prog", "key-prog"), PanelKey(RobotKey.Enter, "Enter", "key-enter")),
    ),
    connected = connected,
    disconnectLabel = tr.liveDisconnect,
    connect = if (connected) null else Action(if (connecting) tr.connectingDots else tr.connectPanel, ActionKind.Confirm, "panel-connect", enabled = !connecting && !s.isBusy),
    connecting = connecting,
    error = if (connected) null else error,
    info = tr.panelInfo,
  )
}
