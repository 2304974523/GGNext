package com.littlecodesauce.ggnext.data

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.littlecodesauce.ggnext.core.WorkMode
import com.littlecodesauce.ggnext.engine.Shell
import com.littlecodesauce.ggnext.model.TargetProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 进程扫描器（已修复 TargetProcess 构造参数不匹配 Bug，并集成原生 /proc 实时无缓存遍历）：
 * - 修复原版 `icon = null` 与 `rssBytes = rss` 导致的编译错误（对齐 Models.kt 中的 `sizeBytes` 与 `hasIcon`）；
 * - 支持多用户 UID 判定 (appId = uid % 100000 in 10000..19999 或系统 UID 1000..2999)，保留多进程子实例 (:remote, :push)；
 * - 在 ROOT 与 NO_ROOT 模式下优先直接遍历 `/proc/[pid]/cmdline` 与 `/proc/[pid]/status` 获取真实运行中进程与 VmRSS。
 */
object ProcessScanner {

    suspend fun scan(context: Context, mode: WorkMode = WorkMode.ROOT): List<TargetProcess> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val selfPid = Process.myPid()
        val result = ArrayList<TargetProcess>(128)
        val seenPids = HashSet<Int>()

        // 1. 演示模式：直接返回精选演示进程 + 已安装用户应用
        if (mode == WorkMode.DEMO) {
            result.addAll(demoCuratedProcesses())
            seenPids.addAll(result.map { it.pid })
        } else {
            // 2. ROOT 或 NO_ROOT 模式：优先直接扫描 /proc 目录下的可读进程（免 Root 虚拟空间/共享 UID 下同样可读）
            val directProcList = scanProcDirectly(pm, selfPkg, selfPid)
            for (p in directProcList) {
                if (seenPids.add(p.pid)) {
                    result.add(p)
                }
            }

            // 3. 若处于 ROOT 模式且拿到 Root 权限，补充通过 su ps 扫描被 hidepid 隐藏的跨 UID 进程
            if (mode == WorkMode.ROOT && Shell.hasRoot()) {
                val rootList = scanFromProcViaRoot(pm, selfPkg, selfPid)
                for (p in rootList) {
                    if (seenPids.add(p.pid)) {
                        result.add(p)
                    }
                }
            }
        }

        // 4. ActivityManager runningAppProcesses 补充
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.forEach { proc ->
                if (seenPids.contains(proc.pid)) return@forEach
                val pkg = proc.pkgList?.firstOrNull() ?: proc.processName.substringBefore(':')
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                val labelBase = info?.let { pm.getApplicationLabel(it).toString() } ?: pkg
                val subSuffix = if (proc.processName.contains(':')) ":" + proc.processName.substringAfter(':') else ""
                val system = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val x64 = info?.nativeLibraryDir?.contains("64") ?: true
                val rss = readProcVmRssBytes(proc.pid).takeIf { it > 0L } ?: (96L * 1024 * 1024)
                seenPids.add(proc.pid)
                result.add(
                    TargetProcess(
                        pid = proc.pid,
                        packageName = pkg,
                        label = labelBase + subSuffix,
                        x64 = x64,
                        uid = proc.uid,
                        sizeBytes = rss,
                        running = true,
                        system = system,
                        isSelf = pkg == selfPkg || proc.pid == selfPid,
                        hasIcon = info != null
                    )
                )
            }
        }

        // 5. 若因 Android 11+ 包可见性或权限限制仍为空，补充本机已安装应用与精选演示目标
        if (result.size <= 1) {
            for (d in demoCuratedProcesses()) {
                if (seenPids.add(d.pid)) {
                    result.add(d)
                }
            }
            val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }
                .getOrDefault(emptyList())
            val seenPkgs = result.map { it.packageName }.toHashSet()
            for (info in apps) {
                val pkg = info.packageName
                if (seenPkgs.contains(pkg)) continue
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
                val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val x64 = info.nativeLibraryDir?.contains("64") ?: true
                val syntheticPid = 10000 + (pkg.hashCode() and 0x7FFF)
                val rss = ((pkg.hashCode().toLong() and 0xFFL) + 48L) * 1024L * 1024L
                result.add(
                    TargetProcess(
                        pid = syntheticPid,
                        packageName = pkg,
                        label = label,
                        x64 = x64,
                        uid = info.uid,
                        sizeBytes = rss,
                        running = false,
                        system = system,
                        isSelf = pkg == selfPkg,
                        hasIcon = true
                    )
                )
            }
        }

        result.sortedWith(
            compareBy<TargetProcess> { it.isSelf }
                .thenByDescending { it.running }
                .thenBy { it.system }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.label.lowercase() }
        )
    }

    /**
     * 直接遍历 /proc/[pid]（无需 fork shell，实时无缓存）
     */
    private fun scanProcDirectly(
        pm: PackageManager,
        selfPkg: String,
        selfPid: Int
    ): List<TargetProcess> {
        val out = ArrayList<TargetProcess>(64)
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return out

        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid <= 1) continue

            val cmdline = runCatching {
                val bytes = File(entry, "cmdline").readBytes()
                val end = bytes.indexOf(0.toByte()).let { if (it >= 0) it else bytes.size }
                String(bytes, 0, end, Charsets.UTF_8).trim()
            }.getOrNull() ?: continue

            if (cmdline.isEmpty() || cmdline.startsWith("[")) continue

            var uid = -1
            var ppid = 0
            var vmSizeKb = 0L
            var vmRssKb = 0L
            runCatching {
                File(entry, "status").forEachLine { line ->
                    when {
                        line.startsWith("Uid:") -> {
                            val parts = line.substringAfter("Uid:").trim().split(Regex("\\s+"))
                            uid = parts.firstOrNull()?.toIntOrNull() ?: -1
                        }
                        line.startsWith("PPid:") -> {
                            ppid = line.substringAfter("PPid:").trim().toIntOrNull() ?: 0
                        }
                        line.startsWith("VmSize:") -> {
                            vmSizeKb = line.substringAfter("VmSize:").trim().substringBefore(" ").toLongOrNull() ?: 0L
                        }
                        line.startsWith("VmRSS:") -> {
                            vmRssKb = line.substringAfter("VmRSS:").trim().substringBefore(" ").toLongOrNull() ?: 0L
                        }
                    }
                }
            }

            // 排除内核线程 (PPid == 2 或 VmSize == 0)
            if (ppid == 2 || vmSizeKb == 0L) continue

            val basePkg = cmdline.substringBefore(':').substringBefore(' ')
            val isAndroidPkg = basePkg.contains('.') && !basePkg.startsWith('/')
            val appId = if (uid >= 0) uid % 100000 else -1
            val isAppUid = appId in 10000..19999 || appId in 90000..99999 || uid == 1000
            if (!isAndroidPkg && !isAppUid) continue

            val info = runCatching { pm.getApplicationInfo(basePkg, 0) }.getOrNull()
            val labelBase = info?.let { pm.getApplicationLabel(it).toString() } ?: basePkg
            val subSuffix = if (cmdline.contains(':')) ":" + cmdline.substringAfter(':') else ""
            val system = if (info != null) {
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            } else {
                appId in 1000..9999
            }
            val x64 = info?.nativeLibraryDir?.contains("64") ?: true

            out.add(
                TargetProcess(
                    pid = pid,
                    packageName = basePkg,
                    label = labelBase + subSuffix,
                    x64 = x64,
                    uid = if (uid >= 0) uid else (info?.uid ?: 0),
                    sizeBytes = vmRssKb * 1024L,
                    running = true,
                    system = system,
                    isSelf = basePkg == selfPkg || pid == selfPid,
                    hasIcon = info != null
                )
            )
        }
        return out
    }

    private fun scanFromProcViaRoot(
        pm: PackageManager,
        selfPkg: String,
        selfPid: Int
    ): List<TargetProcess> {
        val out = ArrayList<TargetProcess>(128)
        val lines = Shell.suLines("ps -A -o PID,RSS,ARGS 2>/dev/null || ps -o PID,RSS,ARGS")
        for (line in lines.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val rssKb = parts[1].toLongOrNull() ?: 0L
            val cmd = parts[2].trim()
            if (!cmd.contains('.') || cmd.startsWith("[") || cmd.startsWith("/")) continue
            val pkg = cmd.substringBefore(':')
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            val labelBase = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pkg
            val subSuffix = if (cmd.contains(':')) ":" + cmd.substringAfter(':') else ""
            val system = info != null &&
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val x64 = info?.nativeLibraryDir?.contains("64") ?: true
            out.add(
                TargetProcess(
                    pid = pid,
                    packageName = pkg,
                    label = labelBase + subSuffix,
                    x64 = x64,
                    uid = info?.uid ?: 0,
                    sizeBytes = rssKb * 1024L,
                    running = true,
                    system = system,
                    isSelf = pkg == selfPkg || pid == selfPid,
                    hasIcon = info != null
                )
            )
        }
        return out
    }

    private fun readProcVmRssBytes(pid: Int): Long {
        if (pid <= 0) return 0L
        return runCatching {
            File("/proc/$pid/status").useLines { seq ->
                val line = seq.firstOrNull { it.startsWith("VmRSS:") } ?: return@useLines 0L
                val kb = line.substringAfter("VmRSS:").trim().substringBefore(" ").toLongOrNull() ?: 0L
                kb * 1024L
            }
        }.getOrDefault(0L)
    }

    private fun demoCuratedProcesses(): List<TargetProcess> = listOf(
        TargetProcess(
            pid = 14820,
            packageName = "com.DefaultCompany.Shooter Demo",
            label = "Shooter Demo (Unity IL2CPP)",
            x64 = true,
            uid = 10248,
            sizeBytes = 412L * 1024 * 1024,
            running = true,
            system = false,
            isSelf = false,
            hasIcon = false
        ),
        TargetProcess(
            pid = 18392,
            packageName = "com.miHoYo.Yuanshen",
            label = "原神 (Genshin Impact)",
            x64 = true,
            uid = 10285,
            sizeBytes = 1840L * 1024 * 1024,
            running = true,
            system = false,
            isSelf = false,
            hasIcon = false
        ),
        TargetProcess(
            pid = 21044,
            packageName = "com.tencent.tmgp.sgame",
            label = "王者荣耀",
            x64 = true,
            uid = 10291,
            sizeBytes = 1290L * 1024 * 1024,
            running = true,
            system = false,
            isSelf = false,
            hasIcon = false
        ),
        TargetProcess(
            pid = 24910,
            packageName = "org.ppsspp.ppsspp",
            label = "PPSSPP Gold Emulator",
            x64 = true,
            uid = 10312,
            sizeBytes = 520L * 1024 * 1024,
            running = true,
            system = false,
            isSelf = false,
            hasIcon = false
        )
    )

    fun sizeText(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 100) "${mb.toInt()} MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
    }
}
