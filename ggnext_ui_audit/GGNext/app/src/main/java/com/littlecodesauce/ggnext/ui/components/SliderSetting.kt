package com.littlecodesauce.ggnext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 参考图里的滑条行：标题 + 右侧数值胶囊 + 刻点滑条。
 */
@Composable
fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Rose.Row, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Rose.Text, fontSize = fsp(15f), modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .background(Rose.CardHigh, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(display, color = Rose.TextSecond, fontSize = fsp(12f))
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Rose.Primary,
                activeTrackColor = Rose.Primary,
                inactiveTrackColor = Rose.CardHigh,
                activeTickColor = Rose.Card,
                inactiveTickColor = Rose.Chip
            )
        )
    }
}
