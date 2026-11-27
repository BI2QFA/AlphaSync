package io.github.bi2qfa.alphasync.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bi2qfa.alphasync.core.ConnectionCenter
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.ptpip.CameraReceiveMeter
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.remote.RemoteControlProxy
import io.github.bi2qfa.alphasync.remote.RemoteControlTarget
import io.github.bi2qfa.alphasync.ui.floatingNavHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun RemoteControlScreen(
    connectedAddress: String?,
    onAddressChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("remote_control", 0) }
    var address by rememberSaveable {
        mutableStateOf(connectedAddress ?: ConnectionCenter.host ?: prefs.getString("address", "") ?: "")
    }
    val openFiles by rememberUpdatedState(onOpenFiles)
    var message by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) foreground = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val target = connectedAddress?.let(RemoteControlTarget::parse)
    val network = ObjectRepository.boundNetwork
    val proxy = remember(target, foreground, network) { if (target != null && foreground) RemoteControlProxy(target, network) else null }
    DisposableEffect(proxy) {
        ThumbStore.remoteControlActive = proxy != null
        onDispose { proxy?.close(); ThumbStore.remoteControlActive = false }
    }
    val scope = rememberCoroutineScope()
    var download by remember { mutableStateOf<Pair<URL, android.net.Network?>?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val request = download
        download = null
        if (uri != null && request != null) scope.launch {
            message = "正在保存照片…"
            message = try {
                withContext(Dispatchers.IO) {
                    val (url, selectedNetwork) = request
                    val connection = ((selectedNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
                        connectTimeout = 5000; readTimeout = 30000; instanceFollowRedirects = false
                    }
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            CameraReceiveMeter.input(connection.inputStream).use { it.copyTo(output, 32 * 1024) }
                        } ?: error("无法写入所选位置")
                    } finally { connection.disconnect() }
                }
                "照片已保存"
            } catch (_: Exception) { "保存失败，请在 AlphaSync 文件页下载原图" }
        }
    }
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
    val bottomInset = floatingNavHeight()
    Column(Modifier.fillMaxSize().padding(bottom = bottomInset)
        .consumeWindowInsets(PaddingValues(bottom = bottomInset)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = onDismiss) { Text("返回") }
            Text("遥控拍摄", modifier = Modifier.weight(1f).padding(12.dp),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            if (target != null) TextButton(onClick = { onAddressChange(null) }) { Text("更换地址") }
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (target == null) {
            Column(Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 在相机端 AlphaSync 菜单中选择「遥控拍摄」。\n2. 保持相机与手机网络互通，输入相机 IP 后打开控制页面。")
                Text("取景期间暂停自动预览预取和原图下载；返回后恢复。手动对焦和包围拍摄需要镜头支持；景深合成仍需电脑端。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("相机 IP，例如 192.168.43.100:8080") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val parsed = RemoteControlTarget.parse(address)
                    if (parsed == null) message = "请输入有效的相机 IPv4 地址，可附加端口号"
                    else {
                        address = "${parsed.host}:${parsed.port}"
                        prefs.edit().putString("address", address).apply()
                        message = ""
                        onAddressChange(address)
                    }
                }) { Text("打开遥控页面") }
                Text("集成自 hhb/sony-pmca-webcontrol（MIT）。在相机端按 MENU 可返回传图界面。", style = MaterialTheme.typography.bodySmall)
            }
        } else if (proxy != null) {
            key(proxy.url) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString += " AlphaSyncFiles/1"
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.builtInZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (url == proxy.url + "native/files") {
                                    if (request.isForMainFrame) openFiles()
                                    return true
                                }
                                return !url.startsWith(proxy.url)
                            }
                            override fun onReceivedError(view: WebView, code: Int, description: String, failingUrl: String) {
                                message = "无法打开遥控页面：请确认相机端已进入遥控拍摄，且 IP 和端口正确"
                            }
                        }
                        setDownloadListener { url, _, disposition, _, _ ->
                            val remote = proxy.resolveDownload(url)
                            if (remote != null) {
                                download = remote to network
                                val filename = android.webkit.URLUtil.guessFileName(url, disposition, "image/jpeg")
                                save.launch(filename)
                            }
                        }
                        loadUrl(proxy.url)
                    }
                }, onRelease = { view -> view.stopLoading(); view.loadUrl("about:blank"); view.removeAllViews(); view.destroy() })
            }
        }
    }
    }
}
