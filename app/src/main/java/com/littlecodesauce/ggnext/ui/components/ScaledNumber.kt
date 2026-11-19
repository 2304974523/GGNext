package com.littlecodesauce.ggnext.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.typeColor

/**
 * 高性能数值文本组件：
 * - Bug 2 修复：当数值被修改后（`item.value != item.previousValue`），在数值后用括号展示修改前的数值（如 `99 (100)`），与原版 GG 保持一致。
 * - 通过 remember 缓存格式化结果与颜色，滚动时零额外字符串分配。
 */
@Composable
fun ScaledNumber(item: MemoryItem, mask: Int) {
    val text = remember(item.value, item.previousValue, item.manuallyEdited, item.flags, mask) {
        valueTextWithMask(item, mask)
    }
    val isDark = Rose.IsDark
    val color = remember(item.flags, isDark) { typeColor(item.flags) }
    Text(
        text = text,
        style = monoStyle(15, color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

fun valueText(item: MemoryItem): String = when (item.flags and 0x7F) {
    ValueType.FLOAT -> Formatter.floatText(item.value)
    ValueType.DOUBLE -> Formatter.doubleText(item.value)
    ValueType.BYTE -> Formatter.signed(item.value, 1).toString()
    ValueType.WORD -> Formatter.signed(item.value, 2).toString()
    ValueType.QWORD -> Formatter.signed(item.value, 8).toString()
    else -> Formatter.signed(item.value, 4).toString()
}

fun previousValueText(item: MemoryItem): String = when (item.flags and 0x7F) {
    ValueType.FLOAT -> Formatter.floatText(item.previousValue)
    ValueType.DOUBLE -> Formatter.doubleText(item.previousValue)
    ValueType.BYTE -> Formatter.signed(item.previousValue, 1).toString()
    ValueType.WORD -> Formatter.signed(item.previousValue, 2).toString()
    ValueType.QWORD -> Formatter.signed(item.previousValue, 8).toString()
    else -> Formatter.signed(item.previousValue, 4).toString()
}

fun valueTextWithMask(item: MemoryItem, mask: Int): String {
    val curVal = valueText(item)
    // 仅当用户手动修改过该地址数值时，才在后方展示修改前的数值
    val base = if (item.manuallyEdited && item.value != item.previousValue) {
        "$curVal (${previousValueText(item)})"
    } else {
        curVal
    }
    val extras = StringBuilder()
    if (mask and Formatter.F_HEX != 0 && mask != Formatter.DEFAULT_MASK) {
        extras.append("  ").append(Formatter.hex(item.value, item.size)).append("h")
    }
    if (mask and Formatter.F_RHEX != 0) {
        val rhex = Formatter.hex(item.value, item.size).chunked(2).reversed().joinToString("")
        extras.append("  ").append(rhex).append("r")
    }
    if (mask and Formatter.F_STRING != 0) {
        val s = Formatter.bytesToAscii(item.value, item.size)
        if (s.isNotEmpty()) extras.append("  '").append(s).append('\'')
    }
    if (mask and Formatter.F_XOR != 0) {
        val xored = ((item.value and 0xFFFFFFFFL) xor (item.address and 0xFFFFFFFFL)).toInt()
        extras.append("  X:").append(xored)
    }
    if (extras.isEmpty()) return base
    return base + extras.toString()
}
