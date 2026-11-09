package app.xvi.web

import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SiteDataDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private val site = "https://www.clear-site.example/"
    private val sibling = "https://account.clear-site.example/"
    private val other = "https://other-site.example/"

    @Test fun defaultProfileClearsOnlyTheChosenSite() = checkDeletion("default")
    @Test fun namedProfileClearsOnlyItsOwnSiteData() = checkDeletion("named")
    @Test fun privateSessionClearsOnlyItsOwnSiteData() = checkDeletion("private")

    private fun snapshot(web: WebView, url: String, seed: String? = null): JSONObject {
        val answer = CompletableFuture<String>()
        val setup = seed?.let { value -> """
            localStorage.setItem('identity', '$value');
            await (await caches.open('site-reset')).put('/marker', new Response('cached'));
            const db = await new Promise((resolve, reject) => {
                const request = indexedDB.open('site-reset', 1);
                request.onupgradeneeded = () => request.result.createObjectStore('values');
                request.onsuccess = () => resolve(request.result);
                request.onerror = () => reject(request.error);
            });
            db.close();
        """.trimIndent() }.orEmpty()
        val html = """
            <script>(async () => {
                $setup
                document.title = 'snapshot:' + JSON.stringify({
                    identity: localStorage.getItem('identity'),
                    caches: await caches.keys(), databases: (await indexedDB.databases()).map(d => d.name)
                });
            })().catch(error => { document.title = 'snapshot:' + JSON.stringify({error: String(error)}); });</script>
        """.trimIndent()
        ui {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (title?.startsWith("snapshot:") == true) answer.complete(title.removePrefix("snapshot:"))
                }
            }
            web.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
        }
        return JSONObject(answer.get(10, TimeUnit.SECONDS)).also { assertFalse(it.toString(), it.has("error")) }
    }

    private fun cookie(session: BrowserSession, url: String, value: String) {
        val saved = CompletableFuture<Unit>()
        ui { session.cookies().setCookie(url, value) { saved.complete(Unit) } }
        saved.get(10, TimeUnit.SECONDS)
    }

    private fun checkDeletion(mode: String) {
        var supported = false
        ui { supported = BrowserProfiles.supported && BrowserSession.privateAvailable() }
        assumeTrue("WebView needs site deletion and profiles", supported)
        lateinit var named: BrowserProfile
        lateinit var target: BrowserSession
        lateinit var peer: BrowserSession
        lateinit var web: WebView
        lateinit var otherWeb: WebView
        lateinit var peerWeb: WebView
        ui {
            named = BrowserProfiles.create(context, "Site data test")!!
            target = when (mode) {
                "named" -> BrowserSession(namedProfile = named.webProfile)
                "private" -> BrowserSession(true)
                else -> BrowserSession()
            }
            peer = if (mode == "default") BrowserSession(namedProfile = named.webProfile) else BrowserSession()
            web = target.createWebView(context)
            otherWeb = target.createWebView(context)
            peerWeb = peer.createWebView(context)
        }
        try {
            assertEquals("target", snapshot(web, site, "target").getString("identity"))
            snapshot(otherWeb, other, "other")
            snapshot(peerWeb, site, "peer")
            cookie(target, site, "identity=target; Domain=clear-site.example; Path=/; Secure; HttpOnly")
            cookie(target, sibling, "sibling=target; Path=/; Secure; HttpOnly")
            cookie(target, other, "identity=other; Path=/; Secure; HttpOnly")
            cookie(peer, site, "identity=peer; Domain=clear-site.example; Path=/; Secure; HttpOnly")
            val cleared = CompletableFuture<Unit>()
            ui { target.clearSiteData(site) { cleared.complete(Unit) } }
            cleared.get(10, TimeUnit.SECONDS)
            ui {
                assertNull(target.cookies().getCookie(site))
                assertNull(target.cookies().getCookie(sibling))
                assertTrue(target.cookies().getCookie(other).orEmpty().contains("identity=other"))
                assertTrue(peer.cookies().getCookie(site).orEmpty().contains("identity=peer"))
                target.release(web)
                web = target.createWebView(context)
            }
            val reset = snapshot(web, site)
            assertTrue(reset.isNull("identity"))
            assertEquals(0, reset.getJSONArray("caches").length())
            assertEquals(0, reset.getJSONArray("databases").length())
            assertEquals("other", snapshot(otherWeb, other).getString("identity"))
            assertEquals("peer", snapshot(peerWeb, site).getString("identity"))
        } finally {
            ui { target.end(); peer.end(); BrowserProfiles.remove(context, named.id) }
        }
    }
}
