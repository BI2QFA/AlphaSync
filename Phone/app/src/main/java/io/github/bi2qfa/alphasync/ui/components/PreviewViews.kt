package io.github.bi2qfa.alphasync.ui.components

/**
 * 全屏大预览 + 相机 EXIF 弹窗（2.11 UI 批次从 FilesScreen 抽取）。
 *
 * 抽取动机：同步页（SyncScreen）点击条目也要进全屏预览（用户定版 D6）——
 * 与文件页**同一套**预览器（同一手势表、同一旋转队列、同一信息弹窗），
 * 各写一份必然漂移。这里**只搬不改**（逐字复制）。
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import io.github.bi2qfa.alphasync.data.LocalPhoto
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.ui.FloatingNav
import io.github.bi2qfa.alphasync.ui.NavItemSpec
import io.github.bi2qfa.alphasync.ui.PhotoRotate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 双击放大到的倍数。2.5 是"看清细节"与"不至于迷路"之间的常用值。 */
private const val DOUBLE_TAP_SCALE = 2.5f

/** 缩放下限与上限。上限 5x：预览图是 1616×1080，再放大就已明显糊了。 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

/**
 * 全屏大预览：**左右滑动切换（吸附+动画） + 双指缩放拖动 + 双击放大**。
 *
 * 手势分工（写清楚免得日后调乱）：
 *
 * | 手势 | 未放大（1x） | 已放大（>1x） |
 * |---|---|---|
 * | 单指拖动 | 切上一张/下一张（Pager 自带吸附与动画） | 平移图片 |
 * | 双指捏合 | 缩放 | 缩放 |
 * | 双击 | 放大到 2.5x | 复位到 1x |
 *
 * ★ **单击不再退出**（用户定版：退出只走左上角的返回键）—— 单击会与双击、与
 *   "点一下想看细节"打架，去掉后手势表更干净。
 *
 * ★ **为什么放大后要挡住 Pager 才能平移**：`HorizontalPager` 在 1x 时吃掉横向拖动，
 *   `transformable` 也吃横向拖动。做法是**只在 1x 时把拖动交给 Pager**：
 *   放大后用 `userScrollEnabled = false` 关掉 Pager 自己的滑动，横向拖动就只剩
 *   `transformable` 在接。
 *
 * @param gallery 同目录的可预览文件（顺序 = 列表顺序）
 * @param startIndex 从哪一张开始看
 * @param isSelected 某一张当前是否已选中（复用列表/网格的同一份多选状态）
 * @param onToggleSelect 切换某一张的选中态
 */
@Composable
internal fun FullscreenPreview(
    gallery: List<ObjectRepository.FtpEntry>,
    startIndex: Int,
    isSelected: (String) -> Boolean,
    onToggleSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 控制中心面板里的两件事（由 MainActivity 一路透传进来）。 */
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: (String) -> Unit = {},
    /**
     * ★ 2.11：底部"选择"动作是否显示。文件页传 true（默认）；**同步页传 false** ——
     * 同步页不做选择模式（传输入队全自动），留一个点了没反应的按钮只会让人困惑。
     */
    showSelectAction: Boolean = true,
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { gallery.size }

    // 缩放与平移状态。**每张图各自一份**（key = 页码）：换到下一张时是
    // "新的图、新的 1x"，而不是把上一张的放大倍数带过去。
    var scale by remember(pagerState.currentPage) { mutableStateOf(1f) }
    var offset by remember(pagerState.currentPage) { mutableStateOf(Offset.Zero) }

    // 1x 才让 Pager 能滑（放大后横向拖动归平移，见上面的分工表）
    val atBaseScale = scale <= 1.001f

    // 用户点了"旋转"的次数（**每张图各自记**）：预览页的左下角按钮每次 +1，
    // 页面据此逆时针转 90°。用 path 当键，翻页回来仍保持用户转过角度。
    val rotationByPath = remember { mutableStateMapOf<String, Int>() }

    // 某张图**已经转完几段**（由预览页在每段烘焙后回报）。按钮据此限流：
    // **最多允许"正在播的那一段 + 一段待播"**（用户定版：最多再多转一段）——
    // 连点三下净效果是转两下（180°），连点十下也只有两下，停止点击后不会再有积压。
    //
    // ★ 限流必须落在**点击那一刻**（本表由页面回报）。放在旋转协程里用 snapshotFlow
    //   收点击是来不及的：流是异步投递的，等它送到，那段动画已经播完、"忙"判成了
    //    "闲"，于是多出来的点击又会被播出来 —— 限流等于没做。
    val bakedByPath = remember { mutableStateMapOf<String, Int>() }

    /** "照片信息"弹窗开关（2.7.0 需求 3：底部导航"旋转 — 照片信息 — 选择"中间那颗）。 */
    var infoOpen by remember { mutableStateOf(false) }

    // ★ 版式与主界面同构（用户定版"预览器背景色和主界面一致"）：
    //   surface 底 + AppHeader 在**内容之上**（不再压在照片上，也就不需要顶部压暗渐变）+
    //   照片区独立 + 底部悬浮导航盖在内容上（与主界面"栏浮在内容之上"的模型一致）。
    // 当前这一张（顶部文案与底部两个动作都用它）
    val current = gallery.getOrNull(pagerState.currentPage)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 照片铺满整屏（标题与导航浮在它上面）→ 在整屏里居中，
        // 而不是被顶部标题挤到下半屏（用户实测："预览图高度位置应该居中"）
        Box(Modifier.fillMaxSize()) {
        // （照片区就是整屏，见上面外层 Box 的说明）
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = atBaseScale,
        ) { page ->
            val entry = gallery.getOrNull(page)
            if (entry != null) {
                PreviewPage(
                    entry = entry,
                    scale = if (page == pagerState.currentPage) scale else 1f,
                    offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                    onScale = { if (page == pagerState.currentPage) scale = it },
                    onOffset = { if (page == pagerState.currentPage) offset = it },
                    turnsOf = { rotationByPath[entry.path] ?: 0 },
                    onBaked = { n -> bakedByPath[entry.path] = n },
                )
            }
        }

        // ===== 顶部：标题浮层（照片满屏，垫一层压暗渐变保证白字可读）=====
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                ),
        ) {
        AppHeader(
            title = gallery.getOrNull(pagerState.currentPage)?.name ?: "",
            subtitle = gallery.getOrNull(pagerState.currentPage)?.let { e ->
                val date = formatTime(e.timestamp)
                val seq = "${pagerState.currentPage + 1}/${gallery.size}"
                if (date.isNotBlank()) "$date · $seq" else seq
            },
            onBack = onDismiss,
        )
        }

        // ===== 底部：**与主界面同一套悬浮导航**（用户定版）=====
        //   · 药丸里放两个动作按钮（旋转 90° / 选择），条目数少了，药丸宽度自然变短；
        //   · 小球与控制中心面板**整块复用主界面那一套**（同一套动画与功能）；
        //   · 药丸 + 小球作为**一个整体在横轴上居中**、高度与主界面相同 —— 这些都由
        //     FloatingNav 自己的布局负责，这里只是把动作传进去。
        val sel = current != null && isSelected(current.path)
        FloatingNav(
            modifier = Modifier.align(Alignment.BottomCenter),
            // 面板里的"设置 / 切换设备"由 MainActivity 透传进来（用户定版：预览器里也要能用）
            onOpenSettings = onOpenSettings,
            onSwitchDevice = onSwitchDevice,
            // ★ 2.11：动作表按需拼（同步页不显示"选择"，见 [showSelectAction]）
            actions = buildList {
                add(
                    NavItemSpec(MsIcon.ROTATE_CCW, "旋转", false) {
                        val p = current ?: return@NavItemSpec
                        val requested = rotationByPath[p.path] ?: 0
                        val done = bakedByPath[p.path] ?: 0
                        // 排队深度上限 2（正在播的那段 + 至多一段待播），再多来的点击丢弃
                        if (requested - done >= 2) return@NavItemSpec
                        rotationByPath[p.path] = requested + 1
                        offset = Offset.Zero          // 转完可用区域变了，平移归零
                    },
                )
                // ★ 2.7.0 需求 3（用户定版）：EXIF 按钮放在"旋转"与"选择"**之间**
                add(NavItemSpec(MsIcon.INFO, "照片信息", false) { infoOpen = true })
                if (showSelectAction) {
                    add(
                        NavItemSpec(MsIcon.CHECK, if (sel) "取消选中" else "选择", sel) {
                            current?.let { onToggleSelect(it.path) }
                        },
                    )
                }
            },
        )

        // ★ 2.7.0 需求 3：照片信息弹窗（数据源 = 随大预览传回并缓存的 sidecar EXIF）
        if (infoOpen && current != null) {
            CameraExifDialog(entry = current, onDismiss = { infoOpen = false })
        }
        }
    }
}


/**
 * 预览里的一张：拉图 + 缩放平移 + 手势 + 旋转。
 *
 * 拆成单独的 composable 是为了让"每张图自己的缩放/旋转状态"天然隔离
 * （父层按页码给状态，这里只负责渲染与手势），也避免 Pager 预取相邻页时
 * 把当前页的手势状态搅在一起。
 *
 * @param turnsOf **现读**"这张图已被点了几次旋转"（父层累加）。传回调而不是值：
 *   旋转队列要用 snapshotFlow 观察它，参数位置传进来的普通 Int 是快照之外的拷贝。
 * @param onBaked 每转完一段回报一次段数，父层的按钮据此限制排队深度。
 */
@Composable
private fun PreviewPage(
    entry: ObjectRepository.FtpEntry,
    scale: Float,
    offset: Offset,
    onScale: (Float) -> Unit,
    onOffset: (Offset) -> Unit,
    turnsOf: () -> Int = { 0 },
    onBaked: (Int) -> Unit = {},
) {
    val key = ThumbStore.ObjectCacheKey(
        path = entry.path,
        size = entry.size,
        mtime = entry.timestamp,
    )
    var bmp by remember(key) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    /** 这张图**已经转过几段**（绝对次数，父层按钮的限流依据）。 */
    var baked by remember(entry.path) { mutableIntStateOf(0) }

    LaunchedEffect(key) {
        val b = withContext(Dispatchers.IO) { ThumbStore.fetchPreviewBlocking(key) }
        if (b == null) {
            failed = true
            return@LaunchedEffect
        }
        // 首帧把"已经点过的旋转"**瞬时**补上（不播动画）：翻页回来或重新打开时，
        // 图应该是你上次离开时的朝向，而不是自己当着你面转几圈。
        // ★ 先算好位图、把 baked 落定、回报给父层，**最后**才赋 bmp —— 旋转协程是靠
        //   "bmp 从无到有"被唤醒的，必须等这些状态都就位再放它跑。
        val already = turnsOf()
        val loaded = PhotoRotate.rotateSteps(b, already)
        baked = already
        onBaked(already)
        bmp = loaded
    }

    // 容器尺寸：夹紧平移要用它（把图片拖出屏幕就找不回来了）
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // ===== 旋转：常驻协程一段段消化（不打断当前动画；排队深度由父层按钮限流）=====
    val anim = remember(entry.path) { Animatable(0f) }
    LaunchedEffect(entry.path) {
        // 观察"点击总数 + 图到位了没"这一整体：图从无到有也会重新发射，
        // 于是拉图期间点的那一下不会被吞（那时 bmp 还是 null，转不了，等图到了再播）。
        snapshotFlow { turnsOf() to (bmp != null) }.collect { (target, ready) ->
            if (!ready) return@collect
            while (baked < target) {
                val cur = bmp ?: break
                anim.snapTo(0f)
                anim.animateTo(
                    PhotoRotate.STEP_DEG,
                    tween(durationMillis = PhotoRotate.STEP_MS, easing = FastOutSlowInEasing),
                )
                bmp = PhotoRotate.rotate(cur, PhotoRotate.STEP_DEG)
                baked += 1
                onBaked(baked)
                anim.snapTo(0f)          // 与烘焙后的朝向等价，无缝
            }
        }
    }

    // ★★ 手势回调必须读"当下值"（用户实测两个 bug 的共同病根）：
    //   `pointerInput(Unit)` 与 `rememberTransformableState` 的 lambda 都只组合一次，
    //   直接捕获 `scale`/`offset` 拿到的是**初值 1f/0** —— 于是"再次双击复位"永远
    //   走放大分支（scale 判断恒假）、捏合也从 1f 起算。用 rememberUpdatedState
    //   把最新值转交给这些长命 lambda。
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (currentScale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        // 缩放变化时把平移也按比例收敛，否则缩小回去之后画面还偏在一边
        val ratio = if (currentScale > 0f) next / currentScale else 1f
        val panned = (currentOffset + panChange) * ratio
        onScale(next)
        // ★ 夹紧：可平移的最大范围 = 图片溢出容器的部分的一半（缩放以中心为基准）。
        //   不夹的话放大后能把图拖到屏幕外，只剩黑底 —— 用户会以为图没了。
        val maxX = (boxSize.width * (next - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (next - 1f) / 2f).coerceAtLeast(0f)
        onOffset(
            Offset(
                panned.x.coerceIn(-maxX, maxX),
                panned.y.coerceIn(-maxY, maxY),
            ),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            // 手势①：双击放大/复位。★ 单击不做事（用户定版：去掉"点击任意处返回"）。
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentScale > 1.001f) {
                            onScale(1f)
                            onOffset(Offset.Zero)
                        } else {
                            onScale(DOUBLE_TAP_SCALE)
                        }
                    },
                )
            }
            // 手势②：双指缩放 + 平移。
            // ★ canPan：**单指平移只在已放大时归 transformable**（用户实测 1x 时它把
            //   横向拖动整个吃掉、Pager 永远翻不了页）；1x 时单指拖动不消费 →
            //   交给 Pager 翻页。双指捏合（zoom）不受 canPan 影响，任何时刻可用。
            .transformable(transformState, canPan = { currentScale > 1.001f })
            // 缩放的视觉表现：scale 改大小、offset 改位置。
            // ★ 用 graphicsLayer 而不是 Modifier.scale：这两个变换都在**绘制阶段**生效，
            //   不触发布局 —— 缩放跟手才不会卡。
            .graphicsLayer {
                // 旋转中的**连续适配**：角度变了，能塞下它的尺寸也跟着变
                // （不然会在烘焙那一刻突然跳一下尺寸，见 PhotoRotate 的说明）
                val rotFit = PhotoRotate.fit(bmp, boxSize, anim.value)
                scaleX = scale * rotFit
                scaleY = scale * rotFit
                translationX = offset.x
                translationY = offset.y
                rotationZ = anim.value
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(
                b,
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                // Fit：整图可见优先。放大靠手势，不靠 contentScale
                contentScale = ContentScale.Fit,
            )
            failed -> Text("无法获取预览", color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * 相机照片的"照片信息"弹窗（2.7.0 需求 3）。
 *
 * 版式与传输页本地查看器**共用 [ExifInfoDialog]**、行构建**共用
 * [LocalPhoto.buildExifRows]** —— 两处显示一致由共用代码保证（用户定版）。
 * 数据源与本地查看器不同：EXIF 来自随大预览一并传输并缓存的 sidecar
 * （[ThumbStore.readExifJson]；相机端按《EXIF读取专项文档》从文件头 64KB 解析）。
 */
@Composable
private fun CameraExifDialog(entry: ObjectRepository.FtpEntry, onDismiss: () -> Unit) {
    val key = ThumbStore.ObjectCacheKey(
        path = entry.path,
        size = entry.size,
        mtime = entry.timestamp,
    )
    var rows by remember(key) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(key) {
        rows = withContext(Dispatchers.IO) {
            val json = runCatching {
                org.json.JSONObject(ThumbStore.readExifJson(key) ?: "{}")
            }.getOrNull()
            LocalPhoto.buildExifRows(entry.name) { tag ->
                json?.optString(EXIF_JSON_KEY[tag] ?: "")?.takeIf { it.isNotBlank() }
            }
        }
    }
    ExifInfoDialog(rows = rows, onDismiss = onDismiss)
}

/**
 * 相机端 sidecar 的短键 ↔ ExifInterface 标签名（[LocalPhoto.buildExifRows] 的查找键）。
 * "o"（方向）不进弹窗，只供大预览与小图自动转正。
 */
private val EXIF_JSON_KEY = mapOf(
    ExifInterface.TAG_DATETIME_ORIGINAL to "dt",
    ExifInterface.TAG_MODEL to "m",
    ExifInterface.TAG_LENS_MODEL to "lens",
    ExifInterface.TAG_FOCAL_LENGTH to "fl",
    ExifInterface.TAG_F_NUMBER to "fn",
    ExifInterface.TAG_EXPOSURE_TIME to "et",
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "ev",
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "iso",
)
