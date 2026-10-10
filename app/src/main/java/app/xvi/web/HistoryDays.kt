package app.xvi.web

import java.util.Calendar
import java.util.TimeZone

/** Groups history by the calendar day of the last visit, newest day first. */
internal object HistoryDays {
    /** [daysAgo] counts calendar days back from today; [start] is that day's midnight. */
    data class Day(val daysAgo: Int, val start: Long, val sameYear: Boolean)

    private const val DAY = 24 * 60 * 60 * 1000L

    fun startOfDay(time: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).run {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    fun day(time: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Day {
        val start = startOfDay(time, zone)
        val today = startOfDay(now, zone)
        // Both are midnights, so rounding absorbs a daylight-saving hour.
        val days = Math.round((today - start) / DAY.toDouble()).toInt()
        val year = { t: Long -> Calendar.getInstance(zone).apply { timeInMillis = t }.get(Calendar.YEAR) }
        return Day(days, start, year(start) == year(today))
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
