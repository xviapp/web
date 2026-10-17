package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A web app window keeps its own site and hands every other site to the browser. */
class WebAppScopeTest {
    private val start = "https://www.example.com/app/"

    @Test fun theSameSiteStays() {
        assertTrue(WebAppScope.contains(start, "https://example.com/inbox?x=1"))
        assertTrue(WebAppScope.contains(start, "https://WWW.Example.com:443/"))
        assertFalse(WebAppScope.leaves(start, "https://example.com/other", redirect = false))
    }

    @Test fun otherSitesSubdomainsSchemesAndPortsLeave() {
        assertTrue(WebAppScope.leaves(start, "https://accounts.example.com/", redirect = false))
        assertTrue(WebAppScope.leaves(start, "https://other.org/", redirect = false))
        assertTrue(WebAppScope.leaves(start, "http://example.com/", redirect = false))
        assertTrue(WebAppScope.leaves(start, "https://example.com:8443/", redirect = false))
    }

    /** Sign-in flows bounce through other sites by redirect and come back. */
    @Test fun serverRedirectsStay() {
        assertFalse(WebAppScope.leaves(start, "https://accounts.other.org/login", redirect = true))
    }

    @Test fun nonWebAddressesHaveNoScope() {
        assertNull(WebAppScope.of("about:blank"))
        assertNull(WebAppScope.of("javascript:alert(1)"))
        assertNull(WebAppScope.of("not a url"))
        assertFalse(WebAppScope.contains("about:blank", "about:blank"))
    }

    @Test fun themeColorsParse() {
        assertEquals(0xFF112233.toInt(), WebAppScope.themeColor("#112233"))
        assertEquals(0xFFAABBCC.toInt(), WebAppScope.themeColor(" #abc "))
        assertEquals(0xFF112233.toInt(), WebAppScope.themeColor("#11223380"))
        assertEquals(0xFF0A141E.toInt(), WebAppScope.themeColor("rgb(10, 20, 30)"))
        assertEquals(0xFF0A141E.toInt(), WebAppScope.themeColor("rgba(10,20,30,0.5)"))
        assertEquals(0xFF0A141E.toInt(), WebAppScope.themeColor("rgb(10 20 30 / 50%)"))
    }

    @Test fun unknownThemeColorsAreIgnored() {
        assertNull(WebAppScope.themeColor(null))
        assertNull(WebAppScope.themeColor(""))
        assertNull(WebAppScope.themeColor("#12"))
        assertNull(WebAppScope.themeColor("#gggggg"))
        assertNull(WebAppScope.themeColor("rgb(300, 0, 0)"))
        assertNull(WebAppScope.themeColor("hsl(0, 0%, 0%)"))
    }

    @Test fun barIconsFollowBrightness() {
        assertTrue(WebAppScope.isLight(0xFFFFFFFF.toInt()))
        assertFalse(WebAppScope.isLight(0xFF000000.toInt()))
        assertFalse(WebAppScope.isLight(0xFF1A73E8.toInt()))
    }
}
