package app.xvi.web

import org.json.JSONArray
import org.json.JSONObject

/**
 * One person's browser: site data, tabs, bookmarks, history and site settings of its own. The main
 * profile is the one every install starts with; it uses WebView's default profile and the original
 * files, so it needs no migration and cannot be removed.
 */
internal data class BrowserProfile(val id: String, val name: String = "") {
    val isMain get() = id == MAIN
    /** The WebView profile holding its site data; null for WebView's default one. */
    val webProfile: String? get() = if (isMain) null else WEB_PREFIX + id

    companion object {
        const val MAIN = "main"
        const val MAX_NAME = 30
        private const val WEB_PREFIX = "xvi-profile-"
    }
}

/**
 * Which profiles exist, which one the browser shows ([active]) and which one links from other apps open
 * in ([preferred]). [removing] names profiles deleted while their data may still be in use; it goes at
 * the next process start. Plain Kotlin and org.json, so it is covered by JVM tests.
 */
internal data class ProfileRegistry(
    val profiles: List<BrowserProfile> = listOf(BrowserProfile(BrowserProfile.MAIN)),
    val active: String = BrowserProfile.MAIN,
    val preferred: String = BrowserProfile.MAIN,
    val removing: Set<String> = emptySet()
) {
    fun find(id: String) = profiles.firstOrNull { it.id == id }

    /** A blank name is refused: every profile but the main one needs one to be told apart. */
    fun create(id: String, name: String): ProfileRegistry? {
        val clean = name.trim().take(BrowserProfile.MAX_NAME)
        if (clean.isEmpty() || !validId(id) || find(id) != null) return null
        return copy(profiles = profiles + BrowserProfile(id, clean))
    }

    fun rename(id: String, name: String): ProfileRegistry? {
        val clean = name.trim().take(BrowserProfile.MAX_NAME)
        if (clean.isEmpty() || find(id) == null) return null
        return copy(profiles = profiles.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun withActive(id: String) = if (find(id) == null) this else copy(active = id)
    fun withPreferred(id: String) = if (find(id) == null) this else copy(preferred = id)

    /** The main and the shown profile stay; a removed preferred one hands over to the main profile. */
    fun remove(id: String): ProfileRegistry? {
        if (id == BrowserProfile.MAIN || id == active || find(id) == null) return null
        return copy(profiles = profiles.filter { it.id != id }, removing = removing + id,
            preferred = if (preferred == id) BrowserProfile.MAIN else preferred)
    }

    fun encode(): String = JSONObject()
        .put("active", active)
        .put("preferred", preferred)
        .put("removing", JSONArray(removing.toList()))
        .put("profiles", JSONArray().apply { profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .toString()

    companion object {
        /** Ids name files, so nothing else is accepted, whatever the saved text says. */
        private val ID = Regex("[0-9a-zA-Z-]{1,64}")
        private fun validId(id: String) = ID.matches(id)

        /** Always holds the main profile; damaged or unknown entries are left out, and fall back to it. */
        fun decode(text: String?): ProfileRegistry {
            val json = runCatching { JSONObject(text ?: return ProfileRegistry()) }.getOrNull() ?: return ProfileRegistry()
            val saved = json.optJSONArray("profiles")
            val read = (0 until (saved?.length() ?: 0)).mapNotNull { saved?.optJSONObject(it) }
                .map { BrowserProfile(it.optString("id"), it.optString("name").take(BrowserProfile.MAX_NAME)) }
                .filter { validId(it.id) && (it.isMain || it.name.isNotBlank()) }
                .distinctBy { it.id }
            val profiles = if (read.any { it.isMain }) read else listOf(BrowserProfile(BrowserProfile.MAIN)) + read
            fun known(key: String) = json.optString(key).takeIf { id -> profiles.any { it.id == id } } ?: BrowserProfile.MAIN
            val gone = json.optJSONArray("removing")
            val removing = (0 until (gone?.length() ?: 0)).mapNotNull { gone?.optString(it) }
                .filterTo(LinkedHashSet()) { id -> validId(id) && id != BrowserProfile.MAIN && profiles.none { it.id == id } }
            return ProfileRegistry(profiles, known("active"), known("preferred"), removing)
        }
    }
}
