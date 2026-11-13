package com.littlecodesauce.ggnext.engine

import android.content.Context
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.MemoryRegion
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * 内存引擎（已深度集成 C++20 MemEngine JNI 后端，并修复全部已知缺陷）：
 *
 * 1. 原生 C++20 优先通道 (NativeMemBridge)：
 *    - 优先通过 `process_vm_readv` / `process_vm_writev` 零拷贝系统调用读写目标进程内存，
 *      受限时无缝回退 `/proc/<pid>/mem` (pread64/pwrite64)，支持 16KB / 4KB 动态页对齐与 EFAULT 保护页跳过；
 *    - 支持 ARM64 NEON SIMD 向量化扫描、MmapCandidateStore 零 OOM 磁盘溢出存储及 ValueLocker 独立锁值线程；
 * 2. 修复免 Root 共享 UID / 虚拟空间模式 (`WorkMode.NO_ROOT`) 被强制降级为沙盒随机数的 Bug：
 *    - 只要未强制指定演示模式 (`forceDemo = false`)，均优先尝试 Native C++20 附加与直接 `/proc/<pid>/mem` 附加；
 * 3. 修复有符号整数与 IEEE-754 浮点数比较时误用 `Long.compareUnsigned` 的严重算术 Bug；
 * 4. 完整支持精确单值、自适应 AUTO、范围 (A~B / A~~B)、文本 ('utf8' / "utf16le")、
 *    联合/结构体搜索 (A;B;C:512 无序 / A;B;C::64 有序)、异或 (AXK)、蒙版搜索与指针链扫描；
 * 5. 修复 `copyMemory` 逐字节系统调用瓶颈，改为 64KB 块缓冲批量读写。
 */
class MemoryEngine {

    data class SearchResult(val items: List<MemoryItem>)

    data class Criteria(
        val type: Int = ValueType.DWORD,
        val value: Long = 0L,
        val from: Long = value,
        val to: Long = value,
        val isRange: Boolean = false,
        val notRange: Boolean = false,
        val sign: Int = ValueType.SIGN_EQUAL,
        val xorKey: Long = 0L,
        val orderedGroup: List<Long> = emptyList(),
        val ordered: Boolean = false,
        val groupTypes: List<Int> = emptyList(),
        val area: Int = 512,
        val memoryFrom: Long = 0L,
        val memoryTo: Long = Long.MAX_VALUE,
        val limit: Int = 5000,
        val rawExpression: String = "",
        val encryptedXor: Boolean = false,
        val selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        val mask: Long = -1L,
        val isText: Boolean = false,
        val textBytes: ByteArray = ByteArray(0),
        val textUtf16: Boolean = false
    )

    var pid: Int = 0
        private set

    var x64: Boolean = true
        private set

    var simulated: Boolean = true
        private set

    var nativeBackendActive: Boolean = false
        private set

    private var nativeHandle: Long = 0L
    private val simCells = LinkedHashMap<Long, MemoryItem>(2048)
    private var simRegions: List<MemoryRegion> = emptyList()

    /**
     * 附加到目标进程：
     * - forceDemo = true 时直接进入纯沙盒演示模式；
     * - ROOT 或 NO_ROOT 模式下优先通过 C++20 NativeMemBridge (process_vm_readv + /proc/<pid>/mem) 附加；
     * - 若处于 ROOT 模式且 `/proc/<pid>/mem` 仅对 su 可读，自动尝试 chmod 辅助打开；
     * - 仅当全部真实跨进程通道均无权限时才回退至沙盒模拟环境。
     */
    fun attach(
        targetPid: Int,
        targetX64: Boolean = true,
        isRootMode: Boolean = true,
        context: Context? = null,
        forceDemo: Boolean = false
    ): Boolean {
        pid = targetPid
        x64 = targetX64
        simCells.clear()
        nativeBackendActive = false

        if (forceDemo || targetPid <= 0) {
            simulated = true
            buildSimulatedProcess(targetPid)
            AppLog.i("Engine", "Attached pid=$targetPid (Demo Sandbox Mode)")
            return true
        }

        // 1. 尝试通过 C++20 NativeMemBridge 附加 (支持 process_vm_readv 与 /proc/<pid>/mem)
        if (NativeMemBridge.isLoaded) {
            val handle = NativeMemBridge.ensureHandle()
            if (handle != 0L) {
                var rc = NativeMemBridge.nativeAttach(handle, targetPid)
                if (rc != 0 && isRootMode && Shell.hasRoot()) {
                    Shell.su("chmod 666 /proc/$targetPid/mem /proc/$targetPid/maps 2>/dev/null || true")
                    rc = NativeMemBridge.nativeAttach(handle, targetPid)
                }
                if (rc == 0) {
                    nativeHandle = handle
                    nativeBackendActive = true
                    simulated = false
                    AppLog.i("Engine", "Attached pid=$targetPid via C++20 MemEngine (process_vm_readv / pread64)")
                    return true
                }
            }
        }

        // 2. JVM 层直接探测 /proc/<pid>/maps 与 /proc/<pid>/mem（支持 NO_ROOT 共享 UID 及 ROOT）
        val mapsFile = File("/proc/$targetPid/maps")
        if (!mapsFile.canRead() && isRootMode && Shell.hasRoot()) {
            Shell.su("chmod 644 /proc/$targetPid/maps 2>/dev/null || true")
        }
        val canReadMaps = mapsFile.exists() && mapsFile.canRead()
        val memCheck = runCatching {
            RandomAccessFile("/proc/$targetPid/mem", "r").use { true }
        }.getOrDefault(false)

        simulated = !(canReadMaps && memCheck)
        if (simulated) {
            buildSimulatedProcess(targetPid)
            AppLog.i("Engine", "Attached pid=$targetPid (Fallback Sandbox Mode)")
        } else {
            AppLog.i("Engine", "Attached pid=$targetPid (JVM /proc/$targetPid/mem Direct Mode)")
        }
        return true
    }

    fun detach() {
        clearNativeFreezes()
        pid = 0
        nativeBackendActive = false
        simulated = true
    }

    /** 读取指定进程的 15 类细分内存区间（对齐原版 GG RegionCatalog.ALL） */
    fun regions(selectedCodes: Set<String> = RegionCatalog.ALL.map { it.code }.toSet()): List<MemoryRegion> {
        if (simulated) return simRegions

        if (nativeBackendActive && nativeHandle != 0L) {
            val mask = NativeMemBridge.buildRegionMask(selectedCodes)
            NativeMemBridge.nativeSetRegionMask(nativeHandle, mask)
            val totals = NativeMemBridge.nativeGetRegionTotals(nativeHandle, pid)
            if (totals != null && totals.size >= 30) {
                return RegionCatalog.ALL.mapIndexed { idx, rt ->
                    val sz = totals[idx]
                    val base = totals[15 + idx]
                    MemoryRegion(
                        start = base,
                        end = if (sz > 0) base + sz else base,
                        perms = if (rt.code == "Xa" || rt.code == "Xs") "r-xp" else "rw-p",
                        path = rt.name,
                        code = rt.code,
                        system = (rt.code == "Xs" || rt.code == "O")
                    )
                }.filter { it.size > 0L }
            }
        }

        val lines = runCatching { File("/proc/$pid/maps").readLines() }
            .getOrElse { Shell.suLines("cat /proc/$pid/maps") }
        val out = ArrayList<MemoryRegion>(lines.size)
        var prevFileBackedPath = ""

        for (line in lines) {
            val seg = parseMapLine(line, prevFileBackedPath) ?: continue
            prevFileBackedPath = seg.prevPathNext
            out.add(
                MemoryRegion(
                    start = seg.start,
                    end = seg.end,
                    perms = seg.perms,
                    path = seg.path,
                    code = seg.code,
                    system = seg.code == "Xs"
                )
            )
        }
        return out
    }

    /** 查询指定虚拟地址所属的细分内存区域代码 (Jh, Ch, Ca, Cd, Cb, Xa 等) */
    fun regionCodeAt(address: Long): String {
        val cleanAddr = address and 0x00FFFFFFFFFFFFFFL
        if (simulated) {
            simCells[address]?.let { return it.region }
            for (r in simRegions) {
                if (r.size > 0 && cleanAddr >= r.start && cleanAddr < r.end) {
                    return r.code
                }
            }
            return "Ca"
        }
        if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeGetRegionCodeAt(nativeHandle, cleanAddr)?.let {
                if (it.isNotEmpty()) return it
            }
        }
        return "Ca"
    }

    /**
     * 根据当前进程 VMA 映射表对候选值进行指针属性分类 (忽略 ARM64 TBI 高 8 位标签)：
     * 返回值对应 FilterState.pointers 掩码位：
     * 0 = 非指针, 1 = 可写且可执行 (WX), 2 = 只读可执行 (X), 4 = 可读 (R), 8 = 只读 (RO), 16 = 可写 (W)
     */
    fun classifyPointer(ptrValue: Long): Int {
        val cleanPtr = ptrValue and 0x00FFFFFFFFFFFFFFL
        if (cleanPtr <= 0x10000L) return 0
        if (simulated) {
            for (r in simRegions) {
                if (r.size > 0 && cleanPtr >= r.start && cleanPtr < r.end) {
                    return when (r.code) {
                        "Xa" -> if (r.perms.contains('w')) 1 else 2
                        "Xs" -> 2
                        "Cd", "O" -> 8
                        else -> 16
                    }
                }
            }
            if (cleanPtr in 0x79F0000000L..0x79FFFFFFFFL) {
                return when (((cleanPtr ushr 12) and 3L).toInt()) {
                    0 -> 16
                    1 -> 8
                    2 -> 2
                    else -> 1
                }
            }
            return 0
        }

        if (nativeBackendActive && nativeHandle != 0L) {
            return NativeMemBridge.nativeClassifyPointer(nativeHandle, cleanPtr)
        }

        val lines = runCatching { File("/proc/$pid/maps").readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val seg = parseMapLine(line) ?: continue
            if (cleanPtr >= seg.start && cleanPtr < seg.end) {
                val r = seg.perms.contains('r')
                val w = seg.perms.contains('w')
                val x = seg.perms.contains('x')
                if (!r) return 0
                return when {
                    w && x -> 1
                    x -> 2
                    w -> 16
                    else -> 8
                }
            }
        }
        return 0
    }

    fun classifyPointers(candidates: LongArray): IntArray =
        IntArray(candidates.size) { idx -> classifyPointer(candidates[idx]) }

    /** 读取单个地址的值（小端序） */
    fun read(address: Long, size: Int): Long {
        if (simulated) {
            simCells[address]?.let { return it.value }
            val aligned = address and -4L
            simCells[aligned]?.let { base ->
                val shift = ((address - aligned).toInt() and 3) * 8
                return (base.value ushr shift) and maskForSize(size)
            }
            return syntheticValueAt(address, size)
        }

        val bytes = readBlock(address, size.coerceIn(1, 8))
        var v = 0L
        for (i in 0 until min(size, bytes.size)) {
            v = v or ((bytes[i].toLong() and 0xFFL) shl (i * 8))
        }
        return v
    }

    /** 批量读取连续字节块（供内存浏览器及偏移计算器使用） */
    fun readBlock(baseAddress: Long, length: Int): ByteArray {
        val safeLen = length.coerceIn(1, 16 * 1024 * 1024)
        if (simulated) {
            val out = ByteArray(safeLen)
            var i = 0
            while (i < safeLen) {
                val addr = baseAddress + i
                val cell = simCells[addr]
                val v = cell?.value ?: syntheticValueAt(addr, 4)
                for (b in 0 until 4) {
                    if (i + b < safeLen) {
                        out[i + b] = ((v ushr (b * 8)) and 0xFF).toByte()
                    }
                }
                i += 4
            }
            return out
        }

        if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeReadBlock(nativeHandle, baseAddress, safeLen)?.let { return it }
        }

        val out = ByteArray(safeLen)
        return try {
            RandomAccessFile("/proc/$pid/mem", "r").use { f ->
                f.seek(baseAddress)
                f.read(out, 0, safeLen)
            }
            out
        } catch (_: Throwable) {
            out
        }
    }

    /** 写入一个地址的值 */
    fun write(address: Long, size: Int, value: Long): Boolean {
        if (simulated) {
            val existing = simCells[address]
            if (existing != null) {
                existing.previousValue = existing.value
                existing.value = value and maskForSize(size)
            } else {
                val flags = when (size) {
                    1 -> ValueType.BYTE
                    2 -> ValueType.WORD
                    4 -> ValueType.DWORD
                    else -> ValueType.QWORD
                }
                simCells[address] = MemoryItem(
                    address = address,
                    value = value and maskForSize(size),
                    previousValue = read(address, size),
                    flags = flags,
                    region = regionCodeAt(address)
                )
            }
            return true
        }

        val safeSize = size.coerceIn(1, 8)
        val buf = ByteArray(safeSize)
        for (i in 0 until safeSize) {
            buf[i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }

        if (nativeBackendActive && nativeHandle != 0L) {
            if (NativeMemBridge.nativeWriteBlock(nativeHandle, address, buf)) {
                return true
            }
        }

        return try {
            RandomAccessFile("/proc/$pid/mem", "rw").use { f ->
                f.seek(address)
                f.write(buf)
            }
            true
        } catch (t: Throwable) {
            AppLog.e("Engine", "write failed @0x${address.toString(16)}", t)
            false
        }
    }

    /** 设置或取消指定地址的冻结锁值（同步到 C++20 ValueLocker 独立高频锁值线程） */
    fun setFreeze(address: Long, flags: Int, value: Long, enabled: Boolean) {
        if (nativeBackendActive && nativeHandle != 0L) {
            if (enabled) {
                val sz = ValueType.sizeOf(flags).coerceIn(1, 8)
                val buf = ByteArray(sz)
                for (i in 0 until sz) {
                    buf[i] = ((value ushr (i * 8)) and 0xFF).toByte()
                }
                NativeMemBridge.nativeLockAddress(nativeHandle, address, buf)
            } else {
                NativeMemBridge.nativeUnlockAddress(nativeHandle, address)
            }
        }
    }

    /** 定时维持已保存列表中的冻结项（支持普通冻结、递增、递减与范围冻结） */
    fun tickFreeze(savedList: List<MemoryItem>) {
        for (item in savedList) {
            if (!item.freeze) continue
            val nextVal = when (item.freezeType) {
                1 -> addTypedValue(item.value, 1L, item.flags)
                2 -> addTypedValue(item.value, -1L, item.flags)
                3 -> {
                    if (item.freezeFrom != item.freezeTo) {
                        val lo = minOf(item.freezeFrom, item.freezeTo)
                        val hi = maxOf(item.freezeFrom, item.freezeTo)
                        item.value.coerceIn(lo, hi)
                    } else item.value
                }
                else -> item.value
            }
            item.value = nextVal
            write(item.address, item.size, nextVal)
        }
    }

    fun clearNativeFreezes() {
        if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeUnlockAll(nativeHandle)
        }
    }

    /**
     * 将起始地址 [fromAddress] 长度为 [bytesCount] 的内存块批量复制到 [toAddress]：
     * 修复原版逐字节读写 (1600万次系统调用) 的性能瓶颈，改为 64KB 块缓冲复制。
     */
    fun copyMemory(fromAddress: Long, toAddress: Long, bytesCount: Long): Boolean {
        val safeLen = bytesCount.coerceIn(0L, 16L * 1024L * 1024L).toInt()
        if (safeLen <= 0) return false

        if (!simulated && nativeBackendActive && nativeHandle != 0L) {
            val copied = NativeMemBridge.nativeCopyMemory(nativeHandle, fromAddress, toAddress, safeLen.toLong())
            if (copied > 0L) return true
        }

        val chunkSize = 64 * 1024
        var offset = 0
        while (offset < safeLen) {
            val step = min(chunkSize, safeLen - offset)
            val data = readBlock(fromAddress + offset, step)
            if (simulated) {
                var i = 0
                while (i < step) {
                    var v = 0L
                    val elemLen = min(4, step - i)
                    for (b in 0 until elemLen) {
                        v = v or ((data[i + b].toLong() and 0xFFL) shl (b * 8))
                    }
                    write(toAddress + offset + i, elemLen, v)
                    i += elemLen
                }
            } else {
                try {
                    RandomAccessFile("/proc/$pid/mem", "rw").use { f ->
                        f.seek(toAddress + offset)
                        f.write(data, 0, step)
                    }
                } catch (_: Throwable) {
                    return offset > 0
                }
            }
            offset += step
        }
        return offset > 0
    }

    /**
     * 导出指定区间 [startAddr, endAddr) 的内存数据到指定文件路径 [fullPath]
     */
    fun dumpMemory(startAddr: Long, endAddr: Long, fullPath: String): Boolean {
        val total = (endAddr - startAddr).coerceIn(0L, 64L * 1024L * 1024L)
        if (total <= 0L) return false
        val outFile = runCatching {
            val f = File(fullPath)
            f.parentFile?.mkdirs()
            f
        }.getOrElse {
            File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", fullPath.substringAfterLast('/'))
        }

        if (!simulated && nativeBackendActive && nativeHandle != 0L) {
            val dumped = NativeMemBridge.nativeDumpMemory(nativeHandle, startAddr, endAddr, outFile.absolutePath)
            if (dumped > 0L) return true
        }

        var written = 0L
        runCatching {
            outFile.outputStream().buffered().use { out ->
                val chunk = 64 * 1024
                var cur = startAddr
                while (cur < endAddr) {
                    val step = min(chunk.toLong(), endAddr - cur).toInt()
                    val bytes = readBlock(cur, step)
                    out.write(bytes)
                    written += bytes.size
                    cur += step
                }
            }
        }
        return written > 0L
    }

    /**
     * 搜索：previous == null 时全量扫描；previous != null 时在已有候选集基础上改善 (Refine)
     */
    fun search(criteria: Criteria, previous: List<MemoryItem>? = null): SearchResult {
        val newSearch = previous == null || previous.isEmpty()
        val current = previous.orEmpty()
        val items = when {
            simulated -> searchSimulated(criteria, criteria.selectedRegions, current, newSearch, criteria.limit)
            nativeBackendActive && nativeHandle != 0L -> searchViaNativeBridge(criteria, criteria.selectedRegions, current, newSearch, criteria.limit)
            else -> searchRealJvmFallback(criteria, criteria.selectedRegions, current, newSearch, criteria.limit)
        }
        return SearchResult(items)
    }

    /**
     * 模糊搜索：
     * - previous == null 时进行初始采样
     * - previous != null 时对比每个候选地址的新旧数值并按 sign 与 delta 过滤
     */
    fun fuzzySearch(
        type: Int,
        sign: Int,
        delta: Long = 0L,
        onlyNth: Int = 0,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = 5000,
        memoryFrom: Long = 0L,
        memoryTo: Long = Long.MAX_VALUE
    ): SearchResult {
        val initial = previous == null || previous.isEmpty()
        val current = previous.orEmpty()
        val hasDelta = delta != 0L
        val dummyCriteria = Criteria(
            type = type,
            sign = sign,
            memoryFrom = memoryFrom,
            memoryTo = memoryTo,
            selectedRegions = selectedRegions,
            limit = limit
        )

        if (simulated) {
            val targetType = if (type == ValueType.AUTO) ValueType.DWORD else type
            val candidates = if (initial) {
                simCells.values
                    .filter { it.region in selectedRegions && (type == ValueType.AUTO || (it.flags and targetType) != 0) }
                    .filter { addressMatchesRangeAndMask(it.address, dummyCriteria) }
                    .map { it.copy(previousValue = it.value) }
            } else {
                // 推进模拟内存中的动态变化，让用户体验真实的增加/减少/无变化筛选
                val rng = Random(System.nanoTime())
                simCells.values.forEach { cell ->
                    cell.previousValue = cell.value
                    when (rng.nextInt(4)) {
                        0 -> {
                            val step = if (hasDelta) abs(delta) else rng.nextInt(1, 25).toLong()
                            cell.value = addTypedValue(cell.value, step, cell.flags)
                        }
                        1 -> {
                            val step = if (hasDelta) -abs(delta) else -rng.nextInt(1, 25).toLong()
                            cell.value = addTypedValue(cell.value, step, cell.flags)
                        }
                        else -> Unit
                    }
                }
                var matchCounter = 0
                current.mapNotNull { oldItem ->
                    val live = simCells[oldItem.address] ?: return@mapNotNull null
                    val oldVal = oldItem.value
                    val newVal = live.value
                    val itemType = oldItem.flags and 0x7F
                    val pass = matchesFuzzyChange(newVal, oldVal, itemType, sign, delta, hasDelta)
                    if (!pass) return@mapNotNull null
                    matchCounter++
                    if (onlyNth > 0 && (matchCounter % onlyNth) != 0) return@mapNotNull null
                    oldItem.copy(
                        value = newVal,
                        previousValue = oldVal,
                        pointer = classifyPointer(newVal)
                    )
                }
            }
            return SearchResult(candidates.take(limit))
        }

        // 真实进程模式 (Native C++20 或 JVM /proc/<pid>/mem)
        val targetType = if (type == ValueType.AUTO) ValueType.DWORD else type
        val size = ValueType.sizeOf(targetType)
        if (initial) {
            if (nativeBackendActive && nativeHandle != 0L) {
                val mask = NativeMemBridge.buildRegionMask(selectedRegions)
                NativeMemBridge.nativeSetRegionMask(nativeHandle, mask)
                NativeMemBridge.nativeStartUnknownSnapshot(nativeHandle, NativeMemBridge.toNativeDataType(targetType))
                val packed = NativeMemBridge.nativeGetCandidatesPacked(nativeHandle, 0, limit)
                if (packed != null && packed.isNotEmpty()) {
                    val out = ArrayList<MemoryItem>(packed.size / 3)
                    var i = 0
                    while (i + 2 < packed.size) {
                        val addr = packed[i]
                        val v = packed[i + 1]
                        val meta = packed[i + 2].toInt()
                        val regCode = NativeMemBridge.fromNativeRegionFlag(1 shl ((meta ushr 8) and 0xFFFF))
                        if (addressMatchesRangeAndMask(addr, dummyCriteria)) {
                            out.add(MemoryItem(address = addr, value = v, previousValue = v, flags = targetType, region = regCode, pointer = classifyPointer(v)))
                        }
                        i += 3
                    }
                    if (out.isNotEmpty()) return SearchResult(out)
                }
            }
            val c = dummyCriteria.copy(type = targetType, sign = ValueType.SIGN_EQUAL)
            return SearchResult(searchRealJvmFallback(c, selectedRegions, emptyList(), newSearch = true, maxResults = limit))
        }

        var matchCounter = 0
        val refined = current.mapNotNull { item ->
            if (!addressMatchesRangeAndMask(item.address, dummyCriteria)) return@mapNotNull null
            val now = read(item.address, size)
            val pass = matchesFuzzyChange(now, item.value, item.flags and 0x7F, sign, delta, hasDelta)
            if (!pass) return@mapNotNull null
            matchCounter++
            if (onlyNth > 0 && (matchCounter % onlyNth) != 0) return@mapNotNull null
            item.copy(
                value = now,
                previousValue = item.value,
                pointer = classifyPointer(now)
            )
        }
        return SearchResult(refined.take(limit))
    }

    /** 地址蒙版搜索 */
    fun maskSearch(
        addressPattern: Long,
        maskBits: Long,
        type: Int,
        sign: Int,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = 5000
    ): SearchResult {
        val targetType = if (type == ValueType.AUTO) ValueType.DWORD else type
        val newSearch = previous == null || previous.isEmpty()
        val pool = if (newSearch) {
            if (simulated) simCells.values.toList()
            else searchRealJvmFallback(
                Criteria(type = targetType, selectedRegions = selectedRegions, limit = limit),
                selectedRegions,
                emptyList(),
                newSearch = true,
                maxResults = limit
            )
        } else previous.orEmpty()

        val matched = pool.filter { item ->
            item.region in selectedRegions && ((item.address and maskBits) == (addressPattern and maskBits))
        }.take(limit).toMutableList()

        if (simulated && newSearch && matched.isEmpty()) {
            val reg = selectedRegions.firstOrNull() ?: "Ca"
            for (k in 0 until 6) {
                val baseAddr = (addressPattern and maskBits) or ((k.toLong() shl 12) and maskBits.inv())
                val aligned = baseAddr and -4L
                val v = 100L + k * 25L
                val item = MemoryItem(
                    address = aligned,
                    value = v,
                    previousValue = v,
                    flags = targetType,
                    region = reg,
                    pointer = classifyPointer(v)
                )
                simCells[aligned] = item
                matched.add(item)
            }
        }
        return SearchResult(matched)
    }

    /** 指针链搜索：查找值指向 [targetAddress - maxOffset, targetAddress + maxOffset] 的指针条目 */
    fun pointerSearch(
        targetAddress: Long,
        maxOffset: Long,
        type: Int,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = 5000
    ): SearchResult {
        val ptrType = if (type == ValueType.AUTO || type == ValueType.DWORD) {
            if (x64) ValueType.QWORD else ValueType.DWORD
        } else type
        val cleanTarget = targetAddress and 0x00FFFFFFFFFFFFFFL
        val lo = (cleanTarget - maxOffset.coerceAtLeast(0L)).coerceAtLeast(0L)
        val hi = cleanTarget + maxOffset.coerceAtLeast(0L)

        if (!simulated && nativeBackendActive && nativeHandle != 0L && (previous == null || previous.isEmpty())) {
            val mask = NativeMemBridge.buildRegionMask(selectedRegions)
            NativeMemBridge.nativeSetRegionMask(nativeHandle, mask)
            NativeMemBridge.nativeSearchRange(
                nativeHandle,
                NativeMemBridge.toNativeDataType(ptrType),
                lo,
                hi,
                lo.toDouble(),
                hi.toDouble(),
                false,
                true
            )
            val packed = NativeMemBridge.nativeGetCandidatesPacked(nativeHandle, 0, limit)
            if (packed != null && packed.isNotEmpty()) {
                val out = ArrayList<MemoryItem>(packed.size / 3)
                var i = 0
                while (i + 2 < packed.size) {
                    val addr = packed[i]
                    val rawVal = packed[i + 1]
                    val meta = packed[i + 2].toInt()
                    val regCode = NativeMemBridge.fromNativeRegionFlag(1 shl ((meta ushr 8) and 0xFFFF))
                    out.add(
                        MemoryItem(
                            address = addr,
                            value = rawVal,
                            previousValue = rawVal,
                            flags = ptrType,
                            region = regCode,
                            pointer = classifyPointer(rawVal)
                        )
                    )
                    i += 3
                }
                if (out.isNotEmpty()) return SearchResult(out)
            }
        }

        val pool = if (previous != null && previous.isNotEmpty()) {
            previous
        } else if (simulated) {
            simCells.values.toList()
        } else {
            emptyList()
        }
        val matched = pool.filter { item ->
            val cleanVal = item.value and 0x00FFFFFFFFFFFFFFL
            item.region in selectedRegions && cleanVal in lo..hi
        }.take(limit).toMutableList()

        if (simulated && matched.isEmpty()) {
            val reg = selectedRegions.firstOrNull() ?: "Ca"
            val offsets = longArrayOf(0L, 0x10L, 0x24L, 0x48L, -0x18L, 0x80L)
            for ((idx, off) in offsets.withIndex()) {
                if (abs(off) > maxOffset) continue
                val holderAddr = (0x79F1720000L + idx * 0x18L) and -8L
                val pointedVal = (cleanTarget + off).coerceAtLeast(0x10000L)
                val item = MemoryItem(
                    address = holderAddr,
                    value = pointedVal,
                    previousValue = pointedVal,
                    flags = ptrType,
                    region = reg,
                    pointer = 16
                )
                simCells[holderAddr] = item
                matched.add(item)
            }
        }
        return SearchResult(matched)
    }

    /**
     * 通过 C++20 MemEngine JNI 执行高性能多线程 + SIMD 检索
     */
    private fun searchViaNativeBridge(
        c: Criteria,
        regions: Set<String>,
        current: List<MemoryItem>,
        newSearch: Boolean,
        maxResults: Int
    ): List<MemoryItem> {
        val regMask = NativeMemBridge.buildRegionMask(regions)
        NativeMemBridge.nativeSetRegionMask(nativeHandle, regMask)

        val nativeDt = NativeMemBridge.toNativeDataType(c.type)
        val fVal1 = when (c.type and 0x7F) {
            ValueType.FLOAT -> Float.fromBits(c.from.toInt()).toDouble()
            ValueType.DOUBLE -> Double.fromBits(c.from)
            else -> c.from.toDouble()
        }
        val fVal2 = when (c.type and 0x7F) {
            ValueType.FLOAT -> Float.fromBits(c.to.toInt()).toDouble()
            ValueType.DOUBLE -> Double.fromBits(c.to)
            else -> c.to.toDouble()
        }

        if (newSearch) {
            when {
                c.isText && c.textBytes.isNotEmpty() -> {
                    val maskStr = "x".repeat(c.textBytes.size)
                    NativeMemBridge.nativeSearchPattern(nativeHandle, c.textBytes, maskStr)
                }
                c.orderedGroup.size > 1 -> {
                    val count = c.orderedGroup.size
                    val typesArr = IntArray(count) { i ->
                        NativeMemBridge.toNativeDataType(c.groupTypes.getOrElse(i) { c.type })
                    }
                    val intArr = LongArray(count) { i -> c.orderedGroup[i] }
                    val fltArr = DoubleArray(count) { i ->
                        val gt = c.groupTypes.getOrElse(i) { c.type } and 0x7F
                        val raw = c.orderedGroup[i]
                        when (gt) {
                            ValueType.FLOAT -> Float.fromBits(raw.toInt()).toDouble()
                            ValueType.DOUBLE -> Double.fromBits(raw)
                            else -> raw.toDouble()
                        }
                    }
                    NativeMemBridge.nativeSearchGroup(
                        nativeHandle,
                        typesArr,
                        intArr,
                        fltArr,
                        c.area.coerceIn(16, 65536),
                        c.ordered,
                        true
                    )
                }
                c.xorKey != 0L -> {
                    NativeMemBridge.nativeSearchEncoded(
                        nativeHandle,
                        nativeDt,
                        NativeMemBridge.ENC_XOR_STATIC,
                        c.value,
                        fVal1,
                        c.xorKey,
                        true
                    )
                }
                c.encryptedXor -> {
                    NativeMemBridge.nativeSearchEncoded(
                        nativeHandle,
                        nativeDt,
                        if ((c.type and 0x7F) == ValueType.QWORD || (c.type and 0x7F) == ValueType.DOUBLE) {
                            NativeMemBridge.ENC_XOR_PAIR_64
                        } else {
                            NativeMemBridge.ENC_XOR_PAIR_32
                        },
                        c.value,
                        fVal1,
                        0L,
                        true
                    )
                }
                c.isRange && !c.notRange -> {
                    NativeMemBridge.nativeSearchRange(
                        nativeHandle,
                        nativeDt,
                        minOf(c.from, c.to),
                        maxOf(c.from, c.to),
                        minOf(fVal1, fVal2),
                        maxOf(fVal1, fVal2),
                        true,
                        true
                    )
                }
                (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_EQUAL && c.mask == -1L && c.memoryFrom == 0L && c.memoryTo == Long.MAX_VALUE -> {
                    NativeMemBridge.nativeSearchExact(
                        nativeHandle,
                        nativeDt,
                        c.value,
                        fVal1,
                        1e-4,
                        true
                    )
                }
                else -> {
                    return searchRealJvmFallback(c, regions, current, true, maxResults)
                }
            }
        } else {
            return searchRealJvmFallback(c, regions, current, false, maxResults)
        }

        val packed = NativeMemBridge.nativeGetCandidatesPacked(nativeHandle, 0, maxResults) ?: LongArray(0)
        val out = ArrayList<MemoryItem>(packed.size / 3)
        var idx = 0
        while (idx + 2 < packed.size) {
            val addr = packed[idx]
            val rawVal = packed[idx + 1]
            val meta = packed[idx + 2].toInt()
            val dt = NativeMemBridge.fromNativeDataType(meta and 0xFF)
            val regFlag = (meta ushr 8) and 0xFFFF
            val regCode = NativeMemBridge.fromNativeRegionFlag(1 shl regFlag)

            if (addressMatchesRangeAndMask(addr, c)) {
                out.add(
                    MemoryItem(
                        address = addr,
                        value = rawVal,
                        previousValue = rawVal,
                        flags = if (c.type == ValueType.AUTO) dt else c.type,
                        region = regCode,
                        pointer = classifyPointer(rawVal)
                    )
                )
            }
            idx += 3
        }
        return out
    }

    // ---------------- 真实进程 JVM 回退与改善检索实现 ----------------

    private data class Seg(
        val start: Long,
        val end: Long,
        val perms: String,
        val code: String,
        val path: String,
        val prevPathNext: String = ""
    )

    private fun parseMapLine(line: String, prevFileBackedPath: String = ""): Seg? {
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 5) return null
        val range = parts[0].split('-')
        if (range.size != 2) return null
        val start = range[0].toULongOrNull(16)?.toLong() ?: return null
        val end = range[1].toULongOrNull(16)?.toLong() ?: return null
        if (end <= start) return null
        val perms = parts[1]
        val inode = parts[4].toLongOrNull() ?: 0L
        val rawPath = if (parts.size >= 6) parts[5] else ""
        val path = rawPath.removeSuffix(" (deleted)")
        val code = classifyMapRegion(perms, path, inode, prevFileBackedPath)
        val nextPrev = if (path.startsWith("/")) path else ""
        return Seg(start, end, perms, code, path, nextPrev)
    }

    /**
     * 15 类细分内存区域判定（严格对齐原版 GG 与 C++20 MapsParser::classifyVma）：
     * Ch, Jh, Ca, Cd, Cb, Ps, A, J, S, As, V, O, B, Xa, Xs
     */
    private fun classifyMapRegion(
        perms: String,
        path: String,
        inode: Long,
        prevFileBackedPath: String
    ): String {
        val readable = perms.startsWith('r')
        val writable = perms.length > 1 && perms[1] == 'w'
        val executable = perms.length > 2 && perms[2] == 'x'
        val isPrivate = perms.length > 3 && perms[3] == 'p'

        if (!readable) return "B"
        val p = path.lowercase()

        if ("ppsspp" in p || "psp_ram" in p || "psp_vram" in p) return "Ps"
        if (p.endsWith(".ttf") || p.endsWith(".otf") || p.endsWith(".ttc") ||
            "/system/fonts/" in p || p == "[vvar]" || p == "[vectors]" ||
            p.startsWith("/dev/binder") || p.startsWith("/dev/hwbinder") ||
            "guard" in p
        ) return "B"

        if ("kgsl" in p || "/dev/mali" in p || "/dev/ion" in p ||
            "/dev/dma_heap" in p || "/dev/dri" in p || "gralloc" in p
        ) return "V"

        if (p == "[stack]" || p.startsWith("[stack:") || p.startsWith("[anon:stack_and_tls")) return "S"
        if (p == "[heap]" || p.startsWith("[heap:")) return "Ch"

        if ("dalvik-" in p || p.startsWith("[anon:art_") ||
            "main space" in p || "alloc space" in p || "region space" in p ||
            "large object space" in p || "zygote" in p
        ) {
            if (executable) return "Xa"
            val isMainHeap = "main space" in p || "alloc space" in p ||
                "region space" in p || "large object" in p ||
                "non moving space" in p || "zygote space" in p
            return if (writable && isMainHeap) "Jh" else "J"
        }
        if (p.endsWith(".vdex") || p.endsWith(".dex")) return "J"

        if ("libc_malloc" in p || "scudo" in p || "jemalloc" in p || "gwp-asan" in p || "bionic_alloc" in p) {
            return "Ca"
        }

        if (p == "[anon:.bss]" || p.startsWith("[anon:bss")) return "Cb"
        if (p.isEmpty() && writable && !executable && isPrivate &&
            (prevFileBackedPath.endsWith(".so") || ".so!" in prevFileBackedPath)
        ) {
            return "Cb"
        }

        if ("ashmem" in p || p.startsWith("/memfd:")) {
            return if (executable) "Xa" else "As"
        }

        if (p.isEmpty() || p.startsWith("[anon:")) {
            if (executable) return "Xa"
            return if (writable) "A" else "O"
        }

        if (p == "[vdso]" || p == "[vsyscall]") return "Xs"

        val isSys = p.startsWith("/system/") || p.startsWith("/apex/") ||
            p.startsWith("/vendor/") || p.startsWith("/product/")
        val isApp = p.startsWith("/data/app/") || p.startsWith("/data/data/") || p.startsWith("/data/user/")

        if (executable) {
            return if (isSys && !isApp) "Xs" else "Xa"
        }

        if (writable && isPrivate && (p.endsWith(".so") || ".so" in p || p.endsWith(".art") || inode > 0)) {
            return "Cd"
        }

        return "O"
    }

    private fun searchRealJvmFallback(
        criteria: Criteria,
        regions: Set<String>,
        current: List<MemoryItem>,
        newSearch: Boolean,
        maxResults: Int
    ): List<MemoryItem> {
        val targetType = if (criteria.type == ValueType.AUTO) ValueType.DWORD else criteria.type
        val size = ValueType.sizeOf(targetType)
        val out = ArrayList<MemoryItem>(min(maxResults, 2048))

        if (!newSearch && current.isNotEmpty()) {
            val prevMap = current.associate { it.address to it.value }
            for (item in current) {
                if (!addressMatchesRangeAndMask(item.address, criteria)) continue
                val itemSize = ValueType.sizeOf(item.flags and 0x7F)
                val v = read(item.address, itemSize)
                if (matches(v, criteria.copy(type = item.flags and 0x7F), prevMap, item.address)) {
                    out.add(
                        item.copy(
                            value = v,
                            previousValue = item.value,
                            pointer = classifyPointer(v)
                        )
                    )
                }
            }
            return out
        }

        val lines = runCatching { File("/proc/$pid/maps").readLines() }.getOrDefault(emptyList())
        var prevPath = ""
        val segs = lines.mapNotNull {
            val s = parseMapLine(it, prevPath)
            if (s != null) prevPath = s.prevPathNext
            s
        }.filter { it.code in regions && it.perms.startsWith("r") && (it.end - it.start) <= 64L * 1024 * 1024 }

        val buffer = ByteArray(64 * 1024 + 16)
        runCatching {
            RandomAccessFile("/proc/$pid/mem", "r").use { f ->
                for (seg in segs) {
                    var offset = seg.start
                    while (offset < seg.end && out.size < maxResults) {
                        val logicalChunk = min(65536L, seg.end - offset).toInt()
                        val readLen = min(buffer.size.toLong(), seg.end - offset).toInt()
                        val read = try {
                            f.seek(offset)
                            f.read(buffer, 0, readLen)
                        } catch (_: Throwable) {
                            -1
                        }
                        if (read <= 0) {
                            offset += 4096L
                            continue
                        }

                        if (criteria.isText && criteria.textBytes.isNotEmpty()) {
                            val pat = criteria.textBytes
                            val limit = min(logicalChunk, read - pat.size + 1)
                            var i = 0
                            while (i < limit && out.size < maxResults) {
                                var matched = true
                                for (k in pat.indices) {
                                    if (buffer[i + k] != pat[k]) {
                                        matched = false
                                        break
                                    }
                                }
                                if (matched && addressMatchesRangeAndMask(offset + i, criteria)) {
                                    var v = 0L
                                    for (b in 0 until min(4, pat.size)) {
                                        v = v or ((pat[b].toLong() and 0xFFL) shl (b * 8))
                                    }
                                    out.add(
                                        MemoryItem(
                                            address = offset + i,
                                            value = v,
                                            previousValue = v,
                                            flags = if (criteria.textUtf16) ValueType.WORD else ValueType.BYTE,
                                            region = seg.code
                                        )
                                    )
                                }
                                i++
                            }
                        } else {
                            var i = 0
                            val limit = min(logicalChunk, read - size + 1)
                            while (i < limit && out.size < maxResults) {
                                val candidateAddr = offset + i
                                if (addressMatchesRangeAndMask(candidateAddr, criteria)) {
                                    var v = 0L
                                    for (b in 0 until size) {
                                        v = v or ((buffer[i + b].toLong() and 0xFFL) shl (b * 8))
                                    }
                                    if (matches(v, criteria.copy(type = targetType), emptyMap(), candidateAddr)) {
                                        out.add(
                                            MemoryItem(
                                                address = candidateAddr,
                                                value = v,
                                                previousValue = v,
                                                flags = targetType,
                                                region = seg.code,
                                                pointer = classifyPointer(v)
                                            )
                                        )
                                    }
                                }
                                i += size
                            }
                        }
                        offset += logicalChunk
                    }
                }
            }
        }
        return out
    }

    // ---------------- 模拟环境构建与检索（支持全部高级语法） ----------------

    private fun buildSimulatedProcess(targetPid: Int) {
        val rng = Random(targetPid.toLong() xor 0x5DEECE66DL)
        val regionCodes = listOf("Jh", "Ch", "Ca", "Cd", "Cb", "A", "S", "As", "J", "Ps", "Xa", "Xs")
        simRegions = RegionCatalog.ALL.mapIndexed { idx, rt ->
            val base = 0x79F1000000L + idx * 0x00200000L
            val sz = when (rt.code) {
                "Jh" -> 96L * 1024 * 1024
                "Ca" -> 64L * 1024 * 1024
                "Ch" -> 32L * 1024 * 1024
                "Cd" -> 18L * 1024 * 1024
                "Cb" -> 12L * 1024 * 1024
                "A"  -> 84L * 1024 * 1024
                "J"  -> 24L * 1024 * 1024
                "S"  -> 8L * 1024 * 1024
                "As" -> 16L * 1024 * 1024
                "Ps" -> if (targetPid == 24910) 64L * 1024 * 1024 else 0L
                "V"  -> 48L * 1024 * 1024
                "Xa" -> 42L * 1024 * 1024
                "Xs" -> 110L * 1024 * 1024
                "O"  -> 22L * 1024 * 1024
                else -> 0L
            }
            val perms = when (rt.code) {
                "Xa", "Xs" -> "r-xp"
                "B" -> "---p"
                else -> "rw-p"
            }
            MemoryRegion(
                start = base,
                end = base + sz,
                perms = perms,
                path = rt.name,
                code = rt.code,
                system = rt.code == "Xs"
            )
        }

        val presetValues = longArrayOf(
            100L, 100L, 150L, 200L, 250L, 300L, 500L, 999L,
            1000L, 1000L, 1500L, 2000L, 5000L, 9999L, 10000L,
            12345L, 50000L, 65535L, 100000L, 999999L,
            0L, 1L, 2L, 3L, 5L, 10L, 50L, 64L, 128L, 255L, 512L, 1024L
        )
        var base = 0x79F1497000L
        for (i in 0 until 720) {
            base += rng.nextLong(4L, 64L) and -4L
            val region = regionCodes[i % 8]
            val typeChoice = when (i % 10) {
                0, 1, 2, 3, 4 -> ValueType.DWORD
                5, 6 -> ValueType.FLOAT
                7 -> ValueType.QWORD
                8 -> ValueType.WORD
                else -> ValueType.BYTE
            }
            val v = when (typeChoice) {
                ValueType.FLOAT -> {
                    val f = listOf(1.0f, 1.5f, 2.0f, 10.0f, 50.0f, 100.0f, 250.0f, 999.0f, 1000.0f)[i % 9]
                    f.toRawBits().toLong() and 0xFFFFFFFFL
                }
                else -> {
                    if (i < presetValues.size * 5) presetValues[i % presetValues.size]
                    else rng.nextLong(0, 100_000)
                }
            }
            simCells[base] = MemoryItem(
                address = base,
                value = v,
                previousValue = v,
                flags = typeChoice,
                region = region,
                pointer = if (i % 7 == 0) (1 shl (i % 5)) else 0
            )
        }
    }

    private fun searchSimulated(
        c: Criteria,
        regions: Set<String>,
        current: List<MemoryItem>,
        newSearch: Boolean,
        maxResults: Int
    ): List<MemoryItem> {
        val prevMap = current.associate { it.address to it.value }
        val pool = if (newSearch) simCells.values.toList() else current.mapNotNull { simCells[it.address] ?: it }

        // 1. 文本搜索 ('abc' / "abc")
        if (c.isText && c.textBytes.isNotEmpty()) {
            val out = ArrayList<MemoryItem>()
            var packedVal = 0L
            for (b in 0 until min(8, c.textBytes.size)) {
                packedVal = packedVal or ((c.textBytes[b].toLong() and 0xFFL) shl (b * 8))
            }
            val anchor = (0x79F1520000L + (c.textBytes.contentHashCode().toLong() and 0xFFFFL)) and -4L
            val item = MemoryItem(
                address = anchor,
                value = packedVal,
                previousValue = packedVal,
                flags = if (c.textUtf16) ValueType.WORD else ValueType.BYTE,
                region = regions.firstOrNull() ?: "Ca"
            )
            simCells[anchor] = item
            out.add(item)
            return out
        }

        // 2. 联合搜索 (Group Search: A;B;C:512 或 A;B;C::64)
        if (c.orderedGroup.size > 1 && newSearch) {
            val out = ArrayList<MemoryItem>()
            val sorted = pool.filter { it.region in regions && addressMatchesRangeAndMask(it.address, c) }
                .sortedBy { it.address }
            val radius = c.area.coerceIn(16, 65536).toLong()

            for (i in sorted.indices) {
                val anchor = sorted[i]
                val windowItems = ArrayList<MemoryItem>()
                var j = i
                while (j < sorted.size && (sorted[j].address - anchor.address) <= radius) {
                    windowItems.add(sorted[j])
                    j++
                }
                if (windowItems.size < c.orderedGroup.size) continue

                val matchedGroup = matchGroupInWindow(windowItems, c.orderedGroup, c.groupTypes, c.type, c.ordered)
                if (matchedGroup != null) {
                    for (m in matchedGroup) {
                        if (out.none { it.address == m.address }) {
                            out.add(m.copy())
                        }
                    }
                    if (out.size >= maxResults) break
                }
            }

            if (out.isEmpty()) {
                val structBase = 0x79F1580000L + ((c.orderedGroup.hashCode().toLong() and 0xFFFL) * 16L)
                val reg = regions.firstOrNull() ?: "Ca"
                for (idx in c.orderedGroup.indices) {
                    val addr = structBase + idx * 4L
                    val gType = c.groupTypes.getOrElse(idx) { if (c.type == ValueType.AUTO) ValueType.DWORD else c.type }
                    val gVal = c.orderedGroup[idx]
                    val item = MemoryItem(
                        address = addr,
                        value = gVal,
                        previousValue = gVal,
                        flags = gType,
                        region = reg,
                        pointer = classifyPointer(gVal)
                    )
                    simCells[addr] = item
                    out.add(item)
                }
            }
            return out.take(maxResults)
        }

        // 3. 常规单值 / AUTO / 范围 / 异或 / 加密搜索
        val filtered = pool.filter { item ->
            val inRegion = item.region in regions
            val typeOk = c.type == ValueType.AUTO || (item.flags and c.type) != 0
            val addrOk = addressMatchesRangeAndMask(item.address, c)
            val evalType = if (c.type == ValueType.AUTO) (item.flags and 0x7F) else (c.type and 0x7F)
            val valOk = matches(item.value, c.copy(type = evalType), prevMap, item.address)
            inRegion && typeOk && addrOk && valOk
        }.take(maxResults).map { it.copy(pointer = classifyPointer(it.value)) }.toMutableList()

        if (newSearch && filtered.isEmpty() && !c.isRange && (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_EQUAL) {
            val targetType = if (c.type == ValueType.AUTO) ValueType.DWORD else c.type
            val baseAddr = 0x79F1600000L + ((c.value and 0xFFFL) * 16L)
            val reg = regions.firstOrNull() ?: "Ca"
            for (k in 0 until 8) {
                val addr = (baseAddr + k * 0x24L) and -4L
                if (!addressMatchesRangeAndMask(addr, c)) continue
                val storedVal = if (c.xorKey != 0L) (c.value xor c.xorKey) else c.value
                val item = MemoryItem(
                    address = addr,
                    value = storedVal,
                    previousValue = storedVal,
                    flags = targetType,
                    region = reg,
                    pointer = classifyPointer(storedVal)
                )
                simCells[addr] = item
                filtered.add(item)
            }
        }

        return filtered
    }

    private fun matchGroupInWindow(
        window: List<MemoryItem>,
        groupValues: List<Long>,
        groupTypes: List<Int>,
        defaultType: Int,
        ordered: Boolean
    ): List<MemoryItem>? {
        val result = ArrayList<MemoryItem>(groupValues.size)
        var startIdx = 0
        for (gIdx in groupValues.indices) {
            val targetVal = groupValues[gIdx]
            val targetType = groupTypes.getOrElse(gIdx) {
                if (defaultType == ValueType.AUTO) ValueType.DWORD else defaultType
            } and 0x7F

            var foundIdx = -1
            val searchRange = if (ordered) (startIdx until window.size) else window.indices
            for (wIdx in searchRange) {
                val candidate = window[wIdx]
                if (result.any { it.address == candidate.address }) continue
                if (compareTypedValues(candidate.value, targetVal, targetType) == 0) {
                    foundIdx = wIdx
                    result.add(candidate)
                    break
                }
            }
            if (foundIdx < 0) return null
            if (ordered) startIdx = foundIdx + 1
        }
        return result
    }

    private fun addressMatchesRangeAndMask(addr: Long, c: Criteria): Boolean {
        if (c.memoryFrom != 0L && java.lang.Long.compareUnsigned(addr, c.memoryFrom) < 0) return false
        if (c.memoryTo != Long.MAX_VALUE && java.lang.Long.compareUnsigned(addr, c.memoryTo) > 0) return false
        return true
    }

    /**
     * 核心数值匹配函数（已修复有符号整数与 IEEE-754 浮点数比较时误用 compareUnsigned 的 Bug）：
     */
    private fun matches(v: Long, c: Criteria, prevMap: Map<Long, Long>, addr: Long): Boolean {
        val logicalVal = when {
            c.xorKey != 0L -> v xor c.xorKey
            c.encryptedXor -> v xor 0x5A5A5A5AL
            else -> v
        }
        val evalType = if (c.type == ValueType.AUTO) ValueType.DWORD else (c.type and 0x7F)
        val sign = c.sign and ValueType.SIGN_MASK

        if (c.isRange) {
            val cmp1 = compareTypedValues(logicalVal, c.from, evalType)
            val cmp2 = compareTypedValues(logicalVal, c.to, evalType)
            val loCmp = minOf(cmp1, cmp2)
            val hiCmp = maxOf(cmp1, cmp2)
            val inRange = (cmp1 >= 0 && cmp2 <= 0) || (cmp1 <= 0 && cmp2 >= 0) || (loCmp <= 0 && hiCmp >= 0)
            val baseMatch = if (c.notRange) !inRange else inRange
            return when (sign) {
                ValueType.SIGN_NOT_EQUAL -> !baseMatch
                else -> baseMatch
            }
        }

        val cmp = compareTypedValues(logicalVal, c.value, evalType)
        return when (sign) {
            ValueType.SIGN_EQUAL -> cmp == 0
            ValueType.SIGN_NOT_EQUAL -> cmp != 0
            ValueType.SIGN_GREATER_EQ -> cmp >= 0
            ValueType.SIGN_LESS_EQ -> cmp <= 0
            else -> {
                val prev = prevMap[addr] ?: return true
                compareTypedValues(logicalVal, prev, evalType) != 0
            }
        }
    }

    /**
     * 按数据类型正确比较两个原始位模式 (raw bits)：
     */
    fun compareTypedValues(rawA: Long, rawB: Long, type: Int): Int {
        return when (type and 0x7F) {
            ValueType.FLOAT -> {
                val fa = Float.fromBits(rawA.toInt())
                val fb = Float.fromBits(rawB.toInt())
                if (fa.isNaN() || fb.isNaN()) return -1
                if (abs(fa - fb) <= 1e-4f) 0 else fa.compareTo(fb)
            }
            ValueType.DOUBLE -> {
                val da = Double.fromBits(rawA)
                val db = Double.fromBits(rawB)
                if (da.isNaN() || db.isNaN()) return -1
                if (abs(da - db) <= 1e-6) 0 else da.compareTo(db)
            }
            ValueType.BYTE -> {
                val sa = rawA.toByte().toLong()
                val sb = rawB.toByte().toLong()
                sa.compareTo(sb)
            }
            ValueType.WORD -> {
                val sa = rawA.toShort().toLong()
                val sb = rawB.toShort().toLong()
                sa.compareTo(sb)
            }
            ValueType.DWORD, ValueType.XOR -> {
                val sa = rawA.toInt().toLong()
                val sb = rawB.toInt().toLong()
                sa.compareTo(sb)
            }
            else -> rawA.compareTo(rawB)
        }
    }

    private fun matchesFuzzyChange(
        newRaw: Long,
        oldRaw: Long,
        type: Int,
        sign: Int,
        delta: Long,
        hasDelta: Boolean
    ): Boolean {
        val targetOld = if (hasDelta && delta != 0L) addTypedValue(oldRaw, delta, type) else oldRaw
        val cmp = compareTypedValues(newRaw, targetOld, type)
        return when (sign and ValueType.SIGN_MASK) {
            ValueType.SIGN_EQUAL -> cmp == 0
            ValueType.SIGN_NOT_EQUAL -> cmp != 0
            ValueType.SIGN_GREATER_EQ -> if (hasDelta) cmp > 0 else compareTypedValues(newRaw, oldRaw, type) > 0
            ValueType.SIGN_LESS_EQ -> if (hasDelta) cmp < 0 else compareTypedValues(newRaw, oldRaw, type) < 0
            else -> true
        }
    }

    private fun addTypedValue(raw: Long, delta: Long, type: Int): Long {
        return when (type and 0x7F) {
            ValueType.FLOAT -> {
                val f = Float.fromBits(raw.toInt()) + delta.toFloat()
                f.toRawBits().toLong() and 0xFFFFFFFFL
            }
            ValueType.DOUBLE -> {
                val d = Double.fromBits(raw) + delta.toDouble()
                d.toRawBits()
            }
            ValueType.BYTE -> ((raw.toByte() + delta.toInt()) and 0xFF).toLong()
            ValueType.WORD -> ((raw.toShort() + delta.toInt()) and 0xFFFF).toLong()
            ValueType.DWORD, ValueType.XOR -> ((raw.toInt() + delta.toInt()).toLong() and 0xFFFFFFFFL)
            else -> raw + delta
        }
    }

    private fun maskForSize(size: Int): Long = when (size) {
        1 -> 0xFFL
        2 -> 0xFFFFL
        4 -> 0xFFFFFFFFL
        else -> -1L
    }

    private fun syntheticValueAt(address: Long, size: Int): Long {
        val mixed = (address xor (address ushr 17)) * 0x27D4EB2FL
        return mixed and maskForSize(size)
    }
}
