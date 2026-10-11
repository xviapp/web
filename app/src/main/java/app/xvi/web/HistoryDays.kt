package app.xvi.web

import java.util.Calendar
import java.util.TimeZone

/** Groups history by the calendar day of the last visit, newest day first. */
internal object HistoryDays {
    /** [daysAgo] counts calendar days back from today; [start] is that day's midnight. */
    data class Day(val daysAgo: Int, val start: Long, val sameYear: Boolean)

    fun startOfDay(time: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).run {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    fun day(time: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Day {
        val start = startOfDay(time, zone)
        val today = startOfDay(now, zone)
        // Counting calendar days, not elapsed milliseconds: across a daylight-saving change two
        // midnights can be 23 or 25 hours apart, which rounding alone reports as the wrong day.
        val days = calendarDaysBetween(start, today, zone)
        return Day(days, start, year(start, zone) == year(today, zone))
    }

    private fun year(time: Long, zone: TimeZone) = Calendar.getInstance(zone).apply { timeInMillis = time }.get(Calendar.YEAR)

    /** Whole days from [from] to [to], both midnights in [zone]. */
    private fun calendarDaysBetween(from: Long, to: Long, zone: TimeZone): Int {
        val at = java.time.Instant.ofEpochMilli(from).atZone(zone.toZoneId()).toLocalDate()
        val until = java.time.Instant.ofEpochMilli(to).atZone(zone.toZoneId()).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(at, until).toInt()
    }

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
