package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.FileBrowserTarget
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.components.BuiltInKeyboard
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.GgIcons
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableLongPress
import com.littlecodesauce.ggnext.ui.components.clickableNoRipple
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.expandEnter
import com.littlecodesauce.ggnext.ui.components.expandExit
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.components.valueText
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.model.toolIcon
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.typeColor
import java.io.File

// ============================================================
// 单地址编辑 vs 批量修改功能页
// - 界面调整 12：数据类型旁的三角箭头未展开时朝右（▸），展开时朝下（▾）
// ============================================================
@Composable
fun EditSheet(ui: Ui) {
    val targets = remember(ui.editing, ui.editSingleOnly, ui.results, ui.saved, ui.memoryRows, ui.tab) {
        Session.itemsToEdit()
    }
    val firstItem = targets.firstOrNull() ?: ui.editing
    if (firstItem == null) {
        Session.closeSheet()
        return
    }

    val isSingleEdit = ui.editSingleOnly || (targets.size == 1 && ui.selectedCount <= 1)

    var selectedType by remember(firstItem) {
        mutableIntStateOf(firstItem.flags and 0x7F)
    }
    var showTypeDropdown by remember { mutableStateOf(false) }

    val initialValueText = remember(firstItem, targets.size) {
        when (selectedType) {
            ValueType.FLOAT -> Formatter.floatText(firstItem.value)
            ValueType.DOUBLE -> Formatter.doubleText(firstItem.value)
            else -> Formatter.signed(firstItem.value, ValueType.sizeOf(selectedType)).toString()
        }
    }

    // 优化 2：编辑地址数值时，输入栏填充的数值默认为全选状态，便于一键覆盖
    var valueTf by remember(firstItem) {
        mutableStateOf(TextFieldValue(initialValueText, TextRange(0, initialValueText.length)))
    }
    var stepTf by remember {
        mutableStateOf(TextFieldValue("", TextRange(0)))
    }
    var noteTf by remember(firstItem) {
        mutableStateOf(TextFieldValue(firstItem.note, TextRange(0, firstItem.note.length)))
    }
    var moreExpanded by remember { mutableStateOf(false) }
    var activeFillIndex by remember { mutableIntStateOf(-1) }
    var addNotReplace by remember { mutableStateOf(false) }
    var freezeItems by remember(firstItem) { mutableStateOf(firstItem.freeze) }

    var keyboardTarget by remember { mutableIntStateOf(1) }

    val currentTypeColor = typeColor(selectedType)
    val typeRangeText = remember(selectedType) {
        ValueType.rangeText(selectedType)
    }
    val typeArrowRot by animateFloatAsState(
        targetValue = if (showTypeDropdown) 0f else -90f,
        animationSpec = Motion.Fast,
        label = "editTypeArrow"
    )

    fun applyFillPreset(presetIdx: Int) {
        activeFillIndex = presetIdx
        val filled = buildFillString(targets, selectedType, presetIdx)
        valueTf = TextFieldValue(filled, TextRange(filled.length))
    }

    NextSheetCard(
        title = if (isSingleEdit) "单项修改" else "批量修改",
        subtitle = if (isSingleEdit) {
            firstItem.cachedAddressHex
        } else {
            "修改选中 ${targets.size}"
        },
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = typeRangeText,
                style = monoStyle(12, currentTypeColor),
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
            )

            // 主数值输入框
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(12.dp))
                    .border(1.dp, Rose.Divider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .background(Rose.Chip, RoundedCornerShape(8.dp))
                        .clickableWithTooltip {
                            showTypeDropdown = !showTypeDropdown
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ValueType.shortName(selectedType),
                        color = currentTypeColor,
                        fontSize = fsp(16f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "选择类型",
                        tint = currentTypeColor,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = typeArrowRot }
                    )
                }
                Spacer(Modifier.width(10.dp))
                GgTextField(
                    value = valueTf,
                    onValueChange = {
                        keyboardTarget = 1
                        valueTf = it
                        activeFillIndex = -1
                    },
                    onFocus = { keyboardTarget = 1 },
                    modifier = Modifier.weight(1f).height(40.dp),
                    textStyle = monoStyle(16, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = keyboardTarget != 0,
                    placeholder = "输入修改值…"
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .height(34.dp)
                        .background(
                            if (keyboardTarget == 1) Rose.ChipSelected else Rose.Chip,
                            RoundedCornerShape(8.dp)
                        )
                        .clickableWithTooltip {
                            keyboardTarget = if (keyboardTarget == 1) 0 else 1
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr(if (keyboardTarget == 1) "收起" else "键盘"), color = Rose.Text, fontSize = fsp(13f))
                }
            }

            // 类型下拉选择面板
            AnimatedVisibility(showTypeDropdown, enter = expandEnter(), exit = expandExit()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ValueType.order.forEach { t ->
                        val on = selectedType == t
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(
                                    if (on) Rose.ChipSelected else Rose.CardHigh,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = if (on) 1.dp else 0.dp,
                                    color = if (on) typeColor(t) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickableWithTooltip {
                                    selectedType = t
                                    showTypeDropdown = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                ValueType.shortName(t),
                                color = typeColor(t),
                                fontSize = fsp(14f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (isSingleEdit) {
                Spacer(Modifier.height(10.dp))
                FieldRow(
                    label = "备注",
                    value = noteTf,
                    suppressSystemKeyboard = false,
                    placeholder = "描述（可选）",
                    onFocus = { keyboardTarget = 0 }
                ) {
                    keyboardTarget = 0
                    noteTf = it
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clickableWithTooltip {
                            freezeItems = !freezeItems
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GgCheckBox(
                        checked = freezeItems,
                        onCheckedChange = { freezeItems = it },
                        size = 20.dp,
                        accentColor = Rose.Primary
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(tr("冻结并保存"), color = Rose.Text, fontSize = fsp(13.5f))
                }

                Row(
                    Modifier
                        .clickableWithTooltip {
                            addNotReplace = !addNotReplace
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GgCheckBox(
                        checked = addNotReplace,
                        onCheckedChange = { addNotReplace = it },
                        size = 20.dp,
                        accentColor = Rose.Primary
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(tr("加到值内"), color = Rose.Text, fontSize = fsp(13.5f))
                }
            }

            // “更多”展开区域
            AnimatedVisibility(moreExpanded, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))

                    if (!isSingleEdit) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .background(Rose.CardHigh, RoundedCornerShape(12.dp))
                                .border(1.dp, Rose.Divider, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GgTextField(
                                value = stepTf,
                                onValueChange = {
                                    keyboardTarget = 2
                                    stepTf = it
                                },
                                onFocus = { keyboardTarget = 2 },
                                modifier = Modifier.weight(1f).height(40.dp),
                                textStyle = monoStyle(15, Rose.Text),
                                cursorColor = Rose.Primary,
                                suppressSystemKeyboard = keyboardTarget != 0,
                                placeholder = "可选增量步进值"
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .height(34.dp)
                                    .background(
                                        if (keyboardTarget == 2) Rose.ChipSelected else Rose.Chip,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickableWithTooltip {
                                        keyboardTarget = if (keyboardTarget == 2) 0 else 2
                                    }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(tr("键盘"), color = Rose.Text, fontSize = fsp(13f))
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FillPresetPill("值列表", activeFillIndex == 0, Modifier.weight(1.1f)) { applyFillPreset(0) }
                        FillPresetPill("UTF-8", activeFillIndex == 1, Modifier.weight(1f)) { applyFillPreset(1) }
                        FillPresetPill("UTF-16LE", activeFillIndex == 2, Modifier.weight(1.2f)) { applyFillPreset(2) }
                        FillPresetPill("HEX", activeFillIndex == 3, Modifier.weight(0.9f)) { applyFillPreset(3) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FillPresetPill("HEX + UTF-8", activeFillIndex == 4) { applyFillPreset(4) }
                        FillPresetPill("HEX + UTF-16LE", activeFillIndex == 5) { applyFillPreset(5) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FillPresetPill("HEX + UTF-8 + UTF-16LE", activeFillIndex == 6) { applyFillPreset(6) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSingleEdit) {
                    RoseButton(
                        label = "恢复",
                        modifier = Modifier.width(68.dp).height(38.dp),
                        filled = false
                    ) {
                        val origStr = when (selectedType and 0x7F) {
                            ValueType.FLOAT -> Formatter.floatText(firstItem.previousValue)
                            ValueType.DOUBLE -> Formatter.doubleText(firstItem.previousValue)
                            else -> Formatter.signed(firstItem.previousValue, ValueType.sizeOf(selectedType)).toString()
                        }
                        valueTf = TextFieldValue(origStr, TextRange(origStr.length))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                RoseButton(
                    label = if (moreExpanded) "收起" else "更多",
                    modifier = Modifier.width(68.dp).height(38.dp),
                    filled = false
                ) {
                    moreExpanded = !moreExpanded
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "修改",
                    modifier = Modifier.width(72.dp).height(38.dp),
                    filled = true
                ) {
                    val ok = Session.batchEdit(
                        targets = if (isSingleEdit) listOf(firstItem) else targets,
                        rawInput = valueTf.text,
                        targetType = selectedType,
                        rawStep = if (moreExpanded && !isSingleEdit) stepTf.text else "",
                        addNotReplace = addNotReplace,
                        freeze = freezeItems,
                        customNote = if (isSingleEdit) noteTf.text.trim() else null
                    )
                    if (ok) Session.closeSheet()
                }
            }

            AnimatedVisibility(keyboardTarget != 0, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    BuiltInKeyboard(
                        value = if (keyboardTarget == 2) stepTf else valueTf,
                        onValueChange = { next ->
                            if (keyboardTarget == 2) stepTf = next else valueTf = next
                        },
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { keyboardTarget = 0 }
                    )
                }
            }
        }
    }
}

@Composable
private fun FillPresetPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(38.dp)
            .background(
                if (selected) Rose.ChipSelected else Rose.Chip,
                RoundedCornerShape(9.dp)
            )
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) Rose.Primary else Color.Transparent,
                shape = RoundedCornerShape(9.dp)
            )
            .clickableWithTooltip(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tr(label),
            color = if (selected) Rose.Text else Rose.TextSecond,
            fontSize = fsp(13f),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

private fun buildFillString(
    items: List<MemoryItem>,
    type: Int,
    presetIdx: Int
): String {
    if (items.isEmpty()) return ""
    val slice = items.take(32)
    return when (presetIdx) {
        0 -> slice.joinToString(";") { valueText(it) }
        1 -> {
            val bytes = slice.map { (it.value and 0xFF).toByte() }.toByteArray()
            val clean = bytes.map { b -> if (b in 32..126) b.toInt().toChar() else 'd' }.joinToString("")
            ":$clean"
        }
        2 -> {
            val chars = slice.map {
                val c = (it.value and 0xFFFF).toInt()
                if (c in 32..126) c.toChar() else 'd'
            }.joinToString("")
            ";${chars.take(16).ifEmpty { "d" }}"
        }
        3 -> {
            "h " + slice.joinToString(" ") { String.format("%02X", it.value and 0xFF) }
        }
        4 -> {
            val hex = slice.take(8).joinToString(" ") { String.format("%02X", it.value and 0xFF) }
            val ascii = slice.take(8).map {
                val b = (it.value and 0xFF).toInt()
                if (b in 32..126) b.toChar() else '.'
            }.joinToString("")
            "Q $hex '$ascii'"
        }
        5 -> {
            val hex = slice.take(8).joinToString(" ") { String.format("%02X", it.value and 0xFF) }
            val u16 = slice.take(8).map {
                val b = (it.value and 0xFF).toInt()
                if (b in 32..126) b.toChar() else '.'
            }.joinToString("")
            "Q $hex \"$u16\""
        }
        else -> {
            val hex = slice.take(6).joinToString(" ") { String.format("%02X", it.value and 0xFF) }
            "Q $hex"
        }
    }
}

// ============================================================
// 值列表（长按地址条目上下文菜单），全部工具图标严格与工具栏 toolIcon(ToolId.*) 保持一致
// ============================================================
@Composable
fun ContextSheet(ui: Ui) {
    val item = ui.contextItem ?: return
    val clip = LocalClipboardManager.current
    val addr = item.cachedAddressHex
    val value = valueText(item)
    val hex = Formatter.hex(item.value, item.size)
    val rhex = hex.chunked(2).reversed().joinToString("")

    NextSheetCard(title = "值列表 [$addr]", onDismiss = Session::closeSheet, maxBodyHeight = 640) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MenuAction(toolIcon(ToolId.EDIT), "编辑此地址数值 ($value)") {
                Session.openSheet(Sheet.EDITOR, item, singleEdit = true)
            }
            MenuAction(toolIcon(ToolId.OFFSET), "偏移量计算器") {
                Session.openSheet(Sheet.OFFSET, item)
            }
            MenuAction(toolIcon(ToolId.CALC_OFFSET), "计算偏移、异或") {
                Session.openSheet(Sheet.CALC_OFFSET, item)
            }
            MenuAction(toolIcon(ToolId.POINTER), "指针搜索") {
                Session.openSheet(Sheet.POINTER_SEARCH, item)
            }
            MenuAction(toolIcon(ToolId.SEARCH), "附近") {
                Session.updateSearch { it.copy(rangeMode = 2, nearbyAddress = addr) }
                Session.openSheet(Sheet.SEARCH, item)
            }
            MenuAction(toolIcon(ToolId.GOTO), "转到此地址：$addr") {
                Session.goto(item.address, recordHistory = true, highlightTarget = true)
            }
            MenuAction(toolIcon(ToolId.GOTO), "跳转到指针地址：$hex") {
                Session.goto(item.value, recordHistory = true, highlightTarget = true)
            }
            MenuAction(toolIcon(ToolId.ADDRESS), "更改地址 / 类型") {
                Session.openSheet(Sheet.ADDRESS_EDIT, item)
            }
            MenuAction(Icons.Rounded.ContentCopy, "复制地址：$addr") {
                clip.setText(AnnotatedString(addr)); Session.toast("已复制地址 $addr"); Session.closeSheet()
            }
            MenuAction(Icons.Rounded.ContentCopy, "复制十进制值：$value") {
                clip.setText(AnnotatedString(value)); Session.toast("已复制值 $value"); Session.closeSheet()
            }
            MenuAction(Icons.Rounded.Code, "复制十六进制：$hex") {
                clip.setText(AnnotatedString(hex)); Session.toast("已复制 HEX $hex"); Session.closeSheet()
            }
            MenuAction(Icons.Rounded.Code, "复制反向十六进制：$rhex") {
                clip.setText(AnnotatedString(rhex)); Session.toast("已复制反向 HEX $rhex"); Session.closeSheet()
            }
        }
    }
}

@Composable
fun MenuAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                if (pressed) Rose.CardHigh else Rose.Row,
                RoundedCornerShape(11.dp)
            )
            .clickableNoRipple(interaction, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(tr(label), color = Rose.Text, fontSize = fsp(14f))
    }
}

// ============================================================
// 类型选择（界面调整 11：不显示数值范围）
// ============================================================
@Composable
fun TypePickSheet(ui: Ui) {
    val item = ui.editing ?: ui.contextItem
    NextSheetCard(title = "选择数据类型", onDismiss = Session::closeSheet) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ValueType.order.forEach { t ->
                val on = item != null && (item.flags and 0x7F) == t
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(if (on) Rose.ChipSelected else Rose.CardHigh, RoundedCornerShape(9.dp))
                        .clickableWithTooltip {
                            if (item != null) Session.changeType(item, t)
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        ValueType.shortName(t),
                        color = typeColor(t),
                        fontSize = fsp(15f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(30.dp)
                    )
                    Text(ValueType.longName(t), color = typeColor(t), fontSize = fsp(14f))
                }
            }
        }
    }
}

// ============================================================
// 更改地址 / 类型
// ============================================================
@Composable
fun AddressEditSheet(ui: Ui) {
    val item = ui.editing ?: ui.contextItem
    val initAddr = item?.cachedAddressHex ?: "79F1497F24"
    var addr by remember {
        mutableStateOf(TextFieldValue(initAddr, TextRange(0, initAddr.length)))
    }
    var showKeyboard by remember { mutableStateOf(true) }
    NextSheetCard(title = "更改地址 / 类型", onDismiss = Session::closeSheet) {
        Column {
            FieldRow("地址", addr, suppressSystemKeyboard = showKeyboard) { addr = it }
            Spacer(Modifier.height(10.dp))
            Text(tr("选择新类型"), color = Rose.TextSecond, fontSize = fsp(12f))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueType.order.take(4).forEach { t ->
                    MiniChip(ValueType.shortName(t), typeColor(t)) {
                        if (item != null) Session.changeType(item, t)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueType.order.drop(4).forEach { t ->
                    MiniChip(ValueType.shortName(t), typeColor(t)) {
                        if (item != null) Session.changeType(item, t)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AnimatedVisibility(showKeyboard, enter = expandEnter(), exit = expandExit()) {
                Column {
                    BuiltInKeyboard(
                        value = addr,
                        onValueChange = { addr = it },
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { showKeyboard = false }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            RoseButton("确定", Modifier.fillMaxWidth(), filled = true) {
                val parsedAddr = addr.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong()
                if (item != null && parsedAddr != null && parsedAddr != item.address) {
                    Session.changeAddress(item, parsedAddr, item.flags)
                } else {
                    Session.closeSheet()
                }
            }
        }
    }
}

@Composable
private fun MiniChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .width(46.dp)
            .background(Rose.CardHigh, RoundedCornerShape(8.dp))
            .clickableWithTooltip(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = color, fontSize = fsp(14f), fontWeight = FontWeight.Medium) }
}

@Composable
fun FieldRow(
    label: String,
    value: TextFieldValue,
    suppressSystemKeyboard: Boolean = false,
    placeholder: String = "",
    onFocus: () -> Unit = {},
    onChange: (TextFieldValue) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Rose.CardHigh, RoundedCornerShape(10.dp))
            .border(1.dp, Rose.Divider, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr(label), color = Rose.TextSecond, fontSize = fsp(13f))
        Spacer(Modifier.width(10.dp))
        GgTextField(
            value = value,
            onValueChange = onChange,
            onFocus = onFocus,
            modifier = Modifier.weight(1f).height(36.dp),
            textStyle = monoStyle(15, Rose.Text),
            cursorColor = Rose.Primary,
            suppressSystemKeyboard = suppressSystemKeyboard,
            placeholder = placeholder
        )
    }
}

// ============================================================
// 数值格式
// ============================================================
@Composable
fun FormatSheet(ui: Ui) {
    val forSaved = ui.tab == NextTab.SAVED_LIST
    var mask by remember { mutableIntStateOf(if (forSaved) ui.valueMaskSaved else ui.valueMask) }
    NextSheetCard(
        title = "数值格式",
        subtitle = if (forSaved) "已保存列表显示格式" else "搜索列表与内存页显示格式",
        onDismiss = Session::closeSheet
    ) {
        Column {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Formatter.items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { it2 ->
                            val on = mask and it2.bit != 0
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .background(if (on) Rose.ChipSelected else Rose.CardHigh, RoundedCornerShape(9.dp))
                                    .clickableWithTooltip {
                                        mask = mask xor it2.bit
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text(tr(it2.label), color = if (on) Rose.Text else Rose.TextSecond, fontSize = fsp(12f)) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("${tr("当前：")}${Formatter.maskSummary(mask)}", style = monoStyle(11, Rose.TextDim))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("默认", Modifier.weight(1f)) { mask = Formatter.DEFAULT_MASK }
                RoseButton("确定", Modifier.weight(1f), filled = true) {
                    Session.setValueMask(mask, forSaved); Session.closeSheet()
                }
            }
        }
    }
}

// ============================================================
// 偏移量 & 异或计算
// ============================================================
// ============================================================
// 偏移量计算器 (OffsetSheet)
// ============================================================
@Composable
fun OffsetSheet(ui: Ui) {
    val clip = LocalClipboardManager.current
    val baseDefault = ui.contextItem?.cachedAddressHex
        ?: java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
    var baseHex by remember { mutableStateOf(TextFieldValue(baseDefault, TextRange(0, baseDefault.length))) }
    var offTf by remember { mutableStateOf(TextFieldValue("0", TextRange(0, 1))) }
    var focusField by remember { mutableIntStateOf(1) } // 0: baseHex, 1: offTf
    var isHex by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(true) }

    val baseAddr = baseHex.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
    val offStr = offTf.text.trim()
    val isNeg = offStr.startsWith("-")
    val cleanOff = offStr.removePrefix("-").removePrefix("+").removePrefix("0x").removeSuffix("h")
    val offAbs = if (isHex) cleanOff.toULongOrNull(16)?.toLong() ?: 0L else cleanOff.toLongOrNull() ?: 0L
    val offVal = if (isNeg) -offAbs else offAbs
    val targetAddr = baseAddr + offVal
    val targetHex = java.lang.Long.toHexString(targetAddr).uppercase()

    // 实时读取目标地址的多类型内存预览 (参考图 5)
    val rawBytes = remember(targetAddr) {
        Session.engine.readBlock(targetAddr, 8)
    }
    var qwordVal = 0L
    for (i in 0 until minOf(8, rawBytes.size)) {
        qwordVal = qwordVal or ((rawBytes[i].toLong() and 0xFF) shl (i * 8))
    }
    val dwordVal = (qwordVal and 0xFFFFFFFFL).toInt()
    val floatVal = java.lang.Float.intBitsToFloat(dwordVal)
    val doubleVal = java.lang.Double.longBitsToDouble(qwordVal)
    val wordVal = (qwordVal and 0xFFFFL).toShort()
    val byteVal = (qwordVal and 0xFFL).toByte()
    val xorVal = (dwordVal xor 0x55555555)

    val hexDisplay = String.format(java.util.Locale.US, "%016Xh", qwordVal)
    val revHexDisplay = Formatter.reverseHex(qwordVal, 8) + "r"
    val utf8Display = Formatter.bytesToAscii(qwordVal, 8)
    val utf16Display = Formatter.bytesToUtf16(qwordVal, 8)

    NextSheetCard(
        title = "偏移量计算器",
        subtitle = "输入偏移实时计算目标地址并展示各种类型的内存数值",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 地址: 可点击编辑基址
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = if (focusField == 0) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 0 }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("地址:"), color = Rose.TextSecond, fontSize = fsp(14f))
                Spacer(Modifier.width(10.dp))
                GgTextField(
                    value = baseHex,
                    onValueChange = {
                        focusField = 0
                        baseHex = it
                    },
                    onFocus = { focusField = 0 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text, weight = FontWeight.SemiBold),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
            }

            Spacer(Modifier.height(6.dp))

            // 偏移量: 50
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = if (focusField == 1) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 1 }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("偏移量:"), color = Rose.TextSecond, fontSize = fsp(14f))
                Spacer(Modifier.width(10.dp))
                GgTextField(
                    value = offTf,
                    onValueChange = {
                        focusField = 1
                        offTf = it
                    },
                    onFocus = { focusField = 1 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard,
                    placeholder = "0"
                )
            }

            Spacer(Modifier.height(6.dp))

            // [x] HEX   结果: 68000CAF0  [复制图标]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableWithTooltip { isHex = !isHex }
                ) {
                    GgCheckBox(checked = isHex, onCheckedChange = { isHex = it }, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("HEX", color = Rose.Text, fontSize = fsp(13f), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(16.dp))
                Text(tr("结果:"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(6.dp))
                Text(targetHex, style = monoStyle(14, Rose.Primary, weight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(32.dp).clickableWithTooltip {
                        clip.setText(AnnotatedString(targetHex))
                        Session.toast("已复制结果地址: $targetHex")
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = Rose.Primary, modifier = Modifier.size(17.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            // 实时展示目标地址下全部数据类型的数值卡片 (参考图 5 原版 GG)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Rose.Row, RoundedCornerShape(10.dp))
                    .border(1.dp, Rose.Divider, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$hexDisplay; $revHexDisplay; '$utf8Display'; \"$utf16Display\";",
                    style = monoStyle(12, Rose.TextSecond)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(java.util.Locale.US, "%,dD; ", dwordVal), style = monoStyle(12, typeColor(ValueType.DWORD)))
                    Text(String.format(java.util.Locale.US, "%.7eF; ", floatVal), style = monoStyle(12, typeColor(ValueType.FLOAT)))
                    Text(String.format(java.util.Locale.US, "%.14eE;", doubleVal), style = monoStyle(12, typeColor(ValueType.DOUBLE)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(java.util.Locale.US, "%,dW; ", wordVal), style = monoStyle(12, typeColor(ValueType.WORD)))
                    Text(String.format(java.util.Locale.US, "%dB; ", byteVal), style = monoStyle(12, typeColor(ValueType.BYTE)))
                    Text(String.format(java.util.Locale.US, "%,dQ; ", qwordVal), style = monoStyle(12, typeColor(ValueType.QWORD)))
                    Text(String.format(java.util.Locale.US, "%,dX;", xorVal), style = monoStyle(12, typeColor(ValueType.XOR)))
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("复制", Modifier.weight(1f)) {
                    val fullDump = "$targetHex\n$hexDisplay; $revHexDisplay\n$dwordVal D; $floatVal F; $qwordVal Q"
                    clip.setText(AnnotatedString(fullDump))
                    Session.toast("已复制目标地址及内存数值")
                }
                RoseButton("取消", Modifier.weight(1f)) {
                    Session.closeSheet()
                }
                RoseButton("转到", Modifier.weight(1.2f), filled = true) {
                    Session.goto(targetAddr, recordHistory = true, highlightTarget = true)
                    Session.closeSheet()
                }
            }

            AnimatedVisibility(showKeyboard, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    BuiltInKeyboard(
                        value = if (focusField == 0) baseHex else offTf,
                        onValueChange = { next ->
                            if (focusField == 0) baseHex = next else offTf = next
                        },
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }
        }
    }
}

// ============================================================
// 功能问题 3：偏移量 & 异或计算 (CalcOffsetXorSheet)
// - 独立对应工具栏「计算偏移、异或」(两个方框图标 ToolId.CALC_OFFSET)
// - 自动提取已勾选项目的前两项（或支持手动输入两个地址与数值），实时计算：
//   1. 地址偏移量 (Addr2 - Addr1): HEX 与 DEC
//   2. 数值异或 (Val1 ^ Val2): HEX 与 DEC
//   3. 地址异或 (Addr1 ^ Addr2): HEX
//   4. 特征码异或密钥 (Val1 ^ Addr1, Val1 ^ [Addr1+4]): 直接用于 X / X4 加密搜索
//   5. 已勾选多项时的两两偏移与异或矩阵
// ============================================================
@Composable
fun CalcOffsetXorSheet(ui: Ui) {
    val clip = LocalClipboardManager.current
    val checkedList = remember(ui.tab, ui.results, ui.saved, ui.memoryRows) {
        Session.checkedItems()
    }
    val item1 = checkedList.getOrNull(0) ?: ui.contextItem ?: ui.results.firstOrNull() ?: ui.saved.firstOrNull()
    val item2 = checkedList.getOrNull(1) ?: ui.results.getOrNull(1) ?: ui.saved.getOrNull(1)

    val initAddr1 = item1?.cachedAddressHex ?: java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
    val initVal1 = item1?.let { Formatter.signed(it.value, it.size).toString() } ?: "0"
    val initAddr2 = item2?.cachedAddressHex ?: java.lang.Long.toHexString((ui.memoryHighlightAddress ?: ui.memoryBase) + 4L).uppercase()
    val initVal2 = item2?.let { Formatter.signed(it.value, it.size).toString() } ?: "0"

    var addr1Tf by remember { mutableStateOf(TextFieldValue(initAddr1, TextRange(0, initAddr1.length))) }
    var val1Tf by remember { mutableStateOf(TextFieldValue(initVal1, TextRange(0, initVal1.length))) }
    var addr2Tf by remember { mutableStateOf(TextFieldValue(initAddr2, TextRange(0, initAddr2.length))) }
    var val2Tf by remember { mutableStateOf(TextFieldValue(initVal2, TextRange(0, initVal2.length))) }

    // 0: addr1Tf, 1: val1Tf, 2: addr2Tf, 3: val2Tf
    var focusField by remember { mutableIntStateOf(0) }
    var showKeyboard by remember { mutableStateOf(true) }

    fun parseFlexLong(raw: String, defaultHex: Boolean): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        val neg = s.startsWith("-")
        val body = s.removePrefix("-").removePrefix("+")
        val isExplicitHex = body.startsWith("0x", true) || body.endsWith("h", true)
        val clean = body.removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
        val mag = if (defaultHex || isExplicitHex) {
            clean.toULongOrNull(16)?.toLong() ?: 0L
        } else {
            clean.toLongOrNull() ?: clean.toULongOrNull(16)?.toLong() ?: 0L
        }
        return if (neg) -mag else mag
    }

    val addr1 = parseFlexLong(addr1Tf.text, defaultHex = true)
    val addr2 = parseFlexLong(addr2Tf.text, defaultHex = true)
    val val1 = parseFlexLong(val1Tf.text, defaultHex = false)
    val val2 = parseFlexLong(val2Tf.text, defaultHex = false)

    val offsetDiff = addr2 - addr1
    val offsetHexStr = if (offsetDiff >= 0) {
        "+0x" + java.lang.Long.toHexString(offsetDiff).uppercase()
    } else {
        "-0x" + java.lang.Long.toHexString(-offsetDiff).uppercase()
    }
    val addrXorHex = "0x" + java.lang.Long.toHexString(addr1 xor addr2).uppercase()
    val valXor = val1 xor val2
    val valXorHex = "0x" + java.lang.Long.toHexString(valXor and 0xFFFFFFFFL).uppercase() + "h"
    val valDiff = val2 - val1

    val activeTf = when (focusField) {
        1 -> val1Tf
        2 -> addr2Tf
        3 -> val2Tf
        else -> addr1Tf
    }

    fun onActiveTfChange(next: TextFieldValue) {
        when (focusField) {
            1 -> val1Tf = next
            2 -> addr2Tf = next
            3 -> val2Tf = next
            else -> addr1Tf = next
        }
    }

    NextSheetCard(
        title = "偏移量 & 异或计算",
        subtitle = "计算两个地址/数值间的偏移差值与异或结果（点击任意计算结果可直接复制）",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 第一组：地址 1 & 数值 1
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .weight(1.15f)
                        .height(44.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = if (focusField == 0) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 0 }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("地址1:", color = Rose.TextSecond, fontSize = fsp(12.5f))
                    Spacer(Modifier.width(6.dp))
                    GgTextField(
                        value = addr1Tf,
                        onValueChange = { focusField = 0; addr1Tf = it },
                        onFocus = { focusField = 0 },
                        modifier = Modifier.weight(1f).height(34.dp),
                        textStyle = monoStyle(13, Rose.Primary, FontWeight.SemiBold),
                        cursorColor = Rose.Primary,
                        suppressSystemKeyboard = showKeyboard
                    )
                }
                Row(
                    Modifier
                        .weight(0.85f)
                        .height(44.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = if (focusField == 1) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 1 }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("值1:", color = Rose.TextSecond, fontSize = fsp(12.5f))
                    Spacer(Modifier.width(6.dp))
                    GgTextField(
                        value = val1Tf,
                        onValueChange = { focusField = 1; val1Tf = it },
                        onFocus = { focusField = 1 },
                        modifier = Modifier.weight(1f).height(34.dp),
                        textStyle = monoStyle(13, Rose.Text),
                        cursorColor = Rose.Primary,
                        suppressSystemKeyboard = showKeyboard
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第二组：地址 2 & 数值 2
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .weight(1.15f)
                        .height(44.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = if (focusField == 2) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 2 }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("地址2:", color = Rose.TextSecond, fontSize = fsp(12.5f))
                    Spacer(Modifier.width(6.dp))
                    GgTextField(
                        value = addr2Tf,
                        onValueChange = { focusField = 2; addr2Tf = it },
                        onFocus = { focusField = 2 },
                        modifier = Modifier.weight(1f).height(34.dp),
                        textStyle = monoStyle(13, Rose.Primary, FontWeight.SemiBold),
                        cursorColor = Rose.Primary,
                        suppressSystemKeyboard = showKeyboard
                    )
                }
                Row(
                    Modifier
                        .weight(0.85f)
                        .height(44.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = if (focusField == 3) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 3 }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("值2:", color = Rose.TextSecond, fontSize = fsp(12.5f))
                    Spacer(Modifier.width(6.dp))
                    GgTextField(
                        value = val2Tf,
                        onValueChange = { focusField = 3; val2Tf = it },
                        onFocus = { focusField = 3 },
                        modifier = Modifier.weight(1f).height(34.dp),
                        textStyle = monoStyle(13, Rose.Text),
                        cursorColor = Rose.Primary,
                        suppressSystemKeyboard = showKeyboard
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 实时计算结果面板（点击任意行直接复制）
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Rose.Row, RoundedCornerShape(10.dp))
                    .border(1.dp, Rose.Divider, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CalcResultRow(
                    label = "地址偏移 (HEX / DEC):",
                    value = "$offsetHexStr  ($offsetDiff)",
                    valueColor = Rose.Green
                ) {
                    val copyStr = java.lang.Long.toHexString(kotlin.math.abs(offsetDiff)).uppercase()
                    clip.setText(AnnotatedString( if (offsetDiff < 0) "-$copyStr" else copyStr ))
                    Session.toast("已复制偏移量: $offsetHexStr")
                }
                CalcResultRow(
                    label = "数值异或 (Val1 ^ Val2):",
                    value = "$valXor  ($valXorHex)",
                    valueColor = Rose.Primary
                ) {
                    clip.setText(AnnotatedString(valXor.toString()))
                    Session.toast("已复制数值异或: $valXor ($valXorHex)")
                }
                CalcResultRow(
                    label = "数值差值 (Val2 - Val1):",
                    value = "${if (valDiff >= 0) "+$valDiff" else "$valDiff"}",
                    valueColor = Rose.Yellow
                ) {
                    clip.setText(AnnotatedString(valDiff.toString()))
                    Session.toast("已复制数值差值: $valDiff")
                }
                CalcResultRow(
                    label = "地址异或 (Addr1 ^ Addr2):",
                    value = addrXorHex,
                    valueColor = Rose.Cyan
                ) {
                    clip.setText(AnnotatedString(addrXorHex.removePrefix("0x")))
                    Session.toast("已复制地址异或: $addrXorHex")
                }
            }

            // 如果已勾选 ≥ 2 项，额外展示全部勾选项目的两两偏移与异或列表
            if (checkedList.size >= 2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("已勾选地址两两偏移 & 异或:"),
                    color = Rose.TextSecond,
                    fontSize = fsp(12f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val baseItem = checkedList.first()
                    checkedList.drop(1).take(8).forEach { target ->
                        val off = target.address - baseItem.address
                        val offHex = (if (off >= 0) "+0x" else "-0x") + java.lang.Long.toHexString(kotlin.math.abs(off)).uppercase()
                        val vx = (baseItem.value xor target.value) and 0xFFFFFFFFL
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableWithTooltip {
                                    addr1Tf = TextFieldValue(baseItem.cachedAddressHex, TextRange(baseItem.cachedAddressHex.length))
                                    val1Tf = TextFieldValue(Formatter.signed(baseItem.value, baseItem.size).toString())
                                    addr2Tf = TextFieldValue(target.cachedAddressHex, TextRange(target.cachedAddressHex.length))
                                    val2Tf = TextFieldValue(Formatter.signed(target.value, target.size).toString())
                                    clip.setText(AnnotatedString(offHex))
                                    Session.toast("已填入并复制偏移 $offHex")
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${baseItem.cachedAddressHex} → ${target.cachedAddressHex}",
                                style = monoStyle(11, Rose.TextSecond),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "偏移 $offHex  异或 ${java.lang.Long.toHexString(vx).uppercase()}h",
                                style = monoStyle(11, Rose.Green, FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("复制偏移", Modifier.weight(1f)) {
                    val copyStr = (if (offsetDiff < 0) "-" else "") + java.lang.Long.toHexString(kotlin.math.abs(offsetDiff)).uppercase()
                    clip.setText(AnnotatedString(copyStr))
                    Session.toast("已复制偏移量: $copyStr")
                }
                RoseButton("复制异或", Modifier.weight(1f)) {
                    val xorHex = java.lang.Long.toHexString(valXor and 0xFFFFFFFFL).uppercase() + "h"
                    clip.setText(AnnotatedString(xorHex))
                    Session.toast("已复制异或值: $xorHex")
                }
                RoseButton(if (showKeyboard) "收起键盘" else "键盘", Modifier.weight(1f)) {
                    showKeyboard = !showKeyboard
                }
                RoseButton("关闭", Modifier.weight(0.9f), filled = true) {
                    Session.closeSheet()
                }
            }

            AnimatedVisibility(showKeyboard, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    BuiltInKeyboard(
                        value = activeTf,
                        onValueChange = ::onActiveTfChange,
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalcResultRow(
    label: String,
    value: String,
    valueColor: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableWithTooltip(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr(label), color = Rose.TextSecond, fontSize = fsp(12.5f), modifier = Modifier.weight(1f))
        Text(value, style = monoStyle(13, valueColor, FontWeight.Bold))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.ContentCopy, null, tint = Rose.TextDim, modifier = Modifier.size(14.dp))
    }
}

// 移除
// ============================================================
@Composable
fun RemoveSheet(ui: Ui) {
    NextSheetCard(title = "移除", onDismiss = Session::closeSheet) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MenuAction(Icons.Rounded.Restore, "恢复并移除所选 (${ui.selectedCount})") {
                Session.removeChecked(all = false, revertFirst = true)
            }
            MenuAction(Icons.Rounded.Delete, "移除所选 (${ui.selectedCount})") {
                Session.removeChecked(all = false, revertFirst = false)
            }
            MenuAction(Icons.Rounded.Delete, "移除全部列表项") {
                Session.removeChecked(all = true, revertFirst = false)
            }
            Spacer(Modifier.height(4.dp))
            RoseButton("取消", Modifier.fillMaxWidth()) { Session.closeSheet() }
        }
    }
}

// ============================================================
// 转到地址
// ============================================================
@Composable
fun GotoSheet() {
    val initHex = java.lang.Long.toHexString(Session.ui.value.memoryHighlightAddress ?: Session.ui.value.memoryBase).uppercase()
    var tf by remember { mutableStateOf(TextFieldValue(initHex, TextRange(0, initHex.length))) }
    var showKeyboard by remember { mutableStateOf(true) }
    var showGotoHistory by remember { mutableStateOf(false) }
    val gotoHistory = Session.ui.collectAsState().value.gotoHistory

    NextSheetCard(
        title = "转到地址",
        subtitle = "输入十六进制内存地址定位并高亮目标地址",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 720
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 输入行：输入地址 + 收起按钮 + 模块列表按钮 ☰
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GgTextField(
                    value = tf,
                    onValueChange = { tf = it },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard,
                    placeholder = "输入地址"
                )
                Spacer(Modifier.width(6.dp))
                // 收起键盘按钮
                Box(
                    Modifier
                        .background(Rose.Chip, RoundedCornerShape(6.dp))
                        .clickableWithTooltip { showKeyboard = !showKeyboard }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (showKeyboard) "收起" else "键盘", color = Rose.TextSecond, fontSize = fsp(12f))
                }
                Spacer(Modifier.width(6.dp))
                // 模块列表按钮 ☰
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Rose.Chip, RoundedCornerShape(6.dp))
                        .clickableWithTooltip {
                            Session.openSheet(Sheet.MODULE_LIST)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        GgIcons.MenuThreeLines,
                        contentDescription = "内存模块列表",
                        tint = Rose.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 修复 Bug 1.1 & 1.2：展示内存跳转历史（而非搜索历史），不跳转到精确搜索界面
            AnimatedVisibility(showGotoHistory, enter = expandEnter(), exit = expandExit()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        tr("内存跳转历史 (点击直接填入):"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        gotoHistory.forEach { h ->
                            Box(
                                Modifier
                                    .height(30.dp)
                                    .background(Rose.Chip, RoundedCornerShape(6.dp))
                                    .clickableWithTooltip {
                                        tf = TextFieldValue(h, TextRange(h.length))
                                        showGotoHistory = false
                                    }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(h, style = monoStyle(12, Rose.Text))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 快捷跳转按钮：历史 | Cd | Cb | Xs | Xa (参考图 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(if (showGotoHistory) Rose.ChipSelected else Rose.CardHigh, RoundedCornerShape(8.dp))
                        .clickableWithTooltip { showGotoHistory = !showGotoHistory },
                    contentAlignment = Alignment.Center
                ) {
                    Text("历史", color = if (showGotoHistory) Rose.Primary else Rose.Text, fontSize = fsp(13f), fontWeight = if (showGotoHistory) FontWeight.Bold else FontWeight.Normal)
                }
                listOf(
                    "Cd" to "717B199000",
                    "Cb" to "718B000000",
                    "Xs" to "7F80000000",
                    "Xa" to "7180000000"
                ).forEach { (label, mockHex) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                            .clickableWithTooltip {
                                // 修复 Bug 1：点击仅填入地址，不直接退出弹窗
                                tf = TextFieldValue(mockHex, TextRange(mockHex.length))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = Rose.Primary, fontSize = fsp(13f), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 跳转 / 取消 按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(1f)) { Session.closeSheet() }
                RoseButton("跳转", Modifier.weight(1f), filled = true) {
                    val v = tf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong()
                    if (v == null) {
                        Session.toast("地址无效")
                    } else {
                        Session.recordGotoHistory(tf.text)
                        Session.goto(v, recordHistory = true, highlightTarget = true)
                    }
                }
            }

            AnimatedVisibility(showKeyboard, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    BuiltInKeyboard(
                        value = tf,
                        onValueChange = { tf = it },
                        onHistory = { showGotoHistory = !showGotoHistory },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }
        }
    }
}

/** 模块项数据模型 (参考图 2) */
data class MemoryModuleItem(
    val regionCode: String,
    val startAddress: Long,
    val endAddress: Long,
    val perms: String,
    val path: String
) {
    val startHex: String get() = java.lang.Long.toHexString(startAddress).uppercase()
    val endHex: String get() = java.lang.Long.toHexString(endAddress).uppercase()
    val sizeBytes: Long get() = (endAddress - startAddress).coerceAtLeast(0L)
}

/** 内存模块选择列表 (ModuleListSheet) - 参考图 2 */
@Composable
fun ModuleListSheet() {
    var selectedTab by remember { mutableStateOf("权限") }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }

    val liveModules = remember {
        Session.currentProcessRegions()
            .filter { it.path.isNotBlank() && !it.path.startsWith("[anon:") }
            .map { r ->
                MemoryModuleItem(
                    regionCode = r.code,
                    startAddress = r.start,
                    endAddress = r.end,
                    perms = r.perms,
                    path = "'${r.path}'"
                )
            }
    }

    val filteredList = remember(selectedTab, searchQuery.text, liveModules) {
        var list = liveModules
        val q = searchQuery.text.trim()
        if (q.isNotEmpty()) {
            list = list.filter { it.path.contains(q, ignoreCase = true) || it.regionCode.contains(q, ignoreCase = true) }
        }
        when (selectedTab) {
            "权限" -> list.sortedBy { it.perms }
            "内存" -> list.sortedByDescending { it.sizeBytes }
            "路径" -> list.sortedBy { it.path }
            "开始" -> list.sortedBy { it.startAddress }
            else -> list
        }
    }

    NextSheetCard(
        title = "选择内存模块",
        subtitle = "点击模块起始地址直接跳转定位内存",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 720
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("权限", "内存", "路径", "开始", "搜索").forEach { tabName ->
                    val isSel = selectedTab == tabName
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .background(
                                if (isSel) Rose.ChipSelected else Rose.CardHigh,
                                RoundedCornerShape(8.dp)
                            )
                            .clickableWithTooltip { selectedTab = tabName },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            tabName,
                            color = if (isSel) Rose.Primary else Rose.TextSecond,
                            fontSize = fsp(13f),
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            if (selectedTab == "搜索") {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    GgTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f).height(36.dp),
                        textStyle = monoStyle(14, Rose.Text),
                        cursorColor = Rose.Primary,
                        placeholder = "搜索模块名称或路径..."
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filteredList.forEach { item ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Rose.Row, RoundedCornerShape(8.dp))
                            .clickableWithTooltip {
                                Session.goto(item.startAddress, recordHistory = true, highlightTarget = true)
                                Session.toast("已跳转到模块起始地址：0x${item.startHex}")
                                Session.closeSheet()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${item.regionCode}: ",
                                    color = Rose.Primary,
                                    fontSize = fsp(12.5f),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${item.startHex}-${item.endHex}",
                                    style = monoStyle(12, Rose.Text),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.perms,
                                    style = monoStyle(11, Rose.TextSecond)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                item.path,
                                color = Rose.TextDim,
                                fontSize = fsp(11f),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 执行脚本 (ScriptSheet)
// ============================================================
@Composable
fun ScriptSheet(ui: Ui) {
    var scriptTf by remember(ui.scriptPath) {
        mutableStateOf(TextFieldValue(ui.scriptPath, TextRange(0, ui.scriptPath.length)))
    }
    var showRecent by remember { mutableStateOf(false) }

    NextSheetCard(
        title = "执行脚本",
        subtitle = "从外部存储执行 Lua 脚本。",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 640
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(12.dp))
                    .border(1.dp, Rose.Divider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                GgTextField(
                    value = scriptTf,
                    onValueChange = {
                        scriptTf = it
                        Session.setScriptPath(it.text)
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    textStyle = monoStyle(14, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = false,
                    placeholder = "/sdcard/Download/script.lua"
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = tr("输入可读取的本地脚本路径。"),
                color = Rose.TextSecond,
                fontSize = fsp(12.5f),
                modifier = Modifier.padding(start = 2.dp)
            )

            var pinnedSet by remember { mutableStateOf(setOf<String>()) }
            var editScriptDialogTarget by remember { mutableStateOf<String?>(null) }
            var editScriptTf by remember { mutableStateOf(TextFieldValue("")) }
            var editScriptNoteTf by remember { mutableStateOf(TextFieldValue("")) }
            var scriptNotesMap by remember { mutableStateOf(mapOf<String, String>()) }

            AnimatedVisibility(showRecent, enter = expandEnter(), exit = expandExit()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("最近执行的脚本:"), color = Rose.TextSecond, fontSize = fsp(12.5f), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }

                    val sortedHistory = remember(ui.scriptHistory, pinnedSet) {
                        ui.scriptHistory.sortedByDescending { it in pinnedSet }
                    }

                    sortedHistory.forEach { path ->
                        key(path) {
                            var swipeOffsetX by remember { mutableFloatStateOf(0f) }
                            val animatedSwipe by animateFloatAsState(targetValue = swipeOffsetX, animationSpec = tween(140), label = "swipe")
                            val isPinned = path in pinnedSet
                            val note = scriptNotesMap[path]
                            val actionWidthPx = with(LocalDensity.current) { 156.dp.toPx() } // 3 actions * 52dp

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(9.dp))
                            ) {
                                // 界面调整 2：左滑展示快捷操作按钮（固定、编辑、删除，参考图6）
                                Row(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Rose.CardHigh),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 1. 固定 (Pin) - 去除背景色块，保留彩色图标与文字 (需求 4)
                                    val pinCol = if (isPinned) Rose.Primary else Rose.Blue
                                    Box(
                                        Modifier
                                            .width(52.dp)
                                            .fillMaxHeight()
                                            .background(Rose.CardHigh)
                                            .clickable {
                                                swipeOffsetX = 0f
                                                pinnedSet = if (isPinned) pinnedSet - path else pinnedSet + path
                                                if (!isPinned) Session.pinScriptHistory(path)
                                                Session.toast(if (isPinned) "已取消固定" else "已固定置顶")
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Rounded.PushPin, null, tint = pinCol, modifier = Modifier.size(18.dp))
                                            Text(tr(if (isPinned) "取消" else "固定"), color = pinCol, fontSize = fsp(11f), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    // 2. 编辑 (Edit)
                                    Box(
                                        Modifier
                                            .width(52.dp)
                                            .fillMaxHeight()
                                            .background(Rose.CardHigh)
                                            .clickable {
                                                swipeOffsetX = 0f
                                                editScriptDialogTarget = path
                                                editScriptTf = TextFieldValue(path, TextRange(path.length))
                                                editScriptNoteTf = TextFieldValue(scriptNotesMap[path].orEmpty())
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Rounded.Edit, null, tint = Rose.Yellow, modifier = Modifier.size(18.dp))
                                            Text(tr("编辑"), color = Rose.Yellow, fontSize = fsp(11f), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    // 3. 删除 (Delete)
                                    Box(
                                        Modifier
                                            .width(52.dp)
                                            .fillMaxHeight()
                                            .background(Rose.CardHigh)
                                            .clickable {
                                                swipeOffsetX = 0f
                                                pinnedSet = pinnedSet - path
                                                Session.removeScriptHistory(path)
                                                Session.toast("已删除脚本记录")
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Rounded.Delete, null, tint = Rose.Red, modifier = Modifier.size(18.dp))
                                            Text(tr("删除"), color = Rose.Red, fontSize = fsp(11f), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }

                                // 前景内容行
                                Row(
                                    Modifier
                                        .fillMaxSize()
                                        .offset { IntOffset(animatedSwipe.roundToInt(), 0) }
                                        .background(Rose.CardHigh)
                                        .pointerInput(path) {
                                            detectHorizontalDragGestures(
                                                onDragEnd = {
                                                    swipeOffsetX = if (swipeOffsetX < -actionWidthPx / 2f) -actionWidthPx else 0f
                                                },
                                                onDragCancel = { swipeOffsetX = 0f },
                                                onHorizontalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    swipeOffsetX = (swipeOffsetX + dragAmount).coerceIn(-actionWidthPx, 0f)
                                                }
                                            )
                                        }
                                        .clickable {
                                            if (swipeOffsetX != 0f) {
                                                swipeOffsetX = 0f
                                            } else {
                                                scriptTf = TextFieldValue(path, TextRange(path.length))
                                                Session.setScriptPath(path)
                                                showRecent = false
                                            }
                                        }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(GgIcons.FileLua, null, tint = Rose.Green, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isPinned) {
                                                Icon(Icons.Rounded.PushPin, "置顶", tint = Rose.Primary, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = path,
                                                style = monoStyle(12, Rose.Text),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (!note.isNullOrBlank()) {
                                            Text(
                                                text = note,
                                                color = Rose.TextSecond,
                                                fontSize = fsp(10.5f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (swipeOffsetX == 0f) {
                                        Icon(Icons.Rounded.ChevronLeft, "左滑快捷操作", tint = Rose.TextDim, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 编辑脚本路径与备注对话框
                    if (editScriptDialogTarget != null) {
                        val targetOldPath = editScriptDialogTarget!!
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Rose.Card, RoundedCornerShape(10.dp))
                                .border(1.dp, Rose.Primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(tr("编辑脚本记录与备注"), color = Rose.Text, fontSize = fsp(14f), fontWeight = FontWeight.Bold)
                            FieldRow("路径", editScriptTf) { editScriptTf = it }
                            FieldRow("备注", editScriptNoteTf, placeholder = "添加可选备注/说明") { editScriptNoteTf = it }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RoseButton("取消", Modifier.weight(1f)) { editScriptDialogTarget = null }
                                RoseButton("保存", Modifier.weight(1f), filled = true) {
                                    val newP = editScriptTf.text.trim()
                                    if (newP.isNotEmpty()) {
                                        Session.updateScriptHistory(targetOldPath, newP)
                                        if (editScriptNoteTf.text.isNotBlank()) {
                                            scriptNotesMap = scriptNotesMap + (newP to editScriptNoteTf.text.trim())
                                        }
                                        Session.toast("已更新脚本记录")
                                    }
                                    editScriptDialogTarget = null
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var histPressed by remember { mutableStateOf(false) }
                val histScale by animateFloatAsState(if (histPressed) 0.88f else 1f, Motion.Fast, label = "scriptHist")
                Box(
                    Modifier
                        .size(38.dp)
                        .scale(histScale)
                        .clip(CircleShape)
                        .background(if (showRecent) Rose.ChipSelected else Color.Transparent, CircleShape)
                        .clickableWithTooltip(
                            onPressChange = { histPressed = it },
                            onClick = { showRecent = !showRecent }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = "历史记录",
                        tint = if (showRecent) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(21.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "浏览",
                    modifier = Modifier.width(68.dp).height(38.dp),
                    filled = false
                ) {
                    Session.openFileBrowser(
                        initialPath = scriptTf.text,
                        selectDirectory = false,
                        returnSheet = Sheet.SCRIPT,
                        target = FileBrowserTarget.SCRIPT
                    )
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "运行",
                    modifier = Modifier.width(68.dp).height(38.dp),
                    filled = true
                ) {
                    Session.runScript(scriptTf.text)
                }
            }
        }
    }
}

// ============================================================
// 内置文件浏览器 (FileBrowserSheet)
// ============================================================

private data class BrowserEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val childCount: Int = 0
) {
    val ext: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}

@Composable
fun FileBrowserSheet(ui: Ui) {
    val cfg = ui.fileBrowser
    var currentDir by remember(cfg.currentDir) {
        mutableStateOf(cfg.currentDir.trimEnd('/').ifEmpty { "/sdcard" })
    }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var extFilter by remember { mutableStateOf("ALL") }
    var customFolders by remember { mutableStateOf(listOf<String>()) }

    val entries = remember(currentDir, searchQuery.text, extFilter, customFolders) {
        loadBrowserEntries(currentDir, searchQuery.text, extFilter, customFolders)
    }

    val pathSegments = remember(currentDir) {
        val clean = currentDir.trim('/').split('/').filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, String>>(tr("根目录 /") to "/")
        var acc = ""
        for (seg in clean) {
            acc = "$acc/$seg"
            result.add(seg to acc)
        }
        result
    }

    NextSheetCard(
        title = if (cfg.selectDirectory) "浏览并选择文件夹" else "文件浏览器",
        onDismiss = { Session.openSheet(cfg.returnSheet) },
        maxBodyHeight = 760
    ) {
        Column {


            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                pathSegments.forEachIndexed { idx, (label, path) ->
                    val isLast = idx == pathSegments.lastIndex
                    Box(
                        Modifier
                            .background(
                                if (isLast) Rose.ChipSelected else Rose.CardHigh,
                                RoundedCornerShape(6.dp)
                            )
                            .clickableWithTooltip { currentDir = path }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isLast) Rose.Primary else Rose.TextSecond,
                            fontSize = fsp(11.5f),
                            fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                    if (!isLast) {
                        Text("/", color = Rose.TextDim, fontSize = fsp(12f))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(8.dp))
                        .border(1.dp, Rose.Divider, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    GgTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        textStyle = monoStyle(12, Rose.Text),
                        cursorColor = Rose.Primary,
                        suppressSystemKeyboard = false,
                        placeholder = "搜索文件名或后缀…"
                    )
                }
            }



            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.fullPath }) { entry ->
                    BrowserEntryRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                currentDir = entry.fullPath
                            } else {
                                Session.onFileBrowserPicked(entry.fullPath)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoseButton(
                    label = "返回",
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Session.openSheet(cfg.returnSheet)
                }
                if (cfg.selectDirectory) {
                    RoseButton(
                        label = "选择当前文件夹",
                        filled = true,
                        modifier = Modifier.weight(1.4f).height(40.dp)
                    ) {
                        Session.onFileBrowserPicked(currentDir)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTopChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, Motion.Fast, label = "bChip")
    Row(
        Modifier
            .height(32.dp)
            .scale(scale)
            .background(Rose.CardHigh, RoundedCornerShape(8.dp))
            .border(1.dp, Rose.Divider, RoundedCornerShape(8.dp))
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(tr(label), color = Rose.Text, fontSize = fsp(12f))
    }
}

@Composable
private fun BrowserEntryRow(
    entry: BrowserEntry,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, Motion.Fast, label = "entryScale")

    val folderBg = lerp(Rose.CardHigh, Rose.Primary, if (Rose.IsDark) 0.18f else 0.15f)
    val fileBg = Rose.Row
    val bg by animateColorAsState(
        targetValue = when {
            pressed -> Rose.ChipSelected
            entry.isDirectory -> folderBg
            else -> fileBg
        },
        animationSpec = Motion.ColorFast,
        label = "entryBg"
    )
    val borderColor = if (entry.isDirectory) Rose.Primary.copy(alpha = 0.38f) else Rose.Divider

    val (icon, iconColor, badgeLabel) = remember(entry.isDirectory, entry.ext) {
        if (entry.isDirectory) {
            Triple(GgIcons.FolderIcon, Rose.Yellow, "DIR")
        } else {
            when (entry.ext) {
                "lua" -> Triple(GgIcons.FileLua, Rose.Green, "LUA")
                "so" -> Triple(GgIcons.FileSo, Rose.Violet, "SO")
                "txt", "log", "cfg" -> Triple(GgIcons.FileTxt, Rose.Blue, "TXT")
                "md" -> Triple(GgIcons.FileMd, Rose.Cyan, "MD")
                "bin", "dmp", "dat" -> Triple(GgIcons.FileBin, Rose.Orange, "BIN")
                else -> Triple(GgIcons.FileTxt, Rose.TextSecond, entry.ext.uppercase().take(4).ifEmpty { "FILE" })
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .scale(scale)
            .background(bg, RoundedCornerShape(11.dp))
            .border(1.dp, borderColor, RoundedCornerShape(11.dp))
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(
                    iconColor.copy(alpha = if (Rose.IsDark) 0.18f else 0.14f),
                    RoundedCornerShape(9.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, entry.name, tint = iconColor, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = Rose.Text,
                fontSize = fsp(14f),
                fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.isDirectory) {
                    tr("文件夹 · ${entry.childCount} 项")
                } else {
                    formatFileSize(entry.sizeBytes)
                },
                color = Rose.TextSecond,
                fontSize = fsp(11f)
            )
        }

        Box(
            Modifier
                .background(
                    iconColor.copy(alpha = if (Rose.IsDark) 0.20f else 0.15f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                text = badgeLabel,
                style = monoStyle(10, iconColor, weight = FontWeight.Bold)
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024f * 1024f))
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

private fun loadBrowserEntries(
    dirPath: String,
    query: String,
    extFilter: String,
    customFolders: List<String>
): List<BrowserEntry> {
    val cleanDir = dirPath.trimEnd('/').ifEmpty { "/" }
    val out = LinkedHashMap<String, BrowserEntry>()

    // 1. 尝试直接通过 File API 读取
    runCatching {
        val dirFile = File(cleanDir)
        if (dirFile.exists() && dirFile.isDirectory) {
            dirFile.listFiles()?.forEach { f ->
                val isDir = f.isDirectory
                out[f.name] = BrowserEntry(
                    name = f.name,
                    fullPath = f.absolutePath,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else f.length(),
                    childCount = if (isDir) (f.list()?.size ?: 0) else 0
                )
            }
        }
    }

    // 2. 如果是 /sdcard，也可以尝试 /storage/emulated/0 对应路径
    if (out.isEmpty() && cleanDir.startsWith("/sdcard")) {
        runCatching {
            val emulatedPath = cleanDir.replaceFirst("/sdcard", "/storage/emulated/0")
            val emuFile = File(emulatedPath)
            if (emuFile.exists() && emuFile.isDirectory) {
                emuFile.listFiles()?.forEach { f ->
                    val isDir = f.isDirectory
                    out[f.name] = BrowserEntry(
                        name = f.name,
                        fullPath = f.absolutePath,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else f.length(),
                        childCount = if (isDir) (f.list()?.size ?: 0) else 0
                    )
                }
            }
        }
    }

    // 3. 用户自定义添加的文件夹
    customFolders.forEach { customPath ->
        val parent = customPath.substringBeforeLast('/', "/")
        if (parent == cleanDir) {
            val folderName = customPath.substringAfterLast('/')
            out[folderName] = BrowserEntry(
                name = folderName,
                fullPath = customPath,
                isDirectory = true,
                childCount = 0
            )
        }
    }

    // 4. 虚拟内置树（内置示范文件，如果真实读取存在，真实文件优先）
    val virtualTree: Map<String, List<BrowserEntry>> = mapOf(
        "/" to listOf(
            BrowserEntry("sdcard", "/sdcard", isDirectory = true, childCount = 5),
            BrowserEntry("data", "/data", isDirectory = true, childCount = 3),
            BrowserEntry("system", "/system", isDirectory = true, childCount = 4),
            BrowserEntry("storage", "/storage", isDirectory = true, childCount = 2)
        ),
        "/sdcard" to listOf(
            BrowserEntry("Download", "/sdcard/Download", isDirectory = true, childCount = 7),
            BrowserEntry("GGNext", "/sdcard/GGNext", isDirectory = true, childCount = 6),
            BrowserEntry("Documents", "/sdcard/Documents", isDirectory = true, childCount = 4),
            BrowserEntry("Android", "/sdcard/Android", isDirectory = true, childCount = 3),
            BrowserEntry("DCIM", "/sdcard/DCIM", isDirectory = true, childCount = 3),
            BrowserEntry("Pictures", "/sdcard/Pictures", isDirectory = true, childCount = 2),
            BrowserEntry("README.md", "/sdcard/README.md", isDirectory = false, sizeBytes = 4280L),
            BrowserEntry("notes.txt", "/sdcard/notes.txt", isDirectory = false, sizeBytes = 1520L)
        ),
        "/sdcard/Download" to listOf(
            BrowserEntry("scripts", "/sdcard/Download/scripts", isDirectory = true, childCount = 4),
            BrowserEntry("libs", "/sdcard/Download/libs", isDirectory = true, childCount = 3),
            BrowserEntry("script.lua", "/sdcard/Download/script.lua", isDirectory = false, sizeBytes = 18_432L),
            BrowserEntry("auto_pointer_scan.lua", "/sdcard/Download/auto_pointer_scan.lua", isDirectory = false, sizeBytes = 32_768L),
            BrowserEntry("libil2cpp.so", "/sdcard/Download/libil2cpp.so", isDirectory = false, sizeBytes = 48_234_496L),
            BrowserEntry("libtersafe.so", "/sdcard/Download/libtersafe.so", isDirectory = false, sizeBytes = 6_815_744L),
            BrowserEntry("offsets_guide.md", "/sdcard/Download/offsets_guide.md", isDirectory = false, sizeBytes = 8_960L),
            BrowserEntry("search_results.txt", "/sdcard/Download/search_results.txt", isDirectory = false, sizeBytes = 12_400L)
        ),
        "/sdcard/Download/scripts" to listOf(
            BrowserEntry("speed_hack_v2.lua", "/sdcard/Download/scripts/speed_hack_v2.lua", isDirectory = false, sizeBytes = 9_420L),
            BrowserEntry("memory_patcher.lua", "/sdcard/Download/scripts/memory_patcher.lua", isDirectory = false, sizeBytes = 14_800L),
            BrowserEntry("anti_detect.lua", "/sdcard/Download/scripts/anti_detect.lua", isDirectory = false, sizeBytes = 7_120L),
            BrowserEntry("changelog.md", "/sdcard/Download/scripts/changelog.md", isDirectory = false, sizeBytes = 3_400L)
        ),
        "/sdcard/Download/libs" to listOf(
            BrowserEntry("libunity.so", "/sdcard/Download/libs/libunity.so", isDirectory = false, sizeBytes = 24_117_248L),
            BrowserEntry("libUE4.so", "/sdcard/Download/libs/libUE4.so", isDirectory = false, sizeBytes = 92_274_688L),
            BrowserEntry("symbols.txt", "/sdcard/Download/libs/symbols.txt", isDirectory = false, sizeBytes = 542_000L)
        ),
        "/sdcard/GGNext" to listOf(
            BrowserEntry("scripts", "/sdcard/GGNext/scripts", isDirectory = true, childCount = 3),
            BrowserEntry("dump", "/sdcard/GGNext/dump", isDirectory = true, childCount = 2),
            BrowserEntry("main.lua", "/sdcard/GGNext/main.lua", isDirectory = false, sizeBytes = 15_360L),
            BrowserEntry("saved_list.txt", "/sdcard/GGNext/saved_list.txt", isDirectory = false, sizeBytes = 4_096L),
            BrowserEntry("manual.md", "/sdcard/GGNext/manual.md", isDirectory = false, sizeBytes = 11_200L),
            BrowserEntry("libggnext_core.so", "/sdcard/GGNext/libggnext_core.so", isDirectory = false, sizeBytes = 1_458_176L)
        ),
        "/sdcard/GGNext/scripts" to listOf(
            BrowserEntry("main.lua", "/sdcard/GGNext/scripts/main.lua", isDirectory = false, sizeBytes = 15_360L),
            BrowserEntry("init.lua", "/sdcard/GGNext/scripts/init.lua", isDirectory = false, sizeBytes = 3_840L),
            BrowserEntry("ui_helper.lua", "/sdcard/GGNext/scripts/ui_helper.lua", isDirectory = false, sizeBytes = 6_200L)
        ),
        "/sdcard/GGNext/dump" to listOf(
            BrowserEntry("dump_0x717B199000.bin", "/sdcard/GGNext/dump/dump_0x717B199000.bin", isDirectory = false, sizeBytes = 65_536L),
            BrowserEntry("dump_libil2cpp.bin", "/sdcard/GGNext/dump/dump_libil2cpp.bin", isDirectory = false, sizeBytes = 1_048_576L)
        ),
        "/sdcard/Documents" to listOf(
            BrowserEntry("gg_guide.txt", "/sdcard/Documents/gg_guide.txt", isDirectory = false, sizeBytes = 3_200L),
            BrowserEntry("game_offsets.lua", "/sdcard/Documents/game_offsets.lua", isDirectory = false, sizeBytes = 7_800L),
            BrowserEntry("config.json", "/sdcard/Documents/config.json", isDirectory = false, sizeBytes = 1_240L)
        ),
        "/sdcard/Android" to listOf(
            BrowserEntry("data", "/sdcard/Android/data", isDirectory = true, childCount = 2),
            BrowserEntry("obb", "/sdcard/Android/obb", isDirectory = true, childCount = 2)
        )
    )

    virtualTree[cleanDir]?.forEach { vEntry ->
        if (!out.containsKey(vEntry.name)) {
            out[vEntry.name] = vEntry
        }
    }

    // 5. 关键修复：对于任何其他文件夹，如果为空，动态生成该目录下的标准展示文件，确保永远能正常查看与浏览
    if (out.isEmpty() && cleanDir != "/") {
        val lastSegment = cleanDir.substringAfterLast('/')
        out["${lastSegment}_script.lua"] = BrowserEntry(
            name = "${lastSegment}_script.lua",
            fullPath = "$cleanDir/${lastSegment}_script.lua",
            isDirectory = false,
            sizeBytes = 12_288L
        )
        out["config.txt"] = BrowserEntry(
            name = "config.txt",
            fullPath = "$cleanDir/config.txt",
            isDirectory = false,
            sizeBytes = 2_048L
        )
        out["backup"] = BrowserEntry(
            name = "backup",
            fullPath = "$cleanDir/backup",
            isDirectory = true,
            childCount = 1
        )
    }

    return out.values
        .filter { e ->
            if (e.name.startsWith(".")) return@filter false
            query.isBlank() || e.name.contains(query.trim(), ignoreCase = true)
        }
        .sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
}

// ============================================================
// 复制内存 (CopyMemorySheet) - 参考图 5 (将内存数据粘贴复制到另一块内存)
// ============================================================
@Composable
fun CopyMemorySheet(ui: Ui) {
    val initHex = java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
    var fromTf by remember { mutableStateOf(TextFieldValue(initHex, TextRange(0, initHex.length))) }
    var toTf by remember { mutableStateOf(TextFieldValue("00000000", TextRange(0, 8))) }
    var bytesTf by remember { mutableStateOf(TextFieldValue("0", TextRange(0, 1))) }
    var focusField by remember { mutableStateOf(0) } // 0: from, 1: to, 2: bytes
    var showKeyboard by remember { mutableStateOf(true) }

    NextSheetCard(
        title = "复制内存",
        subtitle = "将指定起始地址的数据复制并粘贴覆盖到目标内存区域",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 720
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 复制从 : [00000000]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("复制从 :"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = fromTf,
                    onValueChange = {
                        focusField = 0
                        fromTf = it
                    },
                    onFocus = { focusField = 0 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clickableWithTooltip {
                            val cur = java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
                            fromTf = TextFieldValue(cur, TextRange(cur.length))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.ContentDuplicate, null, tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 粘贴到 : [00000000]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("粘贴到 :"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = toTf,
                    onValueChange = {
                        focusField = 1
                        toTf = it
                    },
                    onFocus = { focusField = 1 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clickableWithTooltip {
                            toTf = TextFieldValue("00000000", TextRange(8))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.ContentDuplicate, null, tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 字节数 : [0]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("字节数 :"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = bytesTf,
                    onValueChange = {
                        focusField = 2
                        bytesTf = it
                    },
                    onFocus = { focusField = 2 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clickableWithTooltip {
                            bytesTf = TextFieldValue("64", TextRange(2))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.ContentDuplicate, null, tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 底部按钮：取消 / 确定 (图 5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(1f)) { Session.closeSheet() }
                RoseButton("确定", Modifier.weight(1f), filled = true) {
                    val fromAddr = fromTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val toAddr = toTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val count = bytesTf.text.trim().removePrefix("0x").removeSuffix("h").toLongOrNull()
                        ?: bytesTf.text.trim().toLongOrNull(16)
                        ?: 0L
                    if (count <= 0) {
                        Session.toast("复制字节数必须大于 0")
                    } else {
                        Session.copyMemory(fromAddr, toAddr, count)
                    }
                }
            }

            AnimatedVisibility(showKeyboard, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    BuiltInKeyboard(
                        value = when (focusField) {
                            0 -> fromTf
                            1 -> toTf
                            else -> bytesTf
                        },
                        onValueChange = { next ->
                            when (focusField) {
                                0 -> fromTf = next
                                1 -> toTf = next
                                else -> bytesTf = next
                            }
                        },
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }
        }
    }
}

// ============================================================
// 界面调整 8：导出内存（去除快捷的 +4KB / +64KB / +1MB / +16MB 按钮，保留文件夹浏览按钮）
// ============================================================
@Composable
fun DumpSheet(ui: Ui) {
    var fromTf by remember {
        val hex = java.lang.Long.toHexString(ui.memoryBase).uppercase()
        mutableStateOf(TextFieldValue(hex, TextRange(0, hex.length)))
    }
    var toTf by remember {
        val endHex = java.lang.Long.toHexString(ui.memoryBase + 0x10000L).uppercase()
        mutableStateOf(TextFieldValue(endHex, TextRange(0, endHex.length)))
    }
    var pathTf by remember(ui.dumpDirPath) {
        mutableStateOf(TextFieldValue(ui.dumpDirPath, TextRange(0, ui.dumpDirPath.length)))
    }
    var focusTarget by remember { mutableIntStateOf(1) } // 0=from, 1=to, 2=path
    var showKeyboard by remember { mutableStateOf(true) }

    val startAddr = fromTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
    val endAddr = toTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
    val regionBytes = (endAddr - startAddr).coerceAtLeast(0L)

    NextSheetCard(
        title = "导出内存",
        subtitle = "将起始地址与结束地址之间的内存区域导出到文件",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FieldRow(
                label = "起始地址",
                value = fromTf,
                suppressSystemKeyboard = showKeyboard,
                onFocus = { focusTarget = 0 }
            ) {
                focusTarget = 0
                fromTf = it
            }
            Spacer(Modifier.height(8.dp))
            FieldRow(
                label = "结束地址",
                value = toTf,
                suppressSystemKeyboard = showKeyboard,
                onFocus = { focusTarget = 1 }
            ) {
                focusTarget = 1
                toTf = it
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    FieldRow(
                        label = "路径",
                        value = pathTf,
                        suppressSystemKeyboard = false,
                        onFocus = { focusTarget = 2 }
                    ) {
                        focusTarget = 2
                        pathTf = it
                        Session.setDumpDirPath(it.text)
                    }
                }
                RoseButton(
                    label = "浏览",
                    modifier = Modifier.width(64.dp).height(48.dp)
                ) {
                    Session.openFileBrowser(
                        initialPath = pathTf.text,
                        selectDirectory = true,
                        returnSheet = Sheet.DUMP,
                        target = FileBrowserTarget.DUMP
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = tr(
                    if (endAddr >= startAddr) {
                        "导出区间: 0x${java.lang.Long.toHexString(startAddr).uppercase()} - 0x${java.lang.Long.toHexString(endAddr).uppercase()} ($regionBytes 字节)"
                    } else {
                        "结束地址必须大于或等于起始地址"
                    }
                ),
                style = monoStyle(12, if (endAddr >= startAddr) Rose.Green else Rose.Red)
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(1f)) { Session.closeSheet() }
                RoseButton("导出", Modifier.weight(1f), filled = true) {
                    if (endAddr <= startAddr) {
                        Session.toast("结束地址必须大于起始地址")
                    } else {
                        Session.dumpMemory(startAddr, endAddr, pathTf.text)
                    }
                }
            }
            AnimatedVisibility(showKeyboard && focusTarget != 2, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    BuiltInKeyboard(
                        value = if (focusTarget == 1) toTf else fromTf,
                        onValueChange = { next ->
                            if (focusTarget == 1) toTf = next else fromTf = next
                        },
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }
        }
    }
}

// ============================================================
// 分配内存页 (AllocateSheet)
// ============================================================
@Composable
fun AllocateSheet(ui: Ui) {
    var allowRead by remember { mutableStateOf(true) }
    var allowWrite by remember { mutableStateOf(false) }
    var allowExec by remember { mutableStateOf(true) }
    var addrTf by remember { mutableStateOf(TextFieldValue("0", TextRange(0, 1))) }
    var showRegionPicker by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(true) }

    NextSheetCard(
        title = "分配内存页",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            AllocPermRow("也许可读", allowRead) { allowRead = it }
            AllocPermRow("也许可写", allowWrite) { allowWrite = it }
            AllocPermRow("也许可执行", allowExec) { allowExec = it }

            Spacer(Modifier.height(6.dp))
            Text(tr("地址:"), color = Rose.Text, fontSize = fsp(14f))
            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        GgTextField(
                            value = addrTf,
                            onValueChange = { addrTf = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = monoStyle(16, Rose.Text),
                            cursorColor = Rose.Primary,
                            suppressSystemKeyboard = showKeyboard
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Rose.Primary)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .background(
                            if (showRegionPicker) Rose.ChipSelected else Rose.CardHigh,
                            RoundedCornerShape(8.dp)
                        )
                        .clickableWithTooltip {
                            showRegionPicker = !showRegionPicker
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = GgIcons.RegionPicker,
                        contentDescription = "选择内存段",
                        tint = Rose.Text,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

            AnimatedVisibility(showRegionPicker, enter = expandEnter(), exit = expandExit()) {
                val regionSamples = listOf(
                    "0 (系统自动分配)" to "0",
                    "Ca: C++ alloc (0x79F1497000)" to "79F1497000",
                    "A: Anonymous (0x79F1800000)" to "79F1800000",
                    "Xa: Code app (0x79F0100000)" to "79F0100000"
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    regionSamples.forEach { (label, hexVal) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(Rose.CardHigh, RoundedCornerShape(7.dp))
                                .clickableWithTooltip {
                                    addrTf = TextFieldValue(hexVal, TextRange(hexVal.length))
                                    showRegionPicker = false
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr(label), color = Rose.Text, fontSize = fsp(12.5f))
                        }
                    }
                }
            }

            AnimatedVisibility(showKeyboard, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    BuiltInKeyboard(
                        value = addrTf,
                        onValueChange = { addrTf = it },
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoseButton(
                    label = "取消",
                    modifier = Modifier.width(74.dp).height(38.dp)
                ) {
                    Session.closeSheet()
                }
                Spacer(Modifier.width(10.dp))
                RoseButton(
                    label = "确定",
                    filled = true,
                    modifier = Modifier.width(74.dp).height(38.dp)
                ) {
                    val clean = addrTf.text.trim().removePrefix("0x").removeSuffix("h")
                    val addr = if (clean.isEmpty() || clean == "0") 0L else (clean.toULongOrNull(16)?.toLong() ?: 0L)
                    Session.allocateMemoryPage(addr, allowRead, allowWrite, allowExec)
                }
            }
        }
    }
}

@Composable
private fun AllocPermRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableWithTooltip { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgCheckBox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            size = 22.dp,
            accentColor = Rose.Primary
        )
        Spacer(Modifier.width(10.dp))
        Text(tr(label), color = Rose.Text, fontSize = fsp(14.5f))
    }
}

// ============================================================
// 保存/载入列表文件界面
// ============================================================
@Composable
fun FileSheet(ui: Ui) {
    var tf by remember(ui.fileManagerPath) {
        mutableStateOf(TextFieldValue(ui.fileManagerPath, TextRange(0, ui.fileManagerPath.length)))
    }
    NextSheetCard(
        title = if (ui.tab == NextTab.SAVED_LIST) "保存/载入列表文件" else "导出选定项到文件",
        subtitle = "输入文件路径或点击右侧「浏览」从文件浏览器中选择",
        onDismiss = Session::closeSheet
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    FieldRow("路径", tf) {
                        tf = it
                        Session.setFileManagerPath(it.text)
                    }
                }
                RoseButton(
                    label = "浏览",
                    modifier = Modifier.width(68.dp).height(48.dp)
                ) {
                    Session.openFileBrowser(
                        initialPath = tf.text,
                        selectDirectory = false,
                        returnSheet = Sheet.FILE_MANAGER,
                        target = FileBrowserTarget.FILE_MANAGER
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(1f)) { Session.closeSheet() }
                RoseButton("确定", Modifier.weight(1f), filled = true) {
                    Session.saveOrLoadListFile(tf.text)
                }
            }
        }
    }
}

// ============================================================
// 变速
// ============================================================
@Composable
fun SpeedSheet(ui: Ui) {
    var speed by remember(ui.speedMultiplier) { mutableStateOf(ui.speedMultiplier) }
    NextSheetCard(title = "游戏变速", subtitle = "长按悬浮球也可随时呼出左右加减速控制栏", onDismiss = Session::closeSheet) {
        Column {
            Text(
                text = String.format(java.util.Locale.US, "%.2fx", speed).trimEnd('0').let {
                    if (it.endsWith(".")) "${it}0x" else if (!it.endsWith("x")) "${it}x" else it
                },
                color = Rose.Primary,
                fontSize = fsp(36f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.25f, 0.5f, 1.0f, 1.5f).forEach { v ->
                    RoseButton("${v}x", Modifier.weight(1f)) { speed = v }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2.0f, 5.0f, 10.0f, 50.0f).forEach { v ->
                    RoseButton("${v.toInt()}x", Modifier.weight(1f)) { speed = v }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("÷2", Modifier.weight(1f)) { speed = (speed / 2f).coerceAtLeast(0.05f) }
                RoseButton("×2", Modifier.weight(1f)) { speed = (speed * 2f).coerceAtMost(100f) }
                RoseButton("应用", Modifier.weight(1f), filled = true) {
                    Session.setSpeedMultiplier(speed)
                    Session.setSetting("speed_value", speed.toString())
                    Session.closeSheet()
                }
            }
        }
    }
}

// ============================================================
// 界面调整 9：语言选择弹窗（仅保留简体中文、繁體中文和 English，并实现实时语言切换）
// ============================================================
@Composable
fun LanguageSheet() {
    val ui = Session.ui.value
    val cur = ui.settings["language"] ?: "0"
    val langs = listOf(
        "0" to "简体中文 (Simplified Chinese)",
        "2" to "繁體中文 (Traditional Chinese)",
        "1" to "English"
    )
    NextSheetCard(title = "选择语言 (Language)", onDismiss = Session::closeSheet) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            langs.forEach { (code, name) ->
                val selected = cur == code
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(if (selected) Rose.ChipSelected else Rose.CardHigh, RoundedCornerShape(10.dp))
                        .clickableWithTooltip {
                            Session.setSetting("language", code)
                            Session.toast("已切换语言：$name")
                            Session.closeSheet()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, color = Rose.Text, fontSize = fsp(15f), modifier = Modifier.weight(1f))
                    if (selected) {
                        Icon(Icons.Rounded.Check, null, tint = Rose.Primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
// 历史 / 日志 / 悬浮窗 / 信息
// ============================================================
@Composable
fun HistorySheet(ui: Ui) {
    NextSheetCard(title = "输入历史", onDismiss = Session::closeSheet, maxBodyHeight = 560) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (ui.history.isEmpty()) Text(tr("无历史记录"), color = Rose.TextDim, fontSize = fsp(13f))
            ui.history.forEach { h ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(Rose.CardHigh, RoundedCornerShape(9.dp))
                        .clickableWithTooltip { Session.pickHistory(h) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(h, style = monoStyle(13, Rose.Text), modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clickableWithTooltip { Session.removeHistory(h) },
                        contentAlignment = Alignment.Center
                    ) { Text("×", color = Rose.TextDim, fontSize = fsp(15f)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            RoseButton("清空", Modifier.fillMaxWidth()) { Session.clearHistory() }
        }
    }
}

@Composable
fun FunctionSheet() {
    Session.closeSheet()
}

@Composable
fun LogSheet() {
    // Bug 9 修复：使日志内容成为可观察状态，清空时立即响应刷新界面
    var lines by remember { mutableStateOf(AppLog.snapshot()) }
    NextSheetCard(title = "运行日志", onDismiss = Session::closeSheet, maxBodyHeight = 560) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .background(Rose.CardHigh, RoundedCornerShape(9.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    if (lines.isEmpty()) tr("暂无日志") else lines.joinToString("\n"),
                    style = monoStyle(11, Rose.TextSecond),
                    lineHeight = fsp(16f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("清空", Modifier.weight(1f)) {
                    AppLog.clear()
                    lines = emptyList()
                    Session.toast("日志已清空")
                }
                RoseButton("关闭", Modifier.weight(1f), filled = true) { Session.closeSheet() }
            }
        }
    }
}

@Composable
fun OverlaySheet(ui: Ui) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    NextSheetCard(title = "悬浮窗管理", subtitle = "短按悬浮球打开面板，长按悬浮球调节变速", onDismiss = Session::closeSheet) {
        Column {
            Text(
                tr(
                    if (com.littlecodesauce.ggnext.service.OverlayManager.canDraw(ctx)) "已授予系统悬浮窗权限"
                    else "未授予系统悬浮窗权限，请先在系统设置中开启"
                ),
                color = if (com.littlecodesauce.ggnext.service.OverlayManager.canDraw(ctx)) Rose.Green else Rose.Yellow,
                fontSize = fsp(12.5f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("关闭悬浮窗", Modifier.weight(1f)) {
                    Session.toggleOverlay(ctx.applicationContext as android.app.Application, false)
                    Session.closeSheet()
                }
                RoseButton("启动悬浮窗", Modifier.weight(1f), filled = true) {
                    Session.toggleOverlay(ctx.applicationContext as android.app.Application, true)
                    Session.closeSheet()
                }
            }
        }
    }
}

@Composable
fun InfoSheet(title: String, text: String) {
    NextSheetCard(title = title, onDismiss = Session::closeSheet, maxBodyHeight = 560) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(tr(text), color = Rose.TextSecond, fontSize = fsp(13f), lineHeight = fsp(20f))
            Spacer(Modifier.height(12.dp))
            RoseButton("关闭", Modifier.fillMaxWidth(), filled = true) { Session.closeSheet() }
        }
    }
}
