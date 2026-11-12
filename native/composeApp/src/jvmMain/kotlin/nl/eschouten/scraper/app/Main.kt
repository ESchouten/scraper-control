// The desktop app (macOS, Windows, Linux). Bluetooth through Kable (btleplug).
//   ./gradlew :composeApp:run                         the real robot
//   ./gradlew :composeApp:run --args="--demo"          the in-app demo robot
//   ./gradlew :composeApp:run --args="--demo 'planning=off setup=done'"
//   SCRAPER_SIMULATOR=<simulator script> ./gradlew :composeApp:run   a simulated robot (see the driver)
package nl.eschouten.scraper.app

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import nl.eschouten.scraper.ui.Platform

object DesktopHost : AppHost {
  override val platform = Platform.Desktop
}

fun demoWords(args: Array<String>): String? {
  val i = args.indexOf("--demo")
  if (i >= 0) return args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") } ?: ""
  return System.getenv("SCRAPER_DEMO")
}

// The window's icon (taskbar, window switcher; the Mac Dock uses the .app's icon.icns).
private fun appIcon(): BitmapPainter? =
  DesktopHost::class.java.getResourceAsStream("/icon.png")?.use {
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readAllBytes()).toComposeImageBitmap())
  }

fun main(args: Array<String>) {
  val icon = appIcon()
  val demo = demoWords(args)
  val controller = AppStart.controller(FileStore(), demo)
  val nav = Nav()
  application {
    Window(
      onCloseRequest = {
        controller.dispose()
        exitApplication()
      },
      title = "Scraper Control",
      icon = icon,
      state = rememberWindowState(width = 420.dp, height = 820.dp),
      onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && nav.back() },
    ) {
      App(controller, DesktopHost, nav)
    }
  }
}
