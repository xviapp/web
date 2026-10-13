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
    /** Keeps the background session alive even without a proxy or playing media. */
    var keepBackground: Boolean
        get() = browser.getBoolean(KEEP_BACKGROUND, false)
        set(value) = browser.edit { putBoolean(KEEP_BACKGROUND, value) }
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
    /** Leaves the proxy aside while a VPN carries this app's traffic; off by default. */
    var proxyBypassOnVpn: Boolean
        get() = browser.getBoolean(PROXY_BYPASS_ON_VPN, false)
        set(value) = browser.edit { putBoolean(PROXY_BYPASS_ON_VPN, value) }
    /** Holds pages while the proxy is on but down, instead of letting them go direct; off by default. */
    var proxyHoldOnFailure: Boolean
        get() = browser.getBoolean(PROXY_HOLD_ON_FAILURE, false)
        set(value) = browser.edit { putBoolean(PROXY_HOLD_ON_FAILURE, value) }
    /**
     * Covers private tabs once the browser has been left, until the phone's own unlock is given; off
     * by default. Tied to this phone's screen lock, so a backup leaves it out.
     */
    var lockPrivate: Boolean
        get() = browser.getBoolean(LOCK_PRIVATE, false)
        set(value) = browser.edit { putBoolean(LOCK_PRIVATE, value) }
    var theme: Theme
        get() = Theme.parse(appearance.getString(THEME, null))
        set(value) = appearance.edit { putString(THEME, value.key) }
    val selectedTab: Int get() = browser.getInt(SELECTED, 0)

    /** The user's choices, for a backup; open tabs and state that belongs to this device are left out. */
    fun exportSettings(): JSONObject = JSONObject().apply {
        // Values in effect, defaults included, so restoring also undoes changes made after the backup.
        put(HAPTICS, haptics); put(DARK_PAGES, darkPages); put(KEEP_BACKGROUND, keepBackground)
        put(ASK_DOWNLOAD_LOCATION, askDownloadLocation); put(SEARCH_SUGGESTIONS, searchSuggestions); put(FORCE_ZOOM, forceZoom)
        put(ADBLOCK, adblock); put(PROXY_BYPASS_ON_VPN, proxyBypassOnVpn); put(PROXY_HOLD_ON_FAILURE, proxyHoldOnFailure)
        put(TEXT_ZOOM, textZoom)
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
        const val KEEP_BACKGROUND = "keepBackground"
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
        const val PROXY_BYPASS_ON_VPN = "proxyBypassOnVpn"
        const val PROXY_HOLD_ON_FAILURE = "proxyHoldOnFailure"
        const val LOCK_PRIVATE = "lockPrivate"
        val BACKUP_FLAGS = listOf(HAPTICS, DARK_PAGES, KEEP_BACKGROUND, ASK_DOWNLOAD_LOCATION, SEARCH_SUGGESTIONS, FORCE_ZOOM, ADBLOCK, PROXY_BYPASS_ON_VPN, PROXY_HOLD_ON_FAILURE)
        val BACKUP_TEXTS = listOf(ENGINE, CUSTOM_ENGINE, ADBLOCK_SUBSCRIPTIONS, ADBLOCK_CUSTOM)
        val BACKUP_SETS = listOf(ADBLOCK_OFF, ADBLOCK_ON)
        const val MAX_TEXT = 200_000
        const val TABS = "tabs"
        const val CLOSED = "closed"
        const val SELECTED = "selected"
    }
}
