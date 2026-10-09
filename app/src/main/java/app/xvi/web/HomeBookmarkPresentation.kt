package app.xvi.web

internal object HomeBookmarkPresentation {
    const val COLUMNS = 4
    private const val MAX_EXPANDED_ROWS = 4
    /** Most bookmarks the start page ever shows; one more tells it a "more" link is needed. */
    const val MAX_VISIBLE = COLUMNS * MAX_EXPANDED_ROWS

    fun visibleCount(total: Int, expanded: Boolean): Int {
        val count = total.coerceAtLeast(0)
        return count.coerceAtMost(if (expanded) MAX_VISIBLE else COLUMNS)
    }

    fun showsToggle(total: Int) = total > COLUMNS

    fun showsMore(total: Int, expanded: Boolean) = expanded && total > MAX_VISIBLE
}
