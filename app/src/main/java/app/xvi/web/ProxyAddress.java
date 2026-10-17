package app.xvi.web;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** A user-entered external proxy: SOCKS5 or HTTP, host and port, without credentials. */
final class ProxyAddress {
    /** Why an address was refused; the UI localizes the code. */
    static final class Invalid extends IllegalArgumentException {
        Invalid(String code) { super(code); }
    }

    final boolean socks;
    final String host;
    final int port;

    private ProxyAddress(boolean socks, String host, int port) {
        this.socks = socks;
        this.host = host;
        this.port = port;
    }

    /** Parses {@code socks5://host:port}, {@code http://host:port} or a bare {@code host:port} (SOCKS5). */
    static ProxyAddress parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) throw new Invalid("INVALID");
        if (!text.contains("://")) text = "socks5://" + text;
        URI uri;
        try { uri = new URI(text); } catch (URISyntaxException error) { throw new Invalid("INVALID"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean socks;
        switch (scheme) {
            case "socks5": case "socks5h": case "socks": socks = true; break;
            case "http": socks = false; break;
            default: throw new Invalid("SCHEME");
        }
        if (uri.getRawUserInfo() != null) throw new Invalid("AUTH");
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (host == null || host.isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
            || (path != null && !path.isEmpty() && !path.equals("/"))) throw new Invalid("INVALID");
        if (uri.getPort() < 1 || uri.getPort() > 65535) throw new Invalid("PORT");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return new ProxyAddress(socks, host.toLowerCase(Locale.ROOT), uri.getPort());
    }

    /** The normalized form shown to the user, stored, and used as the WebView proxy rule. */
    String url() {
        String name = host.contains(":") ? "[" + host + "]" : host;
        return (socks ? "socks5://" : "http://") + name + ":" + port;
    }

    /** For native clients; the proxy's own name is resolved when connecting, not here. */
    Proxy proxy() {
        return new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }
}
