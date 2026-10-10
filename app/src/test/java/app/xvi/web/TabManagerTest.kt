package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TabManagerTest {
    private class FakeTab(val name: String, override val isPrivate: Boolean = false, var used: Boolean = true) : TabManager.Entry {
        override val isBlank get() = !used
        override fun toString() = name
    }

    private fun manager(vararg tabs: FakeTab, selected: Int = 0) =
        TabManager { FakeTab("blank", used = false) }.apply { restore(tabs.toList(), selected) }

    @Test fun restoreAlwaysKeepsOneOrdinaryTab() {
        val tabs = manager()
        assertEquals(1, tabs.tabs.size)
        assertFalse(tabs.isPrivateMode)
    }

    @Test fun closingSelectsTheNeighbourInTheSameMode() {
        val a = FakeTab("a"); val p = FakeTab("p", isPrivate = true); val b = FakeTab("b")
        val tabs = manager(a, p, b, selected = 0)
        tabs.close(0)
        assertSame(b, tabs.current)
    }

    @Test fun closingTheLastOrdinaryTabCreatesAPlaceholderInsteadOfEnteringPrivateMode() {
        val a = FakeTab("a"); val p = FakeTab("p", isPrivate = true)
        val tabs = manager(a, p, selected = 0)
        tabs.close(0)
        assertFalse(tabs.isPrivateMode)
        assertEquals(2, tabs.tabs.size)
    }

    @Test fun undoRestoresABatchAtItsPositionsAndDropsTheUnusedPlaceholder() {
        val a = FakeTab("a"); val b = FakeTab("b"); val c = FakeTab("c")
        val tabs = manager(a, b, c, selected = 1)
        tabs.closeGroup(false)
        assertEquals(1, tabs.tabs.size)
        assertSame(a, tabs.undoClose())
        assertEquals(listOf(a, b, c), tabs.tabs)
        assertNull(tabs.lastClosed)
    }

    @Test fun undoKeepsAPlaceholderTheUserAlreadyUsed() {
        val a = FakeTab("a")
        val tabs = manager(a)
        tabs.close(0)
        val placeholder = tabs.current as FakeTab
        placeholder.used = true
        tabs.undoClose()
        assertEquals(listOf(a, placeholder), tabs.tabs)
    }

    @Test fun closingOthersKeepsTheExceptionSelected() {
        val a = FakeTab("a"); val b = FakeTab("b"); val c = FakeTab("c")
        val tabs = manager(a, b, c, selected = 2)
        tabs.closeGroup(false, except = b)
        assertEquals(listOf(b), tabs.tabs)
        assertSame(b, tabs.current)
    }

    @Test fun droppedPrivateBatchCannotBeUndone() {
        val a = FakeTab("a"); val p = FakeTab("p", isPrivate = true)
        val tabs = manager(a, p, selected = 1)
        tabs.close(1)
        tabs.dropClosed(true)
        assertNull(tabs.undoClose())
        assertEquals(listOf(a), tabs.tabs)
    }

    @Test fun groupTargetReturnsToTheLastOrdinaryTab() {
        val a = FakeTab("a"); val b = FakeTab("b"); val p = FakeTab("p", isPrivate = true)
        val tabs = manager(a, b, p, selected = 0)
        tabs.select(2)
        assertTrue(tabs.isPrivateMode)
        assertSame(a, tabs.groupTarget(false))
        assertSame(p, tabs.groupTarget(true))
    }

    @Test fun neighbourStaysWithinTheCurrentMode() {
        val a = FakeTab("a"); val p = FakeTab("p", isPrivate = true); val b = FakeTab("b")
        val tabs = manager(a, p, b, selected = 0)
        assertEquals(2, tabs.neighbor(1))
        assertNull(tabs.neighbor(-1))
    }

    @Test fun snapshotPersistsOnlyOrdinaryTabs() {
        val a = FakeTab("a"); val p = FakeTab("p", isPrivate = true); val b = FakeTab("b")
        val tabs = manager(a, p, b, selected = 2)
        tabs.select(1)
        val (saved, index) = tabs.ordinarySnapshot()
        assertEquals(listOf(a, b), saved)
        assertEquals(1, index)
    }

    @Test fun addingStopsAtTheLimit() {
        val tabs = manager()
        repeat(TabManager.MAX_TABS + 5) { tabs.add(FakeTab("t$it"), activate = false) }
        assertEquals(TabManager.MAX_TABS, tabs.tabs.size)
        assertFalse(tabs.canAdd)
    }

    @Test fun reopenedTabLeavesTheUndoBatch() {
        val a = FakeTab("a"); val b = FakeTab("b"); val c = FakeTab("c")
        val tabs = manager(a, b, c)
        tabs.closeGroup(false, except = a)
        tabs.forget(b)
        tabs.add(b, activate = true)
        tabs.undoClose()
        assertEquals(listOf(a, b, c), tabs.tabs.sortedBy { it.name })
        assertNull(tabs.lastClosed)
    }
}
