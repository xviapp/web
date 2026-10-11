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
        assertTrue(f.pageExempt("https://www.trusted.org/"))
        assertNull(f.hidingCss("https://plain.org/", listOf(".ad")))
        assertNotNull(f.hidingCss(page, listOf(".ad")))
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
