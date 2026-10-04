package io.github.bi2qfa.alphasync.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * 文件页容器（★ 2.11 §9，用户定版）：**文件浏览器 + 同步页**两页横滑。
 *
 * <ul>
 *   <li>页 0 = [FilesScreen]（原文件浏览器，功能不变）；页 1 = [SyncScreen]（同步面板）；</li>
 *   <li>**向左滑 = 文件页 → 同步页**（HorizontalPager 原生手势）；</li>
 *   <li>返回键：同步页 → 先滑回文件页（后注册的 BackHandler 优先生效；文件页自己的
 *       返回处理器只在页 0 组合时存在，两者启用条件互斥，不会打架）；</li>
 *   <li>页保活：只组合当前页（默认 beyondBoundsPageCount=0）—— 文件页切走再回来，
 *       目录位置由 AppRoot 持有、列表/滚动由 FileListCache 兜底，符合既有语义，
 *       不为保活加组合开销。</li>
 *   <li>★ 第三批（§2）：**横向位置跨"文件与同步 ↔ 传输"切换保留**——[pagerPage]
 *       由 AppRoot 持有（rememberSaveable）；本页离开组合时 pager 随之销毁，
 *       回来时用 [pagerPage] 的当前值当 initialPage，直接落在上次那页。</li>
 * </ul>
 */
@Composable
fun FilesPagerScreen(
    onGoTransfers: () -> Unit = {},
    dirState: MutableState<String>,
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: (String) -> Unit = {},
    /** ★ 2.11：双击顶栏标题的回顶信号（转发给当前正在看的那一页）。 */
    topTick: Int = 0,
    /** ★ 第三批（§2）：横向位置（0=文件页，1=同步页）——AppRoot 持有、跨切换保留。 */
    pagerPage: MutableState<Int>,
) {
    val scope = rememberCoroutineScope()
    // ★ 第三批（§2）：只在**本页首次组合**时取一次初始页（remember 快照）。
    //   不能直接把 `pagerPage.value` 传给 rememberPagerState —— 它是 saveable 的
    //   inputs，滑动回写会让它"变化"并触发 pager 重建；快照后初值恒定、pager 稳定。
    val initialPagerPage = remember { pagerPage.value }
    val pager = rememberPagerState(initialPage = initialPagerPage) { 2 }

    // 横向位置回写（AppRoot 持有；离开组合前已是最后停留的那页）
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { pagerPage.value = it }
    }

    // 同步页的返回 = 先滑回文件页
    BackHandler(enabled = pager.currentPage == 1) {
        scope.launch { pager.animateScrollToPage(0) }
    }

    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> FilesScreen(
                onGoTransfers = onGoTransfers,
                dirState = dirState,
                onOpenSettings = onOpenSettings,
                onSwitchDevice = onSwitchDevice,
                topTick = topTick,
            )
            else -> SyncScreen(
                topTick = topTick,
                onOpenSettings = onOpenSettings,
                onSwitchDevice = onSwitchDevice,
            )
        }
    }
}
