package app.xvi.web

import app.xvi.web.AdFilter.Companion.IMAGE
import app.xvi.web.AdFilter.Companion.SCRIPT
import app.xvi.web.AdFilter.Companion.STYLESHEET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Request and element hiding rules in the filter list syntax, and the real lists the app ships. */
class AdFilterTest {
    private fun filter(vararg rules: String) = AdFilter.build(rules.asSequence())
    private val page = "https://news.example.com/article/1"

    @Test fun wholeHostsAndTheirSubdomainsAreBlocked() {
        val f = filter("||ads.com^")
        assertTrue(f.blocks("https://ads.com/x.js", page, SCRIPT))
        assertTrue(f.blocks("https://cdn.ads.com/x.png", page, IMAGE))
        assertFalse(f.blocks("https://notads.com/x.js", page, SCRIPT))
        assertFalse(f.blocks("https://ads.com.example.org/x.js", page, SCRIPT))
    }

    @Test fun pathsWildcardsAndSeparators() {
        val f = filter("||tracker.net/pixel^", "/banner/*/ad_", "|https://exact.org/a.js|", "-ad-300x250.")
        assertTrue(f.blocks("https://tracker.net/pixel?id=1", page, IMAGE))
        assertFalse(f.blocks("https://tracker.net/pixels", page, IMAGE))
        assertTrue(f.blocks("https://x.org/banner/2024/ad_top.png", page, IMAGE))
        assertTrue(f.blocks("https://exact.org/a.js", page, SCRIPT))
        assertFalse(f.blocks("https://exact.org/a.js?v=2", page, SCRIPT))
        assertTrue(f.blocks("https://img.org/top-ad-300x250.jpg", page, IMAGE))
    }

    /** Rules whose only word has two letters are filed under it, and still need it whole. */
    @Test fun twoLetterWordsFileRules() {
        val f = filter(".com/ad/", "?rb=", "_ad_")
        assertTrue(f.blocks("https://x.com/ad/banner.png", page, IMAGE))
        assertFalse(f.blocks("https://x.com/adx/banner.png", page, IMAGE))
        assertFalse(f.blocks("https://x.com/load/banner.png", page, IMAGE))
        assertTrue(f.blocks("https://y.org/p?rb=1", page, SCRIPT))
        assertFalse(f.blocks("https://y.org/p?herb=1", page, SCRIPT))
        assertTrue(f.blocks("https://z.net/top_ad_slot.js", page, SCRIPT))
    }

    /** What holds for a page is kept for its run of requests; another page in between must not inherit it. */
    @Test fun pagesTakingTurnsKeepTheirOwnExceptions() {
        val f = filter("||ads.com^", "@@||trusted.org^\$document")
        repeat(3) {
            assertFalse(f.blocks("https://ads.com/x.js", "https://trusted.org/a", SCRIPT))
            assertTrue(f.blocks("https://ads.com/x.js", page, SCRIPT))
        }
    }

    @Test fun exceptionsWinUnlessTheRuleIsImportant() {
        val f = filter("||ads.com^", "@@||ads.com/allowed/", "||must.com^\$important", "@@||must.com^")
        assertFalse(f.blocks("https://ads.com/allowed/x.js", page, SCRIPT))
        assertTrue(f.blocks("https://ads.com/other.js", page, SCRIPT))
        assertTrue(f.blocks("https://must.com/x.js", page, SCRIPT))
    }

    @Test fun optionsLimitWhereAndWhatIsBlocked() {
        val f = filter("||cdn.org/ad.\$third-party", "||img.org^\$image", "/promo.\$domain=example.com|~shop.example.com", "||x.org^\$~script")
        assertTrue(f.blocks("https://cdn.org/ad.js", page, SCRIPT))
        assertFalse(f.blocks("https://cdn.org/ad.js", "https://www.cdn.org/", SCRIPT))
        assertTrue(f.blocks("https://img.org/a", page, IMAGE))
        assertFalse(f.blocks("https://img.org/a.js", page, SCRIPT))
        assertTrue(f.blocks("https://any.net/promo.js", page, SCRIPT))
        assertFalse(f.blocks("https://any.net/promo.js", "https://shop.example.com/", SCRIPT))
        assertFalse(f.blocks("https://any.net/promo.js", "https://other.org/", SCRIPT))
        assertFalse(f.blocks("https://x.org/a.js", page, SCRIPT))
        assertTrue(f.blocks("https://x.org/a.css", page, STYLESHEET))
    }

    @Test fun unsupportedRulesAreLeftOutRatherThanMisapplied() {
        val f = filter("||popup.com^\$popup", "||csp.com^\$csp=script-src 'none'", "||doc.com^\$document", "example.com#?#div:has-text(ad)")
        assertFalse(f.blocks("https://popup.com/x.js", page, SCRIPT))
        assertFalse(f.blocks("https://csp.com/x.js", page, SCRIPT))
        assertFalse(f.blocks("https://doc.com/x.js", page, SCRIPT))
        assertEquals(0, f.size)
    }

    @Test fun regularExpressionRules() {
        val f = filter("/\\/ad[sx]?\\/[0-9]+\\//")
        assertTrue(f.blocks("https://site.org/ads/123/x.png", page, IMAGE))
        assertFalse(f.blocks("https://site.org/adz/123/x.png", page, IMAGE))
    }

    @Test fun pageExceptionsTurnFilteringOff() {
        val f = filter("||ads.com^", "@@||trusted.org^\$document", "##.ad", "@@||plain.org^\$elemhide")
        assertFalse(f.blocks("https://ads.com/x.js", "https://trusted.org/page", SCRIPT))
        assertNull(f.hidingCss("https://www.trusted.org/", listOf(".ad")))
        assertNull(f.hidingCss("https://plain.org/", listOf(".ad")))
        assertNotNull(f.hidingCss(page, listOf(".ad")))
    }

    /** `$genericblock` turns off only the rules not written for particular sites. */
    @Test fun genericBlockKeepsSiteRules() {
        val f = filter("||ads.com^", "/banner.", "||track.net/\$domain=quiet.org", "@@||quiet.org^\$genericblock")
        assertFalse(f.blocks("https://ads.com/x.js", "https://quiet.org/", SCRIPT))
        assertFalse(f.blocks("https://cdn.org/banner.png", "https://quiet.org/", IMAGE))
        assertTrue(f.blocks("https://track.net/p.js", "https://quiet.org/", SCRIPT))
        assertTrue(f.blocks("https://ads.com/x.js", page, SCRIPT))
    }

    @Test fun excludedSubdomainsKeepSiteHiddenElements() {
        val f = filter("example.com,~m.example.com##.promo")
        assertTrue(f.hidingCss("https://www.example.com/", emptyList())!!.contains(".promo"))
        assertNull(f.hidingCss("https://m.example.com/", emptyList()))
    }

    @Test fun elementHidingPicksSiteAndUsedGenericRules() {
        val f = filter("example.com##.sidebar-ad", "##.ad-box", "###top-ad", "##a[href*=\"track\"]",
            "news.example.com#@#.ad-box", "~example.com##.generic-only", "other.org##.not-here")
        val css = f.hidingCss(page, listOf(".ad-box", "#top-ad", ".unused"))!!
        assertTrue(css.contains(".sidebar-ad{display:none!important}"))
        assertTrue(css.contains("#top-ad{display:none!important}"))
        assertTrue(css.contains("a[href*=\"track\"]{display:none!important}"))
        assertFalse(css.contains(".ad-box{"))
        assertFalse(css.contains(".generic-only"))
        assertFalse(css.contains(".not-here"))
    }

    private fun roundTrip(f: AdFilter) = AdFilter.read(ByteArrayOutputStream().also(f::write).toByteArray())

    /** A filter written out and read back answers every request and page as the one built from the lists. */
    @Test fun aFilterReadBackAnswersAsTheOneBuilt() {
        val f = filter("||ads.com^", "@@||ads.com/allowed/", "||must.com^\$important", "@@||must.com^", "/banner/*/ad_", "-ad-300x250.",
            "||cdn.org/ad.\$third-party", "/promo.\$domain=example.com|~shop.example.com", "/\\/ad[sx]?\\/[0-9]+\\//", "/Case/\$match-case",
            "||x.org^\$~script", "|https://exact.org/a.js|", "@@||trusted.org^\$document", "@@||quiet.org^\$genericblock",
            "||track.net/\$domain=quiet.org", "example.com##.sidebar-ad", "##.ad-box", "###top-ad", "##a[href*=\"track\"]",
            "news.example.com#@#.ad-box", "~example.com##.generic-only", "example.com,~m.example.com##.promo", "#@#.nowhere", "##.nowhere")
        val copy = roundTrip(f)
        assertEquals(f.size, copy.size)
        val pages = listOf(page, "https://shop.example.com/", "https://www.example.com/", "https://m.example.com/", "https://trusted.org/a",
            "https://quiet.org/", "https://www.cdn.org/")
        val urls = listOf("https://ads.com/x.js", "https://ads.com/allowed/x.js", "https://must.com/x.js", "https://x.org/banner/2024/ad_top.png",
            "https://img.org/top-ad-300x250.jpg", "https://cdn.org/ad.js", "https://any.net/promo.js", "https://site.org/ads/123/x.png",
            "https://site.org/Case/a", "https://site.org/case/a", "https://x.org/a.js", "https://x.org/a.css", "https://exact.org/a.js",
            "https://track.net/p.js", "https://fine.org/app.js")
        val keys = listOf(".ad-box", "#top-ad", ".nowhere", ".unused")
        for (p in pages) {
            for (url in urls) for (kind in listOf(SCRIPT, IMAGE, STYLESHEET)) assertEquals("$url on $p", f.blocks(url, p, kind), copy.blocks(url, p, kind))
            assertEquals(p, f.hidingCss(p, keys), copy.hidingCss(p, keys))
        }
    }

    @Test fun aCutOffOrForeignFileIsRefused() {
        val bytes = ByteArrayOutputStream().also(filter("||ads.com^", "/banner.", "##.ad")::write).toByteArray()
        assertTrue(runCatching { AdFilter.read(bytes.copyOf(bytes.size - 2)) }.isFailure)
        assertTrue(runCatching { AdFilter.read(byteArrayOf(9, 0, 0)) }.isFailure)
        assertTrue(runCatching { AdFilter.read(ByteArray(0)) }.isFailure)
    }

    /** The bundled lists read back from their written form answer as when parsed, in a fraction of the time. */
    @Test fun bundledListsReadBackFromTheirWrittenForm() {
        val files = listOf("easylist.txt", "easylistchina.txt").map { File("src/main/assets/adblock/$it") }
        if (files.any { !it.isFile }) return
        val lines = files.flatMap { it.readLines() }
        val built = AdFilter.build(lines.asSequence())
        val bytes = ByteArrayOutputStream().also(built::write).toByteArray()
        repeat(3) { AdFilter.read(bytes) }
        val started = System.nanoTime()
        val read = AdFilter.read(bytes)
        val readMs = (System.nanoTime() - started) / 1_000_000
        repeat(3) { AdFilter.build(lines.asSequence()) }
        val buildStarted = System.nanoTime()
        AdFilter.build(lines.asSequence())
        val buildMs = (System.nanoTime() - buildStarted) / 1_000_000
        println("adblock cache: ${bytes.size / 1024} KB, read in $readMs ms, parsed in $buildMs ms")
        assertEquals(built.size, read.size)
        val urls = listOf("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://news.example.com/static/app.js", "https://cpro.baidustatic.com/cpro/ui/c.js", "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            "https://www.example-news.com/ads/banner-728x90.png", "https://static.zhihu.com/heifetz/main.app.js")
        val pages = listOf(page, "https://www.zhihu.com/question/1", "https://www.bilibili.com/", "https://www.youtube.com/", "https://www.baidu.com/s?wd=1")
        for (p in pages) {
            for (url in urls) assertEquals("$url on $p", built.blocks(url, p, AdFilter.requestTypes(url, "*/*")), read.blocks(url, p, AdFilter.requestTypes(url, "*/*")))
            assertEquals(p, built.hidingCss(p, listOf(".ad", ".adsbygoogle", "#ad-slot", ".banner")), read.hidingCss(p, listOf(".ad", ".adsbygoogle", "#ad-slot", ".banner")))
        }
    }

    @Test fun requestKindsFollowExtensionThenAcceptHeader() {
        assertEquals(SCRIPT, AdFilter.requestTypes("https://a.org/x.js?v=1", "*/*"))
        assertEquals(IMAGE, AdFilter.requestTypes("https://a.org/pic", "image/avif,image/webp,*/*"))
        assertEquals(STYLESHEET, AdFilter.requestTypes("https://a.org/style", "text/css,*/*;q=0.1"))
        assertEquals(0, AdFilter.requestTypes("https://a.org/api", "*/*") and (IMAGE or STYLESHEET))
    }

    @Test fun firstAndThirdPartiesShareTheirRegistrableDomain() {
        assertEquals("example.com", AdFilter.baseDomain("a.b.example.com"))
        assertEquals("example.com.cn", AdFilter.baseDomain("www.example.com.cn"))
        assertEquals("bbc.co.uk", AdFilter.baseDomain("news.bbc.co.uk"))
    }

    /** The bundled lists load quickly and block what they are known for, while ordinary pages load. */
    @Test fun bundledListsBuildAndMatchQuickly() {
        val files = listOf("easylist.txt", "easylistchina.txt").map { File("src/main/assets/adblock/$it") }
        if (files.any { !it.isFile }) return
        val started = System.nanoTime()
        val f = AdFilter.build(files.asSequence().flatMap { it.readLines().asSequence() })
        val buildMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("only ${f.size} rules", f.size > 50_000)
        assertTrue(f.blocks("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, SCRIPT))
        assertTrue(f.blocks("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page, SCRIPT))
        assertFalse(f.blocks("https://news.example.com/static/app.js", page, SCRIPT))
        assertFalse(f.blocks("https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js", page, SCRIPT))
        // Ordinary resources of sites people use every day must keep loading.
        assertFalse(f.blocks("https://static.zhihu.com/heifetz/main.app.js", "https://www.zhihu.com/question/1", SCRIPT))
        assertFalse(f.blocks("https://s1.hdslb.com/bfs/static/jinkela/long/js/jquery.min.js", "https://www.bilibili.com/", SCRIPT))
        assertFalse(f.blocks("https://i.ytimg.com/vi/abc/hqdefault.jpg", "https://www.youtube.com/", IMAGE))
        assertTrue(f.blocks("https://cpro.baidustatic.com/cpro/ui/c.js", page, SCRIPT))
        val urls = listOf("https://news.example.com/static/app.js", "https://img.example.com/photos/123.jpg",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://api.example.com/v1/feed?page=2")
        repeat(200) { urls.forEach { url -> f.blocks(url, page, AdFilter.requestTypes(url, "*/*")) } }
        val matchStarted = System.nanoTime()
        repeat(1000) { urls.forEach { url -> f.blocks(url, page, AdFilter.requestTypes(url, "*/*")) } }
        val perRequestUs = (System.nanoTime() - matchStarted) / 4000 / 1000
        println("adblock: ${f.size} rules, built in $buildMs ms, $perRequestUs us per request")
        assertTrue("matching takes $perRequestUs us", perRequestUs < 500)
    }
}
