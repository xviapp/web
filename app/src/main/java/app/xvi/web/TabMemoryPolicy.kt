package app.xvi.web

/**
 * Memory pressure determines a bounded batch, never a fixed number of live tabs. With memory to spare,
 * only pages left untouched for a long while go, and never the last few used.
 */
object TabMemoryPolicy {
    enum class Pressure { NORMAL, MODERATE, CRITICAL }

    /**
     * Untouched this long, a background page goes even with memory to spare: every live page sits in the
     * WebView's renderer, whose memory the pressure measured here never shows.
     */
    const val LONG_IDLE_MS = 30 * 60_000L
    /** Pages used last that stay live however long they sit, so a few tabs never reload on their own. */
    const val KEPT_LIVE = 4

    fun pressure(lowMemory: Boolean, available: Long, threshold: Long, total: Long,
        heapUsed: Long, heapMax: Long): Pressure = when {
        lowMemory || (heapMax > 0 && heapUsed.toDouble() / heapMax >= .85) -> Pressure.CRITICAL
        available < maxOf(threshold * 2, total / 10) ||
            (heapMax > 0 && heapUsed.toDouble() / heapMax >= .70) -> Pressure.MODERATE
        else -> Pressure.NORMAL
    }

    fun batchSize(liveTabs: Int, pressure: Pressure): Int = when {
        liveTabs <= 1 -> 0
        pressure == Pressure.NORMAL -> (liveTabs - KEPT_LIVE).coerceAtLeast(0)
        pressure == Pressure.CRITICAL -> (liveTabs + 1) / 2
        else -> (liveTabs + 3) / 4
    }

    /** How many of the pages used last are never candidates, however long they sat. */
    fun keptLive(pressure: Pressure): Int = if (pressure == Pressure.NORMAL) KEPT_LIVE else 0

    fun eligible(selected: Boolean, pinned: Boolean, loading: Boolean, idleMillis: Long, pressure: Pressure): Boolean =
        !selected && !pinned && !loading && when (pressure) {
            Pressure.NORMAL -> idleMillis >= LONG_IDLE_MS
            Pressure.MODERATE -> idleMillis >= 30_000
            Pressure.CRITICAL -> true
        }
}
