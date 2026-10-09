package app.xvi.web

import org.junit.Assert.*
import org.junit.Test

class ProxyUrlDraftTest {
    @Test fun snapshotsPopulateCleanDraftWithoutMakingItDirty() {
        val draft = ProxyUrlDraft()
        draft.sync("https://first.test")
        draft.sync("https://second.test")
        assertEquals("https://second.test", draft.value)
        assertFalse(draft.dirty)
    }

    @Test fun broadcastsPreserveUnsavedInputAndRevertingClearsDirty() {
        val draft = ProxyUrlDraft()
        draft.sync("https://first.test")
        draft.edit("https://edit.test")
        repeat(3) { draft.sync("https://first.test") }
        assertEquals("https://edit.test", draft.value)
        assertTrue(draft.dirty)
        draft.edit(" https://first.test ")
        assertFalse(draft.dirty)
    }

    @Test fun completedSavePreservesAnEditMadeWhileSaving() {
        val draft = ProxyUrlDraft()
        draft.sync("https://first.test")
        draft.edit("https://submitted.test")
        draft.beginSave()
        draft.edit("https://newer.test")
        draft.sync("https://submitted.test")
        draft.saved("https://submitted.test", "https://submitted.test")
        draft.sync("https://submitted.test")
        assertEquals("https://newer.test", draft.value)
        assertTrue(draft.dirty)
    }

    @Test fun successfulSaveNormalizesInputAndAllowsLaterSnapshots() {
        val draft = ProxyUrlDraft()
        draft.edit(" https://submitted.test ")
        draft.saved("https://submitted.test", "https://submitted.test")
        assertFalse(draft.dirty)
        assertEquals("https://submitted.test", draft.value)
        draft.sync("https://external.test")
        assertEquals("https://external.test", draft.value)
    }

    @Test fun revertingToOldUrlWhileSavingIsStillANewerEdit() {
        val draft = ProxyUrlDraft()
        draft.sync("https://first.test")
        draft.edit("https://submitted.test")
        draft.beginSave()
        draft.edit("https://first.test")
        draft.sync("https://submitted.test")
        draft.saved("https://submitted.test", "https://submitted.test")
        draft.sync("https://submitted.test")
        assertEquals("https://first.test", draft.value)
        assertTrue(draft.dirty)
    }

    @Test fun failedSaveSnapshotKeepsTheDraft() {
        val draft = ProxyUrlDraft()
        draft.sync("https://first.test")
        draft.edit("invalid")
        draft.sync("https://first.test")
        assertEquals("invalid", draft.value)
        assertTrue(draft.dirty)
    }
}
