package io.github.bi2qfa.alphasync.ui.components

import androidx.compose.runtime.*
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository

internal val LocalPreviewPageActive = compositionLocalOf { true }

/** Only actual visible cells, excluding lazy-layout precomposition/prefetch. */
@Composable
internal fun ViewportPreviews(entries: List<ObjectRepository.FtpEntry>, indices: () -> List<Int>) {
    val owner = remember { Any() }
    val currentEntries by rememberUpdatedState(entries)
    val currentIndices by rememberUpdatedState(indices)
    val active by rememberUpdatedState(LocalPreviewPageActive.current)
    DisposableEffect(owner) { onDispose { ThumbStore.clearVisible(owner) } }
    LaunchedEffect(owner) {
        snapshotFlow {
            if (!active) null else currentIndices().sorted().mapNotNull { currentEntries.getOrNull(it) }
                .filter { !it.isDir && it.ext in setOf("jpg", "jpeg", "arw") }
                .map { ThumbStore.ObjectCacheKey(it.path, it.size, it.timestamp) }
        }.collect { keys ->
            if (keys == null) ThumbStore.clearVisible(owner) else ThumbStore.focusVisible(owner, keys)
        }
    }
}
