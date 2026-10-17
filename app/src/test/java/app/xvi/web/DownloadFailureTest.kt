package app.xvi.web

import app.xvi.web.DownloadFailure.Reason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class DownloadFailureTest {
    @Test fun errorsMapToStableCodes() {
        assertEquals("http:404", DownloadFailure.code(DownloadFailure.HttpStatus(404)))
        assertEquals("storage", DownloadFailure.code(DownloadFailure.Storage(IOException("ENOSPC"))))
        assertEquals("network", DownloadFailure.code(UnknownHostException("example.com")))
        assertEquals("", DownloadFailure.code(IllegalArgumentException("invalid redirect")))
        assertEquals("", DownloadFailure.code(null))
    }

    @Test fun codesParseToReasons() {
        assertEquals(Reason.NETWORK to null, DownloadFailure.parse("network"))
        assertEquals(Reason.STORAGE to null, DownloadFailure.parse("storage"))
        assertEquals(Reason.DENIED to 403, DownloadFailure.parse("http:403"))
        assertEquals(Reason.MISSING to 410, DownloadFailure.parse("http:410"))
        assertEquals(Reason.SERVER to 503, DownloadFailure.parse("http:503"))
        assertEquals(Reason.HTTP to 416, DownloadFailure.parse("http:416"))
        assertEquals(Reason.UNKNOWN to null, DownloadFailure.parse(""))
        assertEquals(Reason.UNKNOWN to null, DownloadFailure.parse("http:abc"))
    }
}
