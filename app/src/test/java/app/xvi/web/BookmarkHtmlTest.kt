package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bookmark files keep their folders on the way out and back in, including files other browsers wrote. */
class BookmarkHtmlTest {
    @Test fun writtenFileReadsBackWithFolders() {
        val html = BookmarkHtml.write("书签", "书签", listOf(
            BookmarkHtml.Item.Folder("工作", listOf(PageEntry("https://b.org/", "B"), PageEntry("https://c.org/", "C"))),
            BookmarkHtml.Item.Page(PageEntry("https://a.org/", "A <&>")),
            BookmarkHtml.Item.Folder("空", emptyList()),
            BookmarkHtml.Item.Page(PageEntry("https://d.org/", "D"))
        ))
        assertEquals(listOf(
            BookmarkHtml.Link("https://b.org/", "B", "工作"),
            BookmarkHtml.Link("https://c.org/", "C", "工作"),
            BookmarkHtml.Link("https://a.org/", "A <&>"),
            BookmarkHtml.Link("https://d.org/", "D")
        ), BookmarkHtml.read(html))
    }

    /** The layout Chrome and Firefox export: a toolbar folder at the top, folders nested inside it. */
    @Test fun otherBrowsersFilesKeepTheNearestFolder() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
                <DT><H3 ADD_DATE="1" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
                <DL><p>
                    <DT><A HREF="https://top.org/">Top</A>
                    <DT><H3>News</H3>
                    <DL><p>
                        <DT><A HREF="https://news.org/">News site</A>
                        <DT><H3>Deep</H3>
                        <DL><p>
                            <DT><A HREF="https://deep.org/">Deep site</A>
                        </DL><p>
                        <DT><A HREF="https://after.org/">After</A>
                    </DL><p>
                </DL><p>
                <DT><A HREF="https://loose.org/">Loose</A>
            </DL><p>
        """.trimIndent()
        assertEquals(listOf("" , "News", "Deep", "News", ""), BookmarkHtml.read(html).map { it.folder })
    }
}
