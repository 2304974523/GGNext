package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.GgSwitch
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.screen.SliderRow
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import kotlin.math.roundToInt

@Composable
fun AppearanceSheet(ui: Ui) {
    val m = ui.metrics
    val bgOpacityPct = (m.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (m.panelScale * 100f).roundToInt().coerceIn(40, 100)

    NextSheetCard(
        title = "外观",
        subtitle = "主题配色、深浅色模式、背景透明度与屏幕占比",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 720
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(tr("主题色"), color = Rose.TextSecond, fontSize = fsp(13f))
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePreset.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            val on = ui.preset == p
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickableWithTooltip {
                                        Session.setPreset(p)
                                        Session.toast("已切换为「${tr(p.label)}」主题")
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .background(p.seed, CircleShape)
                                        .then(if (on) Modifier.border(2.dp, Rose.Text, CircleShape) else Modifier),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (on) {
                                        Icon(
                                            Icons.Rounded.Check, null,
                                            tint = Color(0xFF161114),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    tr(p.label),
                                    color = if (on) Rose.Text else Rose.TextSecond,
                                    fontSize = fsp(11f),
                                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(tr("深色模式"), color = Rose.TextSecond, fontSize = fsp(13f))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkMode.entries.forEach { d ->
                    val on = ui.darkMode == d
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .background(
                                if (on) Rose.ChipSelected else Rose.CardHigh,
                                RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = if (on) 1.dp else 0.dp,
                                color = if (on) Rose.Primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickableWithTooltip {
                                Session.setDarkMode(d)
                                Session.toast("${tr("深色模式")}：${tr(d.label)}")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            tr(d.label),
                            color = if (on) Rose.Text else Rose.TextSecond,
                            fontSize = fsp(13f),
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(
                        if (ui.dynamicColor) Rose.ChipSelected else Rose.CardHigh,
                        RoundedCornerShape(10.dp)
                    )
                    .clickableWithTooltip {
                        val next = !ui.dynamicColor
                        Session.setDynamicColor(next)
                        Session.toast(if (next) "已开启壁纸动态取色" else "已关闭壁纸动态取色")
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("动态取色"), color = Rose.Text, fontSize = fsp(14f))
                    Text(
                        tr(
                            if (android.os.Build.VERSION.SDK_INT >= 31) "Android 12+ 从壁纸提取配色"
                            else "需要 Android 12 及以上"
                        ),
                        color = Rose.TextSecond,
                        fontSize = fsp(11f)
                    )
                }
                com.littlecodesauce.ggnext.ui.components.GgSwitch(
                    checked = ui.dynamicColor,
                    onCheckedChange = {
                        Session.setDynamicColor(it)
                        Session.toast(if (it) "已开启壁纸动态取色" else "已关闭壁纸动态取色")
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            SliderRow("背景透明度", bgOpacityPct.toFloat(), 25f..100f, "$bgOpacityPct%") { pct ->
                val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
            }
            SliderRow("页面屏幕占比", panelScalePct.toFloat(), 40f..100f, "$panelScalePct%") { pct ->
                val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
            }
            SliderRow("界面字体缩放", m.fontScale, 0.7f..1.3f, "%.2f".format(m.fontScale)) {
                Session.setSetting("font_scale", "%.2f".format(it))
            }
            SliderRow("伪弹窗圆角", m.dialogCorner.toFloat(), 0f..28f, "${m.dialogCorner}dp") {
                Session.setSetting("dialog_corner", it.toInt().toString())
            }
            Spacer(Modifier.height(8.dp))
            RoseButton("完成", Modifier.fillMaxWidth(), filled = true) { Session.closeSheet() }
        }
    }
}
