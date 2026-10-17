package app.xvi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.Test;

public class ProxyAddressTest {
    @Test public void normalizesSupportedForms() {
        assertEquals("socks5://127.0.0.1:7890", ProxyAddress.parse(" 127.0.0.1:7890 ").url());
        assertEquals("socks5://localhost:1080", ProxyAddress.parse("SOCKS5H://LocalHost:1080/").url());
        assertEquals("socks5://h:1", ProxyAddress.parse("socks://h:1").url());
        assertEquals("http://10.0.0.2:8080", ProxyAddress.parse("http://10.0.0.2:8080").url());
        assertEquals("socks5://[::1]:10808", ProxyAddress.parse("[::1]:10808").url());
        ProxyAddress http = ProxyAddress.parse("http://proxy.lan:3128");
        assertFalse(http.socks);
        assertEquals(Proxy.Type.HTTP, http.proxy().type());
        assertEquals(Proxy.Type.SOCKS, ProxyAddress.parse("h:1").proxy().type());
    }

    @Test public void refusesWithReasons() {
        refused("", "INVALID");
        refused("https://h:1", "SCHEME");
        refused("ftp://h:1", "SCHEME");
        refused("socks5://user:pass@h:1", "AUTH");
        refused("socks5://h", "PORT");
        refused("socks5://h:70000", "INVALID");
        refused("http://h:1/path", "INVALID");
        refused("http://h:1?x=1", "INVALID");
    }

    private static void refused(String input, String code) {
        try {
            ProxyAddress.parse(input);
            fail("Accepted " + input);
        } catch (ProxyAddress.Invalid error) {
            assertTrue(input + " -> " + error.getMessage(),
                error.getMessage().equals(code) || (code.equals("INVALID") && error.getMessage().equals("PORT")));
        }
    }

    @Test public void probesSocksAndHttp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread thread = answer(server, new byte[] { 5, 0 });
            assertEquals("", ProxyProbe.probe(ProxyAddress.parse("127.0.0.1:" + server.getLocalPort())));
            thread.join();
            thread = answer(server, new byte[] { 5, (byte) 0xFF });
            assertEquals("auth", ProxyProbe.probe(ProxyAddress.parse("127.0.0.1:" + server.getLocalPort())));
            thread.join();
            thread = answer(server, "HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
            assertEquals("protocol", ProxyProbe.probe(ProxyAddress.parse("127.0.0.1:" + server.getLocalPort())));
            thread.join();
            thread = answer(server, new byte[0]);
            assertEquals("", ProxyProbe.probe(ProxyAddress.parse("http://127.0.0.1:" + server.getLocalPort())));
            thread.join();
        }
        int closed;
        try (ServerSocket server = new ServerSocket(0)) { closed = server.getLocalPort(); }
        assertEquals("refused", ProxyProbe.probe(ProxyAddress.parse("127.0.0.1:" + closed)));
    }

    @Test public void timesOutOnSilentSocks() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept()) { Thread.sleep(ProxyProbe.READ_TIMEOUT_MS + 500); }
                catch (Exception ignored) { }
            });
            thread.start();
            assertEquals("timeout", ProxyProbe.probe(ProxyAddress.parse("127.0.0.1:" + server.getLocalPort())));
            thread.join();
        }
    }

    private static Thread answer(ServerSocket server, byte[] reply) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                if (reply.length > 0) {
                    input.readNBytes(3);
                    output.write(reply);
                    output.flush();
                }
            } catch (Exception ignored) { }
        });
        thread.start();
        return thread;
    }
}
