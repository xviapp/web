package app.xvi.web

/** Names files may be saved under on shared storage. */
internal object FileNames {
    /** Most file systems take 255 bytes; some room is left for the " (1)" a provider adds to a taken name. */
    private const val MAX_BYTES = 240
    private val ILLEGAL = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")

    /**
     * [name] without characters FAT and MediaStore refuse, and short enough in UTF-8 bytes; a long name
     * loses the end of its base, never its extension.
     */
    fun clean(name: String): String {
        val safe = name.replace(ILLEGAL, "_").trim().trim('.').ifBlank { return "download" }
        if (safe.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return safe
        val dot = safe.lastIndexOf('.')
        val extension = if (dot > 0 && safe.length - dot <= 16) safe.substring(dot) else ""
        val base = safe.substring(0, safe.length - extension.length)
        val room = MAX_BYTES - extension.toByteArray(Charsets.UTF_8).size
        val kept = StringBuilder()
        var used = 0
        var i = 0
        while (i < base.length) {
            val size = if (Character.isHighSurrogate(base[i]) && i + 1 < base.length) 2 else 1
            val piece = base.substring(i, i + size)
            val bytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + bytes > room) break
            kept.append(piece)
            used += bytes
            i += size
        }
        return kept.toString().trimEnd().ifBlank { "download" } + extension
    }
}
