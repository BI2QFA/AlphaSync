package io.github.bi2qfa.alphasync.transfer

internal data class AutoSyncProgress(val waiting: Int, val running: Int, val done: Int, val failed: Int) {
    companion object {
        fun from(states: List<TransferState?>) = AutoSyncProgress(
            waiting = states.count { it == null || it == TransferState.QUEUED },
            running = states.count { it == TransferState.RUNNING },
            done = states.count { it == TransferState.DONE },
            failed = states.count { it == TransferState.FAILED },
        )
    }
}
