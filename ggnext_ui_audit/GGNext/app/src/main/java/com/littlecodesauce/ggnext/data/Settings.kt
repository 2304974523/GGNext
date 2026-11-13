package com.littlecodesauce.ggnext.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.model.ValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("gg_settings")

/**
 * 设置项定义。id 与枚举一一对应，界面按分组渲染，值持久化到 DataStore。
 */
data class SettingSpec(
    val id: String,
    val title: String,
    val group: String,
    val kind: Kind,
    val default: Any,
    val choices: List<Pair<Int, String>> = emptyList(),
    val min: Long = 0,
    val max: Long = 100,
    val help: String = ""
) {
    enum class Kind { SWITCH, CHOICE, NUMBER, ACTION, TEXT }
}

object SettingKeys {
    const val GROUP_ACTION = "操作"
    const val GROUP_GAME = "游戏设置"
    const val GROUP_GLOBAL = "通用设置"
    const val GROUP_UI = "界面设置"
    const val GROUP_OTHER = "其余选项"
}

object SettingsSpecs {

    val valueFormatChoices: List<Pair<Int, String>> = listOf(
        Formatter.F_HEX to "HEX",
        Formatter.F_DWORD to "Dword",
        Formatter.F_FLOAT to "Float",
        Formatter.F_DOUBLE to "Double",
        Formatter.F_WORD to "Word",
        Formatter.F_BYTE to "Byte",
        Formatter.F_QWORD to "Qword",
        Formatter.F_XOR to "Xor",
        Formatter.F_RHEX to "HEX 逆序",
        Formatter.F_STRING to "字符串",
        Formatter.F_JAVA to "Java 字符串",
        Formatter.F_ARM to "ARM x32",
        Formatter.F_THUMB to "Thumb",
        Formatter.F_ARM8 to "ARM x64"
    )

    val game = listOf(
        SettingSpec("ranges", "选择内存范围", SettingKeys.GROUP_GAME, SettingSpec.Kind.ACTION, 0,
            help = "选择需要搜索的内存区域，如 Jh(Java 堆)、Ch(C++ 堆)、Xa(可执行) 等。"),
        SettingSpec("speedhack_intercept", "变速：拦截计时器", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("speedhack_functions", "变速：函数", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "全部", 1 to "仅时间函数")),
        SettingSpec("time_jump_panel", "显示时间跳跃面板", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("unrandomizer_intercept", "反随机数：拦截", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("unrandomizer_functions", "反随机数：函数", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "全部", 1 to "rand", 2 to "random", 3 to "drand48")),
        SettingSpec("hide_from_game", "对游戏隐藏 GG", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "关闭", 1 to "1 (慢)", 2 to "2 (慢)", 3 to "3 (慢)", 4 to "4 (慢)"),
            help = "在游戏进程里隐藏本应用痕迹，级别越高越隐蔽但可能影响稳定性。"),
        SettingSpec("ptrace_bypass", "用于 ptrace 保护的旁路模式", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "恢复", 1 to "不处理", 2 to "冻结", 3 to "关闭")),
        SettingSpec("skip_memory", "跳过内存区域", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 2,
            listOf(0 to "否 (慢)", 1 to "空内存", 2 to "空内存 + Zygote")),
        SettingSpec("fast_freeze", "快速冻结", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是"),
            help = "比普通冻结更快，但并非对所有内存都可用。"),
        SettingSpec("freeze_interval", "冻结间隔 (微秒)", SettingKeys.GROUP_GAME, SettingSpec.Kind.NUMBER, 33000L, min = 0, max = 600000000L)
    )

    val global = listOf(
        SettingSpec("autopause", "自动暂停游戏", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("search_helper", "搜索助手", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 2,
            listOf(0 to "关闭", 1 to "转到游戏", 2 to "转到游戏并返回")),
        SettingSpec("freeze_interval_saved", "已保存列表刷新间隔 (毫秒)", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.NUMBER, 1000L, min = 100, max = 600000L),
        SettingSpec("speedhack_speeds", "变速倍率", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.ACTION, 0),
        SettingSpec("speedhack_params", "变速参数", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 2,
            listOf(0 to "不处理", 1 to "排序", 2 to "排序并去重")),
        SettingSpec("reset_on_exit", "退出时重置", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("check_libs", "检测游戏库架构", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("data_in_ram", "使用内存缓冲", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "512 MB", 2 to "1 GB", 3 to "2 GB")),
        SettingSpec("memory_access", "内存访问", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "正常", 1 to "扩展", 2 to "实验性")),
        SettingSpec("deep_read", "深度读取", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是 (慢)")),
        SettingSpec("calls", "系统调用模式", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "正常", 1 to "替代")),
        SettingSpec("waitpid", "waitpid 模式", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "正常", 1 to "替代")),
        SettingSpec("temp_path", "临时文件路径", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.TEXT, "/data/local/tmp/gg96"),
        SettingSpec("su_command", "超级命令", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.TEXT, "su"),
        SettingSpec("vspace_root", "在虚拟空间中使用 Root", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("prevent_unload", "运行守护", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "关闭", 1 to "级别 1", 2 to "级别 2", 3 to "级别 3")),
        SettingSpec("disasm_engine", "反汇编/汇编引擎", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "GG101", 1 to "Cap+Key"))
    )

    // 语言仅保留简体中文 (0) 和 English (1)
    val ui = listOf(
        SettingSpec("use_notification", "使用通知", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("hot_key", "热键", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "关闭", 1 to "音量 -", 2 to "音量 +", 3 to "音量 - 与 +")),
        SettingSpec("history_limit", "历史记录条数限制", SettingKeys.GROUP_UI, SettingSpec.Kind.NUMBER, 500L, min = 0, max = 100000L),
        SettingSpec("keyboard", "键盘", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "外部", 1 to "内置")),
        SettingSpec("allow_suggestions", "允许输入法提示", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("ignore_unknown_chars", "忽略未知字符", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("indent", "状态栏缩进", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("visible_type", "在内存编辑器中使用可见数据类型", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("small_items", "小列表项目", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("backgrounds", "黑色背景", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("toolbars", "工具栏", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "隐藏", 1 to "显示")),
        SettingSpec("toolbar_buttons", "工具栏按钮", SettingKeys.GROUP_UI, SettingSpec.Kind.ACTION, 0),
        SettingSpec("fill_toolbar", "填充工具栏", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 2,
            listOf(0 to "从左", 1 to "居中", 2 to "从右")),
        SettingSpec("icons_size", "悬浮窗大小", SettingKeys.GROUP_UI, SettingSpec.Kind.NUMBER, 48L, min = 12, max = 96),
        SettingSpec("icon_appearance", "悬浮窗外观", SettingKeys.GROUP_UI, SettingSpec.Kind.ACTION, 0),
        SettingSpec("hide_icons", "隐藏启动器图标", SettingKeys.GROUP_UI, SettingSpec.Kind.ACTION, 0),
        SettingSpec("acceleration", "界面加速", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "软件", 1 to "硬件")),
        SettingSpec("sound_effects", "启用音效", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 1,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("language", "语言 (Language)", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "简体中文", 1 to "English")),
        SettingSpec("number_locale", "格式化数字的区域设置", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "跟随系统", 1 to "en-US", 2 to "ru-RU", 3 to "de-DE")),
        SettingSpec("theme_preset", "主题配色", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "ROSE"),
        SettingSpec("dark_mode", "深色模式", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "DARK"),
        SettingSpec("dynamic_color", "动态壁纸取色", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "0"),
        SettingSpec("font_scale", "界面字体缩放", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "0.88"),
        SettingSpec("dialog_corner", "弹窗圆角", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "12"),
        SettingSpec("bg_opacity", "背景透明度", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "0.96"),
        SettingSpec("panel_scale", "页面屏幕占比", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "1.00"),
        SettingSpec("skeleton", "列表动画", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "1")
    )

    val other = listOf(
        SettingSpec("disable_protection", "关闭所有程序的保护 (直到重启)", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("clear_history", "清除输入历史", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("reset_ignore", "重置全部对话忽略选择", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("last_stats", "上次搜索的统计信息", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("show_logcat", "显示日志", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("region_log", "写入区域日志", SettingKeys.GROUP_OTHER, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("screenshot", "截图", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("about", "关于", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("changelog", "更新日志", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0)
    )

    val all: List<SettingSpec> = game + global + ui + other

    fun byId(id: String): SettingSpec? = all.firstOrNull { it.id == id }

    // 界面调整 4：通用设置放在最上方
    fun grouped(): List<Pair<String, List<SettingSpec>>> = listOf(
        SettingKeys.GROUP_GLOBAL to global,
        SettingKeys.GROUP_UI to ui,
        SettingKeys.GROUP_GAME to game,
        SettingKeys.GROUP_ACTION to listOf(
            SettingSpec("help", "帮助", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0),
            SettingSpec("donate", "捐赠", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0),
            SettingSpec("reset_config", "重置设置", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0),
            SettingSpec("exit", "退出", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0),
            SettingSpec("kill_game", "终止此游戏", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0)
        ),
        SettingKeys.GROUP_OTHER to other
    )

    /** 默认显示掩码 */
    const val DEFAULT_FORMAT_MASK: Int = Formatter.F_HEX or Formatter.F_DWORD or Formatter.F_FLOAT
}

/** 运行期配置快照 */
data class RuntimeConfig(
    val valueFormatMask: Int = SettingsSpecs.DEFAULT_FORMAT_MASK,
    val savedFormatMask: Int = SettingsSpecs.DEFAULT_FORMAT_MASK,
    val searchType: Int = ValueType.AUTO,
    val searchSign: Int = ValueType.SIGN_EQUAL,
    val memoryFrom: Long = 0L,
    val memoryTo: Long = Long.MAX_VALUE,
    val rangeMode: Int = 0,
    val fuzzyEqualCount: Int = 1,
    val filterEnabled: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object K {
        val valueFormatMask = intPreferencesKey("value_format_mask")
        val savedFormatMask = intPreferencesKey("saved_format_mask")
        val rangeMode = intPreferencesKey("range_mode")
        val memoryFrom = longPreferencesKey("memory_from")
        val memoryTo = longPreferencesKey("memory_to")
        val fuzzyEqual = intPreferencesKey("fuzzy_equal")
        val toolbarButtons = intPreferencesKey("toolbar_buttons")
        val hotPointX = intPreferencesKey("hot_x")
        val hotPointY = intPreferencesKey("hot_y")
        val lastPid = intPreferencesKey("last_pid")
        val lastPackage = stringPreferencesKey("last_package")
        val speed = longPreferencesKey("speed_value")
        fun key(id: String) = stringPreferencesKey("cfg_$id")
    }

    val flow: Flow<Preferences> = context.dataStore.data

    fun valueFormatMask(): Flow<Int> = flow.map { it[K.valueFormatMask] ?: SettingsSpecs.DEFAULT_FORMAT_MASK }
    fun savedFormatMask(): Flow<Int> = flow.map { it[K.savedFormatMask] ?: SettingsSpecs.DEFAULT_FORMAT_MASK }

    fun settingValue(id: String): Flow<String?> = flow.map { it[K.key(id)] }

    suspend fun setInt(id: String, value: Int) {
        context.dataStore.edit { K.key(id).let { k -> it[k] = value.toString() } }
    }

    suspend fun setString(id: String, value: String) {
        context.dataStore.edit { it[K.key(id)] = value }
    }

    suspend fun setValueFormatMask(mask: Int) {
        context.dataStore.edit { it[K.valueFormatMask] = mask }
    }

    suspend fun setSavedFormatMask(mask: Int) {
        context.dataStore.edit { it[K.savedFormatMask] = mask }
    }

    suspend fun saveTarget(pid: Int, pkg: String) {
        context.dataStore.edit {
            it[K.lastPid] = pid
            it[K.lastPackage] = pkg
        }
    }

    suspend fun saveHotPoint(x: Int, y: Int) {
        context.dataStore.edit {
            it[K.hotPointX] = x
            it[K.hotPointY] = y
        }
    }

    fun settingsSnapshot(): Flow<Map<String, String>> = flow.map { prefs ->
        val out = LinkedHashMap<String, String>()
        SettingsSpecs.all.forEach { spec ->
            out[spec.id] = prefs[K.key(spec.id)] ?: spec.default.toString()
        }
        prefs.asMap().forEach { (prefKey, prefVal) ->
            val name = prefKey.name
            if (name.startsWith("cfg_")) {
                out[name.removePrefix("cfg_")] = prefVal.toString()
            }
        }
        out
    }

    companion object {
        fun valueOf(prefs: Map<String, String>, spec: SettingSpec): String {
            return prefs[spec.id] ?: spec.default.toString()
        }

        fun intOf(prefs: Map<String, String>, spec: SettingSpec): Int =
            valueOf(prefs, spec).toIntOrNull() ?: (spec.default as? Int ?: 0)

        fun longOf(prefs: Map<String, String>, spec: SettingSpec): Long =
            valueOf(prefs, spec).toLongOrNull() ?: (spec.default as? Long ?: 0L)
    }
}
