package com.littlecodesauce.ggnext.model

/** 结果列表 / 内存编辑器 / 已保存列表 共用条目（预计算十六进制地址字符串以提升列表滚动帧率） */
data class MemoryItem(
    val address: Long,
    var value: Long,
    var flags: Int = ValueType.DWORD,
    var name: String = "",
    var checked: Boolean = false,
    var region: String = "Jh",
    var freeze: Boolean = false,
    var freezeType: Int = 0,
    var freezeFrom: Long = 0L,
    var freezeTo: Long = 0L,
    var possible: Boolean = true,
    /** 指针链长度 / 指针标记（0=无, 1=WX, 2=X, 4=未知, 8=RO, 16=W） */
    var pointer: Int = 0,
    /** 上一次搜索/修改前的值，用于“恢复所选”与“模糊搜索/仅保留有变化的结果” */
    var previousValue: Long = value,
    /** 自定义备注（批量修改时可写入） */
    var note: String = ""
) {
    val size: Int get() = ValueType.sizeOf(flags)
    val typeShort: String get() = ValueType.shortName(flags)

    /** 预计算缓存的地址十六进制文本，避免 LazyColumn 滑动时每帧重复分配字符串 */
    val cachedAddressHex: String = java.lang.Long.toHexString(address).uppercase().padStart(8, '0')

    fun addressHex(): String = cachedAddressHex
}

/** /proc/<pid>/maps 中一段内存区域 */
data class MemoryRegion(
    val start: Long,
    val end: Long,
    val perms: String,
    val path: String,
    val code: String,
    val system: Boolean = false
) {
    val size: Long get() = end - start
    val readable: Boolean get() = perms.contains('r')
    val writable: Boolean get() = perms.contains('w')
    val executable: Boolean get() = perms.contains('x')
    val label: String get() = if (path.isEmpty()) "[anon:$code]" else path.substringAfterLast('/')
}

/** 目标进程 */
data class TargetProcess(
    val pid: Int,
    val packageName: String,
    val label: String,
    val x64: Boolean,
    val uid: Int = 0,
    val sizeBytes: Long = 0L,
    val running: Boolean = true,
    val system: Boolean = false,
    val isSelf: Boolean = false,
    val hasIcon: Boolean = true,
    /** 是否运行于虚拟空间 / 多开分身沙箱中（在进程名左侧显示蓝色 '*'） */
    val isVirtual: Boolean = false,
    /** 虚拟空间或未注册包名的真实 APK 路径（用于提取图标与标签） */
    val apkPath: String = ""
)

/** 已保存列表条目（可带名称与冻结状态） */
typealias SavedItem = MemoryItem
