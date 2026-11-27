package io.github.bi2qfa.alphasync.data

internal object PreviewChannelPolicy {
    fun shouldYield(foreground: Boolean, urgentQueued: Boolean,
                    budgetPaused: Boolean, retryCoolingDown: Boolean): Boolean =
        foreground || (urgentQueued && !budgetPaused && !retryCoolingDown)
}
