package app.xvi.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * Finds a page's main text for the reader view. The result passes a strict safelist, so nothing from
 * the page can run or style itself in the reader.
 */
internal object ReaderExtractor {
    class Article(val title: String, val html: String)

    private const val MIN_TEXT = 200
    private val noise = "script,style,noscript,template,iframe,svg,canvas,form,button,input,select,textarea,nav,header,footer,aside," +
        "[role=navigation],[role=banner],[role=contentinfo],[aria-hidden=true],[hidden]"
    private val unlikely = Regex("comment|share|social|related|sidebar|footer|header|menu|nav|promo|advert|banner|cookie|popup|subscribe|recommend",
        RegexOption.IGNORE_CASE)
    private val safelist = Safelist.relaxed()
        .removeTags("span", "div", "colgroup", "col")
        .addProtocols("img", "src", "http", "https")
        .addProtocols("a", "href", "http", "https")

    /** Null when the page has too little continuous text to be worth a reader view. */
    fun extract(html: String, url: String): Article? {
        val document = Jsoup.parse(html, url)
        val title = (document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.title()).trim()
        // Lazy-loaded images keep the real address in a data attribute.
        document.select("img").forEach { img ->
            listOf("data-src", "data-original", "data-lazy-src", "data-actualsrc").firstNotNullOfOrNull { img.attr(it).takeIf(String::isNotBlank) }
                ?.let { img.attr("src", it) }
        }
        document.select(noise).remove()
        val root = best(document) ?: return null
        if (root.text().length < MIN_TEXT) return null
        root.select("*").filter { element ->
            val marker = element.className() + " " + element.id()
            element !== root && unlikely.containsMatchIn(marker) && element.select("p").size < 2
        }.forEach { it.remove() }
        root.select("h1").firstOrNull()?.takeIf { it.text().trim() == title }?.remove()
        val clean = Jsoup.clean(root.outerHtml(), document.location(), safelist)
        return Article(title, clean)
    }

    /**
     * Title and plain text of the page for the AI assistant: the reader's main text when there is
     * one, otherwise everything visible in the body. Blocks become lines so the structure survives.
     */
    fun text(html: String, url: String): Pair<String, String> {
        val article = extract(html, url)
        val document = Jsoup.parse(html, url)
        val title = article?.title ?: document.title().trim()
        val body = if (article != null) Jsoup.parseBodyFragment(article.html).body()
            else document.body().also { it.select(noise).remove() }
        body.select("br").forEach { it.after("\n") }
        body.select("p, h1, h2, h3, h4, h5, h6, li, div, tr, pre, blockquote, section, article").forEach { it.appendText("\n") }
        val lines = body.wholeText().lineSequence()
            .map { it.replace(spaces, " ").trim() }
            .filter { it.isNotEmpty() }
        return title to lines.joinToString("\n")
    }

    private val spaces = Regex("[ \\t\\u00a0]+")

    /** `article` or `main` when they hold the text, otherwise the block with the most paragraph text. */
    private fun best(document: Document): Element? {
        document.select("article, main, [role=main], [itemprop=articleBody]")
            .maxByOrNull { paragraphText(it) }
            ?.takeIf { paragraphText(it) >= MIN_TEXT }
            ?.let { return it }
        val scores = HashMap<Element, Int>()
        document.select("p, pre, blockquote, li").forEach { p ->
            val length = p.text().length
            if (length < 25) return@forEach
            val parent = p.parent() ?: return@forEach
            scores[parent] = (scores[parent] ?: 0) + length + 25
            parent.parent()?.let { scores[it] = (scores[it] ?: 0) + (length + 25) / 2 }
        }
        return scores.maxByOrNull { it.value }?.key ?: document.body()
    }

    private fun paragraphText(element: Element) = element.select("p").sumOf { it.text().length }

    /** Complete reader document; colors follow the browser's appearance. */
    fun page(article: Article, dark: Boolean, site: String): String {
        val background = if (dark) "#1c1b1a" else "#fbf8f3"
        val ink = if (dark) "#e6e1da" else "#2a2724"
        val muted = if (dark) "#a49d94" else "#7a7269"
        val link = if (dark) "#8fc7bb" else "#2f7a6b"
        val title = org.jsoup.nodes.Element("h1").text(article.title).outerHtml()
        val source = org.jsoup.nodes.Element("p").addClass("site").text(site).outerHtml()
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http:; style-src 'unsafe-inline'">
<style>
html { background: $background; }
body { color: $ink; font: 18px/1.75 sans-serif; margin: 0 auto; max-width: 42em; padding: 12px 20px 48px; word-wrap: break-word; }
h1 { font-size: 1.5em; line-height: 1.35; margin: .4em 0 .2em; }
h2, h3, h4 { line-height: 1.4; margin-top: 1.4em; }
.site { color: $muted; font-size: .8em; margin: 0 0 1.6em; }
img { max-width: 100%; height: auto; display: block; margin: 1em auto; border-radius: 6px; }
a { color: $link; }
pre, code { font-family: monospace; font-size: .85em; white-space: pre-wrap; }
blockquote { border-left: 3px solid $muted; margin: 1em 0; padding-left: 1em; color: $muted; }
table { border-collapse: collapse; display: block; overflow-x: auto; }
td, th { border: 1px solid $muted; padding: 4px 8px; }
</style></head><body>$title$source${article.html}</body></html>"""
    }
}
