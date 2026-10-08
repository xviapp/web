package app.xvi.web

internal object HomeBookmarkPresentation {
    private const val COLUMNS = 4
    private const val MAX_EXPANDED_ROWS = 4

    fun visibleCount(total: Int, expanded: Boolean): Int {
        val count = total.coerceAtLeast(0)
        return count.coerceAtMost(if (expanded) COLUMNS * MAX_EXPANDED_ROWS else COLUMNS)
    }

    fun showsToggle(total: Int) = total > COLUMNS

    fun showsMore(total: Int, expanded: Boolean) = expanded && total > COLUMNS * MAX_EXPANDED_ROWS
}
