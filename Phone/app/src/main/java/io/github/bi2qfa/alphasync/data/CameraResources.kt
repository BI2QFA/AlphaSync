package io.github.bi2qfa.alphasync.data

import java.util.Locale

/** Only camera telemetry; never phone resource usage. Negative values mean unavailable. */
data class CameraResources(
    val systemCpu10: Int, val appCpu10: Int,
    val memoryTotal: Long, val memoryUsed: Long, val memoryEstimated: Boolean,
    val appRss: Long, val heapUsed: Long, val heapMax: Long,
) {
    fun cpuText(value: Int): String = if (value in 0..1000)
        String.format(Locale.ROOT, "%.1f%%", value / 10.0) else "不可用"
    fun bytesText(value: Long): String = if (value >= 0)
        String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0) else "不可用"
    fun memoryText(): String = if (memoryTotal > 0 && memoryUsed in 0..memoryTotal)
        "${bytesText(memoryUsed)} / ${bytesText(memoryTotal)} (${String.format(Locale.ROOT, "%.1f%%", memoryUsed * 100.0 / memoryTotal)})"
    else "不可用"
}
