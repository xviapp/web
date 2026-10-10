package app.xvi.web

/**
 * Tab list, selection and ordinary / private grouping, without any views.
 * Ordinary browsing always keeps one tab, so closing tabs never silently enters private mode.
 */
internal class TabManager<T : TabManager.Entry>(private val newOrdinary: () -> T) {
    interface Entry {
        val isPrivate: Boolean
        /** Untouched tab that can be dropped when an undo brings real tabs back. */
        val isBlank: Boolean
    }

    /** Tabs closed together, with the index each one had; restored as a unit. */
    class ClosedBatch<T>(val private: Boolean, val items: List<Pair<Int, T>>, val placeholder: T?)

    val tabs = mutableListOf<T>()
    var selected = 0
        private set
    /** The ordinary tab to return to when leaving private mode, and the one restored after restart. */
    var lastOrdinary: T? = null
        private set
    var lastClosed: ClosedBatch<T>? = null
        private set

    val current: T? get() = tabs.getOrNull(selected)
    val isPrivateMode get() = current?.isPrivate == true
    val canAdd get() = tabs.size < MAX_TABS

    fun count(private: Boolean) = tabs.count { it.isPrivate == private }
    fun indicesOf(private: Boolean) = tabs.indices.filter { tabs[it].isPrivate == private }

    fun restore(entries: List<T>, selectedIndex: Int) {
        tabs.clear()
        tabs.addAll(entries.take(MAX_TABS))
        if (tabs.isEmpty()) tabs.add(newOrdinary())
        select(selectedIndex.coerceIn(tabs.indices))
    }

    fun select(index: Int) {
        selected = index.coerceIn(tabs.indices)
        current?.takeIf { !it.isPrivate }?.let { lastOrdinary = it }
    }

    fun select(tab: T) { tabs.indexOf(tab).takeIf { it >= 0 }?.let(::select) }

    /** Null when the tab limit is reached. */
    fun add(tab: T, activate: Boolean): T? {
        if (!canAdd) return null
        tabs.add(tab)
        if (activate) select(tabs.lastIndex)
        return tab
    }

    /** Existing tab to show for [private], or null when one must be created. */
    fun groupTarget(private: Boolean): T? =
        if (!private && lastOrdinary in tabs) lastOrdinary else tabs.lastOrNull { it.isPrivate == private }

    /** Adjacent tab in the current group, for swipe navigation. */
    fun neighbor(step: Int): Int? {
        val group = indicesOf(isPrivateMode)
        return group.getOrNull(group.indexOf(selected) + step)
    }

    /** Removes [index] and selects its neighbour in the same mode; returns the removed tab. */
    fun close(index: Int): T {
        val closing = tabs[index]
        val current = current
        tabs.removeAt(index)
        val placeholder = ensureOrdinary()
        lastClosed = ClosedBatch(closing.isPrivate, listOf(index to closing), placeholder)
        selected = if (closing === current) {
            BrowserNavigation.neighborInGroup(tabs.map { it.isPrivate }, index, closing.isPrivate) ?: ordinaryIndex()
        } else tabs.indexOf(current)
        select(selected)
        return closing
    }

    /** Closes every tab of [private] except [except]; returns the removed tabs. */
    fun closeGroup(private: Boolean, except: T? = null): List<T> {
        val current = current
        val removed = tabs.withIndex().filter { it.value.isPrivate == private && it.value !== except }
        if (removed.isEmpty()) return emptyList()
        tabs.removeAll(removed.map { it.value }.toSet())
        val placeholder = ensureOrdinary()
        lastClosed = ClosedBatch(private, removed.map { it.index to it.value }, placeholder)
        select(tabs.indexOf(except).takeIf { it >= 0 } ?: tabs.indexOf(current).takeIf { it >= 0 } ?: ordinaryIndex())
        return removed.map { it.value }
    }

    /** Puts the last closed batch back at its old positions; returns the first restored tab. */
    fun undoClose(): T? {
        val batch = lastClosed ?: return null
        val placeholder = batch.placeholder?.takeIf { it in tabs && it.isBlank }
        if (tabs.size - (if (placeholder != null) 1 else 0) + batch.items.size > MAX_TABS) return null
        lastClosed = null
        // The blank tab created to keep ordinary browsing alive is no longer needed.
        if (placeholder != null) tabs.remove(placeholder)
        batch.items.sortedBy { it.first }.forEach { (index, tab) -> tabs.add(index.coerceIn(0, tabs.size), tab) }
        val first = batch.items.minByOrNull { it.first }?.second ?: return null
        select(first)
        return first
    }

    /** Takes [tab] out of the undo batch, once it was brought back some other way. */
    fun forget(tab: T) {
        val batch = lastClosed ?: return
        val rest = batch.items.filter { it.second !== tab }
        if (rest.size == batch.items.size) return
        lastClosed = if (rest.isEmpty()) null else ClosedBatch(batch.private, rest, batch.placeholder)
    }

    /** Forget an undo batch whose tabs can no longer be revived (e.g. the private session ended). */
    fun dropClosed(private: Boolean) { if (lastClosed?.private == private) lastClosed = null }

    fun ordinaryIndex() = tabs.indexOf(lastOrdinary).takeIf { it >= 0 } ?: tabs.indexOfLast { !it.isPrivate }

    /** Ordinary tabs and the index of the one to restore, for persistence. */
    fun ordinarySnapshot(): Pair<List<T>, Int> {
        val ordinary = tabs.filter { !it.isPrivate }
        return ordinary to ordinary.indexOf(lastOrdinary).coerceAtLeast(0)
    }

    private fun ensureOrdinary(): T? =
        if (tabs.none { !it.isPrivate }) newOrdinary().also { tabs.add(it) } else null

    companion object { const val MAX_TABS = 50 }
}
