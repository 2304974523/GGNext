package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.littlecodesauce.ggnext.ui.components.GgSwitch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlin.math.roundToInt

/**
 * 悬浮窗内部的设置页：
 * - 界面调整 4：通用设置功能页放在最上方
 * - 界面调整 2：如「对游戏隐藏 GG」之类的浮动按钮，按钮控件右上方不再显示当前选择的是什么按钮
 */
@Composable
fun SettingsScreen(ui: Ui, embedded: Boolean = false) {
    val autopause = (ui.settings["autopause"] ?: "0") == "1"
    val fastFreeze = (ui.settings["fast_freeze"] ?: "0") == "1"
    val vspaceRoot = (ui.settings["vspace_root"] ?: "0") == "1"
    val hideLevel = ui.settings["hide_from_game"] ?: "0"
    val ptraceBypass = ui.settings["ptrace_bypass"] ?: "0"
    val searchHelper = ui.settings["search_helper"] ?: "2"
    val preventUnload = ui.settings["prevent_unload"] ?: "0"
    val skipMem = ui.settings["skip_memory"] ?: "2"
    val disasmEngine = ui.settings["disasm_engine"] ?: "1"
    val freezeInterval = ui.settings["freeze_interval"]?.toLongOrNull() ?: 33000L

    val bgOpacityPct = (ui.metrics.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (ui.metrics.panelScale * 100f).roundToInt().coerceIn(40, 100)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. 界面调整 4：通用设置功能页放在最上方
        item("general_section") { SectionLabel("通用设置") }
        item("general_card") {
            SettingsCard {
                SliderRow(
                    title = "背景透明度",
                    value = bgOpacityPct.toFloat(),
                    range = 25f..100f,
                    display = "$bgOpacityPct%"
                ) { pct ->
                    val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                    Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.Security,
                    title = "工作模式",
                    subtitle = when (ui.workMode) {
                        com.littlecodesauce.ggnext.core.WorkMode.ROOT -> "Root 模式 (${if (ui.hasRootPermission) "已授权 UID 0" else "未授权，点击切换"})"
                        com.littlecodesauce.ggnext.core.WorkMode.NO_ROOT -> "免 Root 模式 (共享 UID / 虚拟空间沙盒)"
                        com.littlecodesauce.ggnext.core.WorkMode.DEMO -> "演示模式 (纯沙盒模拟环境)"
                    }
                ) {
                    val nextMode = when (ui.workMode) {
                        com.littlecodesauce.ggnext.core.WorkMode.ROOT -> com.littlecodesauce.ggnext.core.WorkMode.NO_ROOT
                        com.littlecodesauce.ggnext.core.WorkMode.NO_ROOT -> com.littlecodesauce.ggnext.core.WorkMode.DEMO
                        com.littlecodesauce.ggnext.core.WorkMode.DEMO -> com.littlecodesauce.ggnext.core.WorkMode.ROOT
                    }
                    Session.setWorkMode(nextMode)
                }

                RowDivider()

                SliderRow(
                    title = "页面屏幕占比",
                    value = panelScalePct.toFloat(),
                    range = 40f..100f,
                    display = "$panelScalePct%"
                ) { pct ->
                    val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                    Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.Layers,
                    title = "工具栏顺序与增删",
                    subtitle = "调整搜索列表 / 保存列表 / 内存页工具栏图标"
                ) {
                    Session.openSheet(Sheet.TOOLBAR_ORDER)
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.Palette,
                    title = "外观与字体缩放",
                    subtitle = "当前主题：${tr(ui.preset.label)} · ${tr(ui.darkMode.label)} · ${(ui.metrics.panelScale * 100).roundToInt()}%"
                ) {
                    Session.openSheet(Sheet.APPEARANCE)
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.Language,
                    title = "语言 (Language)",
                    subtitle = when (ui.settings["language"]) {
                        "1", "2" -> "English"
                        else -> "简体中文"
                    }
                ) {
                    Session.openSheet(Sheet.LANGUAGE)
                }
            }
        }

        // 2. 游戏保护设置
        item("protect_section") { SectionLabel("游戏保护设置") }
        item("protect_card") {
            SettingsCard {
                ChoiceGroupRow(
                    title = "对游戏隐藏 GG",
                    options = listOf(
                        "0" to "关闭",
                        "1" to "1 (慢)",
                        "2" to "2 (慢)",
                        "3" to "3 (慢)",
                        "4" to "4 (慢)"
                    ),
                    selectedValue = hideLevel
                ) { code, label ->
                    Session.setSetting("hide_from_game", code)
                    Session.toast("${tr("对游戏隐藏 GG")}：${tr(label)}")
                }

                RowDivider()

                ChoiceGroupRow(
                    title = "用于 ptrace 保护的旁路模式",
                    options = listOf(
                        "0" to "恢复",
                        "1" to "不处理",
                        "2" to "冻结",
                        "3" to "关闭"
                    ),
                    selectedValue = ptraceBypass
                ) { code, label ->
                    Session.setSetting("ptrace_bypass", code)
                    Session.toast("${tr("用于 ptrace 保护的旁路模式")}：${tr(label)}")
                }

                RowDivider()

                SwitchRow("自动暂停游戏", autopause) {
                    val next = if (autopause) "0" else "1"
                    Session.setSetting("autopause", next)
                    Session.toast(if (next == "1") "已开启自动暂停游戏" else "已关闭自动暂停游戏")
                }

                RowDivider()

                ChoiceGroupRow(
                    title = "搜索助手",
                    options = listOf(
                        "0" to "关闭",
                        "1" to "转到游戏",
                        "2" to "转到游戏并返回"
                    ),
                    selectedValue = searchHelper
                ) { code, label ->
                    Session.setSetting("search_helper", code)
                    Session.toast("${tr("搜索助手")}：${tr(label)}")
                }

                RowDivider()

                SwitchRow("在虚拟空间中使用 Root", vspaceRoot) {
                    val next = if (vspaceRoot) "0" else "1"
                    Session.setSetting("vspace_root", next)
                    Session.toast(if (next == "1") "已开启在虚拟空间中使用 Root" else "已关闭在虚拟空间中使用 Root")
                }

                RowDivider()

                ChoiceGroupRow(
                    title = "运行守护",
                    options = listOf(
                        "0" to "关闭",
                        "1" to "级别 1",
                        "2" to "级别 2",
                        "3" to "级别 3"
                    ),
                    selectedValue = preventUnload
                ) { code, label ->
                    Session.setSetting("prevent_unload", code)
                    Session.toast("${tr("运行守护")}：${tr(label)}")
                }
            }
        }

        // 3. 内存设置
        item("memory_section") { SectionLabel("内存设置") }
        item("memory_card") {
            SettingsCard {
                ChoiceGroupRow(
                    title = "反汇编/汇编引擎",
                    subtitle = "使用 Capstone 反汇编，Keystone 汇编。",
                    options = listOf(
                        "0" to "GG101",
                        "1" to "Cap+Key"
                    ),
                    selectedValue = disasmEngine
                ) { code, label ->
                    Session.setSetting("disasm_engine", code)
                    Session.toast("${tr("反汇编/汇编引擎")}：$label")
                }

                RowDivider()

                ChoiceGroupRow(
                    title = "跳过内存区域",
                    options = listOf(
                        "0" to "否 (慢)",
                        "1" to "空内存",
                        "2" to "空内存 + Zygote"
                    ),
                    selectedValue = skipMem
                ) { code, label ->
                    Session.setSetting("skip_memory", code)
                    Session.toast("${tr("跳过内存区域")}：${tr(label)}")
                }

                RowDivider()

                SwitchRow("快速冻结", fastFreeze) {
                    val next = if (fastFreeze) "0" else "1"
                    Session.setSetting("fast_freeze", next)
                    Session.toast(if (next == "1") "已开启快速冻结" else "已关闭快速冻结")
                }

                RowDivider()

                SliderRow(
                    title = "冻结间隔 (毫秒)",
                    value = (freezeInterval / 1000f).coerceIn(5f, 500f),
                    range = 5f..500f,
                    display = "${(freezeInterval / 1000L).coerceAtLeast(5L)} ms"
                ) {
                    Session.setSetting("freeze_interval", (it.toLong() * 1000L).toString())
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.Memory,
                    title = "进程选择",
                    subtitle = ui.target?.let { "[${it.pid}] ${it.label} (${if (it.x64) "x64" else "x86"})" } ?: "未选择进程，点击选择"
                ) {
                    Session.openSheet(Sheet.PROCESS)
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.Layers,
                    title = "内存区域",
                    subtitle = ui.selectedRegions.joinToString(", ").ifEmpty { "未选择" }
                ) {
                    Session.openSheet(Sheet.REGION)
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.FilterAlt,
                    title = "搜索结果过滤器",
                    subtitle = "最大显示 ${ui.filter.maxShow} 条 · 地址/数值/类型筛选"
                ) {
                    Session.openSheet(Sheet.FILTER)
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "数值显示格式",
                    subtitle = "当前：${Formatter.maskSummary(ui.valueMask)}"
                ) {
                    Session.openSheet(Sheet.VALUE_FORMAT)
                }

                RowDivider()

                NavRow(
                    icon = Icons.Rounded.Speed,
                    title = "变速倍率设置",
                    subtitle = "当前：${ui.speedText}"
                ) {
                    Session.openSheet(Sheet.SPEED)
                }
            }
        }

        // 4. 操作与诊断
        item("op_section") { SectionLabel("操作与诊断") }
        item("op_card") {
            SettingsCard {
                NavRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = "清除输入历史",
                    subtitle = "当前共有 ${ui.history.size} 条输入记录"
                ) {
                    Session.clearHistory()
                }
                RowDivider()
                NavRow(
                    icon = Icons.Rounded.BugReport,
                    title = "运行日志",
                    subtitle = "查看引擎与附加记录"
                ) {
                    Session.openSheet(Sheet.LOG)
                }
                RowDivider()
                NavRow(
                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                    title = "帮助文档",
                    subtitle = "查看搜索语法与功能说明"
                ) {
                    Session.openSheet(Sheet.HELP)
                }
                RowDivider()
                NavRow(
                    icon = Icons.Rounded.Info,
                    title = "关于",
                    subtitle = "GGNext v0.4"
                ) {
                    Session.openSheet(Sheet.ABOUT)
                }
                RowDivider()
                NavRow(
                    icon = Icons.Rounded.Close,
                    title = "终止此进程",
                    subtitle = ui.target?.let { "终止当前选中的进程 [${it.pid}] ${it.label}" } ?: "当前未附加目标进程"
                ) {
                    Session.terminateTargetProcess()
                }
                RowDivider()
                NavRow(
                    icon = Icons.Rounded.Close,
                    title = "退出",
                    subtitle = "关闭悬浮窗并完全退出修改器"
                ) {
                    Session.exitApp()
                }
            }
        }
    }
}

/**
 * 界面调整 2：
 * 如「对游戏隐藏 GG」之类的浮动按钮，按钮控件右上方不再显示当前选择的是什么按钮，
 * 仅保留标题（与可选副标题）及下方横向排列的浮动选项按钮。
 */
@Composable
fun ChoiceGroupRow(
    title: String,
    subtitle: String? = null,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String, String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = tr(title),
            color = Rose.Text,
            fontSize = fsp(15f),
            fontWeight = FontWeight.Medium
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = tr(subtitle),
                color = Rose.TextSecond,
                fontSize = fsp(11.5f)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { (code, label) ->
                val selected = code == selectedValue
                var pressed by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (pressed) 0.93f else 1f, Motion.Fast, label = "optScale")
                val bg by animateColorAsState(
                    targetValue = when {
                        pressed -> Rose.PrimaryDeep
                        selected -> Rose.ChipSelected
                        else -> Rose.CardHigh
                    },
                    animationSpec = Motion.ColorFast,
                    label = "optBg"
                )
                val borderCol by animateColorAsState(
                    targetValue = if (selected) Rose.Primary.copy(alpha = 0.65f) else Rose.Divider,
                    animationSpec = Motion.ColorFast,
                    label = "optBorder"
                )

                Box(
                    Modifier
                        .height(34.dp)
                        .scale(scale)
                        .background(bg, RoundedCornerShape(8.dp))
                        .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                        .clickableWithTooltip(
                            onPressChange = { pressed = it },
                            onClick = { onSelect(code, label) }
                        )
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tr(label),
                        color = if (selected) Rose.Text else Rose.TextSecond,
                        fontSize = fsp(12.5f),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        tr(text),
        color = Rose.Primary,
        fontSize = fsp(13f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Rose.Card, RoundedCornerShape(16.dp))
            .border(1.dp, Rose.Divider, RoundedCornerShape(16.dp))
            .padding(vertical = 4.dp)
    ) { content() }
}

@Composable
fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp)
            .height(1.dp)
            .background(Rose.Divider)
    )
}

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (pressed) Rose.CardHigh else Color.Transparent,
        Motion.ColorFast,
        label = "navRowBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(Rose.CardHigh, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
            Text(tr(subtitle), color = Rose.TextSecond, fontSize = fsp(12f))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Rose.TextDim, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableWithTooltip(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr(title), color = Rose.Text, fontSize = fsp(15f), modifier = Modifier.weight(1f))
        com.littlecodesauce.ggnext.ui.components.GgSwitch(
            checked = checked,
            onCheckedChange = { onToggle() }
        )
    }
}

/** 统一滑条：无刻度黑点 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(14.5f), modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .background(Rose.CardHigh, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) { Text(display, color = Rose.TextSecond, fontSize = fsp(12f)) }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Rose.Primary,
                activeTrackColor = Rose.Primary,
                inactiveTrackColor = Rose.CardHigh,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}
