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

/**
 * 统一跨进程内存检索引擎：
 *
 * 1. Root C++20 守护通道 (`RootMemDaemon` -> `su` + `libmem_engine_root.so`)
 * 2. 免 Root 共享 UID / 虚拟空间 JNI 通道 (`NativeMemBridge` -> `libmem_engine_jni.so`)
 * 3. 统一底层传输抽象 (`NativeTransport`)，彻底消除 Root 守护通道与进程内 JNI 通道的重复代码。
 */
class MemoryEngine {

    data class SearchResult(
        val items: List<MemoryItem>,
        val totalFound: Long = items.size.toLong()
    )

    companion object {
        /** Kotlin 层缓存的最大候选条目数（上限 100,000，C++ 层 MmapCandidateStore 保留全量候选） */
        const val MAX_CACHED_CANDIDATES = 100000
    }

    data class Criteria(
        val type: Int = ValueType.DWORD,
        val value: Long = 0L,
        val from: Long = value,
        val to: Long = value,
        val isRange: Boolean = false,
        val notRange: Boolean = false,
        val sign: Int = ValueType.SIGN_EQUAL,
        val xorKey: Long = 0L,
        val xorOffset: Int = 0,
        val orderedGroup: List<Long> = emptyList(),
        val groupTo: List<Long> = emptyList(),
        val groupOps: List<Int> = emptyList(),
        val ordered: Boolean = false,
        val groupTypes: List<Int> = emptyList(),
        val area: Int = 512,
        val memoryFrom: Long = 0L,
        val memoryTo: Long = Long.MAX_VALUE,
        val limit: Int = MAX_CACHED_CANDIDATES,
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

    var attachedOk: Boolean = false
        private set

    var rootDaemonActive: Boolean = false
        private set

    var nativeBackendActive: Boolean = false
        private set

    var lastAttachError: String = ""
        private set

    private var isRootModeActive: Boolean = false
    private var appContextRef: Context? = null
    private var nativeHandle: Long = 0L
    @Volatile
    private var cachedRegions: List<MemoryRegion> = emptyList()
    private val removedAddresses: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet<Long>())

    private val isNativeEngineActive: Boolean
        get() = rootDaemonActive || (nativeBackendActive && nativeHandle != 0L)

    fun markRemoved(addresses: Collection<Long>) {
        if (addresses.isEmpty()) return
        removedAddresses.addAll(addresses)
        if (rootDaemonActive) {
            RootMemDaemon.removeCandidates(addresses)
        } else if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeRemoveCandidates(nativeHandle, addresses.toLongArray())
        }
    }

    fun clearRemoved() {
        removedAddresses.clear()
    }

    fun clearSession() {
        removedAddresses.clear()
        if (rootDaemonActive) {
            RootMemDaemon.clearSession()
        } else if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeClearSession(nativeHandle)
        }
    }

    /**
     * 附加到目标进程：
     * - Root 模式下优先通过 `RootMemDaemon` (`su` + `libmem_engine_root.so`) 以 `uid=0` 附加；
     * - 免 Root 模式下通过 `NativeMemBridge` (`libmem_engine_jni.so`) 或 JVM 直接访问 `/proc/<pid>/mem`；
     * - 若无任何后端具备真实跨进程内存读取权限，诚实返回 `false` 并清空区域与结果，绝不假装附加成功。
     */
    fun attach(
        targetPid: Int,
        targetX64: Boolean = true,
        isRootMode: Boolean = true,
        context: Context? = null
    ): Boolean {
        clearNativeFreezes()
        removedAddresses.clear()
        pid = targetPid
        x64 = targetX64
        isRootModeActive = isRootMode
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }
        rootDaemonActive = false
        nativeBackendActive = false
        attachedOk = false
        lastAttachError = ""
        cachedRegions = emptyList()

        if (targetPid <= 0) {
            lastAttachError = "无效的目标 PID ($targetPid)"
            return false
        }

        val hasRootNow = isRootMode && Shell.hasRoot()

        // 1. Root 模式：优先通过持久化 su C++20 守护进程 (libmem_engine_root.so) 附加
        if (hasRootNow) {
            if (RootMemDaemon.attach(appContextRef, targetPid)) {
                rootDaemonActive = true
                attachedOk = true
                cachedRegions = RootMemDaemon.getRegions()
                if (cachedRegions.isEmpty()) {
                    cachedRegions = parseMapsFromProc(targetPid, allowRoot = true)
                }
                AppLog.i("Engine", "Attached pid=$targetPid via RootMemDaemon (uid=0 C++20 MemEngine, regions=${cachedRegions.size})")
                return true
            }
            if (RootMemDaemon.lastStartError.isNotBlank()) {
                lastAttachError = RootMemDaemon.lastStartError
            }
        }

        // 2. 免 Root 模式（或同 UID 进程）：尝试进程内 C++20 NativeMemBridge (process_vm_readv + /proc/<pid>/mem)
        if (NativeMemBridge.isLoaded) {
            val spillDir = appContextRef?.cacheDir?.absolutePath ?: "/data/local/tmp"
            val handle = NativeMemBridge.ensureHandle(spillDir)
            if (handle != 0L) {
                val rc = NativeMemBridge.nativeAttach(handle, targetPid)
                if (rc == 0) {
                    nativeHandle = handle
                    nativeBackendActive = true
                    attachedOk = true
                    cachedRegions = parseMapsFromProc(targetPid, allowRoot = hasRootNow)
                    lastAttachError = ""
                    AppLog.i("Engine", "Attached pid=$targetPid via in-process C++20 NativeMemBridge (regions=${cachedRegions.size})")
                    return true
                }
            }
        }

        // 3. JVM 探测：仅当当前进程自身确实可读取 `/proc/<pid>/mem` 时才允许回退成功
        val regions = parseMapsFromProc(targetPid, allowRoot = hasRootNow)
        val canReadMem = runCatching {
            RandomAccessFile("/proc/$targetPid/mem", "r").use { f ->
                val firstReadable = regions.firstOrNull { it.perms.startsWith("r") && it.size > 0 }
                if (firstReadable != null) {
                    f.seek(firstReadable.start)
                    val buf = ByteArray(4)
                    f.read(buf, 0, 4) >= 0
                } else {
                    false
                }
            }
        }.getOrDefault(false)

        if (regions.isNotEmpty() && canReadMem) {
            attachedOk = true
            cachedRegions = regions
            lastAttachError = ""
            AppLog.i("Engine", "Attached pid=$targetPid via JVM /proc/mem fallback (regions=${regions.size})")
            return true
        }

        if (lastAttachError.isBlank()) {
            lastAttachError = if (isRootMode && !hasRootNow) {
                "未获取到 Root (su) 授权"
            } else {
                "无跨进程内存读写权限（进程可能已退出或受内核/SELinux 保护）"
            }
        }
        AppLog.w("Engine", "Attach failed for pid=$targetPid (isRootMode=$isRootMode, hasRoot=$hasRootNow): $lastAttachError")
        return false
    }

    fun detach() {
        clearNativeFreezes()
        clearSession()
        pid = 0
        attachedOk = false
        rootDaemonActive = false
        nativeBackendActive = false
        lastAttachError = ""
        cachedRegions = emptyList()
    }

    /**
     * 读取当前附加进程的全部真实 VMA 内存段列表。
     */
    fun regions(): List<MemoryRegion> {
        if (!attachedOk || pid <= 0) return emptyList()

        if (rootDaemonActive) {
            val fresh = RootMemDaemon.getRegions()
            if (fresh.isNotEmpty()) {
                cachedRegions = fresh
                return fresh
            }
        }

        val fresh = parseMapsFromProc(pid, allowRoot = isRootModeActive && Shell.hasRoot())
        if (fresh.isNotEmpty()) {
            cachedRegions = fresh
        }
        return cachedRegions
    }

    /**
     * 获取指定区域类型 (如 "Xa", "Cd", "Cb", "Jh", "Ch", "Ca") 的首个真实基址
     */
    fun firstRegionBase(code: String): Long? {
        val regs = if (cachedRegions.isNotEmpty()) cachedRegions else regions()
        return regs.firstOrNull { it.code.equals(code, ignoreCase = true) && it.readable }?.start
            ?: regs.firstOrNull { it.code.equals(code, ignoreCase = true) }?.start
    }

    private fun parseMapsFromProc(targetPid: Int, allowRoot: Boolean): List<MemoryRegion> {
        if (targetPid <= 0) return emptyList()
        val lines = runCatching {
            val f = File("/proc/$targetPid/maps")
            if (f.canRead()) f.readLines() else emptyList()
        }.getOrDefault(emptyList()).let { direct ->
            if (direct.isNotEmpty()) direct
            else if (allowRoot) Shell.suLines("cat /proc/$targetPid/maps 2>/dev/null")
            else emptyList()
        }
        if (lines.isEmpty()) return emptyList()

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
                    system = (seg.code == "Xs" || seg.code == "O")
                )
            )
        }
        return out
    }

    private fun untagAddr(address: Long): Long =
        if (x64) (address and 0x00FFFFFFFFFFFFFFL) else (address and 0xFFFFFFFFL)

    /** 查询指定虚拟地址所属的细分内存区域代码 (Jh, Ch, Ca, Cd, Cb, Xa 等) */
    fun regionCodeAt(address: Long): String {
        val cleanAddr = untagAddr(address)
        val regs = cachedRegions
        var lo = 0
        var hi = regs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = regs[mid]
            if (java.lang.Long.compareUnsigned(cleanAddr, r.start) < 0) {
                hi = mid - 1
            } else if (java.lang.Long.compareUnsigned(cleanAddr, r.end) >= 0) {
                lo = mid + 1
            } else {
                return r.code
            }
        }
        if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeGetRegionCodeAt(nativeHandle, cleanAddr)?.let {
                if (it.isNotEmpty()) return it
            }
        }
        return "O"
    }

    /**
     * 根据当前进程真实 VMA 映射表对候选值进行指针属性分类 (忽略 ARM64 TBI 高 8 位标签)：
     * 返回值对应 FilterState.pointers 掩码位：
     * 0 = 非指针, 1 = 可写且可执行 (WX), 2 = 只读可执行 (X), 8 = 只读 (RO), 16 = 可写 (W)
     */
    fun classifyPointer(ptrValue: Long): Int {
        val cleanPtr = untagAddr(ptrValue)
        if (cleanPtr <= 0x10000L || (cleanPtr and 3L) != 0L) return 0
        if (x64 && (cleanPtr ushr 48) != 0L) return 0

        var regs = cachedRegions
        if (regs.isEmpty() && attachedOk && pid > 0) {
            regs = regions()
        }
        var lo = 0
        var hi = regs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = regs[mid]
            if (java.lang.Long.compareUnsigned(cleanPtr, r.start) < 0) {
                hi = mid - 1
            } else if (java.lang.Long.compareUnsigned(cleanPtr, r.end) >= 0) {
                lo = mid + 1
            } else {
                if (r.code == "B") return 0
                val read = r.perms.contains('r')
                val write = r.perms.contains('w')
                val exec = r.perms.contains('x')
                if (!read) return 0
                return when {
                    write && exec -> 1
                    exec -> 2
                    write -> 16
                    else -> 8
                }
            }
        }

        if (nativeBackendActive && nativeHandle != 0L) {
            return NativeMemBridge.nativeClassifyPointer(nativeHandle, cleanPtr)
        }
        return 0
    }

    fun classifyPointers(candidates: LongArray): IntArray =
        IntArray(candidates.size) { idx -> classifyPointer(candidates[idx]) }

    /** 读取单个地址的值（小端序） */
    fun read(address: Long, size: Int): Long {
        if (!attachedOk || pid <= 0) return 0L
        val safeSize = size.coerceIn(1, 8)
        val bytes = readBlock(address, safeSize)
        var v = 0L
        for (i in 0 until min(safeSize, bytes.size)) {
            v = v or ((bytes[i].toLong() and 0xFFL) shl (i * 8))
        }
        return v
    }

    /**
     * 批量实时读取一组离散地址的最新内存值（单次 IPC 往返，供搜索列表/保存列表实时刷新使用）
     */
    fun batchReadRawValues(items: List<Pair<Long, Int>>): Map<Long, Long> {
        if (!attachedOk || pid <= 0 || items.isEmpty()) return emptyMap()
        if (rootDaemonActive) {
            return RootMemDaemon.batchReadValues(items)
        }
        val out = HashMap<Long, Long>(items.size)
        for ((addr, sz) in items) {
            out[addr] = read(addr, sz.coerceIn(1, 8))
        }
        return out
    }

    /** 批量读取连续字节块（供内存浏览器及偏移计算器使用） */
    fun readBlock(baseAddress: Long, length: Int): ByteArray {
        val safeLen = length.coerceIn(1, 16 * 1024 * 1024)
        if (!attachedOk || pid <= 0) return ByteArray(safeLen)

        if (rootDaemonActive) {
            RootMemDaemon.readBlock(baseAddress, safeLen)?.let { return it }
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
        if (!attachedOk || pid <= 0) return false
        val safeSize = size.coerceIn(1, 8)
        val buf = ByteArray(safeSize)
        for (i in 0 until safeSize) {
            buf[i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }

        if (rootDaemonActive) {
            if (RootMemDaemon.writeBlock(address, buf)) return true
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

    /**
     * 设置或取消指定地址的冻结锁值（同步到 C++20 ValueLocker 独立高频锁值线程，原生支持全部 4 种冻结模式）
     */
    fun setFreeze(
        address: Long,
        flags: Int,
        value: Long,
        enabled: Boolean,
        freezeType: Int = 0,
        freezeFrom: Long = 0L,
        freezeTo: Long = 0L
    ) {
        if (!attachedOk) return
        val sz = ValueType.sizeOf(flags).coerceIn(1, 8)
        val buf = ByteArray(sz)
        for (i in 0 until sz) {
            buf[i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
        val nativeDt = NativeMemBridge.toNativeDataType(flags)

        if (rootDaemonActive) {
            if (enabled) {
                RootMemDaemon.lockAddressConfigured(address, buf, nativeDt, freezeType, freezeFrom, freezeTo)
            } else {
                RootMemDaemon.unlockAddress(address)
            }
            return
        }

        if (nativeBackendActive && nativeHandle != 0L) {
            if (enabled) {
                NativeMemBridge.nativeLockAddressConfigured(nativeHandle, address, buf, nativeDt, freezeType, freezeFrom, freezeTo)
            } else {
                NativeMemBridge.nativeUnlockAddress(nativeHandle, address)
            }
        }
    }

    fun setFreezePaused(paused: Boolean) {
        if (rootDaemonActive) {
            RootMemDaemon.setFreezePaused(paused)
        } else if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeSetFreezePaused(nativeHandle, paused)
        }
    }

    fun setFreezeIntervalMs(intervalMs: Int) {
        val safeMs = intervalMs.coerceIn(5, 5000)
        if (rootDaemonActive) {
            RootMemDaemon.setFreezeIntervalMs(safeMs)
        } else if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeSetFreezeIntervalMs(nativeHandle, safeMs)
        }
    }

    /**
     * 仅在未启用 C++ 原生引擎 (`!isNativeEngineActive`) 时作为纯 JVM 回退执行锁值写入；
     * 当启用 C++ 引擎时，C++ `ValueLocker` 已在底层独立线程完成全模式锁值，避免双端竞争覆盖。
     */
    fun tickFreeze(savedList: List<MemoryItem>): List<MemoryItem>? {
        if (!attachedOk || pid <= 0 || savedList.isEmpty()) return null
        if (isNativeEngineActive) return null

        var changedAny = false
        val updated = ArrayList<MemoryItem>(savedList.size)
        for (item in savedList) {
            if (!item.freeze) {
                updated.add(item)
                continue
            }
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
            write(item.address, item.size, nextVal)
            if (nextVal != item.value) {
                changedAny = true
                updated.add(item.copy(value = nextVal))
            } else {
                updated.add(item)
            }
        }
        return if (changedAny) updated else null
    }

    fun clearNativeFreezes() {
        if (rootDaemonActive) {
            RootMemDaemon.unlockAll()
        }
        if (nativeBackendActive && nativeHandle != 0L) {
            NativeMemBridge.nativeUnlockAll(nativeHandle)
        }
    }

    /**
     * 将起始地址 [fromAddress] 长度为 [bytesCount] 的内存块批量复制到 [toAddress]
     */
    fun copyMemory(fromAddress: Long, toAddress: Long, bytesCount: Long): Boolean {
        if (!attachedOk || pid <= 0) return false
        val safeLen = bytesCount.coerceIn(0L, 16L * 1024L * 1024L).toInt()
        if (safeLen <= 0) return false

        if (rootDaemonActive) {
            val copied = RootMemDaemon.copyMemory(fromAddress, toAddress, safeLen.toLong())
            if (copied > 0L) return true
        }

        if (nativeBackendActive && nativeHandle != 0L) {
            val copied = NativeMemBridge.nativeCopyMemory(nativeHandle, fromAddress, toAddress, safeLen.toLong())
            if (copied > 0L) return true
        }

        val chunkSize = 64 * 1024
        var offset = 0
        while (offset < safeLen) {
            val step = min(chunkSize, safeLen - offset)
            val data = readBlock(fromAddress + offset, step)
            try {
                RandomAccessFile("/proc/$pid/mem", "rw").use { f ->
                    f.seek(toAddress + offset)
                    f.write(data, 0, step)
                }
            } catch (_: Throwable) {
                return offset > 0
            }
            offset += step
        }
        return offset > 0
    }

    /**
     * 导出指定区间 [startAddr, endAddr) 的内存数据到指定文件路径 [fullPath]
     */
    fun dumpMemory(startAddr: Long, endAddr: Long, fullPath: String): Boolean {
        if (!attachedOk || pid <= 0) return false
        val total = (endAddr - startAddr).coerceIn(0L, 64L * 1024L * 1024L)
        if (total <= 0L) return false
        val outFile = runCatching {
            val f = File(fullPath)
            f.parentFile?.mkdirs()
            f
        }.getOrElse {
            File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", fullPath.substringAfterLast('/'))
        }

        if (rootDaemonActive) {
            val dumped = RootMemDaemon.dumpMemory(startAddr, endAddr, outFile.absolutePath)
            if (dumped > 0L) return true
        }

        if (nativeBackendActive && nativeHandle != 0L) {
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
     * 从底层 C++ 引擎（RootMemDaemon 或 NativeMemBridge）统一拉取候选列表并组装为 `MemoryItem`
     */
    private fun fetchNativeCandidates(
        maxResults: Int,
        defaultType: Int,
        useCandidateDt: Boolean,
        criteriaFilter: Criteria? = null,
        prevValueMap: Map<Long, Long>? = null
    ): Pair<List<MemoryItem>, Long> {
        val extraFetch = removedAddresses.size.coerceAtMost(10000)
        val fetchLimit = (maxResults + extraFetch).coerceIn(1, MAX_CACHED_CANDIDATES * 2)
        val out = ArrayList<MemoryItem>(min(maxResults, 4096))
        var skippedRemoved = 0L

        if (rootDaemonActive) {
            val cands = RootMemDaemon.getCandidates(0, fetchLimit)
            for (cand in cands) {
                if (cand.address in removedAddresses) {
                    skippedRemoved++
                    continue
                }
                if (criteriaFilter != null && !addressMatchesRangeAndMask(cand.address, criteriaFilter)) {
                    continue
                }
                val dt = NativeMemBridge.fromNativeDataType(cand.meta and 0xFF)
                val regShift = (cand.meta ushr 8) and 0xFF
                val regCode = NativeMemBridge.fromNativeRegionFlag(1 shl regShift)
                val nativePtrFlags = (cand.meta ushr 16) and 0xFF
                val ptrKind = if (nativePtrFlags != 0) nativePtrFlags else classifyPointer(cand.rawValue)
                val prevVal = prevValueMap?.get(cand.address) ?: cand.rawValue
                out.add(
                    MemoryItem(
                        address = cand.address,
                        value = cand.rawValue,
                        previousValue = prevVal,
                        flags = if (useCandidateDt) dt else defaultType,
                        region = regCode,
                        pointer = ptrKind
                    )
                )
                if (out.size >= maxResults) break
            }
        } else if (nativeBackendActive && nativeHandle != 0L) {
            val packed = NativeMemBridge.nativeGetCandidatesPacked(nativeHandle, 0, fetchLimit) ?: LongArray(0)
            var idx = 0
            while (idx + 2 < packed.size) {
                val addr = packed[idx]
                val rawVal = packed[idx + 1]
                val meta = packed[idx + 2].toInt()
                idx += 3
                if (addr in removedAddresses) {
                    skippedRemoved++
                    continue
                }
                if (criteriaFilter != null && !addressMatchesRangeAndMask(addr, criteriaFilter)) {
                    continue
                }
                val dt = NativeMemBridge.fromNativeDataType(meta and 0xFF)
                val regShift = (meta ushr 8) and 0xFF
                val regCode = NativeMemBridge.fromNativeRegionFlag(1 shl regShift)
                val nativePtrFlags = (meta ushr 16) and 0xFF
                val ptrKind = if (nativePtrFlags != 0) nativePtrFlags else classifyPointer(rawVal)
                val prevVal = prevValueMap?.get(addr) ?: rawVal
                out.add(
                    MemoryItem(
                        address = addr,
                        value = rawVal,
                        previousValue = prevVal,
                        flags = if (useCandidateDt) dt else defaultType,
                        region = regCode,
                        pointer = ptrKind
                    )
                )
                if (out.size >= maxResults) break
            }
        }
        return out to skippedRemoved
    }

    /**
     * 搜索：previous == null 时全量扫描；previous != null 时在已有候选集基础上改善 (Refine)
     */
    fun search(
        criteria: Criteria,
        previous: List<MemoryItem>? = null,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        val newSearch = previous == null
        if (newSearch) {
            removedAddresses.clear()
        }
        val current = previous.orEmpty()
        val fetchCap = criteria.limit.coerceIn(2000, MAX_CACHED_CANDIDATES)
        return if (isNativeEngineActive) {
            searchViaNativeEngine(criteria, criteria.selectedRegions, current, newSearch, fetchCap, onProgress)
        } else {
            val items = searchRealJvmFallback(criteria, criteria.selectedRegions, current, newSearch, fetchCap, onProgress)
            SearchResult(items, items.size.toLong())
        }
    }

    /**
     * 模糊搜索：
     * - initial == true 时进行初始未知值快照采样
     * - initial == false 时对比每个候选地址的新旧数值并按 sign 与 delta 过滤
     */
    fun fuzzySearch(
        type: Int,
        sign: Int,
        delta: Long = 0L,
        onlyNth: Int = 0,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = MAX_CACHED_CANDIDATES,
        memoryFrom: Long = 0L,
        memoryTo: Long = Long.MAX_VALUE,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        val initial = previous == null
        val current = previous.orEmpty()
        val hasDelta = delta != 0L
        val fetchCap = limit.coerceIn(2000, MAX_CACHED_CANDIDATES)
        val dummyCriteria = Criteria(
            type = type,
            sign = sign,
            memoryFrom = memoryFrom,
            memoryTo = memoryTo,
            selectedRegions = selectedRegions,
            limit = fetchCap
        )

        val targetType = if (type == ValueType.AUTO) ValueType.DWORD else type
        val size = ValueType.sizeOf(targetType)
        val nativeDt = NativeMemBridge.toNativeDataType(targetType)

        if (initial) {
            removedAddresses.clear()
            if (isNativeEngineActive) {
                val mask = NativeMemBridge.buildRegionMask(selectedRegions)
                val totalFound = if (rootDaemonActive) {
                    RootMemDaemon.setRegionMask(mask)
                    RootMemDaemon.startUnknownSnapshot(nativeDt, onProgress)
                } else {
                    NativeMemBridge.nativeSetRegionMask(nativeHandle, mask)
                    NativeMemBridge.nativeStartUnknownSnapshot(nativeHandle, nativeDt)
                    val cnt = NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    onProgress?.invoke(1L, 1L, cnt)
                    cnt
                }
                val (items, _) = fetchNativeCandidates(
                    maxResults = fetchCap,
                    defaultType = targetType,
                    useCandidateDt = false,
                    criteriaFilter = dummyCriteria
                )
                return SearchResult(items, totalFound.coerceAtLeast(items.size.toLong()))
            }

            val c = dummyCriteria.copy(type = targetType, sign = ValueType.SIGN_EQUAL)
            val fallback = searchRealJvmFallback(c, selectedRegions, emptyList(), newSearch = true, maxResults = fetchCap)
            return SearchResult(fallback, fallback.size.toLong())
        }

        if (onlyNth <= 0 && isNativeEngineActive) {
            val deltaOp = when {
                hasDelta -> NativeMemBridge.OP_DELTA_EXACT
                (sign and ValueType.SIGN_MASK) == ValueType.SIGN_NOT_EQUAL -> NativeMemBridge.OP_CHANGED
                (sign and ValueType.SIGN_MASK) == ValueType.SIGN_GREATER_EQ -> NativeMemBridge.OP_INCREASED
                (sign and ValueType.SIGN_MASK) == ValueType.SIGN_LESS_EQ -> NativeMemBridge.OP_DECREASED
                else -> NativeMemBridge.OP_UNCHANGED
            }
            val totalFound = if (rootDaemonActive) {
                RootMemDaemon.refineFuzzy(deltaOp, delta, delta.toDouble(), 1e-4, onProgress)
            } else {
                NativeMemBridge.nativeRefineFuzzy(nativeHandle, deltaOp, delta, delta.toDouble(), 1e-4)
                val cnt = NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                onProgress?.invoke(1L, 1L, cnt)
                cnt
            }
            val prevMap = current.associate { it.address to it.value }
            val (items, skipped) = fetchNativeCandidates(
                maxResults = fetchCap,
                defaultType = targetType,
                useCandidateDt = false,
                criteriaFilter = dummyCriteria,
                prevValueMap = prevMap
            )
            return SearchResult(items, (totalFound - skipped).coerceAtLeast(items.size.toLong()))
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
        return SearchResult(refined.take(fetchCap), refined.size.toLong())
    }

    /** 地址蒙版搜索：在选定内存区域或已有候选集中匹配 `(address & maskBits) == (addressPattern & maskBits)` */
    fun maskSearch(
        addressPattern: Long,
        maskBits: Long,
        type: Int,
        sign: Int,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = MAX_CACHED_CANDIDATES
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        val targetType = if (type == ValueType.AUTO) ValueType.DWORD else type
        val size = ValueType.sizeOf(targetType).coerceAtLeast(1)
        val newSearch = previous == null
        val fetchCap = limit.coerceIn(2000, MAX_CACHED_CANDIDATES)

        if (!newSearch) {
            val matched = previous.orEmpty().filter { item ->
                item.region in selectedRegions && ((item.address and maskBits) == (addressPattern and maskBits))
            }.map { item ->
                val v = read(item.address, ValueType.sizeOf(item.flags and 0x7F))
                item.copy(value = v, previousValue = item.value, pointer = classifyPointer(v))
            }
            return SearchResult(matched.take(fetchCap), matched.size.toLong())
        }

        val targetMasked = addressPattern and maskBits
        val out = ArrayList<MemoryItem>(min(fetchCap, 2048))
        var totalFound = 0L
        val regs = regions().filter { it.code in selectedRegions && it.perms.startsWith("r") && it.size > 0 }
        for (reg in regs) {
            var addr = reg.start
            val end = minOf(reg.end, reg.start + 16L * 1024 * 1024)
            while (addr + size <= end) {
                if ((addr and maskBits) == targetMasked) {
                    totalFound++
                    if (out.size < fetchCap) {
                        val v = read(addr, size)
                        out.add(
                            MemoryItem(
                                address = addr,
                                value = v,
                                previousValue = v,
                                flags = targetType,
                                region = reg.code,
                                pointer = classifyPointer(v)
                            )
                        )
                    }
                }
                addr += size
            }
        }
        return SearchResult(out, totalFound)
    }

    /**
     * 指针链搜索（直接委托给底层 C++20 `SearchEngine::searchPointers` / `refinePointers`）：
     * - 在 C++ 层通过 `VmaIndex` + `ArchPtrPolicy` 完成多线程扫描、TBI 标签剥离、自引用剔除与可读 VMA 目标校验；
     * - 彻底消除原来在 Kotlin 层将指针搜索拆为普通范围搜索再截断过滤导致的漏值与计数不一致。
     */
    fun pointerSearch(
        targetAddress: Long,
        maxOffset: Long,
        type: Int,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = MAX_CACHED_CANDIDATES,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        if (cachedRegions.isEmpty()) {
            regions()
        }
        val ptrType = if (type == ValueType.AUTO) {
            if (x64) ValueType.QWORD else ValueType.DWORD
        } else (type and 0x7F)
        val absOffset = abs(maxOffset).coerceAtLeast(0L)
        val fetchCap = limit.coerceIn(2000, MAX_CACHED_CANDIDATES)
        val nativeDt = NativeMemBridge.toNativeDataType(ptrType)

        if (isNativeEngineActive) {
            val mask = NativeMemBridge.buildRegionMask(selectedRegions)
            val totalFound: Long = if (previous == null) {
                removedAddresses.clear()
                if (rootDaemonActive) {
                    RootMemDaemon.setRegionMask(mask)
                    RootMemDaemon.searchPointers(
                        nativeDt,
                        targetAddress,
                        absOffset,
                        NativeMemBridge.PTR_PROT_ALL,
                        true,
                        onProgress
                    )
                } else {
                    NativeMemBridge.nativeSetRegionMask(nativeHandle, mask)
                    NativeMemBridge.nativeSearchPointers(
                        nativeHandle,
                        nativeDt,
                        targetAddress,
                        absOffset,
                        NativeMemBridge.PTR_PROT_ALL,
                        true
                    )
                    val cnt = NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    onProgress?.invoke(1L, 1L, cnt)
                    cnt
                }
            } else {
                if (rootDaemonActive) {
                    RootMemDaemon.setRegionMask(mask)
                    RootMemDaemon.refinePointers(
                        nativeDt,
                        targetAddress,
                        absOffset,
                        NativeMemBridge.PTR_PROT_ALL,
                        onProgress
                    )
                } else {
                    NativeMemBridge.nativeSetRegionMask(nativeHandle, mask)
                    NativeMemBridge.nativeRefinePointers(
                        nativeHandle,
                        nativeDt,
                        targetAddress,
                        absOffset,
                        NativeMemBridge.PTR_PROT_ALL
                    )
                    val cnt = NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    onProgress?.invoke(1L, 1L, cnt)
                    cnt
                }
            }

            val prevMap = previous?.associate { it.address to it.value }
            val (items, skipped) = fetchNativeCandidates(
                maxResults = fetchCap,
                defaultType = ptrType,
                useCandidateDt = false,
                prevValueMap = prevMap
            )
            return SearchResult(items, (totalFound - skipped).coerceAtLeast(items.size.toLong()))
        }

        // 纯 JVM 回退路径
        val cleanTarget = if (ptrType == ValueType.DWORD) {
            targetAddress and 0xFFFFFFFFL
        } else {
            targetAddress and 0x00FFFFFFFFFFFFFFL
        }
        val lo = (cleanTarget - absOffset).coerceAtLeast(0x10000L)
        val hi = cleanTarget.coerceAtLeast(lo)

        fun acceptPointerCandidate(addr: Long, rawVal: Long): Int {
            if ((addr and 3L) != 0L) return 0
            val cleanVal = if (ptrType == ValueType.DWORD) (rawVal and 0xFFFFFFFFL) else (rawVal and 0x00FFFFFFFFFFFFFFL)
            if (cleanVal !in lo..hi) return 0
            val cleanAddr = if (ptrType == ValueType.DWORD) (addr and 0xFFFFFFFFL) else (addr and 0x00FFFFFFFFFFFFFFL)
            if (cleanVal == cleanAddr) return 0
            if (absOffset > 0L && (cleanVal and 3L) != 0L) return 0
            return classifyPointer(rawVal)
        }

        if (previous == null) {
            removedAddresses.clear()
            val c = Criteria(
                type = ptrType,
                value = lo,
                from = lo,
                to = hi,
                isRange = true,
                notRange = false,
                sign = ValueType.SIGN_EQUAL,
                selectedRegions = selectedRegions,
                limit = fetchCap * 2
            )
            val fallback = searchRealJvmFallback(c, selectedRegions, emptyList(), newSearch = true, maxResults = fetchCap * 2, onProgress = onProgress)
                .filter { acceptPointerCandidate(it.address, it.value) != 0 }
                .take(fetchCap)
            return SearchResult(fallback, fallback.size.toLong())
        }

        val itemSz = ValueType.sizeOf(ptrType)
        val filteredPrev = previous.filter { it.region in selectedRegions && it.address !in removedAddresses }
        val rawMap = batchReadRawValues(filteredPrev.map { it.address to itemSz })
        val matched = ArrayList<MemoryItem>()
        for ((idx, item) in filteredPrev.withIndex()) {
            val v = rawMap[item.address] ?: read(item.address, itemSz)
            val ptrKind = acceptPointerCandidate(item.address, v)
            if (ptrKind != 0) {
                matched.add(item.copy(value = v, previousValue = item.value, flags = ptrType, pointer = ptrKind))
            }
            if ((idx and 0xFF) == 0 || idx + 1 == filteredPrev.size) {
                onProgress?.invoke((idx + 1).toLong(), filteredPrev.size.coerceAtLeast(1).toLong(), matched.size.toLong())
            }
        }
        return SearchResult(matched.take(fetchCap), matched.size.toLong())
    }

    /**
     * 统一驱动底层 C++20 引擎 (`RootMemDaemon` 或 `NativeMemBridge`) 执行扫描与改善 (Refine)
     */
    private fun searchViaNativeEngine(
        c: Criteria,
        regions: Set<String>,
        current: List<MemoryItem>,
        newSearch: Boolean,
        maxResults: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        val regMask = NativeMemBridge.buildRegionMask(regions)
        if (rootDaemonActive) {
            RootMemDaemon.setRegionMask(regMask)
        } else {
            NativeMemBridge.nativeSetRegionMask(nativeHandle, regMask)
        }

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

        val isXorType = (c.type and 0x7F) == ValueType.XOR
        val useEncryptedAuto = c.encryptedXor || isXorType

        // 根据输入值是否有符号/无符号自适应确定 SIGN_GREATER_EQ 的最大上界
        fun maxIntBoundFor(typeFlag: Int, lowerVal: Long): Long = when (typeFlag and 0x7F) {
            ValueType.BYTE -> if (lowerVal > Byte.MAX_VALUE) 255L else Byte.MAX_VALUE.toLong()
            ValueType.WORD -> if (lowerVal > Short.MAX_VALUE) 65535L else Short.MAX_VALUE.toLong()
            ValueType.DWORD, ValueType.XOR -> if (lowerVal > Int.MAX_VALUE) 4294967295L else Int.MAX_VALUE.toLong()
            else -> Long.MAX_VALUE
        }

        fun minIntBoundFor(typeFlag: Int, upperVal: Long): Long = when (typeFlag and 0x7F) {
            ValueType.BYTE -> if (upperVal > Byte.MAX_VALUE) 0L else Byte.MIN_VALUE.toLong()
            ValueType.WORD -> if (upperVal > Short.MAX_VALUE) 0L else Short.MIN_VALUE.toLong()
            ValueType.DWORD, ValueType.XOR -> if (upperVal > Int.MAX_VALUE) 0L else Int.MIN_VALUE.toLong()
            else -> Long.MIN_VALUE
        }

        val totalFound: Long = if (newSearch) {
            when {
                c.isText && c.textBytes.isNotEmpty() -> {
                    val maskStr = "x".repeat(c.textBytes.size)
                    if (rootDaemonActive) {
                        RootMemDaemon.searchPattern(c.textBytes, maskStr, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchPattern(nativeHandle, c.textBytes, maskStr)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
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
                    val intMaxArr = LongArray(count) { i -> c.groupTo.getOrElse(i) { c.orderedGroup[i] } }
                    val fltMaxArr = DoubleArray(count) { i ->
                        val gt = c.groupTypes.getOrElse(i) { c.type } and 0x7F
                        val raw = c.groupTo.getOrElse(i) { c.orderedGroup[i] }
                        when (gt) {
                            ValueType.FLOAT -> Float.fromBits(raw.toInt()).toDouble()
                            ValueType.DOUBLE -> Double.fromBits(raw)
                            else -> raw.toDouble()
                        }
                    }
                    val opsArr = IntArray(count) { i -> c.groupOps.getOrElse(i) { 0 } }
                    val span = c.area.coerceIn(2, 65536)
                    if (rootDaemonActive) {
                        RootMemDaemon.searchGroup(typesArr, intArr, fltArr, intMaxArr, fltMaxArr, opsArr, span, c.ordered, true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchGroup(nativeHandle, typesArr, intArr, fltArr, intMaxArr, fltMaxArr, opsArr, span, c.ordered, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                c.xorOffset != 0 -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.searchEncoded(nativeDt, NativeMemBridge.ENC_XOR_OFFSET, c.value, fVal1, c.xorOffset.toLong(), true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchEncoded(nativeHandle, nativeDt, NativeMemBridge.ENC_XOR_OFFSET, c.value, fVal1, c.xorOffset.toLong(), true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                c.xorKey != 0L -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.searchEncoded(nativeDt, NativeMemBridge.ENC_XOR_STATIC, c.value, fVal1, c.xorKey, true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchEncoded(nativeHandle, nativeDt, NativeMemBridge.ENC_XOR_STATIC, c.value, fVal1, c.xorKey, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                useEncryptedAuto -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.searchEncoded(nativeDt, NativeMemBridge.ENC_GG_AUTO, c.value, fVal1, 0L, true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchEncoded(nativeHandle, nativeDt, NativeMemBridge.ENC_GG_AUTO, c.value, fVal1, 0L, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                c.isRange && !c.notRange -> {
                    val iLo = minOf(c.from, c.to)
                    val iHi = maxOf(c.from, c.to)
                    val fLo = minOf(fVal1, fVal2)
                    val fHi = maxOf(fVal1, fVal2)
                    if (rootDaemonActive) {
                        RootMemDaemon.searchRange(nativeDt, iLo, iHi, fLo, fHi, true, true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchRange(nativeHandle, nativeDt, iLo, iHi, fLo, fHi, true, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_GREATER_EQ -> {
                    val maxInt = maxIntBoundFor(c.type, c.value)
                    if (rootDaemonActive) {
                        RootMemDaemon.searchRange(nativeDt, c.value, maxInt, fVal1, Double.MAX_VALUE, true, true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchRange(nativeHandle, nativeDt, c.value, maxInt, fVal1, Double.MAX_VALUE, true, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_LESS_EQ -> {
                    val minInt = minIntBoundFor(c.type, c.value)
                    if (rootDaemonActive) {
                        RootMemDaemon.searchRange(nativeDt, minInt, c.value, -Double.MAX_VALUE, fVal1, true, true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchRange(nativeHandle, nativeDt, minInt, c.value, -Double.MAX_VALUE, fVal1, true, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_EQUAL -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.searchExact(nativeDt, c.value, fVal1, 1e-4, true, onProgress)
                    } else {
                        NativeMemBridge.nativeSearchExact(nativeHandle, nativeDt, c.value, fVal1, 1e-4, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                else -> {
                    val fallback = searchRealJvmFallback(c, regions, current, true, maxResults, onProgress)
                    return SearchResult(fallback, fallback.size.toLong())
                }
            }
        } else {
            when {
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
                    val intMaxArr = LongArray(count) { i -> c.groupTo.getOrElse(i) { c.orderedGroup[i] } }
                    val fltMaxArr = DoubleArray(count) { i ->
                        val gt = c.groupTypes.getOrElse(i) { c.type } and 0x7F
                        val raw = c.groupTo.getOrElse(i) { c.orderedGroup[i] }
                        when (gt) {
                            ValueType.FLOAT -> Float.fromBits(raw.toInt()).toDouble()
                            ValueType.DOUBLE -> Double.fromBits(raw)
                            else -> raw.toDouble()
                        }
                    }
                    val opsArr = IntArray(count) { i -> c.groupOps.getOrElse(i) { 0 } }
                    val span = c.area.coerceIn(2, 65536)
                    if (rootDaemonActive) {
                        RootMemDaemon.refineGroup(typesArr, intArr, fltArr, intMaxArr, fltMaxArr, opsArr, span, c.ordered, true, onProgress)
                    } else {
                        NativeMemBridge.nativeRefineGroup(nativeHandle, typesArr, intArr, fltArr, intMaxArr, fltMaxArr, opsArr, span, c.ordered, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                c.xorOffset != 0 -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.refineEncoded(nativeDt, NativeMemBridge.ENC_XOR_OFFSET, c.value, fVal1, c.xorOffset.toLong(), onProgress)
                    } else {
                        NativeMemBridge.nativeRefineEncoded(nativeHandle, nativeDt, NativeMemBridge.ENC_XOR_OFFSET, c.value, fVal1, c.xorOffset.toLong())
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                c.xorKey != 0L -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.refineEncoded(nativeDt, NativeMemBridge.ENC_XOR_STATIC, c.value, fVal1, c.xorKey, onProgress)
                    } else {
                        NativeMemBridge.nativeRefineEncoded(nativeHandle, nativeDt, NativeMemBridge.ENC_XOR_STATIC, c.value, fVal1, c.xorKey)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                useEncryptedAuto -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.refineEncoded(nativeDt, NativeMemBridge.ENC_GG_AUTO, c.value, fVal1, 0L, onProgress)
                    } else {
                        NativeMemBridge.nativeRefineEncoded(nativeHandle, nativeDt, NativeMemBridge.ENC_GG_AUTO, c.value, fVal1, 0L)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                c.isRange && !c.notRange -> {
                    val iLo = minOf(c.from, c.to)
                    val iHi = maxOf(c.from, c.to)
                    val fLo = minOf(fVal1, fVal2)
                    val fHi = maxOf(fVal1, fVal2)
                    if (rootDaemonActive) {
                        RootMemDaemon.refineRange(nativeDt, iLo, iHi, fLo, fHi, true, onProgress)
                    } else {
                        NativeMemBridge.nativeRefineRange(nativeHandle, nativeDt, iLo, iHi, fLo, fHi, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                !c.isRange && !c.isText && c.orderedGroup.size <= 1 &&
                    (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_GREATER_EQ -> {
                    val maxInt = maxIntBoundFor(c.type, c.value)
                    if (rootDaemonActive) {
                        RootMemDaemon.refineRange(nativeDt, c.value, maxInt, fVal1, Double.MAX_VALUE, true, onProgress)
                    } else {
                        NativeMemBridge.nativeRefineRange(nativeHandle, nativeDt, c.value, maxInt, fVal1, Double.MAX_VALUE, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                !c.isRange && !c.isText && c.orderedGroup.size <= 1 &&
                    (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_LESS_EQ -> {
                    val minInt = minIntBoundFor(c.type, c.value)
                    if (rootDaemonActive) {
                        RootMemDaemon.refineRange(nativeDt, minInt, c.value, -Double.MAX_VALUE, fVal1, true, onProgress)
                    } else {
                        NativeMemBridge.nativeRefineRange(nativeHandle, nativeDt, minInt, c.value, -Double.MAX_VALUE, fVal1, true)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                !c.isRange && !c.isText && c.orderedGroup.size <= 1 &&
                    (c.sign and ValueType.SIGN_MASK) == ValueType.SIGN_EQUAL -> {
                    if (rootDaemonActive) {
                        RootMemDaemon.refineExact(nativeDt, c.value, fVal1, 1e-4, onProgress)
                    } else {
                        NativeMemBridge.nativeRefineExact(nativeHandle, nativeDt, c.value, fVal1, 1e-4)
                        NativeMemBridge.nativeGetCandidateCount(nativeHandle)
                    }
                }
                else -> {
                    val refined = refineLoadedCandidates(c, current, maxResults, onProgress)
                    return SearchResult(refined, refined.size.toLong())
                }
            }
        }

        if (!rootDaemonActive) {
            onProgress?.invoke(1L, 1L, totalFound)
        }

        val prevMap = if (newSearch) null else current.associate { it.address to it.value }
        val (out, skippedRemoved) = fetchNativeCandidates(
            maxResults = maxResults,
            defaultType = c.type,
            useCandidateDt = (c.type == ValueType.AUTO || c.orderedGroup.size > 1),
            criteriaFilter = c,
            prevValueMap = prevMap
        )
        val hasAddrFilter = (c.memoryFrom != 0L || c.memoryTo != Long.MAX_VALUE)
        val effectiveTotal = (totalFound - skippedRemoved).coerceAtLeast(out.size.toLong())
        val reportedTotal = if (hasAddrFilter) out.size.toLong() else effectiveTotal
        return SearchResult(out, reportedTotal)
    }

    private fun refineLoadedCandidates(
        criteria: Criteria,
        current: List<MemoryItem>,
        maxResults: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): List<MemoryItem> {
        val eligible = current.filter { it.address !in removedAddresses && addressMatchesRangeAndMask(it.address, criteria) }
        if (eligible.isEmpty()) {
            onProgress?.invoke(1L, 1L, 0L)
            return emptyList()
        }
        val out = ArrayList<MemoryItem>(min(maxResults, eligible.size))
        val prevMap = current.associate { it.address to it.value }
        val total = eligible.size.toLong()
        var processed = 0L

        for (chunk in eligible.chunked(512)) {
            val batchReq = chunk.map { item ->
                item.address to ValueType.sizeOf(item.flags and 0x7F).coerceIn(1, 8)
            }
            val batchVals = batchReadRawValues(batchReq)
            for (item in chunk) {
                val itemType = item.flags and 0x7F
                val itemSize = ValueType.sizeOf(itemType).coerceIn(1, 8)
                val v = batchVals[item.address] ?: read(item.address, itemSize)
                if (matches(v, criteria.copy(type = itemType), prevMap, item.address)) {
                    out.add(
                        item.copy(
                            value = v,
                            previousValue = item.value,
                            pointer = classifyPointer(v)
                        )
                    )
                    if (out.size >= maxResults) break
                }
            }
            processed += chunk.size
            onProgress?.invoke(processed, total, out.size.toLong())
            if (out.size >= maxResults) break
        }
        return out
    }

    // ---------------- 真实进程 JVM 回退与 VMA 解析实现 ----------------

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
        val nextPrev = if (path.startsWith("/")) path else if (code == "Cb") prevFileBackedPath else ""
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
        val p = path

        if (p.contains("PPSSPP", true) || p.contains("psp_ram", true) || p.contains("psp_vram", true)) {
            return "Ps"
        }

        if (p.endsWith(".ttf", true) || p.endsWith(".otf", true) ||
            p.endsWith(".ttc", true) || p.endsWith(".woff", true) ||
            "/fonts/" in p || "/system/usr/icu/" in p ||
            "/apex/com.android.i18n/etc/icu/" in p || "icudt" in p ||
            "hyphen-data" in p || p.endsWith(".hyb", true) ||
            p == "[vvar]" || p == "[vectors]" || p == "[sigpage]" ||
            p == "[uprobes]" || p == "[kuserhelpers]" ||
            p.startsWith("/dev/binder") || p.startsWith("/dev/hwbinder") ||
            p.startsWith("/dev/vndbinder") || p.startsWith("/dev/__properties__") ||
            p.startsWith("/dev/event-log-tags") || p.startsWith("/dev/pmsg") ||
            p.startsWith("/dev/kmsg") || p.startsWith("/dev/null") ||
            p.startsWith("/dev/urandom") || p.startsWith("/dev/random") ||
            p.startsWith("/dev/cpuctl") || p.startsWith("/dev/cpuset") ||
            p.startsWith("/dev/stune") || p.startsWith("/dev/blkio") ||
            p.startsWith("[anon:GWP-ASan Guard")
        ) {
            return "B"
        }
        if (!readable) {
            return "O"
        }

        if ("kgsl" in p || "/dev/mali" in p || "/dev/pvr" in p ||
            "/dev/nvhost" in p || "/dev/ion" in p || "/dev/dma_heap" in p ||
            "/dev/dri/" in p || "gralloc" in p || "/dev/ashmem/GFX" in p ||
            "dmabuf" in p || "[anon:gralloc" in p
        ) {
            return "V"
        }

        if (executable) {
            if (p == "[vdso]" || p == "[vsyscall]" ||
                p.startsWith("/memfd:jit-cache") || p.startsWith("/memfd:jit-zygote-cache") ||
                "jit-code-cache" in p || "jit-zygote-cache" in p
            ) {
                return "Xs"
            }
            if (p.startsWith("/data/dalvik-cache/")) {
                val isUserAppDalvik = "data@app@" in p || "mnt@expand@" in p || "data@data@" in p
                return if (isUserAppDalvik) "Xa" else "Xs"
            }
            val isAppCode = p.startsWith("/data/app/") || p.startsWith("/data/data/") ||
                p.startsWith("/data/user/") || p.startsWith("/data/user_de/") ||
                p.startsWith("/mnt/asec/") || p.startsWith("/mnt/expand/") ||
                p.startsWith("/sdcard/") || p.startsWith("/storage/")
            if (isAppCode) return "Xa"
            if (p.isEmpty() || p.startsWith("[anon:")) {
                return if (writable) "Xa" else "Xs"
            }
            return "Xs"
        }

        if (p == "[stack]" || p.startsWith("[stack:") ||
            p.startsWith("[anon:stack_and_tls") || p.startsWith("[anon:thread signal stack") ||
            p.startsWith("[anon:bionic_tls")
        ) {
            return "S"
        }

        if (p == "[heap]" || p.startsWith("[heap:")) {
            return "Ch"
        }

        if ("dalvik-" in p || p.startsWith("[anon:dalvik-") ||
            p.startsWith("/dev/ashmem/dalvik-") || p.startsWith("/memfd:dalvik-") ||
            p.startsWith("[anon:art_") || p.startsWith("/memfd:jit-")
        ) {
            val isJavaMetadataOrJit = "bitmap" in p || "mark-stack" in p ||
                "allocation-stack" in p || "live-stack" in p ||
                "card-table" in p || "card table" in p ||
                "mod-union" in p || "LinearAlloc" in p ||
                "indirect-ref" in p || "reference table" in p ||
                "aux-structure" in p || "CompilerMetadata" in p ||
                "jit" in p ||
                "image space" in p || "intern table" in p || "class table" in p
            if (isJavaMetadataOrJit) return "J"

            val isObjectAllocHeap = "main space" in p || "alloc space" in p ||
                "region space" in p || "large object" in p ||
                "non moving space" in p || "zygote space" in p ||
                "bump-pointer" in p || "rosalloc" in p ||
                "dlmalloc" in p || "free list" in p
            return if (writable && isObjectAllocHeap) "Jh" else "J"
        }

        if (p.endsWith(".art") || p.endsWith(".vdex") || p.endsWith(".dex") || p.endsWith(".jar")) {
            return "J"
        }

        if ("libc_malloc" in p || "scudo:" in p || "jemalloc" in p ||
            "GWP-ASan" in p || "bionic_alloc" in p || "malloc" in p
        ) {
            return "Ca"
        }

        if (p == "[anon:.bss]" || p.startsWith("[anon:.bss") || p.startsWith("[anon:bss")) {
            return "Cb"
        }
        val prevIsNativeElf = prevFileBackedPath.isNotEmpty() &&
            (prevFileBackedPath.endsWith(".so") || ".so!" in prevFileBackedPath ||
                prevFileBackedPath.endsWith(".apk") ||
                prevFileBackedPath.startsWith("/system/bin/") || prevFileBackedPath.startsWith("/apex/")) &&
            !prevFileBackedPath.endsWith(".art") && !prevFileBackedPath.endsWith(".oat") &&
            !prevFileBackedPath.endsWith(".dex") && !prevFileBackedPath.endsWith(".vdex")
        if (p.isEmpty() && writable && isPrivate && prevIsNativeElf) {
            return "Cb"
        }

        if ((p.startsWith("/dev/ashmem") && "dalvik" !in p && "jit" !in p) ||
            p.startsWith("[anon:ashmem") || p.startsWith("/memfd:ashmem")
        ) {
            return "As"
        }

        if (p.isEmpty() || p.startsWith("[anon:")) {
            return if (writable && isPrivate) "A" else "O"
        }

        val isNativeElfData = (p.endsWith(".so") || ".so!" in p || p.endsWith(".apk") ||
            p.startsWith("/system/bin/") || p.startsWith("/vendor/bin/") || p.startsWith("/apex/")) &&
            !p.endsWith(".art") && !p.endsWith(".oat") && !p.endsWith(".dex") &&
            !p.endsWith(".vdex") && !p.endsWith(".jar")
        if (writable && isPrivate && isNativeElfData) {
            return "Cd"
        }

        return "O"
    }

    private fun searchRealJvmFallback(
        criteria: Criteria,
        regions: Set<String>,
        current: List<MemoryItem>,
        newSearch: Boolean,
        maxResults: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): List<MemoryItem> {
        if (!newSearch && current.isNotEmpty()) {
            return refineLoadedCandidates(criteria, current, maxResults, onProgress)
        }

        val targetType = if (criteria.type == ValueType.AUTO) ValueType.DWORD else criteria.type
        val size = ValueType.sizeOf(targetType)
        val out = ArrayList<MemoryItem>(min(maxResults, 2048))

        val segs = regions()
            .filter { it.code in regions && it.perms.startsWith("r") && it.size in 1L..(64L * 1024 * 1024) }

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

    private fun addressMatchesRangeAndMask(addr: Long, c: Criteria): Boolean {
        if (c.memoryFrom != 0L && java.lang.Long.compareUnsigned(addr, c.memoryFrom) < 0) return false
        if (c.memoryTo != Long.MAX_VALUE && java.lang.Long.compareUnsigned(addr, c.memoryTo) > 0) return false
        return true
    }

    /**
     * 核心数值匹配函数（支持有符号整数、无符号正整数范围及 IEEE-754 浮点数比较）
     */
    private fun matches(v: Long, c: Criteria, prevMap: Map<Long, Long>, addr: Long): Boolean {
        val itemSize = ValueType.sizeOf(if (c.type == ValueType.AUTO) ValueType.DWORD else (c.type and 0x7F))
        val logicalVal = when {
            c.xorOffset != 0 -> {
                val keyAtOff = read(addr + c.xorOffset, itemSize)
                v xor keyAtOff
            }
            c.xorKey != 0L -> v xor c.xorKey
            c.encryptedXor -> {
                val keyAfter = read(addr + itemSize, itemSize)
                v xor keyAfter
            }
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
     * 按数据类型正确比较两个原始位模式 (raw bits)，兼容原版 GG 的无符号正数与有符号负数语义
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
                if (rawB > Byte.MAX_VALUE) {
                    val ua = rawA and 0xFFL
                    val ub = rawB and 0xFFL
                    ua.compareTo(ub)
                } else {
                    val sa = rawA.toByte().toLong()
                    val sb = rawB.toByte().toLong()
                    sa.compareTo(sb)
                }
            }
            ValueType.WORD -> {
                if (rawB > Short.MAX_VALUE) {
                    val ua = rawA and 0xFFFFL
                    val ub = rawB and 0xFFFFL
                    ua.compareTo(ub)
                } else {
                    val sa = rawA.toShort().toLong()
                    val sb = rawB.toShort().toLong()
                    sa.compareTo(sb)
                }
            }
            ValueType.DWORD, ValueType.XOR -> {
                if (rawB > Int.MAX_VALUE) {
                    val ua = rawA and 0xFFFFFFFFL
                    val ub = rawB and 0xFFFFFFFFL
                    ua.compareTo(ub)
                } else {
                    val sa = rawA.toInt().toLong()
                    val sb = rawB.toInt().toLong()
                    sa.compareTo(sb)
                }
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
}
