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
import java.util.zip.ZipFile

/**
 * 持久化 Root C++20 内存引擎守护客户端 (`libmem_engine_root.so`)
 *
 * 解决 Android 普通应用进程 (`u0_aXXX`) 即便在获得 Root 授权后，其自身 JVM/JNI 进程仍因内核 `ptrace_may_access`
 * 与 SELinux 限制无法直接打开其他 UID 进程的 `/proc/<pid>/mem` 或调用 `process_vm_readv` 的核心问题。
 *
 * 通过在持久化的 `su` (`uid=0`) 进程中运行原生编译的 `libmem_engine_root.so`，
 * 实现 100% 真实的跨进程 VMA 区域解析、ARM64 NEON SIMD 搜索、原生指针链扫描、内存读写、内存复制/导出与高频锁值。
 */
object RootMemDaemon {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var stderrThread: Thread? = null
    private var lastAppContext: Context? = null
    private var lastAttachedPid: Int = 0
    private var lastRegionMask: Int = 0x3FFF
    private var isRecovering: Boolean = false

    @Volatile
    var lastStartError: String = ""
        private set

    data class RawCandidate(
        val address: Long,
        val rawValue: Long,
        val meta: Int
    )

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\"'\"'") + "'"

    /**
     * 无损格式化 IEEE-754 双精度浮点数供 C++ `std::istringstream` 解析，
     * 避免 `String.format("%.8f")` 截断极小浮点数 (`1e-9` -> `0.00000000`) 或科学计数法精度丢失。
     */
    private fun formatDouble(v: Double): String = when {
        v.isNaN() -> "0.0"
        v == Double.POSITIVE_INFINITY -> "1.7976931348623157E308"
        v == Double.NEGATIVE_INFINITY -> "-1.7976931348623157E308"
        else -> java.lang.Double.toString(v)
    }

    @Synchronized
    fun ensureStarted(context: Context?): Boolean {
        if (context != null) {
            lastAppContext = context.applicationContext ?: context
        }
        if (isAlive()) return true
        stop()
        lastStartError = ""
        if (!Shell.hasRoot()) {
            lastStartError = "未获取到 Root (su) 权限"
            return false
        }

        val ctx = context ?: lastAppContext
        val binPath = resolveExecutablePath(ctx)
        if (binPath == null) {
            lastStartError = "未找到 libmem_engine_root.so 可执行引擎"
            return false
        }
        val spillDir = ctx?.cacheDir?.absolutePath ?: "/data/local/tmp"
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val nativeDir = ctx?.applicationInfo?.nativeLibraryDir

        // 依次尝试全局挂载命名空间 (--mount-master / -mm) 与标准 su，
        // 统一通过 `su -c <script>` 传递启动脚本，彻底避免 mksh 读取缓冲吞掉 stdin 中的首条 `PING` 指令
        val variants = listOf(
            listOf("su", "--mount-master", "-c"),
            listOf("su", "-mm", "-c"),
            listOf("su", "-c")
        )
        for (suPrefix in variants) {
            if (tryStartDaemonProcess(suPrefix, binPath, spillDir, threads, nativeDir)) {
                lastStartError = ""
                return true
            }
        }
        return false
    }

    private fun tryStartDaemonProcess(
        suPrefix: List<String>,
        binPath: String,
        spillDir: String,
        threads: Int,
        nativeDir: String?
    ): Boolean {
        return try {
            val binDir = File(binPath).parent ?: "/data/local/tmp"
            // 绝不可将 /system/lib64 或 /system/lib 注入 LD_LIBRARY_PATH，
            // 否则在 Android 10~15 APEX Bionic 环境下会覆盖 runtime bionic 导致 linker 崩溃
            val appLibDirs = listOfNotNull(nativeDir, binDir).distinct().joinToString(":")

            val stageTmp = "/data/local/tmp/.ggnext_root_daemon"
            val stageDev = "/dev/.ggnext_root_daemon"
            val qSpill = shellQuote(spillDir)
            val qBin = shellQuote(binPath)
            val qStageTmp = shellQuote(stageTmp)
            val qStageDev = shellQuote(stageDev)

            // 所有前置准备工作严格重定向 >/dev/null 2>&1，确保不污染 stdout 握手协议；
            // 先 rm -f 再 cp -f 消除重复启动时的 ETXTBSY (Text file busy)；
            // 仅当 exit code 为 126/127 (无法 exec) 时才回退下一路径。
            val launchScript = buildString {
                append("magiskpolicy --live \"allow su proc_mem file *\" \"allow magisk proc_mem file *\" >/dev/null 2>&1 || ")
                append("ksud sepolicy patch \"allow su proc_mem file *\" >/dev/null 2>&1 || true; ")
                append("mkdir -p ").append(qSpill).append(" >/dev/null 2>&1 || true; ")
                append("rm -f ").append(qStageTmp).append(' ').append(qStageDev).append(" >/dev/null 2>&1 || true; ")
                if (appLibDirs.isNotEmpty()) {
                    append("export LD_LIBRARY_PATH=").append(shellQuote(appLibDirs)).append("; ")
                }
                append("if cp -f ").append(qBin).append(' ').append(qStageTmp).append(" >/dev/null 2>&1; then ")
                append("chmod 755 ").append(qStageTmp).append(" >/dev/null 2>&1 || true; ")
                append("chcon u:object_r:system_file:s0 ").append(qStageTmp)
                    .append(" >/dev/null 2>&1 || chcon u:object_r:shell_data_file:s0 ").append(qStageTmp)
                    .append(" >/dev/null 2>&1 || true; ")
                append(qStageTmp).append(' ').append(qSpill).append(' ').append(threads).append("; ")
                append("rc=$?; if [ \"\$rc\" -ne 126 ] && [ \"\$rc\" -ne 127 ]; then exit \"\$rc\"; fi; ")
                append("fi; ")
                append("if cp -f ").append(qBin).append(' ').append(qStageDev).append(" >/dev/null 2>&1; then ")
                append("chmod 755 ").append(qStageDev).append(" >/dev/null 2>&1 || true; ")
                append("chcon u:object_r:system_file:s0 ").append(qStageDev).append(" >/dev/null 2>&1 || true; ")
                append(qStageDev).append(' ').append(qSpill).append(' ').append(threads).append("; ")
                append("rc=$?; if [ \"\$rc\" -ne 126 ] && [ \"\$rc\" -ne 127 ]; then exit \"\$rc\"; fi; ")
                append("fi; ")
                append("chmod 755 ").append(qBin).append(" >/dev/null 2>&1 || true; ")
                append("exec ").append(qBin).append(' ').append(qSpill).append(' ').append(threads)
            }

            val pb = ProcessBuilder(suPrefix + launchScript)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val w = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            val r = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))

            val stderrLines = java.util.Collections.synchronizedList(ArrayList<String>(8))
            val errThread = Thread({
                try {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { errReader ->
                        while (true) {
                            val errLine = errReader.readLine() ?: break
                            if (stderrLines.size < 16) {
                                stderrLines.add(errLine)
                            }
                            AppLog.w("RootMemDaemon[stderr]", errLine)
                        }
                    }
                } catch (_: Throwable) {
                }
            }, "RootMemDaemon-stderr").apply {
                isDaemon = true
                start()
            }

            process = p
            writer = w
            reader = r
            stderrThread = errThread

            val pong = sendRawLocked("PING")
            val ok = pong != null && pong.startsWith("OK")
            if (ok) {
                AppLog.i("RootMemDaemon", "Started uid=0 C++20 MemEngine daemon (${suPrefix.joinToString(" ")}): $binPath")
            } else {
                val errSummary = stderrLines.joinToString(" | ").take(200)
                lastStartError = buildString {
                    append("${suPrefix.joinToString(" ")} 握手失败")
                    if (pong != null) append(" (resp=${pong.take(80)})")
                    if (errSummary.isNotEmpty()) append(": $errSummary")
                }
                AppLog.w("RootMemDaemon", lastStartError)
                stop()
            }
            ok
        } catch (t: Throwable) {
            lastStartError = "${suPrefix.joinToString(" ")} 异常: ${t.message}"
            AppLog.e("RootMemDaemon", lastStartError, t)
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
        stderrThread = null
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

        val extractDir = context.codeCacheDir ?: context.filesDir
        val extracted = File(extractDir, "libmem_engine_root.so")
        val extractedCpp = File(extractDir, "libc++_shared.so")
        val apkPath = context.applicationInfo.sourceDir ?: return extracted.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        val abis = Build.SUPPORTED_ABIS ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64")
        return try {
            ZipFile(apkPath).use { zip ->
                for (abi in abis) {
                    val entry = zip.getEntry("lib/$abi/libmem_engine_root.so") ?: continue
                    val expectedSize = entry.size
                    if (extracted.exists() && extracted.length() > 0L && (expectedSize <= 0L || extracted.length() == expectedSize)) {
                        extracted.setExecutable(true, false)
                        return extracted.absolutePath
                    }
                    extracted.delete()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(extracted).use { output ->
                            input.copyTo(output)
                        }
                    }
                    zip.getEntry("lib/$abi/libc++_shared.so")?.let { cppEntry ->
                        if (!extractedCpp.exists() || (cppEntry.size > 0L && extractedCpp.length() != cppEntry.size)) {
                            extractedCpp.delete()
                            zip.getInputStream(cppEntry).use { input ->
                                FileOutputStream(extractedCpp).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    extracted.setExecutable(true, false)
                    return extracted.absolutePath
                }
            }
            extracted.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        } catch (t: Throwable) {
            AppLog.e("RootMemDaemon", "Failed to extract libmem_engine_root.so from APK", t)
            extracted.takeIf { it.exists() && it.length() > 0L }?.absolutePath
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
                    val attachResp = sendRawLocked("ATTACH $lastAttachedPid")
                    if (attachResp == null || !attachResp.startsWith("OK ")) {
                        lastStartError = "恢复附加 pid=$lastAttachedPid 失败 (${attachResp ?: "EOF"})"
                        return false
                    }
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
        if (!ensureRecoveredAndAttached()) return null
        return sendRawLocked(cmd, onProgress)
    }

    @Synchronized
    fun attach(context: Context?, pid: Int): Boolean {
        if (context != null) {
            lastAppContext = context.applicationContext ?: context
        }
        lastAttachedPid = pid
        if (ensureStarted(lastAppContext)) {
            val resp = sendRawLocked("ATTACH $pid")
            if (resp != null && resp.startsWith("OK ")) {
                lastStartError = ""
                return true
            }
            if (resp != null) {
                lastStartError = "守护进程 ATTACH $pid 失败 ($resp)"
            }
        }
        // 若首次 ATTACH 因管道断开或守护进程异常退出失败，重启守护进程并重试一次
        stop()
        if (!ensureStarted(lastAppContext)) return false
        val retryResp = sendRawLocked("ATTACH $pid")
        if (retryResp != null && retryResp.startsWith("OK ")) {
            lastStartError = ""
            return true
        }
        lastStartError = "守护进程重试 ATTACH $pid 失败 (${retryResp ?: "EOF"})"
        return false
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
    fun lockAddressConfigured(
        address: Long,
        data: ByteArray,
        dataType: Int,
        freezeMode: Int,
        minVal: Long,
        maxVal: Long
    ) {
        if (data.isEmpty()) return
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val hex = bytesToHex(data)
        sendCommandLocked("LOCK_CONFIG $addrHex $hex $dataType $freezeMode $minVal $maxVal")
    }

    @Synchronized
    fun setFreezePaused(paused: Boolean) {
        val p = if (paused) 1 else 0
        sendCommandLocked("LOCK_PAUSE $p")
    }

    @Synchronized
    fun setFreezeIntervalMs(intervalMs: Int) {
        val safeMs = intervalMs.coerceIn(5, 5000)
        sendCommandLocked("LOCK_INTERVAL $safeMs")
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
    fun clearSession() {
        sendCommandLocked("CLEAR_SESSION")
    }

    @Synchronized
    fun removeCandidates(addresses: Collection<Long>): Long {
        if (addresses.isEmpty()) return 0L
        var remaining = 0L
        for (chunk in addresses.chunked(512)) {
            val sb = StringBuilder("REMOVE_CANDIDATES ").append(chunk.size)
            for (addr in chunk) {
                sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16))
            }
            val resp = sendCommandLocked(sb.toString()) ?: continue
            if (resp.startsWith("OK ")) {
                remaining = resp.removePrefix("OK ").trim().toLongOrNull() ?: remaining
            }
        }
        return remaining
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
        val cmd = "SEARCH_EXACT $dataType $intTarget ${formatDouble(floatTarget)} ${formatDouble(epsilon)} $a"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
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
        val cmd = "REFINE_EXACT $dataType $intTarget ${formatDouble(floatTarget)} ${formatDouble(epsilon)}"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
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
        val cmd = "SEARCH_RANGE $dataType $intMin $intMax ${formatDouble(floatMin)} ${formatDouble(floatMax)} $inc $aln"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
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
        val cmd = "REFINE_RANGE $dataType $intMin $intMax ${formatDouble(floatMin)} ${formatDouble(floatMax)} $inc"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun searchPointers(
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int = NativeMemBridge.PTR_PROT_ALL,
        aligned: Boolean = true,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val aln = if (aligned) 1 else 0
        val addrHex = java.lang.Long.toUnsignedString(targetAddress, 16)
        val uOff = java.lang.Long.toUnsignedString(if (maxOffset >= 0L) maxOffset else -maxOffset)
        val uProt = protMask.toLong() and 0xFFFFFFFFL
        val cmd = "SEARCH_POINTER $dataType $addrHex $uOff $uProt $aln"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun refinePointers(
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int = NativeMemBridge.PTR_PROT_ALL,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val addrHex = java.lang.Long.toUnsignedString(targetAddress, 16)
        val uOff = java.lang.Long.toUnsignedString(if (maxOffset >= 0L) maxOffset else -maxOffset)
        val uProt = protMask.toLong() and 0xFFFFFFFFL
        val cmd = "REFINE_POINTER $dataType $addrHex $uOff $uProt"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
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
                .append(' ').append(formatDouble(floatVals[i]))
                .append(' ').append(iMax)
                .append(' ').append(formatDouble(fMax))
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
        val cmd = "SEARCH_ENCODED $dataType $encodedMode $logicalInt ${formatDouble(logicalFloat)} $uKey $aln"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
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
        val cmd = "REFINE_ENCODED $dataType $encodedMode $logicalInt ${formatDouble(logicalFloat)} $uKey"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
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
        val cmd = "FUZZY_REFINE $deltaOp $intDelta ${formatDouble(floatDelta)} ${formatDouble(epsilon)}"
        val resp = sendCommandLocked(cmd, onProgress) ?: return 0L
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
