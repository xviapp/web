package app.xvi.web

/** Header rules for continuing an interrupted download. */
internal object DownloadResume {
    /**
     * A strong ETag, or else Last-Modified; empty when the server gives neither. A weak ETag cannot be
     * used with If-Range, and says the server cannot vouch for the bytes, so then nothing is: a date
     * to the second cannot either.
     */
    fun validator(etag: String?, lastModified: String?): String {
        val tag = etag?.trim().orEmpty()
        if (tag.startsWith("W/", ignoreCase = true)) return ""
        return tag.ifEmpty { lastModified?.trim().orEmpty() }
    }

    /** Full size from a `416` answer's `Content-Range: bytes *` + `/total`; null when it gives none. */
    fun unsatisfiedTotal(header: String?): Long? =
        Regex("""^\s*bytes\s+\*/(\d+)\s*$""", RegexOption.IGNORE_CASE).find(header ?: return null)?.groupValues?.get(1)?.toLongOrNull()

    /** First byte of `Content-Range: bytes start-end/total`. */
    fun rangeStart(header: String?): Long? = parse(header)?.first

    /** Full size from `Content-Range`; null when the server writes `*`. */
    fun rangeTotal(header: String?): Long? = parse(header)?.second

    private fun parse(header: String?): Pair<Long, Long?>? {
        val match = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(header ?: return null) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[3].toLongOrNull()
    }
}
