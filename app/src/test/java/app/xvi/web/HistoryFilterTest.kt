package app.xvi.web

import java.text.SimpleDateFormat
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class HistoryFilterTest {
    private fun page(url: String = "https://example.com/news", title: String = "新闻", time: Long = 100) = PageEntry(url, title, time)

    @Test fun keywordFindsTitleOrUrlAndTreatsWildcardsLiterally() {
        assertTrue(HistoryFilter(" 新闻 ").matches(page()))
        assertTrue(HistoryFilter("EXAMPLE").matches(page()))
        assertTrue(HistoryFilter("100%_").matches(page(title = "100%_完成")))
        assertFalse(HistoryFilter("100%_").matches(page(title = "100 percent")))
        assertFalse(HistoryFilter(".*").matches(page()))
        assertTrue(HistoryFilter().matches(page()))
    }

    @Test fun urlContainsDoesNotMatchTheTitle() {
        val filter = HistoryFilter("news", HistoryFilter.Mode.URL_CONTAINS)
        assertTrue(filter.matches(page("https://example.com/NEWS?q=1")))
        assertFalse(filter.matches(page("https://other.test/", "news")))
    }

    @Test fun prefixIsAnchoredToTheWholeUrl() {
        val filter = HistoryFilter(" https://example.com/news/ ", HistoryFilter.Mode.URL_PREFIX)
        assertTrue(filter.matches(page("https://example.com/news/item")))
        assertFalse(filter.matches(page("https://example.com/newsletter")))
        assertFalse(filter.matches(page("https://other.test/?next=https://example.com/news/")))
        assertFalse(filter.matches(page("http://example.com/news/item")))
    }

    @Test fun pathSuffixIgnoresQueryAndFragmentButNeverMatchesThem() {
        val filter = HistoryFilter(".pdf", HistoryFilter.Mode.PATH_SUFFIX)
        assertTrue(filter.matches(page("https://example.com/a.PDF?download=1#page=2")))
        assertTrue(filter.matches(page("https://example.com/a%2Epdf")))
        assertFalse(filter.matches(page("https://example.com/article?format=.pdf")))
        assertFalse(filter.matches(page("https://example.com/article#.pdf")))
        assertFalse(filter.matches(page("https://example.com/a.pdf/")))
        assertFalse(filter.matches(page("not a URL")))
        assertTrue(HistoryFilter("/登录", HistoryFilter.Mode.PATH_SUFFIX)
            .matches(page("https://example.com/%E7%99%BB%E5%BD%95?q=x")))
    }

    @Test fun domainsRespectHostBoundariesAndTheSubdomainChoice() {
        val filter = HistoryFilter(" EXAMPLE.com. ", HistoryFilter.Mode.DOMAIN)
        assertTrue(filter.matches(page()))
        assertTrue(filter.matches(page("http://sub.example.com:8080/a")))
        assertTrue(filter.matches(page("https://example.com./")))
        assertFalse(filter.matches(page("https://notexample.com/")))
        assertFalse(filter.matches(page("https://example.com.evil.test/")))
        assertFalse(filter.matches(page("https://example.com@evil.test/")))
        assertFalse(filter.matches(page("https://evil.test/?next=example.com")))
        assertFalse(filter.copy(includeSubdomains = false).matches(page("https://www.example.com/")))
        assertTrue(filter.copy(includeSubdomains = false).matches(page()))
    }

    @Test fun unicodeAndPunycodeDomainsAreTheSameHost() {
        val filter = HistoryFilter("例子.中国", HistoryFilter.Mode.DOMAIN)
        assertTrue(filter.isValid)
        assertTrue(filter.matches(page("https://xn--fsqu00a.xn--fiqs8s/article")))
        assertTrue(filter.matches(page("https://sub.例子.中国/")))
    }

    @Test fun incompleteFiltersCannotBecomeMatchAll() {
        HistoryFilter.Mode.entries.filter { it != HistoryFilter.Mode.KEYWORD }.forEach { mode ->
            val filter = HistoryFilter("  ", mode)
            assertFalse(mode.name, filter.isValid)
            assertFalse(mode.name, filter.matches(page()))
        }
        listOf("https://example.com", "example.com/path", "a@b.com", "*.example.com", "example.com?x=1", "bad domain",
            ".", ".example.com", "bad..example.com", "-bad.com", "bad-.com").forEach { text ->
            val filter = HistoryFilter(text, HistoryFilter.Mode.DOMAIN)
            assertFalse(text, filter.isValid)
            assertFalse(text, filter.matches(page()))
        }
    }

    @Test fun timeRangeIntersectsTheTextFilterIncludingItsBoundary() {
        val filter = HistoryFilter("news", since = 100)
        assertTrue(filter.matches(page(time = 100)))
        assertFalse(filter.matches(page(time = 99)))
        assertFalse(filter.matches(page("https://example.com/other", "other", 101)))
    }

    @Test fun recentWeekUsesCalendarDaysAcrossDaylightSaving() {
        val zone = TimeZone.getTimeZone("America/New_York")
        fun at(text: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = zone }.parse(text)!!.time
        val now = at("2026-11-03 09:00")
        assertEquals(at("2026-10-28 00:00"), HistoryFilter.Range.WEEK.since(now, zone))
        assertEquals(at("2026-11-03 00:00"), HistoryFilter.Range.TODAY.since(now, zone))
        assertEquals(now - 3_600_000, HistoryFilter.Range.HOUR.since(now, zone))
        assertEquals(0L, HistoryFilter.Range.ALL.since(now, zone))
    }
}
