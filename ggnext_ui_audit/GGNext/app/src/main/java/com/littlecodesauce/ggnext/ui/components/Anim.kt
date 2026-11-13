package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import com.littlecodesauce.ggnext.ui.theme.Motion

/** 弹窗入场：缩放 + 淡入（只走 graphicsLayer，不触发重组） */
fun dialogEnter(): EnterTransition =
    fadeIn(tween(Motion.DIALOG_ENTER_MS)) + scaleIn(
        initialScale = 0.92f,
        animationSpec = tween(Motion.DIALOG_ENTER_MS)
    )

fun dialogExit(): ExitTransition =
    fadeOut(tween(Motion.DIALOG_EXIT_MS)) + scaleOut(
        targetScale = 0.96f,
        animationSpec = tween(Motion.DIALOG_EXIT_MS)
    )

/** 列表项入场/退场 */
fun itemEnter(): EnterTransition =
    fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 6 }

fun itemExit(): ExitTransition =
    fadeOut(tween(140)) + shrinkVertically(tween(140))

/** 页面切换：左右滑入，方向由新旧索引决定 */
fun pageEnter(forward: Boolean): EnterTransition =
    fadeIn(tween(200)) + slideInHorizontally(tween(220)) { w -> if (forward) w / 8 else -w / 8 }

fun pageExit(forward: Boolean): ExitTransition =
    fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { w -> if (forward) -w / 12 else w / 12 }

/** 展开 / 收起一行 */
fun expandEnter(): EnterTransition = expandVertically(tween(200)) + fadeIn(tween(200))
fun expandExit(): ExitTransition = shrinkVertically(tween(160)) + fadeOut(tween(120))

/** 入场上浮：延迟按索引递增，形成瀑布节奏 */
@Composable
fun StaggeredAppear(
    index: Int,
    content: @Composable () -> Unit
) {
    val progress = remember(index) { Animatable(0f) }
    LaunchedEffect(index) {
        kotlinx.coroutines.delay((index.coerceAtMost(12) * 26).toLong())
        progress.animateTo(1f, tween(320))
    }
    androidx.compose.foundation.layout.Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 26f
        }
    ) { content() }
}

/** 按下缩放反馈，用于所有可点击卡片 */
fun Modifier.pressScale(pressed: Boolean, target: Float = 0.972f): Modifier = composed {
    val scale by animateFloatAsState(if (pressed) target else 1f, Motion.Springy, label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 淡入淡出组合，供内容替换使用 */
@Composable
fun CrossFadeBox(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.98f),
        exit = fadeOut(tween(140)),
        modifier = modifier
    ) { content() }
}
