package io.github.bi2qfa.alphasync

import android.app.Application
import io.github.bi2qfa.alphasync.core.ConnectionCenter
import io.github.bi2qfa.alphasync.data.IdentityRepo
import io.github.bi2qfa.alphasync.data.PairingStore
import io.github.bi2qfa.alphasync.data.SettingsRepo
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.transfer.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AlphaSyncApp : Application() {

    lateinit var appScope: CoroutineScope
        private set

    override fun onCreate() {
        super.onCreate()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SettingsRepo.init(this)
        // ★ 顺序要求：IdentityRepo 提供 guid16 / friendlyName（UDP 探测与握手都要用），
        //   PairingStore 提供自动连接匹配表，必须先于 ConnectionCenter.init。
        IdentityRepo.init(this)
        PairingStore.init(this)
        TransferStore.load(this)
        ThumbStore.init(this)
        ConnectionCenter.init(appScope, this)
    }
}
