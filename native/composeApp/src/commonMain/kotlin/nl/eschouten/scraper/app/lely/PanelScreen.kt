// The Lely Discovery's own screen: the robot's control panel, live display and the same keys
// (model and flow: shared lely/Panel.kt). Connected only while open and the app is in front.
// Package app (like the other screens), folder lely.
package nl.eschouten.scraper.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.eschouten.scraper.core.RobotKey
import nl.eschouten.scraper.lely.PanelFlow
import nl.eschouten.scraper.lely.PanelKey
import nl.eschouten.scraper.texts.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(controller: RobotController, host: AppHost, nav: Nav) {
  val flow = remember { PanelFlow(controller) }
  val cs by controller.state.collectAsState()
  val st by flow.state.collectAsState()
  val front by host.foreground.collectAsState()
  val v = flow.view(cs, st)
  val scope = rememberCoroutineScope()
  val toast = LocalToast.current
  val haptic = LocalHapticFeedback.current
  val c = MaterialTheme.colorScheme
  val ok = LocalStatus.current.ok

  LaunchedEffect(front) { if (front) flow.resume() else flow.pause() }
  DisposableEffect(Unit) { onDispose { CoroutineScope(Dispatchers.Default).launch { flow.disconnect() } } }

  Scaffold(
    Modifier.testTag("panel"),
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(v.title, fontWeight = FontWeight.ExtraBold)
            Text(v.robotName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVariant)
          }
        },
        navigationIcon = { IconButton({ nav.back() }, Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr.back) } },
        actions = {
          if (v.connected) {
            AssistChip(
              { scope.launch { flow.disconnect() } }, { Text(v.disconnectLabel) },
              Modifier.padding(end = Space.m).testTag("panel-disconnect"),
              leadingIcon = { Dot(ok) },
              colors = AssistChipDefaults.assistChipColors(containerColor = c.surfaceContainerLow),
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface),
      )
    },
    containerColor = c.surface,
  ) { pad ->
    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
      Column(
        Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState())
          .padding(start = Space.gutter, end = Space.gutter, top = Space.xs, bottom = Space.xl),
      ) {
        Lcd(v.lines, v.highlighted, v.dimmed)
        Spacer(Modifier.height(Space.l))
        Keypad(v.keys, v.keysEnabled, onUp = flow::keyUp) { k ->
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          flow.keyDown(k, toast)
        }
        Spacer(Modifier.height(Space.l))
        v.error?.let {
          Callout(nl.eschouten.scraper.ui.Glyph.BluetoothOff.icon, it, nl.eschouten.scraper.ui.Tone.Error)
          Spacer(Modifier.height(Space.m))
        }
        v.connect?.let { a ->
          Button(
            { scope.launch { flow.connect() } }, Modifier.fillMaxWidth().height(54.dp).testTag(a.testId),
            enabled = a.enabled, shape = RoundedCornerShape(Radii.m),
          ) {
            if (v.connecting) {
              CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp, color = c.onPrimary)
            } else {
              Icon(Icons.Rounded.BluetoothConnected, null)
            }
            Spacer(Modifier.width(Space.s))
            Text(a.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
          }
          Spacer(Modifier.height(Space.m))
        }
        Row {
          Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
          Spacer(Modifier.width(Space.s))
          Text(v.info, fontSize = 13.sp, lineHeight = 18.sp, color = c.onSurfaceVariant)
        }
      }
    }
  }
}

/** Same keys in the same place as the robot's panel. */
@Composable
private fun Keypad(rows: List<List<PanelKey>>, enabled: Boolean, onUp: () -> Unit, onDown: (RobotKey) -> Unit) {
  Column {
    for (row in rows) {
      Row {
        for (k in row) {
          Box(Modifier.weight(1f).padding(5.dp)) { Key(k, enabled, { onDown(k.key) }, onUp) }
        }
      }
    }
  }
}

/** Held down as long as the finger is on it, as a key on the robot's own panel. */
@Composable
private fun Key(k: PanelKey, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
  val shape = RoundedCornerShape(Radii.m)
  var pressed by remember { mutableStateOf(false) }
  val down by rememberUpdatedState(onDown)
  val up by rememberUpdatedState(onUp)
  Box(
    Modifier.fillMaxWidth().aspectRatio(1.25f).alpha(if (enabled) 1f else 0.45f).shadow(6.dp, shape).clip(shape)
      .background(Brush.verticalGradient(listOf(Color(0xFFD8283F), Brand.red, Brand.redDeep)))
      .pointerInput(enabled) {
        if (enabled) {
          detectTapGestures(onPress = {
            pressed = true
            down()
            try {
              tryAwaitRelease()
            } finally {
              up()
              pressed = false
            }
          })
        }
      }
      .semantics {
        contentDescription = k.label
        role = Role.Button
        if (enabled) onClick { down(); up(); true } else disabled()
      }
      .testTag(k.testId),
    contentAlignment = Alignment.Center,
  ) {
    if (pressed) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.18f)))
    KeyFace(k.key)
  }
}

@Composable
private fun KeyFace(key: RobotKey) {
  val w = Color.White
  when (key) {
    RobotKey.Soft1, RobotKey.Soft2, RobotKey.Soft3 -> Box(Modifier.size(20.dp).background(w, CircleShape))
    RobotKey.Up, RobotKey.Down -> Canvas(Modifier.size(38.dp, 30.dp)) {
      val up = key == RobotKey.Up
      val p = Path().apply {
        if (up) {
          moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height)
        } else {
          moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height)
        }
        close()
      }
      drawPath(p, w)
    }
    RobotKey.StartStop -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("START", color = w, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
      Box(Modifier.padding(vertical = 3.dp).size(44.dp, 1.5.dp).background(w))
      Text("STOP", color = w, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
    }
    RobotKey.Back -> Text("Esc", color = w, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    RobotKey.Prog -> Icon(Icons.AutoMirrored.Rounded.List, null, Modifier.size(36.dp), tint = w)
    RobotKey.Enter -> Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, null, Modifier.size(36.dp), tint = w)
    else -> {}
  }
}
