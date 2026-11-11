// Root of the Compose app (Android and desktop): onboarding or home, a small screen stack,
// sheets and toasts. What each screen shows and does comes from the shared module (ui/);
// this file only decides which screen is on top.
package nl.eschouten.scraper.app

import nl.eschouten.scraper.ui.finishQuestion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import nl.eschouten.scraper.app.RobotController
import nl.eschouten.scraper.ui.Platform

/** What the platform gives the UI. */
interface AppHost {
  val platform: Platform

  /** Ask for the Bluetooth permissions (Android); nothing elsewhere. */
  suspend fun requestBluetooth() {}

  /** True while the app is in front (the panel connects only then). */
  val foreground: StateFlow<Boolean> get() = alwaysForeground
}

private val alwaysForeground = MutableStateFlow(true)

/** The screens above home. */
sealed interface Route {
  data object Settings : Route
  data class RobotSettings(val uid: String) : Route
  data class ChooseDevice(val uid: String) : Route
  data object Logbook : Route
  data object Panel : Route
  data object AddRobot : Route
}

/** The screen stack; the platform's back goes through [back]. */
class Nav {
  val stack = mutableStateListOf<Route>()
  /** Onboarding has its own back (set while it is shown). */
  var onboardingBack: (() -> Boolean)? = null

  fun push(r: Route) {
    stack += r
  }

  /** False when there is nothing to go back to (leave the app). */
  fun back(): Boolean {
    onboardingBack?.let { return it() }
    if (stack.isEmpty()) return false
    stack.removeAt(stack.lastIndex)
    return true
  }

  fun home() = stack.clear()
}

/** Current time for "3 min ago", ticking every 20 s. */
@Composable
fun rememberNow(): State<Long> {
  val now = remember { androidx.compose.runtime.mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(20_000)
      now.longValue = Clock.System.now().toEpochMilliseconds()
    }
  }
  return now
}

@Composable
fun App(controller: RobotController, host: AppHost, nav: Nav = remember { Nav() }) {
  val s by controller.state.collectAsState()
  val snack = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val toast: (String) -> Unit = { m ->
    if (m.isNotBlank()) scope.launch {
      snack.currentSnackbarData?.dismiss()
      snack.showSnackbar(m)
    }
  }
  AppTheme {
    CompositionLocalProvider(LocalToast provides toast) {
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
          if (!s.onboarded) {
            OnboardingScreen(controller, host, nav)
          } else {
            AnimatedContent(
              nav.stack.toList(),
              transitionSpec = {
                if (targetState.size >= initialState.size) {
                  (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
                } else {
                  fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
              },
              contentKey = { it.size to it.lastOrNull() },
              label = "screens",
            ) { st ->
              when (val r = st.lastOrNull()) {
                null -> HomeScreen(controller, host, nav)
                Route.Settings -> SettingsScreen(controller, host, nav)
                is Route.RobotSettings -> RobotSettingsScreen(controller, nav, r.uid)
                is Route.ChooseDevice -> ChooseDeviceScreen(controller, host, nav, r.uid)
                Route.Logbook -> LogbookScreen(controller, nav)
                Route.Panel -> PanelScreen(controller, host, nav)
                Route.AddRobot -> AddRobotScreen(controller, host, nav)
              }
            }
          }
          // After a stop the robot asks whether to finish the route: the user decides.
          finishQuestion(s)?.let { q -> ChoiceDialog(q) { controller.answerFinish(it) } }
          SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { d ->
            Snackbar(
              shape = MaterialTheme.shapes.small,
              containerColor = MaterialTheme.colorScheme.inverseSurface,
              contentColor = MaterialTheme.colorScheme.inverseOnSurface,
              modifier = Modifier.testTag("toast"),
            ) { Text(d.visuals.message, fontWeight = FontWeight.SemiBold) }
          }
        }
      }
    }
  }
}
