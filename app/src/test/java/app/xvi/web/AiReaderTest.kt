package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI reader view: the page cut into blocks, the AI's answer read, and the article built from the page's own text. */
class AiReaderTest {
    private val url = "https://news.example.com/a/1"
    private val page = """<html><head><title>站点标题</title><script>evil()</script></head><body>
        <nav><a href="/">首页</a><a href="/b">栏目</a></nav>
        <div class="top"><a href="/x">登录</a> <a href="/y">注册</a></div>
        <div class="post">
          <h1>真正的标题</h1>
          <div class="line">第一段正文，写在没有 p 的 div 里。</div>
          散落的文字<b>加粗</b><br>
          <p onclick="x()">第二段<a href="https://example.org/ref" onclick="y()">链接</a>。</p>
          <img data-src="https://img.example.com/a.jpg" src="data:image/gif;base64,R0" alt="示意图">
          <ul><li>要点一</li><li>要点二</li></ul>
        </div>
        <div class="ad">广告：买买买</div>
        <footer>版权所有</footer>
        </body></html>"""

    private fun outline() = AiReader.outline(page, url)!!

    @Test fun pageIsCutIntoBlocksInOrder() {
        val o = outline()
        val lines = o.listing.lines()
        assertEquals(o.blocks.size, lines.size)
        assertTrue(lines[0], lines[0].startsWith("0 段落 [多为链接] 登录"))
        assertEquals("1 h1 真正的标题", lines[1])
        assertEquals("2 段落 第一段正文，写在没有 p 的 div 里。", lines[2])
        assertEquals("3 段落 散落的文字加粗", lines[3])
        assertTrue(lines[4], lines[4].startsWith("4 段落 第二段链接。"))
        assertEquals("5 图片 示意图", lines[5])
        assertEquals("6 列表(2项) 要点一 要点二", lines[6])
        assertEquals("7 段落 广告：买买买", lines[7])
        assertFalse("navigation, footer and scripts are never sent", o.listing.contains("首页") || o.listing.contains("版权") || o.listing.contains("evil"))
        assertEquals("站点标题", o.title)
    }

    @Test fun commentsAreMarked() {
        val o = AiReader.outline("""<article><p>主帖正文</p></article>
            <section id="comments"><div class="commentItem">评论一</div></section>
            <ol class="reply-list"><li><p>跟帖一</p></li></ol>
            <div class="replyCount"><p>回复数 3</p></div>""", url)!!
        assertEquals(listOf("0 段落 主帖正文", "1 段落 [评论区] 评论一", "2 列表(1项) [评论区] 跟帖一", "3 段落 回复数 3"), o.listing.lines())
    }

    @Test fun longBlocksAreSentAsTheirOpening() {
        val long = "字".repeat(500)
        val o = AiReader.outline("<p>$long</p>", url)!!
        assertEquals("0 段落 " + "字".repeat(100) + "…（共500字）", o.listing)
    }

    @Test fun answersAreReadWithRangesAndNoise() {
        val choice = AiReader.parse("好的，结果如下：```json\n{\"title\": 1, \"keep\": [1, \"2-4\", 6, 99, \"x\", \"5\"]}\n```", 8)!!
        assertEquals(1, choice.title)
        assertEquals(listOf(2, 3, 4, 5, 6), choice.keep)
        assertNull(AiReader.parse("{\"title\": null, \"keep\": []}", 8))
        assertNull(AiReader.parse("无法判断", 8))
        assertNull(AiReader.parse("{\"keep\": [20, \"30-40\"]}", 8))
        assertEquals(listOf(6, 7), AiReader.parse("{\"keep\": [\"6-12\"]}", 8)!!.keep)
    }

    @Test fun articleIsThePagesOwnTextThroughTheSafelist() {
        val o = outline()
        val article = AiReader.article(o, AiReader.parse("{\"title\": 1, \"keep\": [\"2-6\"]}", o.blocks.size)!!)!!
        assertEquals("真正的标题", article.title)
        val html = article.html
        listOf("第一段正文", "散落的文字", "加粗", "第二段", "https://example.org/ref", "https://img.example.com/a.jpg", "要点二").forEach {
            assertTrue(it, html.contains(it))
        }
        listOf("onclick", "登录", "广告", "真正的标题", "data:image").forEach { assertFalse(it, html.contains(it)) }
    }

    @Test fun nothingReadableMeansNoArticle() {
        assertNull(AiReader.outline("<html><body><script>x()</script><nav>菜单</nav></body></html>", url))
        val o = AiReader.outline("<p><img src=\"javascript:alert(1)\"></p><p>正文</p>", url)!!
        assertNull(AiReader.article(o, AiReader.Choice(null, listOf(0))))
        assertNotNull(AiReader.article(o, AiReader.Choice(null, listOf(1))))
    }
}
