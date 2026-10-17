package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserStoreTest {
    @Test fun likeWildcardsAreEscaped() {
        assertEquals("100\\%", BrowserStore.escapeLike("100%"))
        assertEquals("a\\_b", BrowserStore.escapeLike("a_b"))
        assertEquals("c:\\\\dir", BrowserStore.escapeLike("c:\\dir"))
        assertEquals("plain", BrowserStore.escapeLike("plain"))
    }

    @Test fun downloadStatesParseStableCodes() {
        DownloadState.entries.forEach { assertEquals(it, DownloadState.parse(it.name)) }
        assertEquals(DownloadState.FAILED, DownloadState.parse("下载中"))
        assertEquals(DownloadState.FAILED, DownloadState.parse(null))
    }

    @Test fun onlyEndedDownloadsCanBeRetried() {
        assertFalse(DownloadState.RUNNING.retryable)
        assertFalse(DownloadState.COMPLETED.retryable)
        assertTrue(DownloadState.FAILED.retryable && DownloadState.CANCELLED.retryable && DownloadState.INTERRUPTED.retryable)
    }
}
