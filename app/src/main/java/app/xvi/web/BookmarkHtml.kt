package app.xvi.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The Netscape bookmark file every browser imports and exports. Folders are `<H3>` headings each
 * followed by a `<DL>` of their contents. Plain Kotlin and jsoup, so it is covered by JVM tests.
 */
internal object BookmarkHtml {
    /** [folders] names the folders holding the bookmark, outermost first; empty outside any. */
    data class Link(val url: String, val title: String, val folders: List<String> = emptyList())

    /** Every link in [html], in file order, with the folders holding it. */
    fun read(html: String): List<Link> = Jsoup.parse(html).select("a[href]").map { Link(it.attr("href"), it.text(), foldersOf(it)) }

    /** One entry in a list of the file: a bookmark, or a folder with what it holds. */
    sealed interface Item {
        data class Page(val entry: PageEntry) : Item
        data class Folder(val name: String, val items: List<Item>) : Item
    }

    /** [top] in its order, each folder followed by the list of what it holds. */
    fun write(title: String, heading: String, top: List<Item>): String = buildString {
        fun list(items: List<Item>, indent: String) {
            items.forEach { item ->
                when (item) {
                    is Item.Page -> append(indent).append("<DT>").append(Element("a").attr("href", item.entry.url).text(item.entry.title).outerHtml()).append('\n')
                    is Item.Folder -> {
                        append(indent).append("<DT>").append(Element("h3").text(item.name).outerHtml()).append('\n')
                        append(indent).append("<DL><p>\n")
                        list(item.items, "$indent    ")
                        append(indent).append("</DL><p>\n")
                    }
                }
            }
        }
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1><META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        append(Element("title").text(title).outerHtml()).append(Element("h1").text(heading).outerHtml()).append("<DL><p>\n")
        list(top, "")
        append("</DL><p>")
    }

    /**
     * The headings of the lists holding [link], outermost first. Jsoup nests a folder's `<DL>` inside
     * the `<DT>` that holds its heading, though a file may also place it beside that `<DT>`. The toolbar
     * and "other bookmarks" folders other browsers export stand for the top level, so they are left out.
     */
    private fun foldersOf(link: Element): List<String> = link.parents().filter { it.normalName() == "dl" }.mapNotNull { list ->
        val before = list.previousElementSibling() ?: return@mapNotNull null
        val heading = if (before.normalName() == "h3") before else before.children().firstOrNull { it.normalName() == "h3" } ?: return@mapNotNull null
        if (heading.hasAttr("personal_toolbar_folder") || heading.hasAttr("unfiled_bookmarks_folder")) null else heading.text().trim()
    }.filter { it.isNotEmpty() }.reversed()
}
