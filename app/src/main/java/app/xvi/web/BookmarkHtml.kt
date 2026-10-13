package app.xvi.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The Netscape bookmark file every browser imports and exports. Folders are `<H3>` headings each
 * followed by a `<DL>` of their contents. Plain Kotlin and jsoup, so it is covered by JVM tests.
 */
internal object BookmarkHtml {
    /** [folder] is blank for a bookmark outside any folder. */
    data class Link(val url: String, val title: String, val folder: String = "")

    /** Every link in [html], in file order, with the name of the nearest folder holding it. */
    fun read(html: String): List<Link> = Jsoup.parse(html).select("a[href]").map { Link(it.attr("href"), it.text(), folderOf(it)) }

    /** One entry at the top of the file: a bookmark, or a folder with its own. */
    sealed interface Item {
        data class Page(val entry: PageEntry) : Item
        data class Folder(val name: String, val entries: List<PageEntry>) : Item
    }

    /** [top] in its order, each folder followed by the list of what it holds. */
    fun write(title: String, heading: String, top: List<Item>): String = buildString {
        fun links(entries: List<PageEntry>, indent: String) = entries.forEach {
            append(indent).append("<DT>").append(Element("a").attr("href", it.url).text(it.title).outerHtml()).append('\n')
        }
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1><META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        append(Element("title").text(title).outerHtml()).append(Element("h1").text(heading).outerHtml()).append("<DL><p>\n")
        top.forEach { item ->
            when (item) {
                is Item.Page -> links(listOf(item.entry), "")
                is Item.Folder -> {
                    append("<DT>").append(Element("h3").text(item.name).outerHtml()).append("\n<DL><p>\n")
                    links(item.entries, "    ")
                    append("</DL><p>\n")
                }
            }
        }
        append("</DL><p>")
    }

    /**
     * The heading of the innermost list holding [link]. Jsoup nests a folder's `<DL>` inside the `<DT>`
     * that holds its heading, though a file may also place it beside that `<DT>`. The toolbar and
     * "other bookmarks" folders other browsers export stand for the top level, so they count as none.
     */
    private fun folderOf(link: Element): String {
        for (list in link.parents()) {
            if (list.normalName() != "dl") continue
            val before = list.previousElementSibling() ?: continue
            val heading = if (before.normalName() == "h3") before else before.children().firstOrNull { it.normalName() == "h3" } ?: continue
            if (heading.hasAttr("personal_toolbar_folder") || heading.hasAttr("unfiled_bookmarks_folder")) return ""
            return heading.text().trim()
        }
        return ""
    }
}
