package io.github.bi2qfa.alphasync.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.ui.floatingNavHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private data class AlbumPhoto(val uri: String, val name: String, val bucket: String, val album: String, val size: Long, val timestamp: Long)
private val thumbnailSlots = Semaphore(3)

/** Read all indexed albums permitted by Android; documents cover unindexed folders and RAW. */
@Composable
fun PhoneAlbumScreen(onDismiss: () -> Unit, navigation: @Composable () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf<List<AlbumPhoto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bucket by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf(arrayListOf<String>()) }
    val imagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun fullAccess() = ContextCompat.checkSelfPermission(context, imagePermission) == PackageManager.PERMISSION_GRANTED
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) viewing = ArrayList(selected.map { it.toString() })
    }
    fun requestAccess() {
        permissions.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(imagePermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(imagePermission))
    }
    LaunchedEffect(refresh) {
        loading = true
        error = null
        photos = withContext(Dispatchers.IO) {
            try {
                val root = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN)
                buildList {
                    context.contentResolver.query(root, projection, null, null,
                        "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC")?.use { cursor ->
                        while (cursor.moveToNext()) add(AlbumPhoto(
                            ContentUris.withAppendedId(root, cursor.getLong(0)).toString(),
                            cursor.getString(1) ?: "照片", cursor.getString(2) ?: "", cursor.getString(3) ?: "未命名影集",
                            cursor.getLong(4), cursor.getLong(5)))
                    }
                }
            } catch (_: SecurityException) { emptyList() }
            catch (_: Exception) { error = "无法读取相册，请刷新或从文件选择"; emptyList() }
        }
        loading = false
    }
    BackHandler { if (viewing.isNotEmpty()) viewing = arrayListOf() else onDismiss() }
    val visible = remember(photos, bucket) { if (bucket.isEmpty()) photos else photos.filter { it.bucket == bucket } }
    val knownEntries = remember(photos) { photos.associate { it.uri to ObjectRepository.FtpEntry(it.name, it.uri, false, it.size, it.timestamp) } }
    val bottomInset = floatingNavHeight()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().padding(bottom = bottomInset)
            .consumeWindowInsets(PaddingValues(bottom = bottomInset)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("返回") }
                Text("手机相册", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }) { Text("刷新") }
                TextButton(onClick = { files.launch(arrayOf("image/*", "application/octet-stream")) }) { Text("从文件选择") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (fullAccess()) "已授权全部相册 · ${photos.size} 张" else "当前仅显示获授权照片；查看全部影集请允许访问所有照片",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { requestAccess() }) { Text(if (fullAccess()) "相册权限" else "授权相册") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = bucket.isEmpty(), onClick = { bucket = "" }, label = { Text("全部") })
                photos.distinctBy { it.bucket }.forEach { album ->
                    FilterChip(selected = bucket == album.bucket, onClick = { bucket = album.bucket }, label = { Text(album.album) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (!loading && visible.isEmpty()) Text("没有可读取的照片。可授权全部相册，或从文件选择未收录的影集。", modifier = Modifier.padding(16.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(visible, key = { _, photo -> photo.uri }) { index, photo ->
                    Column(Modifier.clickable { viewing = ArrayList(visible.drop(index).map { it.uri } + visible.take(index).map { it.uri }) }) {
                        AlbumThumbnail(photo.uri)
                        Text(photo.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (viewing.isEmpty()) navigation()
        else PhoneAlbumViewer(viewing, knownEntries) { viewing = arrayListOf() }
    }
}

@Composable
private fun AlbumThumbnail(uriValue: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uriValue) {
        value = withContext(Dispatchers.IO) {
            thumbnailSlots.withPermit {
                runCatching {
                    val uri = Uri.parse(uriValue)
                    if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(256, 256), null).asImageBitmap()
                    else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        var sample = 1
                        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                        }
                    }
                }.getOrNull()
            }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text("预览", style = MaterialTheme.typography.labelSmall)
    }
}
