// First start: welcome, find the robot, PIN (with a connection test). And adding a robot
// later. The flow lives here; the apps draw the pages.
package nl.eschouten.scraper.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.eschouten.scraper.app.Cancellable
import nl.eschouten.scraper.app.ControllerState
import nl.eschouten.scraper.app.Outcome
import nl.eschouten.scraper.app.Problem
import nl.eschouten.scraper.app.RobotController
import nl.eschouten.scraper.app.RobotProfile
import nl.eschouten.scraper.model.RunIntent
import nl.eschouten.scraper.model.scraperModels
import nl.eschouten.scraper.texts.tr

enum class OnboardingPage { Welcome, FindRobot, Pin }

data class OnboardingState(
  val page: OnboardingPage = OnboardingPage.Welcome,
  val pin: String = "",
  val showPin: Boolean = false,
  val testing: Boolean = false,
  val result: Outcome? = null,
)

data class Feature(val glyph: Glyph, val title: String, val body: String)

data class WelcomePage(val title: String, val lead: String, val features: List<Feature>, val disclaimer: String, val start: Action)

data class StepHeader(val step: String, val progress: Float, val backLabel: String)

data class FindPage(val header: StepHeader, val title: String, val lead: String, val skip: Action, val next: Action)

data class PinResult(val ok: Boolean, val title: String, val body: String)

data class PinPage(
  val header: StepHeader,
  val title: String,
  val lead: String,
  val hint: String,
  val showLabel: String,
  val progress: Progress?,
  val result: PinResult?,
  val test: Action,
  val later: Action,
  /** After a successful test. */
  val done: Action?,
)

class OnboardingFlow(private val controller: RobotController, val platform: Platform) {
  private val scope = newScope()

  private val _state = MutableStateFlow(OnboardingState(pin = controller.state.value.robot.pin))
  val state: StateFlow<OnboardingState> = _state

  /** The scan on the "find robot" page. */
  val scan = ScanFlow(
    controller.state.value.model, selectedId = controller.state.value.robot.robotId,
    initialName = controller.state.value.robot.robotName, platform = platform, scope = scope,
  )

  fun watch(onChange: (OnboardingState) -> Unit): Cancellable = _state.watchIn(scope, onChange)

  fun goto(p: OnboardingPage) {
    _state.value = _state.value.copy(page = p, result = null)
  }

  /** Back from a page; false on the first page (leave the app). */
  fun back(): Boolean {
    val p = _state.value.page
    if (p == OnboardingPage.Welcome) return false
    goto(OnboardingPage.entries[p.ordinal - 1])
    return true
  }

  /** A robot was picked in the scan: remember it. */
  suspend fun pick(id: String) {
    scan.select(id)
    val f = scan.selected ?: return
    controller.updateRobot(controller.state.value.robot.copy(robotId = f.id, robotName = f.name, link = f.link))
  }

  fun setPin(p: String) {
    _state.value = _state.value.copy(pin = p, result = null)
  }

  fun toggleShowPin() {
    _state.value = _state.value.copy(showPin = !_state.value.showPin)
  }

  /** Save the PIN, then connect once and read the home screen. */
  suspend fun test(): Outcome {
    controller.updateRobot(controller.state.value.robot.copy(pin = _state.value.pin.trim()))
    _state.value = _state.value.copy(testing = true, result = null)
    val o = controller.refresh()
    _state.value = _state.value.copy(testing = false, result = o)
    return o
  }

  /** "Done" or "test later": save the PIN and finish. */
  suspend fun finish() {
    controller.updateRobot(controller.state.value.robot.copy(pin = _state.value.pin.trim()))
    controller.updateSettings { onboarded = true }
  }

  private fun header(i: Int) = StepHeader(tr.stepOf(i, 2), i / 2f, tr.back)

  fun welcome() = WelcomePage(
    tr.welcomeTitle,
    tr.welcomeLead(APP_NAME, scraperModels.joinToString(", ") { it.fullName }),
    listOf(
      Feature(Glyph.Touch, tr.featureTapTitle, tr.featureTapBody),
      Feature(Glyph.Schedule, tr.featurePlanningTitle, tr.featurePlanningBody),
      Feature(Glyph.Shield, tr.featureSafeTitle, tr.featureSafeBody),
    ),
    tr.disclaimer,
    Action(tr.getStarted, ActionKind.Confirm, "onboarding-start"),
  )

  fun find(s: ControllerState) = FindPage(
    header(1), tr.searchRobot, tr.findLead,
    skip = Action(tr.skip, ActionKind.Cancel, "onboarding-skip", primary = false),
    next = Action(tr.next, ActionKind.Confirm, "onboarding-next", enabled = s.robot.robotId != null),
  )

  fun pin(s: ControllerState, st: OnboardingState = _state.value): PinPage {
    val o = st.result
    val result = o?.let {
      if (it.ok) {
        PinResult(
          true, tr.connectedWith(s.robot.robotName),
          when (s.snapshot?.planning) { true -> tr.readyPlanningOn; false -> tr.readyPlanningOff; null -> tr.ready },
        )
      } else {
        PinResult(false, tr.thatFailed, if (it.problem == Problem.PinRejected) tr.pinRejectedHelp(s.model.pinHelp) else it.message)
      }
    }
    return PinPage(
      header(2), tr.pin, s.model.pinHelp, tr.pin, if (st.showPin) tr.hide else tr.show,
      progress = if (st.testing) progress(s.model, s.steps, s.link, RunIntent(), "") else null,
      result = result,
      test = Action(if (st.testing) tr.busyDots else tr.testConnection, ActionKind.TryAgain, "onboarding-test", primary = o?.ok != true, enabled = !st.testing),
      later = Action(tr.testLater, ActionKind.Cancel, "onboarding-later", primary = false, enabled = !st.testing),
      done = if (o?.ok == true) Action(tr.doneToApp, ActionKind.Done, "onboarding-done") else null,
    )
  }
}

// ---- add a robot later ----

data class AddRobotPage(
  val title: String,
  val help: String,
  val typeLabel: String,
  val types: List<Choice>,
  val detailsTitle: String,
  val nameLabel: String,
  val nameHint: String,
  val pinLabel: String,
  val pinHelp: String,
  val add: Action,
)

data class AddRobotState(val modelId: String = scraperModels.first().id, val label: String = "", val pin: String = "", val saving: Boolean = false)

class AddRobotFlow(private val controller: RobotController, platform: Platform) {
  private val scope = newScope()

  private val _state = MutableStateFlow(AddRobotState())
  val state: StateFlow<AddRobotState> = _state

  fun watch(onChange: (AddRobotState) -> Unit): Cancellable = _state.watchIn(scope, onChange)

  val scan = ScanFlow(
    scraperModels.first(), exclude = controller.state.value.robots.map { it.robotId }.toSet(),
    platform = platform, scope = scope,
  )

  fun setModel(id: String) {
    _state.value = _state.value.copy(modelId = id)
  }

  fun setLabel(v: String) {
    _state.value = _state.value.copy(label = v)
  }

  fun setPin(v: String) {
    _state.value = _state.value.copy(pin = v)
  }

  /** [scan] is the scan's state, so the button follows the choice. */
  fun page(st: AddRobotState = _state.value, scan: ScanState = this.scan.state.value): AddRobotPage {
    val m = scraperModels.first { it.id == st.modelId }
    val chosen = scan.found.any { it.id == scan.selectedId }
    return AddRobotPage(
      tr.addRobot, tr.addRobotHelp, tr.robotType,
      scraperModels.map { Choice(it.id, it.fullName, it.id == st.modelId) },
      tr.details2, tr.nameInApp, tr.exampleBarn, tr.pin, m.pinHelp,
      Action(if (st.saving) tr.busyDots else tr.add, ActionKind.Confirm, "add-robot-save", enabled = !st.saving && chosen),
    )
  }

  /** Adds the robot, makes it active and tests the connection once. The message for a toast. */
  suspend fun save(): String {
    val f = scan.selected ?: return tr.noRobotNearby
    val st = _state.value
    _state.value = st.copy(saving = true)
    controller.addRobot(RobotProfile(model = st.modelId, robotId = f.id, robotName = f.name, link = f.link, label = st.label.trim(), pin = st.pin.trim()))
    val o = controller.refresh()
    _state.value = _state.value.copy(saving = false)
    return if (o.ok) tr.robotAdded(controller.state.value.robot.displayName) else o.message
  }
}
