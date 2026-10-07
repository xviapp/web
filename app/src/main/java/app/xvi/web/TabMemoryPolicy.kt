package app.xvi.web

/** Memory pressure determines a bounded batch, never a fixed number of live tabs. */
object TabMemoryPolicy {
    enum class Pressure { NORMAL, MODERATE, CRITICAL }

    fun pressure(lowMemory: Boolean, available: Long, threshold: Long, total: Long,
        heapUsed: Long, heapMax: Long): Pressure = when {
        lowMemory || (heapMax > 0 && heapUsed.toDouble() / heapMax >= .85) -> Pressure.CRITICAL
        available < maxOf(threshold * 2, total / 10) ||
            (heapMax > 0 && heapUsed.toDouble() / heapMax >= .70) -> Pressure.MODERATE
        else -> Pressure.NORMAL
    }

    fun batchSize(liveTabs: Int, pressure: Pressure): Int = when {
        liveTabs <= 1 || pressure == Pressure.NORMAL -> 0
        pressure == Pressure.CRITICAL -> (liveTabs + 1) / 2
        else -> (liveTabs + 3) / 4
    }

    fun eligible(selected: Boolean, pinned: Boolean, loading: Boolean, idleMillis: Long, pressure: Pressure): Boolean =
        pressure != Pressure.NORMAL && !selected && !pinned && !loading &&
            (pressure == Pressure.CRITICAL || idleMillis >= 30_000)
}
