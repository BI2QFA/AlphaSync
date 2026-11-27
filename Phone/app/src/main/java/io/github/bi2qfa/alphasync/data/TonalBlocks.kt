package io.github.bi2qfa.alphasync.data

import kotlin.math.pow
import kotlin.math.roundToInt

internal object TonalBlocks {
    enum class Mode(val label: String) {
        FIXED_BRIGHTNESS("固定亮度"),
        CIE_LIGHTNESS("CIE L*"),
    }

    val supportedLevels = listOf(3, 4, 5, 6, 8)
    private val linear = DoubleArray(256) { value ->
        val encoded = value / 255.0
        if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
    }
    private fun luminanceAt(lightness: Double): Double =
        if (lightness <= 8.0) lightness * (3.0 / 29.0).pow(3) else ((lightness + 16) / 116).pow(3)

    private val thresholds = supportedLevels.associateWith { levels ->
        DoubleArray(levels - 1) { index -> luminanceAt(100.0 * (index + 1) / levels) }
    }
    private val grays = supportedLevels.associateWith { levels ->
        IntArray(levels) { band ->
            val y = luminanceAt(100.0 * band / (levels - 1))
            val encoded = if (y <= 0.0031308) y * 12.92 else 1.055 * y.pow(1.0 / 2.4) - 0.055
            (encoded * 255).roundToInt()
        }
    }

    fun validLevels(value: Int): Int = if (value in supportedLevels) value else 4
    fun map(pixel: Int, levels: Int = 4, mode: Mode = Mode.FIXED_BRIGHTNESS): Int {
        require(levels in supportedLevels)
        val r = (pixel ushr 16) and 255
        val g = (pixel ushr 8) and 255
        val b = pixel and 255
        val gray = if (mode == Mode.CIE_LIGHTNESS) {
            val y = 0.2126 * linear[r] + 0.7152 * linear[g] + 0.0722 * linear[b]
            val limits = thresholds.getValue(levels)
            var band = 0
            while (band < limits.size && y >= limits[band]) band++
            grays.getValue(levels)[band]
        } else {
            val brightness = (2126 * r + 7152 * g + 722 * b + 5000) / 10000
            val band = brightness * levels / 256
            (band * 255 + (levels - 1) / 2) / (levels - 1)
        }
        return (pixel and 0xff000000.toInt()) or (gray shl 16) or (gray shl 8) or gray
    }
}
