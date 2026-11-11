// The shared Glyph words as Material icons (iOS: SF Symbols in Glyphs.swift).
package nl.eschouten.scraper.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import nl.eschouten.scraper.ui.Glyph

val Glyph.icon: ImageVector
  get() = when (this) {
    Glyph.Check -> Icons.Rounded.Check
    Glyph.Stop -> Icons.Rounded.Stop
    Glyph.Science -> Icons.Outlined.Science
    Glyph.BluetoothOff -> Icons.Rounded.BluetoothDisabled
    Glyph.BluetoothSearching -> Icons.Rounded.BluetoothSearching
    Glyph.Bluetooth -> Icons.Rounded.Bluetooth
    Glyph.Lock -> Icons.Outlined.Lock
    Glyph.Pending -> Icons.Outlined.Pending
    Glyph.Visibility -> Icons.Outlined.Visibility
    Glyph.Error -> Icons.Outlined.ErrorOutline
    Glyph.Schedule -> Icons.Rounded.Schedule
    Glyph.Shield -> Icons.Outlined.VerifiedUser
    Glyph.Touch -> Icons.Outlined.TouchApp
    Glyph.Robot -> Icons.Outlined.SmartToy
    Glyph.Add -> Icons.Rounded.Add
    Glyph.Widgets -> Icons.Outlined.Widgets
    Glyph.Translate -> Icons.Rounded.Translate
    Glyph.Logbook -> Icons.Rounded.History
    Glyph.Restart -> Icons.Rounded.RestartAlt
    Glyph.Badge -> Icons.Outlined.Badge
    Glyph.Delete -> Icons.Outlined.DeleteOutline
    Glyph.Info -> Icons.Outlined.Info
    Glyph.Dialpad -> Icons.Rounded.Dialpad
    Glyph.Phone -> Icons.Rounded.PhoneAndroid
    Glyph.Play -> Icons.Rounded.PlayArrow
    Glyph.Refresh -> Icons.Rounded.Refresh
    Glyph.Chevron -> Icons.Rounded.ChevronRight
  }
