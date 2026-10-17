package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen icon comes from the sharpest image the site offers, never a vector or a tiny one. */
class SiteIconsTest {
    private val page = "https://example.com/app/inbox"

    @Test fun sizesReadTheLargestSide() {
        assertEquals(512, SiteIcons.largestSize("192x192 512x512"))
        assertEquals(512, SiteIcons.largestSize("any"))
        assertEquals(0, SiteIcons.largestSize(""))
    }

    @Test fun manifestIconsResolveAgainstTheManifest() {
        val json = """{"icons":[{"src":"icons/a.png","sizes":"192x192"},{"src":"/m.png","sizes":"512x512","purpose":"any maskable"},
            {"src":"logo.svg","sizes":"any"},{"src":"v.png","type":"image/svg+xml"}]}"""
        val icons = SiteIcons.fromManifest(json, "https://cdn.example.com/static/manifest.json")
        assertEquals(listOf("https://cdn.example.com/static/icons/a.png", "https://cdn.example.com/m.png"), icons.map { it.src })
        assertTrue(icons[1].maskable)
    }

    @Test fun pageLinksGiveTouchIconsTheirConventionalSize() {
        val json = """{"manifest":"/site.webmanifest","icons":[{"src":"https://example.com/t.png","sizes":"","rel":"apple-touch-icon"},
            {"src":"https://example.com/f.ico","sizes":"16x16","rel":"icon"}]}"""
        val (manifest, icons) = SiteIcons.fromPage(json, page)
        assertEquals("https://example.com/site.webmanifest", manifest)
        assertEquals(listOf(180, 16), icons.map { it.size })
    }

    @Test fun maskableComesFirstThenLargestAndTinyIconsAreDropped() {
        val ranked = SiteIcons.rank(listOf(
            SiteIcons.Candidate("https://example.com/16.png", 16, false),
            SiteIcons.Candidate("https://example.com/180.png", 180, false),
            SiteIcons.Candidate("https://example.com/512.png", 512, false),
            SiteIcons.Candidate("https://example.com/mask.png", 192, true),
        ), page)
        assertEquals(listOf("https://example.com/mask.png", "https://example.com/512.png", "https://example.com/180.png",
            "https://example.com/apple-touch-icon.png"), ranked.map { it.src })
    }
}
