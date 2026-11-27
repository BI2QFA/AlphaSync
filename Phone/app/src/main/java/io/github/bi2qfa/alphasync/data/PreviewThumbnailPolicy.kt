package io.github.bi2qfa.alphasync.data

/** Fit the decoded preview into a thumbnail without upscaling or cropping. */
internal object PreviewThumbnailPolicy {
    fun dimensions(width: Int, height: Int, maxEdge: Int = 640): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxEdge > 0)
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
