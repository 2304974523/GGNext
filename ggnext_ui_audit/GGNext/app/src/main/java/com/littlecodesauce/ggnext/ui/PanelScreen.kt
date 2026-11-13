package com.littlecodesauce.ggnext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import com.littlecodesauce.ggnext.ui.components.monoStyle
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.Hairline
import com.littlecodesauce.ggnext.ui.components.NavBar
import com.littlecodesauce.ggnext.ui.components.ProcessBar
import com.littlecodesauce.ggnext.ui.components.RegionSummaryBar
import com.littlecodesauce.ggnext.ui.components.ToolBar
import com.littlecodesauce.ggnext.ui.components.clickableNoRipple
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.components.valueText
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.screen.MemoryScreen
import com.littlecodesauce.ggnext.ui.screen.SavedListScreen
import com.littlecodesauce.ggnext.ui.screen.SearchListScreen
import com.littlecodesauce.ggnext.ui.screen.SettingsScreen
import com.littlecodesauce.ggnext.ui.sheet.SheetHost
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 修改器工作面板：
 * - 支持背景透明度（ui.metrics.bgOpacity，25% ~ 100%）实时调节
 * - 支持页面屏幕占比（ui.metrics.panelScale，40% ~ 100%）
 * - 界面调整 7：为顶部 ToolBar 接入 `onMoveTool = { from, to -> Session.moveTool(ui.tab, from, to) }`，支持长按工具栏按钮左右滑动直接调整顺序
 */
@Composable
fun PanelScreen(
    ui: Ui,
    onNav: (NextTab) -> Unit,
    onMenu: () -> Unit,
    onMinimize: () -> Unit,
    compact: Boolean = false
) {
    BackHandler(enabled = ui.sheet != Sheet.NONE) { Session.closeSheet() }

    val sheetOpen = ui.sheet != Sheet.NONE
    val app = LocalContext.current.applicationContext as android.app.Application
    val clipboard = LocalClipboardManager.current
    var refreshRequested by remember { mutableStateOf(false) }

    LaunchedEffect(refreshRequested) {
        if (refreshRequested) {
            Session.loadProcesses(app)
            refreshRequested = false
        }
    }

    val contentScale by animateFloatAsState(if (sheetOpen) 0.975f else 1f, Motion.Normal, label = "cs")
    val contentAlpha by animateFloatAsState(if (sheetOpen) 0.55f else 1f, Motion.Normal, label = "ca")

    val checkedCount = remember(ui.tab, ui.results, ui.saved, ui.memoryRows) {
        when (ui.tab) {
            NextTab.SAVED_LIST -> ui.saved.count { it.checked }
            NextTab.MEMORY -> ui.memoryRows.count { it.checked }
            else -> ui.results.count { it.checked }
        }
    }

    val orderedSelectedRegions = remember(ui.selectedRegions) {
        RegionCatalog.ALL.map { it.code }.filter { ui.selectedRegions.contains(it) }
    }

    val panelScale = ui.metrics.panelScale.coerceIn(0.40f, 1.0f)
    val bgOpacity = ui.metrics.bgOpacity.coerceIn(0.25f, 1.0f)
    val isPartialScreen = panelScale < 0.99f
    val panelCorner = if (isPartialScreen) 16.dp else 0.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isPartialScreen) Color.Black.copy(alpha = 0.25f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (isPartialScreen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickableNoRipple(remember { MutableInteractionSource() }) {
                        if (ui.sheet != Sheet.NONE) Session.closeSheet() else onMinimize()
                    }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(panelScale)
                .fillMaxHeight(panelScale)
                .clip(RoundedCornerShape(panelCorner))
                .background(Rose.Surface.copy(alpha = bgOpacity), RoundedCornerShape(panelCorner))
                .then(
                    if (isPartialScreen) Modifier.border(1.dp, Rose.Divider, RoundedCornerShape(panelCorner))
                    else Modifier
                )
                .clickableNoRipple(remember { MutableInteractionSource() }) {}
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (compact || isPartialScreen) Modifier else Modifier.statusBarsPadding())
                    .graphicsLayer {
                        scaleX = contentScale
                        scaleY = contentScale
                        alpha = contentAlpha
                    }
            ) {
                NavBar(
                    tab = ui.tab,
                    target = ui.target,
                    searchCount = ui.results.size,
                    savedCount = ui.saved.size,
                    onPickProcess = { Session.openSheet(Sheet.PROCESS) },
                    onTab = onNav,
                    onMinimize = onMinimize
                )
                if (ui.tab != NextTab.SETTINGS) {
                    ToolBar(
                        flowingTools = ui.flowingTools,
                        pinnedTools = ui.currentPinnedTools,
                        ui = ui,
                        onTool = { id ->
                            handleTool(id, ui) { text ->
                                clipboard.setText(AnnotatedString(text))
                            }
                        },
                        onScript = { Session.openSheet(Sheet.SCRIPT) },
                        onMoreMenu = onMenu,
                        onMoveFlowingTool = { from, to ->
                            val fullList = Session.toolList(ui.tab).toMutableList()
                            val flowing = ui.flowingTools
                            if (from in flowing.indices && to in flowing.indices) {
                                val fromItem = flowing[from]
                                val toItem = flowing[to]
                                val idxFrom = fullList.indexOf(fromItem)
                                val idxTo = fullList.indexOf(toItem)
                                if (idxFrom != -1 && idxTo != -1) {
                                    Session.moveTool(ui.tab, idxFrom, idxTo)
                                }
                            }
                        },
                        onMovePinnedTool = { from, to ->
                            Session.movePinnedTool(ui.tab, from, to)
                        }
                    )
                }
                Hairline()
                ProcessBar(
                    target = ui.target,
                    paused = ui.processPaused,
                    countText = ui.countButtonText,
                    busy = ui.busy,
                    onPick = { Session.openSheet(Sheet.PROCESS) },
                    onCountClick = { Session.openSheet(Sheet.FILTER) },
                    onRefresh = {
                        refreshRequested = true
                        Session.toast("已刷新列表")
                    },
                    onPause = { Session.toggleProcessPause() }
                )
                Hairline()

                AnimatedContent(
                    targetState = ui.tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (fadeIn(tween(160)) + slideInVertically(tween(180)) { h -> if (forward) h / 14 else -h / 14 })
                            .togetherWith(fadeOut(tween(110)))
                    },
                    label = "panelTab",
                    modifier = Modifier.weight(1f)
                ) { tab ->
                    when (tab) {
                        NextTab.SEARCH_LIST -> SearchListScreen(ui)
                        NextTab.SAVED_LIST -> SavedListScreen(ui)
                        NextTab.MEMORY -> MemoryScreen(ui)
                        NextTab.SETTINGS -> SettingsScreen(ui, embedded = true)
                    }
                }

                RegionSummaryBar(
                    codes = orderedSelectedRegions,
                    checkedItemCount = checkedCount,
                    onLongPress = { Session.openSheet(Sheet.REGION) },
                    onClick = { Session.openSheet(Sheet.REGION) }
                )
            }

            SheetHost(ui)
            ui.toast?.let { ToastBubble(it) { Session.consumeToast() } }
        }
    }
}

@Composable
fun ToastBubble(msg: String, onDone: () -> Unit) {
    LaunchedEffect(msg) {
        kotlinx.coroutines.delay(2200)
        onDone()
    }
    // 界面调整 4：结果打印输出在屏幕右下角显示（参考图7软件的效果）
    Box(
        Modifier
            .fillMaxSize()
            .padding(end = 16.dp, bottom = 24.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally(tween(220)) { it / 2 } + fadeIn(tween(220)) + scaleIn(tween(220), 0.92f),
            exit = slideOutHorizontally(tween(160)) { it / 2 } + fadeOut(tween(160))
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        color = if (Rose.IsDark) Color(0xF21C181E) else Color(0xF8FFFFFF),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (Rose.IsDark) Rose.Primary.copy(alpha = 0.45f) else Rose.Divider,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Rose.Green)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "GGNext",
                        style = monoStyle(11, if (Rose.IsDark) Rose.TextSecond else Color(0xFF6B6875), weight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(1.5.dp))
                    Text(
                        text = tr(msg),
                        color = Rose.Text,
                        fontSize = fsp(12.5f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun handleTool(
    id: ToolId,
    ui: Ui,
    onCopyText: (String) -> Unit
) {
    when (id) {
        ToolId.SEARCH -> Session.openSheet(Sheet.SEARCH)
        ToolId.FUZZY -> Session.openSheet(Sheet.FUZZY)
        ToolId.MASK -> Session.openSheet(Sheet.MASK_SEARCH)
        ToolId.POINTER -> Session.openSheet(Sheet.POINTER_SEARCH)
        ToolId.FILTER -> Session.openSheet(Sheet.FILTER)
        ToolId.EDIT -> {
            val targets = Session.itemsToEdit()
            if (targets.isEmpty()) {
                Session.toast("无项目可修改，请先搜索或勾选项目")
            } else {
                Session.openSheet(Sheet.EDITOR, targets.first(), singleEdit = false)
            }
        }
        ToolId.SAVE -> Session.saveChecked()
        ToolId.REMOVE -> Session.openSheet(Sheet.REMOVE)
        ToolId.REVERT -> Session.revertChecked()
        ToolId.SELECT_ALL -> Session.selectAll()
        ToolId.INVERT -> Session.invertSelection()
        ToolId.CLEAR_SEL -> Session.clearSelection()
        ToolId.COPY_SEARCH -> {
            val checked = Session.checkedItems()
            if (checked.isEmpty()) {
                Session.toast("请先勾选要复制为联合搜索的项目")
            } else {
                val minAddr = checked.minOf { it.address }
                val maxAddr = checked.maxOf { it.address + it.size }
                val area = (maxAddr - minAddr).coerceIn(2L, 65536L)
                val expr = checked.joinToString(";") { "${valueText(it)}${it.typeShort}" } + "::$area"
                onCopyText(expr)
                Session.updateSearch { it.copy(text = expr) }
                Session.toast("已复制联合搜索表达式：$expr")
            }
        }
        ToolId.LOAD_RESULTS -> Session.loadCheckedAsResults()
        ToolId.LOAD_CHANGED -> Session.loadChangedAsResults()
        ToolId.FORMAT -> Session.openSheet(Sheet.VALUE_FORMAT)
        ToolId.OFFSET, ToolId.CALC_OFFSET -> Session.openSheet(Sheet.OFFSET)
        ToolId.ADDRESS -> {
            val first = Session.checkedItems().firstOrNull() ?: ui.results.firstOrNull() ?: ui.saved.firstOrNull()
            Session.openSheet(Sheet.ADDRESS_EDIT, first)
        }
        ToolId.LOAD, ToolId.SAVE_FILE, ToolId.EXPORT -> Session.openSheet(Sheet.FILE_MANAGER)
        ToolId.GOTO -> Session.openSheet(Sheet.GOTO)
        ToolId.COPY_MEM -> Session.openSheet(Sheet.COPY_MEMORY)
        ToolId.DUMP -> Session.openSheet(Sheet.DUMP)
        ToolId.ALLOCATE -> Session.openSheet(Sheet.ALLOCATE)
        ToolId.BACK -> Session.memoryBack()
        ToolId.FORWARD -> Session.memoryForward()
        ToolId.SPEED -> Session.openSheet(Sheet.SPEED)
        ToolId.EXEC_SCRIPT -> Session.openSheet(Sheet.SCRIPT)
        ToolId.MENU -> Session.openSheet(Sheet.TOOLBAR_ORDER)
    }
}
