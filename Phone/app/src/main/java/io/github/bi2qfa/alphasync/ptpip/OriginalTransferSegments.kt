package io.github.bi2qfa.alphasync.ptpip

/** Bound each original-file turn so a waiting live preview can use the data channel. */
internal object OriginalTransferSegments {
    private const val MAX_BYTES = 2 * 1024 * 1024
    fun length(size: Long, offset: Long): Int {
        require(size >= 0 && offset in 0..size)
        return minOf(size - offset, MAX_BYTES.toLong()).toInt()
    }
}
