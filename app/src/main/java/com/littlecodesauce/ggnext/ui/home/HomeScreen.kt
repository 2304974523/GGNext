package com.littlecodesauce.ggnext.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.core.WorkMode
import com.littlecodesauce.ggnext.service.OverlayManager
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.screen.RowDivider
import com.littlecodesauce.ggnext.ui.screen.SectionLabel
import com.littlecodesauce.ggnext.ui.screen.SettingsCard
import com.littlecodesauce.ggnext.ui.screen.SliderRow
import com.littlecodesauce.ggnext.ui.screen.SwitchRow
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import kotlin.math.roundToInt

/**
 * 单一软件主页 + 右上角菜单跳转「软件设置 / 关于 / 帮助 / 日志」
 */
@Composable
fun HomeScreen(
    ui: Ui,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    var showSettingsPage by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettingsPage && ui.sheet == Sheet.NONE && !ui.panelVisible) {
        showSettingsPage = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Rose.Surface)
    ) {
        AnimatedContent(
            targetState = showSettingsPage,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180)))
                        .togetherWith(fadeOut(tween(140)))
                } else {
                    fadeIn(tween(180))
                        .togetherWith(slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(140)))
                }
            },
            label = "homeNavigation",
            modifier = Modifier.fillMaxSize()
        ) { inSettings ->
            if (!inSettings) {
                SingleHomePage(
                    ui = ui,
                    onStartOverlay = onStartOverlay,
                    onStopOverlay = onStopOverlay,
                    onOpenSettings = { showSettingsPage = true }
                )
            } else {
                HomeSettingsPage(
                    ui = ui,
                    onBack = { showSettingsPage = false }
                )
            }
        }
    }
}

@Composable
private fun SingleHomePage(
    ui: Ui,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val hasOverlayPerm = remember(ui.floatingBallVisible, ui.panelVisible) {
        OverlayManager.canDraw(ctx)
    }
    val isRunning = ui.floatingBallVisible
    val primary = MaterialTheme.colorScheme.primary
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // 顶部栏：标题 + 右上角菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primary.copy(alpha = 0.16f))
                    .border(1.dp, primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("G", color = primary, fontSize = fsp(20f), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GGNext", color = Rose.Text, fontSize = fsp(21f), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("v0.4", color = Rose.TextDim, fontSize = fsp(12f), fontWeight = FontWeight.Medium)
                }
                Text(
                    text = tr("Android 内存调试工具"),
                    color = Rose.TextSecond,
                    fontSize = fsp(12f)
                )
            }

            Box {
                var menuPressed by remember { mutableStateOf(false) }
                val menuScale by animateFloatAsState(if (menuPressed) 0.9f else 1f, Motion.Fast, label = "menuBtn")
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(menuScale)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Rose.Card)
                        .border(1.dp, Rose.Divider, RoundedCornerShape(11.dp))
                        .clickableWithTooltip(
                            onPressChange = { menuPressed = it },
                            onClick = { menuExpanded = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = tr("菜单"),
                        tint = Rose.Text,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(Rose.CardHigh)
                ) {
                    DropdownMenuItem(
                        text = { Text(tr("软件设置"), color = Rose.Text, fontSize = fsp(14f)) },
                        leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = primary, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("使用帮助"), color = Rose.Text, fontSize = fsp(14f)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = Rose.TextSecond, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuExpanded = false
                            Session.openSheet(Sheet.HELP)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("运行日志"), color = Rose.Text, fontSize = fsp(14f)) },
                        leadingIcon = { Icon(Icons.Rounded.BugReport, null, tint = Rose.TextSecond, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuExpanded = false
                            Session.openSheet(Sheet.LOG)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("关于"), color = Rose.Text, fontSize = fsp(14f)) },
                        leadingIcon = { Icon(Icons.Rounded.Info, null, tint = Rose.TextSecond, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuExpanded = false
                            Session.openSheet(Sheet.ABOUT)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // 主服务卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Rose.Card)
                .border(
                    1.dp,
                    if (isRunning) Rose.Green.copy(alpha = 0.35f) else Rose.Divider,
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr(if (isRunning) "悬浮窗运行中" else "悬浮窗未启动"),
                    color = Rose.Text,
                    fontSize = fsp(17f),
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isRunning) Rose.Green.copy(alpha = 0.16f) else Rose.CardHigh)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isRunning) Rose.Green else Rose.TextDim)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = tr(if (isRunning) "已启动" else "未启动"),
                        color = if (isRunning) Rose.Green else Rose.TextSecond,
                        fontSize = fsp(11.5f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = tr(
                    if (isRunning) "短按悬浮球打开调试面板，长按悬浮球调节游戏速率"
                    else "启动后将在屏幕显示可拖拽悬浮球，点击即可附加进程并检索内存"
                ),
                color = Rose.TextSecond,
                fontSize = fsp(12.5f),
                lineHeight = fsp(18.5f)
            )

            Spacer(Modifier.height(18.dp))

            if (!isRunning) {
                RoseButton(
                    label = "启动悬浮窗",
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    filled = true
                ) {
                    onStartOverlay()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RoseButton(
                        label = "停止服务",
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        onStopOverlay()
                    }
                    RoseButton(
                        label = "展开面板",
                        modifier = Modifier.weight(1.25f).height(44.dp),
                        filled = true
                    ) {
                        Session.setPanelVisible(true)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionLabel("运行环境")
        Spacer(Modifier.height(6.dp))

        SettingsCard {
            HomeItemRow(
                icon = Icons.Rounded.Layers,
                title = "悬浮窗权限",
                subtitle = if (hasOverlayPerm) "已允许在其他应用上层显示" else "尚未授予系统悬浮窗权限",
                badgeText = if (hasOverlayPerm) "已授权" else "去授权",
                badgeColor = if (hasOverlayPerm) Rose.Green else Rose.Yellow
            ) {
                if (hasOverlayPerm) {
                    Session.toast("系统悬浮窗权限已授予")
                } else {
                    OverlayManager.requestPermission(ctx)
                }
            }

            RowDivider()

            WorkModeSelectorRow(ui = ui)
        }
    }
}

@Composable
private fun HomeSettingsPage(
    ui: Ui,
    onBack: () -> Unit
) {
    val m = ui.metrics
    val currentLangCode = ui.settings["language"] ?: "0"
    val ballSize = (ui.settings["icons_size"]?.toIntOrNull() ?: 48).coerceIn(28, 84)
    val bgOpacityPct = (m.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (m.panelScale * 100f).roundToInt().coerceIn(40, 100)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶部返回栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Rose.Card)
                    .border(1.dp, Rose.Divider, RoundedCornerShape(10.dp))
                    .clickableWithTooltip(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("返回"),
                    tint = Rose.Text,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = tr("软件设置"),
                color = Rose.Text,
                fontSize = fsp(19f),
                fontWeight = FontWeight.SemiBold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("mode_header") { SectionLabel("运行方式") }
            item("mode_card") {
                SettingsCard {
                    WorkModeSelectorRow(ui = ui)
                }
            }

            item("theme_header") { SectionLabel("主题与外观") }
            item("theme_card") {
                SettingsCard {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(tr("主题配色"), color = Rose.Text, fontSize = fsp(14.5f), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            ThemePreset.entries.forEach { p ->
                                val selected = ui.preset == p
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(p.seed, CircleShape)
                                        .then(
                                            if (selected) Modifier.border(2.dp, Rose.Text, CircleShape)
                                            else Modifier
                                        )
                                        .clickableWithTooltip {
                                            Session.setPreset(p)
                                            Session.toast("已切换主题：${tr(p.label)}")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(Icons.Rounded.Check, null, tint = Color(0xFF161114), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    RowDivider()

                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(tr("深色模式"), color = Rose.Text, fontSize = fsp(14.5f), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DarkMode.entries.forEach { mode ->
                                val selected = ui.darkMode == mode
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .background(
                                            if (selected) Rose.ChipSelected else Rose.CardHigh,
                                            RoundedCornerShape(9.dp)
                                        )
                                        .border(
                                            width = if (selected) 1.dp else 0.dp,
                                            color = if (selected) Rose.Primary else Color.Transparent,
                                            shape = RoundedCornerShape(9.dp)
                                        )
                                        .clickableWithTooltip {
                                            Session.setDarkMode(mode)
                                            Session.toast("${tr("深色模式")}：${tr(mode.label)}")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        tr(mode.label),
                                        color = if (selected) Rose.Text else Rose.TextSecond,
                                        fontSize = fsp(13f),
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    RowDivider()
                    SwitchRow("Android 12+ 动态壁纸取色", ui.dynamicColor) {
                        val next = !ui.dynamicColor
                        Session.setDynamicColor(next)
                        Session.toast(if (next) "已开启壁纸动态取色" else "已关闭壁纸动态取色")
                    }
                    RowDivider()
                    SliderRow("背景透明度", bgOpacityPct.toFloat(), 25f..100f, "$bgOpacityPct%") { pct ->
                        val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                        Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
                    }
                    SliderRow("页面屏幕占比", panelScalePct.toFloat(), 40f..100f, "$panelScalePct%") { pct ->
                        val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                        Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
                    }
                    SliderRow("界面字体缩放", m.fontScale, 0.7f..1.3f, "%.2fx".format(m.fontScale)) {
                        Session.setSetting("font_scale", "%.2f".format(it))
                    }
                    SliderRow("弹窗圆角半径", m.dialogCorner.toFloat(), 0f..28f, "${m.dialogCorner}dp") {
                        Session.setSetting("dialog_corner", it.toInt().toString())
                    }
                    SliderRow("悬浮球尺寸", ballSize.toFloat(), 32f..76f, "${ballSize}dp") {
                        Session.setSetting("icons_size", it.toInt().toString())
                    }
                    RowDivider()
                    SwitchRow("列表入场与骨架屏动画", m.skeletonLoading) {
                        val next = !m.skeletonLoading
                        Session.setSetting("skeleton", if (next) "1" else "0")
                        Session.toast(if (next) "已启用列表动画" else "已关闭列表动画")
                    }
                }
            }

            item("lang_header") { SectionLabel("语言与其它") }
            item("lang_card") {
                SettingsCard {
                    HomeItemRow(
                        icon = Icons.Rounded.Language,
                        title = "界面语言",
                        subtitle = if (currentLangCode == "1" || currentLangCode == "2") "English" else "简体中文",
                        badgeText = "切换",
                        badgeColor = Rose.Primary
                    ) {
                        Session.openSheet(Sheet.LANGUAGE)
                    }
                    RowDivider()
                    HomeItemRow(
                        icon = Icons.Rounded.RestartAlt,
                        title = "恢复默认设置",
                        subtitle = "重置主题、透明度、屏幕占比、字号与语言设置"
                    ) {
                        Session.resetAppSettings()
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkModeSelectorRow(ui: Ui) {
    val primary = MaterialTheme.colorScheme.primary
    val isRoot = ui.workMode == WorkMode.ROOT
    val isNoRoot = ui.workMode == WorkMode.NO_ROOT

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(tr("工作模式"), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.SemiBold)
            }

            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Rose.Surface)
                    .border(1.dp, Rose.Divider, RoundedCornerShape(8.dp))
                    .padding(2.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isRoot) primary.copy(alpha = 0.22f) else Color.Transparent)
                        .clickableWithTooltip {
                            Session.setWorkMode(WorkMode.ROOT)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tr("Root"),
                        color = if (isRoot) primary else Rose.TextSecond,
                        fontSize = fsp(12f),
                        fontWeight = if (isRoot) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isNoRoot) Rose.Cyan.copy(alpha = 0.22f) else Color.Transparent)
                        .clickableWithTooltip {
                            Session.setWorkMode(WorkMode.NO_ROOT)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tr("免 Root"),
                        color = if (isNoRoot) Rose.Cyan else Rose.TextSecond,
                        fontSize = fsp(12f),
                        fontWeight = if (isNoRoot) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Rose.Surface.copy(alpha = 0.55f))
                .clickableWithTooltip {
                    when (ui.workMode) {
                        WorkMode.ROOT -> {
                            if (!ui.hasRootPermission) Session.refreshRootStatus(promptIfMissing = true)
                            else Session.toast("当前运行于 Root 直读模式")
                        }
                        WorkMode.NO_ROOT -> Session.toast("当前运行于免 Root 共享 UID 模式")
                    }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr(
                    when (ui.workMode) {
                        WorkMode.ROOT -> if (ui.hasRootPermission) "Root 权限已获取，支持全系统进程读写" else "未检测到 Root 授权，点击检测或切换至免 Root"
                        WorkMode.NO_ROOT -> "适用虚拟空间及共享 UID 环境，无需 Root 权限"
                    }
                ),
                color = if (ui.workMode == WorkMode.ROOT && !ui.hasRootPermission) Rose.Red else Rose.TextSecond,
                fontSize = fsp(12f),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(
                        when (ui.workMode) {
                            WorkMode.ROOT -> if (ui.hasRootPermission) Rose.Green.copy(alpha = 0.18f) else Rose.Red.copy(alpha = 0.18f)
                            WorkMode.NO_ROOT -> Rose.Cyan.copy(alpha = 0.18f)
                        },
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = when (ui.workMode) {
                        WorkMode.ROOT -> if (ui.hasRootPermission) tr("已授权") else tr("未授权")
                        WorkMode.NO_ROOT -> tr("共享 UID")
                    },
                    color = when (ui.workMode) {
                        WorkMode.ROOT -> if (ui.hasRootPermission) Rose.Green else Rose.Red
                        WorkMode.NO_ROOT -> Rose.Cyan
                    },
                    fontSize = fsp(11f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun HomeItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color = Rose.Primary,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (pressed) Rose.CardHigh else Color.Transparent,
        Motion.ColorFast,
        label = "homeItemBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(Rose.CardHigh, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(14.5f), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(tr(subtitle), color = Rose.TextSecond, fontSize = fsp(12f))
        }
        if (badgeText != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(badgeColor.copy(alpha = 0.16f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(tr(badgeText), color = badgeColor, fontSize = fsp(11.5f), fontWeight = FontWeight.Medium)
            }
        } else {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Rose.TextDim, modifier = Modifier.size(18.dp))
        }
    }
}
