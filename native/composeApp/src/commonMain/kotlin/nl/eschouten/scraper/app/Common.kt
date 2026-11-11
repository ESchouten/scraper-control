// Small building blocks shared by all screens.
package nl.eschouten.scraper.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.eschouten.scraper.texts.tr
import nl.eschouten.scraper.ui.Action
import nl.eschouten.scraper.ui.AnswerQuestion
import nl.eschouten.scraper.ui.Confirm
import nl.eschouten.scraper.ui.TextDialog
import nl.eschouten.scraper.ui.Tone

/** Show a short message at the bottom. */
val LocalToast = compositionLocalOf<(String) -> Unit> { {} }

/** Letter badge for a route (A-D). */
@Composable
fun RouteBadge(letter: String, size: Dp = 52.dp, solid: Boolean = false, muted: Boolean = false) {
  val c = MaterialTheme.colorScheme
  val bg = when { muted -> c.surfaceContainerHigh; solid -> c.primary; else -> c.primaryContainer }
  val fg = when { muted -> c.onSurfaceVariant; solid -> c.onPrimary; else -> c.onPrimaryContainer }
  Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(bg), contentAlignment = Alignment.Center) {
    Text(letter, color = fg, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.ExtraBold, lineHeight = (size.value * 0.46f).sp)
  }
}

/** Title above a group of rows. */
@Composable
fun SectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
  Row(Modifier.fillMaxWidth().padding(start = Space.xs, top = Space.xl, bottom = Space.s).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      title.uppercase(), Modifier.weight(1f),
      fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    trailing?.invoke()
  }
}

/** Card look used everywhere: no elevation, hairline border. */
@Composable
fun cardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
fun cardBorder() = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (MaterialTheme.colorScheme.isDark) 1f else 0.7f))

/** Inset grouped list: rows in one card with hairlines between them. */
@Composable
fun Group(rows: List<@Composable () -> Unit>) {
  Card(shape = RoundedCornerShape(Radii.l), colors = cardColors(), border = cardBorder()) {
    rows.forEachIndexed { i, r ->
      if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
      r()
    }
  }
}

/** Leading icon in a soft square, for list rows. */
@Composable
fun IconTile(icon: ImageVector, color: Color? = null, background: Color? = null) {
  val c = MaterialTheme.colorScheme
  Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(background ?: c.surfaceContainer), contentAlignment = Alignment.Center) {
    Icon(icon, null, Modifier.size(20.dp), tint = color ?: c.onSurface)
  }
}

/** Icon + text callout. */
@Composable
fun Callout(icon: ImageVector, text: String, tone: Tone = Tone.Neutral, title: String? = null, modifier: Modifier = Modifier) {
  val (fg, bg) = toneColors(tone)
  val c = MaterialTheme.colorScheme
  Row(modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.m)).background(bg).padding(Space.l)) {
    Icon(icon, null, Modifier.size(20.dp), tint = fg)
    Spacer(Modifier.width(Space.m))
    Column(Modifier.weight(1f)) {
      if (title != null) {
        Text(title, fontWeight = FontWeight.ExtraBold, color = c.onSurface)
        Spacer(Modifier.height(2.dp))
      }
      Text(text, color = c.onSurface, fontSize = 14.5.sp, lineHeight = 20.sp)
    }
  }
}

@Composable
fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/** A shared [Action] as a full-width button: filled when primary, else outlined. */
@Composable
fun ActionButton(a: Action, modifier: Modifier = Modifier, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
  val m = modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(a.testId)
  val shape = RoundedCornerShape(Radii.m)
  val content: @Composable () -> Unit = {
    if (icon != null) {
      Icon(icon, null, Modifier.size(20.dp))
      Spacer(Modifier.width(Space.s))
    }
    Text(a.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
  }
  if (a.primary) {
    Button(onClick, m, enabled = a.enabled, shape = shape) { content() }
  } else {
    OutlinedButton(
      onClick, m, enabled = a.enabled, shape = shape,
      border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
    ) { content() }
  }
}

/** App mark: a rounded square with a play button in a ring. [inverse]: white tile, red mark. */
@Composable
fun AppLogo(size: Dp = 96.dp, inverse: Boolean = false) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val fg = if (inverse) Brand.red else Color.White
    val r = CornerRadius(w * 0.27f)
    if (inverse) {
      drawRoundRect(Color.White, cornerRadius = r)
    } else {
      drawRoundRect(Brush.linearGradient(listOf(Brand.redLight, Brand.red, Brand.redDeep), Offset.Zero, Offset(w, w)), cornerRadius = r)
    }
    drawCircle(fg, w * 0.29f, Offset(w / 2, w / 2), style = Stroke(w * 0.065f))
    val t = Path().apply {
      moveTo(w * 0.435f, w * 0.375f)
      lineTo(w * 0.435f, w * 0.625f)
      lineTo(w * 0.64f, w * 0.50f)
      close()
    }
    drawPath(t, fg, style = Fill)
    drawPath(t, fg, style = Stroke(w * 0.05f, join = StrokeJoin.Round))
  }
}

/** A yes/no question before something that changes the robot or the app. */
@Composable
fun ConfirmDialog(q: Confirm, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(q.glyph.icon, null) },
    title = { Text(q.title, textAlign = TextAlign.Center) },
    text = { Text(q.body) },
    confirmButton = { Button(onConfirm, Modifier.testTag("confirm-ok")) { Text(q.ok) } },
    dismissButton = { TextButton(onDismiss, Modifier.testTag("confirm-cancel")) { Text(q.cancel) } },
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  )
}

/** A question with three answers (yes / no / later). Dismissing it = later. */
@Composable
fun ChoiceDialog(q: AnswerQuestion, onAnswer: (Boolean?) -> Unit) {
  AlertDialog(
    onDismissRequest = { onAnswer(null) },
    icon = { Icon(q.glyph.icon, null) },
    title = { Text(q.title, textAlign = TextAlign.Center) },
    text = { Text(q.body) },
    confirmButton = {
      Column(horizontalAlignment = Alignment.End) {
        Button({ onAnswer(true) }, Modifier.testTag("choice-yes")) { Text(q.yes) }
        Button({ onAnswer(false) }, Modifier.testTag("choice-no")) { Text(q.no) }
        TextButton({ onAnswer(null) }, Modifier.testTag("choice-later")) { Text(q.later) }
      }
    },
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  )
}

/** Free text (a name) or, with [TextDialog.secret], a PIN. */
@Composable
fun TextInputDialog(d: TextDialog, help: String? = null, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var v by remember { mutableStateOf(d.value) }
  var show by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = if (d.secret) ({ Icon(Icons.Outlined.Lock, null) }) else null,
    title = { Text(d.title) },
    text = {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (help != null) {
          Text(help, textAlign = TextAlign.Center)
          Spacer(Modifier.height(16.dp))
        }
        OutlinedTextField(
          v, { v = if (d.secret) it.filter(Char::isDigit) else it.take(24) },
          Modifier.fillMaxWidth().testTag("dialog-field"),
          placeholder = { Text(d.hint) },
          singleLine = true,
          textStyle = if (d.secret) MaterialTheme.typography.headlineSmall.copy(letterSpacing = 8.sp, textAlign = TextAlign.Center) else MaterialTheme.typography.bodyLarge,
          visualTransformation = if (d.secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
          keyboardOptions = if (d.secret) KeyboardOptions(keyboardType = KeyboardType.NumberPassword) else KeyboardOptions.Default,
          trailingIcon = if (d.secret) ({
            IconButton({ show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (show) tr.hide else tr.show) }
          }) else null,
          shape = RoundedCornerShape(Radii.m),
        )
      }
    },
    confirmButton = { Button({ onSave(v) }, Modifier.testTag("dialog-save")) { Text(d.ok) } },
    dismissButton = { TextButton(onDismiss) { Text(d.cancel) } },
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  )
}

/** The robot's control panel display, as the core read it. */
@Composable
fun Lcd(lines: List<String>, highlighted: String, dimmed: Boolean, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.l)).background(Color(0xFF141211)).padding(3.dp)) {
    Column(
      Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(Radii.l - 3.dp)).background(Color(0xFF4A4A4A))
        .border(1.5.dp, Color(0xFF6A6A6A), RoundedCornerShape(Radii.l - 3.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag("lcd"),
      verticalArrangement = Arrangement.Top,
    ) {
      val a = if (dimmed) 0.35f else 1f
      for (l in lines) {
        val hl = highlighted.isNotEmpty() && l.trim() == highlighted
        Text(
          l.ifEmpty { " " },
          Modifier.then(if (hl) Modifier.background(Brand.red) else Modifier).padding(horizontal = 2.dp),
          color = Color.White.copy(alpha = a), fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 19.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
        )
      }
    }
  }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** Rounded square tile for a robot or a type screen. */
@Composable
fun SquareIcon(icon: ImageVector, size: Dp = 52.dp) {
  val c = MaterialTheme.colorScheme
  Box(Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
    Icon(icon, null, tint = c.onSurface)
  }
}

/** A tappable list row. */
fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit) = clickable(enabled = enabled, onClick = onClick)
