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

    /**
     * The page's markup for the reader and the assistant, without what both drop before anything else:
     * scripts, styles, frames and drawings, often most of a modern page's markup. They are taken out of a
     * copy in a document of its own, which loads nothing, so the page itself is left as it is.
     */
    const val PAGE_HTML = "(() => { const root = document.documentElement; if (!root) return '';" +
        " try { const copy = document.implementation.createHTMLDocument('').importNode(root, true);" +
        " for (const e of copy.querySelectorAll('script,style,noscript,template,iframe,svg,canvas')) e.remove();" +
        " return copy.outerHTML; } catch (_) { return root.outerHTML; } })()"

    /** [html] with everything outside the reader's safelist removed, so nothing in it can run or style itself. */
    fun clean(html: String, url: String): String = Jsoup.clean(html, url, safelist)

    /** Lazy-loaded images keep the real address in a data attribute; this puts it where the reader looks. */
    fun loadLazyImages(document: Document) = document.select("img").forEach { img ->
        listOf("data-src", "data-original", "data-lazy-src", "data-actualsrc").firstNotNullOfOrNull { img.attr(it).takeIf(String::isNotBlank) }
            ?.let { img.attr("src", it) }
    }

    /** Null when the page has too little continuous text to be worth a reader view. */
    fun extract(html: String, url: String): Article? = extract(Jsoup.parse(html, url))

    /** Leaves [document] without its scripts, navigation and other page chrome. */
    private fun extract(document: Document): Article? {
        val title = (document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.title()).trim()
        loadLazyImages(document)
        document.select(noise).remove()
        val root = best(document) ?: return null
        if (root.text().length < MIN_TEXT) return null
        root.select("*").filter { element ->
            val marker = element.className() + " " + element.id()
            element !== root && unlikely.containsMatchIn(marker) && element.select("p").size < 2
        }.forEach { it.remove() }
        root.select("h1").firstOrNull()?.takeIf { it.text().trim() == title }?.remove()
        return Article(title, clean(root.outerHtml(), document.location()))
    }

    /**
     * Title and plain text of the page for the AI assistant: the reader's main text when there is
     * one, otherwise everything visible in the body. Blocks become lines so the structure survives.
     */
    fun text(html: String, url: String): Pair<String, String> {
        val document = Jsoup.parse(html, url)
        val article = extract(document)
        val title = article?.title ?: document.title().trim()
        val body = if (article != null) Jsoup.parseBodyFragment(article.html).body() else document.body()
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
    /** How the reader page looks; the choices are kept in [BrowserPrefs]. */
    data class Style(val tone: Tone = Tone.AUTO, val serif: Boolean = false, val spacing: Spacing = Spacing.NORMAL)

    /** Page colours; [AUTO] follows the browser's light or dark appearance. */
    enum class Tone(val key: String) {
        AUTO("auto"), LIGHT("light"), SEPIA("sepia"), DARK("dark");
        companion object { fun parse(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO }
    }

    enum class Spacing(val key: String, val lineHeight: String) {
        COMPACT("compact", "1.5"), NORMAL("normal", "1.75"), RELAXED("relaxed", "2");
        companion object { fun parse(key: String?) = entries.firstOrNull { it.key == key } ?: NORMAL }
    }

    /** Background, text, secondary text and link colours for [tone]. */
    data class Colors(val background: String, val ink: String, val muted: String, val link: String)

    fun colors(tone: Tone, dark: Boolean): Colors = when (tone) {
        Tone.AUTO -> if (dark) colors(Tone.DARK, true) else Colors("#fbf8f3", "#2a2724", "#7a7269", "#2f7a6b")
        Tone.LIGHT -> Colors("#ffffff", "#1f1f1f", "#6b6b6b", "#2f7a6b")
        Tone.SEPIA -> Colors("#f4ecd8", "#3b3024", "#7d6e5a", "#8a5a2b")
        Tone.DARK -> Colors("#1c1b1a", "#e6e1da", "#a49d94", "#8fc7bb")
    }

    fun page(article: Article, dark: Boolean, site: String, style: Style = Style()): String {
        val (background, ink, muted, link) = colors(style.tone, dark)
        val font = if (style.serif) "serif" else "sans-serif"
        val title = org.jsoup.nodes.Element("h1").text(article.title).outerHtml()
        val source = org.jsoup.nodes.Element("p").addClass("site").text(site).outerHtml()
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http:; style-src 'unsafe-inline'">
<style>
html { background: $background; }
body { color: $ink; font: 18px/${style.spacing.lineHeight} $font; margin: 0 auto; max-width: 42em; padding: 12px 20px 48px; word-wrap: break-word; }
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
