package app.xvi.web

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject

data class SavedTab(val url: String, val title: String, val desktop: Boolean = false, val keepAlive: Boolean = false)

/** Typed access to browser preferences; every key and default lives here. */
internal class BrowserPrefs(context: Context) {
    enum class Theme(val key: String, val nightMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);
        companion object { fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: SYSTEM }
    }

    private val browser = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    // The embedded proxy screens read this file to follow the browser's appearance.
    private val appearance = context.getSharedPreferences("web-appearance", Context.MODE_PRIVATE)

    var engine: String
        get() = browser.getString(ENGINE, null)?.takeIf { it in BrowserInput.engines } ?: BrowserInput.DEFAULT_ENGINE
        set(value) = browser.edit().putString(ENGINE, value).apply()
    var textZoom: Int
        get() = browser.getInt(TEXT_ZOOM, 100)
        set(value) = browser.edit().putInt(TEXT_ZOOM, value).apply()
    var haptics: Boolean
        get() = browser.getBoolean(HAPTICS, false)
        set(value) = browser.edit().putBoolean(HAPTICS, value).apply()
    var theme: Theme
        get() = Theme.parse(appearance.getString(THEME, null))
        set(value) = appearance.edit().putString(THEME, value.key).apply()
    val selectedTab: Int get() = browser.getInt(SELECTED, 0)

    fun saveTabs(tabs: List<SavedTab>, selected: Int) {
        val array = JSONArray()
        tabs.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("desktop", it.desktop).put("keepAlive", it.keepAlive)) }
        browser.edit().putString(TABS, array.toString()).putInt(SELECTED, selected).apply()
    }

    fun restoreTabs(): List<SavedTab> = runCatching {
        val array = JSONArray(browser.getString(TABS, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            SavedTab(it.optString("url").takeIf { url -> BrowserInput.isWebUrl(url) } ?: "", it.optString("title"), it.optBoolean("desktop"), it.optBoolean("keepAlive"))
        }.take(TabManager.MAX_TABS)
    }.getOrDefault(emptyList())

    private companion object {
        const val ENGINE = "engine"
        const val TEXT_ZOOM = "textZoom"
        const val HAPTICS = "haptics"
        const val THEME = "theme"
        const val TABS = "tabs"
        const val SELECTED = "selected"
    }
}
