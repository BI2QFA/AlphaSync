package io.github.bi2qfa.alphasync.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.ui.components.FullscreenPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local image analysis. Reuse album metadata instead of querying every image again. */
@Composable
fun PhoneAlbumViewer(uris: List<String>, knownEntries: Map<String, ObjectRepository.FtpEntry> = emptyMap(), onDismiss: () -> Unit) {
    val context = LocalContext.current
    var entries by remember(uris) { mutableStateOf<List<ObjectRepository.FtpEntry>?>(null) }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(uris) {
        entries = withContext(Dispatchers.IO) {
            uris.mapIndexed { index, value ->
                knownEntries[value]?.let { return@mapIndexed it }
                val uri = Uri.parse(value)
                var name = "照片 ${index + 1}"
                var size = 0L
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        }
                    }
                }
                ObjectRepository.FtpEntry(name, value, false, size, 0L)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        val gallery = entries
        if (gallery == null) CircularProgressIndicator()
        else FullscreenPreview(
            gallery = gallery,
            startIndex = 0,
            isSelected = { false },
            onToggleSelect = {},
            onDismiss = onDismiss,
            showSelectAction = false,
            localUriOf = { Uri.parse(it.path) },
        )
    }
}
