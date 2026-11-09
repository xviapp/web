package app.xvi.web

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FilterListDownloadTest {
    private val url = "https://lists.example/filter.txt"
    private val accept: (String) -> Boolean = { it.startsWith("!") }

    private fun client(body: ResponseBody, status: Int = 200) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test").body(body).build()
        }.build()

    @Test fun downloadsAcceptedContentAndReportsFinalSize() {
        val text = "! Filter list\n||ads.example^"
        val progress = mutableListOf<Pair<Long, Long>>()
        assertEquals(text, FilterListDownload.download(client(text.toResponseBody()), url, accept) { size, total ->
            progress += size to total
        })
        assertEquals(text.toByteArray().size.toLong() to text.toByteArray().size.toLong(), progress.last())
    }

    @Test fun rejectsHttpErrorsWrongContentAndNetworkFailures() {
        assertNull(FilterListDownload.download(client("! Missing".toResponseBody(), 404), url, accept))
        assertNull(FilterListDownload.download(client("<html>Wrong page</html>".toResponseBody()), url, accept))
        val failed = OkHttpClient.Builder().addInterceptor { throw IOException("Disconnected") }.build()
        assertNull(FilterListDownload.download(failed, url, accept))
    }

    @Test fun boundsBothDeclaredAndUnknownLengthBodies() {
        val buffer = Buffer().writeUtf8("! Small body")
        val declaredOversize = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 16L * 1024 * 1024
            override fun source() = buffer
        }
        assertNull(FilterListDownload.download(client(declaredOversize), url, accept))
        assertEquals("! Small body", buffer.readUtf8()) // Refused before reading the body.

        val unknownLength = object : ResponseBody() {
            private val data = Buffer().writeUtf8("!" + "x".repeat(15 * 1024 * 1024))
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = data
        }
        assertNull(FilterListDownload.download(client(unknownLength), url, accept))
    }

    @Test fun replacesTheListAndCleansPartialFilesAfterFailure() {
        val directory = Files.createTempDirectory("filter-list").toFile()
        try {
            val file = File(directory, "lists/filter.txt")
            assertTrue(FilterListDownload.save(file, "old"))
            assertTrue(FilterListDownload.save(file, "new"))
            assertEquals("new", file.readText())
            assertFalse(File(file.path + ".part").exists())

            // A nonempty directory cannot be replaced by a downloaded file.
            val blocked = File(directory, "blocked").apply { mkdirs() }
            val original = File(blocked, "existing.txt").apply { writeText("keep") }
            assertFalse(FilterListDownload.save(blocked, "replacement"))
            assertEquals("keep", original.readText())
            assertFalse(File(blocked.path + ".part").exists())
            assertFalse(FilterListDownload.save(File(file, "child.txt"), "replacement"))
            assertEquals("new", file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
