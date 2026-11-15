package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.ItemEnter
import com.littlecodesauce.ggnext.ui.components.ScaledNumber
import com.littlecodesauce.ggnext.ui.components.VerticalFastScrollbar
import com.littlecodesauce.ggnext.ui.components.clickableLongPress
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.regionColor
import com.littlecodesauce.ggnext.ui.theme.typeColor

@Composable
fun SearchListScreen(ui: Ui) {
    if (ui.results.isEmpty()) {
        EmptySearch(ui)
        return
    }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            itemsIndexed(
                items = ui.results,
                key = { idx, i -> "${i.address}_${i.flags}_$idx" },
                contentType = { _, _ -> "addr_row" }
            ) { idx, item ->
                AddressRow(item, ui.valueMask, idx)
            }
        }
        VerticalFastScrollbar(
            listState = state,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * 搜索结果行：
 * 界面调整 3：全面使用与滚动容器协同的 clickableLongPress / clickableWithTooltip，
 * 当手指按在任意地址或按钮上滑动屏幕时，列表平滑滚动绝不卡顿。
 */
@Composable
fun AddressRow(item: MemoryItem, mask: Int, index: Int) {
    val currentItem by rememberUpdatedState(item)
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val accent = remember(item.flags) { typeColor(item.flags) }
    val regColor = remember(item.region) { regionColor(item.region) }
    val shortType = remember(item.flags) { ValueType.shortName(item.flags) }
    val bg by animateColorAsState(
        if (item.checked) Rose.Primary.copy(alpha = if (Rose.IsDark) 0.18f else 0.22f)
        else Rose.Row.copy(alpha = (bgOpacity * 0.88f).coerceIn(0.18f, 1.0f)),
        Motion.ColorFast,
        label = "rowBg"
    )
    ItemEnter(item.address, index) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .background(bg, RoundedCornerShape(10.dp))
                .clickableLongPress(
                    onClick = { Session.toggle(currentItem) },
                    onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GgCheckBox(
                checked = item.checked,
                onCheckedChange = { Session.toggle(currentItem) },
                size = 21.dp,
                accentColor = Rose.Primary
            )
            Spacer(Modifier.width(10.dp))
            Text(item.cachedAddressHex, style = monoStyle(14, Rose.Primary))
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .weight(1f)
                    .clickableLongPress(
                        onClick = { Session.openSheet(Sheet.EDITOR, currentItem, singleEdit = true) },
                        onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                    )
            ) {
                ScaledNumber(item, mask)
            }
            if (item.pointer > 0) {
                Text("${item.pointer}", style = monoStyle(11, Rose.TextDim))
                Spacer(Modifier.width(6.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shortType, color = accent, fontSize = fsp(11.5f))
                Text(item.region, style = monoStyle(10, regColor))
            }
            Spacer(Modifier.width(6.dp))
            var delPressed by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .size(28.dp)
                    .clickableWithTooltip(
                        onPressChange = { delPressed = it },
                        onClick = { Session.deleteResultItem(currentItem) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    "移除",
                    tint = Rose.Red.copy(alpha = if (delPressed) 1f else 0.78f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * 界面调整 5：搜索页去除「选择进程」「精确搜索」「模糊搜索」「内存区域」等快捷按钮。
 */
@Composable
private fun EmptySearch(ui: Ui) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            tr(if (ui.target == null) "未选择进程。\n点击进程栏选择目标。" else "打开搜索面板开始。"),
            color = Rose.TextSecond,
            fontSize = fsp(14f),
            lineHeight = fsp(21f),
            textAlign = TextAlign.Center
        )
    }
}
