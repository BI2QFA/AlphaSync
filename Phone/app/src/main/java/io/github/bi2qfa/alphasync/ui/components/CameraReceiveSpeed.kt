package io.github.bi2qfa.alphasync.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bi2qfa.alphasync.core.ConnectionCenter
import io.github.bi2qfa.alphasync.ptpip.CameraReceiveMeter
import io.github.bi2qfa.alphasync.ptpip.ReceiveRateWindow
import kotlinx.coroutines.delay

@Composable
fun CameraReceiveSpeed() {
    val connected = ConnectionCenter.state == ConnectionCenter.State.CONNECTED
    var speed by remember { mutableStateOf(0.0) }
    LaunchedEffect(connected) {
        speed = 0.0
        if (connected) {
            val window = ReceiveRateWindow(CameraReceiveMeter.totalBytes(), System.nanoTime())
            while (true) {
                delay(1000)
                speed = window.sample(CameraReceiveMeter.totalBytes(), System.nanoTime())
            }
        }
    }
    Column(Modifier.widthIn(min = 86.dp), horizontalAlignment = Alignment.End) {
        Text("相机 ↓ 手机 · 3s", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(if (connected) CameraReceiveMeter.format(speed) else "未连接",
            style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
