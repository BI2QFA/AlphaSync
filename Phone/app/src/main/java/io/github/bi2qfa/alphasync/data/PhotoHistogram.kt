package io.github.bi2qfa.alphasync.data

internal data class PhotoHistogram(val luminance: IntArray, val red: IntArray,
                                   val green: IntArray, val blue: IntArray) {
    companion object {
        fun fromPixels(pixels: IntArray): PhotoHistogram {
            val l = IntArray(256); val r = IntArray(256)
            val g = IntArray(256); val b = IntArray(256)
            for (pixel in pixels) {
                if ((pixel ushr 24) == 0) continue
                val rr = (pixel ushr 16) and 255
                val gg = (pixel ushr 8) and 255
                val bb = pixel and 255
                r[rr]++; g[gg]++; b[bb]++
                l[(2126 * rr + 7152 * gg + 722 * bb + 5000) / 10000]++
            }
            return PhotoHistogram(l, r, g, b)
        }
    }
}
