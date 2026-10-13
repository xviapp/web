package app.xvi.web;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.v2ray.ang.internal.xray.LauncherManager;
import com.v2ray.ang.internal.model.entities.ProfileItem;
import com.v2ray.ang.internal.runtime.EmbeddedProxy;
import com.v2ray.ang.api.ProxyPhase;
import com.v2ray.ang.api.ProxyState;
import com.v2ray.ang.api.EmbeddedProxyController;
import com.v2ray.ang.api.Observation;
import com.v2ray.ang.internal.model.EConfigType;
import com.v2ray.ang.internal.storage.MmkvManager;
import com.v2ray.ang.internal.runtime.CoreProxyOnlyService;
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
    private ProxyState phase(BrowserProxyNetwork network, ProxyPhase expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        ProxyState state;
        do {
            state = await(network::status);
            if (state.phase == expected) return state;
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
    private String seedVlessProfile() {
        ProfileItem profile = ProfileItem.Companion.create(EConfigType.VLESS);
        profile.setRemarks("Generated-test");
        profile.setServer("127.0.0.1");
        profile.setServerPort("1");
        profile.setPassword("11111111-1111-4111-8111-111111111111");
        profile.setMethod("none");
        profile.setNetwork("tcp");
        profile.setSecurity("none");
        return MmkvManager.INSTANCE.encodeServerConfig("", profile);
    }

    private void bridgeSocks(Socket client, int targetPort) {
        try (Socket source = client) {
            DataInputStream input = new DataInputStream(source.getInputStream());
            DataOutputStream output = new DataOutputStream(source.getOutputStream());
            if (input.readUnsignedByte() != 5) return;
            int methodCount = input.readUnsignedByte();
            input.skipBytes(methodCount);
            output.write(new byte[]{5, 0});
            output.flush();
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 1) return;
            input.readUnsignedByte();
            int addressType = input.readUnsignedByte();
            String host;
            if (addressType == 1) {
                host = (input.readUnsignedByte() & 255) + "." + (input.readUnsignedByte() & 255) + "."
                    + (input.readUnsignedByte() & 255) + "." + (input.readUnsignedByte() & 255);
            } else if (addressType == 3) {
                byte[] bytes = new byte[input.readUnsignedByte()];
                input.readFully(bytes);
                host = new String(bytes, StandardCharsets.UTF_8);
            } else {
                input.skipBytes(addressType == 4 ? 16 : 0);
                input.readUnsignedShort();
                output.write(new byte[]{5, 8, 0, 1, 0, 0, 0, 0, 0, 0});
                output.flush();
                return;
            }
            int port = input.readUnsignedShort();
            if (!"127.0.0.1".equals(host) || port != targetPort) {
                output.write(new byte[]{5, 4, 0, 1, 0, 0, 0, 0, 0, 0});
                output.flush();
                return;
            }
            try (Socket target = new Socket(host, port)) {
                output.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                output.flush();
                Thread upload = new Thread(() -> copy(source, target), "proxy-test-upload");
                upload.setDaemon(true);
                upload.start();
                copy(target, source);
            }
        } catch (IOException ignored) {}
    }

    private void copy(Socket source, Socket target) {
        try {
            byte[] buffer = new byte[8192];
            for (int count; (count = source.getInputStream().read(buffer)) >= 0;) {
                target.getOutputStream().write(buffer, 0, count);
                target.getOutputStream().flush();
            }
        } catch (IOException ignored) {}
    }

    @Test public void defaultRuntimeConfigIgnoresLegacySettings() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<ProxyTestActivity> activity = ActivityScenario.launch(ProxyTestActivity.class)) {
            BrowserProxyNetwork network = BrowserProxyNetwork.get(context);
            await(done -> network.setEnabled(false, done));
            // Legacy standalone preferences must not re-enable routing or expose the listener.
            MmkvManager.INSTANCE.encodeSettings("pref_mode", "VPN");
            MmkvManager.INSTANCE.encodeSettings("pref_root_mode_enabled", true);
            MmkvManager.INSTANCE.encodeSettings("pref_root_lan_sharing", true);
            MmkvManager.INSTANCE.encodeSettings("pref_proxy_sharing_enabled", true);
            MmkvManager.INSTANCE.encodeSettings("pref_enable_local_proxy", false);
            MmkvManager.INSTANCE.encodeSettings("pref_use_hev_tunnel_v2", false);
            MmkvManager.INSTANCE.encodeSettings("pref_route_only_enabled", true);
            MmkvManager.INSTANCE.encodeSettings("pref_socks_username", "legacy-user");
            MmkvManager.INSTANCE.encodeSettings("pref_socks_password", "legacy-password");
            MmkvManager.INSTANCE.setSelectServer("");
            String guid = seedVlessProfile();
            com.v2ray.ang.internal.model.ConfigResult result = com.v2ray.ang.internal.xray.CoreConfigManager.INSTANCE.getV2rayConfig(context, guid);
            assertTrue(result.getErrorMessage(), result.getStatus());
            org.json.JSONObject config = new org.json.JSONObject(result.getContent());
            org.json.JSONArray inbounds = config.getJSONArray("inbounds");
            assertEquals(1, inbounds.length());
            org.json.JSONObject inbound = inbounds.getJSONObject(0);
            assertEquals("127.0.0.1", inbound.getString("listen"));
            assertEquals("socks", inbound.getString("protocol"));
            assertEquals("noauth", inbound.getJSONObject("settings").getString("auth"));
            assertTrue(inbound.getJSONObject("settings").getBoolean("udp"));
            org.json.JSONArray rules = config.getJSONObject("routing").getJSONArray("rules");
            assertEquals(0, rules.length());
            try {
                assertEquals(ProxyPhase.RUNNING, await(done -> network.setEnabled(true, done)).phase);
            } finally {
                await(done -> network.setEnabled(false, done));
            }
        }
    }

    @Test public void holdKeepsNativeAndWebViewTrafficFromGoingDirect() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<ProxyTestActivity> activity = ActivityScenario.launch(ProxyTestActivity.class);
             ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            BrowserProxyNetwork network = BrowserProxyNetwork.get(context);
            ExecutorService responses = Executors.newSingleThreadExecutor();
            responses.submit(() -> {
                try {
                    while (!server.isClosed()) {
                        try (Socket socket = server.accept()) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            for (String line; (line = reader.readLine()) != null && !line.isEmpty();) { }
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException ignored) {}
            });
            // Local addresses bypass the proxy in WebView, so the page probes a name that only resolves through a proxy.
            String nativeAddress = "http://127.0.0.1:" + server.getLocalPort() + "/probe";
            try {
                assertEquals(ProxyPhase.OFF, await(done -> network.setEnabled(false, done)).phase);
                network.setHoldOnFailure(true);
                // Off means off: holding applies only while the proxy is switched on.
                assertFalse(network.holding());
                assertEquals(Proxy.NO_PROXY, network.proxy());
                MmkvManager.INSTANCE.setSelectServer("");
                assertEquals(ProxyPhase.FAILED, await(done -> network.setEnabled(true, done)).phase);
                assertTrue(network.holding());
                assertEquals(Proxy.Type.SOCKS, network.proxy().type());
                try { request(network, nativeAddress); fail("Held traffic went direct"); }
                catch (IOException expected) {}
                assertEquals("held", webFetch(activity, "http://example.com/"));
                network.setHoldOnFailure(false);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertFalse(network.holding());
                assertEquals(Proxy.NO_PROXY, network.proxy());
                assertEquals("ok", request(network, nativeAddress));
                // The same fetch now goes direct, so the held result above was the hold and not the network.
                assertEquals("loaded", webFetch(activity, "http://example.com/"));
            } finally {
                network.setHoldOnFailure(false);
                server.close();
                responses.shutdownNow();
                await(done -> network.setEnabled(false, done));
            }
        }
    }

    /** Fetches [address] in the test WebView: "held" when the proxy refused it, otherwise the status. */
    private String webFetch(ActivityScenario<ProxyTestActivity> activity, String address) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        activity.onActivity(host -> {
            host.webView.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface public void result(String value) { result.set(value); done.countDown(); }
            }, "Held");
            String script = "fetch('" + address + "',{mode:'no-cors'}).then(r=>Held.result('loaded')).catch(e=>Held.result('held'))";
            host.webView.loadDataWithBaseURL("http://held.test/", "<script>" + script + "</script>", "text/html", "UTF-8", null);
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        return result.get();
    }

    @Test public void nativeAndWebViewRoutingFailureRecoveryAndIsolation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String globalProxy = Settings.Global.getString(context.getContentResolver(), "http_proxy");
        try (ActivityScenario<ProxyTestActivity> activity = ActivityScenario.launch(ProxyTestActivity.class);
             ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
             ServerSocket upstream = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            BrowserProxyNetwork network = BrowserProxyNetwork.get(context);
            assertEquals(ProxyPhase.OFF, await(done -> network.setEnabled(false, done)).phase);
            MmkvManager.INSTANCE.setSelectServer("");
            assertEquals(ProxyPhase.FAILED, await(done -> network.setEnabled(true, done)).phase);
            assertEquals(Proxy.NO_PROXY, network.proxy());
            assertTrue(EmbeddedProxyController.isEnabled());
            ProfileItem profile = ProfileItem.Companion.create(EConfigType.SOCKS);
            profile.setRemarks("Isolated proxy test");
            profile.setServer("127.0.0.1");
            profile.setServerPort(String.valueOf(upstream.getLocalPort()));
            MmkvManager.INSTANCE.encodeServerConfig("", profile);
            assertTrue(EmbeddedProxy.hasProfile());
            assertEquals(ProxyPhase.RUNNING, await(done -> network.setEnabled(true, done)).phase);
            assertEquals(Proxy.Type.SOCKS, network.proxy().type());
            int port = EmbeddedProxy.getPort();
            ExecutorService bridges = Executors.newCachedThreadPool();
            bridges.submit(() -> {
                try {
                    while (!upstream.isClosed()) {
                        Socket client = upstream.accept();
                        bridges.submit(() -> bridgeSocks(client, server.getLocalPort()));
                    }
                } catch (IOException ignored) {}
            });
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
                    try { request(network, "http://" + domain + ":" + server.getLocalPort() + "/probe"); fail("Expected unavailable target"); }
                    catch (IOException expected) {}
                    assertEquals(ProxyPhase.RUNNING, await(network::status).phase);
                    assertNotEquals(Proxy.NO_PROXY, network.proxy());
                }
                LauncherManager.INSTANCE.startService(context, null);
                phase(network, ProxyPhase.RUNNING);
                assertEquals(port, EmbeddedProxy.getPort());
                CountDownLatch reloaded = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean sawReload = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.function.Consumer<ProxyState> reloadObserver = value -> {
                    if (value.phase == ProxyPhase.STARTING) sawReload.set(true);
                    if (sawReload.get() && value.phase == ProxyPhase.RUNNING) reloaded.countDown();
                };
                try (Observation observation = network.observe(reloadObserver)) {
                    LauncherManager.INSTANCE.restartService(context);
                    assertTrue(reloaded.await(20, TimeUnit.SECONDS));
                }
                assertEquals("ok", request(network, address));
                context.stopService(new Intent(context, CoreProxyOnlyService.class));
                phase(network, ProxyPhase.RECONNECTING);
                assertEquals(Proxy.NO_PROXY, network.proxy());
                phase(network, ProxyPhase.RUNNING);
                assertEquals("ok", request(network, address));
                CountDownLatch pidReady = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicInteger daemonPid = new java.util.concurrent.atomic.AtomicInteger();
                context.sendOrderedBroadcast(new Intent(context.getPackageName() + ".action.service")
                    .setPackage(context.getPackageName()).putExtra("key", 1), null,
                    new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context ignored, Intent reply) {
                            daemonPid.set(getResultExtras(false).getInt("daemonPid")); pidReady.countDown();
                        }
                    }, null, android.app.Activity.RESULT_CANCELED, null, null);
                assertTrue(pidReady.await(5, TimeUnit.SECONDS));
                assertTrue(daemonPid.get() > 0 && daemonPid.get() != android.os.Process.myPid());
                android.os.Process.killProcess(daemonPid.get());
                phase(network, ProxyPhase.RECONNECTING);
                assertEquals(Proxy.NO_PROXY, network.proxy());
                phase(network, ProxyPhase.RUNNING);
                assertEquals("ok", request(network, address));
                assertEquals(ProxyPhase.OFF, await(done -> network.setEnabled(false, done)).phase);
                assertEquals(Proxy.NO_PROXY, network.proxy());
                assertEquals(globalProxy, Settings.Global.getString(context.getContentResolver(), "http_proxy"));
            } finally {
                upstream.close();
                server.close();
                bridges.shutdownNow();
                responses.shutdownNow();
                await(done -> network.setEnabled(false, done));
            }
        }
    }
}
