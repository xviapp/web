package app.xvi.web

/** State broadcasts and completed saves must not erase a newer URL edit. */
internal class ProxyUrlDraft {
    var value = ""
        private set
    private var persisted = ""
    private var saving = false
    val dirty get() = value.trim() != persisted

    fun edit(value: String) { this.value = value }
    fun beginSave() { saving = true }
    fun failed() { saving = false }

    fun sync(url: String) {
        val wasDirty = dirty
        persisted = url
        if (!wasDirty && !saving) value = url
    }

    fun saved(submitted: String, url: String) {
        saving = false
        if (value.trim() == submitted) value = url
        persisted = url
    }
}
