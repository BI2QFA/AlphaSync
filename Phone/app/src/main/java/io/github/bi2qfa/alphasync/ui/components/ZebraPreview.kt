package io.github.bi2qfa.alphasync.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.bi2qfa.alphasync.data.ZebraHighlight
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun zebraPreview(image: ImageBitmap, threshold: Int): ImageBitmap {
    val source = image.asAndroidBitmap()
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val row = IntArray(source.width)
    val stripeWidth = (maxOf(source.width, source.height) / 160).coerceAtLeast(2)
    try {
        for (y in 0 until source.height) {
            currentCoroutineContext().ensureActive()
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
            for (x in row.indices) row[x] = ZebraHighlight.overlay(row[x], x, y, stripeWidth, threshold)
            result.setPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        return result.asImageBitmap()
    } catch (t: Throwable) {
        result.recycle()
        throw t
    }
}
