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

private const val CHANGELOG_TEXT = """GGNext v0.6 更新日志：
1. 修复共享 UID 进程枚举与附加链路（支持 SELinux /proc 探测回退、15 字符 comm 包名反查及真实可读内存段校验）；
2. 全面兼容原版 GG 联合搜索语义（支持 100:200:100、100:200::100 有序匹配、全角符号兼容及完整保存各字段命中地址），并新增原生级联合搜索改善 (REFINE_GROUP) 解决进度卡住与结果不精准问题；
3. 内存编辑器列表上下边界新增「转到此地址：xxxx」翻页按钮，移除搜索列表项后支持从剩余结果池自动按序补位；
4. 重构工具栏长按拖拽状态机（消除滚动坐标二次叠加错位与槽位交换弹簧抽搐）与右侧快速拖动条防误触机制；
5. 重构「地址偏移/异或」计算页并为关键数据添加高光，修复 64 位指针搜索 ARM64 TBI 标签掩码与类型/偏移传递问题，优化虚拟空间与系统子进程分类。"""
