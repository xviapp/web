package app.xvi.web

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class SavedTab(val url: String, val title: String, val desktop: Boolean = false, val keepAlive: Boolean = false, val time: Long = 0)

/** Typed access to browser preferences; every key and default lives here. */
internal class BrowserPrefs(context: Context) {
    enum class Theme(val key: String, val nightMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);
        companion object { fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: SYSTEM }
    }

    private val browser = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val appearance = context.getSharedPreferences("web-appearance", Context.MODE_PRIVATE)

    var engine: String
        get() = browser.getString(ENGINE, null)?.takeIf(BrowserInput::isKnownEngine) ?: BrowserInput.DEFAULT_ENGINE
        set(value) = browser.edit { putString(ENGINE, value) }
    var textZoom: Int
        get() = browser.getInt(TEXT_ZOOM, 100)
        set(value) = browser.edit { putInt(TEXT_ZOOM, value) }
    var haptics: Boolean
        get() = browser.getBoolean(HAPTICS, false)
        set(value) = browser.edit { putBoolean(HAPTICS, value) }
    /** Darkens pages without their own dark theme while the browser is dark. */
    var darkPages: Boolean
        get() = browser.getBoolean(DARK_PAGES, true)
        set(value) = browser.edit { putBoolean(DARK_PAGES, value) }
    /** Black instead of dark grey behind everything while the browser is dark. */
    var pureBlack: Boolean
        get() = browser.getBoolean(PURE_BLACK, false)
        set(value) = browser.edit { putBoolean(PURE_BLACK, value) }
    /** Accent colors from the wallpaper, on Android 12 and later. */
    var dynamicColor: Boolean
        get() = browser.getBoolean(DYNAMIC_COLOR, false)
        set(value) = browser.edit { putBoolean(DYNAMIC_COLOR, value) }
    /** Ask for a location on every download instead of saving to Downloads. */
    var askDownloadLocation: Boolean
        get() = browser.getBoolean(ASK_DOWNLOAD_LOCATION, false)
        set(value) = browser.edit { putBoolean(ASK_DOWNLOAD_LOCATION, value) }
    /** Sends typed text to the search engine for suggestions; off by default. */
    var searchSuggestions: Boolean
        get() = browser.getBoolean(SEARCH_SUGGESTIONS, false)
        set(value) = browser.edit { putBoolean(SEARCH_SUGGESTIONS, value) }
    /** Search address with `%s` for the custom engine; empty when unset. */
    var customEngine: String
        get() = browser.getString(CUSTOM_ENGINE, "").orEmpty()
        set(value) = browser.edit { putString(CUSTOM_ENGINE, value) }
    /** Lets pages be zoomed even when they ask the browser not to. */
    var forceZoom: Boolean
        get() = browser.getBoolean(FORCE_ZOOM, false)
        set(value) = browser.edit { putBoolean(FORCE_ZOOM, value) }
    /** Blocks ads and trackers with the filter lists; on by default. */
    var adblock: Boolean
        get() = browser.getBoolean(ADBLOCK, true)
        set(value) = browser.edit { putBoolean(ADBLOCK, value) }
    /** Filter lists the user switched off, by id. */
    var adblockOff: Set<String>
        get() = browser.getStringSet(ADBLOCK_OFF, null)?.toSet().orEmpty()
        set(value) = browser.edit { putStringSet(ADBLOCK_OFF, value) }
    /** Optional built-in filter lists the user switched on, by id. */
    var adblockOn: Set<String>
        get() = browser.getStringSet(ADBLOCK_ON, null)?.toSet().orEmpty()
        set(value) = browser.edit { putStringSet(ADBLOCK_ON, value) }
    /** Addresses of filter lists the user added. */
    var adblockSubscriptions: List<String>
        get() = runCatching { JSONArray(browser.getString(ADBLOCK_SUBSCRIPTIONS, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(emptyList()).filter(BrowserInput::isWebUrl)
        set(value) = browser.edit { putString(ADBLOCK_SUBSCRIPTIONS, JSONArray(value).toString()) }
    /** The user's own rules, one per line in the filter list syntax. */
    var adblockCustomRules: String
        get() = browser.getString(ADBLOCK_CUSTOM, "").orEmpty()
        set(value) = browser.edit { putString(ADBLOCK_CUSTOM, value) }
    /** When every list was last refreshed successfully. */
    var adblockChecked: Long
        get() = browser.getLong(ADBLOCK_CHECKED, 0L)
        set(value) = browser.edit { putLong(ADBLOCK_CHECKED, value) }
    /**
     * Parental control: kinds of site blocked, by [SiteBlock.Category] key. Like the rest of parental
     * control it is left out of backups, so restoring one cannot switch it off.
     */
    var siteBlockOn: Set<String>
        get() = browser.getStringSet(SITE_BLOCK_ON, null)?.toSet().orEmpty()
        set(value) = browser.edit { putStringSet(SITE_BLOCK_ON, value) }
    /** Sites the user blocks besides the lists, one per line. */
    var siteBlockCustom: String
        get() = browser.getString(SITE_BLOCK_CUSTOM, "").orEmpty()
        set(value) = browser.edit { putString(SITE_BLOCK_CUSTOM, value) }
    /** Sites never blocked, whatever the lists say, one per line. */
    var siteBlockAllowed: String
        get() = browser.getString(SITE_BLOCK_ALLOWED, "").orEmpty()
        set(value) = browser.edit { putString(SITE_BLOCK_ALLOWED, value) }
    /** Asks search engines for their strict results; off by default. */
    var safeSearch: Boolean
        get() = browser.getBoolean(SAFE_SEARCH, false)
        set(value) = browser.edit { putBoolean(SAFE_SEARCH, value) }
    /** Opening the parental control settings asks for the phone's own unlock first; off by default. */
    var parentalLock: Boolean
        get() = browser.getBoolean(PARENTAL_LOCK, false)
        set(value) = browser.edit { putBoolean(PARENTAL_LOCK, value) }
    /** Runs the installed user scripts; on by default, so installing one is enough. */
    var userScripts: Boolean
        get() = browser.getBoolean(USER_SCRIPTS, true)
        set(value) = browser.edit { putBoolean(USER_SCRIPTS, value) }
    /** Sends traffic through [proxyUrl]; off by default. */
    var proxyEnabled: Boolean
        get() = browser.getBoolean(PROXY_ENABLED, false)
        set(value) = browser.edit { putBoolean(PROXY_ENABLED, value) }
    /** The external proxy, normalized by [ProxyAddress]; empty while unset. */
    var proxyUrl: String
        get() = browser.getString(PROXY_URL, "").orEmpty()
        set(value) = browser.edit { putString(PROXY_URL, value) }
    /** Leaves the proxy aside while a VPN carries this app's traffic; off by default. */
    var proxyBypassOnVpn: Boolean
        get() = browser.getBoolean(PROXY_BYPASS_ON_VPN, false)
        set(value) = browser.edit { putBoolean(PROXY_BYPASS_ON_VPN, value) }
    /** Shows the proxy shortcut on the start page; off by default. */
    var proxyHomeButton: Boolean
        get() = browser.getBoolean(PROXY_HOME_BUTTON, false)
        set(value) = browser.edit { putBoolean(PROXY_HOME_BUTTON, value) }
    /**
     * Covers private tabs once the browser has been left, until the phone's own unlock is given; off
     * by default. Tied to this phone's screen lock, so a backup leaves it out.
     */
    var lockPrivate: Boolean
        get() = browser.getBoolean(LOCK_PRIVATE, false)
        set(value) = browser.edit { putBoolean(LOCK_PRIVATE, value) }
    /** Pauses private tabs' media as they lock; off by default, so music goes on in the background. */
    /** A failed or abandoned unlock closes every private tab at once, without a word. */
    var lockPrivateCloseOnFail: Boolean
        get() = browser.getBoolean(LOCK_PRIVATE_CLOSE_ON_FAIL, false)
        set(value) = browser.edit { putBoolean(LOCK_PRIVATE_CLOSE_ON_FAIL, value) }
    var lockPrivatePausesMedia: Boolean
        get() = browser.getBoolean(LOCK_PRIVATE_PAUSES_MEDIA, false)
        set(value) = browser.edit { putBoolean(LOCK_PRIVATE_PAUSES_MEDIA, value) }
    /** Tries plain HTTP addresses over HTTPS first; off by default. */
    var httpsFirst: Boolean
        get() = browser.getBoolean(HTTPS_FIRST, false)
        set(value) = browser.edit { putBoolean(HTTPS_FIRST, value) }
    /** What quitting the browser removes, from [ExitCleanup.ALL]; empty when nothing is. */
    var clearOnExit: Set<String>
        get() = browser.getStringSet(CLEAR_ON_EXIT, null)?.filterTo(LinkedHashSet()) { it in ExitCleanup.ALL }.orEmpty()
        set(value) = browser.edit { putStringSet(CLEAR_ON_EXIT, value) }
    /**
     * Set while the browser runs with [clearOnExit] chosen, and cleared once a quit has removed it all,
     * so a quit that never got that far is made up for at the next start.
     */
    var exitCleanupPending: Boolean
        get() = browser.getBoolean(EXIT_CLEANUP_PENDING, false)
        set(value) = browser.edit(commit = true) { putBoolean(EXIT_CLEANUP_PENDING, value) }
    /** Colours, typeface and line spacing of the reader view. */
    var readerStyle: ReaderExtractor.Style
        get() = ReaderExtractor.Style(ReaderExtractor.Tone.parse(browser.getString(READER_TONE, null)), browser.getBoolean(READER_SERIF, false),
            ReaderExtractor.Spacing.parse(browser.getString(READER_SPACING, null)))
        set(value) = browser.edit {
            putString(READER_TONE, value.tone.key).putBoolean(READER_SERIF, value.serif).putString(READER_SPACING, value.spacing.key)
        }
    var theme: Theme
        get() = Theme.parse(appearance.getString(THEME, null))
        set(value) = appearance.edit { putString(THEME, value.key) }
    val selectedTab: Int get() = browser.getInt(SELECTED, 0)

    /** The user's choices, for a backup; open tabs and state that belongs to this device are left out. */
    fun exportSettings(): JSONObject = JSONObject().apply {
        // Values in effect, defaults included, so restoring also undoes changes made after the backup.
        put(HAPTICS, haptics); put(DARK_PAGES, darkPages); put(PURE_BLACK, pureBlack); put(DYNAMIC_COLOR, dynamicColor)
        put(ASK_DOWNLOAD_LOCATION, askDownloadLocation); put(SEARCH_SUGGESTIONS, searchSuggestions); put(FORCE_ZOOM, forceZoom)
        put(ADBLOCK, adblock); put(USER_SCRIPTS, userScripts); put(PROXY_ENABLED, proxyEnabled); put(PROXY_URL, proxyUrl); put(PROXY_BYPASS_ON_VPN, proxyBypassOnVpn)
        put(PROXY_HOME_BUTTON, proxyHomeButton)
        put(HTTPS_FIRST, httpsFirst); put(CLEAR_ON_EXIT, JSONArray(clearOnExit.toList()))
        put(TEXT_ZOOM, textZoom)
        readerStyle.let { put(READER_TONE, it.tone.key); put(READER_SERIF, it.serif); put(READER_SPACING, it.spacing.key) }
        put(ENGINE, engine); put(CUSTOM_ENGINE, customEngine)
        put(ADBLOCK_SUBSCRIPTIONS, JSONArray(adblockSubscriptions).toString()); put(ADBLOCK_CUSTOM, adblockCustomRules)
        put(ADBLOCK_OFF, JSONArray(adblockOff.toList())); put(ADBLOCK_ON, JSONArray(adblockOn.toList()))
        put(THEME, theme.key)
    }

    /** Takes over the settings in [json]; unknown keys and values of the wrong kind are ignored. */
    fun importSettings(json: JSONObject) {
        browser.edit {
            BACKUP_FLAGS.forEach { key -> (json.opt(key) as? Boolean)?.let { putBoolean(key, it) } }
            (json.opt(TEXT_ZOOM) as? Int)?.takeIf { it in 50..300 }?.let { putInt(TEXT_ZOOM, it) }
            BACKUP_TEXTS.forEach { key -> (json.opt(key) as? String)?.takeIf { it.length <= MAX_TEXT }?.let { putString(key, it) } }
            BACKUP_SETS.forEach { key ->
                (json.opt(key) as? JSONArray)?.let { array -> putStringSet(key, (0 until array.length()).mapNotNull { array.opt(it) as? String }.take(100).toSet()) }
            }
        }
        (json.opt(THEME) as? String)?.let { theme = Theme.parse(it) }
    }

    fun saveTabs(tabs: List<SavedTab>, selected: Int) {
        val array = JSONArray()
        tabs.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("desktop", it.desktop).put("keepAlive", it.keepAlive)) }
        browser.edit { putString(TABS, array.toString()).putInt(SELECTED, selected) }
    }

    /** Recently closed ordinary tabs, most recent first; only the address and title are kept. */
    fun saveClosed(tabs: List<SavedTab>) {
        val array = JSONArray()
        tabs.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("desktop", it.desktop).put("time", it.time)) }
        browser.edit { putString(CLOSED, array.toString()) }
    }

    fun restoreClosed(): List<SavedTab> = runCatching {
        val array = JSONArray(browser.getString(CLOSED, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }
            .filter { BrowserInput.isWebUrl(it.optString("url")) }
            .map { SavedTab(it.optString("url"), it.optString("title"), it.optBoolean("desktop"), time = it.optLong("time")) }
    }.getOrDefault(emptyList())

    fun restoreTabs(): List<SavedTab> = runCatching {
        val array = JSONArray(browser.getString(TABS, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            val url = it.optString("url").takeIf(BrowserInput::isWebUrl).orEmpty()
            SavedTab(url, it.optString("title"), it.optBoolean("desktop"), it.optBoolean("keepAlive"))
        }.take(TabManager.MAX_TABS)
    }.getOrDefault(emptyList())

    private companion object {
        const val ENGINE = "engine"
        const val TEXT_ZOOM = "textZoom"
        const val HAPTICS = "haptics"
        const val DARK_PAGES = "darkPages"
        const val PURE_BLACK = "pureBlack"
        const val DYNAMIC_COLOR = "dynamicColor"
        const val ASK_DOWNLOAD_LOCATION = "askDownloadLocation"
        const val SEARCH_SUGGESTIONS = "searchSuggestions"
        const val CUSTOM_ENGINE = "customEngine"
        const val FORCE_ZOOM = "forceZoom"
        const val THEME = "theme"
        const val ADBLOCK = "adblock"
        const val ADBLOCK_OFF = "adblockOff"
        const val ADBLOCK_ON = "adblockOn"
        const val ADBLOCK_SUBSCRIPTIONS = "adblockSubscriptions"
        const val ADBLOCK_CUSTOM = "adblockCustom"
        const val ADBLOCK_CHECKED = "adblockChecked"
        const val USER_SCRIPTS = "userScripts"
        const val SITE_BLOCK_ON = "siteBlockOn"
        const val SITE_BLOCK_CUSTOM = "siteBlockCustom"
        const val SITE_BLOCK_ALLOWED = "siteBlockAllowed"
        const val SAFE_SEARCH = "safeSearch"
        const val PARENTAL_LOCK = "parentalLock"
        const val PROXY_ENABLED = "proxyEnabled"
        const val PROXY_URL = "proxyUrl"
        const val PROXY_BYPASS_ON_VPN = "proxyBypassOnVpn"
        const val PROXY_HOME_BUTTON = "proxyHomeButton"
        const val LOCK_PRIVATE = "lockPrivate"
        const val LOCK_PRIVATE_PAUSES_MEDIA = "lockPrivatePausesMedia"
        const val LOCK_PRIVATE_CLOSE_ON_FAIL = "lockPrivateCloseOnFail"
        const val HTTPS_FIRST = "httpsFirst"
        const val CLEAR_ON_EXIT = "clearOnExit"
        const val EXIT_CLEANUP_PENDING = "exitCleanupPending"
        const val READER_TONE = "readerTone"
        const val READER_SERIF = "readerSerif"
        const val READER_SPACING = "readerSpacing"
        val BACKUP_FLAGS = listOf(HAPTICS, DARK_PAGES, PURE_BLACK, DYNAMIC_COLOR, ASK_DOWNLOAD_LOCATION, SEARCH_SUGGESTIONS, FORCE_ZOOM, ADBLOCK, USER_SCRIPTS, PROXY_ENABLED, PROXY_BYPASS_ON_VPN,
            PROXY_HOME_BUTTON, HTTPS_FIRST, READER_SERIF)
        val BACKUP_TEXTS = listOf(ENGINE, CUSTOM_ENGINE, PROXY_URL, ADBLOCK_SUBSCRIPTIONS, ADBLOCK_CUSTOM, READER_TONE, READER_SPACING)
        val BACKUP_SETS = listOf(ADBLOCK_OFF, ADBLOCK_ON, CLEAR_ON_EXIT)
        const val MAX_TEXT = 200_000
        const val TABS = "tabs"
        const val CLOSED = "closed"
        const val SELECTED = "selected"
    }
}
