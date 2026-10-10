package app.xvi.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFeaturesTest {
    @After fun resetCustomEngine() { BrowserInput.customTemplate = null }

    @Test fun customEngineNeedsOneSlotInAWebAddress() {
        assertTrue(BrowserInput.isValidTemplate("https://search.example/?q=%s"))
        assertFalse(BrowserInput.isValidTemplate("https://search.example/?q="))
        assertFalse(BrowserInput.isValidTemplate("https://search.example/?q=%s&r=%s"))
        assertFalse(BrowserInput.isValidTemplate("javascript:%s"))
    }

    @Test fun customEngineIsOfferedOnlyOnceConfigured() {
        assertFalse(BrowserInput.isKnownEngine(BrowserInput.CUSTOM_ENGINE))
        BrowserInput.customTemplate = "https://search.example/find?q=%s&lang=zh"
        assertTrue(BrowserInput.isKnownEngine(BrowserInput.CUSTOM_ENGINE))
        assertEquals("https://search.example/find?q=a+b%26c&lang=zh", BrowserInput.resolve("a b&c", BrowserInput.CUSTOM_ENGINE))
        // Addresses are still opened directly.
        assertEquals("https://example.org", BrowserInput.resolve("example.org", BrowserInput.CUSTOM_ENGINE))
    }

    @Test fun directAddressesAreTellApartFromSearches() {
        assertEquals("https://example.org/a", BrowserInput.directUrl("example.org/a"))
        assertNull(BrowserInput.directUrl("two words"))
        assertNull(BrowserInput.directUrl("  "))
    }

    @Test fun resumeUsesStrongValidatorsOnly() {
        assertEquals("\"abc\"", DownloadResume.validator("\"abc\"", "Wed, 01 Jan 2025 00:00:00 GMT"))
        assertEquals("Wed, 01 Jan 2025 00:00:00 GMT", DownloadResume.validator("W/\"abc\"", "Wed, 01 Jan 2025 00:00:00 GMT"))
        assertEquals("", DownloadResume.validator(null, null))
    }

    @Test fun contentRangeIsParsed() {
        assertEquals(100L, DownloadResume.rangeStart("bytes 100-199/1000"))
        assertEquals(1000L, DownloadResume.rangeTotal("bytes 100-199/1000"))
        assertNull(DownloadResume.rangeTotal("bytes 100-199/*"))
        assertNull(DownloadResume.rangeStart("items 1-2/3"))
        assertNull(DownloadResume.rangeStart(null))
    }

    @Test fun dataAddressesDecode() {
        assertEquals("hello", String(DataUrl.decode("data:text/plain;base64,aGVsbG8=")))
        assertEquals("a b+c", String(DataUrl.decode("data:,a%20b+c")))
        assertEquals("image/png", DataUrl.type("data:image/png;base64,AAAA"))
        assertEquals("text/plain", DataUrl.type("data:;base64,AAAA"))
    }

    @Test fun readerKeepsTheArticleAndDropsScripts() {
        val paragraph = "这是一段足够长的正文内容，用来测试阅读模式能否找到文章主体。".repeat(4)
        val html = """<html><head><title>标题</title></head><body>
            <nav><a href="/">首页</a></nav>
            <div class="sidebar"><p>侧栏</p></div>
            <article><h1>标题</h1><p onclick="alert(1)">$paragraph</p><p>$paragraph</p>
            <script>alert(1)</script><img data-src="/a.png"></article></body></html>"""
        val article = ReaderExtractor.extract(html, "https://example.org/post")
        assertNotNull(article)
        assertEquals("标题", article!!.title)
        assertTrue(article.html.contains("阅读模式"))
        assertFalse(article.html.contains("script"))
        assertFalse(article.html.contains("onclick"))
        assertFalse(article.html.contains("侧栏"))
        assertTrue(article.html.contains("https://example.org/a.png"))
    }

    @Test fun readerSkipsPagesWithoutText() {
        assertNull(ReaderExtractor.extract("<html><body><p>短</p></body></html>", "https://example.org/"))
    }
}
