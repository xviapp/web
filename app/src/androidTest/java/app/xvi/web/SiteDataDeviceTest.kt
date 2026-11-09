package app.xvi.web

import android.webkit.WebChromeClient
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class SiteDataDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private val site = "https://www.clear-site.example/"
    private val sibling = "https://account.clear-site.example/"
    private val other = "https://other-site.example/"

    private fun workerScript(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.path != "/sw.js") return null
        val script = """
            self.addEventListener('install', e => e.waitUntil(self.skipWaiting()));
            self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
        """.trimIndent()
        return WebResourceResponse("application/javascript", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store", "Service-Worker-Allowed" to "/"), ByteArrayInputStream(script.toByteArray()))
    }

    @Test fun defaultProfileClearsOnlyTheChosenSite() = checkDeletion("default")
    @Test fun namedProfileClearsOnlyItsOwnSiteData() = checkDeletion("named")
    @Test fun privateSessionClearsOnlyItsOwnSiteData() = checkDeletion("private")

    private fun snapshot(web: WebView, url: String, seed: String? = null): JSONObject {
        val answer = CompletableFuture<String>()
        val address = url + "?snapshot=" + System.nanoTime()
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
            await navigator.serviceWorker.register('/sw.js');
            await navigator.serviceWorker.ready;
        """.trimIndent() }.orEmpty()
        val html = """
            <script>(async () => {
                $setup
                document.title = 'snapshot:' + JSON.stringify({
                    identity: localStorage.getItem('identity'),
                    caches: await caches.keys(), databases: (await indexedDB.databases()).map(d => d.name),
                    workers: (await navigator.serviceWorker.getRegistrations()).length
                });
            })().catch(error => { document.title = 'snapshot:' + JSON.stringify({error: String(error)}); });</script>
        """.trimIndent()
        ui {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    workerScript(request) ?: if (request.url.toString() == address)
                        WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                            ByteArrayInputStream(html.toByteArray())) else null
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (title?.startsWith("snapshot:") == true) answer.complete(title.removePrefix("snapshot:"))
                }
            }
            // A loadDataWithBaseURL document cannot register workers. Use a real HTTPS navigation
            // with local responses, so registration exercises WebView without an external server.
            web.loadUrl(address)
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
        val controllers = mutableSetOf<ServiceWorkerController>()
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
            listOf(web, peerWeb).forEach {
                val controller = WebViewCompat.getProfile(it).serviceWorkerController
                controller.setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = workerScript(request)
                })
                controllers += controller
            }
        }
        try {
            val seeded = snapshot(web, site, "target")
            assertEquals("target", seeded.getString("identity"))
            assertEquals(1, seeded.getInt("workers"))
            assertEquals(1, snapshot(otherWeb, other, "other").getInt("workers"))
            assertEquals(1, snapshot(peerWeb, site, "peer").getInt("workers"))
            cookie(target, site, "identity=target; Domain=clear-site.example; Path=/; Secure; HttpOnly")
            cookie(target, sibling, "sibling=target; Path=/; Secure; HttpOnly")
            cookie(target, other, "identity=other; Path=/; Secure; HttpOnly")
            cookie(peer, site, "identity=peer; Domain=clear-site.example; Path=/; Secure; HttpOnly")
            val cleared = CompletableFuture<Unit>()
            ui {
                // Match the product flow: release the site's live document before removing data.
                target.release(web)
                target.clearSiteData(site) { cleared.complete(Unit) }
            }
            cleared.get(10, TimeUnit.SECONDS)
            ui {
                assertNull(target.cookies().getCookie(site))
                assertNull(target.cookies().getCookie(sibling))
                assertTrue(target.cookies().getCookie(other).orEmpty().contains("identity=other"))
                assertTrue(peer.cookies().getCookie(site).orEmpty().contains("identity=peer"))
                web = target.createWebView(context)
            }
            val reset = snapshot(web, site)
            assertTrue(reset.isNull("identity"))
            assertEquals(0, reset.getJSONArray("caches").length())
            assertEquals(0, reset.getJSONArray("databases").length())
            assertEquals(0, reset.getInt("workers"))
            val keptOther = snapshot(otherWeb, other)
            val keptPeer = snapshot(peerWeb, site)
            assertEquals("other", keptOther.getString("identity"))
            assertEquals("peer", keptPeer.getString("identity"))
            assertEquals(1, keptOther.getInt("workers"))
            assertEquals(1, keptPeer.getInt("workers"))
        } finally {
            ui {
                controllers.forEach { it.setServiceWorkerClient(null) }
                target.end(); peer.end(); BrowserProfiles.remove(context, named.id)
            }
        }
    }
}
