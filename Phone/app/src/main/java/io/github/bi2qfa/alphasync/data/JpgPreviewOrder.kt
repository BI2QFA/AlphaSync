package io.github.bi2qfa.alphasync.data

/** Preserve photo order, but put JPG before ARW of the same photo in the same directory. */
internal object JpgPreviewOrder {
    private fun extension(path: String) = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
    private fun group(path: String): String = when (extension(path)) {
        "jpg", "jpeg", "arw" -> path.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
        else -> path
    }
    fun <T> order(items: List<T>, path: (T) -> String): List<T> = items
        .groupBy { group(path(it)) }.values.flatMap { photo ->
            photo.sortedBy { if (extension(path(it)) in listOf("jpg", "jpeg")) 0 else 1 }
        }

    /** No wait for a missing JPG. Called only before dispatch, never cancels an active preview. */
    fun <T> preferred(item: T, queue: Collection<T>, path: (T) -> String): T? {
        val raw = path(item)
        if (extension(raw) != "arw") return null
        val stem = group(raw)
        return queue.firstOrNull {
            val candidate = path(it)
            extension(candidate) in listOf("jpg", "jpeg") && group(candidate) == stem
        }
    }
}
