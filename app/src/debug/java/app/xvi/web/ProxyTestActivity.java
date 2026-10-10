package app.xvi.web;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

/** Isolated instrumentation host; no notebook API or credentials are loaded. */
public class ProxyTestActivity extends Activity {
    WebView webView;
    @SuppressLint("SetJavaScriptEnabled") // Debug-only host for local test pages.
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        setContentView(webView);
        webView.loadUrl("about:blank");
    }
    @Override public void onDestroy() { webView.destroy(); super.onDestroy(); }
}
