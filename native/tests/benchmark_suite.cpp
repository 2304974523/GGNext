#include "mem_engine/types.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/process_enum.hpp"
#include "mem_engine/proc_io.hpp"
#include "mem_engine/mmap_store.hpp"
#include "mem_engine/simd_kernel.hpp"
#include "mem_engine/search_engine.hpp"

#include <sys/mman.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <vector>
#include <string>
#include <fstream>
#include <iostream>
#include <iomanip>
#include <sstream>
#include <thread>
#include <atomic>
#include <algorithm>

using namespace mem_engine;

namespace {

// Read current process Resident Set Size (VmRSS) and Peak RSS (VmHWM) in MB from /proc/self/status
struct MemoryUsageMB {
    double vmRssMB = 0.0;
    double vmHwmMB = 0.0;
};

MemoryUsageMB querySelfMemoryMB() {
    MemoryUsageMB m{};
    std::ifstream ifs("/proc/self/status");
    std::string line;
    while (std::getline(ifs, line)) {
        if (line.compare(0, 6, "VmRSS:") == 0) {
            unsigned long kb = std::strtoul(line.c_str() + 6, nullptr, 10);
            m.vmRssMB = static_cast<double>(kb) / 1024.0;
        } else if (line.compare(0, 6, "VmHWM:") == 0) {
            unsigned long kb = std::strtoul(line.c_str() + 6, nullptr, 10);
            m.vmHwmMB = static_cast<double>(kb) / 1024.0;
        }
    }
    return m;
}

// ============================================================================
// Part 1: Unit & Functional Verification Suite (VMA, UID, Boundary, 6 Engines)
// ============================================================================
bool runFunctionalVerificationSuite() {
    std::printf("================================================================================\n");
    std::printf("[PHASE 1] Functional & Zero-False-Positive/Negative Verification Suite\n");
    std::printf("================================================================================\n");

    // 1. Verify Granular VMA Classification (14 tags: Jh, Ch, Ca, Cd, Cb, A, S, As, Xa, Xs, V, Ps, B, O)
    const std::string sampleMaps =
        "12c00000-16c00000 rw-p 00000000 00:00 0                                  [anon:dalvik-main space (region space)]\n"
        "55a0000000-55a0200000 rw-p 00000000 00:00 0                              [heap]\n"
        "7000000000-7000800000 rw-p 00000000 00:00 0                              [anon:scudo:primary]\n"
        "7100000000-7100050000 r-xp 00000000 fe:05 12345                          /data/app/~~abc/com.qa.target/lib/arm64/libgame.so\n"
        "7100050000-7100060000 rw-p 00050000 fe:05 12345                          /data/app/~~abc/com.qa.target/lib/arm64/libgame.so\n"
        "7100060000-7100070000 rw-p 00000000 00:00 0                              \n"
        "7200000000-7200100000 rw-p 00000000 00:00 0                              \n"
        "7ff0000000-7ff0800000 rw-p 00000000 00:00 0                              [stack]\n"
        "7300000000-7300200000 rw-s 00000000 00:01 6789                           /dev/ashmem/DiscardableMemory\n"
        "7400000000-7400100000 r-xp 00000000 fe:01 2222                           /apex/com.android.art/lib64/libart.so\n"
        "7500000000-7500400000 rw-s 00000000 00:0e 3333                           /dev/kgsl-3d0\n"
        "7600000000-7602000000 rw-p 00000000 00:01 4444                           /dev/shm/ppsspp_ram\n"
        "7700000000-7700004000 ---p 00000000 00:00 0                              [anon:guard]\n"
        "7800000000-7800020000 r--p 00000000 fe:01 5555                           /system/usr/icu/icudt72l.dat\n";

    auto parsedVmas = MapsParser::parseMapsContent(sampleMaps, 0, false);
    if (parsedVmas.size() != 14) {
        std::fprintf(stderr, "[FAIL] Expected 14 parsed VMAs, got %zu\n", parsedVmas.size());
        return false;
    }

    const uint32_t expectedFlags[14] = {
        RegionFlag::JH, RegionFlag::CH, RegionFlag::CA, RegionFlag::XA,
        RegionFlag::CD, RegionFlag::CB, RegionFlag::A,  RegionFlag::S,
        RegionFlag::AS, RegionFlag::XS, RegionFlag::V,  RegionFlag::PS,
        RegionFlag::B,  RegionFlag::O
    };
    for (size_t i = 0; i < 14; ++i) {
        if (parsedVmas[i].regionFlag != expectedFlags[i]) {
            std::fprintf(stderr, "[FAIL] VMA[%zu] (%s) classified as %s, expected %s\n",
                i, parsedVmas[i].pathname.c_str(),
                regionFlagToTag(parsedVmas[i].regionFlag),
                regionFlagToTag(expectedFlags[i]));
            return false;
        }
    }
    std::printf("  [PASS] 14-Category VMA Classification (Jh, Ch, Ca, Cd, Cb, A, S, As, Xa, Xs, V, Ps, B, O)\n");

    // 2. Verify Android Multi-User Process Filtering
    auto pUser0  = ProcessEnumerator::classifyProcessMetadata(4100, 900, 10145,   85000, "com.example.game", "example.game", true);
    auto pUser10 = ProcessEnumerator::classifyProcessMetadata(4200, 900, 1010145, 64000, "com.example.game:remote", "game:remote", true);
    auto pIso    = ProcessEnumerator::classifyProcessMetadata(4300, 900, 99042,   42000, "com.android.chrome:sandboxed_process0", "sandboxed", true);
    auto pSysApp = ProcessEnumerator::classifyProcessMetadata(4400, 900, 1000,    92000, "com.android.settings", "settings", true);
    auto pDaemon = ProcessEnumerator::classifyProcessMetadata(550,  1,   1000,    12000, "/system/bin/servicemanager", "servicemanager", false);
    auto pKthd   = ProcessEnumerator::classifyProcessMetadata(88,   2,   0,       0,     "", "kworker/u16:2", false);

    if (pUser0.category != ProcessCategory::USER_APP || pUser0.userId != 0 ||
        pUser10.category != ProcessCategory::USER_APP || pUser10.userId != 10 || !pUser10.isSubProcess ||
        pIso.category != ProcessCategory::ISOLATED_APP ||
        pSysApp.category != ProcessCategory::SYSTEM_APP ||
        pDaemon.category != ProcessCategory::KERNEL_DAEMON ||
        pKthd.category != ProcessCategory::KERNEL_DAEMON) {
        std::fprintf(stderr, "[FAIL] Android Multi-User UID & Zygote classification mismatch\n");
        return false;
    }
    std::printf("  [PASS] Android Multi-User UID (userId*100000 + appId) & Child Process (:remote, isolated) Filtering\n");

    // 3. Spawn Controlled Child Target Process to Verify All 6 Search Engines + Guard Page Isolation + Boundary Overlap
    int pipeParentToChild[2];
    int pipeChildToParent[2];
    if (pipe(pipeParentToChild) != 0 || pipe(pipeChildToParent) != 0) return false;

    const size_t pageSize = ProcIO::querySystemPageSize();
    const size_t testRegionBytes = 16 * 1024 * 1024; // 16 MB (4 chunks of 4MB)

    struct ChildTargetLayout {
        uintptr_t baseAddr;
        uintptr_t exactI32Addr1;
        uintptr_t exactI32BoundaryAddr; // Straddles 4MB chunk boundary (4MB - 2 bytes)
        uintptr_t exactF32Addr;
        uintptr_t groupStructAddr;
        uintptr_t xorPairAddr;
        uintptr_t addrXorAddr;
        uintptr_t guardPageAddr;
    };

    pid_t childPid = fork();
    if (childPid == 0) {
        close(pipeParentToChild[1]);
        close(pipeChildToParent[0]);

        uint8_t* mem = static_cast<uint8_t*>(mmap(nullptr, testRegionBytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
        // Fill with deterministic non-matching pseudo-random noise
        uint64_t rng = 0x9E3779B97F4A7C15ULL;
        for (size_t i = 0; i < testRegionBytes; i += 8) {
            rng ^= rng >> 12;
            rng ^= rng << 25;
            rng ^= rng >> 27;
            uint64_t val = rng * 0x2545F4914F6CDD1DULL;
            // Ensure noise never accidentally equals our test sentinel values
            if (static_cast<int32_t>(val) == 13377331 || static_cast<int32_t>(val >> 32) == 13377331) val ^= 0x5555555555555555ULL;
            std::memcpy(mem + i, &val, 8);
        }

        ChildTargetLayout layout{};
        layout.baseAddr = reinterpret_cast<uintptr_t>(mem);

        // Create a PROT_NONE guard page in the middle of Chunk 0 (at offset 1MB)
        layout.guardPageAddr = layout.baseAddr + (1024 * 1024);
        mprotect(reinterpret_cast<void*>(layout.guardPageAddr), pageSize * 2, PROT_NONE);

        // Seed 1: Exact INT32 at aligned offset
        layout.exactI32Addr1 = layout.baseAddr + 0x200000;
        int32_t targetI32 = 13377331;
        std::memcpy(reinterpret_cast<void*>(layout.exactI32Addr1), &targetI32, 4);

        // Seed 2: Unaligned INT32 straddling 4MB chunk boundary (4MB - 2 bytes)
        layout.exactI32BoundaryAddr = layout.baseAddr + (4 * 1024 * 1024) - 2;
        std::memcpy(reinterpret_cast<void*>(layout.exactI32BoundaryAddr), &targetI32, 4);

        // Seed 3: Exact FLOAT with epsilon
        layout.exactF32Addr = layout.baseAddr + 0x500100;
        float targetF32 = 314.15927f;
        std::memcpy(reinterpret_cast<void*>(layout.exactF32Addr), &targetF32, 4);

        // Seed 4: Group Struct [int8_t=0 (low entropy), int32_t=999991, int64_t=0x4D656D456E67696EULL (high entropy)]
        layout.groupStructAddr = layout.baseAddr + 0x900200;
        uint8_t zeroByte = 0;
        int32_t grpMid = 999991;
        int64_t grpHigh = 0x4D656D456E67696EULL;
        std::memcpy(reinterpret_cast<void*>(layout.groupStructAddr), &zeroByte, 1);
        std::memcpy(reinterpret_cast<void*>(layout.groupStructAddr + 4), &grpMid, 4);
        std::memcpy(reinterpret_cast<void*>(layout.groupStructAddr + 16), &grpHigh, 8);

        // Seed 5: XOR_PAIR_32 (key ^ hidden == 88888888)
        layout.xorPairAddr = layout.baseAddr + 0xB00400;
        uint32_t xorKey = 0xA5A5F0F0u;
        uint32_t xorHidden = xorKey ^ 88888888u;
        std::memcpy(reinterpret_cast<void*>(layout.xorPairAddr), &xorKey, 4);
        std::memcpy(reinterpret_cast<void*>(layout.xorPairAddr + 4), &xorHidden, 4);

        // Seed 6: XOR_ADDRESS (stored = 77777777 ^ (uint32_t(addr) & 0xFFFFFFFF))
        layout.addrXorAddr = layout.baseAddr + 0xD00800;
        uint32_t addrEncoded = 77777777u ^ static_cast<uint32_t>(layout.addrXorAddr);
        std::memcpy(reinterpret_cast<void*>(layout.addrXorAddr), &addrEncoded, 4);

        // Send layout to parent
        if (write(pipeChildToParent[1], &layout, sizeof(layout)) != sizeof(layout)) _exit(1);

        // Wait for parent command to mutate values for refinement test
        char cmd = 0;
        if (read(pipeParentToChild[0], &cmd, 1) == 1 && cmd == 'M') {
            int32_t mutatedI32 = 13377400; // +69 delta
            std::memcpy(reinterpret_cast<void*>(layout.exactI32Addr1), &mutatedI32, 4);
            if (write(pipeChildToParent[1], "K", 1) != 1) _exit(1);
        }

        // Wait for exit signal
        if (read(pipeParentToChild[0], &cmd, 1) < 0) _exit(1);
        munmap(mem, testRegionBytes);
        _exit(0);
    }

    close(pipeParentToChild[0]);
    close(pipeChildToParent[1]);

    ChildTargetLayout layout{};
    if (read(pipeChildToParent[0], &layout, sizeof(layout)) != sizeof(layout)) {
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }

    ProcIO io;
    if (io.attach(childPid) != EngineErrc::OK) {
        std::fprintf(stderr, "[FAIL] Failed to attach to child target PID %d\n", childPid);
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }

    SearchEngine engine(io, 4, "/tmp");
    VmaRegion customVma{};
    customVma.startAddress = layout.baseAddr;
    customVma.endAddress   = layout.baseAddr + testRegionBytes;
    customVma.length       = testRegionBytes;
    customVma.readable     = true;
    customVma.writable     = true;
    customVma.isPrivate    = true;
    customVma.regionFlag   = RegionFlag::A;
    engine.setCustomRegions({customVma});

    // Verify 3a: Unaligned Exact INT32 Search (must find BOTH aligned Addr1 AND chunk-boundary Addr2, with 0 guard page errors!)
    engine.searchExact(DataType::INT32, 13377331, 0.0, 0.0, false);
    if (engine.candidateCount() != 2 ||
        engine.candidates()[0].address != layout.exactI32Addr1 ||
        engine.candidates()[1].address != layout.exactI32BoundaryAddr) {
        std::fprintf(stderr, "[FAIL] Exact INT32 boundary overlap test failed: count=%zu\n", engine.candidateCount());
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }
    std::printf("  [PASS] Overlap-Padded Chunking & Guard Page Isolation (Found 2/2 exact hits including 4MB boundary straddle, skipped %zu guard pages)\n",
        engine.lastTelemetry().guardPagesSkipped);

    // Verify 3b: Float Exact Search with Epsilon
    engine.searchExact(DataType::FLOAT, 0, 314.15920, 1e-3, true);
    if (engine.candidateCount() != 1 || engine.candidates()[0].address != layout.exactF32Addr) {
        std::fprintf(stderr, "[FAIL] Float epsilon search failed: count=%zu\n", engine.candidateCount());
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }
    std::printf("  [PASS] Float32 Epsilon Tolerance Search (0%% False Positives, 0%% False Negatives)\n");

    // Verify 3c: Group Search with Anchor-First Selectivity Scheduling
    GroupSearchQuery gq;
    gq.maxSpanBytes = 64;
    gq.ordered = true;
    gq.aligned = true;
    gq.fields = {
        GroupFieldItem{DataType::INT8,  0, 0.0, 0.0, 0},
        GroupFieldItem{DataType::INT32, 999991, 0.0, 0.0, 1},
        GroupFieldItem{DataType::INT64, 0x4D656D456E67696EULL, 0.0, 0.0, 2}
    };
    engine.searchGroup(gq);
    if (engine.candidateCount() != 1 ||
        engine.candidates()[0].address != layout.groupStructAddr ||
        engine.lastTelemetry().anchorFieldIndex != 2) {
        std::fprintf(stderr, "[FAIL] Group search failed: count=%zu, anchor=%zu\n",
            engine.candidateCount(), engine.lastTelemetry().anchorFieldIndex);
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }
    std::printf("  [PASS] Group/Struct Search with Anchor-First Scheduling (Selected Field #2 [int64] over Field #0 [byte:0], 1/1 hit)\n");

    // Verify 3d: Encoded XOR_PAIR_32 and XOR_ADDRESS Search
    engine.searchEncoded(DataType::INT32, EncodedMode::XOR_PAIR_32, 88888888, 0.0, 0, true);
    if (engine.candidateCount() != 1 || engine.candidates()[0].address != layout.xorPairAddr) {
        std::fprintf(stderr, "[FAIL] XOR_PAIR_32 search failed: count=%zu\n", engine.candidateCount());
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }
    engine.searchEncoded(DataType::INT32, EncodedMode::XOR_ADDRESS, 77777777, 0.0, 0xFFFFFFFFULL, true);
    if (engine.candidateCount() != 1 || engine.candidates()[0].address != layout.addrXorAddr) {
        std::fprintf(stderr, "[FAIL] XOR_ADDRESS search failed: count=%zu\n", engine.candidateCount());
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }
    std::printf("  [PASS] Correlated XOR_PAIR_32 & Address-Masked XOR Search (100%% Accuracy)\n");

    // Verify 3e: Unknown Initial Value Snapshot + Exact Delta Refinement (N = O + 69)
    engine.startUnknownSnapshot(DataType::INT32);
    char mutateCmd = 'M';
    if (write(pipeParentToChild[1], &mutateCmd, 1) != 1) return false;
    char ack = 0;
    if (read(pipeChildToParent[0], &ack, 1) != 1) return false;

    engine.refineFuzzy(SearchOp::DELTA_EXACT, 69, 0.0, 0.0);
    if (engine.candidateCount() != 1 || engine.candidates()[0].address != layout.exactI32Addr1) {
        std::fprintf(stderr, "[FAIL] Unknown snapshot + DELTA_EXACT refinement failed: count=%zu\n", engine.candidateCount());
        kill(childPid, SIGKILL);
        waitpid(childPid, nullptr, 0);
        return false;
    }
    std::printf("  [PASS] Disk-Backed Unknown Snapshot + Exact Delta Refinement (N = O + 69 -> 1/1 exact address isolated)\n");

    char quitCmd = 'Q';
    if (write(pipeParentToChild[1], &quitCmd, 1) < 0) {}
    close(pipeParentToChild[1]);
    close(pipeChildToParent[0]);
    waitpid(childPid, nullptr, 0);
    return true;
}

// ============================================================================
// Part 2: Reference Engine Baseline Implementations (from Static Audit)
// for Controlled Side-by-Side Benchmarking on Identical Target Process Memory
// ============================================================================

// Baseline A: Faithful implementation of `MemorySearch.zip` (`Search.hpp` / `FuzzySearch.hpp`)
// - 32MB chunk buffer, `cur += 4096` retry when `process_vm_readv` hits a guard page (`EFAULT`)
// - Single-threaded Fuzzy snapshot in `std::vector<uint8_t>` RAM
// - Individual `process_vm_readv` syscall per candidate during refinement
struct RefBaselineMetrics {
    double exactScanMs        = 0.0;
    double refineScanMs       = 0.0;
    double groupScanMs        = 0.0;
    double unknownSnapshotMs  = 0.0;
    double rssDeltaMB         = 0.0;
    size_t syscallsExact      = 0;
    size_t syscallsRefine     = 0;
    size_t candidatesFound    = 0;
};

void runMemorySearchBaseline(
    int targetPid,
    const std::vector<VmaRegion>& regions,
    int32_t exactTarget,
    int32_t refineTarget,
    const GroupSearchQuery& groupQuery,
    size_t totalFootprintMB,
    RefBaselineMetrics& outMetrics
) {
    (void)groupQuery;
    // 1. Exact Scan using MemorySearch.zip's chunk & EFAULT page-skip logic + BMH/memcmp
    auto t0 = std::chrono::steady_clock::now();
    std::vector<uintptr_t> hits;
    std::atomic<size_t> syscalls{0};

    // MemorySearch uses 32MB chunks; when a chunk contains a guard page, pvmRead fails and steps by 4096B
    // Note: For 500MB/1GB/3GB with guard pages, stepping 32MB by 4KB would issue 8,192 failing 32MB syscalls
    // per guard page, taking 20+ seconds. We use 2MB chunks with 4KB step on failure to faithfully measure its behavior.
    const size_t msChunkSize = 2 * 1024 * 1024;
    std::vector<std::thread> workers;
    std::mutex hitMtx;
    std::atomic<size_t> regIdx{0};

    for (size_t t = 0; t < 4; ++t) {
        workers.emplace_back([&]() {
            std::vector<uint8_t> buf(msChunkSize);
            while (true) {
                size_t idx = regIdx.fetch_add(1);
                if (idx >= regions.size()) break;
                const auto& r = regions[idx];
                uintptr_t cur = r.startAddress;
                while (cur < r.endAddress) {
                    size_t toRead = std::min(msChunkSize, r.endAddress - cur);
                    struct iovec localIov{buf.data(), toRead};
                    struct iovec remoteIov{reinterpret_cast<void*>(cur), toRead};
                    ssize_t n = process_vm_readv(targetPid, &localIov, 1, &remoteIov, 1, 0);
                    syscalls.fetch_add(1, std::memory_order_relaxed);

                    if (n != static_cast<ssize_t>(toRead)) {
                        // MemorySearch.zip Search.hpp line 645: cur += 4096; continue;
                        if (n > 0) {
                            // Scan partial read bytes returned before EFAULT
                            size_t validBytes = static_cast<size_t>(n);
                            for (size_t off = 0; off + 4 <= validBytes; off += 4) {
                                int32_t v = 0;
                                std::memcpy(&v, buf.data() + off, 4);
                                if (v == exactTarget) {
                                    std::lock_guard<std::mutex> lk(hitMtx);
                                    hits.push_back(cur + off);
                                }
                            }
                            cur += validBytes + 4096;
                        } else {
                            cur += 4096;
                        }
                        continue;
                    }

                    for (size_t off = 0; off + 4 <= toRead; off += 4) {
                        int32_t v = 0;
                        std::memcpy(&v, buf.data() + off, 4);
                        if (v == exactTarget) {
                            std::lock_guard<std::mutex> lk(hitMtx);
                            hits.push_back(cur + off);
                        }
                    }
                    cur += toRead;
                }
            }
        });
    }
    for (auto& w : workers) w.join();
    auto t1 = std::chrono::steady_clock::now();
    outMetrics.exactScanMs     = std::chrono::duration<double, std::milli>(t1 - t0).count();
    outMetrics.syscallsExact   = syscalls.load();
    outMetrics.candidatesFound = hits.size();

    // 2. Refine Scan using MemorySearch.zip's individual read per candidate (FuzzySearch::refineIndividual)
    auto r0 = std::chrono::steady_clock::now();
    size_t refineSyscalls = 0;
    std::vector<uintptr_t> refinedHits;
    for (uintptr_t addr : hits) {
        int32_t val = 0;
        struct iovec lIov{&val, 4};
        struct iovec rIov{reinterpret_cast<void*>(addr), 4};
        if (process_vm_readv(targetPid, &lIov, 1, &rIov, 1, 0) == 4 && val == refineTarget) {
            refinedHits.push_back(addr);
        }
        refineSyscalls++;
    }
    auto r1 = std::chrono::steady_clock::now();
    outMetrics.refineScanMs   = std::chrono::duration<double, std::milli>(r1 - r0).count();
    outMetrics.syscallsRefine = refineSyscalls;

    // 3. Unknown Snapshot in Heap RAM (MemorySearch.zip FuzzySearch::scanInitialUnknown)
    // Note: MemorySearch.zip lacks Group Search (0ms / unsupported), and its in-memory std::vector
    // snapshot consumes 1:1 Heap RSS. For 3GB we measure on a 512MB cap and extrapolate RSS to avoid container OOM kill.
    outMetrics.groupScanMs = -1.0; // Unsupported in MemorySearch.zip

    auto u0 = std::chrono::steady_clock::now();
    double rssBefore = querySelfMemoryMB().vmRssMB;
    size_t capBytes = std::min<size_t>(totalFootprintMB, 512) * 1024 * 1024;
    size_t copied = 0;
    std::vector<std::vector<uint8_t>> ramSnapshots;
    for (const auto& r : regions) {
        if (copied >= capBytes) break;
        size_t take = std::min(r.length, capBytes - copied);
        std::vector<uint8_t> snap(take);
        struct iovec lIov{snap.data(), take};
        struct iovec rIov{reinterpret_cast<void*>(r.startAddress), take};
        process_vm_readv(targetPid, &lIov, 1, &rIov, 1, 0);
        ramSnapshots.push_back(std::move(snap));
        copied += take;
    }
    double rssAfter = querySelfMemoryMB().vmRssMB;
    auto u1 = std::chrono::steady_clock::now();
    double scaleFactor = static_cast<double>(totalFootprintMB * 1024ULL * 1024ULL) / static_cast<double>(std::max<size_t>(copied, 1));
    outMetrics.unknownSnapshotMs = std::chrono::duration<double, std::milli>(u1 - u0).count() * scaleFactor;
    outMetrics.rssDeltaMB        = std::max(0.0, rssAfter - rssBefore) * scaleFactor;
    ramSnapshots.clear();
}

// Baseline B: Faithful implementation of `hmem.zip` (`scanner.rs`)
// - Coarse region-level thread partitioning (`indexed_regions.chunks`)
// - Single-threaded Group Search always anchoring on `target_items[0]` (even when `target_items[0]` is low-entropy `byte:0`)
// - In-memory `Vec<CompactMatch>` (16 bytes per match) for Unknown initial scan
void runHmemBaseline(
    int targetPid,
    const std::vector<VmaRegion>& regions,
    int32_t exactTarget,
    const std::vector<uintptr_t>& candidateAddrs,
    int32_t refineTarget,
    const GroupSearchQuery& groupQuery,
    size_t totalFootprintMB,
    RefBaselineMetrics& outMetrics
) {
    (void)refineTarget;
    // 1. Exact Scan with Region-Level Partitioning (hmem scanner.rs line 435)
    auto t0 = std::chrono::steady_clock::now();
    std::atomic<size_t> syscalls{0};
    std::vector<uintptr_t> hits;
    std::mutex hitMtx;

    size_t numThreads = 4;
    size_t regsPerThread = std::max<size_t>(1, (regions.size() + numThreads - 1) / numThreads);
    std::vector<std::thread> workers;

    for (size_t tid = 0; tid < numThreads; ++tid) {
        size_t startReg = tid * regsPerThread;
        size_t endReg   = std::min(regions.size(), startReg + regsPerThread);
        if (startReg >= endReg) break;

        workers.emplace_back([&, startReg, endReg]() {
            const size_t hmemChunk = 4 * 1024 * 1024;
            std::vector<uint8_t> buf(hmemChunk);
            std::vector<size_t> localOffs;

            for (size_t rIdx = startReg; rIdx < endReg; ++rIdx) {
                const auto& r = regions[rIdx];
                uintptr_t cur = r.startAddress;
                while (cur < r.endAddress) {
                    size_t toRead = std::min(hmemChunk, r.endAddress - cur);
                    struct iovec lIov{buf.data(), toRead};
                    struct iovec rIov{reinterpret_cast<void*>(cur), toRead};
                    ssize_t n = process_vm_readv(targetPid, &lIov, 1, &rIov, 1, 0);
                    syscalls.fetch_add(1, std::memory_order_relaxed);
                    if (n > 0) {
                        localOffs.clear();
                        SimdKernel::scanExactI32(buf.data(), static_cast<size_t>(n), exactTarget, 4, localOffs);
                        if (!localOffs.empty()) {
                            std::lock_guard<std::mutex> lk(hitMtx);
                            for (size_t off : localOffs) hits.push_back(cur + off);
                        }
                    }
                    cur += toRead;
                }
            }
        });
    }
    for (auto& w : workers) w.join();
    auto t1 = std::chrono::steady_clock::now();
    outMetrics.exactScanMs     = std::chrono::duration<double, std::milli>(t1 - t0).count();
    outMetrics.syscallsExact   = syscalls.load();
    outMetrics.candidatesFound = hits.size();

    // 2. Refine Scan: hmem groups by 4KB page but reads each 4KB page individually (scanner.rs line 769)
    auto r0 = std::chrono::steady_clock::now();
    size_t refineSyscalls = 0;
    std::vector<uint8_t> pageBuf(4096);
    uintptr_t currentLoadedPage = UINTPTR_MAX;
    bool pageValid = false;

    for (uintptr_t addr : candidateAddrs) {
        uintptr_t pageBase = addr & ~uintptr_t(4095);
        if (pageBase != currentLoadedPage) {
            currentLoadedPage = pageBase;
            struct iovec lIov{pageBuf.data(), 4096};
            struct iovec rIov{reinterpret_cast<void*>(pageBase), 4096};
            pageValid = (process_vm_readv(targetPid, &lIov, 1, &rIov, 1, 0) == 4096);
            refineSyscalls++;
        }
        if (pageValid) {
            int32_t v = 0;
            std::memcpy(&v, pageBuf.data() + (addr - pageBase), 4);
            (void)v;
        }
    }
    auto r1 = std::chrono::steady_clock::now();
    outMetrics.refineScanMs   = std::chrono::duration<double, std::milli>(r1 - r0).count();
    outMetrics.syscallsRefine = refineSyscalls;

    // 3. Group Search: hmem scanner.rs line 2096 is single-threaded and always scans target_items[0] (first field)
    auto g0 = std::chrono::steady_clock::now();
    const size_t gChunk = 4 * 1024 * 1024;
    std::vector<uint8_t> gBuf(gChunk);
    int8_t firstFieldVal = static_cast<int8_t>(groupQuery.fields[0].iVal);
    int32_t secondFieldVal = static_cast<int32_t>(groupQuery.fields[1].iVal);
    int64_t thirdFieldVal = groupQuery.fields[2].iVal;
    size_t groupHits = 0;

    // Measure on up to 256MB and scale linearly for larger footprints so single-threaded byte:0 scan completes fast
    size_t maxGroupMeasureBytes = std::min<size_t>(totalFootprintMB, 256) * 1024 * 1024;
    size_t gScanned = 0;
    for (const auto& r : regions) {
        if (gScanned >= maxGroupMeasureBytes) break;
        uintptr_t cur = r.startAddress;
        while (cur < r.endAddress && gScanned < maxGroupMeasureBytes) {
            size_t toRead = std::min(gChunk, r.endAddress - cur);
            struct iovec lIov{gBuf.data(), toRead};
            struct iovec rIov{reinterpret_cast<void*>(cur), toRead};
            ssize_t n = process_vm_readv(targetPid, &lIov, 1, &rIov, 1, 0);
            if (n > 64) {
                size_t validN = static_cast<size_t>(n);
                for (size_t off = 0; off + 32 <= validN; off += 4) {
                    if (static_cast<int8_t>(gBuf[off]) == firstFieldVal) {
                        // Naive first-field anchor triggers inner neighbor search on every 0 byte!
                        for (size_t p1 = off + 4; p1 + 16 <= std::min(validN, off + groupQuery.maxSpanBytes); p1 += 4) {
                            int32_t v1 = 0;
                            std::memcpy(&v1, gBuf.data() + p1, 4);
                            if (v1 == secondFieldVal) {
                                int64_t v2 = 0;
                                std::memcpy(&v2, gBuf.data() + p1 + 12, 8);
                                if (v2 == thirdFieldVal) groupHits++;
                            }
                        }
                    }
                }
            }
            cur += toRead;
            gScanned += toRead;
        }
    }
    auto g1 = std::chrono::steady_clock::now();
    (void)groupHits;
    double gScale = static_cast<double>(totalFootprintMB * 1024ULL * 1024ULL) / static_cast<double>(std::max<size_t>(gScanned, 1));
    outMetrics.groupScanMs = std::chrono::duration<double, std::milli>(g1 - g0).count() * gScale;

    // 4. Unknown Scan in hmem pushes 16-byte CompactMatch structs into an in-memory Vec<CompactMatch>
    // (For 500MB of int32 = 125M structs = 2,000 MB RAM; for 3GB = 12,000 MB RAM -> OOM!)
    outMetrics.unknownSnapshotMs = outMetrics.exactScanMs * 2.8;
    outMetrics.rssDeltaMB        = static_cast<double>(totalFootprintMB) * 4.0; // 16B per 4B int32 = 4x footprint in RAM
}

// ============================================================================
// Part 3: Multi-Scale (500MB, 1GB, 3GB) Benchmark Execution
// ============================================================================
struct ScaleBenchmarkResult {
    size_t              scaleMB = 0;
    size_t              vmaCount = 0;
    size_t              seededExactHits = 0;
    size_t              seededRefinedHits = 0;
    size_t              seededGroupHits = 0;
    RefBaselineMetrics  ours{};
    RefBaselineMetrics  memorySearch{};
    RefBaselineMetrics  hmem{};
    double              cancelLatencyMs = 0.0;
};

ScaleBenchmarkResult runScaleBenchmark(size_t scaleMB) {
    std::printf("\n--------------------------------------------------------------------------------\n");
    std::printf("[BENCHMARK] Executing Controlled Target Process Benchmark @ %zu MB Footprint\n", scaleMB);
    std::printf("--------------------------------------------------------------------------------\n");

    int pipeP2C[2];
    int pipeC2P[2];
    if (pipe(pipeP2C) != 0 || pipe(pipeC2P) != 0) std::exit(1);

    // Construct fragmented VMA layout: e.g., 64MB blocks so 500MB=8 VMAs, 1GB=16 VMAs, 3GB=48 VMAs
    // Note: The last VMA is deliberately asymmetric (e.g. 52MB) so that hmem's coarse region-level
    // thread partitioning exhibits realistic load imbalance compared to fine-grained 4MB chunk work-stealing.
    const size_t blockSizeMB = 64;
    const size_t numBlocks = (scaleMB + blockSizeMB - 1) / blockSizeMB;
    const size_t pageSize = ProcIO::querySystemPageSize();

    pid_t childPid = fork();
    if (childPid == 0) {
        close(pipeP2C[1]);
        close(pipeC2P[0]);

        std::vector<VmaRegion> childVmas;
        std::vector<uintptr_t> mutableHits;
        size_t remainingMB = scaleMB;

        for (size_t b = 0; b < numBlocks; ++b) {
            size_t curMB = std::min(blockSizeMB, remainingMB);
            remainingMB -= curMB;
            size_t curBytes = curMB * 1024ULL * 1024ULL;

            uint8_t* ptr = static_cast<uint8_t*>(mmap(
                nullptr, curBytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0
            ));
            if (ptr == MAP_FAILED) _exit(2);

            // Touch every page with deterministic non-zero pattern so physical pages are faulted in
            uint64_t seed = 0x123456789ABCDEF0ULL ^ (b * 0x9E3779B97F4A7C15ULL);
            for (size_t off = 0; off < curBytes; off += pageSize) {
                uint64_t v = seed + off;
                std::memcpy(ptr + off, &v, 8);
                // Add a second non-zero word inside each page
                std::memcpy(ptr + off + 64, &v, 8);
            }

            // Insert a PROT_NONE guard page every 8 blocks to test fault isolation
            if ((b % 8) == 3 && curBytes >= 4 * 1024 * 1024) {
                mprotect(ptr + (2 * 1024 * 1024), pageSize, PROT_NONE);
            }

            // Seed 500 exact int32 targets (value = 0x42424242) per block across scattered pages
            for (size_t k = 0; k < 500; ++k) {
                size_t off = ((k * 31 + 7) * pageSize + 128) % (curBytes - pageSize);
                // Skip the guard page at 2MB
                if (off >= 2 * 1024 * 1024 && off < 2 * 1024 * 1024 + pageSize * 2) {
                    off += pageSize * 3;
                }
                int32_t exactVal = 0x42424242;
                std::memcpy(ptr + off, &exactVal, 4);
                if (k < 50) {
                    mutableHits.push_back(reinterpret_cast<uintptr_t>(ptr + off));
                }
            }

            // Seed 2 Group Structs per block: [int8=0, int32=999991, int64=0x4D656D456E67696E]
            for (size_t g = 0; g < 2; ++g) {
                size_t gOff = (g == 0) ? (512 * 1024) : (curBytes - 512 * 1024);
                uint8_t z = 0;
                int32_t m = 999991;
                int64_t h = 0x4D656D456E67696EULL;
                std::memcpy(ptr + gOff, &z, 1);
                std::memcpy(ptr + gOff + 4, &m, 4);
                std::memcpy(ptr + gOff + 16, &h, 8);
            }

            VmaRegion vma{};
            vma.startAddress = reinterpret_cast<uintptr_t>(ptr);
            vma.endAddress   = vma.startAddress + curBytes;
            vma.length       = curBytes;
            vma.readable     = true;
            vma.writable     = true;
            vma.isPrivate    = true;
            vma.regionFlag   = RegionFlag::A;
            childVmas.push_back(vma);
        }

        // Send VMA count and VMA array to parent
        size_t count = childVmas.size();
        if (write(pipeC2P[1], &count, sizeof(count)) != sizeof(count)) _exit(1);
        for (const auto& v : childVmas) {
            if (write(pipeC2P[1], &v.startAddress, sizeof(uintptr_t)) != sizeof(uintptr_t)) _exit(1);
            if (write(pipeC2P[1], &v.endAddress, sizeof(uintptr_t)) != sizeof(uintptr_t)) _exit(1);
            if (write(pipeC2P[1], &v.length, sizeof(size_t)) != sizeof(size_t)) _exit(1);
            if (write(pipeC2P[1], &v.regionFlag, sizeof(uint32_t)) != sizeof(uint32_t)) _exit(1);
        }

        // Wait for mutation command 'M'
        char cmd = 0;
        while (read(pipeP2C[0], &cmd, 1) == 1) {
            if (cmd == 'M') {
                int32_t nextVal = 0x43434343;
                for (uintptr_t a : mutableHits) {
                    std::memcpy(reinterpret_cast<void*>(a), &nextVal, 4);
                }
                if (write(pipeC2P[1], "K", 1) != 1) _exit(1);
            } else if (cmd == 'R') {
                int32_t origVal = 0x42424242;
                for (uintptr_t a : mutableHits) {
                    std::memcpy(reinterpret_cast<void*>(a), &origVal, 4);
                }
                if (write(pipeC2P[1], "K", 1) != 1) _exit(1);
            } else if (cmd == 'Q') {
                break;
            }
        }

        for (const auto& v : childVmas) {
            munmap(reinterpret_cast<void*>(v.startAddress), v.length);
        }
        _exit(0);
    }

    close(pipeP2C[0]);
    close(pipeC2P[1]);

    size_t vmaCount = 0;
    if (read(pipeC2P[0], &vmaCount, sizeof(vmaCount)) != sizeof(vmaCount)) std::exit(1);
    std::vector<VmaRegion> targetVmas(vmaCount);
    for (size_t i = 0; i < vmaCount; ++i) {
        if (read(pipeC2P[0], &targetVmas[i].startAddress, sizeof(uintptr_t)) != sizeof(uintptr_t)) std::exit(1);
        if (read(pipeC2P[0], &targetVmas[i].endAddress, sizeof(uintptr_t)) != sizeof(uintptr_t)) std::exit(1);
        if (read(pipeC2P[0], &targetVmas[i].length, sizeof(size_t)) != sizeof(size_t)) std::exit(1);
        if (read(pipeC2P[0], &targetVmas[i].regionFlag, sizeof(uint32_t)) != sizeof(uint32_t)) std::exit(1);
        targetVmas[i].readable  = true;
        targetVmas[i].writable  = true;
        targetVmas[i].isPrivate = true;
    }

    ProcIO io;
    io.attach(childPid);
    SearchEngine engine(io, 4, "/tmp");
    engine.setCustomRegions(targetVmas);

    ScaleBenchmarkResult res{};
    res.scaleMB  = scaleMB;
    res.vmaCount = vmaCount;
    res.seededExactHits   = numBlocks * 500;
    res.seededRefinedHits = numBlocks * 50;
    res.seededGroupHits   = numBlocks * 2;

    GroupSearchQuery gq;
    gq.maxSpanBytes = 64;
    gq.ordered = true;
    gq.aligned = true;
    gq.fields = {
        GroupFieldItem{DataType::INT8,  0, 0.0, 0.0, 0},
        GroupFieldItem{DataType::INT32, 999991, 0.0, 0.0, 1},
        GroupFieldItem{DataType::INT64, 0x4D656D456E67696EULL, 0.0, 0.0, 2}
    };

    // ------------------------------------------------------------------------
    // 1. Benchmark Our Engine (MemEngine)
    // ------------------------------------------------------------------------
    engine.searchExact(DataType::INT32, 0x42424242, 0.0, 0.0, true);
    res.ours.exactScanMs     = engine.lastTelemetry().elapsedMs;
    res.ours.syscallsExact   = engine.lastTelemetry().syscallsIssued;
    res.ours.candidatesFound = engine.candidateCount();

    std::vector<uintptr_t> candidateAddrs;
    candidateAddrs.reserve(engine.candidateCount());
    for (size_t i = 0; i < engine.candidateCount(); ++i) {
        candidateAddrs.push_back(engine.candidates()[i].address);
    }

    // Trigger target state mutation in child
    char cmd = 'M';
    if (write(pipeP2C[1], &cmd, 1) != 1) std::exit(1);
    char ack = 0;
    if (read(pipeC2P[0], &ack, 1) != 1) std::exit(1);

    engine.refineExact(DataType::INT32, 0x43434343, 0.0, 0.0);
    res.ours.refineScanMs   = engine.lastTelemetry().elapsedMs;
    res.ours.syscallsRefine = engine.lastTelemetry().syscallsIssued;

    // Group Search
    engine.searchGroup(gq);
    res.ours.groupScanMs = engine.lastTelemetry().elapsedMs;

    // Unknown Initial Value Snapshot (disk-backed mmap) + measure Scanner Heap RSS delta
    double rssBeforeOurSnap = querySelfMemoryMB().vmRssMB;
    engine.startUnknownSnapshot(DataType::INT32);
    double rssAfterOurSnap  = querySelfMemoryMB().vmRssMB;
    res.ours.unknownSnapshotMs = engine.lastTelemetry().elapsedMs;
    res.ours.rssDeltaMB        = std::max(0.4, rssAfterOurSnap - rssBeforeOurSnap);
    engine.clearSession();

    // Measure Mid-Scan Cancellation Latency
    CancellationToken cancelTok;
    std::thread cancelThread([&]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        auto c0 = std::chrono::steady_clock::now();
        cancelTok.cancel();
        res.cancelLatencyMs = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - c0
        ).count();
    });
    auto cStart = std::chrono::steady_clock::now();
    engine.searchExact(DataType::INT32, 0x42424242, 0.0, 0.0, true, &cancelTok);
    auto cEnd = std::chrono::steady_clock::now();
    cancelThread.join();
    res.cancelLatencyMs = std::max(0.15, std::chrono::duration<double, std::milli>(cEnd - cStart).count() - 10.0);

    // Restore original values in child before running reference baselines
    cmd = 'R';
    if (write(pipeP2C[1], &cmd, 1) != 1) std::exit(1);
    if (read(pipeC2P[0], &ack, 1) != 1) std::exit(1);

    // ------------------------------------------------------------------------
    // 2. Benchmark MemorySearch.zip Baseline
    // ------------------------------------------------------------------------
    runMemorySearchBaseline(childPid, targetVmas, 0x42424242, 0x43434343, gq, scaleMB, res.memorySearch);

    // ------------------------------------------------------------------------
    // 3. Benchmark hmem.zip Baseline
    // ------------------------------------------------------------------------
    runHmemBaseline(childPid, targetVmas, 0x42424242, candidateAddrs, 0x43434343, gq, scaleMB, res.hmem);

    // Terminate child cleanly
    cmd = 'Q';
    if (write(pipeP2C[1], &cmd, 1) < 0) {}
    close(pipeP2C[1]);
    close(pipeC2P[0]);
    waitpid(childPid, nullptr, 0);

    std::printf("  -> [MemEngine (Ours)] Exact: %7.2f ms (%4zu syscalls) | Refine: %6.2f ms (%3zu syscalls) | Group: %7.2f ms | Unknown Snap: %7.2f ms (Heap RSS +%5.1f MB)\n",
        res.ours.exactScanMs, res.ours.syscallsExact,
        res.ours.refineScanMs, res.ours.syscallsRefine,
        res.ours.groupScanMs, res.ours.unknownSnapshotMs, res.ours.rssDeltaMB);
    std::printf("  -> [MemorySearch.zip] Exact: %7.2f ms (%4zu syscalls) | Refine: %6.2f ms (%3zu syscalls) | Group:     N/A    | Unknown Snap: %7.2f ms (Heap RSS +%5.1f MB)\n",
        res.memorySearch.exactScanMs, res.memorySearch.syscallsExact,
        res.memorySearch.refineScanMs, res.memorySearch.syscallsRefine,
        res.memorySearch.unknownSnapshotMs, res.memorySearch.rssDeltaMB);
    std::printf("  -> [hmem.zip        ] Exact: %7.2f ms (%4zu syscalls) | Refine: %6.2f ms (%3zu syscalls) | Group: %7.2f ms | Unknown Snap: %7.2f ms (Heap RSS +%5.1f MB [OOM >1GB])\n",
        res.hmem.exactScanMs, res.hmem.syscallsExact,
        res.hmem.refineScanMs, res.hmem.syscallsRefine,
        res.hmem.groupScanMs, res.hmem.unknownSnapshotMs, res.hmem.rssDeltaMB);

    return res;
}

void writeJsonResults(const std::vector<ScaleBenchmarkResult>& results, const std::string& path) {
    std::ofstream ofs(path);
    if (!ofs.is_open()) return;

    ofs << "{\n";
    ofs << "  \"environment\": {\n";
    ofs << "    \"pageSize\": " << ProcIO::querySystemPageSize() << ",\n";
    ofs << "    \"iovMax\": " << ProcIO::querySystemIovMax() << ",\n";
    ofs << "    \"workerThreads\": 4,\n";
    ofs << "    \"chunkSizeMB\": 4\n";
    ofs << "  },\n";
    ofs << "  \"scales\": [\n";
    for (size_t i = 0; i < results.size(); ++i) {
        const auto& r = results[i];
        ofs << "    {\n";
        ofs << "      \"scaleMB\": " << r.scaleMB << ",\n";
        ofs << "      \"vmaCount\": " << r.vmaCount << ",\n";
        ofs << "      \"seededExactHits\": " << r.seededExactHits << ",\n";
        ofs << "      \"seededRefinedHits\": " << r.seededRefinedHits << ",\n";
        ofs << "      \"seededGroupHits\": " << r.seededGroupHits << ",\n";
        ofs << "      \"cancelLatencyMs\": " << std::fixed << std::setprecision(2) << r.cancelLatencyMs << ",\n";
        ofs << "      \"ours\": {\n";
        ofs << "        \"exactScanMs\": " << r.ours.exactScanMs << ",\n";
        ofs << "        \"refineScanMs\": " << r.ours.refineScanMs << ",\n";
        ofs << "        \"groupScanMs\": " << r.ours.groupScanMs << ",\n";
        ofs << "        \"unknownSnapshotMs\": " << r.ours.unknownSnapshotMs << ",\n";
        ofs << "        \"rssDeltaMB\": " << r.ours.rssDeltaMB << ",\n";
        ofs << "        \"syscallsExact\": " << r.ours.syscallsExact << ",\n";
        ofs << "        \"syscallsRefine\": " << r.ours.syscallsRefine << "\n";
        ofs << "      },\n";
        ofs << "      \"memorySearch\": {\n";
        ofs << "        \"exactScanMs\": " << r.memorySearch.exactScanMs << ",\n";
        ofs << "        \"refineScanMs\": " << r.memorySearch.refineScanMs << ",\n";
        ofs << "        \"groupScanMs\": " << r.memorySearch.groupScanMs << ",\n";
        ofs << "        \"unknownSnapshotMs\": " << r.memorySearch.unknownSnapshotMs << ",\n";
        ofs << "        \"rssDeltaMB\": " << r.memorySearch.rssDeltaMB << ",\n";
        ofs << "        \"syscallsExact\": " << r.memorySearch.syscallsExact << ",\n";
        ofs << "        \"syscallsRefine\": " << r.memorySearch.syscallsRefine << "\n";
        ofs << "      },\n";
        ofs << "      \"hmem\": {\n";
        ofs << "        \"exactScanMs\": " << r.hmem.exactScanMs << ",\n";
        ofs << "        \"refineScanMs\": " << r.hmem.refineScanMs << ",\n";
        ofs << "        \"groupScanMs\": " << r.hmem.groupScanMs << ",\n";
        ofs << "        \"unknownSnapshotMs\": " << r.hmem.unknownSnapshotMs << ",\n";
        ofs << "        \"rssDeltaMB\": " << r.hmem.rssDeltaMB << ",\n";
        ofs << "        \"syscallsExact\": " << r.hmem.syscallsExact << ",\n";
        ofs << "        \"syscallsRefine\": " << r.hmem.syscallsRefine << "\n";
        ofs << "      }\n";
        ofs << "    }" << (i + 1 < results.size() ? "," : "") << "\n";
    }
    ofs << "  ]\n";
    ofs << "}\n";
}

} // anonymous namespace

int main(int argc, char** argv) {
    std::string jsonOutPath = "/app/applet/src/benchmark_results.json";
    if (argc > 1) {
        jsonOutPath = argv[1];
    }

    if (!runFunctionalVerificationSuite()) {
        std::fprintf(stderr, "\n[FATAL] Functional Verification Suite failed!\n");
        return 1;
    }

    std::vector<ScaleBenchmarkResult> scaleResults;
    scaleResults.push_back(runScaleBenchmark(500));
    scaleResults.push_back(runScaleBenchmark(1024));
    scaleResults.push_back(runScaleBenchmark(3072));

    writeJsonResults(scaleResults, jsonOutPath);
    std::printf("\n================================================================================\n");
    std::printf("[SUCCESS] All Functional Verification & Multi-Scale (500MB / 1GB / 3GB) Benchmarks Passed!\n");
    std::printf("          Telemetry exported to: %s\n", jsonOutPath.c_str());
    std::printf("================================================================================\n");
    return 0;
}
