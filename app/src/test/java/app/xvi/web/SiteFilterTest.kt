package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parental control: whole sites blocked by category from domain lists, and strict search addresses. */
class SiteFilterTest {
    private fun list(vararg lines: String) = SiteFilter.hashes(lines.asSequence())

    @Test fun listedSitesAndTheirSubdomainsAreBlocked() {
        val f = SiteFilter.build(listOf("adult" to list("adult.example"), "gambling" to list("casino.test")))
        assertEquals("adult", f.category("https://adult.example/video"))
        assertEquals("adult", f.category("http://m.cdn.adult.example:8080/a.jpg"))
        assertEquals("gambling", f.category("https://www.casino.test/"))
        assertNull(f.category("https://notadult.example/"))
        assertNull(f.category("https://adult.example.org/"))
        assertNull(f.category("https://example/"))
    }

    @Test fun onlyWebAddressesAreBlocked() {
        val f = SiteFilter.build(listOf("adult" to list("adult.example")))
        assertNull(f.category("ftp://adult.example/"))
        assertNull(f.category("data:text/html,adult.example"))
    }

    @Test fun allowedSitesWinOverEveryCategory() {
        val f = SiteFilter.build(listOf("adult" to list("adult.example")), allowed = list("safe.adult.example"))
        assertNull(f.category("https://safe.adult.example/"))
        assertNull(f.category("https://img.safe.adult.example/"))
        assertEquals("adult", f.category("https://adult.example/"))
    }

    @Test fun listFormats() {
        assertEquals("hosts.example", SiteFilter.domain("0.0.0.0 hosts.example"))
        assertEquals("hosts.example", SiteFilter.domain("127.0.0.1\tHosts.Example  # comment"))
        assertEquals("plain.example", SiteFilter.domain("plain.example"))
        assertEquals("rule.example", SiteFilter.domain("||rule.example^"))
        assertEquals("wild.example", SiteFilter.domain("*.wild.example"))
        assertEquals("pasted.example", SiteFilter.domain("https://user@pasted.example:443/path?q=1"))
        listOf("# comment", "! comment", "[Adblock Plus]", "", "0.0.0.0 localhost", "0.0.0.0 0.0.0.0",
            "||rule.example^\$third-party", "example.com##.banner", "bad domain.example", "two words", "a..b").forEach {
            assertNull(it, SiteFilter.domain(it))
        }
    }

    @Test fun emptyCategoriesAreLeftOut() {
        val f = SiteFilter.build(listOf("adult" to list("# nothing here")))
        assertTrue(f.isEmpty)
        assertEquals(0, f.size("adult"))
        assertEquals(2, SiteFilter.build(listOf("gambling" to list("a.test", "b.test", "# c.test"))).size("gambling"))
    }

    @Test fun safeSearchIsAskedOfKnownEngines() {
        assertEquals("https://www.google.com/search?q=cats&safe=active", SafeSearch.apply("https://www.google.com/search?q=cats"))
        assertEquals("https://www.google.com.hk/search?q=x&tbm=isch&safe=active", SafeSearch.apply("https://www.google.com.hk/search?q=x&safe=off&tbm=isch"))
        assertEquals("https://cn.bing.com/images/search?q=x&adlt=strict#top", SafeSearch.apply("https://cn.bing.com/images/search?q=x&adlt=off#top"))
        assertEquals("https://duckduckgo.com/?q=x&kp=1", SafeSearch.apply("https://duckduckgo.com/?q=x"))
        val done = SafeSearch.apply("https://www.bing.com/search?q=x")
        assertEquals(done, SafeSearch.apply(done))
    }

    @Test fun otherAddressesAreLeftAlone() {
        listOf("https://www.google.com/maps?q=x", "https://duckduckgo.com/about", "https://www.baidu.com/s?wd=x",
            "https://google.example.com/search?q=x", "https://www.bing.com/").forEach { assertEquals(it, SafeSearch.apply(it)) }
    }
}
