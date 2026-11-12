// The start sheet of one route (model and flow: shared ui/RunSheet.kt), the robot switcher,
// and the slide-to-confirm control.
package nl.eschouten.scraper.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.ui.ActionKind
import nl.eschouten.scraper.ui.Actions
import nl.eschouten.scraper.ui.Glyph
import nl.eschouten.scraper.ui.Progress
import nl.eschouten.scraper.ui.RunFlow
import nl.eschouten.scraper.ui.RunPhase
import nl.eschouten.scraper.ui.RunSheetView
import nl.eschouten.scraper.ui.pinDialog
import nl.eschouten.scraper.ui.switcherSheet
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSheet(controller: RobotController, route: String, onClose: () -> Unit) {
  val flow = remember(route) { RunFlow(controller, route) }
  val cs by controller.state.collectAsState()
  val st by flow.state.collectAsState()
  val v = flow.view(cs, st)
  val canClose by rememberUpdatedState(v.canClose)
  val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || canClose })
  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current
  var editPin by remember { mutableStateOf(false) }
  val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }
  val start: (Boolean) -> Unit = { dry ->
    scope.launch {
      val o = flow.start(dry)
      haptic.performHapticFeedback(if (o.ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
    }
  }

  ModalBottomSheet(
    onDismissRequest = { if (canClose) onClose() },
    sheetState = sheet,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(topStart = Radii.xl, topEnd = Radii.xl),
    modifier = Modifier.testTag("run-sheet"),
  ) {
    Column(Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, bottom = Space.xl).navigationBarsPadding().animateContentSize()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RouteBadge(v.route, 56.dp, solid = true)
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
          Text(v.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(v.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("run-subtitle"))
        }
      }
      Spacer(Modifier.height(Space.xl))
      when (v.phase) {
        RunPhase.Confirm -> ConfirmPart(v, onSlide = { start(false) }, onDryRun = { start(true) })
        RunPhase.Running -> RunningPart(v)
        RunPhase.Done -> DonePart(v) { kind ->
          when (kind) {
            ActionKind.StartForReal, ActionKind.TryAgain -> flow.back()
            ActionKind.ChangePin -> editPin = true
            else -> close()
          }
        }
      }
    }
  }
  if (editPin) {
    TextInputDialog(pinDialog(cs.robot), help = cs.model.pinHelp, onDismiss = { editPin = false }) { pin ->
      editPin = false
      scope.launch {
        Actions.setPin(controller, cs.robot.uid, pin)
        flow.back()
      }
    }
  }
}

@Composable
private fun ConfirmPart(v: RunSheetView, onSlide: () -> Unit, onDryRun: () -> Unit) {
  Column {
    for (f in v.facts) {
      Row(Modifier.padding(bottom = Space.m)) {
        IconTile(f.glyph.icon)
        Spacer(Modifier.width(Space.m))
        Text(f.text, Modifier.weight(1f).padding(top = 7.dp), fontSize = 14.5.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
      }
    }
    Spacer(Modifier.height(Space.m))
    SlideToConfirm(v.slideLabel, Modifier.testTag("run-slide"), onConfirmed = onSlide)
    Spacer(Modifier.height(Space.s))
    TextButton(onDryRun, Modifier.fillMaxWidth().height(48.dp).testTag("run-dry")) {
      Icon(Icons.Outlined.Science, null, Modifier.size(20.dp))
      Spacer(Modifier.width(Space.s))
      Text(v.dryRunLabel, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun RunningPart(v: RunSheetView) {
  val c = MaterialTheme.colorScheme
  Column(Modifier.testTag("run-progress")) {
    ProgressView(v.progress!!)
    Spacer(Modifier.height(Space.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Glyph.Phone.icon, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
      Spacer(Modifier.width(Space.s))
      Text(v.keepOpen, fontSize = 13.sp, color = c.onSurfaceVariant)
    }
  }
}

/** Headline, progress bar and the steps so far (also used by onboarding's PIN test). */
@Composable
fun ProgressView(p: Progress) {
  val c = MaterialTheme.colorScheme
  val st = LocalStatus.current
  val value by animateFloatAsState(p.value, tween(350), label = "progress")
  Column {
    Text(p.headline, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("progress-headline"))
    Spacer(Modifier.height(Space.m))
    LinearProgressIndicator(
      progress = { value }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
      trackColor = c.surfaceContainerHigh, drawStopIndicator = {}, gapSize = 0.dp,
    )
    Spacer(Modifier.height(Space.l))
    for (d in p.done) {
      Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyph.Check.icon, null, Modifier.size(18.dp), tint = st.ok)
        Spacer(Modifier.width(Space.m))
        Text(d, color = c.onSurfaceVariant, fontSize = 14.5.sp)
      }
    }
  }
}

@Composable
private fun DonePart(v: RunSheetView, onAction: (ActionKind) -> Unit) {
  val c = MaterialTheme.colorScheme
  val st = LocalStatus.current
  val (fg, bg) = toneColors(v.resultTone!!)
  val pop = remember { Animatable(0.6f) }
  LaunchedEffect(v.resultGlyph) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
  var details by remember { mutableStateOf(false) }
  Column(Modifier.testTag("run-result")) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Box(Modifier.size(84.dp).scale(pop.value).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(v.resultGlyph!!.icon, null, Modifier.size(44.dp), tint = fg)
      }
    }
    Spacer(Modifier.height(Space.l))
    Text(v.resultTitle, Modifier.fillMaxWidth().testTag("run-result-title"), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(Space.s))
    Text(v.resultBody, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = c.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp)
    if (v.detailsTitle.isNotEmpty()) {
      Spacer(Modifier.height(Space.m))
      TextButton({ details = !details }, Modifier.testTag("run-details")) {
        Text(v.detailsTitle, fontSize = 14.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.Bold)
      }
      if (details) {
        for (d in v.details) StepLine(Glyph.Check.icon, st.ok, d)
        v.stoppedAt?.let { StepLine(Icons.Rounded.Close, c.error, it) }
        v.detailMessage?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = c.onSurfaceVariant) }
      }
    }
    Spacer(Modifier.height(Space.xl))
    v.actions.forEachIndexed { i, a ->
      if (i > 0) Spacer(Modifier.height(Space.s))
      ActionButton(a, icon = if (a.action == ActionKind.StartForReal) Icons.Rounded.PlayArrow else null) { onAction(a.action) }
    }
  }
}

@Composable
private fun StepLine(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, text: String) {
  Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(18.dp), tint = color)
    Spacer(Modifier.width(Space.m))
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
  }
}

/** Drag the thumb to the end to confirm: harder to trigger by accident than a button, which
 *  matters for something that makes a robot drive. Screen readers get a plain tap action. */
@Composable
fun SlideToConfirm(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onConfirmed: () -> Unit) {
  val c = MaterialTheme.colorScheme
  val h = 64.dp
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  var x by remember { mutableFloatStateOf(0f) }
  var done by remember { mutableStateOf(false) }
  BoxWithConstraints(
    modifier.fillMaxWidth().height(h).alpha(if (enabled) 1f else 0.5f)
      .semantics {
        role = Role.Button
        contentDescription = label
        if (enabled) onClick(label) { onConfirmed(); true }
      },
  ) {
    val max = with(LocalDensity.current) { (maxWidth - h).toPx() }
    val p = if (max <= 0f) 0f else (x / max).coerceIn(0f, 1f)
    val hPx = with(LocalDensity.current) { h.toPx() }
    Box(Modifier.matchParentSize().clip(RoundedCornerShape(h / 2)).background(c.primaryContainer))
    Row(Modifier.matchParentSize().padding(start = h * 0.8f).alpha(1 - p), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
      Text(label, color = c.onPrimaryContainer, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
      Spacer(Modifier.width(6.dp))
      Icon(Icons.Rounded.KeyboardDoubleArrowRight, null, tint = c.onPrimaryContainer.copy(alpha = 0.6f))
    }
    Box(
      Modifier.width(with(LocalDensity.current) { (x + hPx).toDp() }).height(h).clip(RoundedCornerShape(h / 2))
        .background(c.primary.copy(alpha = 0.25f * p)),
    )
    Box(
      Modifier.offset { IntOffset(x.roundToInt(), 0) }.size(h).padding(5.dp)
        .draggable(
          rememberDraggableState { d -> x = (x + d).coerceIn(0f, max) },
          Orientation.Horizontal, enabled = enabled && !done,
          onDragStopped = {
            if (x >= max * 0.9f) {
              x = max
              done = true
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onConfirmed()
            } else {
              animate(x, 0f, animationSpec = tween(250)) { v, _ -> x = v }
            }
          },
        )
        .testTag("slide-thumb"),
    ) {
      Surface(Modifier.size(h - 10.dp).shadow(6.dp, CircleShape), shape = CircleShape, color = c.primary) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(30.dp), tint = c.onPrimary) }
      }
    }
  }
}

/** Pick the robot to work with, or add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotSwitcher(controller: RobotController, nav: Nav, onClose: () -> Unit) {
  val s by controller.state.collectAsState()
  val now by rememberNow()
  val v = switcherSheet(s, now)
  val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  val c = MaterialTheme.colorScheme
  val hide: (() -> Unit) -> Unit = { then -> scope.launch { sheet.hide() }.invokeOnCompletion { onClose(); then() } }
  ModalBottomSheet(onClose, sheetState = sheet, containerColor = c.surfaceContainerLow, modifier = Modifier.testTag("switcher")) {
    Column(Modifier.padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl).navigationBarsPadding()) {
      Text(v.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Space.m))
      Group(
        v.robots.map { r ->
          @Composable {
            Row(
              Modifier.fillMaxWidth().clickableRow { hide { scope.launch { Actions.selectRobot(controller, r.uid) } } }.padding(horizontal = Space.l, vertical = Space.m).testTag(r.testId),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconTile(Glyph.Robot.icon)
              Spacer(Modifier.width(Space.l))
              Column(Modifier.weight(1f)) {
                Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(r.summary, fontSize = 13.5.sp, color = c.onSurfaceVariant)
              }
              if (r.active) Icon(Icons.Rounded.CheckCircle, null, tint = c.primary)
            }
          }
        } + listOf(
          @Composable {
            Row(
              Modifier.fillMaxWidth().clickableRow { hide { nav.push(Route.AddRobot) } }.padding(horizontal = Space.l, vertical = Space.m).testTag("switcher-add"),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconTile(Glyph.Add.icon)
              Spacer(Modifier.width(Space.l))
              Text(v.addLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
          },
        ),
      )
    }
  }
}

