package app.xvi.web;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Routes WebView and the browser's own requests through a user-configured external proxy. The proxy
 * is checked when traffic needs it (answers are reused for a short while); while it does not answer,
 * traffic goes direct and the user is told once. A VPN can stand in for the proxy when allowed.
 */
public final class BrowserProxyNetwork {
    public enum Route { OFF, VPN, CHECKING, PROXY, DIRECT }

    /** A read-only snapshot for the UI. */
    public static final class Status {
        public final boolean enabled;
        /** Normalized address, empty while unset. */
        public final String url;
        public final boolean bypassOnVpn;
        public final Route route;
        /** Unix milliseconds of the last check, 0 before any. */
        public final long checkedAt;
        /** Why the last check failed: refused, timeout, dns, auth or protocol; empty when it answered. */
        public final String error;

        Status(boolean enabled, String url, boolean bypassOnVpn, Route route, long checkedAt, String error) {
            this.enabled = enabled;
            this.url = url;
            this.bypassOnVpn = bypassOnVpn;
            this.route = route;
            this.checkedAt = checkedAt;
            this.error = error;
        }
    }

    public interface Observation { void close(); }

    private static BrowserProxyNetwork instance;

    static synchronized BrowserProxyNetwork get(Context context) {
        if (instance == null) instance = new BrowserProxyNetwork(context.getApplicationContext());
        return instance;
    }

    /** For HTTP clients that cannot reach the network object: a failed proxy connection checks again. */
    static void reportFailureIfCreated() {
        BrowserProxyNetwork network;
        synchronized (BrowserProxyNetwork.class) { network = instance; }
        if (network != null) network.main.post(() -> network.reportFailure(direct -> { }));
    }

    private static final long HEALTHY_FOR_MS = 60_000;
    private static final long FAILED_FOR_MS = 15_000;
    private static final String LEGACY_CLEARED = "proxyLegacyCoreCleared";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService prober = Executors.newSingleThreadExecutor();
    private final BrowserPrefs prefs;
    private final Set<Consumer<Status>> observers = new CopyOnWriteArraySet<>();

    // Main thread only, except where volatile.
    private boolean enabled;
    private boolean bypassOnVpn;
    private volatile ProxyAddress address;
    private volatile boolean vpnActive;
    private volatile Route route = Route.OFF;
    /** The last probe's answer: null before any, then whether the proxy answered. */
    private Boolean healthy;
    private String error = "";
    private long checkedAt;
    /** Bumped on every setting change so a probe of the old address is not believed. */
    private int generation;
    private boolean probing;
    private final List<Runnable> waiters = new ArrayList<>();
    /** The rule WebView was last given, "" for direct (as a new process starts). */
    private String appliedRule = "";
    private Network defaultNetwork;

    private BrowserProxyNetwork(Context context) {
        this.context = context;
        prefs = new BrowserPrefs(context);
        readSettings();
        watchNetwork();
        clearLegacyCore();
    }

    void prepare(Consumer<Status> done) { check(false, done); }

    void status(Consumer<Status> done) { main.post(() -> done.accept(status())); }

    Observation observe(Consumer<Status> observer) {
        observers.add(observer);
        main.post(() -> { if (observers.contains(observer)) observer.accept(status()); });
        return () -> observers.remove(observer);
    }

    /** Checks the proxy if the last answer is old, or always when {@code force}, then routes. */
    void check(boolean force, Consumer<Status> done) {
        main.post(() -> ensure(force, () -> done.accept(status())));
    }

    void setEnabled(boolean value, Consumer<Status> done) {
        main.post(() -> {
            prefs.setProxyEnabled(value);
            changed(done);
        });
    }

    /** Saves the address, or clears it when blank; throws {@link ProxyAddress.Invalid} otherwise. */
    void setUrl(String input, Consumer<Status> done) {
        ProxyAddress next = input == null || input.trim().isEmpty() ? null : ProxyAddress.parse(input);
        main.post(() -> {
            prefs.setProxyUrl(next == null ? "" : next.url());
            changed(done);
        });
    }

    /**
     * Probes an address without saving it or touching the route; throws {@link ProxyAddress.Invalid}.
     * Answers on the main thread as {@link ProxyProbe#probe} does.
     */
    void test(String input, Consumer<String> done) {
        ProxyAddress target = ProxyAddress.parse(input);
        prober.execute(() -> {
            String result = ProxyProbe.probe(target);
            main.post(() -> done.accept(result));
        });
    }

    void setBypassOnVpn(boolean value, Consumer<Status> done) {
        main.post(() -> {
            prefs.setProxyBypassOnVpn(value);
            changed(done);
        });
    }

    /** Call after the proxy settings were changed elsewhere, such as by restoring a backup. */
    void routingSettingsChanged() { main.post(() -> changed(state -> { })); }

    /** Whether traffic goes through the proxy right now. */
    boolean carrying() { return route == Route.PROXY; }

    /**
     * The route for the browser's own requests, without checking; callers that are about to send
     * call {@link #prepare} first.
     */
    Proxy proxy() {
        ProxyAddress target = address;
        return route == Route.PROXY && target != null ? target.proxy() : Proxy.NO_PROXY;
    }

    /**
     * After a request through the proxy failed: checks again at once and tells whether traffic now
     * goes direct, so the caller may retry. Main thread.
     */
    void reportFailure(Consumer<Boolean> direct) {
        if (route != Route.PROXY) { direct.accept(false); return; }
        ensure(true, () -> direct.accept(route == Route.DIRECT));
    }

    private void readSettings() {
        enabled = prefs.getProxyEnabled();
        bypassOnVpn = prefs.getProxyBypassOnVpn();
        try { address = ProxyAddress.parse(prefs.getProxyUrl()); }
        catch (ProxyAddress.Invalid ignored) { address = null; }
    }

    private void changed(Consumer<Status> done) {
        readSettings();
        generation++;
        healthy = null;
        error = "";
        checkedAt = 0;
        ensure(true, () -> done.accept(status()));
    }

    private Route desired() {
        if (!enabled || address == null) return Route.OFF;
        if (bypassOnVpn && vpnActive) return Route.VPN;
        if (healthy == null) return Route.CHECKING;
        return healthy ? Route.PROXY : Route.DIRECT;
    }

    /** Main thread. Probes when the proxy is in use and its answer is stale, then settles. */
    private void ensure(boolean force, Runnable done) {
        Route base = desired();
        boolean inUse = base != Route.OFF && base != Route.VPN;
        long age = System.currentTimeMillis() - checkedAt;
        boolean stale = healthy == null || age > (healthy ? HEALTHY_FOR_MS : FAILED_FOR_MS);
        if (!inUse || !(force || stale)) {
            settle(done);
            return;
        }
        if (done != null) waiters.add(done);
        if (probing) return;
        probing = true;
        int started = generation;
        ProxyAddress target = address;
        if (healthy == null) publish();
        prober.execute(() -> {
            String result = ProxyProbe.probe(target);
            main.post(() -> probed(started, result));
        });
    }

    private void probed(int started, String result) {
        probing = false;
        if (started != generation) {
            // The settings changed meanwhile; check what is configured now.
            ensure(true, null);
            return;
        }
        healthy = result.isEmpty();
        error = result;
        checkedAt = System.currentTimeMillis();
        settle(null);
    }

    /** Main thread. Applies the route, then answers everyone waiting; waits for a running probe. */
    private void settle(Runnable done) {
        if (done != null) waiters.add(done);
        if (probing) return;
        Route before = route;
        Route next = desired();
        route = next;
        announce(before, next);
        ProxyAddress target = address;
        String rule = next == Route.PROXY && target != null ? target.url() : "";
        Runnable finish = () -> {
            List<Runnable> ready = new ArrayList<>(waiters);
            waiters.clear();
            for (Runnable waiter : ready) waiter.run();
            publish();
        };
        if (rule.equals(appliedRule) || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            finish.run();
            return;
        }
        appliedRule = rule;
        if (rule.isEmpty()) {
            ProxyController.getInstance().clearProxyOverride(main::post, finish);
        } else {
            // Loopback stays direct by the implicit rules; the local network does too.
            ProxyConfig.Builder builder = new ProxyConfig.Builder().addProxyRule(rule);
            for (String bypass : LocalAddress.INSTANCE.getBypassRules()) builder.addBypassRule(bypass);
            ProxyController.getInstance().setProxyOverride(builder.build(), main::post, finish);
        }
    }

    /** Tells the user once when traffic falls back to direct and once when the proxy is back. */
    private void announce(Route before, Route next) {
        String message = null;
        ProxyAddress target = address;
        if (next == Route.DIRECT && before != Route.DIRECT && target != null) {
            message = context.getString(R.string.proxy_fell_back, target.url().replaceFirst("^[a-z0-9]+://", ""));
        } else if (next == Route.PROXY && before == Route.DIRECT) {
            message = context.getString(R.string.proxy_recovered);
        }
        if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private void publish() {
        if (observers.isEmpty()) return;
        Status state = status();
        for (Consumer<Status> observer : observers) observer.accept(state);
    }

    private Status status() {
        ProxyAddress target = address;
        return new Status(enabled, target == null ? "" : target.url(), bypassOnVpn, desired(), checkedAt, error);
    }

    /**
     * Follows this app's default network: a new network makes the last answer stale, and a VPN
     * (one that leaves the browser out by split tunnelling does not count) may stand in for the proxy.
     */
    private void watchNetwork() {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        Network active = connectivity.getActiveNetwork();
        defaultNetwork = active;
        vpnActive = isVpn(connectivity.getNetworkCapabilities(active));
        try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (network.equals(defaultNetwork)) return;
                    defaultNetwork = network;
                    networkChanged();
                }

                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    boolean vpn = isVpn(capabilities);
                    if (vpn == vpnActive) return;
                    vpnActive = vpn;
                    networkChanged();
                }

                @Override public void onLost(Network network) {
                    defaultNetwork = null;
                    vpnActive = false;
                    networkChanged();
                }
            }, main);
        } catch (RuntimeException ignored) {
            // Without the callback the state read above stays in use; answers still expire.
        }
    }

    private static boolean isVpn(NetworkCapabilities capabilities) {
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private void networkChanged() {
        checkedAt = 0;
        ensure(true, null);
    }

    /** Removes what the embedded proxy core left behind before it was dropped; runs once. */
    private void clearLegacyCore() {
        android.content.SharedPreferences legacy = context.getSharedPreferences("browser_proxy_cleanup", Context.MODE_PRIVATE);
        if (legacy.getBoolean(LEGACY_CLEARED, false)) return;
        new Thread(() -> {
            delete(new File(context.getFilesDir(), "mmkv"));
            for (String name : new String[] { "androidx.work.workdb", "androidx.work.workdb-shm", "androidx.work.workdb-wal" })
                context.deleteDatabase(name);
            legacy.edit().putBoolean(LEGACY_CLEARED, true).apply();
        }, "browser-proxy-cleanup").start();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
