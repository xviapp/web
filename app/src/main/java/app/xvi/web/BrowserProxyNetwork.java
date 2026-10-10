package app.xvi.web;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.v2ray.ang.api.EmbeddedProxyController;
import com.v2ray.ang.api.Observation;
import com.v2ray.ang.api.ProxyState;

import java.net.Proxy;
import java.util.function.Consumer;

/** Thin browser adapter around the shared embedded proxy controller. */
final class BrowserProxyNetwork {
    private static BrowserProxyNetwork instance;

    static synchronized BrowserProxyNetwork get(Context context) {
        if (instance == null) instance = new BrowserProxyNetwork(context.getApplicationContext());
        return instance;
    }

    private final EmbeddedProxyController controller;

    private BrowserProxyNetwork(Context context) {
        controller = EmbeddedProxyController.get(
            context,
            new WebViewRouting()
        );
    }

    void prepare(Consumer<ProxyState> done) { controller.prepare(done); }
    Observation observe(Consumer<ProxyState> observer) { return controller.observe(observer); }
    void status(Consumer<ProxyState> done) { controller.status(done); }
    void setEnabled(boolean enabled, Consumer<ProxyState> done) { controller.setEnabled(enabled, done); }
    void retry(Consumer<ProxyState> done) { controller.retry(done); }
    void setBackgroundActive(boolean active) { controller.setBackgroundActive(active); }
    Proxy proxy() { return EmbeddedProxyController.networkProxy(); }
    void setSubscription(String url, int updateIntervalMinutes, int testIntervalMinutes, Consumer<ProxyState> done) {
        controller.setSubscription(url, updateIntervalMinutes, testIntervalMinutes, done);
    }
    void refreshSubscription(Consumer<ProxyState> done) { controller.refreshSubscription(done); }
    void selectNode(int index, Consumer<ProxyState> done) { controller.selectNode(index, done); }
    void selectAutomatically(Consumer<ProxyState> done) { controller.selectAutomatically(done); }

    private static final class WebViewRouting implements EmbeddedProxyController.Routing {
        private final Handler main = new Handler(Looper.getMainLooper());

        @Override public boolean isSupported() {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        }

        @Override public void setProxy(int port, Runnable applied) {
            // The core calls this only after isSupported(); keep the check next to the call anyway.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("socks://127.0.0.1:" + port)
                    .removeImplicitRules()
                    .build();
                ProxyController.getInstance().setProxyOverride(config, main::post, applied);
            } else main.post(applied);
        }

        @Override public void clearProxy(Runnable cleared) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(main::post, cleared);
            } else main.post(cleared);
        }
    }
}
