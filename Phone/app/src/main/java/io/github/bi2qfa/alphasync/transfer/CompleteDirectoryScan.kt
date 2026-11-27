package io.github.bi2qfa.alphasync.transfer

/** Results may only be published once every page has been read successfully. */
internal class CompleteDirectoryScan<T> {
    private val entries = ArrayList<T>()
    var offset = 0
        private set
    var complete = false
        private set
    private var invalid = false

    fun accept(page: List<T>, hasMore: Boolean, nextOffset: Int): Boolean {
        if (invalid || complete || (hasMore && (page.isEmpty() || nextOffset <= offset))) {
            invalid = true
            return false
        }
        entries.addAll(page)
        if (hasMore) offset = nextOffset else complete = true
        return true
    }

    fun result(): List<T>? = if (complete && !invalid) entries.toList() else null
}
