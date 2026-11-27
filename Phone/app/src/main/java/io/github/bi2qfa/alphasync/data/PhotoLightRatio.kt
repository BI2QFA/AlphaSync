package io.github.bi2qfa.alphasync.data

import kotlin.math.log2
import kotlin.math.pow

internal object PhotoLightRatio {
    private val linear = DoubleArray(256) { value ->
        val srgb = value / 255.0
        if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }

    fun meanLuminance(pixels: IntArray): Double? {
        var sum = 0.0
        var count = 0
        for (pixel in pixels) {
            val alpha = pixel ushr 24
            if (alpha == 0) continue
            val red = linear[pixel ushr 16 and 255]
            val green = linear[pixel ushr 8 and 255]
            val blue = linear[pixel and 255]
            sum += (0.2126 * red + 0.7152 * green + 0.0722 * blue) * alpha
            count += alpha
        }
        return if (count == 0) null else sum / count
    }

    data class Comparison(val brighter: Char?, val ratio: Double, val stops: Double)

    fun compare(a: Double, b: Double): Comparison? {
        val darker = minOf(a, b)
        if (darker == 0.0) return null
        val ratio = maxOf(a, b) / darker
        val brighter = when {
            a > b -> 'A'
            b > a -> 'B'
            else -> null
        }
        return Comparison(brighter, ratio, log2(ratio))
    }
}
