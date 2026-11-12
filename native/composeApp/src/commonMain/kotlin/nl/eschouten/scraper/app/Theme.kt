// Scraper Control design system (the same values as the iOS app's Theme.swift). Brand red for brand surfaces and the one primary action on a screen; the rest
// is warm neutral. Status is never colour alone: always an icon and a word as well.
package nl.eschouten.scraper.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.eschouten.scraper.ui.Tone

object Brand {
  val red = Color(0xFFC8102E)
  val redDeep = Color(0xFF9B0C24)
  val redOnDark = Color(0xFFE5394F)
  val redLight = Color(0xFFE0243F)
  val ink = Color(0xFF1C1B1B)
}

@Immutable
data class StatusColors(val ok: Color, val okSoft: Color, val warn: Color, val warnSoft: Color, val idle: Color) {
  companion object {
    val light = StatusColors(Color(0xFF1B7F3B), Color(0xFFE3F4E8), Color(0xFF8F5B00), Color(0xFFFFF1D6), Color(0xFF8A8584))
    val dark = StatusColors(Color(0xFF7EDC9A), Color(0xFF16351F), Color(0xFFFFC66B), Color(0xFF3A2A0C), Color(0xFF9C9695))
  }
}

val LocalStatus = staticCompositionLocalOf { StatusColors.light }

/** Spacing (4-pt grid) and radii, so screens line up. */
object Space {
  val xs = 4.dp
  val s = 8.dp
  val m = 12.dp
  val l = 16.dp
  val xl = 24.dp
  val xxl = 32.dp
  val gutter = 20.dp
}

object Radii {
  val s = 10.dp
  val m = 14.dp
  val l = 20.dp
  val xl = 28.dp
}

private val light = lightColorScheme(
  primary = Brand.red,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFFFE5E7),
  onPrimaryContainer = Color(0xFF5F0013),
  secondaryContainer = Color(0xFFF3E2E2),
  onSecondaryContainer = Color(0xFF2B1A1A),
  background = Color(0xFFF6F4F3),
  surface = Color(0xFFF6F4F3),
  onSurface = Brand.ink,
  onSurfaceVariant = Color(0xFF5E5857),
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color.White,
  surfaceContainer = Color(0xFFF0EDEC),
  surfaceContainerHigh = Color(0xFFE9E5E4),
  surfaceContainerHighest = Color(0xFFE2DDDC),
  outlineVariant = Color(0xFFE4DFDE),
  outline = Color(0xFF8A8584),
  error = Color(0xFFA8200D),
  errorContainer = Color(0xFFFCE8E4),
  onErrorContainer = Color(0xFF5C0F04),
  inverseSurface = Brand.ink,
  inverseOnSurface = Color.White,
)

private val dark = darkColorScheme(
  primary = Brand.redOnDark,
  onPrimary = Color.White,
  primaryContainer = Color(0xFF5B1320),
  onPrimaryContainer = Color(0xFFFFDADC),
  secondaryContainer = Color(0xFF3A2A2A),
  onSecondaryContainer = Color(0xFFF3E2E2),
  background = Color(0xFF141212),
  surface = Color(0xFF141212),
  onSurface = Color(0xFFEDE7E6),
  onSurfaceVariant = Color(0xFFB8B0AF),
  surfaceContainerLowest = Color(0xFF0F0D0D),
  surfaceContainerLow = Color(0xFF1E1B1B),
  surfaceContainer = Color(0xFF231F1F),
  surfaceContainerHigh = Color(0xFF2B2726),
  surfaceContainerHighest = Color(0xFF353030),
  outlineVariant = Color(0xFF3B3535),
  outline = Color(0xFF9C9695),
  error = Color(0xFFFFB4A8),
  errorContainer = Color(0xFF4A120A),
  onErrorContainer = Color(0xFFFFDAD3),
  inverseSurface = Color(0xFFEDE7E6),
  inverseOnSurface = Brand.ink,
)

private fun typography(): Typography {
  val t = Typography()
  return t.copy(
    headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
    labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp, fontSize = 16.sp),
  )
}

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalStatus provides if (darkTheme) StatusColors.dark else StatusColors.light) {
    MaterialTheme(
      colorScheme = if (darkTheme) dark else light,
      typography = typography(),
      shapes = Shapes(
        extraSmall = RoundedCornerShape(Radii.s),
        small = RoundedCornerShape(Radii.m),
        medium = RoundedCornerShape(Radii.l),
        large = RoundedCornerShape(Radii.xl),
        extraLarge = RoundedCornerShape(Radii.xl),
      ),
      content = content,
    )
  }
}

/** Foreground and soft background of a tone. */
@Composable
fun toneColors(t: Tone): Pair<Color, Color> {
  val c = MaterialTheme.colorScheme
  val st = LocalStatus.current
  return when (t) {
    Tone.Ok -> st.ok to st.okSoft
    Tone.Warn -> st.warn to st.warnSoft
    Tone.Idle -> st.idle to c.surfaceContainer
    Tone.Error -> c.error to c.errorContainer
    Tone.Brand -> c.primary to c.primaryContainer
    Tone.Neutral -> c.onSurface to c.surfaceContainer
  }
}

@Composable
fun toneColor(t: Tone) = toneColors(t).first

val ColorScheme.isDark get() = surface.red < 0.5f

val tabular = TextStyle(fontFeatureSettings = "tnum")
