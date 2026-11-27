package io.github.bi2qfa.alphasync.data

/** Display-referred preview brightness, not a RAW clipping or IRE measurement. */
internal object ZebraHighlight {
    val thresholds = listOf(70, 80, 90, 95, 100)
    fun validThreshold(value: Int) = if (value in thresholds) value else 95
    fun usage(value: Int): String = when (value) {
        70 -> "观察较大范围的亮部；适合检查主体与背景的明暗分布"
        80 -> "检查明亮主体或浅色物体；比高光预警覆盖更多区域"
        90 -> "提前关注高亮区域；适合留意白色衣物、云和反光处"
        100 -> "只标记预览亮度达到上限的区域；不能代表 RAW 溢出"
        else -> "关注接近亮度上限的高光；常用作高光检查"
    }
    fun overlay(pixel: Int, x: Int, y: Int, stripeWidth: Int, threshold: Int = 95): Int {
        val r = (pixel ushr 16) and 255
        val g = (pixel ushr 8) and 255
        val b = pixel and 255
        val light = (2126 * r + 7152 * g + 722 * b + 5000) / 10000
        if ((pixel ushr 24) == 0 || light * 100 < validThreshold(threshold) * 255) return 0
        return if ((x + y) / stripeWidth % 2 == 0) 0xaaffffff.toInt() else 0xaa000000.toInt()
    }
}
