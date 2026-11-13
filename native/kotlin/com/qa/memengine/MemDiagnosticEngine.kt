package com.qa.memengine

import java.io.Closeable

/**
 * Decoupled, thread-safe Kotlin wrapper for the C++20 Cross-Process Memory Analysis
 * and Search Engine (`libmem_engine_jni.so`).
 *
 * Designed exclusively for Android memory diagnostics, state validation, and
 * automated QA boundary testing under standard Linux/Android DAC & SELinux models.
 */
class MemDiagnosticEngine(
    workerThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
    spillDirectory: String = "/data/local/tmp"
) : Closeable {

    companion object {
        init {
            System.loadLibrary("mem_engine_jni")
        }

        // Granular VMA Region Classification Bitmasks
        const val REGION_JH: Int = 1 shl 0  // Java Heap (ART main/region/large/zygote spaces)
        const val REGION_CH: Int = 1 shl 1  // C/C++ Native Heap ([heap])
        const val REGION_CA: Int = 1 shl 2  // C++ Allocator (scudo,libc_malloc, jemalloc, GWP-ASan)
        const val REGION_CD: Int = 1 shl 3  // C++ .data segment
        const val REGION_CB: Int = 1 shl 4  // C++ .bss segment
        const val REGION_A: Int  = 1 shl 5  // Anonymous RW mappings
        const val REGION_S: Int  = 1 shl 6  // Stack ([stack], [stack:tid])
        const val REGION_AS: Int = 1 shl 7  // Ashmem (/dev/ashmem*, /memfd:*)
        const val REGION_XA: Int = 1 shl 8  // Code App (/data/app r-xp, JIT cache)
        const val REGION_XS: Int = 1 shl 9  // Code System (/system, /apex, /vendor r-xp)
        const val REGION_V: Int  = 1 shl 10 // Video / GPU (/dev/kgsl-3d0, /dev/mali*, /dev/dma_heap)
        const val REGION_PS: Int = 1 shl 11 // PPSSPP Emulator RAM
        const val REGION_B: Int  = 1 shl 12 // Bad / Unreadable Guard Pages (PROT_NONE)
        const val REGION_O: Int  = 1 shl 13 // Other readable mappings

        const val REGION_DEFAULT_RW: Int =
            REGION_JH or REGION_CH or REGION_CA or REGION_CD or REGION_CB or REGION_A or REGION_S or REGION_AS or REGION_PS
    }

    enum class DataType(val code: Int) {
        INT8(0),
        INT16(1),
        INT32(2),
        INT64(3),
        FLOAT(4),
        DOUBLE(5),
        BYTE_ARRAY(6),
        UTF8(7),
        UTF16(8),
        AUTO(9)
    }

    enum class SearchOp(val code: Int) {
        EXACT(0),
        NOT_EQUAL(1),
        GREATER(2),
        GREATER_EQ(3),
        LESS(4),
        LESS_EQ(5),
        RANGE_INC(6),
        RANGE_EXC(7),
        UNKNOWN_INIT(8),
        INCREASED(9),
        DECREASED(10),
        CHANGED(11),
        UNCHANGED(12),
        DELTA_EXACT(13)
    }

    enum class EncodedMode(val code: Int) {
        OFFSET_ADDED(0),
        XOR_STATIC(1),
        XOR_PAIR_32(2),
        XOR_PAIR_64(3),
        XOR_ADDRESS(4)
    }

    enum class EngineStatus(val code: Int) {
        OK(0),
        ERR_PERM(-1),
        ERR_SRCH(-2),
        ERR_FAULT(-3),
        ERR_INVAL(-4),
        ERR_NOMEM(-5),
        ERR_CANCELLED(-6);

        companion object {
            fun fromCode(code: Int): EngineStatus =
                entries.firstOrNull { it.code == code } ?: ERR_INVAL
        }
    }

    @Volatile
    private var nativeHandle: Long = nativeCreate(workerThreads, spillDirectory)

    private fun requireHandle(): Long {
        val h = nativeHandle
        check(h != 0L) { "MemDiagnosticEngine has already been closed" }
        return h
    }

    fun attachProcess(pid: Int): EngineStatus =
        EngineStatus.fromCode(nativeAttachProcess(requireHandle(), pid))

    fun setRegionMask(mask: Int) {
        nativeSetRegionMask(requireHandle(), mask)
    }

    fun cancelActiveScan() {
        val h = nativeHandle
        if (h != 0L) nativeCancelScan(h)
    }

    fun searchExact(
        type: DataType,
        intTarget: Long = 0L,
        floatTarget: Double = 0.0,
        epsilon: Double = 1e-4,
        aligned: Boolean = true
    ): EngineStatus = EngineStatus.fromCode(
        nativeSearchExact(requireHandle(), type.code, intTarget, floatTarget, epsilon, aligned)
    )

    fun refineExact(
        type: DataType,
        intTarget: Long = 0L,
        floatTarget: Double = 0.0,
        epsilon: Double = 1e-4
    ): EngineStatus = EngineStatus.fromCode(
        nativeRefineExact(requireHandle(), type.code, intTarget, floatTarget, epsilon)
    )

    fun searchRange(
        type: DataType,
        intMin: Long = 0L,
        intMax: Long = 0L,
        floatMin: Double = 0.0,
        floatMax: Double = 0.0,
        inclusive: Boolean = true,
        aligned: Boolean = true
    ): EngineStatus = EngineStatus.fromCode(
        nativeSearchRange(requireHandle(), type.code, intMin, intMax, floatMin, floatMax, inclusive, aligned)
    )

    fun startUnknownSnapshot(type: DataType): EngineStatus =
        EngineStatus.fromCode(nativeStartUnknownSnapshot(requireHandle(), type.code))

    fun refineFuzzy(
        deltaOp: SearchOp,
        intDelta: Long = 0L,
        floatDelta: Double = 0.0,
        epsilon: Double = 1e-4
    ): EngineStatus = EngineStatus.fromCode(
        nativeRefineFuzzy(requireHandle(), deltaOp.code, intDelta, floatDelta, epsilon)
    )

    fun searchEncoded(
        type: DataType,
        mode: EncodedMode,
        logicalInt: Long = 0L,
        logicalFloat: Double = 0.0,
        keyOrMask: Long = 0L,
        aligned: Boolean = true
    ): EngineStatus = EngineStatus.fromCode(
        nativeSearchEncoded(requireHandle(), type.code, mode.code, logicalInt, logicalFloat, keyOrMask, aligned)
    )

    fun candidateCount(): Long = nativeGetCandidateCount(requireHandle())

    fun getCandidateAddresses(offset: Int = 0, limit: Int = 1000): LongArray =
        nativeGetCandidateAddresses(requireHandle(), offset, limit) ?: LongArray(0)

    fun writeMemory(address: Long, bytes: ByteArray): EngineStatus =
        EngineStatus.fromCode(nativeWriteMemory(requireHandle(), address, bytes))

    fun lockAddress(address: Long, bytes: ByteArray) {
        nativeLockAddress(requireHandle(), address, bytes)
    }

    fun unlockAddress(address: Long) {
        nativeUnlockAddress(requireHandle(), address)
    }

    override fun close() {
        val h = nativeHandle
        if (h != 0L) {
            nativeHandle = 0L
            nativeDestroy(h)
        }
    }

    // Native JNI bindings
    private external fun nativeCreate(workerThreads: Int, spillDir: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeAttachProcess(handle: Long, pid: Int): Int
    private external fun nativeSetRegionMask(handle: Long, regionMask: Int)
    private external fun nativeCancelScan(handle: Long)
    private external fun nativeSearchExact(
        handle: Long,
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean
    ): Int
    private external fun nativeRefineExact(
        handle: Long,
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double
    ): Int
    private external fun nativeSearchRange(
        handle: Long,
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        aligned: Boolean
    ): Int
    private external fun nativeStartUnknownSnapshot(handle: Long, dataType: Int): Int
    private external fun nativeRefineFuzzy(
        handle: Long,
        deltaOp: Int,
        intDelta: Long,
        floatDelta: Double,
        epsilon: Double
    ): Int
    private external fun nativeSearchEncoded(
        handle: Long,
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        aligned: Boolean
    ): Int
    private external fun nativeGetCandidateCount(handle: Long): Long
    private external fun nativeGetCandidateAddresses(handle: Long, offset: Int, limit: Int): LongArray?
    private external fun nativeWriteMemory(handle: Long, address: Long, bytes: ByteArray): Int
    private external fun nativeLockAddress(handle: Long, address: Long, bytes: ByteArray)
    private external fun nativeUnlockAddress(handle: Long, address: Long)
}
