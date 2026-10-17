package app.xvi.web

/** Folder nesting worked out from the flat list [BrowserStore.folders] returns. Plain Kotlin, covered by JVM tests. */
internal object BookmarkTree {
    /** Every folder under [root], each followed by what it holds, with its depth below [root] (0 for its own). */
    fun flatten(folders: List<BookmarkFolder>, root: Long = BrowserStore.ROOT_FOLDER): List<Pair<BookmarkFolder, Int>> {
        val byParent = folders.groupBy { it.parent }
        val seen = hashSetOf<Long>()
        val result = mutableListOf<Pair<BookmarkFolder, Int>>()
        fun walk(parent: Long, depth: Int) {
            byParent[parent].orEmpty().forEach { if (seen.add(it.id)) { result += it to depth; walk(it.id, depth + 1) } }
        }
        walk(root, 0)
        return result
    }

    /** Names from the top level down to [id]; empty for the top level or a folder not in [folders]. */
    fun path(folders: List<BookmarkFolder>, id: Long): List<String> {
        val byId = folders.associateBy { it.id }
        val names = mutableListOf<String>()
        var current = byId[id]
        while (current != null && names.size <= folders.size) {
            names += current.name
            current = byId[current.parent]
        }
        return names.reversed()
    }

    /** [id] and every folder inside it, at any depth. */
    fun subtree(folders: List<BookmarkFolder>, id: Long): Set<Long> = setOf(id) + flatten(folders, id).map { it.first.id }
}
