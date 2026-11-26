package io.github.bi2qfa.alphasync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.github.bi2qfa.alphasync.data.PhotoLightRatio
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LightRatioState {
    var a by mutableStateOf<Offset?>(null)
        private set
    var b by mutableStateOf<Offset?>(null)
        private set
    var reading by mutableStateOf<String?>(null)

    fun select(point: Offset) {
        if (a == null || b != null) {
            a = point
            b = null
        } else {
            b = point
        }
        reading = null
    }

    fun reset() {
        a = null
        b = null
        reading = null
    }
}

@Composable
internal fun LightRatioToggle(enabled: Boolean, onToggle: () -> Unit) {
    OutlinedButton(
        onClick = onToggle,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
    ) {
        Text(if (enabled) "光比：开" else "光比", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun LightRatioReadout(
    state: LightRatioState,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.reading ?: when {
                state.a == null -> "点选区域 A（蓝框）"
                state.b == null -> "点选区域 B（黄框）"
                else -> "正在比较…"
            },
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
        )
        if (state.a != null) {
            TextButton(onClick = state::reset) { Text("重选", color = contentColor) }
        }
    }
}

@Composable
internal fun LightRatioLayer(image: ImageBitmap, state: LightRatioState) {
    val a = state.a
    val b = state.b
    LaunchedEffect(image) { state.reset() }
    LaunchedEffect(image, a, b) {
        if (a == null || b == null) return@LaunchedEffect
        state.reading = withContext(Dispatchers.Default) {
            try {
                val first = sample(image, a)
                val second = sample(image, b)
                if (first == null || second == null) return@withContext "取样区域无可用像素"
                val comparison = PhotoLightRatio.compare(first, second)
                    ?: return@withContext "取样区域为纯黑，无法计算光比"
                val brighter = comparison.brighter?.let { "$it 较亮" } ?: "A、B 等亮"
                String.format(Locale.ROOT, "%s · %.2f∶1 · %.2f 档（基于预览）",
                    brighter, comparison.ratio, comparison.stops)
            } catch (_: IllegalArgumentException) {
                "无法读取取样区域"
            } catch (_: IllegalStateException) {
                "无法读取取样区域"
            }
        }
    }

    Canvas(Modifier.fillMaxSize().pointerInput(image, state) {
        detectTapGestures { tap ->
            val fit = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
            if (fit <= 0f) return@detectTapGestures
            val left = (size.width - image.width * fit) / 2f
            val top = (size.height - image.height * fit) / 2f
            val x = (tap.x - left) / fit
            val y = (tap.y - top) / fit
            if (x in 0f..<image.width.toFloat() && y in 0f..<image.height.toFloat()) {
                state.select(Offset(x, y))
            }
        }
    }) {
        val fit = minOf(size.width / image.width, size.height / image.height)
        val origin = Offset((size.width - image.width * fit) / 2f, (size.height - image.height * fit) / 2f)
        for ((point, color) in listOf(a to Color(0xFF65DBFF), b to Color(0xFFFFD26B))) {
            if (point == null) continue
            val rect = bounds(image, point)
            val topLeft = origin + Offset(rect.left * fit, rect.top * fit)
            val size = Size(rect.width * fit, rect.height * fit)
            drawRect(Color.Black, topLeft, size, style = Stroke(3.dp.toPx()))
            drawRect(color, topLeft, size, style = Stroke(1.5.dp.toPx()))
        }
    }
}

// Sample a square with side length about 4% of the short image edge, bounded for large photos.
private fun bounds(image: ImageBitmap, point: Offset): IntRect {
    val radius = (minOf(image.width, image.height) * 0.02f).roundToInt().coerceIn(1, 48)
    val x = point.x.toInt()
    val y = point.y.toInt()
    return IntRect(
        (x - radius).coerceAtLeast(0),
        (y - radius).coerceAtLeast(0),
        (x + radius + 1).coerceAtMost(image.width),
        (y + radius + 1).coerceAtMost(image.height),
    )
}

private fun sample(image: ImageBitmap, point: Offset): Double? {
    val rect = bounds(image, point)
    val pixels = IntArray(rect.width * rect.height)
    image.asAndroidBitmap().getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
    return PhotoLightRatio.meanLuminance(pixels)
}
