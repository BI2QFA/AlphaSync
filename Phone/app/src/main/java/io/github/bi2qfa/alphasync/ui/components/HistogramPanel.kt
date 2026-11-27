package io.github.bi2qfa.alphasync.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.github.bi2qfa.alphasync.data.PhotoHistogram
import io.github.bi2qfa.alphasync.data.PreviewThumbnailPolicy

internal object HistogramCache {
    private val cache = object : LinkedHashMap<String, PhotoHistogram>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhotoHistogram>?) = size > 64
    }
    fun calculate(key: String, image: ImageBitmap): PhotoHistogram {
        synchronized(cache) { cache[key]?.let { return it } }
        val original = image.asAndroidBitmap()
        val (w, h) = PreviewThumbnailPolicy.dimensions(original.width, original.height, 512)
        val sampled = Bitmap.createScaledBitmap(original, w, h, true)
        val pixels = IntArray(w * h)
        try { sampled.getPixels(pixels, 0, w, 0, 0, w, h) }
        finally { if (sampled !== original) sampled.recycle() }
        val result = PhotoHistogram.fromPixels(pixels)
        synchronized(cache) { cache[key] = result }
        return result
    }
}

@Composable
internal fun HistogramPanel(histogram: PhotoHistogram?, status: String, rgb: Boolean,
                            onRgb: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopEnd) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = !rgb, onClick = { onRgb(false) }, label = { Text("亮度", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = rgb, onClick = { onRgb(true) }, label = { Text("RGB", style = MaterialTheme.typography.labelSmall) })
            }
            if (histogram == null) Text(status, style = MaterialTheme.typography.bodySmall)
            else Canvas(Modifier.fillMaxWidth().height(52.dp)) {
                val channels = if (rgb) listOf(histogram.red to Color(0xFFFF7777),
                    histogram.green to Color(0xFF77DD88), histogram.blue to Color(0xFF88AAFF))
                    else listOf(histogram.luminance to Color(0xFFE2DFFF))
                val peak = channels.maxOf { it.first.maxOrNull() ?: 0 }.coerceAtLeast(1)
                drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height))
                for ((bins, color) in channels) {
                    val path = Path()
                    bins.forEachIndexed { i, count ->
                        val x = i * size.width / 255f
                        val y = size.height - count.toFloat() / peak * (size.height - 2f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("暗 0", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                Text("基于预览图", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                Text("255 亮", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
}
