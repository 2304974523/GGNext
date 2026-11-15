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
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlinx.coroutines.awaitCancellation

/**
 * 界面调整 9：
 * 多语言引擎（仅支持：0 = 简体中文、1 = English），支持全界面实时切换语言。
 */
object I18n {
    var currentLang by mutableStateOf("0")

    private val enMap: Map<String, String> = mapOf(
        "主页" to "Home",
        "设置" to "Settings",
        "菜单" to "Menu",
        "软件设置" to "App Settings",
        "启动" to "Start",
        "已启动" to "Running",
        "未启动" to "Stopped",
        "启动悬浮窗" to "Start Overlay",
        "停止悬浮窗" to "Stop Overlay",
        "停止服务" to "Stop Service",
        "展开面板" to "Open Panel",
        "展开修改器" to "Open Modifier",
        "Android 内存调试工具" to "Android Memory Debugger",
        "悬浮窗运行中" to "Overlay Active",
        "悬浮窗未启动" to "Overlay Inactive",
        "短按悬浮球打开调试面板，长按悬浮球调节游戏速率" to "Tap the floating ball to open the panel, hold to adjust speed",
        "启动后将在屏幕显示可拖拽悬浮球，点击即可附加进程并检索内存" to "Shows a draggable floating ball on screen to attach processes and inspect memory",
        "运行环境" to "Environment",
        "运行方式" to "Operation Mode",
        "已允许在其他应用上层显示" to "Display over other apps allowed",
        "尚未授予系统悬浮窗权限" to "Overlay permission not granted yet",
        "已授权" to "Granted",
        "未授权" to "Unauthorized",
        "共享 UID" to "Shared UID",
        "Root" to "Root",
        "免 Root" to "No Root",
        "Root 模式" to "Root Mode",
        "免 Root 模式" to "No-Root Mode",
        "Root 权限已获取，支持全系统进程读写" to "Root access ready for system-wide process memory I/O",
        "未检测到 Root 授权，点击检测或切换至免 Root" to "Root access not detected, tap to recheck or switch to No-Root",
        "适用虚拟空间及共享 UID 环境，无需 Root 权限" to "For virtual spaces and shared UID environments without Root",
        "主题配色" to "Theme Color",
        "界面语言" to "Language",
        "语言" to "Language",
        "恢复默认设置" to "Reset Default Settings",
        "重置主题、透明度、屏幕占比、字号与语言设置" to "Reset theme, opacity, scale, font size and language",
        "冻结间隔" to "Freeze Interval",
        "使用声明与免责协议" to "Terms of Use & Disclaimer",
        "请完整阅读以下条款后决定是否继续使用" to "Please read the terms carefully before continuing",
        "一、工具性质与适用范围" to "1. Purpose and Intended Scope",
        "GGNext 为开源本地内存分析与调试工具，仅适用于程序开发测试、故障排查、安全研究及经明确授权的本地环境实验。任何功能均按通用内存读写机制提供，不针对任何特定商业软件、游戏或网络服务。" to "GGNext is an open-source local memory analysis and debugging tool intended solely for development testing, troubleshooting, security research, and authorized local environments. Its features provide general memory read/write operations and do not target any specific commercial software, game, or online service.",
        "二、严禁非法及侵权用途" to "2. Prohibited Uses",
        "严禁将本软件用于制作或分发游戏外挂、篡改联机数据、破坏计算机信息系统、绕过付费与版权保护、窃取敏感信息或任何违反当地法律法规及第三方服务协议的行为。如您所在地区法律限制此类工具的使用，请立即停止使用并卸载。" to "You must not use this software to create or distribute cheats, alter online data, compromise computer systems, bypass billing or copyright protections, extract sensitive data, or violate applicable laws or third-party terms of service. Discontinue use and uninstall immediately if restricted in your jurisdiction.",
        "三、使用者独立责任" to "3. Sole User Responsibility",
        "因使用本软件而产生的一切技术后果、数据损坏、账号封禁、设备异常及法律责任，均由使用者本人独立承担。软件作者及代码贡献者无法控制任何第三方的具体操作方式，亦不参与、不教唆任何违规用途。" to "You assume full responsibility for all technical outcomes, data corruption, account penalties, device issues, and legal liabilities arising from your use of this software. The authors and contributors cannot control third-party usage and neither participate in nor endorse unlawful activity.",
        "四、开源协议与无担保声明" to "4. License and No Warranty",
        "本软件依据 GNU General Public License v3.0 协议以现状形式开源分发，不附带任何明示或暗示的适用性、可靠性或无侵权担保。在适用法律允许的最大范围内，作者与贡献者不对因使用或无法使用本软件造成的任何直接、间接或附带损失承担赔偿责任。" to "This software is distributed AS IS under the GNU General Public License v3.0 without warranties of any kind, express or implied, including fitness for a particular purpose or non-infringement. To the maximum extent permitted by law, authors and contributors are not liable for any direct, indirect, or consequential damages.",
        "五、确认与生效" to "5. Acceptance",
        "点击「同意并继续」即表示您已充分阅读、理解并自愿遵守上述全部条款；若您不同意任何内容，请点击「不同意并退出」并立即移除本软件。" to "Tapping Agree & Continue confirms that you have read, understood, and agree to these terms. If you disagree, tap Decline & Exit and remove the software.",
        "不同意并退出" to "Decline & Exit",
        "同意并继续" to "Agree & Continue",
        "开源内存分析与调试工具，遵循 GNU General Public License v3.0 协议发布。" to "Open-source memory analysis and debugging tool licensed under GNU General Public License v3.0.",
        "项目地址" to "Repository",
        "获取源码、版本发布与问题反馈" to "Source code, releases and issue tracker",
        "参考来源" to "Reference",
        "界面布局与交互设计灵感源自 AGameGuardian 优秀的界面设计。" to "Interface layout and interaction design drew inspiration from AGameGuardian's clean UI.",
        "点击打开或复制" to "Tap to open or copy",
        "当前目标" to "Current Target",
        "悬浮球已在屏幕显示" to "Floating ball is active on screen",
        "点击上方按钮启动悬浮球" to "Tap the button above to start floating ball",
        "悬浮球服务运行中 · 短按打开面板 / 长按调节变速" to "Overlay active · Tap to open panel / Hold for speedhack",
        "新一代高性能内存调试工具" to "Next-gen high-performance memory debugger",
        "短按「G」悬浮球展开修改器（面板展开时悬浮球自动隐藏）；长按「G」悬浮球呼出左右变速调节栏" to "Tap 'G' ball to open panel (auto-hides while open); long-press 'G' ball for speedhack bar",
        "启动后将在屏幕生成可拖拽悬浮球，点击悬浮球进入修改器界面" to "Creates a draggable floating ball on screen; tap it to open the modifier",
        "运行环境与权限" to "Environment & Permissions",
        "悬浮窗权限" to "Overlay Permission",
        "已授予系统悬浮窗权限，支持跨应用显示" to "System overlay permission granted",
        "点击前往系统设置授予悬浮窗权限" to "Tap to grant overlay permission in system settings",
        "已就绪" to "Ready",
        "去授权" to "Grant",
        "工作模式" to "Working Mode",
        "沙盒模拟引擎（无 Root 亦可完整体验全部 UI 与搜索流程）" to "Sandbox simulation engine (full UI & search without Root)",
        "Root 直读模式 (/proc/<pid>/mem)" to "Root direct access (/proc/<pid>/mem)",
        "沙盒模式" to "Sandbox",
        "软件快捷入口" to "Quick Shortcuts",
        "主题与外观设置" to "Theme & Appearance",
        "语言 (Language)" to "Language",
        "使用帮助与语法指南" to "Help & Syntax Guide",
        "了解精确搜索、模糊搜索、联合搜索与批量修改用法" to "Learn exact, fuzzy, group search & batch editing",
        "关于软件" to "About App",
        "GGNext v0.5 · 查看软件信息与更新日志" to "GGNext v0.5 · App info & changelog",
        "管理软件语言、主题配色、背景透明度、页面屏幕占比与关于信息" to "Manage language, theme, opacity, screen ratio & about info",
        "语言与区域" to "Language & Region",
        "切换" to "Switch",
        "主题与外观" to "Theme & Appearance",
        "主题配色方案" to "Color Theme Preset",
        "深色模式" to "Dark Mode",
        "跟随系统" to "System",
        "深色" to "Dark",
        "浅色" to "Light",
        "玫瑰" to "Rose",
        "青翠" to "Green",
        "天青" to "Cyan",
        "蔚蓝" to "Blue",
        "紫罗兰" to "Violet",
        "琥珀" to "Amber",
        "赤红" to "Red",
        "中性" to "Mono",
        "Android 12+ 动态壁纸取色" to "Android 12+ Dynamic Wallpaper Color",
        "动态取色" to "Dynamic Color",
        "Android 12+ 从壁纸提取配色" to "Extract palette from wallpaper on Android 12+",
        "需要 Android 12 及以上" to "Requires Android 12 or above",
        "背景透明度" to "Background Opacity",
        "页面屏幕占比" to "Screen Ratio",
        "界面字体缩放" to "Font Scale",
        "弹窗圆角半径" to "Dialog Corner Radius",
        "伪弹窗圆角" to "Dialog Corner Radius",
        "悬浮球尺寸" to "Floating Ball Size",
        "列表入场与骨架屏动画" to "List Enter & Skeleton Animation",
        "关于与支持" to "About & Support",
        "关于 GGNext" to "About GGNext",
        "版本 v0.5 · 架构与环境信息" to "Version v0.5 · Architecture & environment",
        "更新日志" to "Changelog",
        "查看 v0.5 版本优化与修复详情" to "View v0.5 optimizations & bug fixes",
        "使用帮助" to "User Guide",
        "搜索语法与功能说明" to "Search syntax & feature reference",
        "软件运行日志" to "Runtime Log",
        "查看内部状态与调试日志" to "View internal state & debug logs",
        "恢复软件默认设置" to "Reset App Settings",
        "重置主题、透明度、屏幕占比、字号、语言等软件偏好设置" to "Reset theme, opacity, screen ratio, font size & language",
        "通用设置" to "General Settings",
        "通用与界面设置" to "General & UI Settings",
        "游戏保护设置" to "Game Protection Settings",
        "内存设置" to "Memory Settings",
        "操作与诊断" to "Actions & Diagnostics",
        "对游戏隐藏" to "Hide GG from the game",
        "对游戏隐藏 GG" to "Hide GG from the game",
        "关闭" to "Off",
        "1 (慢)" to "1 (slow)",
        "2 (慢)" to "2 (slow)",
        "3 (慢)" to "3 (slow)",
        "4 (慢)" to "4 (slow)",
        "用于 ptrace 保护的旁路模式" to "Bypass mode for ptrace protection",
        "恢复" to "Restore",
        "不处理" to "Nothing",
        "冻结" to "Freeze",
        "自动暂停游戏" to "Autopause game",
        "搜索助手" to "Search helper",
        "转到游戏" to "Go to game",
        "转到游戏并返回" to "Go to game and return",
        "在虚拟空间中使用 Root" to "Use root in virtual space",
        "运行守护" to "Prevent unload",
        "级别 1" to "Level 1",
        "级别 2" to "Level 2",
        "级别 3" to "Level 3",
        "反汇编/汇编引擎" to "Disassembler / assembler:",
        "使用 Capstone 反汇编，Keystone 汇编。" to "Uses Capstone disassembler and Keystone assembler.",
        "跳过内存区域" to "Skip memory areas",
        "否 (慢)" to "No (slow)",
        "空内存" to "Empty",
        "空内存 + Zygote" to "Empty + Zygote",
        "快速冻结" to "Fast freeze",
        "冻结间隔 (毫秒)" to "Freeze interval (ms)",
        "进程选择" to "Select process",
        "未选择进程，点击选择" to "No process selected, tap to select",
        "未选择进程 (点击选择)" to "No process selected (tap to select)",
        "内存区域" to "Memory ranges",
        "未选择" to "None",
        "搜索结果过滤器" to "Search result filter",
        "数值显示格式" to "Value format",
        "变速倍率设置" to "Speedhack settings",
        "工具栏顺序与增删" to "Toolbar order & buttons",
        "调整搜索列表 / 保存列表 / 内存页工具栏图标" to "Customize toolbar icons for Search / Saved / Memory tabs",
        "外观与字体缩放" to "Appearance & font scale",
        "清除输入历史" to "Clear input history",
        "运行日志" to "Runtime log",
        "查看引擎与附加记录" to "View engine & attach logs",
        "帮助文档" to "Help documentation",
        "查看搜索语法与功能说明" to "View search syntax & feature guide",
        "关于" to "About",
        "搜索列表" to "Search",
        "保存列表" to "Saved",
        "内存编辑器" to "Memory",
        "内存" to "Memory",
        "未选择进程。\n点击进程栏选择目标。" to "No process selected.\nTap the process bar to select a target.",
        "打开搜索面板开始。" to "Open the search panel to start.",
        "保存列表为空。\n在搜索列表中勾选后按「保存」加入。" to "Saved list is empty.\nCheck items in Search list and tap Save.",
        "内存编辑器\n使用工具栏「转到地址」加载内存视图。" to "Memory Editor\nUse 'Go to address' on the toolbar to load memory.",
        "上一页" to "Prev",
        "下一页" to "Next",
        "定址" to "Goto",
        "刷新" to "Refresh",
        "精确搜索" to "Exact Search",
        "支持单值、范围 (A~B)、联合 (A;B:512)、异或 (AXK) 与 HEX" to "Supports single, range (A~B), group (A;B:512), XOR & HEX",
        "更多" to "More",
        "收起" to "Less",
        "键盘" to "Kbd",
        "= 等于" to "= Equal",
        "≠ 不等于" to "≠ Not equal",
        "≥ 大于或等于" to "≥ Greater or equal",
        "≤ 小于或等于" to "≤ Less or equal",
        "地址蒙版" to "Address mask",
        "搜索范围：在全部内存中" to "Range: All memory",
        "搜索范围：仅在附近内存中" to "Range: Nearby memory only",
        "加密值搜索" to "Encrypted value",
        "改善搜索" to "Refine",
        "新搜索" to "New search",
        "模糊搜索" to "Fuzzy Search",
        "选择要采样的数值类型，点击下方「搜索」按钮进行初始内存采样：" to "Select the value type to sample and tap 'Search' below:",
        "取消" to "Cancel",
        "搜索" to "Search",
        "值是否发生了改变?" to "How has the value changed?",
        "无变化" to "Unchanged",
        "有变化" to "Changed",
        "增加了" to "Increased",
        "减少了" to "Decreased",
        "只有第N个结果: " to "Only N-th result: ",
        "最大显示记录: " to "Max shown records: ",
        "地址:  ≥" to "Address: ≥",
        "地址:  ≤" to "Address: ≤",
        "数值:  ≥" to "Value: ≥",
        "数值:  ≤" to "Value: ≤",
        "类型: " to "Type: ",
        "指针: " to "Pointer: ",
        "点击选择" to "Tap to pick",
        "重置" to "Reset",
        "应用" to "Apply",
        "完成" to "Done",
        "确定" to "OK",
        "保存" to "Save",
        "返回" to "Back",
        "单项修改" to "Edit Value",
        "批量修改" to "Batch Edit",
        "输入修改值…" to "Enter value…",
        "备注" to "Note",
        "描述（可选）" to "Description (optional)",
        "冻结并保存" to "Freeze & save",
        "加到值内" to "Add to value, do not replace",
        "可选增量步进值" to "Optional step increment",
        "值列表" to "Values",
        "修改" to "Yes",
        "选择数据类型" to "Select Data Type",
        "更改地址 / 类型" to "Change Address / Type",
        "地址" to "Address",
        "选择新类型" to "Select new type",
        "数值格式" to "Value Format",
        "已保存列表显示格式" to "Display format for saved list",
        "搜索列表与内存页显示格式" to "Display format for search list & memory",
        "默认" to "Default",
        "偏移量 & 异或计算" to "Offset & XOR Calculator",
        "长按任意计算出的偏移量、目标地址或异或值即可复制" to "Long-press any calculated offset, target address or XOR value to copy",
        "基址 (HEX)" to "Base (HEX)",
        "偏移 (HEX)" to "Offset (HEX)",
        "计算偏移:" to "Offset:",
        "目标地址:" to "Target:",
        "异或结果:" to "XOR:",
        "已勾选地址两两偏移 & 异或:" to "Checked addresses pairwise Offset & XOR:",
        "复制偏移" to "Copy Offset",
        "跳转到目标地址" to "Go to Target",
        "移除" to "Remove",
        "转到地址" to "Go to Address",
        "输入十六进制内存地址定位并高亮目标地址" to "Enter hex memory address to locate and highlight",
        "目标地址 (HEX)" to "Target (HEX)",
        "转到" to "Go to",
        "执行脚本" to "Execute Script",
        "从外部存储执行 Lua 脚本。" to "Execute Lua script from storage.",
        "输入可读取的本地脚本路径。" to "Enter a readable local script path.",
        "最近执行的脚本:" to "Recent scripts:",
        "浏览" to "Browse",
        "运行" to "Execute",
        "浏览并选择文件夹" to "Select Folder",
        "文件浏览器" to "File Browser",
        "上一级" to "Up",
        "新建目录" to "New Folder",
        "根目录 /" to "Root /",
        "搜索文件名或后缀…" to "Search file name or ext…",
        "全部" to "All",
        "选择当前文件夹" to "Select This Folder",
        "复制内存" to "Copy Memory",
        "复制起始地址与结束地址之间区域的内存数据" to "Copy memory between start address and end address",
        "起始地址" to "From",
        "结束地址" to "To",
        "结束地址必须大于或等于起始地址" to "End address must be >= start address",
        "复制" to "Copy",
        "导出内存" to "Dump Memory",
        "将起始地址与结束地址之间的内存区域导出到文件" to "Export memory region between start and end address to file",
        "路径" to "Path",
        "导出" to "Dump",
        "分配内存页" to "Allocate Memory Page",
        "也许可读" to "May read",
        "也许可写" to "May write",
        "也许可执行" to "May execute",
        "地址:" to "Address:",
        "保存/载入列表文件" to "Save / Load List File",
        "导出选定项到文件" to "Export Selected to File",
        "输入文件路径或点击右侧「浏览」从文件浏览器中选择" to "Enter file path or tap 'Browse' to pick from file browser",
        "游戏变速" to "Speedhack",
        "长按悬浮球也可随时呼出左右加减速控制栏" to "Long-press the floating ball anytime to toggle the speedhack bar",
        "选择语言 (Language)" to "Select Language",
        "输入历史" to "Input History",
        "无历史记录" to "No history",
        "清空" to "Clear",
        "暂无日志" to "No logs yet",
        "悬浮窗管理" to "Floating Window",
        "短按悬浮球打开面板，长按悬浮球调节变速" to "Tap floating ball to open panel, long-press for speedhack",
        "关闭悬浮窗" to "Stop Overlay",
        "进程列表" to "Process List",
        "选择要附加的进程" to "Select a process to attach",
        "搜索名称 / 包名 / pid" to "Search name / package / pid",
        "用户" to "User",
        "应用" to "Apply",
        "系统" to "System",
        "优先级" to "Order",
        "大小" to "Size",
        "选择内存范围" to "Select Memory Ranges",
        "未选择进程" to "No process selected",
        "工具栏顺序" to "Toolbar Order",
        "长按行上下拖动排序 / 下方可增删工具" to "Long-press and drag up/down to reorder / add or remove below",
        "增删工具" to "Add/Remove",
        "点击勾选或移除该页面的工具栏图标" to "Tap to enable or disable toolbar icons for this tab",
        "返回排序" to "Back to Order",
        "外观" to "Appearance",
        "主题配色、深浅色模式、背景透明度与屏幕占比" to "Theme color, dark/light mode, opacity & screen ratio",
        "主题色" to "Theme Color",
        "地址蒙版搜索" to "Address Mask Search",
        "支持使用 ? 通配符匹配特定偏移规律的内存地址" to "Match addresses by bitmask or '?' wildcards",
        "蒙版:" to "Mask:",
        "改善结果" to "Refine",
        "新蒙版搜索" to "New Mask Search",
        "指针搜索" to "Pointer Search",
        "查找指向目标地址及最大偏移范围内的指针链" to "Find pointers to target address within max offset",
        "最大偏移:" to "Max offset:",
        "开始指针搜索" to "Search Pointers",
        "偏移量计算器" to "Offset calculator",
        "计算偏移、异或" to "Calculate offset, XOR",
        "搜索附近内存" to "Search nearby memory",
        "分配内存页" to "Allocate memory page",
        "工作模式" to "Working Mode",
        "Root 模式" to "Root",
        "免 Root 模式" to "No-Root",
        "已授权 Root" to "Root",
        "未授权" to "Unrooted",
        "共享 UID" to "Shared UID",
        "附近" to "Nearby",
        "附近:" to "Nearby:",
        "地址:" to "Address:",
        "之前" to "Before",
        "后" to "After",
        "距离:" to "Distance:",
        "偏移量:" to "Offset:",
        "结果:" to "Result:",
        "转到" to "Goto",
        "复制" to "Copy",
        "终止此进程" to "Kill Target Process",
        "退出" to "Exit GGNext",
        "最近执行的脚本:" to "Recent Scripts:"
    )

    fun tr(text: String): String {
        if (text.isEmpty()) return text
        return when (currentLang) {
            "1", "2" -> toEnglish(text)
            else -> text
        }
    }

    private fun toEnglish(text: String): String {
        enMap[text]?.let { return it }
        when {
            text.startsWith("已选 ") -> return "Selected " + text.removePrefix("已选 ")
            text.startsWith("修改选中 ") -> return "Edit " + text.removePrefix("修改选中 ") + " selected"
            text.startsWith("当前：") -> return "Current: " + toEnglish(text.removePrefix("当前："))
            text.startsWith("当前预设：") -> return "Preset: " + text.removePrefix("当前预设：")
            text.startsWith("当前主题：") -> return "Theme: " + text.removePrefix("当前主题：")
            text.startsWith("当前共有 ") -> return text.replace("当前共有 ", "Total ").replace(" 条输入记录", " history records")
            text.startsWith("最大显示 ") -> return text.replace("最大显示 ", "Max ").replace(" 条 · 地址/数值/类型筛选", " · Address/Value/Type filter")
            text.startsWith("搜索范围：指定地址区间") -> return text.replace("搜索范围：指定地址区间", "Range: Custom")
            text.startsWith("值列表 [") -> return text.replace("值列表 [", "Value Action [")
            text.startsWith("编辑此地址数值") -> return text.replace("编辑此地址数值", "Edit value")
            text.startsWith("转到此地址：") -> return text.replace("转到此地址：", "Go to address: ")
            text.startsWith("跳转到指针地址：") -> return text.replace("跳转到指针地址：", "Go to pointer: ")
            text.startsWith("复制地址：") -> return text.replace("复制地址：", "Copy address: ")
            text.startsWith("复制十进制值：") -> return text.replace("复制十进制值：", "Copy decimal: ")
            text.startsWith("复制十六进制：") -> return text.replace("复制十六进制：", "Copy HEX: ")
            text.startsWith("复制反向十六进制：") -> return text.replace("复制反向十六进制：", "Copy reverse HEX: ")
            text.startsWith("恢复并移除所选") -> return text.replace("恢复并移除所选", "Revert & remove selected")
            text.startsWith("移除所选") -> return text.replace("移除所选", "Remove selected")
            text == "移除全部列表项" -> return "Remove all items"
            text.startsWith("文件夹 · ") -> return text.replace("文件夹 · ", "Folder · ").replace(" 项", " items")
            text.startsWith("选中区域: ") -> return text.replace("选中区域: ", "Region: ").replace("共 ", "").replace(" 字节", " bytes")
            text.startsWith("导出区间: ") -> return text.replace("导出区间: ", "Dump: ").replace(" 字节", " bytes")
            text.startsWith("最终合成蒙版: ") -> return text.replace("最终合成蒙版: ", "Resultant mask: ")
        }
        return text
    }
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
    var wasFocused by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val currentVal by androidx.compose.runtime.rememberUpdatedState(value)
    val currentOnValueChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)

    if (initialFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                if (suppressSystemKeyboard) {
                    awaitCancellation()
                } else {
                    nextHandler.startInputMethod(request)
                }
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
                                            showContextMenu = true
                                        },
                                        onTap = { tapOffset ->
                                            focusRequester.requestFocus()
                                            onFocus?.invoke()
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
 * 其他改进 1：列表右侧快速滑动条 (VerticalFastScrollbar)
 * - 颜色跟随当前主题色 (Rose.Primary)
 * - 视觉滑块纤细精致 (常态 3.5.dp，按住拖拽时平滑加宽至 5.5.dp)，右侧触控响应宽度 18.dp（兼顾易按住与不遮挡列表按钮）
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

    val scrollFraction = if (isDragging) {
        dragFraction
    } else {
        val firstIndex = listState.firstVisibleItemIndex.toFloat()
        val maxIndex = (totalItems - visibleItems).coerceAtLeast(1).toFloat()
        (firstIndex / maxIndex).coerceIn(0f, 1f)
    }

    val thumbAlpha by animateFloatAsState(
        targetValue = when {
            isDragging -> 0.95f
            listState.isScrollInProgress -> 0.80f
            else -> 0.42f
        },
        animationSpec = tween(180),
        label = "sbAlpha"
    )
    val thumbWidthDp by animateFloatAsState(
        targetValue = if (isDragging) 5.5f else 3.5f,
        animationSpec = tween(150),
        label = "sbWidth"
    )
    val primaryColor = Rose.Primary
    val trackColor = Rose.Divider.copy(alpha = if (isDragging) 0.32f else 0.12f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(18.dp)
            .pointerInput(totalItems, visibleItems) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val change = down.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            isDragging = true
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val frac = (change.position.y / h).coerceIn(0f, 1f)
                            dragFraction = frac
                            val targetIdx = (frac * (totalItems - visibleItems).coerceAtLeast(1)).toInt().coerceIn(0, totalItems - 1)
                            scope.launch { listState.scrollToItem(targetIdx) }
                            change.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val dragChange = event.changes.firstOrNull() ?: break
                                if (!dragChange.pressed) {
                                    isDragging = false
                                    break
                                }
                                val nextFrac = (dragChange.position.y / h).coerceIn(0f, 1f)
                                dragFraction = nextFrac
                                val nextIdx = (nextFrac * (totalItems - visibleItems).coerceAtLeast(1)).toInt().coerceIn(0, totalItems - 1)
                                scope.launch { listState.scrollToItem(nextIdx) }
                                dragChange.consume()
                            }
                            isDragging = false
                        }
                    }
                }
            }
    ) {
        val density = LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val thumbRatio = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.08f, 0.55f)
        val thumbHeightPx = (trackHeightPx * thumbRatio).coerceAtLeast(with(density) { 28.dp.toPx() })
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
