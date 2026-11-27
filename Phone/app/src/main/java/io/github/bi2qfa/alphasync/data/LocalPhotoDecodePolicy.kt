package io.github.bi2qfa.alphasync.data

import kotlin.math.floor
import kotlin.math.sqrt

/** Bound the display bitmap without unnecessarily halving modern-device resolution. */
internal object LocalPhotoDecodePolicy {
    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val edgeScale = 4096.0 / maxOf(width, height)
        val pixelScale = sqrt(12_000_000.0 / (width.toDouble() * height))
        val scale = minOf(1.0, edgeScale, pixelScale)
        return floor(width * scale).toInt().coerceAtLeast(1) to floor(height * scale).toInt().coerceAtLeast(1)
    }
}
