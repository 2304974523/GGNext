package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.data.AppIconCache
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.model.toolIcon
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.regionColor

/**
 * 顶部导航栏：
 * - 左侧当前进程图标按钮
 * - 4 个导航页签（设置 / 搜索列表 / 保存列表 / 内存）共享一个带物理弹簧移动效果的跟随背景胶囊与底部指示条
 * - 右侧关闭/最小化按钮（×）
 */
@Composable
fun NavBar(
    tab: NextTab,
    target: TargetProcess?,
    searchCount: Int,
    savedCount: Int,
    onPickProcess: () -> Unit,
    onTab: (NextTab) -> Unit,
    onMinimize: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val ctx = LocalContext.current
    val appBmp = remember(target?.packageName, target?.apkPath) {
        target?.let { AppIconCache.get(ctx, it.packageName, it.apkPath) }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var iconPressed by remember { mutableStateOf(false) }
        val iconScale by animateFloatAsState(if (iconPressed) 0.88f else 1f, Motion.Fast, label = "procIcon")
        Box(
            Modifier
                .size(38.dp)
                .scale(iconScale)
                .clip(RoundedCornerShape(10.dp))
                .background(Rose.CardHigh)
                .clickableWithTooltip(
                    onPressChange = { iconPressed = it },
                    onClick = onPickProcess
                ),
            contentAlignment = Alignment.Center
        ) {
            if (appBmp != null) {
                Image(bitmap = appBmp, contentDescription = target?.label, modifier = Modifier.size(32.dp))
            } else {
                Text("G", color = primary, fontSize = fsp(17f), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.width(4.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(4f)
                .height(42.dp)
        ) {
            val slotWidth = maxWidth / 4f
            val indicatorX by animateDpAsState(
                targetValue = slotWidth * tab.ordinal,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "navSlideX"
            )

            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(slotWidth)
                    .height(42.dp)
                    .padding(horizontal = 3.dp, vertical = 2.dp)
                    .background(
                        color = primary.copy(alpha = if (Rose.IsDark) 0.22f else 0.18f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = primary.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(22.dp)
                        .height(2.5.dp)
                        .background(primary, RoundedCornerShape(2.dp))
                )
            }

            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIcon(
                    icon = Icons.Rounded.Tune,
                    desc = "设置",
                    selected = tab == NextTab.SETTINGS,
                    primary = primary,
                    modifier = Modifier.weight(1f)
                ) { onTab(NextTab.SETTINGS) }

                NavIcon(
                    icon = Icons.Rounded.Search,
                    desc = "搜索列表",
                    selected = tab == NextTab.SEARCH_LIST,
                    primary = primary,
                    badgeCount = searchCount,
                    modifier = Modifier.weight(1f)
                ) { onTab(NextTab.SEARCH_LIST) }

                NavIcon(
                    icon = Icons.Rounded.Save,
                    desc = "保存列表",
                    selected = tab == NextTab.SAVED_LIST,
                    primary = primary,
                    badgeCount = savedCount,
                    modifier = Modifier.weight(1f)
                ) { onTab(NextTab.SAVED_LIST) }

                NavIcon(
                    icon = GgIcons.MemoryList,
                    desc = "内存编辑器",
                    selected = tab == NextTab.MEMORY,
                    primary = primary,
                    modifier = Modifier.weight(1f)
                ) { onTab(NextTab.MEMORY) }
            }
        }

        NavIcon(
            icon = Icons.Rounded.Close,
            desc = "收起面板",
            selected = false,
            primary = primary,
            modifier = Modifier.weight(0.86f),
            onClick = onMinimize
        )
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    desc: String,
    selected: Boolean,
    primary: Color,
    badgeCount: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = Motion.Fast,
        label = "navScale"
    )
    val tint by animateColorAsState(
        if (selected) primary else Rose.Text,
        Motion.ColorNormal,
        label = "navTint"
    )
    Box(
        modifier
            .padding(horizontal = 2.dp)
            .height(42.dp)
            .scale(scale)
            .clickableWithTooltip(
                tooltip = desc,
                onPressChange = { pressed = it },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        if (badgeCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
                    .background(primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badgeCount > 9999) "9999+" else badgeCount.toString(),
                    color = Rose.OnPrimary,
                    fontSize = fsp(9f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 工具栏：
 * 1. 拆分“流动工具栏”与“固定工具栏（右侧常驻）”，固定工具栏支持自定义添加，点击固定后不再在流动工具栏显示；
 * 2. 彻底修复长按拖动串键 Bug：拖拽手势精准绑定在具体按钮自身，绝对不会控制到其它按钮；
 * 3. 动态状态联动：根据当前界面与数据状况实时更新按钮可用状态，不可用时按钮呈灰色且点击无效，但仍可长按拖动调序；
 * 4. 左右边缘拖拽时工具栏平滑自动跟随滚动。
 */
@Composable
fun ToolBar(
    flowingTools: List<ToolId>,
    pinnedTools: List<ToolId>,
    ui: com.littlecodesauce.ggnext.core.Ui,
    onTool: (ToolId) -> Unit,
    onScript: () -> Unit,
    onMoreMenu: () -> Unit,
    onMoveFlowingTool: (from: Int, to: Int) -> Unit,
    onMovePinnedTool: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val pinnedScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemWidthPx = with(density) { 40.dp.toPx() }
    val itemSpacingPx = with(density) { 2.dp.toPx() }
    val itemSpanPx = itemWidthPx + itemSpacingPx

    val currentFlowing by rememberUpdatedState(flowingTools)
    val currentPinned by rememberUpdatedState(pinnedTools)
    val currentOnMoveFlowing by rememberUpdatedState(onMoveFlowingTool)
    val currentOnMovePinned by rememberUpdatedState(onMovePinnedTool)

    var draggingFlowingTool by remember { mutableStateOf<ToolId?>(null) }
    var viewportFingerXFlowing by remember { mutableFloatStateOf(0f) }
    var grabOffsetFlowingPx by remember { mutableFloatStateOf(0f) }

    var draggingPinnedTool by remember { mutableStateOf<ToolId?>(null) }
    var viewportFingerXPinned by remember { mutableFloatStateOf(0f) }
    var grabOffsetPinnedPx by remember { mutableFloatStateOf(0f) }

    // 精确计算含滞回区间 (Hysteresis) 的目标槽位，彻底消除槽位边界来回抖动抽搐
    fun resolveTargetSlot(
        draggedCenterX: Float,
        curIndex: Int,
        lastIndex: Int
    ): Int {
        if (lastIndex <= 0) return 0
        val hysteresisPx = itemSpanPx * 0.14f
        var target = curIndex
        while (target < lastIndex) {
            val nextSlotThreshold = (target + 1) * itemSpanPx + hysteresisPx
            if (draggedCenterX >= nextSlotThreshold) {
                target++
            } else {
                break
            }
        }
        while (target > 0) {
            val prevSlotThreshold = target * itemSpanPx - hysteresisPx
            if (draggedCenterX <= prevSlotThreshold) {
                target--
            } else {
                break
            }
        }
        return target.coerceIn(0, lastIndex)
    }

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧流动工具栏：外层 Box 捕获视口坐标系手势（位于 horizontalScroll 之外，避免滚动偏移被重复叠加导致错位）
        Box(
            Modifier
                .weight(1f)
                .pointerInput(ui.tab) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            val curList = currentFlowing
                            if (curList.isEmpty()) return@detectDragGesturesAfterLongPress
                            val contentX = startOffset.x + scrollState.value
                            val idx = (contentX / itemSpanPx).toInt().coerceIn(0, curList.lastIndex)
                            val slotCenterX = idx * itemSpanPx + itemWidthPx * 0.5f
                            viewportFingerXFlowing = startOffset.x
                            grabOffsetFlowingPx = (contentX - slotCenterX).coerceIn(-itemWidthPx * 0.5f, itemWidthPx * 0.5f)
                            draggingFlowingTool = curList[idx]
                        },
                        onDragEnd = {
                            draggingFlowingTool = null
                            viewportFingerXFlowing = 0f
                            grabOffsetFlowingPx = 0f
                        },
                        onDragCancel = {
                            draggingFlowingTool = null
                            viewportFingerXFlowing = 0f
                            grabOffsetFlowingPx = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val activeTool = draggingFlowingTool ?: return@detectDragGesturesAfterLongPress
                            val curList = currentFlowing
                            if (curList.isEmpty()) return@detectDragGesturesAfterLongPress

                            viewportFingerXFlowing = (viewportFingerXFlowing + dragAmount.x)
                                .coerceIn(0f, size.width.toFloat().coerceAtLeast(1f))

                            // 边缘平滑自动滚动
                            val edgeThreshold = 44f
                            if (viewportFingerXFlowing < edgeThreshold && scrollState.value > 0) {
                                coroutineScope.launch { scrollState.scrollBy(-10f) }
                            } else if (viewportFingerXFlowing > (size.width - edgeThreshold) && scrollState.value < scrollState.maxValue) {
                                coroutineScope.launch { scrollState.scrollBy(10f) }
                            }

                            val maxContentX = (curList.size * itemSpanPx - itemSpacingPx - itemWidthPx * 0.5f)
                                .coerceAtLeast(itemWidthPx * 0.5f)
                            val contentX = viewportFingerXFlowing + scrollState.value
                            val draggedCenterX = (contentX - grabOffsetFlowingPx)
                                .coerceIn(itemWidthPx * 0.5f, maxContentX)

                            val curIndex = curList.indexOf(activeTool)
                            if (curIndex != -1) {
                                val targetIndex = resolveTargetSlot(draggedCenterX, curIndex, curList.lastIndex)
                                if (targetIndex != curIndex) {
                                    currentOnMoveFlowing(curIndex, targetIndex)
                                }
                            }
                        }
                    )
                }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState, enabled = draggingFlowingTool == null),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                flowingTools.forEachIndexed { idx, id ->
                    androidx.compose.runtime.key("flow_${id.name}") {
                        val isDragging = draggingFlowingTool == id
                        val enabled = com.littlecodesauce.ggnext.ui.model.isToolEnabled(id, ui.tab, ui)
                        val maxContentX = (flowingTools.size * itemSpanPx - itemSpacingPx - itemWidthPx * 0.5f)
                            .coerceAtLeast(itemWidthPx * 0.5f)
                        val draggedCenterX = ((viewportFingerXFlowing + scrollState.value) - grabOffsetFlowingPx)
                            .coerceIn(itemWidthPx * 0.5f, maxContentX)
                        val slotCenterX = idx * itemSpanPx + itemWidthPx * 0.5f
                        // 拖拽中采用 1:1 零延迟跟手位移，彻底消除槽位交换瞬间弹簧滞后引起的跳变抽搐
                        val directTx = if (isDragging) (draggedCenterX - slotCenterX) else 0f

                        ToolIconItem(
                            id = id,
                            enabled = enabled,
                            isDragging = isDragging,
                            translationX = directTx,
                            interactionEnabled = draggingFlowingTool == null && draggingPinnedTool == null,
                            onClick = {
                                if (enabled) onTool(id) else Session.toast("当前无可选项目")
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(2.dp))

        // 右侧固定工具栏：同样由外层 Box 捕获视口坐标，消除滚动坐标二次叠加
        val maxPinnedWidth = (42.dp * 3) + 4.dp
        Box(
            Modifier
                .widthIn(max = maxPinnedWidth)
                .pointerInput(ui.tab) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            val curList = currentPinned
                            if (curList.isEmpty()) return@detectDragGesturesAfterLongPress
                            val contentX = startOffset.x + pinnedScrollState.value
                            val idx = (contentX / itemSpanPx).toInt().coerceIn(0, curList.lastIndex)
                            val slotCenterX = idx * itemSpanPx + itemWidthPx * 0.5f
                            viewportFingerXPinned = startOffset.x
                            grabOffsetPinnedPx = (contentX - slotCenterX).coerceIn(-itemWidthPx * 0.5f, itemWidthPx * 0.5f)
                            draggingPinnedTool = curList[idx]
                        },
                        onDragEnd = {
                            draggingPinnedTool = null
                            viewportFingerXPinned = 0f
                            grabOffsetPinnedPx = 0f
                        },
                        onDragCancel = {
                            draggingPinnedTool = null
                            viewportFingerXPinned = 0f
                            grabOffsetPinnedPx = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val activeTool = draggingPinnedTool ?: return@detectDragGesturesAfterLongPress
                            val curList = currentPinned
                            if (curList.isEmpty()) return@detectDragGesturesAfterLongPress

                            viewportFingerXPinned = (viewportFingerXPinned + dragAmount.x)
                                .coerceIn(0f, size.width.toFloat().coerceAtLeast(1f))

                            val edgeThreshold = 32f
                            if (viewportFingerXPinned < edgeThreshold && pinnedScrollState.value > 0) {
                                coroutineScope.launch { pinnedScrollState.scrollBy(-10f) }
                            } else if (viewportFingerXPinned > (size.width - edgeThreshold) && pinnedScrollState.value < pinnedScrollState.maxValue) {
                                coroutineScope.launch { pinnedScrollState.scrollBy(10f) }
                            }

                            val maxContentX = (curList.size * itemSpanPx - itemSpacingPx - itemWidthPx * 0.5f)
                                .coerceAtLeast(itemWidthPx * 0.5f)
                            val contentX = viewportFingerXPinned + pinnedScrollState.value
                            val draggedCenterX = (contentX - grabOffsetPinnedPx)
                                .coerceIn(itemWidthPx * 0.5f, maxContentX)

                            val curIndex = curList.indexOf(activeTool)
                            if (curIndex != -1) {
                                val targetIndex = resolveTargetSlot(draggedCenterX, curIndex, curList.lastIndex)
                                if (targetIndex != curIndex) {
                                    currentOnMovePinned(curIndex, targetIndex)
                                }
                            }
                        }
                    )
                }
        ) {
            Row(
                Modifier
                    .horizontalScroll(pinnedScrollState, enabled = draggingPinnedTool == null),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pinnedTools.forEachIndexed { idx, id ->
                    androidx.compose.runtime.key("pin_${id.name}") {
                        val isDragging = draggingPinnedTool == id
                        val enabled = com.littlecodesauce.ggnext.ui.model.isToolEnabled(id, ui.tab, ui)
                        val maxContentX = (pinnedTools.size * itemSpanPx - itemSpacingPx - itemWidthPx * 0.5f)
                            .coerceAtLeast(itemWidthPx * 0.5f)
                        val draggedCenterX = ((viewportFingerXPinned + pinnedScrollState.value) - grabOffsetPinnedPx)
                            .coerceIn(itemWidthPx * 0.5f, maxContentX)
                        val slotCenterX = idx * itemSpanPx + itemWidthPx * 0.5f
                        val directTx = if (isDragging) (draggedCenterX - slotCenterX) else 0f

                        ToolIconItem(
                            id = id,
                            enabled = enabled,
                            isDragging = isDragging,
                            translationX = directTx,
                            isPinned = true,
                            interactionEnabled = draggingFlowingTool == null && draggingPinnedTool == null,
                            onClick = {
                                if (!enabled) {
                                    Session.toast("当前无可选项目")
                                    return@ToolIconItem
                                }
                                when (id) {
                                    ToolId.EXEC_SCRIPT -> onScript()
                                    ToolId.MENU -> onMoreMenu()
                                    else -> onTool(id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具栏图标渲染组件（支持主题色高亮，支持 1:1 零延迟拖拽跟手渲染）
 */
@Composable
private fun ToolIconItem(
    id: ToolId,
    enabled: Boolean,
    isDragging: Boolean,
    translationX: Float,
    isPinned: Boolean = false,
    interactionEnabled: Boolean = true,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.12f
            pressed && interactionEnabled -> 0.88f
            else -> 1f
        },
        animationSpec = Motion.Fast,
        label = "toolScale"
    )

    Box(
        Modifier
            .size(40.dp)
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.translationX = translationX
            }
            .then(
                if (isDragging) {
                    Modifier
                        .background(Rose.ChipSelected, RoundedCornerShape(10.dp))
                        .border(1.dp, Rose.Primary, RoundedCornerShape(10.dp))
                } else Modifier
            )
            .clickableWithTooltip(
                tooltip = id.label,
                onPressChange = { pressed = it && interactionEnabled },
                onClick = {
                    if (interactionEnabled) onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val tint = when {
            !enabled -> Rose.TextDim.copy(alpha = 0.28f)
            isPinned -> Rose.Primary
            else -> Rose.Text
        }
        Icon(
            toolIcon(id),
            id.label,
            tint = tint,
            modifier = Modifier.size(21.dp)
        )
    }
}

/**
 * 进程空间栏
 */
@Composable
fun ProcessBar(
    target: TargetProcess?,
    paused: Boolean,
    countText: String,
    busy: Boolean,
    onPick: () -> Unit,
    onCountClick: () -> Unit,
    onRefresh: () -> Unit,
    onPause: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var pausePressed by remember { mutableStateOf(false) }
        val pauseScale by animateFloatAsState(if (pausePressed) 0.88f else 1f, Motion.Fast, label = "pauseScale")
        Box(
            Modifier
                .size(22.dp)
                .scale(pauseScale)
                .clickableWithTooltip(
                    onPressChange = { pausePressed = it },
                    onClick = onPause
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = "暂停进程",
                tint = if (paused) Rose.Primary else Rose.TextSecond,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(6.dp))

        var procPressed by remember { mutableStateOf(false) }
        if (target == null) {
            Text(
                tr("未选择进程 (点击选择)"),
                color = Rose.Red,
                fontSize = fsp(12.5f),
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .clickableWithTooltip(
                        onPressChange = { procPressed = it },
                        onClick = onPick
                    )
            )
        } else {
            Row(
                Modifier
                    .weight(1f)
                    .clickableWithTooltip(
                        onPressChange = { procPressed = it },
                        onClick = onPick
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("[${target.pid}]", style = monoStyle(12, Rose.Green))
                Spacer(Modifier.width(4.dp))
                if (target.isVirtual) {
                    Text(
                        text = "*",
                        color = Color(0xFF42A5F5),
                        fontSize = fsp(14f),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    target.label,
                    color = Rose.Text,
                    fontSize = fsp(12.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(4.dp))
                Text("[${if (target.x64) "x64" else "x86"}]", style = monoStyle(12, Rose.Green))
                Spacer(Modifier.width(4.dp))
                Text("[${ProcessScanner.sizeText(target.sizeBytes)}]", style = monoStyle(12, Rose.Green))
            }
        }

        Spacer(Modifier.width(6.dp))

        var refreshPressed by remember { mutableStateOf(false) }
        val refreshScale by animateFloatAsState(if (refreshPressed) 0.88f else 1f, Motion.Fast, label = "refScale")
        Box(
            Modifier
                .size(22.dp)
                .scale(refreshScale)
                .clickableWithTooltip(
                    onPressChange = { refreshPressed = it },
                    onClick = onRefresh
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Refresh, "刷新", tint = Rose.TextSecond, modifier = Modifier.size(15.dp))
        }

        Spacer(Modifier.width(4.dp))

        var countPressed by remember { mutableStateOf(false) }
        val countScale by animateFloatAsState(if (countPressed) 0.92f else 1f, Motion.Fast, label = "cntScale")
        val countBg by animateColorAsState(
            if (countPressed) Rose.CardHigh else Color.Transparent,
            Motion.ColorFast,
            label = "cntBg"
        )
        Box(
            Modifier
                .scale(countScale)
                .background(countBg, RoundedCornerShape(6.dp))
                .clickableWithTooltip(
                    onPressChange = { countPressed = it },
                    onClick = onCountClick
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = countText,
                style = monoStyle(12, if (countPressed) Rose.Primary else Rose.TextSecond)
            )
        }
    }
}

/**
 * 其他 2：紧贴在 ProcessBar 下方的精简非阻塞搜索进度栏
 * 显示当前任务名称、实时命中数量、扫描进度百分比与预计剩余时间（不弹窗阻塞，允许在搜索同时操作界面）
 */
@Composable
fun InlineSearchProgressBar(ui: com.littlecodesauce.ggnext.core.Ui) {
    val progress by animateFloatAsState(
        targetValue = ui.searchProgress.coerceIn(0.04f, 1f),
        animationSpec = Motion.Fast,
        label = "inlineSearchProg"
    )
    val pct = (ui.searchProgress.coerceIn(0f, 1f) * 100f).toInt()
    val etaStr = if (ui.searchEtaSeconds >= 0) "剩余 ~${ui.searchEtaSeconds}s" else "扫描中"

    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .background(Rose.Card.copy(alpha = (ui.metrics.bgOpacity * 0.92f).coerceIn(0.25f, 1f)))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = ui.busyText.ifEmpty { "搜索中..." },
                color = Rose.Primary,
                fontSize = fsp(11f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "命中 ${ui.searchFoundSoFar}",
                    style = monoStyle(11, Rose.Green, FontWeight.SemiBold)
                )
                if (ui.searchTotalMb > 0.1f) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = String.format(java.util.Locale.US, "%.0f/%.0fMB", ui.searchScannedMb, ui.searchTotalMb),
                        style = monoStyle(10, Rose.TextDim)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$pct% · $etaStr",
                    style = monoStyle(11, Rose.TextSecond)
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(Rose.Divider)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.5.dp)
                    .background(Rose.Primary)
            )
        }
    }
}

/**
 * 合并底栏：已选内存类型 + 已选数量
 */
@Composable
fun RegionSummaryBar(
    codes: List<String>,
    checkedItemCount: Int,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(26.dp)
            .padding(horizontal = 14.dp)
            .clickableLongPress(onClick = onClick, onLongPress = onLongPress),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            codes.forEach { code ->
                Text(
                    text = code,
                    color = regionColor(code),
                    fontSize = fsp(11.5f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = tr("已选 $checkedItemCount"),
            color = Rose.TextSecond,
            fontSize = fsp(11.5f)
        )
    }
}
