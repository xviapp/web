package app.xvi.web

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Keeps a WebView's UA string and client hints in the same mode, including after switching back. */
internal class PageUserAgent(private val settings: WebSettings) {
    private val original = settings.userAgentString
    private val desktop = UserAgents.desktop(original)
    private val metadata = if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA))
        WebSettingsCompat.getUserAgentMetadata(settings) else null
    private val desktopMetadata by lazy {
        metadata?.let {
            UserAgentMetadata.Builder(it)
                .setPlatform("Linux")
                .setPlatformVersion("")
                .setArchitecture("x86")
                .setBitness(64)
                .setModel("")
                .setMobile(false)
                .setWow64(false)
                .apply {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS))
                        setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))
                }
                .build()
        }
    }
    private var desktopApplied = false

    fun apply(useDesktop: Boolean) {
        if (desktopApplied == useDesktop) return
        val hints = if (useDesktop) desktopMetadata else metadata
        if (hints != null) WebSettingsCompat.setUserAgentMetadata(settings, hints)
        // Changing the UA may restart a load; its first request must already have the matching hints.
        settings.userAgentString = if (useDesktop) desktop else original
        desktopApplied = useDesktop
    }
}
