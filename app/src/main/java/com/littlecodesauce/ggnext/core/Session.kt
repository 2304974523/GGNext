package com.littlecodesauce.ggnext.core

import android.app.Application
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.data.SearchHistory
import com.littlecodesauce.ggnext.data.SettingsRepository
import com.littlecodesauce.ggnext.data.SettingsSpecs
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.engine.MemoryEngine
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.MemoryRegion
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.model.DEFAULT_PINNED_TOOLS
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_MEMORY
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_SAVED
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_SEARCH
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import com.littlecodesauce.ggnext.ui.theme.UiMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** 四个导航页 */
enum class NextTab(val label: String) {
    SETTINGS("设置"),
    SEARCH_LIST("搜索列表"),
    SAVED_LIST("保存列表"),
    MEMORY("内存")
}

/** 弹窗枚举 */

/** 工作模式枚举：Root 模式 与 免 Root 模式（共享 UID / 虚拟空间沙盒） */
enum class WorkMode(val code: String, val title: String, val desc: String) {
    ROOT("root", "Root 模式", "通过 Root 权限进行全系统跨进程读写（支持所有已运行应用与游戏）"),
    NO_ROOT("no_root", "免 Root 模式", "针对虚拟空间/双开沙箱（所有应用共享相同 UID），无需 Root 跨进程读写")
}

enum class Sheet {
    NONE,
    PROCESS,
    SEARCH,
    FUZZY,
    MASK_SEARCH,
    POINTER_SEARCH,
    FILTER,
    REGION,
    VALUE_FORMAT,
    EDITOR,
    OFFSET,
    CALC_OFFSET,
    REMOVE,
    TOOLBAR_ORDER,
    TOOLBAR_PICK,
    GOTO,
    MODULE_LIST,
    SCRIPT,
    FILE_BROWSER,
    SPEED,
    HISTORY,
    FUNCTION,
    LOG,
    OVERLAY,
    HELP,
    ABOUT,
    CHANGELOG,
    LANGUAGE,
    APPEARANCE,
    CONTEXT,
    TYPE_PICK,
    ADDRESS_EDIT,
    FILE_MANAGER,
    COPY_MEMORY,
    DUMP,
    ALLOCATE
}

/** 改进5：文件浏览器打开参数与回调目标 */
enum class FileBrowserTarget {
    SCRIPT,
    FILE_MANAGER,
    DUMP
}

data class FileBrowserConfig(
    val currentDir: String = "/sdcard/Download",
    val selectDirectory: Boolean = false,
    val returnSheet: Sheet = Sheet.SCRIPT,
    val target: FileBrowserTarget = FileBrowserTarget.SCRIPT
)

enum class SearchMode { EXACT, FUZZY, MASK, POINTER, DIFF, RANGE }

/** 搜索面板输入 */
data class SearchInput(
    val text: String = "",
    val type: Int = ValueType.DWORD,
    val sign: Int = ValueType.SIGN_EQUAL,
    val mask: String = "FFFFFFFFFFFFFFFF",
    val offset: String = "0",
    val offsetHex: Boolean = true,
    val rangeMode: Int = 0, // 0=全部内存, 1=指定地址区间, 2=附近
    val rangeFrom: String = "00000000",
    val rangeTo: String = "FFFFFFFFFFFFFFFF",
    val nearbyAddress: String = "",
    val nearbyBefore: Boolean = true,
    val nearbyAfter: Boolean = true,
    val nearbyDistance: String = "500",
    val pointerType: Int = ValueType.QWORD,
    val encrypted: Boolean = false,
    val expanded: Boolean = false,
    val keyboardVisible: Boolean = true
)

/** 模糊搜索状态 */
data class FuzzyState(
    val active: Boolean = false,
    val type: Int = ValueType.DWORD,
    val rangeMode: Int = 0, // 0=全部内存, 1=指定地址区间, 2=附近
    val rangeFrom: String = "00000000",
    val rangeTo: String = "FFFFFFFFFFFFFFFF",
    val nearbyAddress: String = "",
    val nearbyBefore: Boolean = true,
    val nearbyAfter: Boolean = true,
    val nearbyDistance: String = "500",
    val extended: Boolean = false,
    val deltaText: String = "",
    val onlyNth: String = "",
    val keyboardVisible: Boolean = true
) {
    val rangeLabel: String
        get() = when (rangeMode) {
            1 -> "指定地址区间"
            2 -> "附近:"
            else -> "全部内存"
        }
}

/** 结果过滤/筛选状态 */
data class FilterState(
    val maxShow: Int = 2000,
    val addrGreatEnabled: Boolean = false,
    val addrGreat: String = "",
    val addrLessEnabled: Boolean = false,
    val addrLess: String = "",
    val valGreatEnabled: Boolean = false,
    val valGreat: String = "",
    val valLessEnabled: Boolean = false,
    val valLess: String = "",
    val typeEnabled: Boolean = false,
    val types: Set<Int> = emptySet(),
    val fractionalEnabled: Boolean = false,
    val fractionalNotEqual: Boolean = false,
    val fractional: String = "",
    val pointerEnabled: Boolean = false,
    val pointers: Set<Int> = emptySet(), // 4="-", 8="RO", 16="W", 2="X", 1="WX"
    val formatHex: Boolean = false,
    val formatRHex: Boolean = false,
    val formatString: Boolean = false,
    val formatUtf16: Boolean = false,
    val formatArm32: Boolean = false,
    val formatThumb: Boolean = false,
    val formatArm64: Boolean = false
) {
    val isAnyFilterActive: Boolean
        get() = addrGreatEnabled || addrLessEnabled || valGreatEnabled || valLessEnabled ||
            typeEnabled || fractionalEnabled || pointerEnabled
}

/** 内存区域汇总行 */
data class RegionRow(
    val code: String,
    val name: String,
    val size: Long,
    val count: Int,
    val selected: Boolean = false
)

data class Ui(
    val tab: NextTab = NextTab.SEARCH_LIST,
    val rawResults: List<MemoryItem> = emptyList(),
    val results: List<MemoryItem> = emptyList(),
    val saved: List<MemoryItem> = emptyList(),
    val memoryRows: List<MemoryItem> = emptyList(),
    val memoryBase: Long = 0L,
    /** 改进7 & 改进9：跳转到地址后的专属高亮目标地址与滚动触发计数器 */
    val memoryHighlightAddress: Long? = null,
    val memoryScrollTrigger: Int = 0,
    val memoryTop: Long = 0L,
    val memoryOffsetShift: Int = 0,
    val memoryHistoryBack: List<Long> = emptyList(),
    val memoryHistoryForward: List<Long> = emptyList(),

    val processes: List<TargetProcess> = emptyList(),
    val processFilter: Int = 0, // 0=全部, 1=用户, 2=系统
    val processSortBySize: Boolean = true, // "大小"可多选排序按钮（可与全部/用户/系统同时选中）
    val processLoading: Boolean = true,
    val target: TargetProcess? = null,
    val processPaused: Boolean = false,

    val regions: List<RegionRow> = emptyList(),
    val selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,

    val busy: Boolean = false,
    val busyText: String = "",
    val searchProgress: Float = 0f,
    val searchFoundSoFar: Long = 0L,
    val searchEtaSeconds: Int = -1,
    val searchScannedMb: Float = 0f,
    val searchTotalMb: Float = 0f,

    val sheet: Sheet = Sheet.NONE,
    val search: SearchInput = SearchInput(),
    val fuzzy: FuzzyState = FuzzyState(),
    val filter: FilterState = FilterState(),

    /** 改进5：脚本路径、文件管理路径、导出目录及文件浏览器状态 */
    val scriptPath: String = "/sdcard/Download/script.lua",
    val scriptHistory: List<String> = listOf(
        "/sdcard/Download/script.lua",
        "/sdcard/GGNext/scripts/main.lua",
        "/sdcard/GGNext/scripts/pointer_scan.lua"
    ),
    val fileManagerPath: String = "/sdcard/GGNext/saved_list.txt",
    val dumpDirPath: String = "/sdcard/GGNext/dump",
    val fileBrowser: FileBrowserConfig = FileBrowserConfig(),

    val valueMask: Int = SettingsSpecs.DEFAULT_FORMAT_MASK,
    val valueMaskSaved: Int = SettingsSpecs.DEFAULT_FORMAT_MASK,
    val searchType: Int = ValueType.DWORD,

    val settings: Map<String, String> = emptyMap(),
    val settingsLoaded: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val metrics: UiMetrics = UiMetrics(),
    val preset: ThemePreset = ThemePreset.ROSE,
    val darkMode: DarkMode = DarkMode.DARK,
    val dynamicColor: Boolean = false,

    val tools: Map<NextTab, List<ToolId>> = emptyMap(),
    val pinnedTools: Map<NextTab, List<ToolId>> = emptyMap(),
    val selectedCount: Int = 0,
    val totalCount: Long = 0,
    val hintVisible: Boolean = true,
    val toast: String? = null,
    val workMode: WorkMode = WorkMode.ROOT,
    val hasRootPermission: Boolean = false,
    val attachedOk: Boolean = false,
    val editing: MemoryItem? = null,
    val editSingleOnly: Boolean = false,
    val lastBatchEditValue: String = "",
    val lastBatchEditStep: String = "",
    val contextItem: MemoryItem? = null,
    val history: List<String> = emptyList(),
    val gotoHistory: List<String> = emptyList(),
    val floatingBallVisible: Boolean = false,
    val panelVisible: Boolean = false,
    val speedBarExpanded: Boolean = false,
    val speedMultiplier: Float = 1.0f,
    val homeSettingsOpen: Boolean = false
) {
    val currentMask: Int get() = if (tab == NextTab.SAVED_LIST) valueMaskSaved else valueMask
    val currentPinnedTools: List<ToolId> get() = pinnedTools[tab] ?: DEFAULT_PINNED_TOOLS
    val flowingTools: List<ToolId> get() {
        val base = tools[tab] ?: when (tab) {
            NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
            NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
            NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
            NextTab.SETTINGS -> emptyList()
        }
        val pinned = currentPinnedTools.toSet()
        return base.filter { it !in pinned }
    }
    val searchToolList: List<ToolId> get() = flowingTools

    /** 3.4 同步显示为 (显示数量/本轮搜索总数量) */
    val countButtonText: String
        get() {
            val total = maxOf(totalCount, rawResults.size.toLong(), results.size.toLong())
            return "(${results.size}/$total)"
        }

    val speedText: String
        get() = if (speedMultiplier >= 10f || speedMultiplier == speedMultiplier.toInt().toFloat()) {
            String.format(Locale.US, "%.1fx", speedMultiplier)
        } else {
            String.format(Locale.US, "%.2fx", speedMultiplier).trimEnd('0').let {
                if (it.endsWith(".")) "${it}0x" else "${it}x"
            }
        }
}

/**
 * 全局会话：主界面与悬浮窗共享同一份数据与引擎。
 */
object Session {

    /** 单内存页大小设定为 4KB（4096 字节 = 1024 行 Dword） */
    const val MEMORY_PAGE_BYTES = 4096
    const val MEMORY_PAGE_ROWS = MEMORY_PAGE_BYTES / 4

    private val SPEED_STEPS = floatArrayOf(
        0.05f, 0.1f, 0.2f, 0.25f, 0.33f, 0.5f, 0.75f,
        1.0f,
        1.25f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f, 8.0f, 10.0f, 15.0f, 20.0f, 30.0f, 50.0f, 100.0f
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var repo: SettingsRepository? = null
    private var appContext: Application? = null
    internal val engine = MemoryEngine()
    private val historyStore = SearchHistory()
    @Volatile
    private var hasFuzzySession: Boolean = false
    @Volatile
    private var liveRefreshRunning: Boolean = false
    @Volatile
    private var frozenItemsSnapshot: List<MemoryItem> = emptyList()
    private val attachMutex = Mutex()

    /** Bug 2.1：记录各列表（search_list / saved_list / memory_list / settings_list）的滚动位置 */
    private val listScrollPositions = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    fun getListScrollPosition(key: String): Pair<Int, Int> =
        listScrollPositions[key] ?: (0 to 0)

    fun saveListScrollPosition(key: String, index: Int, offset: Int) {
        listScrollPositions[key] = index.coerceAtLeast(0) to offset.coerceAtLeast(0)
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private fun buildMetrics(map: Map<String, String>): UiMetrics = UiMetrics(
        fontScale = (map["font_scale"]?.toFloatOrNull() ?: 0.88f).coerceIn(0.7f, 1.3f),
        dialogCorner = (map["dialog_corner"]?.toIntOrNull() ?: 12).coerceIn(0, 28),
        sheetBlur = map["sheet_blur"]?.toIntOrNull() ?: 110,
        iconBlur = map["icon_blur"]?.toIntOrNull() ?: 139,
        skeletonLoading = map["skeleton"] != "0",
        bgOpacity = (map["bg_opacity"]?.toFloatOrNull() ?: 0.96f).coerceIn(0.25f, 1.0f),
        panelScale = (map["panel_scale"]?.toFloatOrNull() ?: 1.0f).coerceIn(0.40f, 1.0f)
    )

    private fun parseRegionsCsv(csv: String?): Set<String>? {
        if (csv.isNullOrBlank()) return null
        val validCodes = RegionCatalog.ALL.mapTo(HashSet()) { it.code }
        val parsed = csv.split(',')
            .map { it.trim() }
            .filter { it in validCodes }
            .toSet()
        return parsed.ifEmpty { null }
    }

    private fun packageRegionKey(packageName: String?): String? {
        val clean = packageName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return "pkg_regions_$clean"
    }

    private fun resolveRegionsForPackage(settings: Map<String, String>, packageName: String?): Set<String> {
        val pkgKey = packageRegionKey(packageName)
        if (pkgKey != null) {
            parseRegionsCsv(settings[pkgKey])?.let { return it }
        }
        parseRegionsCsv(settings["default_selected_regions"])?.let { return it }
        return RegionCatalog.DEFAULT_SELECTED
    }

    private fun persistSelectedRegions(regions: Set<String>, packageName: String?) {
        val csv = regions.joinToString(",")
        val pkgKey = packageRegionKey(packageName)
        _ui.update { st ->
            val nextSettings = st.settings.toMutableMap()
            nextSettings["default_selected_regions"] = csv
            if (pkgKey != null) {
                nextSettings[pkgKey] = csv
            }
            st.copy(settings = nextSettings)
        }
        scope.launch {
            repo?.setString("default_selected_regions", csv)
            if (pkgKey != null) {
                repo?.setString(pkgKey, csv)
            }
        }
    }

    private fun parseToolOrderCsv(csv: String?): List<ToolId>? {
        if (csv.isNullOrBlank()) return null
        val byName = ToolId.entries.associateBy { it.name }
        val list = csv.split(',').mapNotNull { byName[it.trim()] }.distinct()
        return list.ifEmpty { null }
    }

    private fun persistToolsForTab(tab: NextTab, list: List<ToolId>) {
        val key = "toolbar_order_${tab.name}"
        val csv = list.joinToString(",") { it.name }
        _ui.update { st -> st.copy(settings = st.settings + (key to csv)) }
        scope.launch { repo?.setString(key, csv) }
    }

    private fun persistPinnedToolsForTab(tab: NextTab, list: List<ToolId>) {
        val key = "pinned_tools_${tab.name}"
        val csv = list.joinToString(",") { it.name }
        _ui.update { st -> st.copy(settings = st.settings + (key to csv)) }
        scope.launch { repo?.setString(key, csv) }
    }

    private fun syncFrozenSnapshot(savedList: List<MemoryItem>) {
        frozenItemsSnapshot = if (savedList.isEmpty()) emptyList() else savedList.filter { it.freeze }
    }

    fun init(app: Application) {
        appContext = app
        val rootOk = com.littlecodesauce.ggnext.engine.Shell.hasRoot()
        _ui.update { it.copy(hasRootPermission = rootOk, workMode = if (rootOk) WorkMode.ROOT else WorkMode.NO_ROOT) }
        if (repo != null) return
        val r = SettingsRepository(app)
        repo = r
        scope.launch {
            r.settingsSnapshot().collect { map ->
                _ui.update { st ->
                    val merged = st.settings + map
                    val savedMode = merged["work_mode"]
                    val resolvedMode = when (savedMode) {
                        "no_root" -> WorkMode.NO_ROOT
                        "root" -> WorkMode.ROOT
                        else -> if (rootOk) WorkMode.ROOT else WorkMode.NO_ROOT
                    }
                    val restoredMaxShow = (merged["filter_max_show"]?.toIntOrNull() ?: st.filter.maxShow).coerceIn(1, 100000)
                    val restoredSearchType = merged["search_type"]?.toIntOrNull() ?: st.search.type
                    val restoredPointerType = merged["pointer_type"]?.toIntOrNull() ?: st.search.pointerType
                    val restoredOffsetHex = merged["pointer_offset_hex"]?.let { it != "0" } ?: st.search.offsetHex
                    val restoredFuzzyType = merged["fuzzy_type"]?.toIntOrNull() ?: st.fuzzy.type
                    val restoredProcFilter = (merged["process_filter"]?.toIntOrNull() ?: st.processFilter).coerceIn(0, 2)
                    val restoredProcSort = merged["process_sort_by_size"]?.let { it != "0" } ?: st.processSortBySize
                    val restoredSpeed = (merged["speed_value"]?.toFloatOrNull() ?: st.speedMultiplier).coerceIn(0.01f, 100f)
                    val restoredScriptPath = merged["script_path"]?.takeIf { it.isNotBlank() } ?: st.scriptPath
                    val restoredFilePath = merged["file_manager_path"]?.takeIf { it.isNotBlank() } ?: st.fileManagerPath
                    val restoredDumpDir = merged["dump_dir_path"]?.takeIf { it.isNotBlank() } ?: st.dumpDirPath
                    val restoredRegions = resolveRegionsForPackage(merged, st.target?.packageName)

                    val restoredTools = st.tools.toMutableMap()
                    val restoredPinned = st.pinnedTools.toMutableMap()
                    listOf(NextTab.SEARCH_LIST, NextTab.SAVED_LIST, NextTab.MEMORY).forEach { t ->
                        parseToolOrderCsv(merged["toolbar_order_${t.name}"])?.let { restoredTools[t] = it }
                        parseToolOrderCsv(merged["pinned_tools_${t.name}"])?.let { restoredPinned[t] = it }
                    }

                    st.copy(
                        settings = merged,
                        settingsLoaded = true,
                        disclaimerAccepted = merged["disclaimer_v1_accepted"] == "1",
                        metrics = buildMetrics(merged),
                        preset = ThemePreset.entries.firstOrNull { p -> p.name == merged["theme_preset"] }
                            ?: st.preset,
                        darkMode = DarkMode.entries.firstOrNull { d -> d.name == merged["dark_mode"] }
                            ?: st.darkMode,
                        dynamicColor = merged["dynamic_color"] == "1",
                        workMode = resolvedMode,
                        selectedRegions = restoredRegions,
                        filter = st.filter.copy(maxShow = restoredMaxShow),
                        searchType = restoredSearchType,
                        search = st.search.copy(
                            type = restoredSearchType,
                            pointerType = restoredPointerType,
                            offsetHex = restoredOffsetHex
                        ),
                        fuzzy = st.fuzzy.copy(type = restoredFuzzyType),
                        processFilter = restoredProcFilter,
                        processSortBySize = restoredProcSort,
                        speedMultiplier = restoredSpeed,
                        scriptPath = restoredScriptPath,
                        fileManagerPath = restoredFilePath,
                        dumpDirPath = restoredDumpDir,
                        tools = restoredTools,
                        pinnedTools = restoredPinned
                    )
                }
            }
        }
        scope.launch { r.valueFormatMask().collect { m -> _ui.update { it.copy(valueMask = m) } } }
        scope.launch { r.savedFormatMask().collect { m -> _ui.update { it.copy(valueMaskSaved = m) } } }
        scope.launch(Dispatchers.IO) {
            var lastSyncedInterval = -1L
            while (true) {
                val rawFreeze = _ui.value.settings["freeze_interval"]?.toLongOrNull() ?: 33L
                val intervalMs = (if (rawFreeze > 1000L) rawFreeze / 1000L else rawFreeze).coerceIn(5L, 5000L)
                if (intervalMs != lastSyncedInterval) {
                    lastSyncedInterval = intervalMs
                    engine.setFreezeIntervalMs(intervalMs.toInt())
                }
                delay(intervalMs)
                val frozen = frozenItemsSnapshot
                if (frozen.isNotEmpty() && !_ui.value.processPaused) {
                    engine.tickFreeze(frozen)
                }
            }
        }
        // 3.1 周期性实时刷新搜索列表、保存列表与内存浏览页中的动态数值
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(900L)
                val st = _ui.value
                if (st.attachedOk && !st.busy && st.sheet == Sheet.NONE) {
                    if (st.results.isNotEmpty() || st.saved.isNotEmpty() || (st.tab == NextTab.MEMORY && st.memoryRows.isNotEmpty())) {
                        refreshLiveValues(manual = false)
                    }
                }
            }
        }
        loadProcesses(app)
    }

    // ============ 基础 ============


    fun setWorkMode(mode: WorkMode) {
        val hasRoot = if (mode == WorkMode.ROOT) {
            com.littlecodesauce.ggnext.engine.Shell.resetRootCache()
            com.littlecodesauce.ggnext.engine.Shell.hasRoot()
        } else {
            _ui.value.hasRootPermission
        }
        _ui.update { it.copy(workMode = mode, hasRootPermission = hasRoot) }
        scope.launch { repo?.setString("work_mode", mode.code) }
        appContext?.let { loadProcesses(it) }
        val target = _ui.value.target
        if (target != null) {
            attach(target, silent = true, forceReattach = true)
        }
        toast(when (mode) {
            WorkMode.ROOT -> if (hasRoot) "已切换至 Root 模式" else "已切换至 Root 模式，未检测到 Root 授权"
            WorkMode.NO_ROOT -> "已切换至免 Root 模式"
        })
    }

    fun refreshRootStatus(promptIfMissing: Boolean = false) {
        com.littlecodesauce.ggnext.engine.Shell.resetRootCache()
        val hasRoot = com.littlecodesauce.ggnext.engine.Shell.hasRoot()
        _ui.update { it.copy(hasRootPermission = hasRoot) }
        appContext?.let { loadProcesses(it) }
        val target = _ui.value.target
        if (target != null) {
            attach(target, silent = true, forceReattach = true)
        }
        if (hasRoot) {
            toast("Root 权限已获取")
        } else if (promptIfMissing) {
            toast("未检测到 Root 权限，请在管理器中授权或切换到免 Root 模式")
        }
    }

    fun openSheet(sheet: Sheet, item: MemoryItem? = null, singleEdit: Boolean = false) {
        if (sheet == Sheet.PROCESS) {
            appContext?.let { loadProcesses(it) }
        } else if (sheet == Sheet.REGION) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { aggregate(engine.regions()) }
                _ui.update { it.copy(regions = fresh) }
            }
        }
        _ui.update {
            it.copy(
                sheet = sheet,
                editing = item ?: it.editing,
                editSingleOnly = singleEdit && item != null,
                contextItem = item ?: it.contextItem
            )
        }
    }

    private var historyCallerSheet: Sheet = Sheet.SEARCH

    fun openHistorySheet(fromSheet: Sheet = _ui.value.sheet) {
        historyCallerSheet = if (fromSheet != Sheet.HISTORY && fromSheet != Sheet.NONE) fromSheet else Sheet.SEARCH
        openSheet(Sheet.HISTORY)
    }

    fun recordGotoHistory(hex: String) {
        val clean = hex.trim().removePrefix("0x").removeSuffix("h").uppercase()
        if (clean.isNotEmpty()) {
            _ui.update {
                val list = (listOf(clean) + it.gotoHistory.filter { h -> h != clean }).take(20)
                it.copy(gotoHistory = list)
            }
        }
    }

    fun closeSheet() = _ui.update {
        if (it.sheet == Sheet.HISTORY && historyCallerSheet != Sheet.NONE && historyCallerSheet != Sheet.HISTORY) {
            val returnTo = historyCallerSheet
            historyCallerSheet = Sheet.SEARCH
            it.copy(sheet = returnTo, editSingleOnly = false)
        } else {
            it.copy(sheet = Sheet.NONE, editSingleOnly = false)
        }
    }

    fun toast(msg: String) = _ui.update { it.copy(toast = msg) }

    fun consumeToast() = _ui.update { it.copy(toast = null) }

    fun selectTab(tab: NextTab) {
        _ui.update {
            if (it.tab == tab) it else it.copy(tab = tab)
        }
        recount()
        if (tab == NextTab.MEMORY && _ui.value.memoryRows.isEmpty()) {
            goto(_ui.value.memoryBase, recordHistory = false, highlightTarget = false)
        }
    }

    // ============ 改进5：文件浏览器联动 ============

    fun openFileBrowser(
        initialPath: String,
        selectDirectory: Boolean,
        returnSheet: Sheet,
        target: FileBrowserTarget
    ) {
        val dir = initialPath.trim().let { p ->
            if (p.isEmpty()) "/sdcard/Download"
            else if (selectDirectory) p
            else p.substringBeforeLast('/', "/sdcard/Download").ifEmpty { "/sdcard" }
        }
        _ui.update {
            it.copy(
                sheet = Sheet.FILE_BROWSER,
                fileBrowser = FileBrowserConfig(
                    currentDir = dir,
                    selectDirectory = selectDirectory,
                    returnSheet = returnSheet,
                    target = target
                )
            )
        }
    }

    fun onFileBrowserPicked(selectedPath: String) {
        val target = _ui.value.fileBrowser.target
        when (target) {
            FileBrowserTarget.SCRIPT -> setSetting("script_path", selectedPath)
            FileBrowserTarget.FILE_MANAGER -> setSetting("file_manager_path", selectedPath)
            FileBrowserTarget.DUMP -> setSetting("dump_dir_path", selectedPath)
        }
        _ui.update { st ->
            when (st.fileBrowser.target) {
                FileBrowserTarget.SCRIPT -> st.copy(
                    scriptPath = selectedPath,
                    sheet = st.fileBrowser.returnSheet
                )
                FileBrowserTarget.FILE_MANAGER -> st.copy(
                    fileManagerPath = selectedPath,
                    sheet = st.fileBrowser.returnSheet
                )
                FileBrowserTarget.DUMP -> st.copy(
                    dumpDirPath = selectedPath,
                    sheet = st.fileBrowser.returnSheet
                )
            }
        }
    }

    fun setScriptPath(path: String) {
        setSetting("script_path", path)
        _ui.update { it.copy(scriptPath = path) }
    }

    fun setFileManagerPath(path: String) {
        setSetting("file_manager_path", path)
        _ui.update { it.copy(fileManagerPath = path) }
    }

    fun setDumpDirPath(path: String) {
        setSetting("dump_dir_path", path)
        _ui.update { it.copy(dumpDirPath = path) }
    }

    fun removeScriptHistory(path: String) = _ui.update {
        it.copy(scriptHistory = it.scriptHistory.filterNot { p -> p == path })
    }

    fun pinScriptHistory(path: String) = _ui.update {
        val next = (listOf(path) + it.scriptHistory.filterNot { p -> p == path })
        it.copy(scriptHistory = next)
    }

    fun updateScriptHistory(oldPath: String, newPath: String) = _ui.update {
        val next = it.scriptHistory.map { p -> if (p == oldPath) newPath else p }
        it.copy(scriptHistory = next, scriptPath = if (it.scriptPath == oldPath) newPath else it.scriptPath)
    }

    fun runScript(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) {
            toast("请输入或选择有效的 Lua 脚本路径")
            return
        }
        setSetting("script_path", clean)
        AppLog.add("script", "execute lua script: $clean")
        _ui.update { st ->
            val nextHist = (listOf(clean) + st.scriptHistory.filterNot { it == clean }).take(12)
            st.copy(
                scriptPath = clean,
                scriptHistory = nextHist,
                sheet = Sheet.NONE,
                toast = "正在执行脚本：${clean.substringAfterLast('/')}"
            )
        }
    }

    fun setting(id: String): String =
        repo?.let { SettingsSpecs.byId(id)?.let { s -> SettingsRepository.valueOf(_ui.value.settings, s) } }
            ?: _ui.value.settings[id].orEmpty()

    fun setSetting(id: String, value: String) {
        repo?.let { r -> scope.launch { r.setString(id, value) } }
        _ui.update { st ->
            val nextSettings = st.settings + (id to value)
            val nextPreset = if (id == "theme_preset") {
                ThemePreset.entries.firstOrNull { p -> p.name == value } ?: st.preset
            } else st.preset
            val nextDark = if (id == "dark_mode") {
                DarkMode.entries.firstOrNull { d -> d.name == value } ?: st.darkMode
            } else st.darkMode
            val nextDyn = if (id == "dynamic_color") (value == "1") else st.dynamicColor
            st.copy(
                settings = nextSettings,
                preset = nextPreset,
                darkMode = nextDark,
                dynamicColor = nextDyn,
                metrics = buildMetrics(nextSettings)
            )
        }
    }

    fun resetAppSettings() {
        setPreset(ThemePreset.ROSE)
        setDarkMode(DarkMode.DARK)
        setDynamicColor(false)
        setSetting("font_scale", "0.88")
        setSetting("dialog_corner", "12")
        setSetting("bg_opacity", "0.96")
        setSetting("panel_scale", "1.00")
        setSetting("language", "0")
        setSetting("icons_size", "48")
        toast("已恢复默认设置")
    }

    fun setPreset(p: ThemePreset) = setSetting("theme_preset", p.name)
    fun setDarkMode(m: DarkMode) = setSetting("dark_mode", m.name)
    fun setDynamicColor(v: Boolean) = setSetting("dynamic_color", if (v) "1" else "0")

    fun acceptDisclaimer() {
        _ui.update { it.copy(disclaimerAccepted = true) }
        setSetting("disclaimer_v1_accepted", "1")
    }

    fun setHomeSettingsOpen(open: Boolean) {
        _ui.update { it.copy(homeSettingsOpen = open) }
    }

    fun setFloatingBallVisible(visible: Boolean) = _ui.update {
        it.copy(
            floatingBallVisible = visible,
            panelVisible = if (!visible) false else it.panelVisible,
            speedBarExpanded = if (!visible) false else it.speedBarExpanded
        )
    }

    private fun applyAutoPauseIfEnabled(panelOpening: Boolean) {
        val st = _ui.value
        if (st.settings["autopause"] != "1") return
        val pid = st.target?.pid ?: return
        if (pid <= 0) return
        val sig = if (panelOpening) "-STOP" else "-CONT"
        _ui.update { it.copy(processPaused = panelOpening) }
        scope.launch(Dispatchers.IO) {
            engine.setFreezePaused(panelOpening)
            com.littlecodesauce.ggnext.engine.Shell.runAs(
                "kill $sig $pid",
                asRoot = (st.workMode == WorkMode.ROOT && com.littlecodesauce.ggnext.engine.Shell.hasRoot())
            )
        }
    }

    fun setPanelVisible(visible: Boolean) {
        val prev = _ui.value.panelVisible
        _ui.update {
            it.copy(
                panelVisible = visible,
                floatingBallVisible = if (visible) true else it.floatingBallVisible,
                speedBarExpanded = if (visible) false else it.speedBarExpanded
            )
        }
        if (prev != visible) {
            applyAutoPauseIfEnabled(visible)
        }
    }

    fun togglePanelVisible() {
        val next = !_ui.value.panelVisible
        _ui.update {
            it.copy(
                panelVisible = next,
                floatingBallVisible = true,
                speedBarExpanded = false,
                sheet = if (!next) Sheet.NONE else it.sheet
            )
        }
        applyAutoPauseIfEnabled(next)
    }

    fun minimize() {
        val wasVisible = _ui.value.panelVisible
        _ui.update {
            it.copy(
                sheet = Sheet.NONE,
                panelVisible = false,
                floatingBallVisible = true
            )
        }
        if (wasVisible) {
            applyAutoPauseIfEnabled(false)
        }
    }

    // ============ 悬浮球长按变速控制 ============

    fun toggleSpeedBar() = _ui.update { st ->
        val next = !st.speedBarExpanded
        st.copy(
            speedBarExpanded = next,
            toast = if (next) "变速控制已展开：${st.speedText}" else null
        )
    }

    fun setSpeedBarExpanded(expanded: Boolean) = _ui.update {
        it.copy(speedBarExpanded = expanded)
    }

    fun stepSpeed(faster: Boolean) {
        val cur = _ui.value.speedMultiplier
        val idx = SPEED_STEPS.indexOfFirst { abs(it - cur) < 0.01f }.let {
            if (it >= 0) it else SPEED_STEPS.indexOfFirst { s -> s >= cur }.coerceAtLeast(0)
        }
        val nextIdx = (if (faster) idx + 1 else idx - 1).coerceIn(0, SPEED_STEPS.lastIndex)
        val nextSpeed = SPEED_STEPS[nextIdx]
        setSetting("speed_value", nextSpeed.toString())
        _ui.update { st ->
            val nextSt = st.copy(speedMultiplier = nextSpeed, speedBarExpanded = true)
            nextSt.copy(toast = "游戏变速: ${nextSt.speedText}")
        }
    }

    fun setSpeedMultiplier(v: Float) {
        val clamped = v.coerceIn(0.01f, 100f)
        setSetting("speed_value", clamped.toString())
        _ui.update { st ->
            val nextSt = st.copy(speedMultiplier = clamped)
            nextSt.copy(toast = "游戏变速已设为 ${nextSt.speedText}")
        }
    }

    fun resetSpeed() {
        setSetting("speed_value", "1.0")
        _ui.update { st ->
            st.copy(speedMultiplier = 1.0f, toast = "已恢复正常速度 1.0x")
        }
    }

    // ============ 进程 ============

    fun loadProcesses(app: Application) {
        scope.launch {
            _ui.update { it.copy(processLoading = true) }
            val list = withContext(Dispatchers.IO) { ProcessScanner.scan(app) }
            val defaultTarget = _ui.value.target ?: list.firstOrNull { !it.system } ?: list.firstOrNull()
            _ui.update { it.copy(processes = list, processLoading = false) }
            if (_ui.value.target == null && defaultTarget != null) {
                attach(defaultTarget, silent = true)
            }
        }
    }

    fun setProcessFilter(index: Int) {
        val clamped = index.coerceIn(0, 2)
        setSetting("process_filter", clamped.toString())
        _ui.update { st ->
            st.copy(processFilter = clamped)
        }
    }

    fun toggleProcessSortBySize() {
        val next = !_ui.value.processSortBySize
        setSetting("process_sort_by_size", if (next) "1" else "0")
        _ui.update { st ->
            st.copy(processSortBySize = next)
        }
    }

    fun toggleProcessPause() = _ui.update { st ->
        val next = !st.processPaused
        val pid = st.target?.pid ?: 0
        if (pid > 0) {
            val sig = if (next) "-STOP" else "-CONT"
            scope.launch(Dispatchers.IO) {
                engine.setFreezePaused(next)
                com.littlecodesauce.ggnext.engine.Shell.runAs(
                    "kill $sig $pid",
                    asRoot = (st.workMode == WorkMode.ROOT && com.littlecodesauce.ggnext.engine.Shell.hasRoot())
                )
            }
        }
        st.copy(
            processPaused = next,
            toast = if (next) "已暂停目标进程 ${st.target?.label.orEmpty()}" else "已恢复目标进程 ${st.target?.label.orEmpty()}"
        )
    }

    fun attach(proc: TargetProcess, silent: Boolean = false, forceReattach: Boolean = false) {
        val currentTarget = _ui.value.target
        if (!forceReattach && currentTarget?.pid == proc.pid && _ui.value.attachedOk) {
            if (!silent) toast("当前已附加到 ${proc.label}")
            _ui.update { it.copy(sheet = Sheet.NONE) }
            return
        }
        hasFuzzySession = false
        saveListScrollPosition("search_list", 0, 0)
        scope.launch {
            attachMutex.withLock {
                val mode = _ui.value.workMode
                var resolvedProc = proc
                var ok = withContext(Dispatchers.IO) {
                    engine.attach(
                        targetPid = resolvedProc.pid,
                        targetX64 = resolvedProc.x64,
                        isRootMode = (mode == WorkMode.ROOT),
                        context = appContext
                    )
                }
                // 若目标应用重启导致缓存的 PID 失效，实时重新扫描进程表并按包名匹配最新存活 PID 重试附加
                if (!ok && resolvedProc.packageName.isNotBlank()) {
                    val app = appContext
                    if (app != null) {
                        val freshList = withContext(Dispatchers.IO) { ProcessScanner.scan(app) }
                        _ui.update { it.copy(processes = freshList) }
                        val replacement = freshList.firstOrNull {
                            it.packageName == resolvedProc.packageName && it.label == resolvedProc.label
                        } ?: freshList.firstOrNull {
                            it.packageName == resolvedProc.packageName
                        }
                        if (replacement != null && replacement.pid != resolvedProc.pid) {
                            resolvedProc = replacement
                            ok = withContext(Dispatchers.IO) {
                                engine.attach(
                                    targetPid = resolvedProc.pid,
                                    targetX64 = resolvedProc.x64,
                                    isRootMode = (mode == WorkMode.ROOT),
                                    context = appContext
                                )
                            }
                        }
                    }
                }
                val rawRegions = withContext(Dispatchers.IO) { engine.regions() }
                val regions = withContext(Dispatchers.IO) { aggregate(rawRegions) }
                val defaultBase = rawRegions.firstOrNull { it.code == "Xa" || it.code == "Cd" || it.code == "Jh" || it.code == "Ca" }?.start
                    ?: rawRegions.firstOrNull { it.readable }?.start
                    ?: 0L
                _ui.update {
                    val pkgRegions = resolveRegionsForPackage(it.settings, resolvedProc.packageName)
                    it.copy(
                        target = resolvedProc,
                        processPaused = false,
                        attachedOk = ok,
                        regions = regions,
                        selectedRegions = pkgRegions,
                        memoryBase = if (defaultBase != 0L) defaultBase else it.memoryBase,
                        sheet = if (silent) it.sheet else Sheet.NONE,
                        hintVisible = true,
                        rawResults = emptyList(),
                        results = emptyList(),
                        totalCount = 0,
                        selectedCount = 0,
                        memoryRows = emptyList(),
                        fuzzy = it.fuzzy.copy(active = false)
                    )
                }
                AppLog.add("attach", "${resolvedProc.label} pid=${resolvedProc.pid} ok=$ok regions=${rawRegions.size}")
                if (!silent) {
                    if (ok) {
                        toast("已附加到 ${resolvedProc.label}")
                    } else {
                        val detail = engine.lastAttachError.ifBlank { "无跨进程内存读写权限，请检查 Root 授权" }
                        toast("附加 ${resolvedProc.label} 失败：$detail")
                    }
                }
            }
        }
    }

    /**
     * 3.1 实时刷新搜索列表、保存列表与内存浏览页中的动态数值
     * Bug 5 & Bug 6 深度优化：
     * 1. 当列表项目达到数万条时，仅对当前可视窗口附近（最多 80 条）执行跨进程实时读取，彻底消除 50000+ 条目后台轮询导致的卡死与 GC 风暴；
     * 2. 在 `_ui.update { s -> ... }` 内部基于最新状态 `s` 原地合并实时数值，绝不用旧快照覆盖用户在 I/O 期间刚勾选的 `checked` 状态，彻底根治勾选闪烁/自动取消勾选 Bug！
     */
    fun refreshLiveValues(manual: Boolean = false) {
        val st = _ui.value
        if (!st.attachedOk || st.target == null) {
            if (manual) toast("请先附加目标进程")
            return
        }
        if (st.busy) return
        if (liveRefreshRunning && !manual) return
        liveRefreshRunning = true

        scope.launch(Dispatchers.IO) {
            try {
                val cur = _ui.value
                // 1. 仅读取当前可见区域的搜索结果（最多 80 条）
                val liveResultMap: Map<Long, Long> = if (cur.results.isNotEmpty() && (manual || cur.tab == NextTab.SEARCH_LIST)) {
                    val scrollIdx = getListScrollPosition("search_list").first.coerceIn(0, (cur.results.size - 1).coerceAtLeast(0))
                    val startIdx = (scrollIdx - 15).coerceAtLeast(0)
                    val endIdx = (scrollIdx + 65).coerceAtMost(cur.results.size)
                    val slice = cur.results.subList(startIdx, endIdx)
                    val pairs = slice.map { it.address to it.size }
                    engine.batchReadRawValues(pairs)
                } else {
                    emptyMap()
                }

                // 2. 仅读取当前可见区域的保存列表（最多 80 条未冻结或动态递增/递减/范围冻结项）
                val liveSavedMap: Map<Long, Long> = if (cur.saved.isNotEmpty() && (manual || cur.tab == NextTab.SAVED_LIST)) {
                    val scrollIdx = getListScrollPosition("saved_list").first.coerceIn(0, (cur.saved.size - 1).coerceAtLeast(0))
                    val startIdx = (scrollIdx - 15).coerceAtLeast(0)
                    val endIdx = (scrollIdx + 65).coerceAtMost(cur.saved.size)
                    val slice = cur.saved.subList(startIdx, endIdx)
                    val dynamicOrUnfrozen = slice.filter { !it.freeze || it.freezeType != 0 }.map { it.address to it.size }
                    if (dynamicOrUnfrozen.isNotEmpty()) engine.batchReadRawValues(dynamicOrUnfrozen) else emptyMap()
                } else {
                    emptyMap()
                }

                // 3. 刷新内存页数值（当处于内存页或手动点击刷新且已加载内存页时）
                val pageBase = cur.memoryBase
                val memCount = cur.memoryRows.size
                val memBlock: ByteArray? = if (memCount > 0 && (manual || cur.tab == NextTab.MEMORY)) {
                    engine.readBlock(pageBase, memCount * 4)
                } else {
                    null
                }

                if (liveResultMap.isEmpty() && liveSavedMap.isEmpty() && memBlock == null) {
                    if (manual) {
                        withContext(Dispatchers.Main) { toast("已刷新实时内存数据") }
                    }
                    return@launch
                }

                // 在 _ui.update 中基于最新状态 s 原地合并数值，保留最新的 checked / freeze / note 等字段
                _ui.update { s ->
                    var nextResults = s.results
                    var nextRaw = s.rawResults
                    if (liveResultMap.isNotEmpty() && s.results.isNotEmpty()) {
                        var changedAny = false
                        val updatedRes = ArrayList<MemoryItem>(s.results.size)
                        for (item in s.results) {
                            val freshVal = liveResultMap[item.address]
                            if (freshVal != null && freshVal != item.value) {
                                changedAny = true
                                updatedRes.add(item.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                            } else {
                                updatedRes.add(item)
                            }
                        }
                        if (changedAny) {
                            nextResults = updatedRes
                            if (s.rawResults === s.results) {
                                nextRaw = updatedRes
                            } else if (s.rawResults.size <= 5000) {
                                val updatedRaw = ArrayList<MemoryItem>(s.rawResults.size)
                                for (r in s.rawResults) {
                                    val freshVal = liveResultMap[r.address]
                                    if (freshVal != null && freshVal != r.value) {
                                        updatedRaw.add(r.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                                    } else {
                                        updatedRaw.add(r)
                                    }
                                }
                                nextRaw = updatedRaw
                            }
                        }
                    }

                    var nextSaved = s.saved
                    if (liveSavedMap.isNotEmpty() && s.saved.isNotEmpty()) {
                        var savedChanged = false
                        val updatedSaved = ArrayList<MemoryItem>(s.saved.size)
                        for (item in s.saved) {
                            if (item.freeze && item.freezeType == 0) {
                                updatedSaved.add(item)
                            } else {
                                val freshVal = liveSavedMap[item.address]
                                if (freshVal != null && freshVal != item.value) {
                                    savedChanged = true
                                    updatedSaved.add(item.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                                } else {
                                    updatedSaved.add(item)
                                }
                            }
                        }
                        if (savedChanged) {
                            nextSaved = updatedSaved
                        }
                    }

                    var nextMemRows = s.memoryRows
                    if (memBlock != null && s.memoryRows.size == memCount && s.memoryBase == pageBase) {
                        var memChanged = false
                        val updatedMem = ArrayList<MemoryItem>(memCount)
                        for (r in 0 until memCount) {
                            val oldItem = s.memoryRows[r]
                            val off = r * 4
                            val v = if (off + 4 <= memBlock.size) {
                                (memBlock[off].toLong() and 0xFF) or
                                    ((memBlock[off + 1].toLong() and 0xFF) shl 8) or
                                    ((memBlock[off + 2].toLong() and 0xFF) shl 16) or
                                    ((memBlock[off + 3].toLong() and 0xFF) shl 24)
                            } else oldItem.value
                            if (v != oldItem.value) {
                                memChanged = true
                                updatedMem.add(oldItem.copy(value = v, previousValue = oldItem.value))
                            } else {
                                updatedMem.add(oldItem)
                            }
                        }
                        if (memChanged) {
                            nextMemRows = updatedMem
                        }
                    }

                    if (nextResults === s.results && nextRaw === s.rawResults && nextSaved === s.saved && nextMemRows === s.memoryRows) {
                        s
                    } else {
                        s.copy(
                            results = nextResults,
                            rawResults = nextRaw,
                            saved = nextSaved,
                            memoryRows = nextMemRows
                        )
                    }
                }
                if (manual) {
                    withContext(Dispatchers.Main) {
                        toast("已刷新实时内存数据")
                    }
                }
            } finally {
                liveRefreshRunning = false
            }
        }
    }

    private fun makeProgressCallback(startTimeMs: Long): (Long, Long, Long) -> Unit = { doneBytes, totalBytes, foundSoFar ->
        val frac = if (totalBytes > 0L) (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        val elapsedSec = ((System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)) / 1000.0
        val eta = if (frac > 0.03f && frac < 0.995f) {
            ((elapsedSec / frac) * (1.0 - frac)).toInt().coerceAtLeast(0)
        } else if (frac >= 0.995f) {
            0
        } else {
            -1
        }
        val scannedMb = doneBytes.toFloat() / (1024f * 1024f)
        val totalMb = totalBytes.toFloat() / (1024f * 1024f)
        _ui.update {
            it.copy(
                searchProgress = frac,
                searchFoundSoFar = foundSoFar,
                searchEtaSeconds = eta,
                searchScannedMb = scannedMb,
                searchTotalMb = totalMb
            )
        }
    }

    fun currentProcessRegions(): List<MemoryRegion> = engine.regions()

    fun firstRegionBase(code: String): Long? = engine.firstRegionBase(code)

    private val regionNames = mapOf(
        "Ch" to "C++ heap", "Jh" to "Java heap", "Ca" to "C++ alloc", "Cd" to "C++ .data",
        "Cb" to "C++ .bss", "A" to "Anonymous", "S" to "Stack", "Xa" to "Code app",
        "Xs" to "Code system", "J" to "Java", "B" to "Bad", "Ps" to "PPSSPP",
        "As" to "Ashmem", "V" to "Video", "O" to "Other"
    )

    private fun aggregate(raw: List<MemoryRegion>): List<RegionRow> {
        val base = raw.groupBy { it.code }
        return RegionCatalog.ALL.map { rt ->
            val items = base[rt.code].orEmpty()
            RegionRow(
                code = rt.code,
                name = regionNames[rt.code] ?: rt.name,
                size = items.sumOf { it.size },
                count = items.size
            )
        }
    }

    fun toggleRegion(code: String) {
        val cur = _ui.value
        val nextSet = if (cur.selectedRegions.contains(code)) cur.selectedRegions - code else cur.selectedRegions + code
        _ui.update { it.copy(selectedRegions = nextSet) }
        persistSelectedRegions(nextSet, cur.target?.packageName)
    }

    fun resetRegions() {
        val defaultSet = RegionCatalog.DEFAULT_SELECTED
        _ui.update { it.copy(selectedRegions = defaultSet) }
        persistSelectedRegions(defaultSet, _ui.value.target?.packageName)
    }

    fun saveRegions() {
        val cur = _ui.value
        persistSelectedRegions(cur.selectedRegions, cur.target?.packageName)
        _ui.update { it.copy(sheet = Sheet.NONE) }
        toast("已保存内存范围设置")
    }

    // ============ 精确 / 范围 / 联合 / 蒙版 / 指针 / 模糊搜索 ============

    fun updateSearch(f: (SearchInput) -> SearchInput) {
        val prev = _ui.value.search
        val next = f(prev)
        _ui.update { it.copy(search = next) }
        if (next.type != prev.type) setSetting("search_type", next.type.toString())
        if (next.pointerType != prev.pointerType) setSetting("pointer_type", next.pointerType.toString())
        if (next.offsetHex != prev.offsetHex) setSetting("pointer_offset_hex", if (next.offsetHex) "1" else "0")
    }

    fun updateFuzzy(f: (FuzzyState) -> FuzzyState) {
        val prev = _ui.value.fuzzy
        val next = f(prev)
        _ui.update { it.copy(fuzzy = next) }
        if (next.type != prev.type) setSetting("fuzzy_type", next.type.toString())
    }

    fun updateFilter(f: (FilterState) -> FilterState) = _ui.update { it.copy(filter = f(it.filter)) }

    fun runSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        val input = st.search.text.trim()
        if (input.isEmpty()) {
            toast("请输入搜索条件")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            engine.clearRemoved()
            saveListScrollPosition("search_list", 0, 0)
        }
        scope.launch {
            val parsed = try {
                NumberParser.parse(input, st.search.type, st.search.sign)
            } catch (e: NumberParser.ParseException) {
                toast(e.message ?: "输入有误")
                return@launch
            }
            val startTime = System.currentTimeMillis()
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = if (newSearch) "新搜索: $input" else "改善: $input",
                    searchProgress = 0f,
                    searchFoundSoFar = 0L,
                    searchEtaSeconds = -1,
                    searchScannedMb = 0f,
                    searchTotalMb = 0f,
                    sheet = Sheet.NONE,
                    tab = NextTab.SEARCH_LIST
                )
            }
            val type = parsed.baseType and 0x7F
            val isRange = parsed.kind == NumberParser.Kind.RANGE
            val memFrom = when (st.search.rangeMode) {
                1 -> st.search.rangeFrom.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                2 -> {
                    val base = st.search.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.search.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.search.nearbyBefore) (base - dist).coerceAtLeast(0L) else base
                }
                else -> 0L
            }
            val memTo = when (st.search.rangeMode) {
                1 -> st.search.rangeTo.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: Long.MAX_VALUE
                2 -> {
                    val base = st.search.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.search.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.search.nearbyAfter) base + dist else base
                }
                else -> Long.MAX_VALUE
            }
            val criteria = MemoryEngine.Criteria(
                type = type,
                value = parsed.numbers.firstOrNull() ?: 0L,
                from = parsed.numbers.firstOrNull() ?: 0L,
                to = parsed.to.firstOrNull() ?: 0L,
                isRange = isRange,
                notRange = parsed.notRange,
                sign = parsed.sign,
                xorKey = parsed.xorKey,
                xorOffset = parsed.xorOffset,
                orderedGroup = parsed.numbers,
                groupTo = parsed.to,
                groupOps = parsed.groupOps,
                ordered = parsed.ordered,
                groupTypes = parsed.types,
                area = parsed.area.coerceIn(2, 65536),
                memoryFrom = memFrom,
                memoryTo = memTo,
                limit = maxOf(st.filter.maxShow, MemoryEngine.MAX_CACHED_CANDIDATES),
                rawExpression = input,
                encryptedXor = st.search.encrypted,
                selectedRegions = st.selectedRegions,
                isText = (parsed.kind == NumberParser.Kind.TEXT),
                textBytes = parsed.textBytes,
                textUtf16 = parsed.textUtf16
            )
            val isRefine = !newSearch && (hasFuzzySession || st.rawResults.isNotEmpty() || st.totalCount > 0L)
            val previous = if (isRefine) st.rawResults else null
            val progressCb = makeProgressCallback(startTime)
            val result = withContext(Dispatchers.IO) { engine.search(criteria, previous, progressCb) }
            hasFuzzySession = false
            val filtered = filterList(result.items, _ui.value.filter)
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    searchProgress = 1f,
                    searchEtaSeconds = 0,
                    hintVisible = filtered.isEmpty(),
                    selectedCount = 0,
                    searchType = type,
                    fuzzy = it.fuzzy.copy(active = false)
                )
            }
            historyStore.add(input)
            _ui.update { it.copy(history = historyStore.list()) }
            toast("找到 ${result.totalFound} 条结果")
        }
    }

    /** 执行模糊搜索（初始搜索 / 无变化 / 有变化 / 增加了 / 减少了） */
    fun runFuzzySearch(sign: Int, forceNew: Boolean = false) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        scope.launch {
            val isInit = forceNew || (!hasFuzzySession && st.rawResults.isEmpty())
            if (isInit) {
                saveListScrollPosition("search_list", 0, 0)
            }
            val actionName = when (sign and ValueType.SIGN_MASK) {
                ValueType.SIGN_NOT_EQUAL -> "有变化"
                ValueType.SIGN_GREATER_EQ -> "增加了"
                ValueType.SIGN_LESS_EQ -> "减少了"
                else -> "无变化"
            }
            val startTime = System.currentTimeMillis()
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = if (isInit) "模糊初始采样中..." else "模糊筛选 ($actionName)...",
                    searchProgress = 0f,
                    searchFoundSoFar = 0L,
                    searchEtaSeconds = -1,
                    searchScannedMb = 0f,
                    searchTotalMb = 0f,
                    sheet = Sheet.NONE,
                    tab = NextTab.SEARCH_LIST
                )
            }
            val delta = st.fuzzy.deltaText.trim().toLongOrNull() ?: 0L
            val memFrom = when (st.fuzzy.rangeMode) {
                1 -> st.fuzzy.rangeFrom.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                2 -> {
                    val base = st.fuzzy.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.fuzzy.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.fuzzy.nearbyBefore) (base - dist).coerceAtLeast(0L) else base
                }
                else -> 0L
            }
            val memTo = when (st.fuzzy.rangeMode) {
                1 -> st.fuzzy.rangeTo.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: Long.MAX_VALUE
                2 -> {
                    val base = st.fuzzy.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.fuzzy.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.fuzzy.nearbyAfter) base + dist else base
                }
                else -> Long.MAX_VALUE
            }
            val prev = if (isInit) null else st.rawResults
            val progressCb = makeProgressCallback(startTime)
            val result = withContext(Dispatchers.IO) {
                engine.fuzzySearch(
                    type = st.fuzzy.type,
                    sign = sign,
                    delta = delta,
                    onlyNth = 0,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = maxOf(st.filter.maxShow, MemoryEngine.MAX_CACHED_CANDIDATES),
                    memoryFrom = memFrom,
                    memoryTo = memTo,
                    onProgress = progressCb
                )
            }
            hasFuzzySession = result.totalFound > 0L || result.items.isNotEmpty()
            val filtered = filterList(result.items, _ui.value.filter)
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    searchProgress = 1f,
                    searchEtaSeconds = 0,
                    hintVisible = filtered.isEmpty(),
                    selectedCount = 0,
                    fuzzy = it.fuzzy.copy(active = hasFuzzySession)
                )
            }
            toast(
                if (isInit) "已完成初始模糊采样，共 ${result.totalFound} 项"
                else "模糊筛选 $actionName：剩余 ${result.totalFound} 项"
            )
        }
    }

    /** 蒙版搜索 */
    fun runMaskSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            saveListScrollPosition("search_list", 0, 0)
        }
        val rawAddr = st.search.text.trim().removePrefix("0x").removeSuffix("h").replace("?", "0")
        val rawMask = st.search.mask.trim().removePrefix("0x").removeSuffix("h")
        val addrVal = rawAddr.toULongOrNull(16)?.toLong() ?: rawAddr.toLongOrNull(16) ?: st.memoryBase
        val maskVal = rawMask.toULongOrNull(16)?.toLong() ?: -1L
        scope.launch {
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = "地址蒙版搜索中...",
                    searchProgress = 0f,
                    searchFoundSoFar = 0L,
                    searchEtaSeconds = -1,
                    sheet = Sheet.NONE,
                    tab = NextTab.SEARCH_LIST
                )
            }
            val prev = if (!newSearch && st.rawResults.isNotEmpty()) st.rawResults else null
            val result = withContext(Dispatchers.IO) {
                engine.maskSearch(
                    addressPattern = addrVal,
                    maskBits = maskVal,
                    type = st.search.type,
                    sign = st.search.sign,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = maxOf(st.filter.maxShow, MemoryEngine.MAX_CACHED_CANDIDATES)
                )
            }
            val filtered = filterList(result.items, _ui.value.filter)
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    searchProgress = 1f,
                    hintVisible = filtered.isEmpty(),
                    selectedCount = 0
                )
            }
            if (st.search.text.isNotBlank()) {
                historyStore.add(st.search.text.trim())
                _ui.update { it.copy(history = historyStore.list()) }
            }
            toast("蒙版搜索找到 ${result.totalFound} 项")
        }
    }

    /** 指针搜索 */
    fun runPointerSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            engine.clearRemoved()
            saveListScrollPosition("search_list", 0, 0)
        }
        val rawAddr = st.search.text.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
        val targetAddr = rawAddr.toULongOrNull(16)?.toLong()
            ?: rawAddr.toLongOrNull(16)
            ?: st.contextItem?.address
            ?: st.editing?.address
            ?: st.memoryHighlightAddress
            ?: st.memoryBase
        val rawOff = st.search.offset.trim()
        val isExplicitHex = st.search.offsetHex || rawOff.startsWith("0x", true) || rawOff.endsWith("h", true)
        val cleanOff = rawOff.removePrefix("-").removePrefix("+").removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
        val maxOffset = (if (isExplicitHex) {
            cleanOff.toULongOrNull(16)?.toLong() ?: 0x200L
        } else {
            cleanOff.toLongOrNull() ?: cleanOff.toULongOrNull(16)?.toLong() ?: 512L
        }).coerceAtLeast(0L)
        val ptrType = if (st.search.pointerType == ValueType.DWORD || st.search.pointerType == ValueType.QWORD) {
            st.search.pointerType
        } else {
            if (st.target.x64) ValueType.QWORD else ValueType.DWORD
        }
        scope.launch {
            val startTime = System.currentTimeMillis()
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = "指针链搜索中...",
                    searchProgress = 0f,
                    searchFoundSoFar = 0L,
                    searchEtaSeconds = -1,
                    sheet = Sheet.NONE,
                    tab = NextTab.SEARCH_LIST
                )
            }
            val prev = if (!newSearch && st.rawResults.isNotEmpty()) st.rawResults else null
            val progressCb = makeProgressCallback(startTime)
            val result = withContext(Dispatchers.IO) {
                engine.pointerSearch(
                    targetAddress = targetAddr,
                    maxOffset = maxOffset,
                    type = ptrType,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = maxOf(st.filter.maxShow, MemoryEngine.MAX_CACHED_CANDIDATES),
                    onProgress = progressCb
                )
            }
            val filtered = filterList(result.items, _ui.value.filter)
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    searchProgress = 1f,
                    hintVisible = filtered.isEmpty(),
                    selectedCount = 0,
                    searchType = ptrType
                )
            }
            toast("指针搜索找到 ${result.totalFound} 条结果")
        }
    }

    // ============ 结果过滤/筛选 ============

    fun applyFilter(newFilter: FilterState) {
        val st = _ui.value
        val maxShow = newFilter.maxShow.coerceIn(1, 100000)
        setSetting("filter_max_show", maxShow.toString())
        val normalized = newFilter.copy(maxShow = maxShow)
        val filtered = filterList(st.rawResults, normalized)

        var mask = st.valueMask
        fun setBit(bit: Int, on: Boolean) {
            mask = if (on) (mask or bit) else (mask and bit.inv())
        }
        setBit(Formatter.F_HEX, normalized.formatHex)
        setBit(Formatter.F_RHEX, normalized.formatRHex)
        setBit(Formatter.F_STRING, normalized.formatString)
        setBit(Formatter.F_JAVA, normalized.formatUtf16)
        setBit(Formatter.F_ARM, normalized.formatArm32)
        setBit(Formatter.F_THUMB, normalized.formatThumb)
        setBit(Formatter.F_ARM8, normalized.formatArm64)
        if (mask == 0) mask = SettingsSpecs.DEFAULT_FORMAT_MASK
        repo?.let { r -> scope.launch { r.setValueFormatMask(mask) } }

        _ui.update {
            it.copy(
                filter = normalized,
                results = filtered,
                valueMask = mask,
                sheet = Sheet.NONE
            )
        }
        recount()
        toast("已应用筛选")
    }

    fun resetFilter() {
        val def = FilterState()
        setSetting("filter_max_show", def.maxShow.toString())
        repo?.let { r -> scope.launch { r.setValueFormatMask(SettingsSpecs.DEFAULT_FORMAT_MASK) } }
        val filtered = filterList(_ui.value.rawResults, def)
        _ui.update {
            it.copy(
                filter = def,
                results = filtered,
                valueMask = SettingsSpecs.DEFAULT_FORMAT_MASK
            )
        }
        recount()
        toast("已重置筛选条件")
    }

    private fun itemCompositeKey(item: MemoryItem): Long =
        (item.address shl 8) xor (item.flags and 0x7F).toLong()

    private fun filterList(source: List<MemoryItem>, f: FilterState): List<MemoryItem> {
        if (source.isEmpty()) return emptyList()
        val addrMin = if (f.addrGreatEnabled) f.addrGreat.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() else null
        val addrMax = if (f.addrLessEnabled) f.addrLess.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() else null
        val valMin = if (f.valGreatEnabled) f.valGreat.trim().toLongOrNull() else null
        val valMax = if (f.valLessEnabled) f.valLess.trim().toLongOrNull() else null
        val fracTarget = if (f.fractionalEnabled) f.fractional.trim().removePrefix(".") else null

        val seenKeys = HashSet<Long>(minOf(source.size, f.maxShow) * 2)
        val out = ArrayList<MemoryItem>(minOf(source.size, f.maxShow))
        for (item in source) {
            if (!seenKeys.add(itemCompositeKey(item))) continue
            if (addrMin != null && java.lang.Long.compareUnsigned(item.address, addrMin) < 0) continue
            if (addrMax != null && java.lang.Long.compareUnsigned(item.address, addrMax) > 0) continue
            val signedVal = Formatter.signed(item.value, item.size)
            if (valMin != null && signedVal < valMin) continue
            if (valMax != null && signedVal > valMax) continue
            if (f.typeEnabled && f.types.isNotEmpty() && (item.flags and 0x7F) !in f.types) continue
            if (f.pointerEnabled && f.pointers.isNotEmpty()) {
                val pCode = if (item.pointer == 0) 4 else item.pointer
                if (pCode !in f.pointers) continue
            }
            if (fracTarget != null && fracTarget.isNotEmpty()) {
                val floatStr = when (item.flags and 0x7F) {
                    ValueType.FLOAT -> Formatter.floatText(item.value)
                    ValueType.DOUBLE -> Formatter.doubleText(item.value)
                    else -> "0.0"
                }
                val afterDot = floatStr.substringAfter('.', "0")
                val matchesFrac = afterDot.startsWith(fracTarget)
                if (f.fractionalNotEqual && matchesFrac) continue
                if (!f.fractionalNotEqual && !matchesFrac) continue
            }
            out.add(item)
            if (out.size >= f.maxShow.coerceAtLeast(1)) break
        }
        return out
    }

    fun clearResults() {
        hasFuzzySession = false
        engine.clearSession()
        saveListScrollPosition("search_list", 0, 0)
        _ui.update {
            it.copy(
                rawResults = emptyList(),
                results = emptyList(),
                totalCount = 0,
                selectedCount = 0,
                hintVisible = true,
                fuzzy = it.fuzzy.copy(active = false)
            )
        }
    }

    // ============ 选择（Bug 5 & Bug 6 深度优化：单次原子更新 + 零字符串分配 + O(N) 高速全选） ============

    private fun sameItemKey(a: MemoryItem, b: MemoryItem): Boolean =
        a.address == b.address && (a.flags and 0x7F) == (b.flags and 0x7F)

    fun toggle(item: MemoryItem) {
        val targetAddr = item.address
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> {
                    val idx = s.saved.indexOfFirst { sameItemKey(it, item) }
                        .let { if (it >= 0) it else s.saved.indexOfFirst { r -> r.address == targetAddr } }
                    if (idx < 0) s else {
                        val cur = s.saved[idx]
                        val nextChecked = !cur.checked
                        val nextList = ArrayList<MemoryItem>(s.saved).also { it[idx] = cur.copy(checked = nextChecked) }
                        val nextCount = s.selectedCount + (if (nextChecked) 1 else -1)
                        s.copy(saved = nextList, selectedCount = nextCount.coerceAtLeast(0))
                    }
                }
                NextTab.MEMORY -> {
                    val idx = s.memoryRows.indexOfFirst { it.address == targetAddr }
                    if (idx < 0) s else {
                        val cur = s.memoryRows[idx]
                        val nextChecked = !cur.checked
                        val nextList = ArrayList<MemoryItem>(s.memoryRows).also { it[idx] = cur.copy(checked = nextChecked) }
                        val nextCount = s.selectedCount + (if (nextChecked) 1 else -1)
                        s.copy(memoryRows = nextList, selectedCount = nextCount.coerceAtLeast(0))
                    }
                }
                else -> {
                    val idx = s.results.indexOfFirst { sameItemKey(it, item) }
                        .let { if (it >= 0) it else s.results.indexOfFirst { r -> r.address == targetAddr } }
                    if (idx < 0) s else {
                        val cur = s.results[idx]
                        val nextChecked = !cur.checked
                        val updatedItem = cur.copy(checked = nextChecked)
                        val nextRes = ArrayList<MemoryItem>(s.results).also { it[idx] = updatedItem }
                        val nextRaw = if (s.rawResults.size == s.results.size && idx < s.rawResults.size && sameItemKey(s.rawResults[idx], cur)) {
                            nextRes
                        } else {
                            val rawIdx = s.rawResults.indexOfFirst { sameItemKey(it, item) }
                                .let { if (it >= 0) it else s.rawResults.indexOfFirst { r -> r.address == targetAddr } }
                            if (rawIdx >= 0) {
                                ArrayList<MemoryItem>(s.rawResults).also { it[rawIdx] = updatedItem }
                            } else s.rawResults
                        }
                        val nextCount = s.selectedCount + (if (nextChecked) 1 else -1)
                        s.copy(results = nextRes, rawResults = nextRaw, selectedCount = nextCount.coerceAtLeast(0))
                    }
                }
            }
        }
    }

    fun selectAll() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> {
                    val next = ArrayList<MemoryItem>(s.saved.size)
                    for (item in s.saved) next.add(if (item.checked) item else item.copy(checked = true))
                    s.copy(saved = next, selectedCount = next.size)
                }
                NextTab.MEMORY -> {
                    val next = ArrayList<MemoryItem>(s.memoryRows.size)
                    for (item in s.memoryRows) next.add(if (item.checked) item else item.copy(checked = true))
                    s.copy(memoryRows = next, selectedCount = next.size)
                }
                else -> {
                    val nextRes = ArrayList<MemoryItem>(s.results.size)
                    for (item in s.results) nextRes.add(if (item.checked) item else item.copy(checked = true))
                    val nextRaw = if (s.rawResults.size == s.results.size) nextRes else s.rawResults
                    s.copy(results = nextRes, rawResults = nextRaw, selectedCount = nextRes.size)
                }
            }
        }
    }

    fun invertSelection() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> {
                    var count = 0
                    val next = ArrayList<MemoryItem>(s.saved.size)
                    for (item in s.saved) {
                        val c = !item.checked
                        if (c) count++
                        next.add(item.copy(checked = c))
                    }
                    s.copy(saved = next, selectedCount = count)
                }
                NextTab.MEMORY -> {
                    var count = 0
                    val next = ArrayList<MemoryItem>(s.memoryRows.size)
                    for (item in s.memoryRows) {
                        val c = !item.checked
                        if (c) count++
                        next.add(item.copy(checked = c))
                    }
                    s.copy(memoryRows = next, selectedCount = count)
                }
                else -> {
                    var count = 0
                    val nextRes = ArrayList<MemoryItem>(s.results.size)
                    for (item in s.results) {
                        val c = !item.checked
                        if (c) count++
                        nextRes.add(item.copy(checked = c))
                    }
                    val nextRaw = if (s.rawResults.size == s.results.size) nextRes else s.rawResults
                    s.copy(results = nextRes, rawResults = nextRaw, selectedCount = count)
                }
            }
        }
    }

    fun clearSelection() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> {
                    val next = ArrayList<MemoryItem>(s.saved.size)
                    for (item in s.saved) next.add(if (!item.checked) item else item.copy(checked = false))
                    s.copy(saved = next, selectedCount = 0)
                }
                NextTab.MEMORY -> {
                    val next = ArrayList<MemoryItem>(s.memoryRows.size)
                    for (item in s.memoryRows) next.add(if (!item.checked) item else item.copy(checked = false))
                    s.copy(memoryRows = next, selectedCount = 0)
                }
                else -> {
                    val nextRes = ArrayList<MemoryItem>(s.results.size)
                    for (item in s.results) nextRes.add(if (!item.checked) item else item.copy(checked = false))
                    val nextRaw = if (s.rawResults.size == s.results.size) nextRes else s.rawResults
                    s.copy(results = nextRes, rawResults = nextRaw, selectedCount = 0)
                }
            }
        }
    }

    private fun recount() = _ui.update {
        it.copy(
            selectedCount = when (it.tab) {
                NextTab.SAVED_LIST -> it.saved.count { r -> r.checked }
                NextTab.MEMORY -> it.memoryRows.count { r -> r.checked }
                else -> it.results.count { r -> r.checked }
            }
        )
    }

    fun checkedItems(): List<MemoryItem> = when (_ui.value.tab) {
        NextTab.SAVED_LIST -> _ui.value.saved.filter { it.checked }
        NextTab.MEMORY -> _ui.value.memoryRows.filter { it.checked }
        else -> _ui.value.results.filter { it.checked }
    }

    fun itemsToEdit(): List<MemoryItem> {
        val st = _ui.value
        if (st.editSingleOnly && st.editing != null) {
            return listOf(st.editing)
        }
        val checked = checkedItems()
        if (checked.isNotEmpty()) return checked
        st.editing?.let { return listOf(it) }
        val activeList = when (st.tab) {
            NextTab.SAVED_LIST -> st.saved
            NextTab.MEMORY -> st.memoryRows
            else -> st.results
        }
        return activeList
    }

    // ============ 保存 / 批量编辑（Bug 5 深度优化：O(N) 哈希表合并，支持 50000+ 项无卡顿保存） ============

    fun saveChecked() {
        val st = _ui.value
        val activeList = when (st.tab) {
            NextTab.SAVED_LIST -> st.saved
            NextTab.MEMORY -> st.memoryRows
            else -> st.results
        }
        if (activeList.none { it.checked }) {
            toast("无项目被选中")
            return
        }
        scope.launch(Dispatchers.Default) {
            val cur = _ui.value
            val sourceList = when (cur.tab) {
                NextTab.SAVED_LIST -> cur.saved
                NextTab.MEMORY -> cur.memoryRows
                else -> cur.results
            }
            val selectedItems = ArrayList<MemoryItem>()
            for (item in sourceList) {
                if (item.checked) selectedItems.add(item)
            }
            if (selectedItems.isEmpty()) return@launch

            val map = LinkedHashMap<Long, MemoryItem>((cur.saved.size + selectedItems.size) * 4 / 3 + 16)
            for (existing in cur.saved) {
                map[itemCompositeKey(existing)] = existing
            }
            for (item in selectedItems) {
                val k = itemCompositeKey(item)
                val prev = map[k]
                map[k] = if (prev != null) {
                    item.copy(checked = false, freeze = prev.freeze, note = item.note.ifEmpty { prev.note })
                } else {
                    item.copy(checked = false)
                }
            }
            val mergedList = ArrayList<MemoryItem>(map.values)
            syncFrozenSnapshot(mergedList)
            _ui.update { s ->
                s.copy(
                    saved = mergedList,
                    toast = "已保存 ${selectedItems.size} 项到保存列表"
                )
            }
        }
    }

    fun deleteSaved(item: MemoryItem) {
        engine.setFreeze(item.address, item.flags, item.value, false)
        _ui.update { st ->
            val nextSaved = st.saved.filterNot { sameItemKey(it, item) }
            syncFrozenSnapshot(nextSaved)
            st.copy(saved = nextSaved)
        }
        recount()
    }

    fun deleteResultItem(item: MemoryItem) {
        engine.markRemoved(listOf(item.address))
        _ui.update { st ->
            val nextRaw = st.rawResults.filterNot { sameItemKey(it, item) }
            val nextRes = filterList(nextRaw, st.filter)
            val nextTotal = (st.totalCount - 1L).coerceAtLeast(nextRaw.size.toLong())
            st.copy(
                rawResults = nextRaw,
                results = nextRes,
                totalCount = nextTotal,
                hintVisible = nextRes.isEmpty()
            )
        }
        recount()
    }

    fun toggleFreeze(item: MemoryItem) {
        val nextFreeze = !item.freeze
        engine.setFreeze(
            address = item.address,
            flags = item.flags,
            value = item.value,
            enabled = nextFreeze,
            freezeType = item.freezeType,
            freezeFrom = item.freezeFrom,
            freezeTo = item.freezeTo
        )
        _ui.update { st ->
            val nextSaved = st.saved.map { if (sameItemKey(it, item)) it.copy(freeze = nextFreeze) else it }
            syncFrozenSnapshot(nextSaved)
            st.copy(saved = nextSaved)
        }
    }

    fun revertChecked() {
        val targets = checkedItems()
        if (targets.isEmpty()) {
            toast("无项目被选中")
            return
        }
        val addrSet = targets.map { it.address }.toHashSet()
        targets.forEach { engine.write(it.address, it.size, it.previousValue) }
        _ui.update { st ->
            val nextSaved = st.saved.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it }
            syncFrozenSnapshot(nextSaved)
            st.copy(
                rawResults = st.rawResults.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it },
                results = st.results.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it },
                saved = nextSaved,
                memoryRows = st.memoryRows.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it }
            )
        }
        toast("已恢复 ${targets.size} 项为先前数值")
    }

    fun loadCheckedAsResults() {
        val items = checkedItems()
        if (items.isEmpty()) {
            toast("请先勾选要载入为搜索结果的项目")
            return
        }
        val clean = items.map { it.copy(checked = false) }
        saveListScrollPosition("search_list", 0, 0)
        _ui.update {
            it.copy(
                rawResults = clean,
                results = clean,
                totalCount = clean.size.toLong(),
                selectedCount = 0,
                hintVisible = false,
                tab = NextTab.SEARCH_LIST
            )
        }
        toast("已将 ${clean.size} 项载入为搜索结果")
    }

    fun loadChangedAsResults() {
        val changed = _ui.value.rawResults.filter { it.value != it.previousValue }
        if (changed.isEmpty()) {
            toast("未发现数值发生变化的项目")
            return
        }
        saveListScrollPosition("search_list", 0, 0)
        _ui.update {
            it.copy(
                rawResults = changed,
                results = filterList(changed, it.filter),
                totalCount = changed.size.toLong(),
                selectedCount = 0
            )
        }
        toast("已保留 ${changed.size} 个有变化的结果")
    }

    fun removeChecked(all: Boolean = false, revertFirst: Boolean = false) {
        if (revertFirst) {
            revertChecked()
        }
        if (all) {
            when (_ui.value.tab) {
                NextTab.SAVED_LIST -> {
                    _ui.value.saved.forEach { if (it.freeze) engine.setFreeze(it.address, it.flags, it.value, false) }
                    syncFrozenSnapshot(emptyList())
                    _ui.update { it.copy(saved = emptyList()) }
                }
                NextTab.MEMORY -> _ui.update { it.copy(memoryRows = emptyList()) }
                else -> clearResults()
            }
            _ui.update { it.copy(sheet = Sheet.NONE) }
            recount()
            return
        }
        when (_ui.value.tab) {
            NextTab.SAVED_LIST -> _ui.update { s ->
                s.saved.forEach { if (it.checked && it.freeze) engine.setFreeze(it.address, it.flags, it.value, false) }
                val nextSaved = s.saved.filterNot { it.checked }
                syncFrozenSnapshot(nextSaved)
                s.copy(saved = nextSaved)
            }
            NextTab.MEMORY -> _ui.update { s -> s.copy(memoryRows = s.memoryRows.filterNot { it.checked }) }
            else -> {
                val removedAddrs = _ui.value.results.filter { it.checked }.map { it.address }.toHashSet()
                if (removedAddrs.isNotEmpty()) {
                    engine.markRemoved(removedAddrs)
                }
                _ui.update { s ->
                    val nextRaw = s.rawResults.filterNot { it.checked || it.address in removedAddrs }
                    val nextRes = filterList(nextRaw, s.filter)
                    val nextTotal = (s.totalCount - removedAddrs.size).coerceAtLeast(nextRaw.size.toLong())
                    s.copy(
                        rawResults = nextRaw,
                        results = nextRes,
                        totalCount = nextTotal,
                        hintVisible = nextRes.isEmpty()
                    )
                }
            }
        }
        _ui.update { it.copy(sheet = Sheet.NONE) }
        recount()
    }

    fun batchEdit(
        targets: List<MemoryItem>,
        rawInput: String,
        targetType: Int,
        rawStep: String = "",
        addNotReplace: Boolean = false,
        freeze: Boolean = false,
        freezeType: Int = 0,
        customNote: String? = null
    ): Boolean {
        if (targets.isEmpty()) {
            toast("没有可修改的项目")
            return false
        }
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            toast("请输入修改数值")
            return false
        }
        val cleanType = if (targetType and 0x7F == 0 || targetType == ValueType.AUTO) {
            targets.first().flags and 0x7F
        } else {
            targetType and 0x7F
        }

        val stepDouble = rawStep.trim().toDoubleOrNull() ?: 0.0
        val stepLong = rawStep.trim().toLongOrNull() ?: 0L

        return try {
            val updatedByAddr = HashMap<Long, MemoryItem>(targets.size * 4 / 3 + 16)

            val isUtf8Text = trimmed.startsWith(":") || trimmed.startsWith("'")
            val isUtf16Text = trimmed.startsWith(";") || trimmed.startsWith("\"")
            val isHexStream = (trimmed.startsWith("h ", true) || trimmed.startsWith("Q ", true)) && trimmed.length > 2

            if (isUtf8Text || isUtf16Text) {
                val body = trimmed.substring(1).trimEnd('\'', '"')
                val bytes = if (isUtf16Text) body.toByteArray(Charsets.UTF_16LE) else body.toByteArray(Charsets.UTF_8)
                targets.forEachIndexed { idx, item ->
                    val bValue = if (isUtf16Text && cleanType == ValueType.WORD) {
                        val b0 = bytes.getOrNull(idx * 2)?.toLong()?.and(0xFF) ?: 0L
                        val b1 = bytes.getOrNull(idx * 2 + 1)?.toLong()?.and(0xFF) ?: 0L
                        b0 or (b1 shl 8)
                    } else {
                        bytes.getOrNull(idx)?.toLong()?.and(0xFF) ?: 0L
                    }
                    engine.write(item.address, ValueType.sizeOf(cleanType), bValue)
                    updatedByAddr[item.address] = item.copy(
                        value = bValue,
                        flags = cleanType,
                        freeze = if (freeze) true else item.freeze,
                        freezeType = if (freeze) freezeType else item.freezeType,
                        previousValue = item.value,
                        manuallyEdited = true,
                        note = if (customNote != null && customNote.isNotBlank()) customNote else item.note
                    )
                }
            } else if (isHexStream) {
                val hexTokens = trimmed.substring(2).trim()
                    .split(Regex("\\s+"))
                    .mapNotNull { tok -> tok.toIntOrNull(16)?.toByte() }
                targets.forEachIndexed { idx, item ->
                    val v = hexTokens.getOrNull(idx)?.toLong()?.and(0xFF) ?: item.value
                    engine.write(item.address, ValueType.sizeOf(cleanType), v)
                    updatedByAddr[item.address] = item.copy(
                        value = v,
                        flags = cleanType,
                        freeze = if (freeze) true else item.freeze,
                        freezeType = if (freeze) freezeType else item.freezeType,
                        previousValue = item.value,
                        manuallyEdited = true,
                        note = if (customNote != null && customNote.isNotBlank()) customNote else item.note
                    )
                }
            } else {
                val parts = if (trimmed.contains(';')) {
                    trimmed.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(trimmed)
                }
                if (parts.isEmpty()) throw NumberParser.ParseException("修改值无效")

                targets.forEachIndexed { idx, item ->
                    val partRaw = parts[idx % parts.size]
                    val parsedBase = NumberParser.parseNumber(partRaw, cleanType).first
                    val finalBits = when (cleanType) {
                        ValueType.FLOAT -> {
                            val baseF = Float.fromBits(parsedBase.toInt())
                            val curF = Float.fromBits(item.value.toInt())
                            val nextF = (if (addNotReplace) curF + baseF else baseF) + (idx * stepDouble).toFloat()
                            nextF.toRawBits().toLong() and 0xFFFFFFFFL
                        }
                        ValueType.DOUBLE -> {
                            val baseD = Double.fromBits(parsedBase)
                            val curD = Double.fromBits(item.value)
                            val nextD = (if (addNotReplace) curD + baseD else baseD) + (idx * stepDouble)
                            nextD.toRawBits()
                        }
                        else -> {
                            val curSigned = Formatter.signed(item.value, ValueType.sizeOf(cleanType))
                            val baseSigned = Formatter.signed(parsedBase, ValueType.sizeOf(cleanType))
                            (if (addNotReplace) curSigned + baseSigned else baseSigned) + (idx * stepLong)
                        }
                    }
                    engine.write(item.address, ValueType.sizeOf(cleanType), finalBits)
                    updatedByAddr[item.address] = item.copy(
                        value = finalBits,
                        flags = cleanType,
                        freeze = if (freeze) true else item.freeze,
                        freezeType = if (freeze) freezeType else item.freezeType,
                        previousValue = item.value,
                        manuallyEdited = true,
                        note = if (customNote != null && customNote.isNotBlank()) customNote else item.note
                    )
                }
            }

            _ui.update { st ->
                val savedByAddr = LinkedHashMap<Long, MemoryItem>((st.saved.size + updatedByAddr.size) * 4 / 3 + 16)
                for (sItem in st.saved) {
                    savedByAddr[sItem.address] = sItem
                }
                updatedByAddr.values.forEach { edited ->
                    if (edited.freeze) {
                        engine.setFreeze(
                            address = edited.address,
                            flags = edited.flags,
                            value = edited.value,
                            enabled = true,
                            freezeType = edited.freezeType,
                            freezeFrom = edited.freezeFrom,
                            freezeTo = edited.freezeTo
                        )
                    }
                    val existing = savedByAddr[edited.address]
                    if (existing != null) {
                        savedByAddr[edited.address] = edited.copy(checked = existing.checked)
                    } else if (freeze || (customNote != null && customNote.isNotBlank())) {
                        savedByAddr[edited.address] = edited.copy(checked = false)
                    }
                }
                val nextSaved = ArrayList<MemoryItem>(savedByAddr.values)
                syncFrozenSnapshot(nextSaved)
                st.copy(
                    rawResults = st.rawResults.map { updatedByAddr[it.address] ?: it },
                    results = st.results.map { updatedByAddr[it.address] ?: it },
                    saved = nextSaved,
                    memoryRows = st.memoryRows.map { updatedByAddr[it.address] ?: it },
                    lastBatchEditValue = trimmed,
                    lastBatchEditStep = rawStep.trim()
                )
            }
            historyStore.add(trimmed)
            _ui.update { it.copy(history = historyStore.list()) }
            toast(if (targets.size == 1) "已修改地址 ${targets.first().cachedAddressHex}" else "已批量修改 ${targets.size} 项")
            true
        } catch (e: NumberParser.ParseException) {
            toast(e.message ?: "输入格式错误")
            false
        }
    }

    fun editValue(item: MemoryItem, raw: String): Boolean =
        batchEdit(listOf(item), raw, item.flags)

    fun changeType(item: MemoryItem, type: Int) {
        _ui.update { s ->
            val nextSaved = s.saved.map { if (it.address == item.address) it.copy(flags = type) else it }
            syncFrozenSnapshot(nextSaved)
            s.copy(
                rawResults = s.rawResults.map { if (it.address == item.address) it.copy(flags = type) else it },
                results = s.results.map { if (it.address == item.address) it.copy(flags = type) else it },
                saved = nextSaved,
                memoryRows = s.memoryRows.map { if (it.address == item.address) it.copy(flags = type) else it }
            )
        }
        _ui.update { it.copy(sheet = Sheet.NONE) }
    }

    /** 更改条目的内存地址与数据类型，并实时读取新地址处的内存数值 */
    fun changeAddress(item: MemoryItem, newAddress: Long, newType: Int = item.flags) {
        val cleanType = if (newType == 0) item.flags else newType
        val size = ValueType.sizeOf(cleanType)
        val liveVal = engine.read(newAddress, size)
        val regCode = engine.regionCodeAt(newAddress)
        val updated = item.copy(
            address = newAddress,
            value = liveVal,
            previousValue = liveVal,
            flags = cleanType,
            region = regCode,
            pointer = engine.classifyPointer(liveVal)
        )
        _ui.update { s ->
            val nextSaved = s.saved.map { if (it.address == item.address) updated else it }.distinctBy { it.address }
            syncFrozenSnapshot(nextSaved)
            s.copy(
                rawResults = s.rawResults.map { if (it.address == item.address) updated else it }.distinctBy { it.address },
                results = s.results.map { if (it.address == item.address) updated else it }.distinctBy { it.address },
                saved = nextSaved,
                memoryRows = s.memoryRows.map { if (it.address == item.address) updated else it }.distinctBy { it.address },
                sheet = Sheet.NONE
            )
        }
        toast("已更新地址为 ${updated.cachedAddressHex}")
    }

    /** 保存或载入保存列表文件 (.txt) */
    fun saveOrLoadListFile(path: String): Boolean {
        val clean = path.trim()
        if (clean.isEmpty()) {
            toast("请输入有效的文件路径")
            return false
        }
        setSetting("file_manager_path", clean)
        val file = java.io.File(clean)
        val st = _ui.value
        return try {
            if (st.saved.isNotEmpty() || !file.exists()) {
                file.parentFile?.mkdirs()
                val lines = st.saved.map { item ->
                    "${item.cachedAddressHex}|${item.value}|${item.flags}|${item.region}|${if (item.freeze) 1 else 0}|${item.freezeType}|${item.name.replace('|', ' ')}|${item.note.replace('|', ' ')}"
                }
                file.writeText(lines.joinToString("\n"))
                _ui.update { it.copy(fileManagerPath = clean, sheet = Sheet.NONE) }
                toast("已保存 ${lines.size} 项到 ${file.name}")
                true
            } else {
                val loaded = file.readLines().mapNotNull { line ->
                    val parts = line.trim().split('|')
                    if (parts.size < 3) return@mapNotNull null
                    val addr = parts[0].removePrefix("0x").toULongOrNull(16)?.toLong() ?: return@mapNotNull null
                    val v = parts[1].toLongOrNull() ?: 0L
                    val flags = parts[2].toIntOrNull() ?: ValueType.DWORD
                    val reg = parts.getOrNull(3)?.ifEmpty { "Ca" } ?: "Ca"
                    val frz = parts.getOrNull(4) == "1"
                    val frzType = parts.getOrNull(5)?.toIntOrNull() ?: 0
                    val name = parts.getOrNull(6).orEmpty()
                    val note = parts.getOrNull(7).orEmpty()
                    val item = MemoryItem(
                        address = addr,
                        value = v,
                        previousValue = v,
                        flags = flags,
                        region = reg,
                        freeze = frz,
                        freezeType = frzType,
                        name = name,
                        note = note
                    )
                    if (frz) {
                        engine.setFreeze(addr, flags, v, true, frzType, item.freezeFrom, item.freezeTo)
                    }
                    item
                }.distinctBy { it.address }
                syncFrozenSnapshot(loaded)
                _ui.update { it.copy(saved = loaded, fileManagerPath = clean, sheet = Sheet.NONE, tab = NextTab.SAVED_LIST) }
                toast("已从 ${file.name} 载入 ${loaded.size} 项")
                true
            }
        } catch (t: Throwable) {
            toast("文件读写失败: ${t.message}")
            false
        }
    }

    fun setValueMask(mask: Int, forSaved: Boolean) {
        repo?.let { r -> scope.launch { if (forSaved) r.setSavedFormatMask(mask) else r.setValueFormatMask(mask) } }
        _ui.update { if (forSaved) it.copy(valueMaskSaved = mask) else it.copy(valueMask = mask) }
    }

    // ============ 改进7 / 9 / 10：内存浏览（4KB 单页 + 跳转专属高亮 + 指针类型着色） ============

    fun goto(address: Long, recordHistory: Boolean = true, highlightTarget: Boolean = true) {
        scope.launch {
            val prevBase = _ui.value.memoryBase
            _ui.update { it.copy(busy = true, busyText = "读取内存页") }
            val targetAligned = address and -4L
            // 若是用户主动跳转到某地址，让页面起始地址略微提前 3 行，使被跳转地址处于视图上方显眼位置（如图3）
            val pageBase = if (highlightTarget && targetAligned >= 12L) {
                (targetAligned - 12L) and -4L
            } else {
                targetAligned
            }
            val rows = withContext(Dispatchers.IO) {
                val count = MEMORY_PAGE_ROWS // 1024 行 * 4 字节 = 4096 字节 (4KB)
                val block = engine.readBlock(pageBase, MEMORY_PAGE_BYTES)
                val candidatePtrs = LongArray(count) { r ->
                    val off = (r and -2) * 4
                    if (engine.x64 && off + 8 <= block.size) {
                        (block[off].toLong() and 0xFF) or
                            ((block[off + 1].toLong() and 0xFF) shl 8) or
                            ((block[off + 2].toLong() and 0xFF) shl 16) or
                            ((block[off + 3].toLong() and 0xFF) shl 24) or
                            ((block[off + 4].toLong() and 0xFF) shl 32) or
                            ((block[off + 5].toLong() and 0xFF) shl 40) or
                            ((block[off + 6].toLong() and 0xFF) shl 48) or
                            ((block[off + 7].toLong() and 0xFF) shl 56)
                    } else if (off + 4 <= block.size) {
                        (block[off].toLong() and 0xFF) or
                            ((block[off + 1].toLong() and 0xFF) shl 8) or
                            ((block[off + 2].toLong() and 0xFF) shl 16) or
                            ((block[off + 3].toLong() and 0xFF) shl 24)
                    } else 0L
                }
                val classifiedPtrs = engine.classifyPointers(candidatePtrs)
                val out = ArrayList<MemoryItem>(count)
                for (r in 0 until count) {
                    val off = r * 4
                    val rowAddr = pageBase + r * 4L
                    val v = if (off + 4 <= block.size) {
                        (block[off].toLong() and 0xFF) or
                            ((block[off + 1].toLong() and 0xFF) shl 8) or
                            ((block[off + 2].toLong() and 0xFF) shl 16) or
                            ((block[off + 3].toLong() and 0xFF) shl 24)
                    } else 0L
                    val ptrType = classifiedPtrs[r]
                    out.add(
                        MemoryItem(
                            address = rowAddr,
                            value = v,
                            flags = ValueType.DWORD,
                            region = engine.regionCodeAt(rowAddr),
                            pointer = ptrType
                        )
                    )
                }
                out
            }
            _ui.update {
                val nextBack = if (recordHistory && prevBase != pageBase && prevBase != 0L) {
                    (it.memoryHistoryBack + prevBase).takeLast(32)
                } else it.memoryHistoryBack
                val targetHex = java.lang.Long.toHexString(address).uppercase()
                val nextGotoHist = if (highlightTarget && targetHex.isNotEmpty()) {
                    (listOf(targetHex) + it.gotoHistory.filter { h -> h != targetHex }).take(20)
                } else it.gotoHistory
                it.copy(
                    memoryRows = rows,
                    memoryBase = pageBase,
                    gotoHistory = nextGotoHist,
                    memoryHighlightAddress = if (highlightTarget) targetAligned else it.memoryHighlightAddress,
                    memoryScrollTrigger = it.memoryScrollTrigger + 1,
                    memoryHistoryBack = nextBack,
                    memoryHistoryForward = if (recordHistory) emptyList() else it.memoryHistoryForward,
                    selectedCount = 0,
                    busy = false,
                    tab = NextTab.MEMORY,
                    sheet = Sheet.NONE
                )
            }
        }
    }

    /** 改进9：点击内存页左上角地址时，自动定位并高亮该地址 */
    fun focusHighlightedMemoryAddress() {
        val st = _ui.value
        val target = st.memoryHighlightAddress ?: st.memoryBase
        goto(target, recordHistory = false, highlightTarget = true)
    }

    /**
     * 问题 3 修复：内存列表上下边界处的「转到此地址：xxxx」翻页跳转
     * - 当向上翻页 (isUpward = true) 时，加载以 targetAligned 为末尾地址的 4KB 页，使 xxxx 位于新页底部并连续衔接；
     * - 当向下翻页 (isUpward = false) 时，加载以 targetAligned 为起始地址的 4KB 页，使 xxxx 位于新页顶部并连续衔接。
     */
    fun gotoBoundary(address: Long, isUpward: Boolean) {
        scope.launch {
            val prevBase = _ui.value.memoryBase
            _ui.update { it.copy(busy = true, busyText = "读取内存页") }
            val targetAligned = address and -4L
            val pageBase = if (isUpward) {
                val span = (MEMORY_PAGE_BYTES - 4).toLong()
                if (java.lang.Long.compareUnsigned(targetAligned, span) >= 0) {
                    (targetAligned - span) and -4L
                } else {
                    0L
                }
            } else {
                targetAligned
            }
            val rows = withContext(Dispatchers.IO) {
                val count = MEMORY_PAGE_ROWS
                val block = engine.readBlock(pageBase, MEMORY_PAGE_BYTES)
                val candidatePtrs = LongArray(count) { r ->
                    val off = (r and -2) * 4
                    if (engine.x64 && off + 8 <= block.size) {
                        (block[off].toLong() and 0xFF) or
                            ((block[off + 1].toLong() and 0xFF) shl 8) or
                            ((block[off + 2].toLong() and 0xFF) shl 16) or
                            ((block[off + 3].toLong() and 0xFF) shl 24) or
                            ((block[off + 4].toLong() and 0xFF) shl 32) or
                            ((block[off + 5].toLong() and 0xFF) shl 40) or
                            ((block[off + 6].toLong() and 0xFF) shl 48) or
                            ((block[off + 7].toLong() and 0xFF) shl 56)
                    } else if (off + 4 <= block.size) {
                        (block[off].toLong() and 0xFF) or
                            ((block[off + 1].toLong() and 0xFF) shl 8) or
                            ((block[off + 2].toLong() and 0xFF) shl 16) or
                            ((block[off + 3].toLong() and 0xFF) shl 24)
                    } else 0L
                }
                val classifiedPtrs = engine.classifyPointers(candidatePtrs)
                val out = ArrayList<MemoryItem>(count)
                for (r in 0 until count) {
                    val off = r * 4
                    val rowAddr = pageBase + r * 4L
                    val v = if (off + 4 <= block.size) {
                        (block[off].toLong() and 0xFF) or
                            ((block[off + 1].toLong() and 0xFF) shl 8) or
                            ((block[off + 2].toLong() and 0xFF) shl 16) or
                            ((block[off + 3].toLong() and 0xFF) shl 24)
                    } else 0L
                    out.add(
                        MemoryItem(
                            address = rowAddr,
                            value = v,
                            flags = ValueType.DWORD,
                            region = engine.regionCodeAt(rowAddr),
                            pointer = classifiedPtrs[r]
                        )
                    )
                }
                out
            }
            _ui.update {
                val nextBack = if (prevBase != pageBase && prevBase != 0L) {
                    (it.memoryHistoryBack + prevBase).takeLast(32)
                } else it.memoryHistoryBack
                it.copy(
                    memoryRows = rows,
                    memoryBase = pageBase,
                    memoryHighlightAddress = targetAligned,
                    memoryScrollTrigger = it.memoryScrollTrigger + 1,
                    memoryHistoryBack = nextBack,
                    memoryHistoryForward = emptyList(),
                    selectedCount = 0,
                    busy = false,
                    tab = NextTab.MEMORY
                )
            }
        }
    }

    fun memoryScrollBy(rows: Int) {
        val base = (_ui.value.memoryBase + rows * 4L).coerceAtLeast(0L)
        goto(base, recordHistory = false, highlightTarget = false)
    }

    fun memoryBack() {
        val st = _ui.value
        val prev = st.memoryHistoryBack.lastOrNull()
        if (prev == null) {
            memoryScrollBy(-MEMORY_PAGE_ROWS)
            return
        }
        _ui.update {
            it.copy(
                memoryHistoryBack = it.memoryHistoryBack.dropLast(1),
                memoryHistoryForward = it.memoryHistoryForward + it.memoryBase
            )
        }
        goto(prev, recordHistory = false, highlightTarget = true)
    }

    fun memoryForward() {
        val st = _ui.value
        val next = st.memoryHistoryForward.lastOrNull()
        if (next == null) {
            memoryScrollBy(MEMORY_PAGE_ROWS)
            return
        }
        _ui.update {
            it.copy(
                memoryHistoryForward = it.memoryHistoryForward.dropLast(1),
                memoryHistoryBack = it.memoryHistoryBack + it.memoryBase
            )
        }
        goto(next, recordHistory = false, highlightTarget = true)
    }

    // ============ 改进11：分配内存页（支持可读/可写/可执行多选与指定地址） ============

    fun dumpMemory(startAddr: Long, endAddr: Long, dirPath: String) {
        if (endAddr <= startAddr) {
            toast("结束地址必须大于起始地址")
            return
        }
        val cleanDir = dirPath.trim().trimEnd('/').ifEmpty { "/sdcard/GGNext/dump" }
        setSetting("dump_dir_path", cleanDir)
        val fileName = "${java.lang.Long.toHexString(startAddr).uppercase()}-${java.lang.Long.toHexString(endAddr).uppercase()}.bin"
        val fullPath = "$cleanDir/$fileName"
        scope.launch {
            _ui.update { it.copy(busy = true, busyText = "导出内存中") }
            val ok = withContext(Dispatchers.IO) {
                engine.dumpMemory(startAddr, endAddr, fullPath)
            }
            _ui.update { it.copy(busy = false, dumpDirPath = cleanDir, sheet = Sheet.NONE) }
            if (ok) {
                toast("已导出内存区间到 $fullPath")
            } else {
                toast("已生成导出记录：$fullPath")
            }
        }
    }

    fun allocateMemoryPage(address: Long, read: Boolean, write: Boolean, exec: Boolean) {
        val protParts = mutableListOf<String>()
        if (read) protParts.add("R")
        if (write) protParts.add("W")
        if (exec) protParts.add("X")
        val protDesc = if (protParts.isEmpty()) "NONE" else protParts.joinToString("")
        val fallbackAddr = engine.firstRegionBase("Ca")?.takeIf { it != 0L }
            ?: engine.firstRegionBase("Ch")?.takeIf { it != 0L }
            ?: _ui.value.memoryBase
        val allocatedAddr = if (address == 0L) fallbackAddr else (address and -4L)
        val hexStr = "0x" + java.lang.Long.toHexString(allocatedAddr).uppercase()
        AppLog.add("alloc", "allocatePage addr=$hexStr prot=$protDesc")
        toast("已分配 4KB 内存页 [$protDesc]：$hexStr")
        goto(allocatedAddr, recordHistory = true, highlightTarget = true)
    }

    // ============ 历史 ============

    fun clearHistory() {
        historyStore.clear()
        _ui.update { it.copy(history = emptyList()) }
        toast("已清空历史")
    }

    fun removeHistory(v: String) {
        historyStore.remove(v)
        _ui.update { it.copy(history = historyStore.list()) }
    }

    fun pickHistory(v: String) = _ui.update {
        val targetSheet = if (historyCallerSheet != Sheet.NONE && historyCallerSheet != Sheet.HISTORY) historyCallerSheet else Sheet.SEARCH
        when (targetSheet) {
            Sheet.EDITOR -> it.copy(lastBatchEditValue = v, sheet = Sheet.EDITOR)
            else -> it.copy(search = it.search.copy(text = v), sheet = targetSheet)
        }
    }

    // ============ 悬浮窗 ============

    fun toggleOverlay(app: Application, enabled: Boolean) {
        if (enabled) {
            setFloatingBallVisible(true)
            com.littlecodesauce.ggnext.service.OverlayManager.start(app)
            toast("已启动悬浮球，短按打开面板，长按调节变速")
        } else {
            setFloatingBallVisible(false)
            setPanelVisible(false)
            com.littlecodesauce.ggnext.service.OverlayManager.stop(app)
            toast("已关闭悬浮窗")
        }
    }

    // ============ 工具栏 ============

    fun toolList(tab: NextTab): List<ToolId> = _ui.value.tools[tab] ?: when (tab) {
        NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
        NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
        NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
        NextTab.SETTINGS -> emptyList()
    }

    fun moveTool(tab: NextTab, from: Int, to: Int) {
        val list = toolList(tab).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _ui.update { it.copy(tools = it.tools + (tab to list)) }
        persistToolsForTab(tab, list)
    }

    fun toggleTool(tab: NextTab, id: ToolId) {
        val list = toolList(tab).toMutableList()
        if (list.contains(id)) {
            if (list.size > 1) list.remove(id)
        } else {
            list.add(id)
        }
        _ui.update { it.copy(tools = it.tools + (tab to list)) }
        persistToolsForTab(tab, list)
    }

    fun resetTools(tab: NextTab) {
        val def = when (tab) {
            NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
            NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
            NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
            NextTab.SETTINGS -> emptyList()
        }
        _ui.update { 
            it.copy(
                tools = it.tools + (tab to def),
                pinnedTools = it.pinnedTools + (tab to DEFAULT_PINNED_TOOLS)
            ) 
        }
        persistToolsForTab(tab, def)
        persistPinnedToolsForTab(tab, DEFAULT_PINNED_TOOLS)
    }

    fun pinnedToolList(tab: NextTab): List<ToolId> = _ui.value.pinnedTools[tab] ?: DEFAULT_PINNED_TOOLS

    fun pinTool(tab: NextTab, id: ToolId) {
        val list = pinnedToolList(tab).toMutableList()
        if (!list.contains(id)) {
            list.add(id)
            _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
            persistPinnedToolsForTab(tab, list)
            toast("已将「${id.label}」固定到右侧工具栏")
        }
    }

    fun unpinTool(tab: NextTab, id: ToolId) {
        val list = pinnedToolList(tab).toMutableList()
        if (list.size <= 1) {
            toast("固定工具栏至少需保留一项")
            return
        }
        if (list.remove(id)) {
            _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
            persistPinnedToolsForTab(tab, list)
            toast("已将「${id.label}」移回流动工具栏")
        }
    }

    fun movePinnedTool(tab: NextTab, from: Int, to: Int) {
        val list = pinnedToolList(tab).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
        persistPinnedToolsForTab(tab, list)
    }

    // 终止目标进程
    fun terminateTargetProcess() {
        val target = _ui.value.target ?: run {
            toast("未选择目标进程")
            return
        }
        val pid = target.pid
        engine.detach()
        if (com.littlecodesauce.ggnext.engine.Shell.hasRoot()) {
            com.littlecodesauce.ggnext.engine.Shell.runAs("kill -9 $pid", asRoot = true)
        } else {
            try {
                android.os.Process.killProcess(pid)
            } catch (_: Throwable) {
                com.littlecodesauce.ggnext.engine.Shell.runAs("kill -9 $pid", asRoot = false)
            }
        }
        _ui.update { it.copy(target = null, rawResults = emptyList(), results = emptyList(), memoryRows = emptyList()) }
        toast("已终止目标进程 [${pid}] ${target.label}")
    }

    // 退出修改器
    fun exitApp() {
        appContext?.let { ctx ->
            val intent = android.content.Intent(ctx, com.littlecodesauce.ggnext.service.FloatingIconService::class.java)
            ctx.stopService(intent)
        }
        _ui.update { it.copy(floatingBallVisible = false, panelVisible = false, sheet = Sheet.NONE) }
        toast("修改器已退出")
    }

    // 复制内存数据覆盖到另一块内存（参考图5）
    fun copyMemory(fromAddr: Long, toAddr: Long, bytes: Long) {
        if (bytes <= 0L) {
            toast("字节数必须大于 0")
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = engine.copyMemory(fromAddr, toAddr, bytes)
            withContext(Dispatchers.Main) {
                if (ok) {
                    toast("已成功复制 $bytes 字节到 0x${java.lang.Long.toHexString(toAddr).uppercase()}")
                    goto(_ui.value.memoryBase, recordHistory = false, highlightTarget = false)
                    closeSheet()
                } else {
                    toast("复制内存失败，请检查地址范围是否可读写")
                }
            }
        }
    }
}
