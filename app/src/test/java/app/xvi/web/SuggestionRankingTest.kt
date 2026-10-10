package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestionRankingTest {
    private val now = 1_000_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test fun typedHostPrefixBeatsMoreRecentTitleMatches() {
        val github = SuggestionCandidate("https://github.com/", "GitHub", visits = 3, updated = now - 10 * day)
        val article = SuggestionCandidate("https://news.example/about-git", "Using git well", visits = 1, updated = now)
        assertEquals(listOf(github, article), SuggestionRanking.rank(listOf(article, github), "gi", now, 10))
    }

    @Test fun frequentSitesRankAboveOneOffVisits() {
        val often = SuggestionCandidate("https://mail.example/", "Mail", visits = 40, updated = now - 3 * day)
        val once = SuggestionCandidate("https://maps.example/", "Maps", visits = 1, updated = now - 3 * day)
        assertEquals(often, SuggestionRanking.rank(listOf(once, often), "ma", now, 10).first())
    }

    @Test fun unrelatedRowsAreDropped() {
        assertEquals(emptyList<SuggestionCandidate>(), SuggestionRanking.rank(listOf(SuggestionCandidate("https://a.example/", "A")), "zzz", now, 10))
    }

    @Test fun completionOnlyExtendsHostPrefixes() {
        val rows = listOf(SuggestionCandidate("https://www.github.com/x", "GitHub", visits = 5, updated = now))
        assertEquals("github.com", SuggestionRanking.completion(rows, "git", now))
        assertEquals("github.com", SuggestionRanking.completion(rows, "GIT", now))
        assertNull(SuggestionRanking.completion(rows, "hub", now))
        assertNull(SuggestionRanking.completion(rows, "github.com", now))
        assertNull(SuggestionRanking.completion(rows, "git hub", now))
        assertNull(SuggestionRanking.completion(rows, "github.com/x", now))
        assertNull(SuggestionRanking.completion(rows, "吉", now))
    }
}
