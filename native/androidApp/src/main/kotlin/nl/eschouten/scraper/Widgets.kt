// Home-screen widgets: routes A-D of a robot, and one route as a tile. What they show and
// what a tap does come from the shared module (app/WidgetBridge.kt); this file only draws
// RemoteViews and runs the tap in the background.
package nl.eschouten.scraper

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.eschouten.scraper.app.PrefsStore
import nl.eschouten.scraper.app.Settings
import nl.eschouten.scraper.app.WidgetBridge
import nl.eschouten.scraper.app.WidgetNotice
import nl.eschouten.scraper.texts.tr

object WidgetViews {
  private val buttons = mapOf("A" to R.id.route_a, "B" to R.id.route_b, "C" to R.id.route_c, "D" to R.id.route_d)

  fun store(c: Context) = PrefsStore(c).also { Settings.load(it) } // also sets the app's language

  /** Redraw every widget (after a run, a rename, ...). */
  fun refreshAll(c: Context) {
    val m = AppWidgetManager.getInstance(c)
    for (cls in listOf(RoutesWidget::class.java, RouteTileWidget::class.java)) {
      val ids = m.getAppWidgetIds(ComponentName(c, cls))
      for (id in ids) m.updateAppWidget(id, views(c, id, cls == RouteTileWidget::class.java))
    }
  }

  fun views(c: Context, widgetId: Int, tile: Boolean): RemoteViews {
    val store = store(c)
    val robotUid = store.getString("w.robot.$widgetId")
    return if (tile) {
      val t = WidgetBridge.tile(store, robotUid, store.getString("w.route.$widgetId"))
      RemoteViews(c.packageName, R.layout.widget_tile).apply {
        setTextViewText(R.id.letter, t.route)
        setTextViewText(R.id.name, t.title)
        setTextViewText(R.id.detail, t.detail)
        setOnClickPendingIntent(R.id.route_root, tap(c, widgetId * 10, t.robotUid, t.route))
      }
    } else {
      val b = WidgetBridge.bar(store, robotUid)
      RemoteViews(c.packageName, R.layout.widget_routes).apply {
        setTextViewText(R.id.title, b.title)
        val shown = b.routes.associateBy { it.route }
        buttons.forEach { (letter, view) ->
          setViewVisibility(view, if (letter in shown) View.VISIBLE else View.GONE)
          setOnClickPendingIntent(view, tap(c, widgetId * 10 + 1 + "ABCD".indexOf(letter), b.robotUid, letter))
          shown[letter]?.let { setContentDescription(view, tr.startRoute(it.title)) }
        }
        setTextViewText(R.id.status, b.status)
        setOnClickPendingIntent(
          R.id.status,
          PendingIntent.getActivity(c, widgetId, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
      }
    }
  }

  private fun tap(c: Context, code: Int, robotUid: String?, route: String): PendingIntent {
    val i = Intent(c, WidgetTapReceiver::class.java).setAction("nl.eschouten.scraper.ROUTE")
      .putExtra("route", route).putExtra("robot", robotUid)
    return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }
}

class RoutesWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    ids.forEach { manager.updateAppWidget(it, WidgetViews.views(context, it, tile = false)) }
  }
}

class RouteTileWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    ids.forEach { manager.updateAppWidget(it, WidgetViews.views(context, it, tile = true)) }
  }
}

/** A widget tap: connect, start the route, disconnect, in the background (up to 55 s). */
class WidgetTapReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val route = intent.getStringExtra("route") ?: return
    val robot = intent.getStringExtra("robot")
    val app = context.applicationContext
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
      try {
        val store = WidgetViews.store(app)
        val out = withTimeoutOrNull(55_000) {
          WidgetBridge.tap(store, robot, route) { WidgetViews.refreshAll(app) }
        }
        WidgetBridge.notice(store, robot, route, out)?.let { WidgetNotify.show(app, it) }
        (app as? ScraperApp)?.widgetRan()
      } finally {
        pending.finish()
      }
    }
  }
}

/** A failed widget start as a notification: the widget itself only shows "Mislukt". */
object WidgetNotify {
  private const val CHANNEL = "widget-failures"
  private const val ID = 7

  /** Android 13+: notifications need the user's OK, asked when a widget is placed. */
  fun needsPermission(c: Context) =
    Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

  fun show(c: Context, n: WidgetNotice) {
    if (needsPermission(c)) return
    val m = c.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= 26) {
      m.createNotificationChannel(NotificationChannel(CHANNEL, tr.widgetFailuresChannel, NotificationManager.IMPORTANCE_HIGH))
    }
    val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    @Suppress("DEPRECATION")
    val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CHANNEL) else android.app.Notification.Builder(c)
    m.notify(
      ID,
      b.setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle(n.title)
        .setContentText(n.body)
        .setStyle(android.app.Notification.BigTextStyle().bigText(n.body))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build(),
    )
  }
}

/** When a widget is placed: pick the robot (and for the tile, the route). Skipped when there
 *  is only one choice. */
class WidgetConfigureActivity : Activity() {
  private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setResult(RESULT_CANCELED)
    widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
      finish()
      return
    }
    val tile = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className == RouteTileWidget::class.java.name
    val store = WidgetViews.store(this)
    val options = WidgetBridge.choices(store, tile)
    if (options.isEmpty()) {
      AlertDialog.Builder(this).setTitle(tr.setupFirst).setMessage(tr.setupFirstBody)
        .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
      return
    }
    if (options.size == 1) {
      done(options[0].robotUid, options[0].route)
      return
    }
    AlertDialog.Builder(this).setTitle(if (tile) tr.whichRoute else tr.whichRobot)
      .setItems(options.map { it.label }.toTypedArray()) { _, i -> done(options[i].robotUid, options[i].route) }
      .setOnCancelListener { finish() }.show()
  }

  private fun done(robotUid: String, route: String?) {
    val store = WidgetViews.store(this)
    store.putString("w.robot.$widgetId", robotUid)
    if (route != null) store.putString("w.route.$widgetId", route)
    WidgetViews.refreshAll(this)
    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
    // a failed start from the widget is reported as a notification: ask for that now
    if (WidgetNotify.needsPermission(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) else finish()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    finish()
  }
}
