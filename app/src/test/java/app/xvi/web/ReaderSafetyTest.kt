package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reader mode takes untrusted page markup and hands it back to a WebView, so it gets the strictest
 * tests: nothing the page wrote may survive as a script, a style, a handler or a non-web address.
 */
class ReaderSafetyTest {
    /** Comfortably past the extractor's minimum, so a short fixture cannot decide the outcome. */
    private val body = "这是一段足够长的正文内容，用来测试阅读模式能否找到文章主体。".repeat(8)

    private fun article(inner: String, head: String = "") =
        ReaderExtractor.extract("<html><head><title>标题</title>$head</head><body>$inner</body></html>", "https://example.org/post")

    @Test fun scriptsStylesAndHandlersNeverReachTheReader() {
        val result = article("""
            <article>
              <p>$body</p>
              <script>alert(1)</script>
              <style>body { display: none }</style>
              <iframe src="https://evil.example/"></iframe>
              <svg><script>alert(2)</script></svg>
              <p onmouseover="alert(3)" style="background: url(javascript:alert(4))">$body</p>
            </article>
        """)
        assertNotNull(result)
        val html = result!!.html
        assertFalse(html.contains("script"))
        assertFalse(html.contains("style"))
        assertFalse(html.contains("iframe"))
        assertFalse(html.contains("svg"))
        assertFalse(html.contains("onmouseover"))
        assertFalse(html.contains("javascript:"))
    }

    @Test fun onlyWebAddressesSurviveInLinksAndImages() {
        val result = article("""
            <article>
              <p><a href="javascript:alert(1)">x</a><a href="data:text/html,<script>alert(2)</script>">y</a>
                 <a href="https://good.example/page">ok</a></p>
              <p><img src="javascript:alert(3)"><img src="data:image/svg+xml,<svg onload=alert(4)>"><img src="https://good.example/a.png"></p>
              <p>$body</p>
            </article>
        """)
        assertNotNull(result)
        val html = result!!.html
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("data:image/svg"))
        assertFalse(html.contains("onload"))
        // The safe addresses are kept, so relative and absolute links still work in the reader.
        assertTrue(html.contains("good.example/page"))
        assertTrue(html.contains("good.example/a.png"))
    }

    /** A lazy-loaded image keeps the real address in a data attribute; the reader must not follow it. */
    @Test fun lazyImageAttributesAreNotCopiedVerbatim() {
        val result = article("""<article><p>$body</p><img data-src="https://cdn.example/real.png"></article>""")
        assertNotNull(result)
        assertTrue(result!!.html.contains("cdn.example/real.png"))
        assertFalse(result.html.contains("data-src"))
    }

    @Test fun pagesWithoutEnoughTextHaveNoReaderView() {
        assertNull(article("<article><p>短</p></article>"))
        assertNull(ReaderExtractor.extract("", "https://example.org/"))
        assertNull(ReaderExtractor.extract("<html><body></body></html>", "https://example.org/"))
    }

    @Test fun thePublishedDocumentRestrictsItsOwnOrigins() {
        val result = article("""<article><p>$body</p></article>""")
        val page = ReaderExtractor.page(result!!, dark = true, site = "example.org")
        // No script origin is allowed, so a surviving script could not load anything anyway.
        assertTrue(page.contains("default-src 'none'"))
        assertFalse(page.contains("script-src"))
    }
}
