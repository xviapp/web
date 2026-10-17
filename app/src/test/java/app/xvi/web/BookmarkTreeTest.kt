package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkTreeTest {
    private val folders = listOf(
        BookmarkFolder(1, "工作"), BookmarkFolder(2, "项目", parent = 1), BookmarkFolder(3, "生活"),
        BookmarkFolder(4, "旧", parent = 2), BookmarkFolder(5, "文档", parent = 1)
    )

    @Test fun foldersFlattenUnderTheOneHoldingThem() {
        assertEquals(listOf("工作" to 0, "项目" to 1, "旧" to 2, "文档" to 1, "生活" to 0), BookmarkTree.flatten(folders).map { it.first.name to it.second })
    }

    @Test fun pathsRunFromTheTop() {
        assertEquals(listOf("工作", "项目", "旧"), BookmarkTree.path(folders, 4))
        assertEquals(emptyList<String>(), BookmarkTree.path(folders, BrowserStore.ROOT_FOLDER))
    }

    @Test fun subtreeHoldsEveryLevelInside() {
        assertEquals(setOf(1L, 2L, 4L, 5L), BookmarkTree.subtree(folders, 1))
    }

    /** A loop, which only a damaged database could hold, ends rather than running forever. */
    @Test fun loopsEnd() {
        val looped = listOf(BookmarkFolder(1, "a", parent = 2), BookmarkFolder(2, "b", parent = 1))
        assertTrue(BookmarkTree.path(looped, 1).size <= looped.size + 1)
        assertEquals(setOf(1L, 2L), BookmarkTree.subtree(looped, 1))
    }
}
