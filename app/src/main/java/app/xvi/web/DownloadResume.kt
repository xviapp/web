package app.xvi.web

/** Header rules for continuing an interrupted download. */
internal object DownloadResume {
    /**
     * A strong ETag, or else Last-Modified; empty when the server gives neither. Weak ETags cannot be
     * used with If-Range, so they do not count.
     */
    fun validator(etag: String?, lastModified: String?): String =
        etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") } ?: lastModified?.trim().orEmpty()

    /** First byte of `Content-Range: bytes start-end/total`. */
    fun rangeStart(header: String?): Long? = parse(header)?.first

    /** Full size from `Content-Range`; null when the server writes `*`. */
    fun rangeTotal(header: String?): Long? = parse(header)?.second

    private fun parse(header: String?): Pair<Long, Long?>? {
        val match = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(header ?: return null) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[3].toLongOrNull()
    }
}
