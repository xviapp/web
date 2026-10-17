package app.xvi.web

/** Desktop mode follows the device WebView's Chrome version instead of a frozen string. */
internal object UserAgents {
    private val chrome = Regex("""Chrome/(\d+)""")
    private const val FALLBACK_MAJOR = "130"

    /** Reduced desktop UA, matching what desktop Chrome sends: only the major version is real. */
    fun desktop(defaultUserAgent: String?): String {
        val major = defaultUserAgent?.let { chrome.find(it)?.groupValues?.get(1) } ?: FALLBACK_MAJOR
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }
}
