package io.github.bi2qfa.alphasync.transfer

/** Stable FIFO within each class. Running items are never selected a second time. */
internal object DownloadOrder {
    fun next(items: List<TransferItem>): TransferItem? =
        items.firstOrNull { it.state == TransferState.QUEUED && it.priorityDownload }
            ?: items.firstOrNull { it.state == TransferState.QUEUED }

    fun shouldPreempt(current: TransferItem, items: List<TransferItem>): Boolean =
        !current.priorityDownload && items.any { it.id != current.id && it.state == TransferState.QUEUED && it.priorityDownload }
}
