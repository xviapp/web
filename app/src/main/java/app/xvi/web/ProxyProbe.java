package app.xvi.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Checks that the proxy itself answers; whether its upstream reaches the internet is not tested. */
final class ProxyProbe {
    static final int CONNECT_TIMEOUT_MS = 1500;
    static final int READ_TIMEOUT_MS = 1000;

    private ProxyProbe() { }

    /**
     * Returns an empty string when the proxy answers, otherwise {@code refused}, {@code timeout},
     * {@code dns}, {@code auth} (a SOCKS5 proxy that requires credentials) or {@code protocol}.
     * Blocks for up to the connect and read timeouts; never call on the main thread.
     */
    static String probe(ProxyAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.host, address.port), CONNECT_TIMEOUT_MS);
            if (!address.socks) return "";
            socket.setSoTimeout(READ_TIMEOUT_MS);
            // SOCKS5 greeting offering only "no authentication".
            socket.getOutputStream().write(new byte[] { 5, 1, 0 });
            InputStream input = socket.getInputStream();
            int version = input.read(), method = input.read();
            if (version != 5) return "protocol";
            if (method == 0) return "";
            return method == 0xFF ? "auth" : "protocol";
        } catch (SocketTimeoutException error) {
            return "timeout";
        } catch (UnknownHostException error) {
            return "dns";
        } catch (IOException | RuntimeException error) {
            return "refused";
        }
    }
}
