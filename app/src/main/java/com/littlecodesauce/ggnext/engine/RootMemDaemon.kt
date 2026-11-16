package com.littlecodesauce.ggnext.engine

import android.content.Context
import android.os.Build
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.model.MemoryRegion
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 持久化 Root C++20 内存引擎守护客户端 (`libmem_engine_root.so`)
 *
 * 解决 Android 普通应用进程 (`u0_aXXX`) 即便在获得 Root 授权后，其自身 JVM/JNI 进程仍因内核 `ptrace_may_access`
 * 与 SELinux 限制无法直接打开其他 UID 进程的 `/proc/<pid>/mem` 或调用 `process_vm_readv` 的核心问题。
 *
 * 通过在持久化的 `su` (`uid=0`) 进程中运行原生编译的 `libmem_engine_root.so`，
 * 实现 100% 真实的跨进程 VMA 区域解析、ARM64 NEON SIMD 搜索、内存读写、内存复制/导出与高频锁值。
 */
object RootMemDaemon {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var lastAppContext: Context? = null
    private var lastAttachedPid: Int = 0
    private var lastRegionMask: Int = 0x3FFF
    private var isRecovering: Boolean = false

    data class RawCandidate(
        val address: Long,
        val rawValue: Long,
        val meta: Int
    )

    @Synchronized
    fun ensureStarted(context: Context?): Boolean {
        if (context != null) {
            lastAppContext = context.applicationContext ?: context
        }
        if (isAlive()) return true
        stop()
        if (!Shell.hasRoot()) return false

        val ctx = context ?: lastAppContext
        val binPath = resolveExecutablePath(ctx) ?: return false
        val spillDir = ctx?.cacheDir?.absolutePath ?: "/data/local/tmp"
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        return try {
            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(false)
            val p = pb.start()
            val w = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            val r = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))

            val binDir = File(binPath).parent ?: "/data/local/tmp"
            val nativeDir = ctx?.applicationInfo?.nativeLibraryDir
            val libDirs = listOfNotNull(nativeDir, binDir, "/system/lib64", "/system/lib")
                .distinct()
                .joinToString(":")
            // 赋予可执行权限并注入 LD_LIBRARY_PATH (c++_shared) 后直接 exec 启动 C++20 Root 引擎守护进程
            w.write("chmod 755 \"$binPath\" 2>/dev/null || true\n")
            w.write("export LD_LIBRARY_PATH=\"$libDirs:\${LD_LIBRARY_PATH:-}\"\n")
            w.write("exec \"$binPath\" \"$spillDir\" $threads\n")
            w.flush()

            process = p
            writer = w
            reader = r

            val pong = sendRawLocked("PING")
            val ok = pong != null && pong.startsWith("OK")
            if (ok) {
                AppLog.i("RootMemDaemon", "Started uid=0 C++20 MemEngine daemon: $binPath")
            } else {
                AppLog.w("RootMemDaemon", "Failed to receive PONG from $binPath (resp=$pong)")
                stop()
            }
            ok
        } catch (t: Throwable) {
            AppLog.e("RootMemDaemon", "Failed to start RootMemDaemon", t)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            writer?.write("EXIT\n")
            writer?.flush()
        } catch (_: Throwable) {
        }
        try {
             reader?.close()
        } catch (_: Throwable) {
        }
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        writer = null
        reader = null
    }

    private fun isAlive(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun resolveExecutablePath(context: Context?): String? {
        if (context == null) return null
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val directSo = File(nativeDir, "libmem_engine_root.so")
        if (directSo.exists() && directSo.length() > 0L) {
            return directSo.absolutePath
        }

        // 若系统未解压 nativeLibs，则直接从 APK (sourceDir) 中提取对应 ABI 的 libmem_engine_root.so 与 libc++_shared.so
        val extractDir = context.codeCacheDir ?: context.filesDir
        val extracted = File(extractDir, "libmem_engine_root.so")
        val extractedCpp = File(extractDir, "libc++_shared.so")
        if (extracted.exists() && extracted.length() > 0L && extractedCpp.exists() && extractedCpp.length() > 0L) {
            extracted.setExecutable(true, false)
            return extracted.absolutePath
        }

        val apkPath = context.applicationInfo.sourceDir ?: return null
        val abis = Build.SUPPORTED_ABIS ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64")
        return try {
            ZipFile(apkPath).use { zip ->
                for (abi in abis) {
                    val entry = zip.getEntry("lib/$abi/libmem_engine_root.so") ?: continue
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(extracted).use { output ->
                            input.copyTo(output)
                        }
                    }
                    zip.getEntry("lib/$abi/libc++_shared.so")?.let { cppEntry ->
                        zip.getInputStream(cppEntry).use { input ->
                            FileOutputStream(extractedCpp).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    extracted.setExecutable(true, false)
                    return extracted.absolutePath
                }
            }
            null
        } catch (t: Throwable) {
            AppLog.e("RootMemDaemon", "Failed to extract libmem_engine_root.so from APK", t)
            null
        }
    }

    private fun sendRawLocked(
        cmd: String,
        onProgress: ((doneBytes: Long, totalBytes: Long, foundCount: Long) -> Unit)? = null
    ): String? {
        val w = writer ?: return null
        val r = reader ?: return null
        try {
            w.write(cmd)
            w.write("\n")
            w.flush()
            while (true) {
                val line = r.readLine() ?: return null
                if (line.startsWith("PROGRESS ")) {
                    val parts = line.trim().split(' ')
                    if (parts.size >= 4) {
                        val done = parts[1].toLongOrNull() ?: 0L
                        val total = parts[2].toLongOrNull() ?: 1L
                        val found = parts[3].toLongOrNull() ?: 0L
                        onProgress?.invoke(done, total, found)
                    }
                    continue
                }
                return line
            }
        } catch (_: Throwable) {
            stop()
            return null
        }
    }

    private fun ensureRecoveredAndAttached(): Boolean {
        if (isAlive() && writer != null && reader != null) return true
        if (isRecovering) return false
        isRecovering = true
        return try {
            if (!ensureStarted(lastAppContext)) {
                false
            } else {
                if (lastAttachedPid > 0) {
                    sendRawLocked("ATTACH $lastAttachedPid")
                    val uMask = lastRegionMask.toLong() and 0xFFFFFFFFL
                    sendRawLocked("SET_MASK $uMask")
                }
                true
            }
        } finally {
            isRecovering = false
        }
    }

    private fun sendCommandLocked(
        cmd: String,
        onProgress: ((doneBytes: Long, totalBytes: Long, foundCount: Long) -> Unit)? = null
    ): String? {
        if (!ensureRecoveredAndAttached()) return null
        val firstTry = sendRawLocked(cmd, onProgress)
        if (firstTry != null) return firstTry
        // 若守护进程在命令执行期间意外断开，自动重连恢复并重试一次
        if (!ensureRecoveredAndAttached()) return null
        return sendRawLocked(cmd, onProgress)
    }

    @Synchronized
    fun attach(context: Context?, pid: Int): Boolean {
        if (context != null) {
            lastAppContext = context.applicationContext ?: context
        }
        lastAttachedPid = pid
        if (!ensureStarted(lastAppContext)) return false
        val resp = sendRawLocked("ATTACH $pid") ?: return false
        return resp.startsWith("OK ")
    }

    @Synchronized
    fun setRegionMask(mask: Int): Boolean {
        lastRegionMask = mask
        val uMask = mask.toLong() and 0xFFFFFFFFL
        val resp = sendCommandLocked("SET_MASK $uMask") ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun getRegions(): List<MemoryRegion> {
        val header = sendCommandLocked("REGIONS") ?: return emptyList()
        if (!header.startsWith("OK ")) return emptyList()
        val count = header.removePrefix("OK ").trim().toIntOrNull() ?: 0
        if (count <= 0) return emptyList()
        val r = reader ?: return emptyList()
        val out = ArrayList<MemoryRegion>(count)
        for (i in 0 until count) {
            val line = r.readLine() ?: break
            val parts = line.split(' ', limit = 5)
            if (parts.size < 4) continue
            val start = parts[0].toULongOrNull(16)?.toLong() ?: continue
            val end = parts[1].toULongOrNull(16)?.toLong() ?: continue
            val perms = parts[2]
            val code = parts[3]
            val rawPath = if (parts.size >= 5) parts[4] else "-"
            val path = if (rawPath == "-") "" else rawPath
            out.add(
                MemoryRegion(
                    start = start,
                    end = end,
                    perms = perms,
                    path = path,
                    code = code,
                    system = (code == "Xs" || code == "O")
                )
            )
        }
        return out
    }

    @Synchronized
    fun readBlock(address: Long, size: Int): ByteArray? {
        val safeLen = size.coerceIn(1, 16 * 1024 * 1024)
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val resp = sendCommandLocked("READ $addrHex $safeLen") ?: return null
        if (!resp.startsWith("OK ")) return null
        val hex = resp.removePrefix("OK ").trim()
        return hexToBytes(hex, safeLen)
    }

    /**
     * 批量单次 IPC 往返读取多个地址的最新原始值，用于搜索列表、保存列表、内存视图的实时刷新
     */
    @Synchronized
    fun batchReadValues(items: List<Pair<Long, Int>>): Map<Long, Long> {
        if (items.isEmpty() || lastAttachedPid <= 0) return emptyMap()
        val result = HashMap<Long, Long>(items.size)
        return try {
            for (chunk in items.chunked(512)) {
                val sb = StringBuilder("BATCH_READ ").append(chunk.size)
                for ((addr, sz) in chunk) {
                    val clampedSz = sz.coerceIn(1, 8)
                    sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16)).append(':').append(clampedSz)
                }
                val header = sendCommandLocked(sb.toString()) ?: break
                if (!header.startsWith("OK ")) break
                val r = reader ?: break
                val count = header.removePrefix("OK ").trim().toIntOrNull() ?: 0
                for (i in 0 until count) {
                    val line = r.readLine() ?: break
                    val parts = line.trim().split(' ')
                    if (parts.size < 3) continue
                    val addr = parts[0].toULongOrNull(16)?.toLong() ?: continue
                    val ok = parts[1].toIntOrNull() ?: 0
                    val rawVal = parts[2].toLongOrNull() ?: 0L
                    if (ok == 1) {
                        result[addr] = rawVal
                    }
                }
            }
            result
        } catch (_: Throwable) {
            stop()
            result
        }
    }

    @Synchronized
    fun writeBlock(address: Long, data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val hex = bytesToHex(data)
        val resp = sendCommandLocked("WRITE $addrHex $hex") ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun copyMemory(fromAddr: Long, toAddr: Long, length: Long): Long {
        val fromHex = java.lang.Long.toUnsignedString(fromAddr, 16)
        val toHex = java.lang.Long.toUnsignedString(toAddr, 16)
        val resp = sendCommandLocked("COPY $fromHex $toHex $length") ?: return 0L
        if (!resp.startsWith("OK ")) return 0L
        return resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L
    }

    @Synchronized
    fun dumpMemory(startAddr: Long, endAddr: Long, outPath: String): Long {
        val startHex = java.lang.Long.toUnsignedString(startAddr, 16)
        val endHex = java.lang.Long.toUnsignedString(endAddr, 16)
        val resp = sendCommandLocked("DUMP $startHex $endHex $outPath") ?: return 0L
        if (!resp.startsWith("OK ")) return 0L
        return resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L
    }

    @Synchronized
    fun lockAddress(address: Long, data: ByteArray) {
        if (data.isEmpty()) return
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val hex = bytesToHex(data)
        sendCommandLocked("LOCK $addrHex $hex")
    }

    @Synchronized
    fun unlockAddress(address: Long) {
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        sendCommandLocked("UNLOCK $addrHex")
    }

    @Synchronized
    fun unlockAll() {
        sendCommandLocked("UNLOCK_ALL")
    }

    @Synchronized
    fun searchExact(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val a = if (aligned) 1 else 0
        val resp = sendCommandLocked(
            String.format(Locale.US, "SEARCH_EXACT %d %d %.8f %.8f %d", dataType, intTarget, floatTarget, epsilon, a),
            onProgress
        ) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun refineExact(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val resp = sendCommandLocked(
            String.format(Locale.US, "REFINE_EXACT %d %d %.8f %.8f", dataType, intTarget, floatTarget, epsilon),
            onProgress
        ) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun searchRange(
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val inc = if (inclusive) 1 else 0
        val aln = if (aligned) 1 else 0
        val resp = sendCommandLocked(
            String.format(
                Locale.US,
                "SEARCH_RANGE %d %d %d %.8f %.8f %d %d",
                dataType, intMin, intMax, floatMin, floatMax, inc, aln
            ),
            onProgress
        ) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun refineRange(
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val inc = if (inclusive) 1 else 0
        val resp = sendCommandLocked(
            String.format(
                Locale.US,
                "REFINE_RANGE %d %d %d %.8f %.8f %d",
                dataType, intMin, intMax, floatMin, floatMax, inc
            ),
            onProgress
        ) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun searchGroup(
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long = sendGroupCmd("SEARCH_GROUP", types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned, onProgress)

    @Synchronized
    fun refineGroup(
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long = sendGroupCmd("REFINE_GROUP", types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned, onProgress)

    private fun sendGroupCmd(
        cmdName: String,
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val ord = if (ordered) 1 else 0
        val aln = if (aligned) 1 else 0
        val sb = StringBuilder()
        sb.append(cmdName).append(' ').append(windowSize).append(' ')
            .append(ord).append(' ').append(aln).append(' ').append(types.size)
        for (i in types.indices) {
            val iMax = intMaxVals.getOrElse(i) { intVals[i] }
            val fMax = floatMaxVals.getOrElse(i) { floatVals[i] }
            val op = fieldOps.getOrElse(i) { 0 }
            sb.append(' ').append(types[i])
                .append(' ').append(intVals[i])
                .append(' ').append(String.format(Locale.US, "%.8f", floatVals[i]))
                .append(' ').append(iMax)
                .append(' ').append(String.format(Locale.US, "%.8f", fMax))
                .append(' ').append(op)
        }
        val resp = sendCommandLocked(sb.toString(), onProgress) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun searchPattern(
        pattern: ByteArray,
        mask: String,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val hex = bytesToHex(pattern)
        val safeMask = mask.ifEmpty { "x".repeat(pattern.size) }
        val resp = sendCommandLocked("SEARCH_PATTERN $hex $safeMask", onProgress) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun searchEncoded(
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val aln = if (aligned) 1 else 0
        val uKey = java.lang.Long.toUnsignedString(keyOrMask)
        val resp = sendCommandLocked(
            String.format(
                Locale.US,
                "SEARCH_ENCODED %d %d %d %.8f %s %d",
                dataType, encodedMode, logicalInt, logicalFloat, uKey, aln
            ),
            onProgress
        ) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun refineEncoded(
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val uKey = java.lang.Long.toUnsignedString(keyOrMask)
        val resp = sendCommandLocked(
            String.format(
                Locale.US,
                "REFINE_ENCODED %d %d %d %.8f %s",
                dataType, encodedMode, logicalInt, logicalFloat, uKey
            ),
            onProgress
        ) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun startUnknownSnapshot(
        dataType: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val resp = sendCommandLocked("FUZZY_INIT $dataType", onProgress) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun refineFuzzy(
        deltaOp: Int,
        intDelta: Long,
        floatDelta: Double,
        epsilon: Double = 1e-4,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val resp = sendCommandLocked(
            String.format(Locale.US, "FUZZY_REFINE %d %d %.8f %.8f", deltaOp, intDelta, floatDelta, epsilon),
            onProgress
        ) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun getCandidates(offset: Int, limit: Int): List<RawCandidate> {
        val header = sendCommandLocked("GET_CANDIDATES $offset $limit") ?: return emptyList()
        if (!header.startsWith("OK ")) return emptyList()
        val count = header.removePrefix("OK ").trim().toIntOrNull() ?: 0
        if (count <= 0) return emptyList()
        val r = reader ?: return emptyList()
        val out = ArrayList<RawCandidate>(count)
        for (i in 0 until count) {
            val line = r.readLine() ?: break
            val parts = line.trim().split(' ')
            if (parts.size < 3) continue
            val addr = parts[0].toULongOrNull(16)?.toLong() ?: continue
            val rawVal = parts[1].toLongOrNull() ?: 0L
            val meta = parts[2].toULongOrNull()?.toInt() ?: 0
            out.add(RawCandidate(addr, rawVal, meta))
        }
        return out
    }

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    private fun hexToBytes(hex: String, expectedLen: Int): ByteArray {
        val out = ByteArray(expectedLen)
        val maxPairs = minOf(expectedLen, hex.length / 2)
        for (i in 0 until maxPairs) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi >= 0 && lo >= 0) {
                out[i] = ((hi shl 4) or lo).toByte()
            }
        }
        return out
    }
}
