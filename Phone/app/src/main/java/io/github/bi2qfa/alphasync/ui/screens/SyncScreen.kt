package io.github.bi2qfa.alphasync.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bi2qfa.alphasync.core.ConnectionCenter
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.transfer.LiveTransferCenter
import io.github.bi2qfa.alphasync.transfer.TransferStore
import io.github.bi2qfa.alphasync.ui.FloatingNavInset
import io.github.bi2qfa.alphasync.ui.components.EnqueueMark
import io.github.bi2qfa.alphasync.ui.components.EntryRow
import io.github.bi2qfa.alphasync.ui.components.ExpressiveButton
import io.github.bi2qfa.alphasync.ui.components.FullscreenPreview
import io.github.bi2qfa.alphasync.ui.components.GridCell
import io.github.bi2qfa.alphasync.ui.components.HintText
import io.github.bi2qfa.alphasync.ui.components.MsIcon
import io.github.bi2qfa.alphasync.ui.components.PREVIEWABLE_EXT
import io.github.bi2qfa.alphasync.ui.components.markOf
import io.github.bi2qfa.alphasync.ui.components.segmentShape
import io.github.bi2qfa.alphasync.ui.theme.Motion

/**
 * 同步页（★ 2.11 §9，用户定版）——文件页**向左滑**进入的第二页。
 *
 * <p>语义 = 原来的"边拍边传"（2.7/2.8），但状态与列表都搬到了这里：
 * <ul>
 *   <li>顶部**自动同步开关**：开启后巡检（常开）确认稳定的新照片自动加入传输列表，
 *       同时出现在这个面板；</li>
 *   <li>面板条目 = [LiveTransferCenter.panelEntries]（与自动入队同一批、先来后到序）；</li>
 *   <li>显示默认**最新在前**，点排序切倒序（只改显示；入队顺序永远是先来后到）；</li>
 *   <li>显示方式默认**九宫格**（与文件页网格同版式），可切列表；</li>
 *   <li>点条目 → 全屏预览（与文件页同一套预览器）；</li>
 *   <li>新照片到达：只有用户正停在新照片出现的那一端才自动滚动露出（§2 同款）。</li>
 * </ul>
 *
 * <p>局部态（排序方向 / 视图模式）与文件页同约定：**不持久化**，离开页面回默认。
 */
@Composable
fun SyncScreen(
    /** ★ 2.11：双击顶栏标题 → 回列表顶部（MainActivity 递增的信号）。 */
    topTick: Int = 0,
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val connected = ConnectionCenter.state == ConnectionCenter.State.CONNECTED

    // ★ 2.11 第二轮（§3）：告诉 ThumbStore "同步页在屏" —— 传输期间小图批不受
    //   暂停限制（新照片的小缩略图照拉）；离开页面（横滑出视口 / 整页切换）即回收。
    DisposableEffect(Unit) {
        ThumbStore.setSyncVisible(true)
        onDispose { ThumbStore.setSyncVisible(false) }
    }

    // 局部态（与文件页同款"不持久化"约定）
    var syncDesc by remember { mutableStateOf(true) }   // true = 最新在前（默认，需求）
    var gridView by remember { mutableStateOf(true) }   // 默认九宫格（需求）
    var fullscreenEntry by remember { mutableStateOf<ObjectRepository.FtpEntry?>(null) }

    // ★ 2.11 第二轮（§5.3）：角标数据源 = 当前设备队列的真实状态（与传输页同套 markOf）。
    val queueItems = TransferStore.items

    // 面板数据（到达序 = 旧→新）。key 用 size：只追加、不修改既有元素。
    val raw = LiveTransferCenter.panelEntries
    val entries = remember(raw.size, syncDesc) {
        val snapshot = raw.toList()
        if (syncDesc) {
            snapshot.sortedWith(
                compareByDescending<ObjectRepository.FtpEntry> { it.timestamp }.thenBy { it.name },
            )
        } else {
            snapshot.sortedWith(
                compareBy<ObjectRepository.FtpEntry> { it.timestamp }.thenBy { it.name },
            )
        }
    }

    // ★ 2.11 第二轮（§5.4）：选择模式状态机 —— 与文件页同构（量小、两页语义有差异，
    //   不硬抽公共 hook）。
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var batchAnchor by remember { mutableStateOf<String?>(null) }

    fun exitSelectMode() {
        selecting = false
        selected.clear()
    }

    // 退出选择模式（selecting → false）→ 锚点一并清；覆盖一切出口。
    LaunchedEffect(selecting) {
        if (!selecting) batchAnchor = null
    }

    fun toggle(path: String) {
        if (!selected.remove(path)) selected.add(path)
    }

    /** 批量选择（两步锚点，与文件页 longPress 同一实现）。 */
    fun longPress(path: String) {
        if (!selecting) return
        val anchor = batchAnchor
        if (anchor == null) {
            toggle(path)
            batchAnchor = if (selected.contains(path)) path else null
        } else {
            val a = entries.indexOfFirst { it.path == anchor }
            val b = entries.indexOfFirst { it.path == path }
            if (a >= 0 && b >= 0) {
                for (e in entries.subList(minOf(a, b), maxOf(a, b) + 1)) {
                    if (!selected.contains(e.path)) selected.add(e.path)
                }
            }
            batchAnchor = null                  // 一次批量选择完成
        }
    }

    /**
     * ★ 2.11 第二轮（§5.4）：**操作选择框**目标 —— 非选择模式长按 = 单条；
     * 选择模式点「操作」钮 = 选中集合。文案定版："移出同步列表" / "立刻开始传输"。
     */
    var actionTargets by remember { mutableStateOf<List<ObjectRepository.FtpEntry>?>(null) }

    fun doRemove(targets: List<ObjectRepository.FtpEntry>) {
        LiveTransferCenter.removeFromPanel(targets)
        exitSelectMode()
    }

    fun doStartNow(targets: List<ObjectRepository.FtpEntry>) {
        LiveTransferCenter.startNow(targets)
        exitSelectMode()
    }

    // 返回键：选择模式中先退选择（后注册优先，盖过 pager 的"滑回文件页"）。
    androidx.activity.compose.BackHandler(enabled = selecting) {
        exitSelectMode()
    }

    // 整块淡入：切换排序 / 首次进入时闪一下（与文件页"列表闪烁"同一手法）
    val listFade = remember { Animatable(1f) }
    LaunchedEffect(syncDesc) {
        listFade.snapTo(0f)
        listFade.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }

    // ★ 2.11：topTick 的**消费基线** —— 切九宫格/列表会让分支重建，若无基线，
    //   旧信号会在重建后再把列表拽回顶部一次（与 FilesScreen 同一处理）。
    var topTickSeen by remember { mutableIntStateOf(topTick) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ===== 工具条药丸（与文件页同语言）：[自动同步 + 开关] …… [排序] [视图] =====
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("自动同步", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                    // 开关可用性沿用原主页开关逻辑：未连接置灰；相机后台化重连期间
                    // （enabled=true 但 connected=false）仍可随时关掉。
                    // ★ 2.11 第二轮（§5.2 定版）：大 Switch → 与工具条同规格的**图标钮**
                    //   （22dp）。开启 = primary + 实心（filled）；关闭 = 默认色空心。
                    //   可用性沿用原逻辑：未连接置灰；相机后台化重连期间仍可随时关掉。
                    val syncSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = {
                            if (LiveTransferCenter.enabled) {
                                LiveTransferCenter.disable()
                            } else if (ConnectionCenter.state == ConnectionCenter.State.CONNECTED) {
                                LiveTransferCenter.enable(context)
                            }
                        },
                        enabled = connected || LiveTransferCenter.enabled,
                        interactionSource = syncSource,
                    ) {
                        MsIcon(
                            icon = MsIcon.SYNC,
                            contentDescription = if (LiveTransferCenter.enabled) "自动同步 开" else "自动同步 关",
                            modifier = Modifier.size(22.dp),
                            size = 22.dp,
                            tint = if (LiveTransferCenter.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            filled = LiveTransferCenter.enabled,
                            interactionSource = syncSource,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // ★ 2.11 第二轮（§5.4）：[选择模式] 钮（与文件页同款，位于排序左边）
                    val selModeSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = {
                            if (selecting) exitSelectMode() else selecting = true
                        },
                        enabled = entries.isNotEmpty() || selecting,
                        interactionSource = selModeSource,
                    ) {
                        MsIcon(
                            icon = MsIcon.CHECKLIST,
                            contentDescription = if (selecting) "退出选择模式" else "进入选择模式",
                            modifier = Modifier.size(22.dp),
                            size = 22.dp,
                            interactionSource = selModeSource,
                        )
                    }
                    // 操作钮：**仅选择模式出现**（横向展开 + 淡入；退出时收回）——
                    //   弹出 [移出同步列表 / 立刻开始传输] 操作框（作用 = 选中集合）。
                    AnimatedVisibility(
                        visible = selecting,
                        enter = fadeIn(Motion.effectsFast()) +
                            expandHorizontally(Motion.spatialDefault()),
                        exit = fadeOut(Motion.effectsFast()) +
                            shrinkHorizontally(Motion.spatialFast()),
                    ) {
                        val opSource = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                val targets = entries.filter { selected.contains(it.path) }
                                if (targets.isNotEmpty()) actionTargets = targets
                            },
                            enabled = selected.isNotEmpty(),
                            interactionSource = opSource,
                        ) {
                            MsIcon(
                                icon = MsIcon.MORE_VERT,
                                contentDescription = "操作",
                                modifier = Modifier.size(22.dp),
                                size = 22.dp,
                                interactionSource = opSource,
                            )
                        }
                    }
                    // 排序（与文件页同款：图标 + 上下翻面弹簧）
                    val sortSource = remember { MutableInteractionSource() }
                    val sortFlip by animateFloatAsState(
                        targetValue = if (syncDesc) 1f else -1f,
                        animationSpec = Motion.spatialDefault(),
                        label = "syncSortFlip",
                    )
                    IconButton(
                        onClick = { syncDesc = !syncDesc },
                        interactionSource = sortSource,
                    ) {
                        MsIcon(
                            icon = MsIcon.SORT,
                            contentDescription = "切换排序",
                            modifier = Modifier.size(22.dp).graphicsLayer { scaleY = sortFlip },
                            size = 22.dp,
                            interactionSource = sortSource,
                        )
                    }
                    val viewSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { gridView = !gridView },
                        interactionSource = viewSource,
                    ) {
                        MsIcon(
                            icon = if (gridView) MsIcon.LIST else MsIcon.GRID,
                            contentDescription = "切换显示模式",
                            modifier = Modifier.size(22.dp),
                            size = 22.dp,
                            interactionSource = viewSource,
                        )
                    }
                }
            }

            // ===== 状态行（开关开启时显示；含"等待相机…/已暂停"这类关键状态） =====
            if (LiveTransferCenter.enabled && LiveTransferCenter.statusText.isNotBlank()) {
                Text(
                    LiveTransferCenter.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                )
            }

            // ===== 空态 =====
            if (!LiveTransferCenter.enabled) {
                HintText("打开自动同步后，新拍摄的照片会出现在这里并自动加入传输列表")
            }

            // ===== 内容：九宫格（默认）/ 列表 =====
            if (gridView) {
                val gridState = rememberLazyGridState()
                // 双击标题回顶（消费基线：旧信号不在分支重建后重放）
                LaunchedEffect(topTick) {
                    if (topTick > topTickSeen) {
                        topTickSeen = topTick
                        if (entries.isNotEmpty()) gridState.animateScrollToItem(0)
                    }
                }
                // §2 同款：新照片到达时，只有用户正停在"新照片出现的那一端"才自动露出
                var prevCount by remember { mutableIntStateOf(entries.size) }
                LaunchedEffect(entries.size) {
                    val prev = prevCount
                    prevCount = entries.size
                    val added = entries.size - prev
                    if (added <= 0 || prev == 0) return@LaunchedEffect
                    // 等一帧：Lazy 的 key 锚定在"头部插入"时会把首可见 index 顶高
                    // added 个位（原本 index 0 的条目现在在 added 处）—— 布局完成后
                    // 才能读到稳定值，"用户原本在最上面" ⇔ first ≤ added。
                    androidx.compose.runtime.withFrameNanos { }
                    if (syncDesc) {
                        if (gridState.firstVisibleItemIndex <= added) {
                            gridState.animateScrollToItem(0)
                        }
                    } else {
                        val lastVisible =
                            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        if (lastVisible >= prev - 1) {
                            gridState.animateScrollToItem(entries.lastIndex)
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { alpha = listFade.value },
                    contentPadding = PaddingValues(bottom = FloatingNavInset, top = 4.dp),
                ) {
                    gridItems(entries, key = { it.path }) { entry ->
                        GridCell(
                            entry = entry,
                            // ★ 2.11 第二轮（§5.4）：选择模式交互（与文件页同套）
                            selecting = selecting,
                            isSelected = selected.contains(entry.path),
                            onToggle = { toggle(entry.path) },
                            onDoubleClick = {
                                if (!entry.isDir && entry.ext in PREVIEWABLE_EXT) fullscreenEntry = entry
                            },
                            onLongClick = {
                                // 选择模式：批量选择（两步锚点）；非选择模式：单条操作框
                                if (selecting) longPress(entry.path) else actionTargets = listOf(entry)
                            },
                            onClick = {
                                if (!entry.isDir && entry.ext in PREVIEWABLE_EXT) {
                                    fullscreenEntry = entry
                                }
                            },
                            // ★ 2.11 第二轮（§5.3）：队列状态角标（✓/•/!，与传输页同套 markOf）
                            mark = markOf(queueItems, entry.path, entry.size, entry.timestamp),
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(topTick) {
                    if (topTick > topTickSeen) {
                        topTickSeen = topTick
                        if (entries.isNotEmpty()) listState.animateScrollToItem(0)
                    }
                }
                var prevCount by remember { mutableIntStateOf(entries.size) }
                LaunchedEffect(entries.size) {
                    val prev = prevCount
                    prevCount = entries.size
                    val added = entries.size - prev
                    if (added <= 0 || prev == 0) return@LaunchedEffect
                    androidx.compose.runtime.withFrameNanos { }
                    if (syncDesc) {
                        if (listState.firstVisibleItemIndex <= added) {
                            listState.animateScrollToItem(0)
                        }
                    } else {
                        val lastVisible =
                            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        if (lastVisible >= prev - 1) {
                            listState.animateScrollToItem(entries.lastIndex)
                        }
                    }
                }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = listFade.value },
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = FloatingNavInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    val count = entries.size
                    itemsIndexed(entries, key = { _, e -> e.path }) { index, entry ->
                        EntryRow(
                            shape = segmentShape(index, count),
                            entry = entry,
                            // ★ 2.11 第二轮（§5.4）：选择模式交互（与文件页同套）
                            selecting = selecting,
                            isSelected = selected.contains(entry.path),
                            onToggleSelect = { toggle(entry.path) },
                            onDoubleClick = {
                                if (!entry.isDir && entry.ext in PREVIEWABLE_EXT) fullscreenEntry = entry
                            },
                            onLongClick = {
                                // 选择模式：批量选择（两步锚点）；非选择模式：单条操作框
                                if (selecting) longPress(entry.path) else actionTargets = listOf(entry)
                            },
                            onClick = {
                                if (!entry.isDir && entry.ext in PREVIEWABLE_EXT) {
                                    fullscreenEntry = entry
                                }
                            },
                            // ★ 2.11 第二轮（§5.3）：队列状态角标（✓/•/!，与传输页同套 markOf）
                            mark = markOf(queueItems, entry.path, entry.size, entry.timestamp),
                        )
                    }
                }
            }
        }
    }

    // ★ 第三批（§3/§9）：操作弹窗 = AlertDialog（居中、点空白可关为原生能力）。
    // ★ 第四批（项1）：操作行改用 MD3E 堆叠胶囊按钮（ExpressiveButton，56dp/全圆角/
    //   tonal 填充）——"卡片式按钮"；"取消"从右下角收进同一列（整宽、同款、无图标），
    //   三行同一列、位置对齐不再突兀。文案不变："移出同步列表" / "立刻开始传输"。
    actionTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { actionTargets = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExpressiveButton(
                        text = "移出同步列表",
                        onClick = {
                            actionTargets = null
                            doRemove(targets)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        tonal = true,
                        leadingIcon = {
                            MsIcon(
                                icon = MsIcon.TRASH,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                size = 20.dp,
                                animated = false,
                            )
                        },
                    )
                    ExpressiveButton(
                        text = "立刻开始传输",
                        onClick = {
                            actionTargets = null
                            doStartNow(targets)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        tonal = true,
                        leadingIcon = {
                            MsIcon(
                                icon = MsIcon.START,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                size = 20.dp,
                                animated = false,
                            )
                        },
                    )
                    ExpressiveButton(
                        text = "取消",
                        onClick = { actionTargets = null },
                        modifier = Modifier.fillMaxWidth(),
                        tonal = true,
                    )
                }
            },
            // 取消已收进上面的按钮列（整宽同款）——按钮区留空，不再在右下角挂一个小字按钮
            confirmButton = {},
        )
    }

    // ===== 全屏大预览（与文件页共用同一套；同步页不做选择，隐藏"选择"动作） =====
    fullscreenEntry?.let { entry ->
        val gallery = remember(entries) { entries.filter { it.ext in PREVIEWABLE_EXT } }
        val startIndex = gallery.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        Dialog(
            onDismissRequest = { fullscreenEntry = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            FullscreenPreview(
                gallery = gallery,
                startIndex = startIndex,
                isSelected = { false },
                onToggleSelect = {},
                onDismiss = { fullscreenEntry = null },
                onOpenSettings = onOpenSettings,
                onSwitchDevice = onSwitchDevice,
                showSelectAction = false,
            )
        }
    }
}
