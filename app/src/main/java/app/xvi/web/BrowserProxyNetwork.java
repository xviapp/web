package app.xvi.web;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.v2ray.ang.api.EmbeddedProxyController;
import com.v2ray.ang.api.Observation;
import com.v2ray.ang.api.ProxyState;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Thin browser adapter around the shared embedded proxy controller. */
final class BrowserProxyNetwork {
    private static BrowserProxyNetwork instance;

    static synchronized BrowserProxyNetwork get(Context context) {
        if (instance == null) instance = new BrowserProxyNetwork(context.getApplicationContext());
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BrowserPrefs prefs;
    private final WebViewRouting routing;
    private final EmbeddedProxyController controller;
    /** Observers told again when a VPN starts or stops standing in for the proxy; main thread only. */
    private final List<Consumer<ProxyState>> observers = new ArrayList<>();
    private volatile boolean vpnActive;

    private BrowserProxyNetwork(Context context) {
        prefs = new BrowserPrefs(context);
        routing = new WebViewRouting(this::bypassing);
        controller = EmbeddedProxyController.get(context, routing);
        watchVpn(context);
    }

    void prepare(Consumer<ProxyState> done) { controller.prepare(done); }
    Observation observe(Consumer<ProxyState> observer) {
        Observation core = controller.observe(observer);
        main.post(() -> observers.add(observer));
        return () -> { core.close(); main.post(() -> observers.remove(observer)); };
    }
    void status(Consumer<ProxyState> done) { controller.status(done); }
    void setEnabled(boolean enabled, Consumer<ProxyState> done) { controller.setEnabled(enabled, done); }
    void retry(Consumer<ProxyState> done) { controller.retry(done); }
    void setBackgroundActive(boolean active) { controller.setBackgroundActive(active); }
    /** The route for the browser's own requests; direct while a VPN stands in for the proxy. */
    Proxy proxy() { return bypassing() ? Proxy.NO_PROXY : EmbeddedProxyController.networkProxy(); }

    boolean bypassOnVpn() { return prefs.getProxyBypassOnVpn(); }
    void setBypassOnVpn(boolean enabled) {
        prefs.setProxyBypassOnVpn(enabled);
        main.post(this::bypassChanged);
    }
    /** Call after the setting was changed elsewhere, such as by restoring a backup. */
    void bypassSettingChanged() { main.post(this::bypassChanged); }
    /** Whether a VPN carries the browser's traffic right now, so the proxy is set aside. */
    boolean bypassing() { return vpnActive && prefs.getProxyBypassOnVpn(); }
    void setSubscription(String url, int updateIntervalMinutes, int testIntervalMinutes, Consumer<ProxyState> done) {
        controller.setSubscription(url, updateIntervalMinutes, testIntervalMinutes, done);
    }
    void refreshSubscription(Consumer<ProxyState> done) { controller.refreshSubscription(done); }
    void selectNode(int index, Consumer<ProxyState> done) { controller.selectNode(index, done); }
    void selectAutomatically(Consumer<ProxyState> done) { controller.selectAutomatically(done); }

    /**
     * Follows whether this app's own default network is a VPN, so a VPN that leaves the browser out
     * by split tunnelling counts as off.
     */
    private void watchVpn(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        vpnActive = isVpn(connectivity.getNetworkCapabilities(connectivity.getActiveNetwork()));
        try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    setVpnActive(isVpn(capabilities));
                }

                @Override public void onLost(Network network) { setVpnActive(false); }
            }, main);
        } catch (RuntimeException ignored) {
            // Without the callback the state read above stays in use.
        }
    }

    private static boolean isVpn(NetworkCapabilities capabilities) {
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private void setVpnActive(boolean active) {
        if (vpnActive == active) return;
        vpnActive = active;
        if (prefs.getProxyBypassOnVpn()) bypassChanged();
    }

    /** Re-applies the route and tells observers; main thread only. */
    private void bypassChanged() {
        routing.reapply();
        if (observers.isEmpty()) return;
        controller.status(state -> new ArrayList<>(observers).forEach(observer -> observer.accept(state)));
    }

    /**
     * Applies the core's SOCKS port to WebView, unless {@code bypass} says a VPN stands in for it; the
     * port is kept either way so the proxy comes back as soon as the VPN goes.
     */
    private static final class WebViewRouting implements EmbeddedProxyController.Routing {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final java.util.function.BooleanSupplier bypass;
        /** The port the core asked for, 0 while it asked for none. */
        private int port;

        WebViewRouting(java.util.function.BooleanSupplier bypass) { this.bypass = bypass; }

        @Override public boolean isSupported() {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        }

        @Override public void setProxy(int port, Runnable applied) {
            this.port = port;
            apply(applied);
        }

        @Override public void clearProxy(Runnable cleared) {
            port = 0;
            apply(cleared);
        }

        /** Applies the route again after the VPN or the setting changed. */
        void reapply() { if (port > 0) apply(() -> { }); }

        private void apply(Runnable applied) {
            if (port <= 0 || bypass.getAsBoolean()) clear(applied);
            // The core calls this only after isSupported(); keep the check next to the call anyway.
            else if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyConfig.Builder builder = new ProxyConfig.Builder()
                    .addProxyRule("socks://127.0.0.1:" + port)
                    .removeImplicitRules();
                // Local and private-network addresses go direct; the remote node cannot reach them.
                for (String rule : LocalAddress.INSTANCE.getBypassRules()) builder.addBypassRule(rule);
                ProxyConfig config = builder.build();
                ProxyController.getInstance().setProxyOverride(config, main::post, applied);
            } else main.post(applied);
        }

        private void clear(Runnable cleared) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(main::post, cleared);
            } else main.post(cleared);
        }
    }
}
