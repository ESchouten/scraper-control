// The last 50 things the app or the widget did with a robot (the logbook).
package nl.eschouten.scraper.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EventKind {
  @SerialName("route") Route,
  @SerialName("dryRun") DryRun,
  @SerialName("planning") Planning,
  @SerialName("failed") Failed,
  @SerialName("stopped") Stopped,
}

/** [at] in epoch milliseconds. [route]: "A".."D" for route starts and dry runs. [robot]: RobotProfile.uid.
 *  [ms]: how long it took, connecting included. */
@Serializable
data class HistoryEvent(
  val at: Long,
  val kind: EventKind,
  val title: String,
  val detail: String = "",
  @SerialName("widget") val viaWidget: Boolean = false,
  val route: String? = null,
  val robot: String? = null,
  val ms: Long = 0,
)

object History {
  private const val KEY = "history"
  const val MAX = 50

  /** Newest first. */
  fun load(store: Store): List<HistoryEvent> =
    store.getString(KEY)?.let { runCatching { json.decodeFromString<List<HistoryEvent>>(it) }.getOrNull() } ?: emptyList()

  fun add(store: Store, e: HistoryEvent) {
    store.putString(KEY, json.encodeToString((listOf(e) + load(store)).take(MAX)))
  }

  fun clear(store: Store) = store.putString(KEY, null)
}
