package io.github.bi2qfa.alphasync.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.bi2qfa.alphasync.data.TonalBlocks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read row by row to avoid a second full-sized pixel array; preserve preview resolution. */
internal suspend fun tonalBlockPreview(image: ImageBitmap, levels: Int, mode: TonalBlocks.Mode): ImageBitmap {
    val source = image.asAndroidBitmap()
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val row = IntArray(source.width)
    try {
        for (y in 0 until source.height) {
            currentCoroutineContext().ensureActive()
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
            for (x in row.indices) row[x] = TonalBlocks.map(row[x], levels, mode)
            output.setPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        return output.asImageBitmap()
    } catch (t: Throwable) {
        output.recycle()
        throw t
    }
}
