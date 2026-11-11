// Logbook, settings, a robot's settings, the device picker and adding a robot
// (models: shared ui/Lists.kt, ui/SettingsScreens.kt, ui/Onboarding.kt).
package nl.eschouten.scraper.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.ui.Actions
import nl.eschouten.scraper.ui.AddRobotFlow
import nl.eschouten.scraper.ui.Glyph
import nl.eschouten.scraper.ui.RowId
import nl.eschouten.scraper.ui.deviceScan
import nl.eschouten.scraper.ui.Section
import nl.eschouten.scraper.ui.logbookScreen
import nl.eschouten.scraper.ui.pinDialog
import nl.eschouten.scraper.ui.robotSettingsScreen
import nl.eschouten.scraper.ui.routeNameDialog
import nl.eschouten.scraper.ui.settingsScreen
import nl.eschouten.scraper.ui.Row as RowModel

/** App bar + centred, width-limited list: the frame of every pushed screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushedScreen(
  title: String,
  nav: Nav,
  testTag: String,
  actions: @Composable () -> Unit = {},
  content: LazyListScope.() -> Unit,
) {
  val c = MaterialTheme.colorScheme
  Scaffold(
    Modifier.testTag(testTag),
    topBar = {
      TopAppBar(
        title = { Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp) },
        navigationIcon = { IconButton({ nav.back() }, Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr.back) } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface, scrolledContainerColor = c.surface),
      )
    },
    containerColor = c.surface,
  ) { pad ->
    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.xxl),
        content = content,
      )
    }
  }
}

// ---- logbook ----

@Composable
fun LogbookScreen(controller: RobotController, nav: Nav) {
  val s by controller.state.collectAsState()
  val now by rememberNow()
  val v = logbookScreen(s, now)
  var ask by remember { mutableStateOf(false) }
  val c = MaterialTheme.colorScheme
  PushedScreen(
    v.title, nav, "logbook",
    actions = {
      if (v.days.isNotEmpty()) IconButton({ ask = true }, Modifier.testTag("logbook-clear")) { Icon(Icons.Outlined.DeleteOutline, v.clearLabel) }
    },
  ) {
    if (v.days.isEmpty()) {
      item {
        Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(Glyph.Logbook.icon, null, Modifier.size(56.dp), tint = c.onSurfaceVariant)
          Spacer(Modifier.height(Space.l))
          Text(v.emptyTitle, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.testTag("logbook-empty"))
          Spacer(Modifier.height(Space.s))
          Text(v.emptyHelp, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        }
      }
    }
    for (d in v.days) {
      item(key = d.title) { SectionHeader(d.title) }
      item(key = d.title + "-rows") {
        Group(
          d.rows.map { r ->
            @Composable {
              Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                if (r.route != null) {
                  RouteBadge(r.route!!, 36.dp, solid = !r.muted, muted = r.muted)
                } else {
                  val (fg, bg) = toneColors(r.tone)
                  IconTile(r.glyph.icon, fg, bg)
                }
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f)) {
                  Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.route != null) {
                      Icon(r.glyph.icon, null, Modifier.size(14.dp), tint = toneColor(r.tone))
                      Spacer(Modifier.width(4.dp))
                    }
                    Text(r.subtitle, fontSize = 13.5.sp, color = c.onSurfaceVariant)
                  }
                }
                Text(r.time, color = c.onSurfaceVariant, fontWeight = FontWeight.SemiBold, style = tabular)
              }
            }
          },
        )
      }
    }
  }
  if (ask) {
    ConfirmDialog(v.clearConfirm, { ask = false }) {
      ask = false
      Actions.clearLogbook(controller)
    }
  }
}

// ---- settings ----

/** A settings row from the shared model. */
@Composable
fun SettingsRow(r: RowModel, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
  val c = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clickableRow(enabled = r.enabled && onClick != null) { onClick?.invoke() }
      .padding(horizontal = Space.l, vertical = 12.dp).testTag(r.testId),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when {
      r.route != null -> RouteBadge(r.route!!, 36.dp, solid = r.routeShown, muted = !r.routeShown)
      r.glyph != null -> IconTile(r.glyph!!.icon, if (r.checked) c.primary else null, if (r.checked) c.primaryContainer else null)
      else -> AppLogo(36.dp)
    }
    Spacer(Modifier.width(Space.l))
    Column(Modifier.weight(1f)) {
      Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
      if (r.subtitle.isNotEmpty()) Text(r.subtitle, fontSize = 13.5.sp, lineHeight = 17.5.sp, color = c.onSurfaceVariant)
    }
    when {
      trailing != null -> trailing()
      r.busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
      r.checked -> Icon(Icons.Rounded.Check, null, tint = c.primary)
      onClick != null && r.enabled -> Chevron()
    }
  }
}

fun LazyListScope.section(sec: Section, row: @Composable (RowModel) -> Unit) {
  item(key = "h-" + sec.title) { SectionHeader(sec.title) }
  item(key = "g-" + sec.title) { Group(sec.rows.map { r -> @Composable { row(r) } }) }
  sec.footer?.let { f ->
    item(key = "f-" + sec.title) {
      Text(f, Modifier.padding(start = Space.xs, end = Space.xs, top = Space.s), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
    }
  }
}

@Composable
fun SettingsScreen(controller: RobotController, host: AppHost, nav: Nav) {
  val s by controller.state.collectAsState()
  val now by rememberNow()
  val v = settingsScreen(s, now, host.platform)
  val scope = rememberCoroutineScope()
  var pickLanguage by remember { mutableStateOf(false) }
  var askReset by remember { mutableStateOf(false) }
  PushedScreen(v.title, nav, "settings") {
    for (sec in v.sections) {
      section(sec) { r ->
        val tap: (() -> Unit)? = when {
          r.id.startsWith("robot-") -> ({ nav.push(Route.RobotSettings(r.id.removePrefix("robot-"))) })
          r.id == RowId.ADD_ROBOT -> ({ nav.push(Route.AddRobot) })
          r.id == RowId.LOGBOOK -> ({ nav.push(Route.Logbook) })
          r.id == RowId.LANGUAGE -> ({ pickLanguage = true })
          r.id == RowId.RESET -> ({ askReset = true })
          else -> null
        }
        SettingsRow(r, tap)
      }
    }
  }
  if (pickLanguage) {
    AlertDialog(
      onDismissRequest = { pickLanguage = false },
      title = { Text(v.languageTitle) },
      text = {
        Column {
          for (l in v.languages) {
            Row(
              Modifier.fillMaxWidth().clickableRow {
                pickLanguage = false
                scope.launch { Actions.setLanguage(controller, l.id) }
              }.padding(vertical = 14.dp).testTag("language-" + l.id.ifEmpty { "phone" }),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(l.label, Modifier.weight(1f), fontSize = 16.sp)
              if (l.selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      },
      confirmButton = {},
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
  }
  if (askReset) {
    ConfirmDialog(v.resetConfirm, { askReset = false }) {
      askReset = false
      nav.home()
      scope.launch { Actions.resetSetup(controller) }
    }
  }
}

// ---- one robot ----

@Composable
fun RobotSettingsScreen(controller: RobotController, nav: Nav, uid: String) {
  val s by controller.state.collectAsState()
  val v = robotSettingsScreen(s, uid) ?: return
  val p = s.robots.first { it.uid == uid }
  val scope = rememberCoroutineScope()
  val toast = LocalToast.current
  var dialog by remember { mutableStateOf<String?>(null) } // "name", "pin", "route-X"
  var askRemove by remember { mutableStateOf(false) }
  val c = MaterialTheme.colorScheme
  PushedScreen(v.title, nav, "robot-settings") {
    for (sec in v.sections) {
      section(sec) { r ->
        val tap: (() -> Unit)? = when {
          r.id == RowId.NAME -> ({ dialog = "name" })
          r.id == RowId.DEVICE -> ({ nav.push(Route.ChooseDevice(uid)) })
          r.id == RowId.PIN -> ({ dialog = "pin" })
          r.id == RowId.TEST -> ({ scope.launch { toast(Actions.testConnection(controller, uid)) } })
          r.id == RowId.READ_ROUTES -> ({ scope.launch { toast(Actions.readRoutes(controller, uid)) } })
          r.route != null -> ({ dialog = r.id })
          else -> null
        }
        SettingsRow(
          r, tap,
          trailing = r.toggle?.let { on ->
            {
              Switch(
                on, { scope.launch { Actions.showRoute(controller, uid, r.route!!, it) } },
                Modifier.testTag(r.testId + "-switch"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = c.primary),
              )
            }
          },
        )
      }
    }
    v.remove?.let { a ->
      item {
        Spacer(Modifier.height(Space.xl))
        ActionButton(a, icon = Icons.Outlined.DeleteOutline, danger = true) { askRemove = true }
      }
    }
  }
  when (val d = dialog) {
    null -> {}
    "name" -> TextInputDialog(v.nameDialog, onDismiss = { dialog = null }) { x ->
      dialog = null
      scope.launch { Actions.rename(controller, uid, x) }
    }
    "pin" -> TextInputDialog(pinDialog(p), help = p.type.pinHelp, onDismiss = { dialog = null }) { x ->
      dialog = null
      scope.launch { Actions.setPin(controller, uid, x) }
    }
    else -> {
      val r = d.removePrefix("route-")
      TextInputDialog(routeNameDialog(p, r), onDismiss = { dialog = null }) { x ->
        dialog = null
        scope.launch { Actions.renameRoute(controller, uid, r, x) }
      }
    }
  }
  if (askRemove) {
    ConfirmDialog(v.removeConfirm, { askRemove = false }) {
      askRemove = false
      nav.back()
      scope.launch { Actions.removeRobot(controller, uid) }
    }
  }
}

@Composable
fun ChooseDeviceScreen(controller: RobotController, host: AppHost, nav: Nav, uid: String) {
  val flow = remember(uid) { deviceScan(controller.state.value, uid, host.platform) } ?: return
  val scope = rememberCoroutineScope()
  PushedScreen("Bluetooth", nav, "choose-device") {
    item {
      ScanView(flow) { id ->
        flow.select(id)
        val f = flow.selected ?: return@ScanView
        scope.launch {
          Actions.chooseDevice(controller, uid, f)
          nav.back()
        }
      }
    }
  }
}

// ---- add a robot ----

@Composable
fun AddRobotScreen(controller: RobotController, host: AppHost, nav: Nav) {
  val flow = remember { AddRobotFlow(controller, host.platform) }
  val st by flow.state.collectAsState()
  val scan by flow.scan.state.collectAsState()
  val v = flow.page(st, scan)
  val scope = rememberCoroutineScope()
  val toast = LocalToast.current
  val c = MaterialTheme.colorScheme
  PushedScreen(v.title, nav, "add-robot") {
    item { Text(v.help, color = c.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = Space.s)) }
    if (v.types.size > 1) {
      item {
        SectionHeader(v.typeLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
          for (t in v.types) FilterChip(t.selected, { flow.setModel(t.id) }, { Text(t.label) })
        }
      }
    }
    item {
      SectionHeader("Bluetooth")
      ScanView(flow.scan) { flow.scan.select(it) }
    }
    item {
      SectionHeader(v.detailsTitle)
      OutlinedTextField(
        st.label, flow::setLabel, Modifier.fillMaxWidth().testTag("add-robot-name"),
        label = { Text(v.nameLabel) }, placeholder = { Text(v.nameHint) }, singleLine = true, shape = RoundedCornerShape(Radii.m),
      )
      Spacer(Modifier.height(Space.m))
      OutlinedTextField(
        st.pin, { flow.setPin(it.filter(Char::isDigit)) }, Modifier.fillMaxWidth().testTag("add-robot-pin"),
        label = { Text(v.pinLabel) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(Radii.m),
        supportingText = { Text(v.pinHelp) },
      )
      Spacer(Modifier.height(Space.xl))
      ActionButton(v.add) {
        scope.launch {
          toast(flow.save())
          nav.home()
        }
      }
    }
  }
}
