package io.github.bi2qfa.alphasync.ui.components

/**
 * 文件/传输/同步三个页面共用的"条目视图"组件（2.11 UI 批次从 FilesScreen 抽取）。
 *
 * 抽取动机：同步页（SyncScreen）要与文件页**完全同款**地显示条目（用户定版），
 * 传输页要用同一条缩略图管线 —— 各写一份必然漂移。这里**只搬不改**：所有行为、
 * 注释、动效语义都保持抽取前的原样（逐字复制）。
 *
 * 纪律：
 * - 这些组件是**纯展示 + 回调**，不持有任何业务状态（选中集合、目录、队列都在调用方）；
 * - 改这里的显示方案 = 三个页面一起改（这正是抽取的意义）；
 * - [EntryRow] / [GridCell] 的"选择模式"参数给了默认值（同步页/传输页不用选择模式）。
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.transfer.TransferItem
import io.github.bi2qfa.alphasync.transfer.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    modifier: Modifier = Modifier,
    shape: Shape,
    entry: ObjectRepository.FtpEntry,
    selecting: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit = {},
    onDoubleClick: () -> Unit = {},
    /**
     * 长按。**可空**（2.11 §8）：文件页在非选择模式传 null —— 长按连"检测"都不发生
     * （无触感、无延迟判定）；同步页/传输页同样不需要长按。
     */
    onLongClick: (() -> Unit)? = null,
    /** ★ 2.11 第二轮（§5.3）：队列状态角标（同步页传；不传 = 不显示，其余页不受影响）。 */
    mark: EnqueueMark = EnqueueMark.NONE,
) {
    val selected = selecting && isSelected
    Row(
        modifier
            .fillMaxWidth()
            // 圆角由它在组里的位置决定（见调用处）：与上下的行拼成一整块卡
            .clip(shape)
            // 底色不再用 secondaryContainer 直接铺 —— 那个颜色太实，几行连选时整块变成
            // 一大片紫。选中态改成 secondaryContainer，未选中是分段面的底色，
            // 两者亮度差足够看出选中，又不至于把整页染上色。
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else segmentSurfaceColor()
            )
            // ★ 选择模式下点行 = 选中/取消（与九宫格同一语义）：对勾删掉后这是唯一的切换入口。
            //   **瞬时回调**：不传 onDoubleClick —— 传了单击会被压后到双击窗口结束才触发、
            //   手感发肉（用户实测"单击反应迟钝"）；双击交给下面的旁路检测器（单击零延迟）。
            .combinedClickable(
                onClick = if (selecting) onToggleSelect else onClick,
                onLongClick = onLongClick,
            )
            // ★ 选择模式下**双击 = 打开大图预览**（用户定版：两种视图都要）。
            //   旁路观察（Initial pass、不消费事件）：单击照旧瞬时触发（选择模式下两次
            //   toggle 对称抵消，选中集合净不变），只在"同一项两次点击落在双击窗口内"时
            //   补发一次 onDoubleClick。见 [doubleTapDetector]。
            .doubleTapDetector(enabled = selecting, onDoubleTap = onDoubleClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ★ 选择标注只用**高亮底色**（用户定版"去掉左边的对勾"）；onToggleSelect
        //   仍由整行点击承担（选择模式下点一行 = 选中/取消）
        ThumbOrPlaceholder(entry, size = 56)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // ★ 2.11 第二轮（§5.3）：同步页条目的队列状态角标（与传输页同套 markOf）
                if (mark != EnqueueMark.NONE) {
                    Spacer(Modifier.width(4.dp))
                    MarkBadge(mark)
                }
            }
            // 大小 + 修改时间（FTP LIST 自带，用户要求显示）
            val meta = if (entry.isDir) formatTime(entry.timestamp)
            else listOf(formatSize(entry.size), formatTime(entry.timestamp))
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GridCell(
    modifier: Modifier = Modifier,
    entry: ObjectRepository.FtpEntry,
    selecting: Boolean = false,
    isSelected: Boolean = false,
    onToggle: () -> Unit = {},
    onDoubleClick: () -> Unit = {},
    /** 长按；可空，语义同 [EntryRow]。 */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    /** ★ 2.11 第二轮（§5.3）：队列状态角标（同步页传；不传 = 不显示）。 */
    mark: EnqueueMark = EnqueueMark.NONE,
) {
    Card(
        modifier
            .padding(4.dp)
            // ★ 裁剪必须跟在 Card 的**外面**先做：combinedClickable 挂在 Card 之前，
            //   它的水波纹画在卡片之外、不受卡片自身圆角裁剪 —— 不裁的话选择时是
            //   一个**方角块**盖在圆角卡片上（用户反馈"圆角遮罩处理粗糙")。
            //   形状直接取 CardDefaults.shape，避免两处各写一个半径日后漂移。
            .clip(CardDefaults.shape)
            // 单击：选择模式下切换选中，普通模式打开；长按任何视图下都进选择模式
            // （单击**瞬时**触发：双击不再走 combinedClickable 的 onDoubleClick ——
            //   传了它单击要被压到双击窗口结束，手感发肉）
            .combinedClickable(
                onClick = if (selecting) onToggle else onClick,
                onLongClick = onLongClick,
            )
            // ★ 选择模式下**双击 = 打开大图预览**（用户定版：九宫格与列表都要）：
            //   同 EntryRow，由 [doubleTapDetector] 旁路检测（观察不消费，单击零延迟）。
            .doubleTapDetector(enabled = selecting, onDoubleTap = onDoubleClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            // 选中时整张卡片换底色：九宫格里缩略图占满，只靠右上角那枚对号不够显眼
            containerColor = if (selecting && isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                // ★ 版式统一（用户要求：文件的显示和文件夹的显示要一样）：
                //   图片 = 缩略图铺满这块面；文件夹/非图片文件 = **同一块面**铺中性底
                //   + 居中的字形。原来文件夹只在格子中间浮着一枚小字形，跟铺满的缩略图
                //   是两种版式，一屏里一眼就能看出"这几格是空的"。
                //
                //   ★ 预览区**四周内缩 6dp**（用户定版）：图片原来紧贴卡片边缘，选中时
                //   的高亮底色只在图片上下的文字区看得见 —— 卡片加高一点、图与卡边留出
                //   空隙，高亮框才能在**图片上方**也露出来。
                val isImage = entry.ext in PREVIEWABLE_EXT
                val cacheKey = if (isImage) {
                    ThumbStore.ObjectCacheKey(
                        path = entry.path,
                        size = entry.size,
                        mtime = entry.timestamp,
                    )
                } else null
                // ★ O1：小图走**异步解码**（内存即时命中；miss 在 IO 线程解，不卡组合），
                //   并订阅方向信号（需求 7：sidecar 学到方向后重解、跟着大预览转正）。
                val bmp = rememberSmallThumb(cacheKey)
                // ★ 加载中的小图 → 转圈（2026-10-04 恢复）：仅"正在加载"（st == LOADING）
                //   亮转圈；未加载 / 失败 / 非图片一律维持原占位图标不变。
                //   （此前一个批次曾把转圈误删并附了不实的"用户定版"注释，本处已纠正。）
                val st = cacheKey?.let { ThumbStore.states[it.token] }
                // 内缩 6dp + 小圆角裁剪：高亮（选中底色）在图片四周露出一整圈
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .clip(MaterialTheme.shapes.small),
                ) {
                    when {
                        bmp != null -> Image(
                            bmp,
                            contentDescription = entry.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.5f),
                            // ★ 2.11（第二轮回归）：**默认 Fit、位图原样**——Crop 会把竖
                            //   构图照片的上下裁掉（用户主诉"看不全"）。照片与 3:2 面板
                            //   比例不重合，Fit 后露出的卡片底色是几何必然，不是"黑边"。
                            //   用户定版：看得全 > 不露底。**勿再改 Crop**。
                        )
                        // ★ 正在加载：历史口径 22dp / strokeWidth 2dp（2.6 原位实现）
                        isImage && st == ThumbStore.ThumbState.LOADING -> Box(
                            Modifier.fillMaxWidth().aspectRatio(1.5f),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        else -> GridPlaceholder(
                            // 文件夹 / 无缩略图的图片 / 其它文件 —— 三种字形，只画图标
                            when {
                                entry.isDir -> MsIcon.FOLDER
                                isImage -> MsIcon.IMAGE_PLACEHOLDER
                                else -> MsIcon.FILE_GENERIC
                            }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
                ) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // ★ 2.11 第二轮（§5.3）：同步页条目的队列状态角标（与列表行同套）
                    if (mark != EnqueueMark.NONE) {
                        Spacer(Modifier.width(4.dp))
                        MarkBadge(mark)
                    }
                }
                // 修改日期时间（用户定版：网格里也要看得见，不只列表里有）
                val when_ = formatTime(entry.timestamp)
                if (when_.isNotBlank()) {
                    Text(
                        when_,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            start = 10.dp,
                            end = 10.dp,
                            top = 2.dp,
                            bottom = 10.dp,
                        ),
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

/**
 * ★ 选择模式下**双击 = 打开大图预览**的旁路检测器（用户定版"单击零延迟 + 双击可识别"）。
 *
 * 为什么不用 combinedClickable 自带的 onDoubleClick：传了它，库为了区分单/双击会把
 * 单击也压后到双击窗口结束才回调（~300ms 发肉，用户实测"单击反应比较迟钝"）。
 * 为什么不再叠一层 detectTapGestures（v1 教训）：它在 Main pass 一上来就消费 down 事件，
 * 外层 combinedClickable 的单击/长按整组被吃掉（用户实测"单击长按失灵"）。
 *
 * 本检测器只挂在 Initial pass（事件分发的第一站、先于所有正式手势处理）**观察**事件，
 * 从不调用 consume() —— 事件原样流过，单击/长按的既有链路零影响；自己数
 * "同一项、两次短促点击（位移 < touchSlop、时长 < 长按阈值）、抬起间隔落在系统双击
 * 窗口内"时补发一次 onDoubleTap。
 * 双击的两次单击各自 toggle 一次、对称抵消 —— 语义 = "只是看一眼大图，不改选中集合"；
 * 代价是双击时高亮会闪一下（"单击零延迟 + 双击可识别"的固有取舍，桌面文件管理器同款）。
 *
 * ★ 2.11 UI 批次：从 FilesScreen 提升为共享组件（AppHeader 标题双击回顶部也用它）。
 */
internal fun Modifier.doubleTapDetector(
    enabled: Boolean,
    onDoubleTap: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        var lastTapUp = 0L
        var downAt = 0L
        var downPos = Offset.Zero
        var moved = false
        var down = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)   // 只观察、绝不消费
            if (e.changes.size != 1) { down = false; continue }   // 多指：不判定
            val c = e.changes[0]
            if (c.pressed && !down) {
                down = true; downAt = c.uptimeMillis; downPos = c.position; moved = false
            } else if (down && c.pressed && (c.position - downPos).getDistance() > slop) {
                moved = true
            }
            if (!c.pressed && down) {
                down = false
                val isTap = !moved && c.uptimeMillis - downAt < longPressMs
                if (isTap) {
                    if (lastTapUp != 0L && c.uptimeMillis - lastTapUp <= doubleTapMs) {
                        lastTapUp = 0L
                        onDoubleTap()
                    } else lastTapUp = c.uptimeMillis
                } else lastTapUp = 0L
            }
        }
    }
}

/**
 * 网格里"没有缩略图"的那块位置：与图片**同一个尺寸**（整格上半部），内容是一枚
 * 居中的字形。
 *
 * ★ 2.11 UI 批次（用户定版）：**底色面整个去掉，只画图标本身** —— 原来这里铺
 *   `surfaceContainerHighest` 做成"一块面"，用户反馈不要这块外框。图标因此从
 *   32dp 放大到 48dp（原来图标浮在 3:2 大面上有"块"的体量感，去掉面之后 32dp
 *   会显得单薄；48dp 在两列网格里约占 27%，与常见文件管理器的占位比例一致）。
 */
@Composable
internal fun GridPlaceholder(icon: MsIcon) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            size = 48.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 网格里可能一次出现几十个占位：关掉动效，省掉每个图标的手势检测与动画状态
            animated = false,
        )
    }
}

/**
 * 小缩略图的**异步取图**（O1，2.6）：只为把"磁盘解码"挪出组合期。
 *
 * <p>取自 [ThumbStore.peekSmallThumb]（纯内存）—— 命中即时返回、不阻塞；
 * 未命中在 IO 线程走 [ThumbStore.smallThumb]（解码 + 回填内存）后回设状态。
 * 快速滚动时主线程不再做 jpg 解码，掉帧消失。
 *
 * <p>方向（2.7.0 需求 7）：[ThumbStore.orientationEpoch] 既作订阅（组合期读一下）
 * 又作 LaunchedEffect 的 key —— 大预览 sidecar 学到方向后内存缓存被 evict、
 * epoch 变化触发重解，小图跟着大预览一起转正。
 */
@Composable
internal fun rememberSmallThumb(key: ThumbStore.ObjectCacheKey?): androidx.compose.ui.graphics.ImageBitmap? {
    if (key == null) return null
    val epoch = ThumbStore.orientationEpoch      // 订阅方向信号
    // ★ 2.10-fix（修复 3：完成不直显）：订阅 states —— 小图下载完成时 states[token] → READY，
    //   Compose 状态变化触发重组；但 bmp 本身是局部态，effect 不重跑不会更新 → 轮询兜底。
    val st = ThumbStore.states[key.token]
    var bmp by remember(key) { mutableStateOf(ThumbStore.peekSmallThumb(key)) }
    LaunchedEffect(key, epoch, st) {
        val mem = ThumbStore.peekSmallThumb(key)
        if (mem != null) {
            bmp = mem
            return@LaunchedEffect
        }
        bmp = withContext(Dispatchers.IO) { ThumbStore.smallThumb(key) }
        if (bmp != null) return@LaunchedEffect
        // ★ 轮询兜底：网络下载完成时 memoryCache 变了但 key/epoch/st 都不变（st 置 READY
        //   是**同一次写**里先置的，effect 可能已在等待中错过）——每 200ms 查一次，
        //   命中即回设；30s 超时退出（防死循环，key 变化时 effect 自动取消重来）。
        var waited = 0L
        while (waited < 30_000L) {
            kotlinx.coroutines.delay(200L)
            waited += 200L
            val hit = ThumbStore.peekSmallThumb(key)
            if (hit != null) {
                bmp = hit
                return@LaunchedEffect
            }
        }
    }
    return bmp
}

/**
 * 列表行的小缩略图 / 占位（需求：无缩略图文件单独显示占位图标）。
 *
 * ★ 2.11 UI 批次（用户定版）：
 *   · 图片分支**无 clip、无 Crop，位图原样 Fit 居中**（第二轮回归：Crop 会裁掉
 *     非 1:1 照片的边、大圆角 clip 在 56dp 方槽里被钳成正圆——两条都是用户主诉；
 *     留白露出的卡片底色是长宽比不重合的几何必然，不是"黑边"）；
 *   · 占位分支**去掉底色方块与圆角面，只画图标**（原 56×56 底色块 + 26dp 图标 →
 *     56dp 宽的图标位 + 36dp 图标；36dp 是"去掉面之后补一点体量"的值 ——
 *     原 26dp 是浮在方块里的，去掉方块后偏小）；
 *   · **正在加载**的小图（状态 LOADING）画转圈（历史口径 20dp / strokeWidth 2dp）；
 *     未加载 / 失败 / 非图片维持占位图标不变。（2026-10-04 恢复：此前批次曾误删
 *     转圈并附了不实的"用户定版"注释，本处已纠正。）
 */
@Composable
internal fun ThumbOrPlaceholder(entry: ObjectRepository.FtpEntry, size: Int) {
    val isImage = entry.ext in PREVIEWABLE_EXT
    val cacheKey = if (isImage) {
        ThumbStore.ObjectCacheKey(
            path = entry.path,
            size = entry.size,
            mtime = entry.timestamp,
        )
    } else null
    // ★ O1：异步解码 + 方向信号订阅（同 GridCell 内的说明）
    val bmp = rememberSmallThumb(cacheKey)
    val st = cacheKey?.let { ThumbStore.states[it.token] }
    Box(
        Modifier.width(size.dp).height(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> Image(
                bmp,
                contentDescription = entry.name,
                // ★ 2.11（第二轮回归）：**无 clip、无 Crop，位图原样 Fit 居中**——
                //   大圆角 clip 在 56dp 方槽里被 Skia 钳成 28dp 半径 = 正圆（用户主诉）；
                //   Crop 又会裁掉非 1:1 照片的边。留白露出的是卡片底色，几何必然、
                //   不是"黑边"。用户定版：看得全 > 不露底。**勿再改 Crop**。
                modifier = Modifier.size(size.dp),
            )
            // ★ 正在加载：历史口径 20dp / strokeWidth 2dp（2.6 原位实现）
            isImage && st == ThumbStore.ThumbState.LOADING ->
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            // ★ 与网格同一条规矩：文件夹 / 非图片文件 / 未加载图片都只画**一枚图标**
            else -> MsIcon(
                icon = when {
                    entry.isDir -> MsIcon.FOLDER
                    isImage -> MsIcon.IMAGE_PLACEHOLDER
                    else -> MsIcon.FILE_GENERIC
                },
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                size = 36.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // 列表里每一行都有一个：关掉动效（同 GridPlaceholder 的理由）
                animated = false,
            )
        }
    }
}

/** 可全屏预览的扩展名（与进入预览时的判断同一套，别再写第二份）。 */
internal val PREVIEWABLE_EXT = setOf("jpg", "jpeg", "arw")

/**
 * **定槽缩略图**（原"比例自适应"，★ 1.0 按用户实测反馈修订）——传输页行首专用。
 *
 * <p>2.11 原行为：宽度固定、高度按位图真实宽高比自适应（竖 3:4 → 56×75）——用户实测
 * 反馈"竖拍图转正后把卡片撑高，卡片大小应和横图一致"。1.0 修订为**固定槽位**：
 * 槽高恒为 **4:3 横图基准**（0.75×宽，横图恰好填满、视觉与原一致），位图**原样 Fit
 * 居中**（与文件页 [ThumbOrPlaceholder] 同款口径）——竖图在槽内整体缩小，
 * **卡片高度不再随方向变化**。
 *
 * <p>同时**去掉圆角 clip**（原 8dp 圆角被指"奇怪"，文件页缩略图本就无圆角）。
 * 留白露出的是卡片底色（几何必然，同文件页"看得全 > 不露底"口径）。
 *
 * @param key 缩略图缓存键；null（非图片）直接用 [fallback]
 * @param fallback 无图时画什么（传输页 = 原来的状态圆片 IconCircle）
 */
@Composable
internal fun RatioThumb(
    key: ThumbStore.ObjectCacheKey?,
    fallback: @Composable () -> Unit,
    width: androidx.compose.ui.unit.Dp = 56.dp,
) {
    val bmp = rememberSmallThumb(key)
    Box(
        Modifier.width(width).height(width * 0.75f),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp == null) {
            fallback()
        } else {
            Image(
                bmp,
                contentDescription = null,
                modifier = Modifier.width(width).height(width * 0.75f),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}

internal fun formatSize(b: Long): String = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1 shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/** 修改时间（FTP LIST 解析）；无效返回空串 */
internal fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}

// ============================================================
// 队列状态角标（2.11 第二轮 §4，传输页 / 同步页共用）
// ============================================================

/** 文件名后的队列状态角标：✓ 已传完 / • 排队·传输中 / ! 失败 / 无。 */
enum class EnqueueMark { DONE, PENDING, FAILED, NONE }

/**
 * 从**队列真实状态**里取 [id] 的角标（两页共用同一套判定，不做 UI 本地推算）。
 *
 * 身份匹配口径与 TransferStore.hasActive 一致（id+size+mtime，防重拍误配）：
 * - DONE / FAILED **只要 id 相同就算** —— 历史条目身份略差（重拍后 size/mtime 变了）
 *   也该显示对号/感叹：传完就是传完；
 * - QUEUED / RUNNING 要求身份一致 —— 重拍的新文件还没入队时不得冒领"待传"。
 */
internal fun markOf(
    items: List<TransferItem>,
    id: String,
    size: Long,
    mtime: Long,
): EnqueueMark {
    val found = items.firstOrNull { t -> t.id == id } ?: return EnqueueMark.NONE
    return when (found.state) {
        TransferState.DONE -> EnqueueMark.DONE
        TransferState.FAILED -> EnqueueMark.FAILED
        TransferState.QUEUED, TransferState.RUNNING ->
            if (found.size == size && found.mtime == mtime) EnqueueMark.PENDING
            else EnqueueMark.NONE
    }
}

/**
 * 角标本体（16dp / animated=false —— 列表几十行，省手势检测与动画状态）。
 * 颜色全走主题：DONE=primary、FAILED=error、PENDING=onSurfaceVariant（6dp 实心圆点）。
 */
@Composable
internal fun MarkBadge(mark: EnqueueMark) {
    when (mark) {
        EnqueueMark.DONE -> MsIcon(
            icon = MsIcon.CHECK,
            contentDescription = "已传输",
            modifier = Modifier.size(16.dp),
            size = 16.dp,
            tint = MaterialTheme.colorScheme.primary,
            animated = false,
        )
        EnqueueMark.FAILED -> MsIcon(
            icon = MsIcon.ERROR,
            contentDescription = "传输失败",
            modifier = Modifier.size(16.dp),
            size = 16.dp,
            tint = MaterialTheme.colorScheme.error,
            animated = false,
        )
        EnqueueMark.PENDING -> Box(
            Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
        EnqueueMark.NONE -> Unit
    }
}
