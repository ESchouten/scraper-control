// First start (welcome, find the robot, PIN with a connection test) and the Bluetooth scan
// (models and flows: shared ui/Onboarding.kt, ui/SettingsScreens.kt).
package nl.eschouten.scraper.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.eschouten.scraper.ui.Action
import nl.eschouten.scraper.ui.OnboardingFlow
import nl.eschouten.scraper.ui.OnboardingPage
import nl.eschouten.scraper.ui.ScanFlow
import nl.eschouten.scraper.ui.StepHeader
import nl.eschouten.scraper.ui.Tone
import kotlin.math.min

@Composable
fun OnboardingScreen(controller: RobotController, host: AppHost, nav: Nav) {
  val flow = remember { OnboardingFlow(controller, host.platform) }
  val st by flow.state.collectAsState()
  val cs by controller.state.collectAsState()
  val scope = rememberCoroutineScope()
  DisposableEffect(flow) {
    nav.onboardingBack = { flow.back() }
    onDispose { nav.onboardingBack = null }
  }
  AnimatedContent(
    st.page,
    transitionSpec = {
      if (targetState.ordinal > initialState.ordinal) {
        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
      } else {
        (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
      }
    },
    label = "onboarding",
  ) { page ->
    when (page) {
      OnboardingPage.Welcome -> Welcome(flow) {
        scope.launch {
          runCatching { host.requestBluetooth() }
          flow.goto(OnboardingPage.FindRobot)
        }
      }
      OnboardingPage.FindRobot -> {
        val v = flow.find(cs)
        StepFrame(v.header, { flow.back() }) {
          StepPage(v.title, v.lead, "onboarding-find", bottom = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              TextButton({ flow.goto(OnboardingPage.Pin) }, Modifier.testTag(v.skip.testId)) { Text(v.skip.label, fontWeight = FontWeight.Bold) }
              Spacer(Modifier.width(Space.m))
              ActionButton(v.next, Modifier.weight(1f)) { flow.goto(OnboardingPage.Pin) }
            }
          }) {
            ScanView(flow.scan) { id -> scope.launch { flow.pick(id) } }
          }
        }
      }
      OnboardingPage.Pin -> {
        val v = flow.pin(cs, st)
        StepFrame(v.header, { flow.back() }) {
          StepPage(v.title, v.lead, "onboarding-pin", bottom = {
            val done = v.done
            if (done != null) {
              ActionButton(done) { scope.launch { flow.finish() } }
            } else {
              Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ scope.launch { flow.finish() } }, Modifier.testTag(v.later.testId), enabled = v.later.enabled) { Text(v.later.label, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(Space.m))
                ActionButton(v.test.copy(primary = true), Modifier.weight(1f)) { scope.launch { flow.test() } }
              }
            }
          }) {
            OutlinedTextField(
              st.pin, { flow.setPin(it.filter(Char::isDigit)) }, Modifier.fillMaxWidth().testTag("onboarding-pin-field"),
              label = { Text(v.hint) }, singleLine = true,
              textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 8.sp),
              visualTransformation = if (st.showPin) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
              trailingIcon = { TextButton({ flow.toggleShowPin() }) { Text(v.showLabel) } },
              shape = RoundedCornerShape(Radii.m),
            )
            Spacer(Modifier.height(Space.xl))
            v.progress?.let { ProgressView(it) }
            v.result?.let { r ->
              Callout(
                if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, r.body,
                if (r.ok) Tone.Ok else Tone.Error, r.title, Modifier.testTag("onboarding-result"),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Welcome(flow: OnboardingFlow, onNext: () -> Unit) {
  val v = flow.welcome()
  Box(
    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Brand.red, Brand.redDeep))).testTag("onboarding-welcome"),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(Modifier.widthIn(max = 520.dp).fillMaxSize().safeDrawingPadding()) {
      val scroll = rememberScrollState()
      Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = Space.xl)) {
          AppLogo(72.dp, inverse = true)
          Spacer(Modifier.height(Space.xl))
          Text(v.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp, letterSpacing = (-0.8).sp)
          Spacer(Modifier.height(Space.m))
          Text(v.lead, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, lineHeight = 22.sp)
          Spacer(Modifier.height(40.dp))
          for (f in v.features) {
            Row(Modifier.padding(bottom = Space.xl)) {
              Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radii.m)).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(f.glyph.icon, null, tint = Color.White)
              }
              Spacer(Modifier.width(Space.l))
              Column(Modifier.weight(1f)) {
                Text(f.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(f.body, color = Color.White.copy(alpha = 0.82f), fontSize = 14.5.sp, lineHeight = 20.sp)
              }
            }
          }
        }
        // more below (large text): the text fades out above the button instead of running under it
        if (scroll.canScrollForward) {
          Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Brand.redDeep))))
        }
      }
      Button(
        onNext, Modifier.padding(start = 28.dp, end = 28.dp, top = Space.s, bottom = Space.xl).fillMaxWidth().heightIn(min = 54.dp).testTag(v.start.testId),
        shape = RoundedCornerShape(Radii.m), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.red),
      ) { Text(v.start.label, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
  }
}

/** Frame for steps 1 and 2: back, "Stap x van 2" and a thin progress bar. */
@Composable
private fun StepFrame(h: StepHeader, onBack: () -> Unit, content: @Composable () -> Unit) {
  val c = MaterialTheme.colorScheme
  val p by animateFloatAsState(h.progress, tween(400), label = "step")
  Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 520.dp).fillMaxSize().safeDrawingPadding()) {
      Row(Modifier.padding(start = Space.xs, top = Space.xs, end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, h.backLabel) }
        Spacer(Modifier.width(Space.xs))
        Text(h.step, color = c.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(Space.l))
        LinearProgressIndicator(
          progress = { p }, modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)),
          trackColor = c.surfaceContainerHigh, drawStopIndicator = {}, gapSize = 0.dp,
        )
      }
      Box(Modifier.weight(1f)) { content() }
    }
  }
}

@Composable
private fun StepPage(title: String, lead: String, tag: String, bottom: @Composable () -> Unit, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().testTag(tag)) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Space.xl, end = Space.xl, top = Space.xl, bottom = Space.l)) {
      Text(title, style = MaterialTheme.typography.headlineMedium)
      Spacer(Modifier.height(Space.s))
      Text(lead, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Space.xl))
      content()
    }
    Box(Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.xl)) { bottom() }
  }
}

/** Radar, the robots found, and "search again". Scans once when it appears. */
@Composable
fun ScanView(flow: ScanFlow, onSelected: (String) -> Unit) {
  val st by flow.state.collectAsState()
  val v = flow.view(st)
  val scope = rememberCoroutineScope()
  val c = MaterialTheme.colorScheme
  LaunchedEffect(flow) { if (!flow.state.value.scanned && !flow.state.value.scanning) flow.scan() }
  Column(Modifier.fillMaxWidth().testTag("scan")) {
    Radar(v.scanning, c.primary)
    Spacer(Modifier.height(8.dp))
    if (v.headline.isNotEmpty()) Text(v.headline, Modifier.fillMaxWidth().testTag("scan-headline"), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
    v.error?.let { Text(it, Modifier.padding(top = 8.dp), color = c.error) }
    Spacer(Modifier.height(12.dp))
    for (f in v.rows) {
      Card(
        onClick = { onSelected(f.id) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(f.testId),
        shape = RoundedCornerShape(Radii.l),
        colors = CardDefaults.cardColors(containerColor = if (f.selected) c.secondaryContainer else c.surfaceContainerLow),
        border = cardBorder(),
      ) {
        Row(Modifier.padding(horizontal = Space.l, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(if (f.selected) Icons.Rounded.CheckCircle else Icons.Outlined.SmartToy, null, tint = if (f.selected) c.primary else c.onSurfaceVariant)
          Spacer(Modifier.width(Space.l))
          Column {
            Text(f.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(f.detail, fontSize = 13.5.sp, color = c.onSurfaceVariant)
          }
        }
      }
    }
    v.emptyHelp?.let {
      Text(it, color = c.onSurfaceVariant, lineHeight = 19.sp, modifier = Modifier.testTag("scan-empty"))
      Spacer(Modifier.height(12.dp))
    }
    if (v.nameHelp != null || v.emptyHelp != null) {
      OutlinedTextField(
        st.name, flow::setName, Modifier.fillMaxWidth(), label = { Text(v.nameLabel) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Badge, null) }, shape = RoundedCornerShape(Radii.m),
      )
      Spacer(Modifier.height(12.dp))
    }
    ActionButton(v.again, icon = Icons.Rounded.Refresh) { scope.launch { flow.scan() } }
  }
}

@Composable
private fun Radar(scanning: Boolean, color: Color) {
  val t = rememberInfiniteTransition(label = "radar")
  val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
  Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val maxR = min(size.width, size.height) / 2
      val ctr = Offset(size.width / 2, size.height / 2)
      val inner = 30.dp.toPx()
      if (!scanning) {
        drawCircle(color.copy(alpha = 0.08f), maxR * 0.75f, ctr)
      } else {
        for (i in 0 until 3) {
          val p = (phase + i / 3f) % 1f
          drawCircle(color.copy(alpha = 0.22f * (1 - p)), inner + (maxR - inner) * p, ctr)
        }
      }
    }
    Box(Modifier.size(60.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
      Icon(nl.eschouten.scraper.ui.Glyph.BluetoothSearching.icon, null, Modifier.size(30.dp), tint = Color.White)
    }
  }
}
