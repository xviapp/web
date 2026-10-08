package app.xvi.web;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.v2ray.ang.AppConfig;
import com.v2ray.ang.core.LauncherManager;
import com.v2ray.ang.embedded.EmbeddedProxy;
import com.v2ray.ang.service.CoreProxyOnlyService;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Owns host network selection. Core liveness is acknowledged by the daemon, never inferred from a socket or node. */
final class BrowserProxyNetwork {
    private static final long[] RECONNECT_DELAYS_MS = {1000L, 2000L, 4000L, 8000L, 16000L, 30000L};
    private static BrowserProxyNetwork instance;
    static synchronized BrowserProxyNetwork get(Context context) {
        if (instance == null) instance = new BrowserProxyNetwork(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Consumer<ProxyState>> observers = new ArrayList<>();
    private final List<Consumer<ProxyState>> waiting = new ArrayList<>();
    private String phase = "off";
    private String message = "";
    private volatile int activePort;
    private int generation;
    private boolean initialized;
    private boolean suspended;
    private boolean applying;
    private long deadline;
    // Poll while an activity is started or the browser's background keep-alive is active.
    private int visible;
    private boolean backgroundActive;
    void setBackgroundActive(boolean active) {
        main.post(() -> {
            backgroundActive = active;
            main.removeCallbacks(beat);
            if (active || visible > 0) main.post(beat);
        });
    }
    private final Runnable beat = this::heartbeat;
    private final Runnable reconnect = this::runReconnect;
    private final Runnable reconnectCountdown = this::updateReconnectCountdown;
    private int reconnectAttempts;
    private boolean reconnectScheduled;
    private long reconnectDeadline;

    private BrowserProxyNetwork(Context context) {
        this.context = context;
        ContextCompat.registerReceiver(context, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                int key = intent.getIntExtra("key", 0);
                if (!EmbeddedProxy.isEnabled()) {
                    suspended = false;
                    selectDirect("off", "");
                } else if (key == AppConfig.MSG_STATE_START_FAILURE) {
                    scheduleReconnect();
                } else if (key == AppConfig.MSG_STATE_RUNNING || key == AppConfig.MSG_STATE_START_SUCCESS) {
                    if (!suspended) selectProxy(intent.getIntExtra("embeddedPort", 0));
                } else if (key == EmbeddedProxy.STATE_RELOADING) {
                    beginWaiting();
                } else if (key == AppConfig.MSG_STATE_STOP_SUCCESS && !suspended
                        && (phase.equals("running") || phase.equals("reconnecting"))) {
                    scheduleReconnect();
                }
            }
        }, new IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED);
        ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                // Back on screen: check at once, then keep polling.
                if (visible++ == 0) { main.removeCallbacks(beat); main.post(beat); }
            }
            @Override public void onActivityStopped(Activity activity) {
                if (--visible == 0 && !backgroundActive) main.removeCallbacks(beat);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
        main.postDelayed(beat, 3000);
    }

    void prepare(Consumer<ProxyState> done) {
        main.post(() -> {
            if (initialized && !applying && !phase.equals("starting")) { done.accept(snapshot()); return; }
            waiting.add(done);
            if (!initialized) {
                initialized = true;
                if (EmbeddedProxy.isEnabled()) start();
                else selectDirect("off", "");
            }
        });
    }
    void observe(Consumer<ProxyState> observer) { main.post(() -> observers.add(observer)); }
    void unobserve(Consumer<ProxyState> observer) { main.post(() -> observers.remove(observer)); }
    void status(Consumer<ProxyState> done) { main.post(() -> done.accept(snapshot())); }
    void setEnabled(boolean enabled, Consumer<ProxyState> done) {
        main.post(() -> {
            initialized = true;
            waiting.add(done);
            EmbeddedProxy.setEnabled(enabled);
            if (enabled) {
                cancelReconnect();
                reconnectAttempts = 0;
                suspended = false;
                start();
            }
            else {
                cancelReconnect();
                reconnectAttempts = 0;
                suspended = false;
                LauncherManager.INSTANCE.stopService(context);
                selectDirect("off", "");
            }
        });
    }
    /** Reload the running core after the selected profile changes. */
    void reload(Consumer<ProxyState> done) {
        main.post(() -> {
            initialized = true;
            waiting.add(done);
            cancelReconnect();
            reconnectAttempts = 0;
            suspended = false;
            if (!EmbeddedProxy.isEnabled()) {
                selectDirect("off", "");
                return;
            }
            beginWaiting();
            query((running, port) -> {
                if (suspended || !EmbeddedProxy.isEnabled()) return;
                if (running) {
                    LauncherManager.INSTANCE.restartService(context);
                } else if (!EmbeddedProxy.hasProfile()) {
                    fail(context.getString(R.string.proxy_start_failed));
                } else if (!LauncherManager.startEmbedded(context)) {
                    scheduleReconnect();
                }
            });
        });
    }
    private void beginWaiting() {
        cancelReconnect();
        suspended = false;
        deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        phase = "starting";
        message = "";
        publish();
    }
    private void start() {
        beginWaiting();
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            fail(context.getString(R.string.proxy_webview_unsupported));
            return;
        }
        // Query an existing daemon first; two Activities must share one core and one port.
        query((running, port) -> {
            if (suspended || !EmbeddedProxy.isEnabled()) return;
            if (running) selectProxy(port);
            else if (!EmbeddedProxy.hasProfile()) fail(context.getString(R.string.proxy_start_failed));
            else if (!LauncherManager.startEmbedded(context)) scheduleReconnect();
        });
    }
    private interface DaemonResult { void accept(boolean running, int port); }
    private void query(DaemonResult result) {
        Intent intent = new Intent(AppConfig.BROADCAST_ACTION_SERVICE).setPackage(context.getPackageName());
        intent.putExtra("key", AppConfig.MSG_REGISTER_CLIENT);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent reply) {
                Bundle extras = getResultExtras(false);
                result.accept(getResultCode() == Activity.RESULT_OK, extras == null ? 0 : extras.getInt("embeddedPort", 0));
            }
        }, main, Activity.RESULT_CANCELED, null, null);
    }
    private void heartbeat() {
        if (initialized && EmbeddedProxy.isEnabled() && !suspended) {
            if (phase.equals("starting") && android.os.SystemClock.elapsedRealtime() >= deadline)
                scheduleReconnect();
            else query((running, port) -> {
                if (suspended || !EmbeddedProxy.isEnabled()) return;
                if (running) selectProxy(port);
                else if (phase.equals("running")) {
                    scheduleReconnect();
                }
            });
        }
        main.removeCallbacks(beat);
        if (visible > 0 || backgroundActive) main.postDelayed(beat, 3000);
    }
    private void selectProxy(int port) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            fail(context.getString(R.string.proxy_webview_unsupported));
            return;
        }
        if (port < 1024 || port > 65535) { fail(context.getString(R.string.proxy_start_failed)); return; }
        if (applying || (phase.equals("running") && activePort == port)) return;
        int token = ++generation;
        applying = true;
        ProxyConfig config = new ProxyConfig.Builder().addProxyRule("http://127.0.0.1:" + port)
            .removeImplicitRules().build();
        try {
            ProxyController.getInstance().setProxyOverride(config, main::post, () -> {
                if (token != generation) return;
                activePort = port;
                EmbeddedProxy.setRoutingPort(port);
                applying = false;
                phase = "running";
                message = "";
                reconnectAttempts = 0;
                cancelReconnect();
                publish();
                complete();
            });
        } catch (RuntimeException error) { fail(context.getString(R.string.proxy_start_failed)); }
    }
    private void runReconnect() {
        reconnectScheduled = false;
        main.removeCallbacks(reconnectCountdown);
        reconnectDeadline = 0L;
        if (!EmbeddedProxy.isEnabled() || suspended) return;
        start();
    }
    private void scheduleReconnect() {
        if (!EmbeddedProxy.isEnabled()) {
            cancelReconnect();
            suspended = false;
            selectDirect("off", "");
            return;
        }
        if (!EmbeddedProxy.hasProfile()) {
            fail(context.getString(R.string.proxy_start_failed));
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            fail(context.getString(R.string.proxy_webview_unsupported));
            return;
        }
        if (reconnectScheduled) return;
        if (reconnectAttempts >= RECONNECT_DELAYS_MS.length) {
            fail(context.getString(R.string.proxy_reconnect_failed));
            return;
        }
        reconnectAttempts++;
        long delay = RECONNECT_DELAYS_MS[reconnectAttempts - 1];
        reconnectScheduled = true;
        suspended = false;
        phase = "reconnecting";
        message = "";
        int token = ++generation;
        activePort = 0;
        EmbeddedProxy.setRoutingPort(0);
        applying = true;
        Runnable cleared = () -> {
            if (token != generation) return;
            applying = false;
            reconnectDeadline = android.os.SystemClock.elapsedRealtime() + delay;
            updateReconnectCountdown();
            publish();
            complete();
            if (reconnectScheduled) main.postDelayed(reconnect, delay);
        };
        try {
            ProxyController.getInstance().clearProxyOverride(main::post, cleared);
        } catch (RuntimeException error) {
            cleared.run();
        }
    }
    private void cancelReconnect() {
        reconnectScheduled = false;
        main.removeCallbacks(reconnect);
        main.removeCallbacks(reconnectCountdown);
        reconnectDeadline = 0L;
    }
    private void updateReconnectCountdown() {
        if (!reconnectScheduled || reconnectDeadline == 0L) return;
        long remaining = reconnectDeadline - android.os.SystemClock.elapsedRealtime();
        if (remaining <= 0L) return;
        long seconds = Math.max(1L, (remaining + 999L) / 1000L);
        message = context.getString(R.string.proxy_reconnecting, seconds);
        publish();
        if (reconnectScheduled) main.postDelayed(reconnectCountdown, Math.min(1000L, remaining));
    }
    private void fail(String reason) {
        cancelReconnect();
        reconnectAttempts = 0;
        suspended = true;
        // Stop a timed-out/failed core so late acknowledgements cannot restore routing without a retry.
        context.stopService(new Intent(context, CoreProxyOnlyService.class));
        selectDirect("failed", reason);
    }
    private void selectDirect(String nextPhase, String reason) {
        int token = ++generation;
        activePort = 0;
        EmbeddedProxy.setRoutingPort(0);
        applying = true;
        Runnable applied = () -> {
            if (token != generation) return;
            applying = false;
            phase = nextPhase;
            message = reason;
            publish();
            complete();
        };
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
            ProxyController.getInstance().clearProxyOverride(main::post, applied);
        else applied.run();
    }
    Proxy proxy() {
        int port = activePort;
        return port == 0 ? Proxy.NO_PROXY : new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
    }
    private ProxyState snapshot() {
        return new ProxyState().put("enabled", EmbeddedProxy.isEnabled()).put("phase", phase)
            .put("message", message).put("hasProfile", EmbeddedProxy.hasProfile());
    }
    private void publish() { for (Consumer<ProxyState> observer : new ArrayList<>(observers)) observer.accept(snapshot()); }
    private void complete() {
        List<Consumer<ProxyState>> pending = new ArrayList<>(waiting);
        waiting.clear();
        for (Consumer<ProxyState> done : pending) done.accept(snapshot());
    }
}
