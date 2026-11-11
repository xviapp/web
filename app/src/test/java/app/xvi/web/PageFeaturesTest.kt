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
        // A weak tag says the bytes may differ; a date to the second cannot vouch for them either.
        assertEquals("", DownloadResume.validator("W/\"abc\"", "Wed, 01 Jan 2025 00:00:00 GMT"))
        assertEquals("", DownloadResume.validator("w/\"abc\"", "Wed, 01 Jan 2025 00:00:00 GMT"))
        assertEquals("Wed, 01 Jan 2025 00:00:00 GMT", DownloadResume.validator(null, "Wed, 01 Jan 2025 00:00:00 GMT"))
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

    /** The size guard runs before decoding, so an oversized address is refused without allocating it. */
    @Test fun oversizedDataAddressesAreRefusedBeforeDecoding() {
        val limit = 1024L
        val directory = java.nio.file.Files.createTempDirectory("data-url").toFile()
        try {
            val small = "data:text/plain;base64," + java.util.Base64.getEncoder().encodeToString("x".repeat(64).toByteArray())
            val file = java.io.File(directory, "small")
            assertEquals("text/plain", DataUrl.write(small, file, limit))
            assertEquals("x".repeat(64), file.readText())

            // Just over the header allowance: refused, and nothing is written.
            val huge = "data:text/plain;base64," + java.util.Base64.getEncoder().encodeToString(ByteArray(4096))
            val refused = java.io.File(directory, "huge")
            val error = runCatching { DataUrl.write(huge, refused, limit) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(refused.exists())
        } finally {
            directory.deleteRecursively()
        }
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

    @Test fun anUnsatisfiableRangeTellsTheWholeSize() {
        assertEquals(1000L, DownloadResume.unsatisfiedTotal("bytes */1000"))
        assertNull(DownloadResume.unsatisfiedTotal("bytes 0-1/1000"))
        assertNull(DownloadResume.unsatisfiedTotal(null))
    }

    @Test fun fileNamesKeepTheirExtensionAndFitInBytes() {
        assertEquals("a_b_c_d_e_f_g_.txt", FileNames.clean("a:b*c?d\"e<f>g|.txt"))
        val long = FileNames.clean("中".repeat(200) + ".pdf")
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 240)
        assertEquals("download", FileNames.clean("  ..  "))
        assertEquals("x😀".repeat(10), FileNames.clean("x😀".repeat(10)))
        assertFalse(FileNames.clean("😀".repeat(100)).contains('\uFFFD'))
    }

    @Test fun dataAddressesDecodeWithoutTurningBytesIntoText() {
        assertEquals("<svg width=\"100%\"/>", String(DataUrl.decode("data:image/svg+xml,<svg width=\"100%\"/>")))
        assertEquals(listOf(0xff, 0x00, 0x41), DataUrl.decode("data:application/octet-stream,%FF%00A").map { it.toInt() and 0xff })
        assertEquals("hi+there", String(DataUrl.decode("data:text/plain;base64,aGkrdGhlcmU=")))
        assertEquals("a b", String(DataUrl.decode("data:,a%20b")))
        assertEquals("é", String(DataUrl.decode("data:text/plain;charset=utf-8,é"), Charsets.UTF_8))
    }

    @Test fun dynamicSurfaceHarmonizationRespectsDynamicAndPureBlackModes() {
        val neutralLight = 0xFFFFFFFF.toInt()
        val neutralBlack = 0xFF000000.toInt()
        val accent = 0xFF2060E0.toInt()
        assertEquals(neutralLight, BrowserPalette.harmonizeSurface(neutralLight, accent, dynamic = false, black = false, dark = false, lightRatio = 0.04f, darkRatio = 0.06f))
        assertEquals(neutralBlack, BrowserPalette.harmonizeSurface(neutralBlack, accent, dynamic = true, black = true, dark = true, lightRatio = 0.04f, darkRatio = 0.06f))
        val tintedLight = BrowserPalette.harmonizeSurface(neutralLight, accent, dynamic = true, black = false, dark = false, lightRatio = 0.04f, darkRatio = 0.06f)
        assertTrue(tintedLight != neutralLight)
        assertEquals(0xFF, (tintedLight ushr 24) and 0xFF)
    }

    @Test fun readerPageReservesRoomForFloatingHeader() {
        val page = ReaderExtractor.page(
            ReaderExtractor.Article("标题", "<p>正文</p>"),
            dark = false,
            site = "example.org",
            style = ReaderExtractor.Style(tone = ReaderExtractor.Tone.SEPIA, serif = true, spacing = ReaderExtractor.Spacing.RELAXED)
        )
        assertTrue(page.contains("padding: 60px 20px 48px"))
        assertTrue(page.contains("font: 18px/2 serif"))
        assertTrue(page.contains("#f4ecd8"))
    }
}
