// How times, voltages and the like are written, the same in every app.
package nl.eschouten.scraper.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import nl.eschouten.scraper.texts.tr
import kotlin.time.Instant

object Fmt {
  var zone: TimeZone = TimeZone.currentSystemDefault()

  private fun local(at: Long) = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)

  /** "21:00:00" -> "21:00". */
  fun hm(hms: String): String {
    val p = hms.split(':')
    return if (p.size >= 2) "${p[0]}:${p[1]}" else hms
  }

  /** 13500 -> "13,5 V" (NL) / "13.5 V" (EN). */
  fun volts(mv: Int): String {
    val tenths = (mv + 50) / 100
    return "${tenths / 10}${tr.decimal}${tenths % 10} V"
  }

  fun hhmm(at: Long): String {
    val t = local(at)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
  }

  /** "vandaag 06:12", "gisteren 21:00", "3 nov 07:45". */
  fun whenText(at: Long, now: Long): String = "${day(at, now)} ${hhmm(at)}"

  /** "Vandaag", "Gisteren", "3 nov": a heading in the logbook. */
  fun dayTitle(at: Long, now: Long): String = day(at, now).replaceFirstChar { it.uppercase() }

  /** "zojuist", "5 min geleden", "2 uur geleden", else [whenText]. */
  fun ago(at: Long, now: Long): String {
    val s = (now - at) / 1000
    return when {
      s < 60 -> tr.justNow
      s < 3600 -> tr.minutesAgo((s / 60).toInt())
      s < 86400 -> tr.hoursAgo((s / 3600).toInt())
      else -> whenText(at, now)
    }
  }

  private fun day(at: Long, now: Long): String {
    val d: LocalDate = local(at).date
    val today: LocalDate = local(now).date
    val days = today.toEpochDays() - d.toEpochDays()
    return when (days) {
      0L -> tr.today
      1L -> tr.yesterday
      else -> "${d.day} ${tr.months[d.month.ordinal]}"
    }
  }
}
