# MemEngine: Cross-Process Memory Analysis & Search Engine Library (C++20 / NDK / JNI)

An industrial-grade, memory-safe cross-process memory diagnostics, state validation, and automated QA testing engine designed for Android (`arm64-v8a`, `armeabi-v7a`, `x86_64`) and Linux (`x86_64`) environments.

---

## 1. Compliance & Security Model

1. **Strict Standard Linux/Android Access Model**:
   - Exclusively operates on authorized QA target processes, debuggable builds, or owned child processes using standard Linux/Android Discretionary Access Control (DAC), `ptrace_may_access`, and SELinux policies.
   - Prioritizes zero-copy `process_vm_readv` / `process_vm_writev` syscalls and falls back cleanly to standard `/proc/[pid]/mem` (`pread64` / `pwrite64`), returning `EngineErrc::ERR_PERM` (`EPERM` / `EACCES`) on unauthorized targets.
2. **Zero Security-Bypass Code**:
   - Contains **no** SELinux privilege escalation, **no** out-of-tree kernel rootkit modules (`sys_getresuid` hijacking), **no** anti-debugging evasion, **no** signature hiding, and **no** executable code injection.

---

## 2. Directory Layout

```text
native/
├── CMakeLists.txt                                   # Cross-platform CMake build (ARM64 NEON + 16KB ELF alignment + x86_64)
├── README.md                                        # Build & architecture documentation
├── include/mem_engine/
│   ├── types.hpp                                    # RegionFlag bitmasks, DataType, SearchOp, EncodedMode, CancellationToken
│   ├── maps_parser.hpp                              # 14-tag VMA classifier (/proc/[pid]/maps & on-demand smaps)
│   ├── process_enum.hpp                             # Cache-free Android multi-user process enumerator
│   ├── proc_io.hpp                                  # Cross-process I/O, EFAULT guard isolation, page-batched iovec, ValueLocker
│   ├── mmap_store.hpp                               # Hybrid RAM + disk-backed mmap candidate & snapshot stores
│   ├── simd_kernel.hpp                              # Dual-path ARM NEON (<arm_neon.h>) & x86_64 auto-vectorized kernels
│   └── search_engine.hpp                            # Multi-threaded overlap-padded scanner (6 core search engines)
├── src/
│   ├── maps_parser.cpp
│   ├── process_enum.cpp
│   ├── proc_io.cpp
│   ├── mmap_store.cpp
│   ├── simd_kernel.cpp
│   └── search_engine.cpp
├── jni/
│   └── mem_engine_jni.cpp                           # Decoupled JNI C++ bridge for libmem_engine_jni.so
├── kotlin/com/qa/memengine/
│   └── MemDiagnosticEngine.kt                       # Thread-safe AutoCloseable Kotlin diagnostic wrapper
└── tests/
    └── benchmark_suite.cpp                          # Automated verification & 500MB / 1GB / 3GB 3-way benchmark runner
```

---

## 3. Build Instructions

### A. Android NDK Build (`arm64-v8a` with ARM NEON & Android 15+ 16KB Page Alignment)

```bash
cmake -S native -B native/build-android-arm64 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release

cmake --build native/build-android-arm64 -j$(nproc)
```

- Automatically enables `-O3 -flto -fvisibility=hidden` and links with `-Wl,-z,max-page-size=16384` for full compatibility with Android 15+ 16KB page-size devices.
- Produces `libmem_engine_core.a` (static engine library) and `libmem_engine_jni.so` (shared JNI library).

### B. Linux Host Build & Automated Multi-Scale Benchmark Execution (`x86_64`)

```bash
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build -j$(nproc)

# Run Functional Verification + 500MB / 1GB / 3GB 3-Way Comparative Benchmark Suite
./native/build/benchmark_suite
```

---

## 4. Key Architectural Innovations Over Reference Archives

| Subsystem | `MemorySearch.zip` Defect | `hmem.zip` Defect | `MemEngine` (Ours) Solution |
| :--- | :--- | :--- | :--- |
| **Cross-Process I/O & Guard Pages** | 32MB read chunk steps by hardcoded `4096` bytes on `EFAULT`, triggering **8,192 redundant failing 32MB syscalls** per guard page. | Requires custom kernel rootkit (`HMKPM_MAGIC` via `SYS_GETRESUID`); zero standard Linux `process_vm_readv` support. | Standard `process_vm_readv` + `pread64` fallback with per-page `iovec` fault isolation (`readChunkFaultTolerant`) at dynamic `sysconf(_SC_PAGESIZE)` granularity. |
| **SIMD Alignment & Strict Aliasing** | Scalar `memcmp` / BMH only; zero ARM NEON intrinsics; no float epsilon support. | Casts unaligned `uint8_t*` directly to `*const i32`/`*const f32` before typed `vld1q_*` loads. | Loads unaligned bytes strictly via `vld1q_u8` + `vreinterpretq_*_u8` and reduces with `vmaxvq_u8` / `vmaxvq_u32`; full float/double epsilon tolerance. |
| **Multi-Threaded Partitioning & Boundaries** | Splits chunks without overlap padding (misses values straddling chunk boundaries); `FuzzySearch` is single-threaded. | Partitions at coarse VMA level (`indexed_regions.chunks`), starving worker threads on large heap VMAs. | Work-stealing 4MB chunk scheduler with `overlap = maxPatternSpan - 1` padding and strict `offset < logicalLen` deduplication (**0% boundary misses, 0% duplicate hits**). |
| **Iterative Refinement I/O** | Issues 1 individual syscall per candidate (`FuzzySearch::refineIndividual`). | Reads 1 individual 4KB page per candidate group without multi-iovec coalescing. | Coalesces sorted candidates into page-aligned intervals (up to 64KB) and dispatches batched `process_vm_readv` calls up to `IOV_MAX` (1024 iovecs/syscall). |
| **Group / Struct Search** | Not implemented. | Single-threaded; always anchors on `target_items[0]` even when `target_items[0]` is low-entropy (`byte:0`). | Multi-threaded **Anchor-First Selectivity Scheduler** scoring byte width, popcount entropy, and non-zero rarity; supports both **Ordered** and **Unordered** group queries. |
| **3GB Snapshot & Result OOM Protection** | Stores all snapshots and hits in `std::vector` in Heap RAM (`MemMmap` calls `mmap` on `/proc/pid/mem` which always fails with `ENODEV`, falling back to `malloc`). | Pushes 16-byte `CompactMatch` structs into an in-memory `Vec` (750M items = 12GB RAM on 3GB scan -> immediate OOM). | `MmapCandidateStore` (spills >1M hits to anonymous `O_TMPFILE` `mmap`) + `MmapRegionSnapshotStore` (streams 3GB unknown snapshots directly to disk-backed `mmap` with `< 5 MB` Heap RSS delta). |
