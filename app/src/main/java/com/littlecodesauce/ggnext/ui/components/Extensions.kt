package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest

/**
 * 响应式多语言门面：
 * 统一委托给纯 Kotlin 字典 AppStrings，同时维护 Compose Snapshot State 保证整树实时热切换。
 */
object I18n {
    var currentLang by mutableStateOf(AppStrings.currentLang())

    init {
        AppStrings.onLangChanged = { lang ->
            if (currentLang != lang) currentLang = lang
        }
    }

    fun tr(text: String): String = AppStrings.tr(text, currentLang)
}

fun tr(text: String): String = I18n.tr(text)

/** 无涟漪点击：无背景闪烁，配合按下缩放动画 */
fun Modifier.clickableNoRipple(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = this.clickable(
    enabled = enabled,
    interactionSource = source,
    indication = null,
    onClick = onClick
)

/** 等宽文字样式（去除多余字体上下内边距，保证输入框光标与文字基线 100% 对齐） */
@Composable
fun monoStyle(size: Int, color: Color = Rose.Text, weight: FontWeight = FontWeight.Normal): TextStyle {
    val scaled = (size * LocalUiMetrics.current.fontScale).sp
    return TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = scaled,
        lineHeight = scaled,
        color = color,
        fontWeight = weight,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}

/**
 * 改进3：细腻动画的通用复选框组件 (GgCheckBox)
 * - 具备物理弹簧缩放回弹（按下微缩、勾选瞬间轻盈弹起）
 * - 平滑的背景填充与边框色彩过渡
 * - 渐进式对勾路径绘制动画（0f -> 1f 分两段顺滑勾出）
 * - 支持独立点击事件且不阻塞滚动
 */
@Composable
fun GgCheckBox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    accentColor: Color = Rose.Primary,
    tooltip: String = ""
) {
    var pressed by remember { mutableStateOf(false) }
    val checkProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "chkProgress"
    )
    val bounceScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.84f
            checked -> 1.0f
            else -> 0.96f
        },
        animationSpec = spring(
            dampingRatio = 0.56f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "chkScale"
    )
    val fillColor by animateColorAsState(
        targetValue = if (checked) accentColor else Color.Transparent,
        animationSpec = tween(170),
        label = "chkFill"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) accentColor else Rose.TextDim,
        animationSpec = tween(170),
        label = "chkBorder"
    )
    val checkMarkColor = if (Rose.IsDark) Color(0xFF141013) else Color(0xFFFFFFFF)

    val clickMod = if (onCheckedChange != null) {
        Modifier.clickableWithTooltip(
            tooltip = tooltip,
            onPressChange = { pressed = it },
            onClick = { onCheckedChange(!checked) }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(bounceScale)
            .then(clickMod),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = 1.8.dp.toPx()
            val cornerPx = 5.5.dp.toPx()
            val inset = strokePx / 2f
            val boxSize = Size(this.size.width - strokePx, this.size.height - strokePx)

            if (fillColor.alpha > 0.01f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(inset, inset),
                    size = boxSize,
                    cornerRadius = CornerRadius(cornerPx, cornerPx)
                )
            }
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(inset, inset),
                size = boxSize,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = strokePx)
            )

            if (checkProgress > 0.01f) {
                val w = this.size.width
                val h = this.size.height
                val p1 = Offset(w * 0.24f, h * 0.52f)
                val p2 = Offset(w * 0.43f, h * 0.71f)
                val p3 = Offset(w * 0.77f, h * 0.31f)

                val path = Path()
                path.moveTo(p1.x, p1.y)
                if (checkProgress <= 0.42f) {
                    val t = (checkProgress / 0.42f).coerceIn(0f, 1f)
                    path.lineTo(
                        p1.x + (p2.x - p1.x) * t,
                        p1.y + (p2.y - p1.y) * t
                    )
                } else {
                    path.lineTo(p2.x, p2.y)
                    val t = ((checkProgress - 0.42f) / 0.58f).coerceIn(0f, 1f)
                    path.lineTo(
                        p2.x + (p3.x - p2.x) * t,
                        p2.y + (p3.y - p2.y) * t
                    )
                }
                drawPath(
                    path = path,
                    color = checkMarkColor,
                    style = Stroke(
                        width = 2.1.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * 专用无外置键盘干扰、光标严格居中对齐的输入框组件：
 * - 3.2：获得焦点或初次打开包含非空文本的输入界面时，自动全选输入框内的内容；
 * - 3.6：长按输入框弹出「全选 / 复制 / 粘贴 / 剪切 / 清空」快捷菜单。
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GgTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = monoStyle(15, Rose.Text),
    cursorColor: Color = Rose.Primary,
    suppressSystemKeyboard: Boolean = true,
    autoSelectOnFocus: Boolean = false,
    initialFocus: Boolean = false,
    onFocus: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    var wasFocused by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val currentVal by androidx.compose.runtime.rememberUpdatedState(value)
    val currentOnValueChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val currentSuppress by androidx.compose.runtime.rememberUpdatedState(suppressSystemKeyboard)

    var wakeNonce by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    fun wakeExternalKeyboardIfAllowed() {
        if (!currentSuppress) {
            wakeNonce++
            view.requestFocus()
            keyboardController?.show()
            val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    if (initialFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
            wakeExternalKeyboardIfAllowed()
        }
    }

    androidx.compose.runtime.LaunchedEffect(suppressSystemKeyboard, wasFocused, wakeNonce) {
        if (wasFocused) {
            if (suppressSystemKeyboard) {
                keyboardController?.hide()
            } else {
                keyboardController?.show()
                val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                androidx.compose.runtime.snapshotFlow { currentSuppress to wakeNonce }
                    .collectLatest { (suppress, _) ->
                        if (suppress) {
                            keyboardController?.hide()
                            awaitCancellation()
                        } else {
                            keyboardController?.show()
                            nextHandler.startInputMethod(request)
                        }
                    }
                awaitCancellation()
            }
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(cursorColor),
                onTextLayout = { textLayoutResult = it },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && !wasFocused) {
                            wasFocused = true
                            onFocus?.invoke()
                            wakeExternalKeyboardIfAllowed()
                        } else if (!focusState.isFocused) {
                            wasFocused = false
                        }
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = tr(placeholder),
                                style = textStyle.copy(color = Rose.TextDim),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            innerTextField()
                        }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    var lastTapTimeMs = 0L
                                    var lastTapX = -1000f
                                    detectTapGestures(
                                        onLongPress = {
                                            focusRequester.requestFocus()
                                            onFocus?.invoke()
                                            wakeExternalKeyboardIfAllowed()
                                            showContextMenu = true
                                        },
                                        onTap = { tapOffset ->
                                            focusRequester.requestFocus()
                                            onFocus?.invoke()
                                            wakeExternalKeyboardIfAllowed()
                                            val text = currentVal.text
                                            if (text.isEmpty()) {
                                                currentOnValueChange(
                                                    currentVal.copy(selection = androidx.compose.ui.text.TextRange.Zero)
                                                )
                                                return@detectTapGestures
                                            }
                                            val layout = textLayoutResult
                                            val targetOffset = if (layout != null) {
                                                val centerY = layout.size.height / 2f
                                                layout.getOffsetForPosition(Offset(tapOffset.x, centerY))
                                                    .coerceIn(0, text.length)
                                            } else {
                                                text.length
                                            }
                                            val now = android.os.SystemClock.uptimeMillis()
                                            val hadSelection = !currentVal.selection.collapsed
                                            val isRapidDoubleTap = !hadSelection &&
                                                (now - lastTapTimeMs) in 40L..280L &&
                                                kotlin.math.abs(tapOffset.x - lastTapX) < 28.dp.toPx()

                                            if (isRapidDoubleTap) {
                                                lastTapTimeMs = 0L
                                                currentOnValueChange(
                                                    currentVal.copy(
                                                        selection = androidx.compose.ui.text.TextRange(0, text.length)
                                                    )
                                                )
                                            } else {
                                                lastTapTimeMs = now
                                                lastTapX = tapOffset.x
                                                currentOnValueChange(
                                                    currentVal.copy(
                                                        selection = androidx.compose.ui.text.TextRange(targetOffset)
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }
                        )
                    }
                }
            )
        }

        // 界面问题 6：长按文本工具改为横向紧凑排列，单按钮体积适中不臃肿
        androidx.compose.material3.DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(Rose.CardHigh)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ContextToolbarBtn(tr("全选"), Rose.Text) {
                    showContextMenu = false
                    val t = currentVal.text
                    currentOnValueChange(currentVal.copy(selection = androidx.compose.ui.text.TextRange(0, t.length)))
                }
                ContextToolbarDivider()
                ContextToolbarBtn(tr("复制"), Rose.Text) {
                    showContextMenu = false
                    val sel = currentVal.selection
                    val textToCopy = if (!sel.collapsed) {
                        currentVal.text.substring(sel.min.coerceAtLeast(0), sel.max.coerceAtMost(currentVal.text.length))
                    } else {
                        currentVal.text
                    }
                    if (textToCopy.isNotEmpty()) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                        com.littlecodesauce.ggnext.core.Session.toast("已复制: $textToCopy")
                    }
                }
                ContextToolbarDivider()
                ContextToolbarBtn(tr("粘贴"), Rose.Text) {
                    showContextMenu = false
                    val clipText = clipboard.getText()?.text.orEmpty()
                    if (clipText.isNotEmpty()) {
                        val sel = currentVal.selection
                        val start = sel.min.coerceIn(0, currentVal.text.length)
                        val end = sel.max.coerceIn(start, currentVal.text.length)
                        val nextText = currentVal.text.substring(0, start) + clipText + currentVal.text.substring(end)
                        val nextCursor = start + clipText.length
                        currentOnValueChange(
                            TextFieldValue(
                                text = nextText,
                                selection = androidx.compose.ui.text.TextRange(nextCursor)
                            )
                        )
                    }
                }
                ContextToolbarDivider()
                ContextToolbarBtn(tr("剪切"), Rose.Text) {
                    showContextMenu = false
                    val sel = currentVal.selection
                    if (!sel.collapsed) {
                        val start = sel.min.coerceIn(0, currentVal.text.length)
                        val end = sel.max.coerceIn(start, currentVal.text.length)
                        val cutStr = currentVal.text.substring(start, end)
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(cutStr))
                        val nextText = currentVal.text.removeRange(start, end)
                        currentOnValueChange(TextFieldValue(text = nextText, selection = androidx.compose.ui.text.TextRange(start)))
                    } else if (currentVal.text.isNotEmpty()) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(currentVal.text))
                        currentOnValueChange(TextFieldValue(text = "", selection = androidx.compose.ui.text.TextRange(0)))
                    }
                }
                ContextToolbarDivider()
                ContextToolbarBtn(tr("清空"), Rose.Red) {
                    showContextMenu = false
                    currentOnValueChange(TextFieldValue(text = "", selection = androidx.compose.ui.text.TextRange(0)))
                }
            }
        }
    }
}

@Composable
private fun ContextToolbarBtn(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = color,
            fontSize = fsp(12f),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun ContextToolbarDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 14.dp)
            .background(Rose.Divider)
    )
}

/**
 * 持久化列表滚动位置状态（Bug 2.1 修复）：
 * 在切换标签页或最小化修改器后再次展开时，自动恢复列表此前的首条可见索引与像素偏移量。
 */
@Composable
fun rememberPersistentLazyListState(listKey: String): LazyListState {
    val saved = remember(listKey) {
        com.littlecodesauce.ggnext.core.Session.getListScrollPosition(listKey)
    }
    val listState = remember(listKey) {
        LazyListState(
            firstVisibleItemIndex = saved.first,
            firstVisibleItemScrollOffset = saved.second
        )
    }
    androidx.compose.runtime.LaunchedEffect(listState, listKey) {
        androidx.compose.runtime.snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, off) ->
            com.littlecodesauce.ggnext.core.Session.saveListScrollPosition(listKey, idx, off)
        }
    }
    androidx.compose.runtime.DisposableEffect(listState, listKey) {
        onDispose {
            com.littlecodesauce.ggnext.core.Session.saveListScrollPosition(
                listKey,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }
    return listState
}

/**
 * 列表右侧快速滑动条 (VerticalFastScrollbar)（Bug 2 修复）：
 * - 修复此前因 pointerInput(totalItems, visibleItems) 在滚动过程中随 visibleItems ±1 抖动重启协程导致拖拽中断的问题；
 * - 移除强制长按 80ms 才能拖动的限制：只要手指按在滑块 (thumb) 区域并垂直拖动即可立即顺滑跟手响应；
 * - 空白轨道区域不拦截点击或普通滑动，彻底兼顾防误触与直接拖动体验。
 */
@Composable
fun VerticalFastScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size
    if (totalItems <= 0 || visibleItems <= 0 || totalItems <= visibleItems) return

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    val liveFraction = run {
        val firstIndex = listState.firstVisibleItemIndex.toFloat()
        val maxIndex = (totalItems - visibleItems).coerceAtLeast(1).toFloat()
        (firstIndex / maxIndex).coerceIn(0f, 1f)
    }
    val scrollFraction = if (isDragging) dragFraction else liveFraction
    val currentLiveFraction by androidx.compose.runtime.rememberUpdatedState(liveFraction)
    val currentTotalItems by androidx.compose.runtime.rememberUpdatedState(totalItems)
    val currentVisibleItems by androidx.compose.runtime.rememberUpdatedState(visibleItems)

    val thumbAlpha by animateFloatAsState(
        targetValue = when {
            isDragging -> 0.96f
            listState.isScrollInProgress -> 0.82f
            else -> 0.48f
        },
        animationSpec = tween(150),
        label = "sbAlpha"
    )
    val thumbWidthDp by animateFloatAsState(
        targetValue = if (isDragging) 6.5f else 4.0f,
        animationSpec = tween(140),
        label = "sbWidth"
    )
    val primaryColor = Rose.Primary
    val trackColor = Rose.Divider.copy(alpha = if (isDragging) 0.34f else 0.14f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(18.dp)
            .pointerInput(Unit) {
                val minThumbPx = 30.dp.toPx()
                val hitPadPx = 14.dp.toPx()

                awaitPointerEventScope {
                    while (true) {
                        val downEvent = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val downChange = downEvent.changes.firstOrNull() ?: continue
                        if (!downChange.pressed || downChange.isConsumed) continue

                        val curTotal = currentTotalItems
                        val curVisible = currentVisibleItems
                        if (curTotal <= curVisible || curTotal <= 0) continue

                        val trackH = size.height.toFloat().coerceAtLeast(1f)
                        val thumbRatio = (curVisible.toFloat() / curTotal.toFloat()).coerceIn(0.08f, 0.55f)
                        val thumbH = (trackH * thumbRatio).coerceAtLeast(minThumbPx)
                        val availRange = (trackH - thumbH).coerceAtLeast(1f)
                        val startFrac = currentLiveFraction
                        val curThumbTop = availRange * startFrac

                        val downX = downChange.position.x
                        val downY = downChange.position.y
                        // 仅当按下位置命中滑块 (thumb) 上下容差范围时接管拖拽，空白轨道不拦截普通列表滑动
                        val hitThumb = downY >= (curThumbTop - hitPadPx) && downY <= (curThumbTop + thumbH + hitPadPx)
                        if (!hitThumb) {
                            while (true) {
                                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (ev.changes.none { it.pressed }) break
                            }
                            continue
                        }

                        var activated = false
                        var abortedHorizontal = false
                        var grabAnchorY = downY
                        var anchorFrac = startFrac

                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val ch = event.changes.firstOrNull() ?: break
                            if (!ch.pressed) break

                            val dx = kotlin.math.abs(ch.position.x - downX)
                            val dy = kotlin.math.abs(ch.position.y - downY)

                            if (!activated && !abortedHorizontal) {
                                if (dx > 12.dp.toPx() && dx > dy * 1.5f) {
                                    abortedHorizontal = true
                                } else if (dy >= 0.5.dp.toPx()) {
                                    activated = true
                                    isDragging = true
                                    grabAnchorY = downY
                                    anchorFrac = startFrac
                                    dragFraction = startFrac
                                }
                            }

                            if (abortedHorizontal) {
                                continue
                            }

                            if (activated) {
                                val latestTotal = currentTotalItems
                                val latestVis = currentVisibleItems.coerceAtLeast(1)
                                val deltaY = ch.position.y - grabAnchorY
                                val nextFrac = (anchorFrac + deltaY / availRange).coerceIn(0f, 1f)
                                dragFraction = nextFrac
                                val maxIdx = (latestTotal - latestVis).coerceAtLeast(1)
                                val nextIdx = (nextFrac * maxIdx).toInt().coerceIn(0, (latestTotal - 1).coerceAtLeast(0))
                                scope.launch { listState.scrollToItem(nextIdx) }
                                ch.consume()
                            }
                        }
                        isDragging = false
                    }
                }
            }
    ) {
        val density = LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val thumbRatio = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.08f, 0.55f)
        val thumbHeightPx = (trackHeightPx * thumbRatio).coerceAtLeast(with(density) { 30.dp.toPx() })
        val availableRangePx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val thumbTopPx = availableRangePx * scrollFraction

        Canvas(modifier = Modifier.fillMaxSize()) {
            val wPx = thumbWidthDp.dp.toPx()
            val xLeft = this.size.width - wPx - 2.dp.toPx()
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(xLeft, 4.dp.toPx()),
                size = Size(wPx, (this.size.height - 8.dp.toPx()).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(wPx / 2f, wPx / 2f)
            )
            drawRoundRect(
                color = primaryColor.copy(alpha = thumbAlpha),
                topLeft = Offset(xLeft, thumbTopPx),
                size = Size(wPx, thumbHeightPx),
                cornerRadius = CornerRadius(wPx / 2f, wPx / 2f)
            )
        }
    }
}

@Composable
fun Label(text: String, color: Color = Rose.TextSecond, size: Int = 12) {
    Text(tr(text), color = color, fontSize = (size * LocalUiMetrics.current.fontScale).sp)
}

@Composable
fun Gap(h: Int) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp))
}
