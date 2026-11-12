// The start sheet of one route: confirm (slide to start, or a dry run), the run with its
// steps, and the result with the way out. The flow (phases, what happens on each button)
// lives here, so both apps behave the same; they only draw [RunSheetView].
package nl.eschouten.scraper.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.eschouten.scraper.app.Cancellable
import nl.eschouten.scraper.app.ControllerState
import nl.eschouten.scraper.app.LinkState
import nl.eschouten.scraper.app.Outcome
import nl.eschouten.scraper.app.Problem
import nl.eschouten.scraper.app.RobotController
import nl.eschouten.scraper.app.Widgets
import nl.eschouten.scraper.model.RunIntent
import nl.eschouten.scraper.model.RunStep
import nl.eschouten.scraper.model.ScraperModel
import nl.eschouten.scraper.texts.tr

enum class RunPhase { Confirm, Running, Done }

data class RunFlowState(
  val phase: RunPhase = RunPhase.Confirm,
  val dryRun: Boolean = false,
  val planningBefore: Boolean? = null,
  val outcome: Outcome? = null,
  val failedAt: String = "",
)

/** What is happening now, as a short headline. */
fun currentStep(model: ScraperModel, steps: List<RunStep>, link: LinkState, intent: RunIntent): String =
  if (link == LinkState.Searching) tr.searchRobot else model.nextStep(steps, intent)

data class Progress(val headline: String, val value: Float, val done: List<String>)

fun progress(model: ScraperModel, steps: List<RunStep>, link: LinkState, intent: RunIntent, routeTitle: String): Progress {
  val total = model.expectedSteps(intent)
  val v = ((steps.size + 0.5f) / (total + 1)).coerceIn(0.05f, 0.98f)
  return Progress(currentStep(model, steps, link, intent) + "...", v, steps.map { model.stepLabel(it, routeTitle) })
}

data class InfoLine(val glyph: Glyph, val text: String)

data class RunSheetView(
  val route: String,
  val title: String,
  val subtitle: String,
  /** False while running: the sheet cannot be closed. */
  val canClose: Boolean,
  val phase: RunPhase,
  // confirm
  val facts: List<InfoLine> = emptyList(),
  val slideLabel: String = "",
  val dryRunLabel: String = "",
  // running
  val progress: Progress? = null,
  val keepOpen: String = "",
  // done
  val resultGlyph: Glyph? = null,
  val resultTone: Tone? = null,
  val resultTitle: String = "",
  val resultBody: String = "",
  /** Steps that worked and where it stopped (a failed run). */
  val detailsTitle: String = "",
  val details: List<String> = emptyList(),
  val stoppedAt: String? = null,
  val detailMessage: String? = null,
  val actions: List<Action> = emptyList(),
)

class RunFlow(private val controller: RobotController, val route: String) {
  private val scope = newScope()

  private val _state = MutableStateFlow(RunFlowState())
  val state: StateFlow<RunFlowState> = _state

  /** Swift: observe without Flow. */
  fun watch(onChange: (RunFlowState) -> Unit): Cancellable = _state.watchIn(scope, onChange)

  private fun intent(st: RunFlowState) = RunIntent(route, st.dryRun, st.planningBefore)

  /** Slide to start ([dryRun] false) or the dry-run button. */
  suspend fun start(dryRun: Boolean): Outcome {
    if (_state.value.phase == RunPhase.Running) return Outcome(false, tr.busy)
    val cs = controller.state.value
    _state.value = RunFlowState(RunPhase.Running, dryRun, cs.snapshot?.planning)
    val o = controller.startRoute(route, dryRun)
    val after = controller.state.value
    val failedAt = currentStep(after.model, after.steps, after.link, intent(_state.value))
    report(after, o, dryRun)
    _state.value = _state.value.copy(phase = RunPhase.Done, outcome = o, failedAt = failedAt)
    return o
  }

  /** Back to the start (after a dry run, "try again", or a changed PIN). */
  fun back() {
    _state.value = RunFlowState()
  }

  private fun report(cs: ControllerState, o: Outcome, dryRun: Boolean) {
    val t = cs.robot.routeTitle(route)
    Widgets.report?.invoke(
      Widgets.Report(
        robotUid = cs.robot.uid,
        route = route,
        line = when {
          !o.ok -> "$t: ${o.message}"
          o.stopped -> tr.routeStoppedTitle
          dryRun -> tr.dryRunDoneShort(t)
          else -> tr.routeStarted(t)
        },
        short = when {
          !o.ok -> tr.failed
          o.stopped -> tr.stoppedShort
          dryRun -> ""
          else -> tr.started
        },
      ),
    )
  }

  fun view(cs: ControllerState, st: RunFlowState = _state.value): RunSheetView {
    val title = cs.robot.routeTitle(route)
    val subtitle = when {
      st.dryRun && st.phase != RunPhase.Confirm -> tr.dryRunNoDrive
      cs.robot.routeName(route).isEmpty() -> tr.manualRoute
      else -> tr.manualRouteLetter(route)
    }
    val base = RunSheetView(route, title, subtitle, canClose = st.phase != RunPhase.Running, phase = st.phase)
    return when (st.phase) {
      RunPhase.Confirm -> base.copy(
        facts = listOf(
          InfoLine(Glyph.Schedule, when (cs.snapshot?.planning) { true -> tr.sheetPlanningOn; false -> tr.sheetPlanningOff; null -> tr.sheetPlanningUnknown }),
          InfoLine(Glyph.Shield, tr.sheetSafe),
        ),
        slideLabel = tr.slideToStart,
        dryRunLabel = tr.dryRunFirst,
      )
      RunPhase.Running -> base.copy(
        progress = progress(cs.model, cs.steps, cs.link, intent(st), title),
        keepOpen = tr.keepAppOpen,
      )
      RunPhase.Done -> done(base, cs, st)
    }
  }

  private fun done(base: RunSheetView, cs: ControllerState, st: RunFlowState): RunSheetView {
    val o = st.outcome!!
    if (o.ok) {
      val stopped = o.stopped
      return base.copy(
        resultGlyph = when { stopped -> Glyph.Stop; st.dryRun -> Glyph.Science; else -> Glyph.Check },
        resultTone = Tone.Ok,
        resultTitle = when { stopped -> tr.routeStoppedTitle; st.dryRun -> tr.dryRunOk; else -> tr.routeStartedTitle },
        resultBody = when {
          stopped -> tr.routeStoppedMsg
          st.dryRun -> tr.dryRunOkBody
          st.planningBefore == true || cs.planningResumes -> tr.drivingResumes
          else -> tr.drivingStaysOff
        },
        actions = if (st.dryRun && !stopped) {
          listOf(Action(tr.startForReal, ActionKind.StartForReal, "run-for-real"), Action(tr.close, ActionKind.Close, "run-close", primary = false))
        } else {
          listOf(Action(tr.done, ActionKind.Done, "run-done"))
        },
      )
    }
    data class F(val g: Glyph, val title: String, val help: String, val warn: Boolean)
    val f = when (o.problem) {
      Problem.BluetoothOff -> F(
        Glyph.BluetoothOff, o.message.replace(".", ""),
        when (o.message) { tr.btOff -> tr.btOnHelp; tr.btDenied -> tr.btPermissionHelp(APP_NAME); else -> tr.btDeviceHelp },
        false,
      )
      Problem.NotFound -> F(Glyph.BluetoothOff, tr.robotUnreachable, cs.model.reachHelp, false)
      Problem.PinRejected -> F(Glyph.Lock, tr.pinWrong, cs.model.pinHelp, false)
      Problem.Busy -> F(Glyph.Pending, tr.robotBusy, tr.robotBusyHelp, true)
      Problem.NoAnswer -> F(Glyph.Visibility, tr.checkRobot, o.message, true)
      else -> F(Glyph.Error, tr.startFailed, o.message, false)
    }
    val showDetails = cs.steps.isNotEmpty() || o.problem == Problem.Failed
    val primary = when (o.problem) {
      Problem.PinRejected -> Action(tr.changePin, ActionKind.ChangePin, "run-change-pin")
      Problem.BluetoothOff, Problem.NotFound, Problem.Failed -> Action(tr.tryAgain, ActionKind.TryAgain, "run-try-again")
      else -> Action(tr.close, ActionKind.Close, "run-close")
    }
    val actions = if (o.problem != Problem.Busy && o.problem != Problem.NoAnswer) {
      listOf(primary, Action(tr.close, ActionKind.Close, "run-close", primary = false))
    } else {
      listOf(primary)
    }
    return base.copy(
      resultGlyph = f.g,
      resultTone = if (f.warn) Tone.Warn else Tone.Error,
      resultTitle = f.title,
      resultBody = f.help,
      detailsTitle = if (showDetails) tr.details else "",
      details = if (showDetails) cs.steps.map { cs.model.stepLabel(it, base.title) } else emptyList(),
      stoppedAt = if (showDetails) tr.stoppedAt(st.failedAt) else null,
      detailMessage = if (showDetails && o.problem != Problem.Failed) o.message else null,
      actions = actions,
    )
  }
}
