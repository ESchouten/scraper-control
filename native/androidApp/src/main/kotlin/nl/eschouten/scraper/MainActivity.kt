package nl.eschouten.scraper

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.eschouten.scraper.app.App
import nl.eschouten.scraper.app.AppHost
import nl.eschouten.scraper.app.Nav
import nl.eschouten.scraper.ui.Platform

class MainActivity : ComponentActivity(), AppHost {
  override val platform = Platform.Android
  private val front = MutableStateFlow(false)
  override val foreground: StateFlow<Boolean> get() = front
  private val nav = Nav()
  private var asked: CompletableDeferred<Unit>? = null
  private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked?.complete(Unit) }

  override suspend fun requestBluetooth() {
    val want = if (Build.VERSION.SDK_INT >= 31) {
      arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val d = CompletableDeferred<Unit>()
    asked = d
    permissions.launch(want)
    d.await()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val controller = (application as ScraperApp).controller(intent.getStringExtra("demo"))
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (!nav.back()) moveTaskToBack(true)
        }
      },
    )
    setContent { App(controller, this, nav) }
  }

  override fun onStart() {
    super.onStart()
    front.value = true
  }

  override fun onStop() {
    front.value = false
    super.onStop()
  }
}
