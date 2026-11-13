package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

@Composable
fun fsp(base: Float): androidx.compose.ui.unit.TextUnit =
    (base * LocalUiMetrics.current.fontScale).sp

@Composable
fun NextSheetCard(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    maxBodyHeight: Int = 620,
    body: @Composable () -> Unit
) {
    val metrics = LocalUiMetrics.current
    val corner = metrics.dialogCorner.dp
    val cardAlpha = (metrics.bgOpacity + 0.04f).coerceIn(0.55f, 1.0f)
    Column(
        modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { /* 消费弹窗内容区域点击，防止误触发关闭 */ }
            .background(Rose.Card.copy(alpha = cardAlpha), RoundedCornerShape(corner))
            .border(1.dp, Rose.Divider, RoundedCornerShape(corner))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = if (subtitle.isNullOrBlank()) 8.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(20f), fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            CloseBadge(onDismiss)
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                tr(subtitle),
                color = Rose.TextSecond,
                fontSize = fsp(13f),
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight.dp)
                .padding(horizontal = 14.dp)
                .padding(bottom = 14.dp)
        ) { body() }
    }
}

@Composable
fun CloseBadge(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, Motion.Fast, label = "closeScale")
    Box(
        Modifier
            .size(36.dp)
            .scale(scale)
            .background(Rose.CardHigh, RoundedCornerShape(10.dp))
            .clickableWithTooltip("关闭", onPressChange = { pressed = it }, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Close, "关闭", tint = Rose.Text, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun FilterChipRow(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEachIndexed { i, label ->
            Chip(label, selected == i, Modifier.weight(1f)) { onSelect(i) }
        }
    }
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Rose.Primary,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, Motion.Fast, label = "chipScale")
    val bg by animateColorAsState(
        if (selected) Rose.ChipSelected else if (pressed) Rose.CardHigh else Rose.Chip,
        Motion.ColorFast,
        label = "chipBg"
    )
    Box(
        modifier
            .heightIn(min = 40.dp)
            .scale(scale)
            .background(bg, RoundedCornerShape(10.dp))
            .clickableWithTooltip(label, onPressChange = { pressed = it }, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            tr(label),
            color = if (selected) Rose.Text else Rose.TextSecond,
            fontSize = fsp(14f),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
fun RoseButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    tooltip: String = label,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed && enabled) 0.94f else 1f, Motion.Fast, label = "btnScale")
    val bg by animateColorAsState(
        when {
            !enabled -> Rose.CardHigh
            pressed -> Rose.PrimaryDeep
            filled -> Rose.ChipSelected
            else -> Rose.Chip
        },
        Motion.ColorFast,
        label = "btnBg"
    )
    Box(
        modifier
            .heightIn(min = 42.dp)
            .scale(scale)
            .background(bg, RoundedCornerShape(10.dp))
            .then(
                if (filled && enabled) Modifier.border(1.dp, Rose.Primary.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickableWithTooltip(
                tooltip = tooltip,
                enabled = enabled,
                onPressChange = { pressed = it },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            tr(label),
            color = when {
                !enabled -> Rose.TextDim
                filled -> Rose.Text
                else -> Rose.TextSecond
            },
            fontSize = fsp(14f),
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(0.6.dp)
            .background(Rose.Divider)
    )
}

@Composable
fun Scrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Rose.Scrim)
            .clickableWithTooltip("关闭弹窗", onClick = onClick)
    )
}
