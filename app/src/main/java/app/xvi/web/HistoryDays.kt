package app.xvi.web

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TimeZone

/** Groups history by the calendar day of the last visit, newest day first. */
internal object HistoryDays {
    /** [daysAgo] counts calendar days back from today; [start] is that day's midnight. */
    data class Day(val daysAgo: Int, val start: Long, val sameYear: Boolean)

    fun startOfDay(time: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        date(time, zone).atStartOfDay(zone.toZoneId()).toInstant().toEpochMilli()

    fun day(time: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Day {
        val date = date(time, zone)
        val today = date(now, zone)
        // Counting calendar days, not elapsed milliseconds: across a daylight-saving change two
        // midnights can be 23 or 25 hours apart, which rounding alone reports as the wrong day.
        val days = ChronoUnit.DAYS.between(date, today).toInt()
        return Day(days, startOfDay(time, zone), date.year == today.year)
    }

    private fun date(time: Long, zone: TimeZone): LocalDate = Instant.ofEpochMilli(time).atZone(zone.toZoneId()).toLocalDate()

    /** Entries (already newest first) with a heading from [label] before each day. */
    fun group(entries: List<PageEntry>, now: Long, label: (Day) -> String, zone: TimeZone = TimeZone.getDefault()): List<Any> = buildList {
        var current: Long? = null
        entries.forEach { entry ->
            val day = day(entry.time, now, zone)
            if (day.start != current) { current = day.start; add(label(day)) }
            add(entry)
        }
    }
}
