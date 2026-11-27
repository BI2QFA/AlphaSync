package io.github.bi2qfa.alphasync.transfer

internal object AutoSyncPolicy {
    fun accepts(path: String, jpg: Boolean, raw: Boolean): Boolean =
        when (path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
            "jpg", "jpeg" -> jpg
            "arw" -> raw
            else -> false
        }
    fun discoveryInterval(enabled: Boolean): Long = if (enabled) 2000L else 4000L
    fun tickInterval(enabled: Boolean, pending: Boolean): Long =
        if (pending) 1000L else discoveryInterval(enabled)
}
