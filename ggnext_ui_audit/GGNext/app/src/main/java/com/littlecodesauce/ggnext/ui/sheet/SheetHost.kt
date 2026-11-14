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
                    Sheet.ABOUT -> InfoSheet("关于 GGNext", aboutText(ui))
                    Sheet.CHANGELOG -> InfoSheet("更新日志 (v0.4)", CHANGELOG_TEXT)
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

private const val HELP_TEXT = """• 精确与高级搜索：支持数字、范围 100~200、反向范围 100~~200、文本 'abc' (UTF-8) / "abc" (UTF-16LE)、HEX（1234h / h4142）、联合搜索 1;2;3:512、有序联合 1;2::64、异或 100X7。
• 模糊搜索：在无搜索结果时点击工具栏「🔍?」选择数据类型并点击「搜索」开始初始采样；数值变化后通过「无变化 / 有变化 / 增加了 / 减少了」快速定位目标地址。
• 内置软键盘：点击 `fx` 可直接在键盘内切换为符号与运算符键盘（`123` 按钮可切回数字/十六进制键盘）。
• 工具栏长按滑动排序：可直接长按顶部工具栏中的工具图标并左右滑动实时调整顺序，也可点击右侧固定按钮打开「工具栏顺序」上下拖动或增删工具。
• 执行脚本与文件浏览器：工具栏右侧固定「<>」按钮可打开脚本执行页，点击「浏览」可通过内置文件浏览器（区分文件夹与 .lua/.so/.txt/.md 文件图标）选择文件。"""

private const val CHANGELOG_TEXT = """GGNext v0.4 更新日志：
1. 彻底移除长按普通按钮显示按钮名称气泡功能，仅针对顶部工具栏支持长按工具图标并左右滑动实时调整顺序；
2. 深度优化触摸与滚动手势协同，彻底解决初始手指按在按钮或列表项上时无法滑动屏幕的问题；
3. 修复切换浅色模式时部分文字未立即同步的问题，并深度优化浅色模式配色与字体清晰度；
4. 修复悬浮球长按呼出变速调节栏时窗口宽度未同步刷新导致的截断问题；
5. 修复「工具栏顺序」弹窗中长按拖动工具时列表不跟随移动的问题，并统一固定工具栏「<>」与「≡」按钮颜色；
6. 设置页将「通用设置」移至最上方，去除「对游戏隐藏 GG」等浮动按钮右上角的状态胶囊，去除搜索/内存空状态及导出/复制内存的冗余快捷按钮；
7. 精确搜索、模糊搜索与类型选择展开列表不再显示数值范围，搜索条件与数据类型旁边的三角箭头支持未展开朝右（▸）、展开朝下（▾）动态旋转；
8. 语言设置精简为简体中文、繁體中文、English 三种语言，并实现全界面实时语言切换。"""

private fun aboutText(ui: Ui): String = """GGNext  v0.4
基于 GameGuardian 深度重制的现代化高性能内存调试工具
技术栈：Kotlin 2.0 + Jetpack Compose + C++20 MemEngine (NEON SIMD)

当前目标进程：${ui.target?.label ?: "未选择"}
工作模式：${ui.workMode.title} (${if (ui.attachedOk) "已成功附加跨进程通道" else "未附加"})
已选内存范围：${ui.selectedRegions.joinToString(", ")}"""
