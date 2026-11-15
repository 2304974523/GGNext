package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.Scrim
import com.littlecodesauce.ggnext.ui.components.dialogEnter
import com.littlecodesauce.ggnext.ui.components.dialogExit

/** 弹窗宿主：遮罩淡入 + 卡片缩放入场，返回键关闭 */
@Composable
fun SheetHost(ui: Ui) {
    val open = ui.sheet != Sheet.NONE
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Scrim { Session.closeSheet() }
        }
        AnimatedVisibility(
            visible = open,
            enter = dialogEnter(),
            exit = dialogExit(),
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { Session.closeSheet() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { /* 消费所有弹窗内部空白处点击，仅点击弹窗外遮罩时关闭 */ }
                ) {
                    when (ui.sheet) {
                        Sheet.PROCESS -> ProcessSheet(ui)
                        Sheet.SEARCH -> SearchSheet(ui)
                        Sheet.FUZZY -> FuzzySearchSheet(ui)
                        Sheet.MASK_SEARCH -> MaskSearchSheet(ui)
                        Sheet.POINTER_SEARCH -> PointerSearchSheet(ui)
                        Sheet.FILTER -> FilterSearchSheet(ui)
                        Sheet.REGION -> RegionSheet(ui)
                        Sheet.VALUE_FORMAT -> FormatSheet(ui)
                        Sheet.EDITOR -> EditSheet(ui)
                        Sheet.OFFSET -> OffsetSheet(ui)
                        Sheet.CALC_OFFSET -> CalcOffsetXorSheet(ui)
                        Sheet.REMOVE -> RemoveSheet(ui)
                        Sheet.TOOLBAR_ORDER -> ToolbarSheet(ui)
                        Sheet.TOOLBAR_PICK -> ToolbarPickSheet(ui)
                        Sheet.GOTO -> GotoSheet()
                        Sheet.MODULE_LIST -> ModuleListSheet()
                        Sheet.SCRIPT -> ScriptSheet(ui)
                        Sheet.FILE_BROWSER -> FileBrowserSheet(ui)
                        Sheet.SPEED -> SpeedSheet(ui)
                        Sheet.HISTORY -> HistorySheet(ui)
                        Sheet.FUNCTION -> FunctionSheet()
                        Sheet.LOG -> LogSheet()
                        Sheet.OVERLAY -> OverlaySheet(ui)
                        Sheet.HELP -> InfoSheet("使用帮助", HELP_TEXT)
                        Sheet.ABOUT -> AboutSheet(ui)
                        Sheet.CHANGELOG -> InfoSheet("更新日志", CHANGELOG_TEXT)
                        Sheet.LANGUAGE -> LanguageSheet()
                        Sheet.APPEARANCE -> AppearanceSheet(ui)
                        Sheet.CONTEXT -> ContextSheet(ui)
                        Sheet.TYPE_PICK -> TypePickSheet(ui)
                        Sheet.ADDRESS_EDIT -> AddressEditSheet(ui)
                        Sheet.FILE_MANAGER -> FileSheet(ui)
                        Sheet.COPY_MEMORY -> CopyMemorySheet(ui)
                        Sheet.DUMP -> DumpSheet(ui)
                        Sheet.ALLOCATE -> AllocateSheet(ui)
                        Sheet.NONE -> Unit
                    }
                }
            }
        }
    }
}

private const val HELP_TEXT = """• 精确与高级搜索：支持数字、范围 100~200、反向范围 100~~200、文本 'abc' / "abc"、HEX 数值（1234h / h4142）、联合搜索 1;2;3:512、有序联合 1;2::64、异或 100X7。
• 模糊搜索：在无搜索结果时点击工具栏「🔍?」选择数据类型并点击「搜索」开始初始采样；数值变化后通过「无变化 / 有变化 / 增加了 / 减少了」定位目标地址。
• 内置软键盘：点击 `fx` 可切换为符号与运算符键盘，`123` 按钮可切回数字与十六进制键盘。
• 工具栏长按排序：长按顶部工具栏图标并左右滑动可调整顺序，点击右侧固定按钮打开「工具栏顺序」可上下拖动或增删工具。
• 脚本与文件浏览器：工具栏右侧「<>」按钮可打开脚本执行页，点击「浏览」可通过内置文件浏览器选择文件。"""

private const val CHANGELOG_TEXT = """GGNext v0.5 更新日志：
1. 修复联合搜索崩溃及模糊搜索、加密搜索后后续搜索结果异常归零的问题，支持对模糊快照直接进行精确与范围过滤；
2. 优化输入框交互，仅自动全选默认主输入框，支持点击空白或字符间隙取消全选与精确定位光标；
3. 修正模糊搜索等弹窗空白处点击误关闭问题，为终止进程与退出操作增加二次确认；
4. 重写偏移量与异或计算面板，新增设置滑条动态波浪拖动效果，修复长按内存数值不弹出菜单的问题；
5. 移除冗余繁体选项及提示气泡多余后缀，修复主页设置切换主题后自动跳回主页的问题。"""
