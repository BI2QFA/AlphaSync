package io.github.bi2qfa.alphasync.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.bi2qfa.alphasync.core.ConnectionCenter
import androidx.exifinterface.media.ExifInterface
import io.github.bi2qfa.alphasync.data.FileListCache
import io.github.bi2qfa.alphasync.data.LocalPhoto
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.transfer.DownloadService
import io.github.bi2qfa.alphasync.transfer.TransferItem
import io.github.bi2qfa.alphasync.transfer.TransferStore
import io.github.bi2qfa.alphasync.ui.FloatingNavInset
import io.github.bi2qfa.alphasync.ui.components.HintText
import io.github.bi2qfa.alphasync.ui.components.segmentShape
import io.github.bi2qfa.alphasync.ui.components.segmentSurfaceColor
import io.github.bi2qfa.alphasync.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.bi2qfa.alphasync.ui.components.MsIcon
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import io.github.bi2qfa.alphasync.ui.components.AppHeader
import io.github.bi2qfa.alphasync.ui.components.DownloadDirMissingDialog
import io.github.bi2qfa.alphasync.ui.components.ExifInfoDialog
import io.github.bi2qfa.alphasync.core.StorageSink
import io.github.bi2qfa.alphasync.data.SettingsRepo
import io.github.bi2qfa.alphasync.ui.FloatingNav
import io.github.bi2qfa.alphasync.ui.NavItemSpec
import io.github.bi2qfa.alphasync.ui.PhotoRotate
import androidx.compose.ui.graphics.Brush
import io.github.bi2qfa.alphasync.ui.components.WholeAreaPressScope
import io.github.bi2qfa.alphasync.ui.components.LocalRowInteraction
// ★ 2.11 UI 批次：条目视图与全屏预览已抽到 ui.components（SyncScreen/TransfersScreen 共用）
import io.github.bi2qfa.alphasync.ui.components.EntryRow
import io.github.bi2qfa.alphasync.ui.components.FullscreenPreview
import io.github.bi2qfa.alphasync.ui.components.GridCell
import io.github.bi2qfa.alphasync.ui.components.PREVIEWABLE_EXT
import io.github.bi2qfa.alphasync.ui.components.markOf

/**
 * 文件页：
 * - 小缩略图列表 / 大缩略图网格两种显示模式；未取到缩略图的文件显示占位图标
 * - 单击文件 → 全屏缩略图（大预览）。
 * - ★ 2.11 §8（用户定版）**选择模式**：进入只走工具条「进入选择模式」按钮
 *   （非选择模式长按无操作）；进入后 [CHECKLIST] 按钮左移、右侧展开「全选/反选」。
 *   选择模式里单击=切换选中、双击=看大图、长按=两步批量选择
 *   （先长按锚点、再长按终点 → 显示序区间全选）。
 * - ★ 2.11 §3/§10：排序按钮 = 列表闪烁（listFade 重播）+ 图标 180° 弹簧翻转；
 *   默认新→旧，不持久化；刷新已删（进目录即自动加载）
 * - ★ 2.11 §2：停留期间有新照片（自动刷新落地）且用户正停在**新文件出现的那一端**
 *   时，自动滚动露出；其余情况保位不动
 * - 选择模式右下角 = 「开始传输(n)」FAB，样式与传输页一致
 * - 浏览位置（当前目录）由 AppRoot 提升持有：切页再回来仍在原位置
 * - 本页被 [FilesPagerScreen] 包在页 0：向左滑 = 同步页（[SyncScreen]）
 *
 * MD3E 版式：路径与动作收进一条**药丸工具条**（28dp 圆角的整块面），列表行用
 * 行内缩的圆角按压块 + 16dp 圆角缩略图。列表**不套大卡片** —— 一屏十几行套进卡片后，
 * 卡片底边会伸到悬浮药丸导航底下被压住，四角反而看不出是张卡（试过，不如不套）。
 */
@OptIn(
    ExperimentalFoundationApi::class,
    // M3E 多边形加载图标（LoadingIndicator）：下拉指示器与加载行都用它
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun FilesScreen(
    onGoTransfers: () -> Unit,
    dirState: androidx.compose.runtime.MutableState<String>,
    /** 控制中心面板里的两件事：由 MainActivity 透传（预览器里的面板要用）。 */
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: (String) -> Unit = {},
    /** ★ 2.11：双击顶栏标题 → 回列表顶部（MainActivity 递增的信号；0/未变不动）。 */
    topTick: Int = 0,
) {
    val context = LocalContext.current
    val host = ConnectionCenter.host
    val scope = rememberCoroutineScope()

    var dir by dirState
    var entries by remember { mutableStateOf<List<ObjectRepository.FtpEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var nextOffset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var gridView by remember { mutableStateOf(false) }
    /**
     * 列表排序方向（2.10）：默认 true = 新→旧。
     *
     * <p>与 [gridView] 同款**局部态、不持久化**（D5：退出页面即回默认）；渲染顺序见
     * [displayEntries]。服务端分页方向（reverse 传参）由整合阶段接线，见工具条按钮里的 TODO。
     */
    var sortDesc by remember { mutableStateOf(true) }

    // ★ 2.11 §2：列表两端的"在位"状态 —— 自动刷新落地后，只有用户正停在**新文件
    //   出现的那一端**才自动滚动露出（不在两端 = 键锚定天然保位，什么都不做）。
    var atTop by remember { mutableStateOf(true) }
    var atBottom by remember { mutableStateOf(false) }
    /** 自动露出信号（递增 = 滚到对应端；只在"用户就在那一端"时被触发）。 */
    var revealTopTick by remember { mutableIntStateOf(0) }
    var revealBottomTick by remember { mutableIntStateOf(0) }

    // ★ 2.11：信号**消费基线** —— 分支重建（切目录/切视图/点排序）时分支内的 remember
    //   会重置，若不记基线，"已经用过的旧信号"会在重建后再把列表拽回顶部/底部一次。
    //   基线放在这一层（分支之外）：只有**真正新来**的信号才被消费。
    var topTickSeen by remember { mutableIntStateOf(topTick) }
    var revealTopSeen by remember { mutableIntStateOf(0) }
    var revealBottomSeen by remember { mutableIntStateOf(0) }

    /** 当前设备 guid（内存缓存 key 的一半；见 [FileListCache] 的隔离说明）。 */
    val guid = ConnectionCenter.camera?.guidHex.orEmpty()

    /**
     * [entries] 这份列表**属于哪个目录**（L2 修正）。
     *
     * <p>没有它就会闪：切目录那一帧 [entries] 还是上一个目录的，`key(dir)` 又把
     * 列表重建了一遍 —— 用户看到"回到上一层级时列表闪一下"（先闪旧列表、
     * 再换成新目录的缓存）。下面的 [shownEntries] 用它对账：`entries` 不属于当前
     * 目录时，直接拿当前目录的内存缓存（没有就空表）来渲染。
     */
    var loadedDir by remember { mutableStateOf<String?>(null) }

    /**
     * **屏上真正渲染的列表**：永远属于当前目录。
     *
     * <p>`entries` 已属于当前目录 → 就是它；否则（刚切目录、[load] 还没跑完）
     * 用该目录的内存缓存顶一帧（缓存也没有 → 空表 + 亮加载行）。
     * 空表那一帧就是"真在等网络"，加载行照亮 —— 有缓存时则**不亮**
     * （用户定版：有缓存不播加载动画）。
     */
    val shownEntries: List<ObjectRepository.FtpEntry> =
        if (loadedDir == dir) entries
        else FileListCache.get(guid, dir)?.entries ?: emptyList()

    /**
     * **排序后的渲染源**（2.10）：网格/列表、全屏预览的左右切换序列、全选/反选都用它。
     *
     * <p>[shownEntries] 本身语义不动（加载判断、空表判断、翻页阈值继续引用它）——
     * 这里只是给同一份内容换一个**显示顺序**：默认新→旧（[sortDesc]）；
     * 时间戳相同时按文件名兜底（同一秒连拍也要有确定次序）。
     */
    val displayEntries: List<ObjectRepository.FtpEntry> = remember(shownEntries, sortDesc) {
        shownEntries.sortedWith(
            if (sortDesc) {
                compareByDescending<ObjectRepository.FtpEntry> { it.timestamp }.thenBy { it.name }
            } else {
                compareBy<ObjectRepository.FtpEntry> { it.timestamp }.thenBy { it.name }
            },
        )
    }

    /**
     * 列表**整块渐显**（用户定版）：点击文件夹进入下一层 / 返回上一层，列表项目
     * 不是"啪"地出现，而是像列表项自身那样淡入（与 animateItem 的渐显同一手感）。
     *
     * <p>触发点 = **目录（或列表内容）换了一份**那一拍：
     * <ul>
     *   <li>进下一层 / 返回上层 —— <b>有没有缓存都播</b>（用户定版：缓存秒回的那次
     *       同样要有出现动画；不能因为"没等网络"就少一段）；</li>
     *   <li>首次进目录（无缓存）—— 先按住 alpha=0，等第一页落地那一拍再淡入，
     *       动画不会被"等待的那几百毫秒"吃掉；</li>
     *   <li>翻页追加、下拉刷新落地 —— 目录与"空→非空"都没变，键不变 → 不重播
     *       （那些场景各项自己走 animateItem，整块不该再淡一次）。</li>
     * </ul>
     */
    val listFade = remember { Animatable(1f) }

    /**
     * [listFade] 当前值**归属哪个目录**：只有它 == [dir] 时渲染层才用 listFade 的
     * alpha，刚切目录的那一帧（效果还没跑）直接按 0 画。
     *
     * <p>★ 这行是"闪烁"的根治（用户实测"动画会闪烁，很不自然"）：没有它，切目录
     * 的那一帧会先按**上一次动画结束的 alpha=1** 把新列表画出来，下一帧才归零
     * 重淡 —— 一帧亮、一帧灭、再淡入，看起来就是闪。归零必须发生在**同一次组合**
     * 里，不能等效果调度。
     */
    var fadeDir by remember { mutableStateOf<String?>(null) }

    /**
     * ★ 2.11：与 [fadeDir] 同理 —— 记录"本次淡入属于哪个排序方向"。
     *
     * <p>点排序会让分支按 `key(dir, guid, sortDesc)` 重建，但 [fadeDir] 只对比目录
     * 名、**看不出排序变了**：重建后的首帧会按上一次动画结束的 alpha=1 把新顺序直接
     * 画出来（下一帧才归零重淡）——正是"闪烁"的味道。把排序方向也纳入对账，
     * 首帧直接按 0 画。
     */
    var fadeSort by remember { mutableStateOf<Boolean?>(null) }

    // ★ 2.11 §3：`sortDesc` 也作 key —— 点排序那一刻整块列表**重播淡入**（用户定版：
    //   "只需要刷新列表那个列表闪烁一下的动画"），与下面 key(dir, guid, sortDesc) 的
    //   分支重建配合，条目不再走"上下颠倒"的位移动画。
    LaunchedEffect(dir, guid, sortDesc, shownEntries.isEmpty()) {
        fadeDir = dir
        fadeSort = sortDesc
        if (shownEntries.isEmpty()) {
            listFade.snapTo(0f)         // 还在等（空表 + 加载行）：按到 0，内容到了再播
        } else {
            listFade.snapTo(0f)
            listFade.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
    }

    // ★ L4：强制刷新（下拉 / 工具条按钮两个入口一个动作）
    var refreshing by remember { mutableStateOf(false) }
    /** 每次刷新**成功** +1：两个列表分支据此把浏览位置归零（"从头看新列表"）。 */
    var refreshGen by remember { mutableIntStateOf(0) }
    /** 最近一次刷新的目录：refreshGen 只对"它自己的目录"生效（换目录不误伤）。 */
    var refreshedDir by remember { mutableStateOf<String?>(null) }

    /**
     * 加载代次（F4）：每次 [load] 递增；在飞的 load/loadMore 返回后比对自己
     * 出发时的代次，不等就**整批丢弃** —— 防两类串扰：
     * ① 目录 A 在飞的翻页返回时用户已进目录 B（旧实现把 A 的条目追加进 B 的列表）；
     * ② 同一目录"重新加载"与在飞翻页的 nextOffset 竞争（旧实现可能漏页）。
     */
    val loadEpoch = remember { intArrayOf(0) }

    // 传输选择模式
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    /**
     * ★ 2.11 §8：**批量选择锚点**（选择模式里长按的第一步）。
     *
     * <p>长按未选中项 → 选中它并记锚；再长按另一项 → 锚点到该项的**显示序区间**
     * 全部选中（一次批量选择完成，锚点清空）。退出选择模式 → 锚点一并清。
     */
    var batchAnchor by remember { mutableStateOf<String?>(null) }

    /** 退出选择模式（工具条按钮 / 返回键 / 开始传输收尾三条出口统一走它）。 */
    fun exitSelectMode() {
        selecting = false
        selected.clear()
        // 锚点由下面的 LaunchedEffect(selecting) 统一清，这里不重复
    }

    // 退出选择模式（selecting → false）→ 锚点一并清；覆盖一切出口（含以后新增的）。
    LaunchedEffect(selecting) {
        if (!selecting) batchAnchor = null
    }
    // ★ 第三批（§1）：角标数据源 = 当前设备队列的真实状态（与传输/同步页同套 markOf）
    val queueItems = TransferStore.items

    // ★ 第三批（§4）：非选择模式长按 = 弹"下载"确认框（针对该单个文件；文件夹无操作）
    var downloadTarget by remember { mutableStateOf<ObjectRepository.FtpEntry?>(null) }

    var preparing by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf("") }
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** 下载目录预检失败（目录被删/权限回收）→ 弹窗，文件一律不入队（用户定版）。 */
    var dirMissing by remember { mutableStateOf(false) }

    // 全屏预览
    var fullscreenEntry by remember { mutableStateOf<ObjectRepository.FtpEntry?>(null) }

    // 返回键层级化（用户定版）：文件页返回=上一层目录；选择模式中返回=先退
    // 选择。后注册的 BackHandler 优先生效，选择模式因此盖过目录层级。
    androidx.activity.compose.BackHandler(enabled = dir != "/") {
        dir = ObjectRepository.parentOf(dir)
    }
    androidx.activity.compose.BackHandler(enabled = selecting) {
        exitSelectMode()
    }

    /** 目录条目 → 缩略图缓存键（load/loadMore 共用一份，别再各写一遍）。 */
    fun thumbKeysOf(list: List<ObjectRepository.FtpEntry>): List<ThumbStore.ObjectCacheKey> =
        list.filter { !it.isDir && it.ext in PREVIEWABLE_EXT }.map {
            ThumbStore.ObjectCacheKey(
                    path = it.path,
                size = it.size,
                mtime = it.timestamp,
            )
        }

    /**
     * 加载目录第一页。
     *
     * <p>★ F4（2.6 修复）：结果落地前校验代次（[loadEpoch]）—— 在飞的旧请求返回即
     * 丢弃；失败**自动重试**（[LOAD_MAX_ATTEMPTS] 次、间隔 [LOAD_RETRY_DELAY_MS]），
     * 相机排队/瞬时抖动不再直接把红字甩给用户；重试仍失败才置错误文案（且只在
     * "仍是本代"时置，不覆盖新目录的状态）。
     *
     * <p>★ L2（2.6）：**先看内存缓存**（[FileListCache]，用户定版）——
     * <ol>
     *   <li>有缓存：列表**立刻铺上**且**不摆加载动画**（屏上非空 → 加载行不亮，
     *       见 [shownEntries]）——屏上是"上次的列表"，指纹校验与（若变了）重载
     *       都在背后进行；</li>
     *   <li>指纹（[dirFingerprint]，2.8 起 = **目录条目计数**）一致 → 缓存即最终
     *       结果，零分页拉取；</li>
     *   <li>没缓存 / 有变动 / 强制刷新（[force]）→ 全量重拉（F4 重试骨架照旧），
     *       成功后连同指纹写回缓存。</li>
     * </ol>
     *
     * @return 结果是否**落地**（true = 用了缓存或加载成功；false = 被更新的 load
     *   取代、或重试耗尽仍失败）。L4 的强制刷新靠它决定要不要归零滚动位置。
     */
    suspend fun load(d: String, force: Boolean = false): Boolean {
        val h = host ?: return false
        val myEpoch = ++loadEpoch[0]
        // ★ 进目录先查缓存：有缓存 → 立即铺列表、不亮加载行（背后校验/重载）；
        //   没缓存（首次进这个目录）→ 清空列表、亮加载行（那是真·等待）。
        val cached = FileListCache.get(guid, d)
        if (cached != null && cached.entries.isNotEmpty()) {
            entries = cached.entries
            nextOffset = cached.nextOffset
            hasMore = cached.hasMore
            // ★ 2.10：缓存游标带方向（倒序游标 ≠ 正序游标，见 FileListCache.pagedDesc）。
            //   方向不匹配时游标不可信 → 置零并放行到下面的全量重载（指纹捷径也
            //   会被 pagedDesc 校验挡掉）；entries 照铺（即时上屏不回退）。
            if (cached.pagedDesc != sortDesc) {
                nextOffset = 0
                hasMore = true
            }
            // 缩略图请求照走一遍（幂等：已缓存的直接跳，断连期间丢的图顺带补上）
            ThumbStore.request(thumbKeysOf(cached.entries))
            // ★ 2.10-fix：补漏——仍缺预览的条目补进无感通道（失败潮留下的洞自动重填）
            ThumbStore.ensurePreviews(thumbKeysOf(cached.entries))
        } else {
            // 没有缓存：把上一个目录的列表**立刻作废**。不清的话，`entries` 会带着
            // 旧目录的内容活到本次加载落地，配合 nextOffset/hasMore 还会让
            // loadMore 拿旧偏移去翻页（[loadedDir] + shownEntries 双保险）。
            entries = emptyList()
            nextOffset = 0
            hasMore = false
        }
        loadedDir = d
        loading = true
        loadError = null
        try {
            // ① 指纹探测（2.8 起 = 目录条目计数探针，见 [dirFingerprint]）
            val fp = dirFingerprint(h, d)
            if (loadEpoch[0] != myEpoch) return false      // 已被更新的 load 取代
            if (!force && cached != null && fp != null && fp == cached.dirStamp && cached.pagedDesc == sortDesc) {
                // ② 条目数没变 → 缓存即最终结果（列表已在上面铺好），零分页拉取
                return true
            }
            // ③ 有变动 / 无缓存 / 强制刷新 → 原有的全量加载（F4 重试骨架照旧）
            for (attempt in 0 until LOAD_MAX_ATTEMPTS) {
                try {
                    val page = withContext(Dispatchers.IO) {
                        ObjectRepository.listPage(h, d, 0, 256, reverse = sortDesc)
                    }
                    if (loadEpoch[0] != myEpoch) return false   // 已被更新的 load 取代：丢弃
                    entries = page.entries
                    loadedDir = d
                    nextOffset = page.nextOffset
                    hasMore = page.hasMore
                    // ★ L2：写入内存缓存。**复用旧对象**（有的话）——滚动位置记在
                    //   同一对象上，新建会把用户的位置抹掉。
                    val c = cached ?: FileListCache.DirCache(emptyList(), 0, false, FileListCache.STAMP_UNKNOWN)
                    c.entries = page.entries
                    c.nextOffset = page.nextOffset
                    c.hasMore = page.hasMore
                    // 指纹：本次探测到的条目数；探测不到（根目录/探测失败）用哨兵 = 不可命中
                    c.dirStamp = fp ?: FileListCache.STAMP_UNKNOWN
                    // ★ 2.10：游标随本次的分页方向入缓存（loadMore 与回访都按同方向续翻）
                    c.pagedDesc = sortDesc
                    FileListCache.put(guid, d, c)
                    // 本目录图片文件按需请求小缩略图
                    ThumbStore.request(thumbKeysOf(page.entries))
                    // ★ 2.10-fix：同款补漏（预览）
                    ThumbStore.ensurePreviews(thumbKeysOf(page.entries))
                    return true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 切页/换目录导致的取消不是错误，放行（此前取消异常的 message
                    // 会被当成错误显示成带 scope 字样的红字）
                    throw e
                } catch (e: Exception) {
                    android.util.Log.d("FilesScreen", "目录读取失败(attempt=${attempt + 1}): ${e.message}")
                    if (attempt < LOAD_MAX_ATTEMPTS - 1) {
                        delay(LOAD_RETRY_DELAY_MS)
                        if (loadEpoch[0] != myEpoch) return false
                    } else if (loadEpoch[0] == myEpoch) {
                        loadError = "目录读取失败"
                    }
                }
            }
            return false
        } finally {
            // 只有仍是本代才能清加载态（否则会把新 load 刚置的 true 清掉）
            if (loadEpoch[0] == myEpoch) loading = false
        }
    }

    /**
     * 加载下一页并追加。
     *
     * <p>★ F4（2.6 修复）：
     * <ol>
     *   <li>**闭包目录守卫**：返回后若已切到别的目录，整页丢弃 —— 旧实现会把
     *       目录 A 的后续页追加进目录 B 的列表（跨目录污染）；</li>
     *   <li>**代次守卫**（[loadEpoch]）：同目录的"重新加载"也会让在飞的旧翻页作废，
     *       防 nextOffset 竞争导致漏页；</li>
     *   <li>失败**自动重试**（≤[LOAD_MORE_MAX_ATTEMPTS] 次、1s 间隔）：红字不再
     *       "卡死等下一下滚动"；</li>
     *   <li>nextOffset 不前进（异常相机端）→ 直接收束 `hasMore=false`，防死循环。</li>
     * </ol>
     */
    suspend fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        // 屏上的列表必须已经属于当前目录：切目录后的窗口期（load 还没跑完）
        // nextOffset/hasMore 还是上一个目录的，拿去翻页会翻错页（见 [loadedDir]）。
        if (loadedDir != dir) return
        val h = host ?: return
        val d = dir                        // ★ 闭包快照：本次翻的是哪个目录
        val myEpoch = loadEpoch[0]
        val requestedOffset = nextOffset
        loadingMore = true
        try {
            for (attempt in 0 until LOAD_MORE_MAX_ATTEMPTS) {
                try {
                    val page = withContext(Dispatchers.IO) {
                        ObjectRepository.listPage(h, d, requestedOffset, 256, reverse = sortDesc)
                    }
                    // ★ 旧目录 / 旧代次：整页丢弃（不追加、不动 offset）
                    if (loadEpoch[0] != myEpoch || dir != d) return
                    val existing = entries.mapTo(HashSet()) { it.path }
                    entries = entries + page.entries.filter { existing.add(it.path) }
                    nextOffset = page.nextOffset
                    hasMore = page.hasMore && page.nextOffset > requestedOffset
                    loadError = null
                    // ★ L2：把追加后的**完整列表**回写缓存 —— "翻到第 3 页 → 切走 →
                    //   回来"恢复的就是 3 页的完整列表 + 滚动位置，往下翻从第 4 页开始。
                    //   ★ 2.10：翻页延续 load 时的方向（loadMore 的请求方向与 load 一致，
                    //   缓存里记的就是它）。
                    FileListCache.get(guid, d)?.let {
                        it.entries = entries
                        it.nextOffset = nextOffset
                        it.hasMore = hasMore
                    }
                    ThumbStore.request(thumbKeysOf(page.entries))
                    // ★ 2.10-fix：翻页同样补漏（预览）
                    ThumbStore.ensurePreviews(thumbKeysOf(page.entries))
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.d("FilesScreen", "loadMore failed(attempt=${attempt + 1}): ${e.message}")
                    if (attempt < LOAD_MORE_MAX_ATTEMPTS - 1) {
                        delay(LOAD_MORE_RETRY_DELAY_MS)
                        if (loadEpoch[0] != myEpoch || dir != d) return
                    } else if (loadEpoch[0] == myEpoch && dir == d) {
                        loadError = "目录读取失败"
                    }
                }
            }
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(dir, host) { load(dir) }

    /**
     * ★ 2.8：**停留期间自动刷新**——后台运行时代的旧前提"软件运行时 SD 卡内的
     *   文件不会变化"已过时（相机在后台拍照写卡），文件页停留期间周期性校验
     *   目录指纹，变了就在**不丢滚动位置**的前提下重载列表。
     *
     * <p>节奏与代价：每 [AUTO_REFRESH_INTERVAL_MS]（6s）一条计数探针（几十字节
     *   的小包）；指纹没变 = 零分页拉取，控制面几乎零占用。计数变了（新照片/
     *   删除）→ 走 [load] 重载（首页全拉 + 缓存回写），**不带 force**、不归零
     *   滚动位置 —— 与"切走再回来"同一行为；animateItem 让新条目滑入。
     *
     * <p>与下拉刷新（[forceRefresh]）的分工：那个是**手动 + 跳过指纹 + 归零滚动**
     *   的确定性出口；这个是自动、保位置、走指纹的静默同步。
     *
     * <p>暂停条件：翻页中 / 刷新中 / 加载中不动（避免与在飞请求的代次竞争）；
     *   未连接时 [dirFingerprint] 自会失败（null → 视为有变动 → load 内部
     *   重试骨架处理），轮询本身无害。全屏预览开着时也暂停 —— 用户正在看图，
     *   列表在背后换了内容反而突兀，退出预览后下一轮补上。
     */
    LaunchedEffect(dir, host, guid, fullscreenEntry == null) {
        if (host == null) return@LaunchedEffect
        while (true) {
            delay(AUTO_REFRESH_INTERVAL_MS)
            if (loading || loadingMore || refreshing) continue
            // ★ 2.10-fix：预览补漏**不依赖指纹变化** —— 每轮（6s）对当前已加载列表
            //   补一次"仍缺预览"的条目（失败潮留下的洞自动重填；纯本地浅检查 + 去重，
            //   便宜）。指纹只决定要不要重拉列表，与预览补漏是两件事。
            ThumbStore.ensurePreviews(thumbKeysOf(entries))
            val h = host ?: return@LaunchedEffect
            val fp = dirFingerprint(h, dir)
            if (fp == null) continue
            val cached = FileListCache.get(guid, dir) ?: continue
            if (fp != cached.dirStamp) {
                android.util.Log.d("FilesScreen", "停留期间指纹变化（${cached.dirStamp} → $fp），自动刷新：$dir")
                // ★ 2.11 §2："新文件自动露出"判定 —— 必须在 load **之前**拍下这两个快照
                //   （load 会换掉 entries；边缘状态要的是"用户当时在不在那一端"）。
                val before = entries.toList()
                val wasAtTop = atTop
                val wasAtBottom = atBottom
                if (load(dir)) {
                    val beforePaths = before.mapTo(HashSet()) { it.path }
                    val afterPaths = entries.mapTo(HashSet()) { it.path }
                    val removed = before.any { it.path !in afterPaths }   // 有删除 → 保守不滚
                    val added = entries.any { it.path !in beforePaths }
                    if (added && !removed) {
                        // 新照片必是最新的：sortDesc（新→旧）时落在**头部**，
                        // 否则落在**尾部**；只有用户原本就停在那端才自动露出。
                        if (sortDesc && wasAtTop) revealTopTick++
                        if (!sortDesc && wasAtBottom) revealBottomTick++
                    }
                }
            }
        }
    }

    /**
     * L4 强制刷新（下拉 / 工具条按钮两个入口一个动作）：**跳过指纹判断**直接全量
     * 重拉当前目录 —— 既是"SD 上真有变动但目录 mtime 没赶上"的手动兜底，也是用户
     * "我就想刷一下"的确定性出口。成功后写缓存（新指纹 + 新列表）并把该目录的
     * 浏览位置归零（"从头看新列表"）。
     *
     * 防重入：加载中/翻页中忽略；刷新中用户点进子目录 → [load] 的代次机制天然
     * 作废本次结果，[refreshing] 由 finally 复位。
     */
    fun forceRefresh() {
        if (loading || loadingMore || refreshing || host == null) return
        val d = dir
        refreshing = true
        scope.launch {
            try {
                if (load(d, force = true) && dir == d) {
                    FileListCache.get(guid, d)?.let {
                        it.gridScroll = 0
                        it.listScroll = 0
                    }
                    refreshedDir = d
                    refreshGen++
                }
            } finally {
                refreshing = false
            }
        }
    }

    fun toggle(path: String) {
        if (!selected.remove(path)) selected.add(path)
    }

    /**
     * 长按（★ 2.11 §8 用户定版，语义重写）。
     *
     * <p>未处于选择模式 → **无操作**（旧版是"长按进选择"，现在进入只走工具条按钮）。
     * 选择模式里两步批量选择：
     * <ol>
     *   <li>第一步：长按**未选中**的文件 → 选中它并记为锚点；长按**已选中**的
     *       文件 → 取消该项并清锚（对称操作，D5）；</li>
     *   <li>第二步：长按另一个文件 → 锚点到它的**显示序区间（两端含）**全部选中，
     *       一次批量选择完成（锚点清空）。</li>
     * </ol>
     * 批量只作用于长按；单击 [toggle] 完全不受影响。
     */
    fun longPress(path: String) {
        if (!selecting) return                  // 非选择模式：长按无操作（需求原文）
        val anchor = batchAnchor
        if (anchor == null) {
            toggle(path)
            batchAnchor = if (selected.contains(path)) path else null
        } else {
            val a = displayEntries.indexOfFirst { it.path == anchor }
            val b = displayEntries.indexOfFirst { it.path == path }
            if (a >= 0 && b >= 0) {
                for (e in displayEntries.subList(minOf(a, b), maxOf(a, b) + 1)) {
                    if (!selected.contains(e.path)) selected.add(e.path)
                }
            }
            batchAnchor = null                  // 一次批量选择完成
        }
    }

    /**
     * 全选 / 反选合并成**一个按钮的状态切换**（2.10，用户定版）：
     * 无选择 → 全选 [displayEntries]；已有选择 → 反选（原 invertSelection 语义）。
     */
    fun toggleSelectAll() {
        selecting = true
        val universe = displayEntries.map { it.path }
        if (selected.isEmpty()) {
            selected.addAll(universe)
        } else {
            val cur = selected.toHashSet()
            selected.clear()
            for (p in universe) if (p !in cur) selected.add(p)
        }
    }

    /**
     * ★ 第三批（§4）：[targets] = 指定条目名单（非选择模式长按单文件"下载"）——
     * null 时走当前选择集合（原行为不变）；点下载不进入选择模式、不清现有选择。
     */
    fun startTransfer(targets: List<ObjectRepository.FtpEntry>? = null) {
        if (preparing || host == null) return
        val activeHost = host
        preparing = true
        scanProgress = "正在扫描…"
        scanJob = scope.launch {
            // ★ 下载目录预检（用户定版：点"开始传输"那一刻就查，不存在则弹窗、
            //   文件一律不进传输队列）：SAF query 是阻塞调用，放 IO 线程。
            val dirOk = withContext(Dispatchers.IO) {
                StorageSink.downloadDirAvailable(context, SettingsRepo.downloadTreeUri)
            }
            if (!dirOk) {
                preparing = false
                scanJob = null
                scanProgress = ""
                dirMissing = true
                return@launch
            }
            // 待扫描清单先在本线程（主线程）拍快照：扫描要挪到 IO 线程去做，
            // 那边不能再读这两个 Compose 状态列表。
            // ★ 第三批（§4）：targets（长按单文件）优先；否则选择集合（原行为）。
            val entriesSnapshot = targets ?: shownEntries.toList()
            val selectedSnapshot = targets?.map { it.path } ?: selected.toList()
            try {
                // ★ 网络扫描必须离开主线程：listPage() 是同步阻塞调用（内部在
                //   ioExecutor 上等结果），选中含子目录的条目时这里会在主线程跑
                //   几十上百次网络往返 —— 界面完全冻结（连进度条和取消按钮都画不
                //   出来），目录一大就直接 ANR。2.0 是在 Dispatchers.IO 里收集的，
                //   2.5 重写成"分批 + 进度 + 可取消"时把这层上下文丢了。
                withContext(Dispatchers.IO) {
                    collectAndEnqueue(
                        host = activeHost,
                        entries = entriesSnapshot,
                        selected = selectedSnapshot,
                        enqueue = { batch ->
                            val items = batch.map {
                                TransferItem(
                                    id = it.path, name = it.name, path = it.path,
                                    size = it.size, mtime = it.timestamp,
                                )
                            }
                            // 入队改的是 Compose 状态 → 回到主线程写
                            withContext(Dispatchers.Main) { TransferStore.enqueueAll(items) }
                        },
                        onProgress = { dirs, found ->
                            withContext(Dispatchers.Main) {
                                scanProgress = "已扫描 $dirs 个目录 · 已加入 $found 个文件"
                            }
                        },
                    )
                }
                // 以下是 UI 收尾（改状态 + 跳页），回到主线程后执行
                preparing = false
                scanJob = null
                scanProgress = ""
                if (ConnectionCenter.host != null) {
                    DownloadService.start(context)
                    selecting = false
                    selected.clear()
                    onGoTransfers()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消：已经分批入队的任务保留，尚未入队的丢弃。
                preparing = false
                scanJob = null
                scanProgress = ""
                throw e
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ★ 第三批（§8）：删掉 `if (preparing) Surface(…)` 顶部悬浮扫描条 ——
        //   点"开始传输"瞬间 scanProgress 先回退空串再被覆盖，这块 secondaryContainer
        //   暗色圆角矩形会在顶部闪一下（用户截图根因）。扫描进度 + 取消并入底部 FAB
        //  （见下方 FAB 的 preparing 分支），顶部全程不动。
        Column(Modifier.fillMaxSize()) {
            // ===== 路径 + 动作：一整条 28dp 圆角的药丸工具条 =====
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ★ 2.11（第二轮）：面包屑当前层级永不被挤走 —— 目录一变就滚到最右。
                    //   key 带 maxValue：首帧未测量时滚不到底（maxValue 是占位值），
                    //   布局完成后 maxValue 变化 → effect 自动重启补滚一次 → 最终停在
                    //   最右；用户手动滚动不改 maxValue、不重启，不会被拽回。
                    val crumbScroll = rememberScrollState()
                    LaunchedEffect(dir, crumbScroll.maxValue) {
                        crumbScroll.scrollTo(crumbScroll.maxValue)
                    }
                    Row(
                        Modifier.weight(1f).horizontalScroll(crumbScroll),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ObjectRepository.breadcrumbOf(dir).forEachIndexed { idx, (name, path) ->
                            if (idx > 0) {
                                Text(
                                    "›",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                            Text(
                                name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                color = if (path == dir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    // 先裁成小圆角再 clickable：水波纹才是圆角块而不是方角块；
                                    // padding 放在 clickable **之后**，让可点区域含留白
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { dir = path }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                            )
                        }
                    }
                    // ★ 2.11 §8（用户定版）：[进入/退出选择模式] 按钮 —— 未选择模式时
                    //   全选按钮**隐藏**，这个位置就是它；进入选择模式后全选图标从它
                    //   右侧展开（动作组右对齐 → 本按钮视觉上左移一格、腾出的空间
                    //   正好被全选图标占住）。
                    val selModeSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = {
                            if (selecting) exitSelectMode() else selecting = true
                        },
                        enabled = displayEntries.isNotEmpty() || selecting,
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
                    // 全选 / 反选：**仅选择模式出现**（横向展开 + 淡入；退出时收回）。
                    AnimatedVisibility(
                        visible = selecting,
                        enter = fadeIn(Motion.effectsFast()) +
                            expandHorizontally(Motion.spatialDefault()),
                        exit = fadeOut(Motion.effectsFast()) +
                            shrinkHorizontally(Motion.spatialFast()),
                    ) {
                        val selAllSource = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { toggleSelectAll() },
                            enabled = displayEntries.isNotEmpty(),
                            interactionSource = selAllSource,
                        ) {
                            MsIcon(
                                icon = if (selected.isEmpty()) MsIcon.SELECT_ALL else MsIcon.SELECT_INVERSE,
                                contentDescription = if (selected.isEmpty()) "全选" else "反选",
                                modifier = Modifier.size(22.dp),
                                size = 22.dp,
                                interactionSource = selAllSource,
                            )
                        }
                    }
                    // 排序按钮（2.10，原反选位置）：点击在 新→旧 / 旧→新 之间翻转
                    val sortSource = remember { MutableInteractionSource() }
                    // ★ 2.11（第二轮回归）：图标随方向**上下翻面**（scaleY 1↔-1，spatial
                    //   弹簧），再点一次翻回来 —— 与"列表闪烁"是同一个点击的两路反馈。
                    //   （第一轮是平面 rotate 180°，第二轮用户定版改为垂直镜像。）
                    val sortFlip by animateFloatAsState(
                        targetValue = if (sortDesc) 1f else -1f,
                        animationSpec = Motion.spatialDefault(),
                        label = "sortFlip",
                    )
                    IconButton(
                        onClick = {
                            // ★ 2.10：切换排序 = 切换**服务端分页方向**（reverse=sortDesc）。
                            //   列表内容不重拉——displayEntries 本就按 timestamp 排序渲染；
                            //   变的是"继续往下翻页时从哪一端增量"：
                            //   新→旧（sortDesc=true）→ reverse=true，从最新端往旧翻；
                            //   旧→新 → reverse=false（服务端原生正序）。
                            //   缓存里的旧方向 nextOffset 对新方向无意义 → 归零重取首页，
                            //   已加载内容保留（entries 不清，只重置分页游标）。
                            sortDesc = !sortDesc
                            nextOffset = 0
                            hasMore = false
                            // ★ 2.11：顺序整体翻转 → 滚动位置**立即**归零（分支重建时
                            //   initialIndex 取到的就是 0；不用等下面的 load 回来才跳顶，
                            //   否则网速一慢就能看到"先停在旧位置、再突然跳顶"）。
                            FileListCache.get(guid, dir)?.let {
                                it.gridScroll = 0
                                it.listScroll = 0
                            }
                            scope.launch {
                                // 轻量重取：只拉首页建立新方向的游标（load 的缓存指纹
                                // 命中路径不重拉，这里显式 force 保证游标重建）
                                if (load(dir, force = true)) {
                                    FileListCache.get(guid, dir)?.let {
                                        it.entries = entries
                                        it.nextOffset = nextOffset
                                        it.hasMore = hasMore
                                    }
                                    // ★ 顺序整体翻转，旧滚动位置不再对应任何条目：
                                    //   归零（与 forceRefresh 同款机制，见 refreshGen）。
                                    refreshedDir = dir
                                    refreshGen++
                                }
                            }
                        },
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
                    IconButton(onClick = { gridView = !gridView }, interactionSource = viewSource) {
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

            // 选中提示条：进/出选择模式时**展开收起**（带弹簧），下面的列表跟着平滑
            // 下移/上移，而不是"啪"地多出一行把内容顶下去
            AnimatedVisibility(
                visible = selecting,
                enter = fadeIn(Motion.effectsFast()) + expandVertically(Motion.spatialDefault()),
                exit = fadeOut(Motion.effectsFast()) + shrinkVertically(Motion.spatialFast()),
            ) {
                Surface(
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "已选 ${selected.size} 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            // 加载行：① 下拉刷新进行中**不显示** —— 那枚图标已经在缝隙里转（同一个
            // 图标），再冒出一行会把列表整体往下顶一格（用户实测"触发刷新时列表
            // 会弹一下"的直接机制）；② 屏上已有内容（缓存铺的列表）时**不显示**
            // （用户定版：有缓存就不播加载动画 —— 等待发生在背后）。
            // 判据用 shownEntries 而非 loading：切目录那一帧它与缓存对账，
            // 有缓存 → 非空 → 不亮；没缓存 → 空 → 亮（那才是真在等）。
            if (loading && !refreshing && shownEntries.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // 用户定版：原来的小圆圈（CircularProgressIndicator）换成
                    // M3E 的多边形旋转加载图标，尺寸与原来一档（22dp）。
                    androidx.compose.material3.LoadingIndicator(Modifier.size(22.dp))
                }
            }
            loadError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            // 未连接时的空态：用户定版"未连接也留在主界面、导航栏可用"，所以文件页
            // 也会在没连接时被打开。没有这一行就是个空白网格，用户只会以为目录选错了。
            if (host == null && !loading) {
                HintText("未连接相机，无法浏览文件")
            }

            // ★ L4 下拉刷新（用户定版形态）：下拉 → **整块文件列表跟着下移**，顶部
            //   缝隙里露出 M3E 的多边形旋转加载图标（material3 的 LoadingIndicator，
            //   形状 morph + 自转）；松手过阈值 → 图标持续旋转、走强制全量重拉；
            //   加载完成 → 位移归零、界面回弹（state 自己动画到 hidden）。
            //
            //   两个实现要点：
            //   ① 指示器用 PullToRefreshDefaults.LoadingIndicator（与库同一套 M3E
            //      形状），不自己造手势轮子；threshold 与盒子传同一个值 —— 状态的
            //      distanceFraction 就是按它归一化的；
            //   ② 内容位移取 distanceFraction × 阈值（库已施加 0.5 阻尼）—— 与指示器
            //      同一进度量，两者同步位移；回弹由 state→0 的动画自然带出。
            val ptrState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
            // 触发阈值比库默认（80dp）小一档：轻拉即可触发（用户定版）。
            // 盒子与"缝隙高度"必须用**同一个值** —— 状态的 distanceFraction
            // 就是按它归一化的，两边不一致会错位。
            val ptrThreshold = PULL_REFRESH_THRESHOLD
            val density = androidx.compose.ui.platform.LocalDensity.current
            val ptrThresholdPx = with(density) { ptrThreshold.toPx() }
            val ptrSpinnerPx = with(density) { PULL_SPINNER_SIZE.toPx() }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { forceRefresh() },
                state = ptrState,
                enabled = !loading && !loadingMore,
                threshold = ptrThreshold,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    // 指示器（用户定版）：M3E 多边形加载图标，**小号**（22dp，不套
                    // PullToRefreshDefaults 那个 48dp 大容器 —— 那正是"太大且错位"
                    // 的来源）；位置钉在下拉缝隙的正中（缝隙高 = distanceFraction
                    // × 阈值，图标在其中居中）。
                    //
                    // 动静分档（用户定版："刚拉下没触发刷新时应该是静止的，触发刷新后
                    // 才开始旋转"）：
                    //  · 拖动阶段用**定量**变体（progress = 下拉进度）—— 形状随手指
                    //    走、自己不转，松手停在哪就是哪；
                    //  · 松手过阈值进入刷新后换**不定量**变体 —— 持续旋转。
                    //  两者同为 22dp 的多边形，切换处形状连续，看不出接缝。
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer {
                                val gap = ptrState.distanceFraction * ptrThresholdPx
                                translationY = ((gap - ptrSpinnerPx) / 2f).coerceAtLeast(0f)
                                alpha = if (refreshing) 1f
                                else ptrState.distanceFraction.coerceIn(0f, 1f)
                            },
                    ) {
                        if (refreshing) {
                            androidx.compose.material3.LoadingIndicator(
                                modifier = Modifier.size(PULL_SPINNER_SIZE),
                            )
                        } else {
                            androidx.compose.material3.LoadingIndicator(
                                progress = { ptrState.distanceFraction.coerceIn(0f, 1f) },
                                modifier = Modifier.size(PULL_SPINNER_SIZE),
                            )
                        }
                    }
                },
            ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = ptrState.distanceFraction * ptrThresholdPx
                },
            ) {
            if (gridView) {
                // ★ L3：key(dir, guid) —— 切目录/切页重建时 LazyGridState 随之重建，
                //   并从 FileListCache 取**该目录自己的**滚动位置。位置必须存在
                //   Composable 之外：remember 的状态在销毁（含 AnimatedContent 切页）
                //   时丢弃。列表分支同构（listScroll）。
                // ★ 2.11 §3：sortDesc 也是 key —— 点排序时整个 LazyGrid 重建，
                //   条目**不走 animateItem 的跨位置弹簧**（那正是用户嫌"上下颠倒"的
                //   复杂动画）；配合上面 listFade 的淡入重播 = "列表闪一下"。
                key(dir, guid, sortDesc) {
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                    initialFirstVisibleItemIndex = FileListCache.get(guid, dir)?.gridScroll ?: 0,
                )
                // 滚动位置持续回写（每个目录一层，互不覆盖 —— "记住每一层级"）；
                // ★ 2.11 §2：顺带维护"在不在两端"—— 自动露出只在用户停在那端时触发
                //   （totalItemsCount 是布局状态，列表增减也会让本流重新发射）。
                LaunchedEffect(dir, guid) {
                    snapshotFlow {
                        val info = gridState.layoutInfo
                        Triple(
                            gridState.firstVisibleItemIndex,
                            info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                            info.totalItemsCount,
                        )
                    }.collect { (first, last, total) ->
                        FileListCache.get(guid, dir)?.gridScroll = first
                        atTop = total == 0 || first == 0
                        atBottom = total > 0 && last >= total - 1
                    }
                }
                // ★ L4：强制刷新成功 → 回到顶部（刷新的语义 = 从头看新列表）
                LaunchedEffect(refreshGen) {
                    if (refreshGen > 0 && refreshedDir == dir) gridState.scrollToItem(0)
                }
                // ★ 2.11 §2/§5：双击标题（topTick）与"新文件露出"（revealTopTick）→
                //   回顶；revealBottomTick → 到底（仅用户本来就在那一端时被触发）。
                //   消费基线见 [topTickSeen] 的说明（旧信号不因分支重建重放）。
                LaunchedEffect(topTick, revealTopTick) {
                    if (topTick > topTickSeen || revealTopTick > revealTopSeen) {
                        topTickSeen = topTick
                        revealTopSeen = revealTopTick
                        if (displayEntries.isNotEmpty()) gridState.animateScrollToItem(0)
                    }
                }
                LaunchedEffect(revealBottomTick) {
                    if (revealBottomTick > revealBottomSeen) {
                        revealBottomSeen = revealBottomTick
                        if (displayEntries.isNotEmpty()) {
                            gridState.animateScrollToItem(displayEntries.lastIndex)
                        }
                    }
                }
                val nearEnd by androidx.compose.runtime.derivedStateOf {
                    // ★ F4：阈值提前到"还剩 6 项"（掩盖网络延迟）；空列表不触发
                    //   （防"空页 + hasMore=true"的异常组合下 loadMore 死循环）
                    shownEntries.isNotEmpty() &&
                        (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                        shownEntries.lastIndex - 6
                }
                LaunchedEffect(nearEnd, hasMore) {
                    if (nearEnd && hasMore) loadMore()
                }
                LazyVerticalGrid(
                    // ★ 一排两张（用户定版：原九宫格三列）。列表项要显示"文件名 + 修改时间"，
                    //   三列时每格不到 110dp，文件名和日期都会被截成省略号，看不到完整信息。
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    // 整块渐显（见 [listFade] / [fadeDir] / [fadeSort]）：只改绘制层 alpha，不碰布局
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                        .graphicsLayer {
                            alpha = if (fadeDir == dir && fadeSort == sortDesc) listFade.value else 0f
                        },
                    contentPadding = PaddingValues(bottom = FloatingNavInset, top = 4.dp),
                ) {
                    gridItems(displayEntries, key = { it.path }) { entry ->
                        GridCell(
                            modifier = Modifier.animateItem(
                                placementSpec = Motion.spatialDefault(),
                                fadeInSpec = Motion.effectsDefault(),
                                fadeOutSpec = Motion.effectsFast(),
                            ),
                            entry = entry,
                            selecting = selecting,
                            isSelected = selected.contains(entry.path),
                            onToggle = { toggle(entry.path) },
                            // 选择模式下的双击 = 看大图（只是看，不改选中集合）；
                            // 文件夹/非图片双击无预览意义，不响应
                            onDoubleClick = {
                                if (!entry.isDir && entry.ext in PREVIEWABLE_EXT) fullscreenEntry = entry
                            },
                            // ★ 第三批（§4）：非选择模式长按 = 弹"下载"确认框（只对文件；
                            //   文件夹长按无操作）。选择模式内仍是批量选择（§8 语义不变）。
                            onLongClick = if (selecting) ({ longPress(entry.path) })
                                else ({ if (!entry.isDir) downloadTarget = entry }),
                            onClick = {
                                if (entry.isDir) dir = entry.path
                                else if (entry.ext in PREVIEWABLE_EXT)
                                    fullscreenEntry = entry
                            },
                            // ★ 第三批（§1）：队列状态角标（与传输/同步页同套 markOf）
                            mark = markOf(queueItems, entry.path, entry.size, entry.timestamp),
                        )
                    }
                }
                }   // key(dir, guid, sortDesc)
            } else {
                key(dir, guid, sortDesc) {   // ★ L3 同网格分支（位置字段用 listScroll）；§3 同款 sortDesc 键
                val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                    initialFirstVisibleItemIndex = FileListCache.get(guid, dir)?.listScroll ?: 0,
                )
                // 滚动位置持续回写（列表模式自己的字段）；★ 2.11 §2 同网格分支维护两端状态
                LaunchedEffect(dir, guid) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        Triple(
                            listState.firstVisibleItemIndex,
                            info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                            info.totalItemsCount,
                        )
                    }.collect { (first, last, total) ->
                        FileListCache.get(guid, dir)?.listScroll = first
                        atTop = total == 0 || first == 0
                        atBottom = total > 0 && last >= total - 1
                    }
                }
                // ★ L4：强制刷新成功 → 回到顶部
                LaunchedEffect(refreshGen) {
                    if (refreshGen > 0 && refreshedDir == dir) listState.scrollToItem(0)
                }
                // ★ 2.11 §2/§5：双击标题与"新文件露出" → 回顶；revealBottomTick → 到底
                //   （消费基线同网格分支，见 [topTickSeen] 的说明）。
                LaunchedEffect(topTick, revealTopTick) {
                    if (topTick > topTickSeen || revealTopTick > revealTopSeen) {
                        topTickSeen = topTick
                        revealTopSeen = revealTopTick
                        if (displayEntries.isNotEmpty()) listState.animateScrollToItem(0)
                    }
                }
                LaunchedEffect(revealBottomTick) {
                    if (revealBottomTick > revealBottomSeen) {
                        revealBottomSeen = revealBottomTick
                        if (displayEntries.isNotEmpty()) {
                            listState.animateScrollToItem(displayEntries.lastIndex)
                        }
                    }
                }
                val nearEnd by androidx.compose.runtime.derivedStateOf {
                    // ★ F4：阈值提前到"还剩 6 项"；空列表不触发（同网格分支的说明）
                    shownEntries.isNotEmpty() &&
                        (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                        shownEntries.lastIndex - 6
                }
                LaunchedEffect(nearEnd, hasMore) {
                    if (nearEnd && hasMore) loadMore()
                }
                // ★ 「几个小块拼成一个大块」（用户定版，照已配对相机那一页的样子）：
                //   行与行之间留库自带的分段缝（`SegmentedGap`），每行的圆角由它在组里的
                //   位置决定（首段上圆、末段下圆、中间两角方），拼起来是**一整块被切开的卡**。
                //   顺带把选中高亮一并治了：高亮是画在每一段自己那块面上的，段间有缝，
                //   相邻两项同时选中也不会连成一片（旧版是等宽圆角块紧挨着，两块高亮
                //   一挨上边缘就糊在一起 —— 用户反馈"太丑了、边缘连到一起"）。
                LazyColumn(
                    // 整块渐显（同网格分支，见 [listFade] / [fadeDir] / [fadeSort]）
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (fadeDir == dir && fadeSort == sortDesc) listFade.value else 0f
                    },
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = FloatingNavInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    val count = displayEntries.size
                    itemsIndexed(displayEntries, key = { _, e -> e.path }) { index, entry ->
                        EntryRow(
                            // 列表项的增删与挪位交给弹簧（换目录时整片列表重排，
                            // 有它才是"滑过去"而不是"跳过去"）
                            modifier = Modifier.animateItem(
                                placementSpec = Motion.spatialDefault(),
                                fadeInSpec = Motion.effectsDefault(),
                                fadeOutSpec = Motion.effectsFast(),
                            ),
                            shape = segmentShape(index, count),
                            entry = entry,
                            selecting = selecting,
                            isSelected = selected.contains(entry.path),
                            onToggleSelect = { toggle(entry.path) },
                            // 与九宫格同款：选择模式下双击 = 看大图（文件夹不响应）
                            onDoubleClick = {
                                if (!entry.isDir && entry.ext in PREVIEWABLE_EXT) fullscreenEntry = entry
                            },
                            onClick = {
                                if (entry.isDir) dir = entry.path
                                else if (entry.ext in PREVIEWABLE_EXT)
                                    fullscreenEntry = entry
                            },
                            // ★ 第三批（§4）：非选择模式长按 = 弹"下载"确认框（只对文件）。
                            onLongClick = if (selecting) ({ longPress(entry.path) })
                                else ({ if (!entry.isDir) downloadTarget = entry }),
                            // ★ 第三批（§1）：队列状态角标（与传输/同步页同套 markOf）
                            mark = markOf(queueItems, entry.path, entry.size, entry.timestamp),
                        )
                    }
                }
                }   // key(dir, guid, sortDesc)
            }
            }   // 位移 Box（下拉跟随）
            }   // PullToRefreshBox
        }

        // 「开始传输」FAB：出现/消失走弹簧（缩放 + 淡变）。FAB 是个"凭空多出来的动作"，
        // 硬切进来会显得突兀；从 0.7 倍弹到 1 倍、同时淡入，读起来是"它长出来了"。
        // 位移/缩放用 spatial、淡变用 effects —— MD3E 的分工。
        AnimatedVisibility(
            visible = selecting && selected.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = scaleIn(
                animationSpec = Motion.spatialDefault(),
                initialScale = 0.7f,
            ) + fadeIn(Motion.effectsFast()),
            exit = scaleOut(
                animationSpec = Motion.spatialFast(),
                targetScale = 0.7f,
            ) + fadeOut(Motion.effectsFast()),
        ) {
            // ★ 整颗按钮都能驱动图标的按下变形（用户实测："只有精准按到图标才变形"）：
            //   FAB 拿不到库的交互源，所以由这个壳用 Initial pass 观察**整块区域**，
            //   再把信号经 LocalRowInteraction 递给图标。
            WholeAreaPressScope(
                modifier = Modifier
                    // ★ 与传输页的"开始传输"**同一个高度**（用户定版："两个传输按钮
                    //   都改成在文件页的高度基础上降低一点"）。基准 = 文件页原来的
                    //   20 + FloatingNavInset(112)，这里去掉那 20 再降 12 → 100dp。
                    .padding(horizontal = 20.dp)
                    .padding(bottom = FloatingNavInset - 12.dp),
            ) {
                ExtendedFloatingActionButton(onClick = {
                    // ★ 第三批（§8）：扫描中点 FAB = 取消（原顶部悬浮条的"取消"挪到这里，
                    //   一处承载）；平时 = 开始传输（原行为）。
                    if (preparing) {
                        scanJob?.cancel()
                        scanJob = null
                        preparing = false
                        scanProgress = ""
                    } else {
                        startTransfer()
                    }
                }) {
                    if (preparing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(scanProgress.ifBlank { "扫描中…" })
                    } else {
                        MsIcon(
                            icon = MsIcon.START,
                            contentDescription = null,
                            interactionSource = LocalRowInteraction.current,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("开始传输 (${selected.size})")
                    }
                }
            }
        }
    }

    // 全屏大预览
    //
    // ★ 预览要能在**同目录的图片之间左右滑动**，所以它拿的是"这个目录里所有可预览的
    //   文件 + 从哪一张开始"，而不是单独一张（见 FullscreenPreview 的注释）。
    //   可预览 = 扩展名属于图片（与进入预览的判断同一套），跳过文件夹。
    fullscreenEntry?.let { entry ->
        val gallery = remember(displayEntries) {
            displayEntries.filter { it.ext in PREVIEWABLE_EXT }
        }
        val startIndex = gallery.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        Dialog(
            onDismissRequest = { fullscreenEntry = null },
            // usePlatformDefaultWidth=false：默认弹窗左右各留边距，
            // 那种宽度下 Pager 的翻页手势会显得很局促，图片也看不全
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // 铺满整屏（含状态栏/手势栏区域）→ 预览页真正沉浸
                decorFitsSystemWindows = false,
            ),
        ) {
            FullscreenPreview(
                gallery = gallery,
                startIndex = startIndex,
                isSelected = { p -> selected.contains(p) },
                onToggleSelect = { p ->
                    // ★ 同时进入选择模式：不然从预览里选中了，回到列表看不到勾选框、
                    //   底部「开始传输(n)」也不会出现 —— 用户会以为没选上。
                    selecting = true
                    toggle(p)
                },
                onDismiss = { fullscreenEntry = null },
                onOpenSettings = onOpenSettings,
                onSwitchDevice = onSwitchDevice,
            )
        }
    }

    // ★ 第三批（§4）：非选择模式长按文件 → "下载"确认框。点"下载"直接把该条目
    //   点名进扫描队列（targets），不进入选择模式、不清现有选择；文件夹不弹（§10 Q4）。
    downloadTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { downloadTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("下载") },
            text = { Text("下载 ${entry.name}？") },
            confirmButton = {
                TextButton(onClick = {
                    downloadTarget = null
                    startTransfer(targets = listOf(entry))
                }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { downloadTarget = null }) { Text("取消") }
            },
        )
    }

    // ★ 用户定版：弹窗的"选择目录"**直接拉起系统目录选择器**（不再绕设置页）
    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SettingsRepo.updateDownloadTreeUri(uri.toString())
        }
    }
    // 下载目录不可用：弹窗提示 + 给一条"去设置"的路（文件已在预检处拦下，未入队）
    if (dirMissing) {
        DownloadDirMissingDialog(
            dirLabel = SettingsRepo.downloadDirLabel(),
            onPickDir = { treePicker.launch(null) },
            onDismiss = { dirMissing = false },
        )
    }
}

/**
 * 选中项收集：目录递归展开（**限深 4 层**，无条数上限）；IO 线程调用。
 *
 * ★ 这里**没有** 500 项上限 —— 旧版有过，后来改成"逐目录递归、分批入队"，
 *   上限就撤了（静默截断比慢更糟：用户以为选全了，实际少了一半）。
 *   现在唯一的边界是深度 4：再深的目录树在相机存储卡上不存在，
 *   而万一有环（符号链接之类）也不会把队列撑爆。
 */
private suspend fun collectAndEnqueue(
    host: String,
    entries: List<ObjectRepository.FtpEntry>,
    selected: List<String>,
    /** suspend：调用方要把入队（改 Compose 状态）切回主线程，见 [startTransfer]。 */
    enqueue: suspend (List<ObjectRepository.FtpEntry>) -> Unit,
    onProgress: suspend (Int, Int) -> Unit,
) {
    data class DirTask(val path: String, val depth: Int)

    val seen = HashSet<String>()
    val batch = ArrayList<ObjectRepository.FtpEntry>(128)
    var found = 0
    var dirs = 0
    suspend fun flush() {
        if (batch.isEmpty()) return
        val copy = batch.toList()
        batch.clear()
        enqueue(copy)
        onProgress(dirs, found)
    }

    for (path in selected) {
        val selectedEntry = entries.firstOrNull { it.path == path } ?: continue
        if (!selectedEntry.isDir) {
            if (seen.add(selectedEntry.path)) {
                batch.add(selectedEntry)
                found++
            }
            if (batch.size >= 128) flush()
            continue
        }

        val queue = ArrayDeque<DirTask>()
        queue.add(DirTask(selectedEntry.path, 1))
        while (queue.isNotEmpty()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val task = queue.removeFirst()
            dirs++
            onProgress(dirs, found)

            var offset = 0
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // F6：传输前的目录收集属"批量扫描"场景，走扫描档超时（30s）
                val page = ObjectRepository.listPage(
                    host, task.path, offset, 256, ObjectRepository.SCAN_LIST_TIMEOUT_MS,
                )
                for (e in page.entries) {
                    if (e.isDir) {
                        if (task.depth < 4) queue.add(DirTask(e.path, task.depth + 1))
                    } else if (e.ext in PREVIEWABLE_EXT && seen.add(e.path)) {
                        batch.add(e)
                        found++
                        if (batch.size >= 128) flush()
                    }
                }
                if (!page.hasMore || page.nextOffset <= offset) break
                offset = page.nextOffset
            }
        }
    }
    flush()
    onProgress(dirs, found)
}

/** 目录首页加载的自动重试次数与间隔（F4）。 */
private const val LOAD_MAX_ATTEMPTS = 3
private const val LOAD_RETRY_DELAY_MS = 1000L

/** 停留期间自动刷新的校验周期（2.8）：一条计数探针（几十字节）判目录有没有变。 */
private const val AUTO_REFRESH_INTERVAL_MS = 6_000L

/** 翻页的自动重试次数与间隔（F4）。 */
private const val LOAD_MORE_MAX_ATTEMPTS = 3
private const val LOAD_MORE_RETRY_DELAY_MS = 1000L

/** 下拉刷新的触发阈值（比库默认 80dp 小一档，轻拉即可触发；用户定版）。 */
private val PULL_REFRESH_THRESHOLD = 48.dp

/** 下拉指示器的图标尺寸（与顶部加载行同一规格的小号加载图标）。 */
private val PULL_SPINNER_SIZE = 24.dp

/**
 * 目录指纹（L2 2.6；**2.8 重做**）：目标目录的**条目计数** —— "这份列表是否还作数"
 * 的判据，用 [ObjectRepository.dirCount] 计数探针一次往返拿到。
 *
 * <p>★ 2.6 的旧做法（扫父目录首页、取目标目录条目的 mtime）建立在"FAT 目录
 *   mtime 随内容变化"上——真机实测这台相机**不成立**（目录 mtime 恒为创建时间，
 *   新增/删除照片都不更新），于是"拍新照片 → 指纹仍命中 → 列表永远不刷新、
 *   必须手动刷新"（用户实测主诉）。改用**条目计数**：增删条目必然改变计数。
 *
 * <p>★ 为什么不用 `ObjectRepository.stat(dir)`：相机端 `OP_STAT` 只受理**文件**
 *   （`objectSize` 要求 `isFile()`，目录一律回 `RC_NOT_FOUND`），对目录 stat 必然失败。
 *
 * <p>已知边界（可接受）：**同数换名**（删一张又拍一张）计数不变、指纹误命中。
 *   这由 FilesScreen 停留期间的周期性校验兜底（那里指纹变了走全量重载，
 *   而全量重载每次都真拉首页 —— 同数换名最终在下一轮周期校验中被"强制刷新"
 *   捕获：[AUTO_REFRESH_INTERVAL_MS] 到点即 force=true，跳过指纹判断）。
 *
 * @return null = 探测不到（根目录 / 网络失败）——调用方按"有变动"处理，走全量
 *   加载（安全侧），且该目录的缓存指纹记为 [FileListCache.STAMP_UNKNOWN]
 *   （以后也不可能被误判命中）。根目录没有父目录可查、本身很小（DCIM + 少量
 *   文件），每次全量拉也不贵 —— 但计数探针对它同样可用，一并覆盖。
 */
private suspend fun dirFingerprint(host: String, dir: String): Int? {
    return try {
        withContext(Dispatchers.IO) { ObjectRepository.dirCount(host, dir) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.d("FilesScreen", "目录指纹探测失败($dir): ${e.message}")
        null
    }
}
