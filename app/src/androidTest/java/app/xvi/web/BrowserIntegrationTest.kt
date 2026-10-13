package app.xvi.web

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun awaitPage(scenario: ActivityScenario<BrowserActivity>, title: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var found = false
            scenario.onActivity { activity -> found = views(activity.window.decorView).filterIsInstance<WebView>().any { it.title == title && it.progress == 100 } }
            if (found) return
            Thread.sleep(100)
        }
        fail("Page never loaded: $title")
    }
    @Test fun navigatesBackAndRestoresTabsAfterActivityRecreation() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            executor.execute {
                while (!server.isClosed) runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val title = if (line.contains("/second")) "Second local page" else "First local page"
                        val html = "<html><head><title>$title</title></head><body><h1>$title</h1><a href='/second'>Next</a><input id='text' value='form preserved'></body></html>".toByteArray()
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().write(html)
                    }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/first"
            val intent = Intent(context, BrowserActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
            ActivityScenario.launch<BrowserActivity>(intent).use { scenario ->
                awaitPage(scenario, "First local page")
                scenario.onActivity { activity -> views(activity.window.decorView).filterIsInstance<WebView>().single().loadUrl("http://127.0.0.1:${server.localPort}/second") }
                awaitPage(scenario, "Second local page")
                scenario.onActivity { activity ->
                    val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
                    assertTrue(web.canGoBack()); web.goBack()
                }
                awaitPage(scenario, "First local page")
                scenario.recreate()
                awaitPage(scenario, "First local page")
                assertTrue(BrowserStore(context).use { it.entries(false).any { entry -> entry.url == url } })
            }
        }
        executor.shutdownNow()
    }
    @Test fun bookmarkImportRejectsPrivilegedUrlsAndRoundTripsUnicode() {
        BrowserStore(context).use { store ->
            val html = "<DL><DT><A HREF='https://example.org/?a=1&amp;b=2'>中文 &amp; 书签</A><DT><A HREF='javascript:alert(1)'>bad</A></DL>"
            assertEquals(1, store.importBookmarks(html.byteInputStream()))
            val result = store.exportBookmarks()
            assertTrue(result.contains("中文 &amp; 书签"))
            assertFalse(result.contains("javascript:"))
            assertEquals(1, store.importBookmarks(result.byteInputStream()))
            store.remove(true, "https://example.org/?a=1&b=2")
        }
    }
    @Test fun bookmarksKeepTheirArrangedOrder() {
        BrowserStore(context).use { store ->
            val urls = (1..4).map { "https://order-$it.example.org/" }
            fun order() = store.entries(true).map { it.url }.filter { it in urls }
            urls.reversed().forEach { store.bookmark(it, it) }
            assertEquals(urls, order())
            // A partial list only swaps the places its own entries held.
            store.reorderBookmarks(listOf(urls[3], urls[1]))
            assertEquals(listOf(urls[0], urls[3], urls[2], urls[1]), order())
            store.bookmark("renamed", urls[2])
            store.updateBookmark(urls[3], "moved", "https://order-5.example.org/")
            assertEquals(listOf(urls[0], "https://order-5.example.org/", urls[2], urls[1]), store.entries(true).map { it.url }.filter { "order-" in it })
            (urls + "https://order-5.example.org/").forEach { store.remove(true, it) }
        }
    }
    @Test fun foldersHoldBookmarksAndTravelThroughFiles() {
        BrowserStore(context).use { store ->
            val work = store.addFolder("folder-test 工作")
            assertEquals(work, store.addFolder("folder-test 工作"))
            store.bookmark("A", "https://folder-a.example.org/", work)
            store.bookmark("B", "https://folder-b.example.org/")
            assertEquals(listOf("https://folder-a.example.org/"), store.entries(true, folder = work).map { it.url })
            assertTrue(store.entries(true, folder = BrowserStore.ROOT_FOLDER).none { it.url == "https://folder-a.example.org/" })
            // Saving an existing bookmark again keeps its folder; moving it changes only that.
            store.bookmark("A again", "https://folder-a.example.org/")
            assertEquals(work, store.entries(true, "folder-a").single().folder)
            store.updateBookmark("https://folder-b.example.org/", "B", "https://folder-b.example.org/", work)
            assertEquals(2, store.folders().single { it.id == work }.count)
            val other = store.addFolder("folder-test other")
            assertFalse(store.renameFolder(other, "folder-test 工作"))
            assertTrue(store.renameFolder(other, "folder-test renamed"))
            val html = store.exportBookmarks()
            val removed = store.removeFolder(work)
            assertTrue(store.entries(true, "folder-").isEmpty())
            store.restore(removed)
            assertEquals(2, store.entries(true, folder = work).size)
            store.removeFolder(work)
            store.importBookmarks(html.byteInputStream())
            val imported = store.folders().single { it.name == "folder-test 工作" }
            assertEquals(setOf("https://folder-a.example.org/", "https://folder-b.example.org/"), store.entries(true, folder = imported.id).map { it.url }.toSet())
            store.folders().filter { it.name.startsWith("folder-test") }.forEach { store.removeFolder(it.id) }
        }
    }
    @Test fun nativeDownloadWritesExactBytesAndSendsWebsiteCookie() = downloadWritesExactBytes(false)
    @Test fun privateDownloadSendsCookieWithoutPersistingRecord() = downloadWritesExactBytes(true)
    private fun downloadWritesExactBytes(private: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        val destination = java.io.File.createTempFile("web-download-", ".txt", context.cacheDir)
        val received = java.util.concurrent.CompletableFuture<String>()
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            executor.execute {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = StringBuilder()
                        while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; headers.append(line).append('\n') }
                        received.complete(headers.toString())
                        val bytes = "下载内容 intact & complete".toByteArray()
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().write(bytes)
                    }
                }.onFailure { received.completeExceptionally(it) }
            }
            ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    androidx.core.content.ContextCompat.startForegroundService(activity,
                        Intent(activity, BrowserDownloadService::class.java)
                            .putExtra("url", "http://127.0.0.1:${server.localPort}/download")
                            .putExtra("cookie", "downloadProbe=yes")
                            .putExtra("ua", "XviIntegrationTest")
                            .putExtra("private", private)
                            .putExtra("title", destination.name)
                            .putExtra("destination", Uri.fromFile(destination).toString()))
                }
                assertTrue(received.get(20, TimeUnit.SECONDS).contains("Cookie: downloadProbe=yes"))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline && (if (private) BrowserDownloadService.running else BrowserStore(context).use { it.downloads().none { entry -> entry.title == destination.name && entry.state == DownloadState.COMPLETED } })) Thread.sleep(100)
                assertEquals("下载内容 intact & complete", destination.readText())
                if (private) assertTrue(BrowserStore(context).use { it.downloads().none { entry -> entry.title == destination.name } })
                else assertTrue(BrowserStore(context).use { it.downloads().any { entry -> entry.title == destination.name && entry.state == DownloadState.COMPLETED } })
            }
        }
        destination.delete(); executor.shutdownNow()
    }
}
