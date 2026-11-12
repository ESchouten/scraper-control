// Home: robot status at a glance and one card per route (model: shared ui/Home.kt).
package nl.eschouten.scraper.app

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.eschouten.scraper.ui.Actions
import nl.eschouten.scraper.ui.Fact
import nl.eschouten.scraper.ui.FactId
import nl.eschouten.scraper.ui.Glyph
import nl.eschouten.scraper.ui.HomeScreen
import nl.eschouten.scraper.ui.ModelScreenCard
import nl.eschouten.scraper.ui.RouteCard
import nl.eschouten.scraper.ui.StatePill
import nl.eschouten.scraper.ui.Tone
import nl.eschouten.scraper.ui.homeScreen
import nl.eschouten.scraper.ui.planningConfirm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(controller: RobotController, host: AppHost, nav: Nav) {
  val s by controller.state.collectAsState()
  val now by rememberNow()
  val h = homeScreen(s, now, host.platform)
  val scope = rememberCoroutineScope()
  val toast = LocalToast.current
  var runRoute by remember { mutableStateOf<String?>(null) }
  var switcher by remember { mutableStateOf(false) }
  var askPlanning by remember { mutableStateOf(false) }
  var refreshing by remember { mutableStateOf(false) }
  val refresh: () -> Unit = {
    scope.launch { Actions.refresh(controller)?.let(toast) }
  }

  PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = {
      refreshing = true
      scope.launch {
        Actions.refresh(controller)?.let(toast)
        refreshing = false
      }
    },
    modifier = Modifier.fillMaxSize().testTag("home"),
  ) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
      item { Header(h, onSettings = { nav.push(Route.Settings) }, onSwitch = { switcher = true }, onPill = refresh, busy = s.isBusy, onFact = { if (it.editable) askPlanning = true }) }
      if (h.routesTitle != null) {
        item {
          Box(Modifier.padding(horizontal = Space.gutter)) {
            SectionHeader(h.routesTitle!!) {
              TextButton({ nav.push(Route.RobotSettings(s.robot.uid)) }, Modifier.testTag("home-edit")) { Text(h.editLabel, fontWeight = FontWeight.Bold) }
            }
          }
        }
        h.allHidden?.let { item { Callout(Icons.Outlined.VisibilityOff, it, modifier = Modifier.padding(horizontal = Space.gutter)) } }
        items(h.routes, key = { it.testId }) { r ->
          RouteCardView(r, Modifier.padding(horizontal = Space.gutter, vertical = Space.m / 2)) { runRoute = r.route }
        }
      }
      if (h.modelTitle != null) {
        item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(h.modelTitle!!) } }
        items(h.modelScreens, key = { it.testId }) { m ->
          ModelScreenView(m, Modifier.padding(horizontal = Space.gutter, vertical = Space.m / 2)) { nav.push(Route.Panel) }
        }
      }
      h.widgetTip?.let { tip ->
        item {
          WidgetTip(tip, h.closeLabel, Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.m)) {
            scope.launch { Actions.dismissWidgetTip(controller) }
          }
        }
      }
    }
  }

  runRoute?.let { r -> RunSheet(controller, r, onClose = { runRoute = null }) }
  if (switcher) RobotSwitcher(controller, nav, onClose = { switcher = false })
  if (askPlanning) {
    ConfirmDialog(planningConfirm(s), onDismiss = { askPlanning = false }) {
      askPlanning = false
      scope.launch { toast(Actions.togglePlanning(controller)) }
    }
  }
}

/** Red brand header with the robot's name and state, and the facts card overlapping it. */
@Composable
private fun Header(h: HomeScreen, onSettings: () -> Unit, onSwitch: () -> Unit, onPill: () -> Unit, busy: Boolean, onFact: (Fact) -> Unit) {
  val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  Box {
    Column {
      Column(
        Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Brand.red, Brand.redDeep)))
          .padding(start = Space.gutter, top = top + Space.s, end = Space.s, bottom = 72.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          AppLogo(26.dp, inverse = true)
          Spacer(Modifier.width(10.dp))
          Text(h.appName, Modifier.weight(1f), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
          IconButton(onSettings, Modifier.testTag("home-settings")) { Icon(Icons.Outlined.Settings, h.settingsLabel, tint = Color.White) }
        }
        Spacer(Modifier.height(Space.l))
        Row(
          Modifier.clip(RoundedCornerShape(Radii.s)).clickable(onClick = onSwitch).semantics { contentDescription = h.switchLabel }.testTag("home-robot"),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(h.robotName, Modifier.weight(1f, fill = false), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Spacer(Modifier.width(4.dp))
          Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(30.dp), tint = Color.White)
        }
        h.robotCount?.let { Text(it, Modifier.padding(top = 2.dp), color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp) }
        Spacer(Modifier.height(Space.s))
        Pill(h.pill, enabled = !busy, onClick = onPill)
      }
      Spacer(Modifier.height(52.dp))
    }
    Facts(h.facts, Modifier.align(Alignment.BottomCenter).padding(horizontal = Space.gutter), onFact)
  }
}

@Composable
private fun Pill(p: StatePill, enabled: Boolean, onClick: () -> Unit) {
  val dot = when (p.dot) {
    Tone.Ok -> Color(0xFF7EF0A6)
    Tone.Warn -> Color(0xFFFFD27A)
    Tone.Idle -> Color.White.copy(alpha = 0.54f)
    null -> null
    else -> Color.White.copy(alpha = 0.7f)
  }
  Surface(
    onClick = onClick, enabled = enabled && p.tappable, shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.16f),
    modifier = Modifier.testTag("home-pill"),
  ) {
    Row(Modifier.padding(start = 10.dp, top = 6.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      if (p.spinning) {
        CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
      } else if (dot != null) {
        Dot(dot)
      }
      Spacer(Modifier.width(8.dp))
      Text(p.text, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("home-pill-text"))
      if (!p.spinning) {
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Refresh, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = 0.7f))
      }
    }
  }
}

@Composable
private fun Facts(facts: List<Fact>, modifier: Modifier, onFact: (Fact) -> Unit) {
  val c = MaterialTheme.colorScheme
  Surface(
    modifier.fillMaxWidth().heightIn(min = 92.dp).shadow(24.dp, RoundedCornerShape(Radii.l), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f)),
    shape = RoundedCornerShape(Radii.l), color = c.surfaceContainerLow,
  ) {
    // large text: labels may take two lines, the card grows with them
    Row(Modifier.height(IntrinsicSize.Min).heightIn(min = 92.dp)) {
      facts.forEachIndexed { i, f ->
        if (i > 0) VerticalDivider(Modifier.padding(vertical = 20.dp), color = c.outlineVariant)
        FactView(f, Modifier.weight(1f)) { onFact(f) }
      }
    }
  }
}

@Composable
private fun FactView(f: Fact, modifier: Modifier, onClick: () -> Unit) {
  val c = MaterialTheme.colorScheme
  Column(
    modifier.fillMaxHeight().clickable(enabled = f.editable, onClick = onClick).padding(horizontal = Space.m, vertical = Space.m).testTag(f.testId),
    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(f.label, fontSize = 12.5.sp, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 15.sp)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      f.dot?.let {
        Dot(toneColor(it))
        Spacer(Modifier.width(6.dp))
      }
      Text(f.value, Modifier.weight(1f, fill = false).testTag(f.testId + "-value"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp, maxLines = 1, style = tabular)
      if (f.editable && f.id == FactId.Planning) {
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun RouteCardView(r: RouteCard, modifier: Modifier, onClick: () -> Unit) {
  val c = MaterialTheme.colorScheme
  Card(
    onClick = onClick, enabled = r.enabled, modifier = modifier.fillMaxWidth().testTag(r.testId),
    shape = RoundedCornerShape(Radii.l), colors = cardColors(), border = cardBorder(),
  ) {
    Row(Modifier.padding(start = Space.l, top = Space.l, end = Space.m, bottom = Space.l), verticalAlignment = Alignment.CenterVertically) {
      RouteBadge(r.route, solid = true)
      Spacer(Modifier.width(Space.l))
      Column(Modifier.weight(1f)) {
        Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(r.subtitle, color = c.onSurfaceVariant, fontSize = 13.5.sp)
      }
      Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (r.enabled) c.primaryContainer else c.surfaceContainerHigh)
          .semantics { contentDescription = r.startLabel },
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(28.dp), tint = if (r.enabled) c.primary else c.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun ModelScreenView(m: ModelScreenCard, modifier: Modifier, onClick: () -> Unit) {
  val c = MaterialTheme.colorScheme
  Card(onClick = onClick, modifier = modifier.fillMaxWidth().testTag(m.testId), shape = RoundedCornerShape(Radii.l), colors = cardColors(), border = cardBorder()) {
    Row(Modifier.padding(start = Space.l, top = Space.l, end = Space.m, bottom = Space.l), verticalAlignment = Alignment.CenterVertically) {
      SquareIcon(Glyph.Dialpad.icon)
      Spacer(Modifier.width(Space.l))
      Column(Modifier.weight(1f)) {
        Text(m.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(m.subtitle, color = c.onSurfaceVariant, fontSize = 13.5.sp)
      }
      Chevron()
    }
  }
}

@Composable
private fun WidgetTip(text: String, closeLabel: String, modifier: Modifier, onClose: () -> Unit) {
  val c = MaterialTheme.colorScheme
  Row(
    modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.l)).background(c.surfaceContainer).padding(start = Space.l, top = Space.m, end = Space.xs, bottom = Space.m).testTag("home-widget-tip"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconTile(Glyph.Widgets.icon)
    Spacer(Modifier.width(Space.m))
    Text(text, Modifier.weight(1f), fontSize = 13.5.sp, lineHeight = 18.sp, color = c.onSurface)
    IconButton(onClose, Modifier.testTag("home-widget-tip-close")) { Icon(Icons.Rounded.Close, closeLabel, Modifier.size(20.dp)) }
  }
}
