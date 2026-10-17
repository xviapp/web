package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsFirstTest {
    @Test fun plainHttpIsUpgradedKeepingTheRestOfTheAddress() {
        assertEquals("https://example.com/a?b=1#c", HttpsFirst.upgrade("http://example.com/a?b=1#c"))
        assertEquals("https://example.com/", HttpsFirst.upgrade("HTTP://example.com/"))
        assertEquals("https://example.com/x", HttpsFirst.upgrade("http://example.com:80/x"))
        assertEquals("https://user@example.com/", HttpsFirst.upgrade("http://user@example.com/"))
    }

    @Test fun otherAddressesAreLeftAlone() {
        listOf("https://example.com/", "http://example.com:8080/", "http://192.168.1.1/", "http://localhost/",
            "http://router/", "http://nas.local/", "ftp://example.com/", "not a url", "").forEach {
            assertNull(it, HttpsFirst.upgrade(it))
        }
    }

    @Test fun redirectsBackToHttpOnTheSameSiteAreDowngrades() {
        assertTrue(HttpsFirst.isDowngrade("http://example.com/", "http://example.com/"))
        assertTrue(HttpsFirst.isDowngrade("http://www.example.com/a", "http://example.com/"))
        assertFalse(HttpsFirst.isDowngrade("https://example.com/", "http://example.com/"))
        assertFalse(HttpsFirst.isDowngrade("http://other.com/", "http://example.com/"))
    }
}
