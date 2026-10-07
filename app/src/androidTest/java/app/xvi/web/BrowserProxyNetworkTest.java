package app.xvi.web;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.v2ray.ang.core.LauncherManager;
import com.v2ray.ang.embedded.EmbeddedProxy;
import com.v2ray.ang.handler.AngConfigManager;
import com.v2ray.ang.handler.MmkvManager;
import com.v2ray.ang.service.CoreProxyOnlyService;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserProxyNetworkTest {
    private interface Operation { void run(java.util.function.Consumer<ProxyState> done); }
    private ProxyState await(Operation operation) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ProxyState> state = new AtomicReference<>();
        operation.run(value -> { state.set(value); latch.countDown(); });
        assertTrue("Network operation timed out", latch.await(25, TimeUnit.SECONDS));
        return state.get();
    }
    private ProxyState phase(BrowserProxyNetwork network, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        ProxyState state;
        do {
            state = await(network::status);
            if (state.text("phase").equals(expected)) return state;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + expected + " but got " + state);
    }
    private String request(BrowserProxyNetwork network, String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(network.proxy());
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        try { return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
        finally { connection.disconnect(); }
    }
    @Test public void generatedProfilesIgnoreStandaloneSettings() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<ProxyTestActivity> activity = ActivityScenario.launch(ProxyTestActivity.class)) {
            BrowserProxyNetwork network = BrowserProxyNetwork.get(context);
            await(done -> network.setEnabled(false, done));
            // A restored standalone backup must not re-enable system routing or expose the listener.
            MmkvManager.INSTANCE.encodeSettings("pref_mode", "VPN");
            MmkvManager.INSTANCE.encodeSettings("pref_root_mode_enabled", true);
            MmkvManager.INSTANCE.encodeSettings("pref_root_lan_sharing", true);
            MmkvManager.INSTANCE.encodeSettings("pref_proxy_sharing_enabled", true);
            MmkvManager.INSTANCE.encodeSettings("pref_enable_local_proxy", false);
            MmkvManager.INSTANCE.encodeSettings("pref_use_hev_tunnel_v2", false);
            MmkvManager.INSTANCE.encodeSettings("pref_route_only_enabled", true);
            MmkvManager.INSTANCE.encodeSettings("pref_socks_username", "legacy-user");
            MmkvManager.INSTANCE.encodeSettings("pref_socks_password", "legacy-password");
            com.v2ray.ang.handler.SettingsManager.INSTANCE.resetRoutingRulesets(
                "[{\"domain\":[\"full:localhost\"],\"process\":[\"other.application\"],\"outboundTag\":\"direct\"}]");
            MmkvManager.INSTANCE.setSelectServer("");
            AngConfigManager.INSTANCE.importBatchConfig(
                "vless://11111111-1111-4111-8111-111111111111@127.0.0.1:1?encryption=none&security=none&type=tcp#Generated-test", "", false);
            String guid = MmkvManager.INSTANCE.getSelectServer();
            com.v2ray.ang.dto.ConfigResult result = com.v2ray.ang.core.CoreConfigManager.INSTANCE.getV2rayConfig(context, guid);
            assertTrue(result.getErrorMessage(), result.getStatus());
            org.json.JSONObject config = new org.json.JSONObject(result.getContent());
            org.json.JSONArray inbounds = config.getJSONArray("inbounds");
            assertEquals(1, inbounds.length());
            org.json.JSONObject inbound = inbounds.getJSONObject(0);
            assertEquals("127.0.0.1", inbound.getString("listen"));
            assertEquals("socks", inbound.getString("protocol"));
            assertEquals("noauth", inbound.getJSONObject("settings").getString("auth"));
            assertFalse(inbound.getJSONObject("settings").getBoolean("udp"));
            org.json.JSONArray rules = config.getJSONObject("routing").getJSONArray("rules");
            for (int i = 0; i < rules.length(); i++) assertTrue(rules.getJSONObject(i).isNull("process"));
            try {
                assertEquals("running", await(done -> network.setEnabled(true, done)).text("phase"));
            } finally {
                await(done -> network.setEnabled(false, done));
            }
        }
    }

    @Test public void nativeAndWebViewRoutingFailureRecoveryAndIsolation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String globalProxy = Settings.Global.getString(context.getContentResolver(), "http_proxy");
        try (ActivityScenario<ProxyTestActivity> activity = ActivityScenario.launch(ProxyTestActivity.class);
             ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            BrowserProxyNetwork network = BrowserProxyNetwork.get(context);
            assertEquals("off", await(done -> network.setEnabled(false, done)).text("phase"));
            MmkvManager.INSTANCE.setSelectServer("");
            assertEquals("failed", await(done -> network.setEnabled(true, done)).text("phase"));
            assertEquals(Proxy.NO_PROXY, network.proxy());
            assertTrue(EmbeddedProxy.isEnabled());
            String raw = "{\"remarks\":\"Isolated proxy test\",\"inbounds\":[],\"outbounds\":[{\"tag\":\"direct\",\"protocol\":\"freedom\"},{\"tag\":\"block\",\"protocol\":\"blackhole\"},{\"tag\":\"proxy\",\"protocol\":\"socks\",\"settings\":{\"servers\":[{\"address\":\"127.0.0.1\",\"port\":1}]}}],\"routing\":{\"rules\":[{\"type\":\"field\",\"domain\":[\"full:blocked.proxy.test\"],\"outboundTag\":\"block\"},{\"type\":\"field\",\"domain\":[\"full:unavailable.proxy.test\"],\"outboundTag\":\"proxy\"}]}}";
            AngConfigManager.INSTANCE.importBatchConfig(raw, "", false);
            assertTrue(EmbeddedProxy.hasProfile());
            assertEquals("running", await(done -> network.setEnabled(true, done)).text("phase"));
            int port = EmbeddedProxy.getPort();
            ExecutorService responses = Executors.newSingleThreadExecutor();
            responses.submit(() -> {
                try {
                    while (!server.isClosed()) {
                        try (Socket socket = server.accept()) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            String requestLine = reader.readLine();
                            int length = 0;
                            boolean range = false;
                            boolean cookie = false;
                            for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                                if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.split(":", 2)[1].trim());
                                if (line.toLowerCase().startsWith("range:")) range = true;
                                if (line.toLowerCase().startsWith("cookie:") && line.contains("probe=test")) cookie = true;
                            }
                            char[] body = new char[length];
                            int read = 0;
                            while (read < length) { int count = reader.read(body, read, length - read); if (count < 0) break; read += count; }
                            if (requestLine.contains("/stream")) {
                                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\nfirst".getBytes(StandardCharsets.UTF_8));
                                socket.getOutputStream().flush();
                                try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                                socket.getOutputStream().write("second".getBytes(StandardCharsets.UTF_8));
                                continue;
                            }
                            String payload = requestLine.contains("/post") ? new String(body) + (cookie ? ":cookie" : ":missing-cookie") : "ok";
                            String status = range ? "206 Partial Content" : "200 OK";
                            socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + payload.length() + "\r\nConnection: close\r\n\r\n" + payload).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException ignored) {}
            });
            try {
                String address = "http://127.0.0.1:" + server.getLocalPort() + "/probe";
                assertEquals("ok", request(network, address));
                CountDownLatch webDone = new CountDownLatch(1);
                AtomicReference<String> webResult = new AtomicReference<>();
                activity.onActivity(host -> {
                    host.webView.addJavascriptInterface(new Object() {
                        @android.webkit.JavascriptInterface public void result(String value) { webResult.set(value); webDone.countDown(); }
                    }, "Probe");
                    android.webkit.CookieManager.getInstance().setCookie(address, "probe=test; HttpOnly; SameSite=Lax");
                    String script = "fetch('" + address.replace("/probe", "/post") + "',{method:'POST',body:'hello'}).then(r=>r.text()).then(async v=>{const r=await fetch('" + address + "',{headers:{Range:'bytes=0-1'}});const stream=await fetch('" + address.replace("/probe", "/stream") + "');const reader=stream.body.getReader();const first=new TextDecoder().decode((await reader.read()).value);let all=first;for(;;){const part=await reader.read();if(part.done)break;all+=new TextDecoder().decode(part.value)}Probe.result(v+':'+r.status+':'+first+':'+all)}).catch(e=>Probe.result(String(e)))";
                    host.webView.loadDataWithBaseURL(address, "<script>" + script + "</script>", "text/html", "UTF-8", null);
                });
                assertTrue(webDone.await(10, TimeUnit.SECONDS));
                assertEquals("hello:cookie:206:first:firstsecond", webResult.get());
                for (String domain : new String[]{"blocked.proxy.test", "unavailable.proxy.test"}) {
                    try { request(network, "http://" + domain + ":" + server.getLocalPort() + "/probe"); fail("Expected rule failure"); }
                    catch (IOException expected) {}
                    assertEquals("running", await(network::status).text("phase"));
                    assertNotEquals(Proxy.NO_PROXY, network.proxy());
                }
                LauncherManager.INSTANCE.startService(context, null);
                phase(network, "running");
                assertEquals(port, EmbeddedProxy.getPort());
                CountDownLatch reloaded = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean sawReload = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.function.Consumer<ProxyState> reloadObserver = value -> {
                    if (value.text("phase").equals("starting")) sawReload.set(true);
                    if (sawReload.get() && value.text("phase").equals("running")) reloaded.countDown();
                };
                network.observe(reloadObserver);
                LauncherManager.INSTANCE.restartService(context);
                assertTrue(reloaded.await(20, TimeUnit.SECONDS));
                network.unobserve(reloadObserver);
                assertEquals("ok", request(network, address));
                context.stopService(new Intent(context, CoreProxyOnlyService.class));
                phase(network, "reconnecting");
                assertEquals(Proxy.NO_PROXY, network.proxy());
                phase(network, "running");
                assertEquals("ok", request(network, address));
                CountDownLatch pidReady = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicInteger daemonPid = new java.util.concurrent.atomic.AtomicInteger();
                context.sendOrderedBroadcast(new Intent(com.v2ray.ang.AppConfig.BROADCAST_ACTION_SERVICE)
                    .setPackage(context.getPackageName()).putExtra("key", com.v2ray.ang.AppConfig.MSG_REGISTER_CLIENT), null,
                    new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context ignored, Intent reply) {
                            daemonPid.set(getResultExtras(false).getInt("daemonPid")); pidReady.countDown();
                        }
                    }, null, android.app.Activity.RESULT_CANCELED, null, null);
                assertTrue(pidReady.await(5, TimeUnit.SECONDS));
                assertTrue(daemonPid.get() > 0 && daemonPid.get() != android.os.Process.myPid());
                android.os.Process.killProcess(daemonPid.get());
                phase(network, "reconnecting");
                assertEquals(Proxy.NO_PROXY, network.proxy());
                phase(network, "running");
                assertEquals("ok", request(network, address));
                assertEquals("off", await(done -> network.setEnabled(false, done)).text("phase"));
                assertEquals(Proxy.NO_PROXY, network.proxy());
                assertEquals(globalProxy, Settings.Global.getString(context.getContentResolver(), "http_proxy"));
            } finally { server.close(); responses.shutdownNow(); await(done -> network.setEnabled(false, done)); }
        }
    }
}
