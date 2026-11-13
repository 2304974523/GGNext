package com.littlecodesauce.ggnext.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
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
 * 软件主界面（非悬浮窗）：
 * - 版本号更新为 v0.4
 * - 界面调整 9：软件设置里的「语言」仅保留简体中文、繁體中文和英语，并支持全界面实时语言切换
 */
@Composable
fun HomeScreen(
    ui: Ui,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    var currentPage by remember { mutableIntStateOf(0) } // 0 = 启动主页, 1 = 软件设置

    Box(
        Modifier
            .fillMaxSize()
            .background(Rose.Surface)
    ) {
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                fadeIn(tween(180)).togetherWith(fadeOut(tween(140)))
            },
            label = "homeTabContent",
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page == 0) {
                LauncherHomePage(
                    ui = ui,
                    onStartOverlay = onStartOverlay,
                    onStopOverlay = onStopOverlay,
                    onOpenSettings = { currentPage = 1 }
                )
            } else {
                AppSelfSettingsPage(ui = ui)
            }
        }

        // 底部导航栏（主页 / 软件设置）
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .fillMaxWidth(0.68f)
                .height(60.dp)
                .background(Rose.Card, RoundedCornerShape(30.dp))
                .border(1.dp, Rose.Divider, RoundedCornerShape(30.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BottomTabItem(
                icon = Icons.Rounded.Home,
                label = "主页",
                selected = currentPage == 0,
                onClick = { currentPage = 0 }
            )
            BottomTabItem(
                icon = Icons.Rounded.Settings,
                label = "设置",
                selected = currentPage == 1,
                onClick = { currentPage = 1 }
            )
        }
    }
}

@Composable
private fun LauncherHomePage(
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
    val currentLangCode = ui.settings["language"] ?: "0"

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 顶部品牌栏
        item("brand_header") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(primary.copy(alpha = 0.18f))
                        .border(1.dp, primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("G", color = primary, fontSize = fsp(24f), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GGNext", color = Rose.Text, fontSize = fsp(24f), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .background(Rose.CardHigh, RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text("v0.4", color = primary, fontSize = fsp(11f), fontWeight = FontWeight.Medium)
                        }
                    }
                    Text(
                        tr(if (isRunning) "悬浮球服务运行中 · 短按打开面板 / 长按调节变速" else "新一代高性能内存调试工具"),
                        color = if (isRunning) Rose.Green else Rose.TextSecond,
                        fontSize = fsp(12.5f)
                    )
                }
            }
        }

        // 核心启动卡片（大启动按钮）
        item("hero_launch_card") {
            val pulseTransition = rememberInfiniteTransition(label = "launchPulse")
            val pulseScale by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isRunning) 1.05f else 1.02f,
                animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                label = "pulse"
            )
            var btnPressed by remember { mutableStateOf(false) }
            val pressScale by animateFloatAsState(if (btnPressed) 0.94f else 1f, Motion.Fast, label = "launchPress")

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Rose.Card, RoundedCornerShape(22.dp))
                    .border(1.dp, if (isRunning) Rose.Green.copy(alpha = 0.35f) else Rose.Divider, RoundedCornerShape(22.dp))
                    .padding(horizontal = 20.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(116.dp)
                        .scale(pulseScale * pressScale)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) Rose.Green.copy(alpha = 0.2f) else primary.copy(alpha = 0.22f),
                            CircleShape
                        )
                        .border(
                            width = 2.5.dp,
                            color = if (isRunning) Rose.Green else primary,
                            shape = CircleShape
                        )
                        .clickableWithTooltip(
                            onPressChange = { btnPressed = it },
                            onClick = {
                                if (!isRunning) {
                                    onStartOverlay()
                                } else {
                                    Session.setPanelVisible(true)
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isRunning) Icons.Rounded.Layers else Icons.Rounded.PowerSettingsNew,
                            contentDescription = "启动",
                            tint = if (isRunning) Rose.Green else primary,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tr(if (isRunning) "已启动" else "启动"),
                            color = Rose.Text,
                            fontSize = fsp(16f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = tr(if (isRunning) "悬浮球已在屏幕显示" else "点击上方按钮启动悬浮球"),
                    color = Rose.Text,
                    fontSize = fsp(16f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tr(
                        if (isRunning) {
                            "短按「G」悬浮球展开修改器（面板展开时悬浮球自动隐藏）；长按「G」悬浮球呼出左右变速调节栏"
                        } else {
                            "启动后将在屏幕生成可拖拽悬浮球，点击悬浮球进入修改器界面"
                        }
                    ),
                    color = Rose.TextSecond,
                    fontSize = fsp(12.5f),
                    textAlign = TextAlign.Center
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
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RoseButton(
                            label = "停止悬浮窗",
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            onStopOverlay()
                        }
                        RoseButton(
                            label = "展开修改器",
                            modifier = Modifier.weight(1f).height(44.dp),
                            filled = true
                        ) {
                            Session.setPanelVisible(true)
                        }
                    }
                }
            }
        }

        // 环境与权限检测卡片
        item("env_section") { SectionLabel("运行环境与工作模式") }
        item("env_card") {
            SettingsCard {
                HomeRow(
                    icon = Icons.Rounded.Layers,
                    title = "悬浮窗权限",
                    subtitle = if (hasOverlayPerm) "已授予系统悬浮窗权限，支持跨应用显示" else "点击前往系统设置授予悬浮窗权限",
                    badgeText = if (hasOverlayPerm) "已就绪" else "去授权",
                    badgeColor = if (hasOverlayPerm) Rose.Green else Rose.Yellow
                ) {
                    if (hasOverlayPerm) {
                        Session.toast("系统悬浮窗权限已授予")
                    } else {
                        OverlayManager.requestPermission(ctx)
                    }
                }
                RowDivider()
                // 工作模式选择栏（Root 模式 vs 免 Root 模式）
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
                        // 模式切换 Pills（支持 Root / 免 Root / 演示模式）
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Rose.Surface)
                                .border(1.dp, Rose.Divider, RoundedCornerShape(8.dp))
                                .padding(2.dp)
                        ) {
                            val isRoot = ui.workMode == WorkMode.ROOT
                            val isNoRoot = ui.workMode == WorkMode.NO_ROOT
                            val isDemo = ui.workMode == WorkMode.DEMO

                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isRoot) primary.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickableWithTooltip(tooltip = "Root 模式：需 Root 权限，全系统跨进程直读") {
                                        Session.setWorkMode(WorkMode.ROOT)
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tr("Root 模式"),
                                    color = if (isRoot) primary else Rose.TextSecond,
                                    fontSize = fsp(12f),
                                    fontWeight = if (isRoot) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isNoRoot) Rose.Cyan.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickableWithTooltip(tooltip = "免 Root 模式：共享 UID / 虚拟空间沙盒") {
                                        Session.setWorkMode(WorkMode.NO_ROOT)
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tr("免 Root 模式"),
                                    color = if (isNoRoot) Rose.Cyan else Rose.TextSecond,
                                    fontSize = fsp(12f),
                                    fontWeight = if (isNoRoot) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDemo) Rose.Yellow.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickableWithTooltip(tooltip = "演示模式：纯沙盒模拟环境，无需权限，完整体验功能与交互") {
                                        Session.setWorkMode(WorkMode.DEMO)
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tr("演示模式"),
                                    color = if (isDemo) Rose.Yellow else Rose.TextSecond,
                                    fontSize = fsp(12f),
                                    fontWeight = if (isDemo) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 当前模式详细说明与状态
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Rose.Surface.copy(alpha = 0.5f))
                            .clickableWithTooltip {
                                when (ui.workMode) {
                                    WorkMode.ROOT -> {
                                        if (!ui.hasRootPermission) Session.refreshRootStatus(promptIfMissing = true)
                                        else Session.toast("当前运行于 Root 守护直读模式")
                                    }
                                    WorkMode.NO_ROOT -> Session.toast("当前运行于免 Root 共享 UID 模式")
                                    WorkMode.DEMO -> Session.toast("当前运行于纯沙盒演示模式")
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = tr(
                                    when (ui.workMode) {
                                        WorkMode.ROOT -> {
                                            if (ui.hasRootPermission) "Root 权限已获取 · 真实跨进程内存读写"
                                            else "未检测到 Root 权限 · 点击申请授权或切换至「免 Root」或「演示模式」"
                                        }
                                        WorkMode.NO_ROOT -> "免 Root 共享 UID 模式 · 适用于双开沙箱空间内跨进程读写"
                                        WorkMode.DEMO -> "演示模式 · 纯沙盒模拟环境，无需权限，完整体验功能与交互"
                                    }
                                ),
                                color = if (ui.workMode == WorkMode.ROOT && !ui.hasRootPermission) Rose.Red else Rose.TextSecond,
                                fontSize = fsp(12f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .background(
                                    when (ui.workMode) {
                                        WorkMode.ROOT -> if (ui.hasRootPermission) Rose.Green.copy(alpha = 0.18f) else Rose.Red.copy(alpha = 0.18f)
                                        WorkMode.NO_ROOT -> Rose.Cyan.copy(alpha = 0.18f)
                                        WorkMode.DEMO -> Rose.Yellow.copy(alpha = 0.18f)
                                    },
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = when (ui.workMode) {
                                    WorkMode.ROOT -> if (ui.hasRootPermission) tr("已授权 Root") else tr("未授权")
                                    WorkMode.NO_ROOT -> tr("共享 UID")
                                    WorkMode.DEMO -> tr("演示模式")
                                },
                                color = when (ui.workMode) {
                                    WorkMode.ROOT -> if (ui.hasRootPermission) Rose.Green else Rose.Red
                                    WorkMode.NO_ROOT -> Rose.Cyan
                                    WorkMode.DEMO -> Rose.Yellow
                                },
                                fontSize = fsp(11f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // 快捷入口
        item("quick_section") { SectionLabel("软件快捷入口") }
        item("quick_card") {
            SettingsCard {
                HomeRow(
                    icon = Icons.Rounded.Palette,
                    title = "主题与外观设置",
                    subtitle = "当前主题：${tr(ui.preset.label)} · ${tr(ui.darkMode.label)} · ${(ui.metrics.panelScale * 100).roundToInt()}%"
                ) {
                    Session.openSheet(Sheet.APPEARANCE)
                }
                RowDivider()
                HomeRow(
                    icon = Icons.Rounded.Language,
                    title = "语言 (Language)",
                    subtitle = languageLabel(currentLangCode)
                ) {
                    Session.openSheet(Sheet.LANGUAGE)
                }
                RowDivider()
                HomeRow(
                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                    title = "使用帮助与语法指南",
                    subtitle = "了解精确搜索、模糊搜索、联合搜索与批量修改用法"
                ) {
                    Session.openSheet(Sheet.HELP)
                }
                RowDivider()
                HomeRow(
                    icon = Icons.Rounded.Info,
                    title = "关于软件",
                    subtitle = "GGNext v0.4 · 查看软件信息与更新日志"
                ) {
                    Session.openSheet(Sheet.ABOUT)
                }
            }
        }
    }
}

@Composable
private fun AppSelfSettingsPage(ui: Ui) {
    val m = ui.metrics
    val currentLangCode = ui.settings["language"] ?: "0"
    val ballSize = (ui.settings["icons_size"]?.toIntOrNull() ?: 48).coerceIn(28, 84)
    val bgOpacityPct = (m.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (m.panelScale * 100f).roundToInt().coerceIn(40, 100)

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("settings_title") {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text(tr("软件设置"), color = Rose.Text, fontSize = fsp(26f), fontWeight = FontWeight.SemiBold)
                Text(
                    tr("管理软件语言、主题配色、背景透明度、页面屏幕占比与关于信息"),
                    color = Rose.TextSecond,
                    fontSize = fsp(12.5f)
                )
            }
        }

        // 1. 语言设置
        item("lang_header") { SectionLabel("语言与区域") }
        item("lang_card") {
            SettingsCard {
                HomeRow(
                    icon = Icons.Rounded.Language,
                    title = "语言 (Language)",
                    subtitle = "当前：${languageLabel(currentLangCode)}",
                    badgeText = "切换",
                    badgeColor = Rose.Primary
                ) {
                    Session.openSheet(Sheet.LANGUAGE)
                }
            }
        }

        // 2. 主题与外观
        item("theme_header") { SectionLabel("主题与外观") }
        item("theme_card") {
            SettingsCard {
                HomeRow(
                    icon = Icons.Rounded.Palette,
                    title = "主题配色方案",
                    subtitle = "当前预设：${tr(ui.preset.label)}"
                ) {
                    Session.openSheet(Sheet.APPEARANCE)
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
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
                                    Session.toast("已切换为「${tr(p.label)}」主题")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(Icons.Rounded.Check, null, tint = Color(0xFF161114), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                RowDivider()

                // 深色模式三态切换
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(tr("深色模式"), color = Rose.Text, fontSize = fsp(15f))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DarkMode.entries.forEach { mode ->
                            val selected = ui.darkMode == mode
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(38.dp)
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

        // 3. 关于与软件自身相关功能
        item("about_header") { SectionLabel("关于与支持") }
        item("about_card") {
            SettingsCard {
                HomeRow(
                    icon = Icons.Rounded.Info,
                    title = "关于 GGNext",
                    subtitle = "版本 v0.4 · 架构与环境信息"
                ) {
                    Session.openSheet(Sheet.ABOUT)
                }
                RowDivider()
                HomeRow(
                    icon = Icons.Rounded.HistoryEdu,
                    title = "更新日志",
                    subtitle = "查看 v0.4 版本优化与修复详情"
                ) {
                    Session.openSheet(Sheet.CHANGELOG)
                }
                RowDivider()
                HomeRow(
                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                    title = "使用帮助",
                    subtitle = "搜索语法与功能说明"
                ) {
                    Session.openSheet(Sheet.HELP)
                }
                RowDivider()
                HomeRow(
                    icon = Icons.Rounded.BugReport,
                    title = "软件运行日志",
                    subtitle = "查看内部状态与调试日志"
                ) {
                    Session.openSheet(Sheet.LOG)
                }
                RowDivider()
                HomeRow(
                    icon = Icons.Rounded.RestartAlt,
                    title = "恢复软件默认设置",
                    subtitle = "重置主题、透明度、屏幕占比、字号、语言等软件偏好设置"
                ) {
                    Session.resetAppSettings()
                }
            }
        }

        item("foot") {
            Text(
                "GGNext · v0.4 · Kotlin + Jetpack Compose",
                color = Rose.TextDim,
                fontSize = fsp(11f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BottomTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, Motion.Fast, label = "btmTab")
    val bg by animateColorAsState(
        if (selected) primary.copy(alpha = 0.2f) else Color.Transparent,
        Motion.ColorNormal,
        label = "btmBg"
    )
    val tint by animateColorAsState(
        if (selected) primary else Rose.TextSecond,
        Motion.ColorNormal,
        label = "btmTint"
    )

    Row(
        Modifier
            .scale(scale)
            .height(44.dp)
            .background(bg, RoundedCornerShape(22.dp))
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            tr(label),
            color = if (selected) Rose.Text else Rose.TextSecond,
            fontSize = fsp(14f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun HomeRow(
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
        label = "homeRowBg"
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
                .size(40.dp)
                .background(Rose.CardHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(tr(subtitle), color = Rose.TextSecond, fontSize = fsp(12f))
        }
        if (badgeText != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(badgeColor.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(tr(badgeText), color = badgeColor, fontSize = fsp(11.5f), fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Rose.TextDim, modifier = Modifier.size(18.dp))
    }
}

private fun languageLabel(code: String): String = when (code) {
    "1", "2" -> "English"
    else -> "简体中文 (Simplified Chinese)"
}
