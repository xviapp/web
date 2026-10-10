package app.xvi.web

import org.junit.Assert.*
import org.junit.Test

class BrowserInputTest {
    @Test fun recognizesUrlsAndEncodesSearchTerms() {
        assertEquals("https://example.org/path?q=1", BrowserInput.resolve(" example.org/path?q=1 ", "必应"))
        assertEquals("http://127.0.0.1:8080/page", BrowserInput.resolve("http://127.0.0.1:8080/page", "必应"))
        assertEquals("https://www.bing.com/search?q=a+b%26c", BrowserInput.resolve("a b&c", "必应"))
        assertTrue(BrowserInput.resolve("你好 世界", "百度").startsWith("https://www.baidu.com/s?wd="))
    }
    @Test fun privilegedSchemesCannotEnterBrowserNavigationOrBookmarks() {
        listOf("javascript:alert(1)", "file:///sdcard/private", "content://secrets/path", "data:text/html,hello", "https://user:pass@example.org", "https://").forEach {
            assertFalse(it, BrowserInput.isWebUrl(it))
            assertTrue(BrowserInput.resolve(it, "必应").startsWith("https://www.bing.com/search?q="))
        }
    }
    @Test fun preservesEmptyHomeAndFallsBackForUnknownEngine() {
        assertEquals("", BrowserInput.resolve("  ", "必应"))
        assertTrue(BrowserInput.resolve("two words", "removed").startsWith("https://www.bing.com/search?q="))
    }
    @Test fun addressBarShowsTheHostWhileNotEditing() {
        assertEquals("example.org", BrowserInput.display("https://www.Example.org/a/b?c=1"))
        assertEquals("127.0.0.1", BrowserInput.display("http://127.0.0.1:8080/page"))
        assertEquals("not a url", BrowserInput.display("not a url"))
    }
}
