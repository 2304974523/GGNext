#include "mem_engine/search_engine.hpp"
#include <chrono>
#include <thread>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <cstring>
#include <cmath>

namespace mem_engine {

// ============================================================================
// GroupFieldItem Selectivity Scoring
// ============================================================================
uint32_t GroupFieldItem::computeSelectivityScore() const noexcept {
    if (op == 3) return 0u;  // Wildcard (0~~0) must not be chosen as anchor unless all fields are wildcards
    if (op == 2) return 2u;  // Inverse range (~~) has very low selectivity
    if (op == 1) return 20u; // Range (~) has moderate-low selectivity

    // Base score by byte width (8B > 4B > 2B > 1B)
    uint32_t score = static_cast<uint32_t>(dataTypeSize(type)) * 100u;

    uint64_t rawBits = 0;
    if (type == DataType::FLOAT) {
        float fv = static_cast<float>(fVal);
        uint32_t bits = 0;
        std::memcpy(&bits, &fv, sizeof(float));
        rawBits = bits;
    } else if (type == DataType::DOUBLE) {
        std::memcpy(&rawBits, &fVal, sizeof(double));
    } else {
        rawBits = static_cast<uint64_t>(iVal);
    }

    // Penalize ultra-common values (0, 1, -1, 0xFFFFFFFF)
    if (rawBits == 0) {
        return 5u; // Very low entropy (zero pages are ubiquitous)
    }
    if (rawBits == 1 || rawBits == 0xFFFFFFFFULL || rawBits == 0xFFFFFFFFFFFFFFFFULL) {
        score = score / 3u;
    }

    // Reward bit population diversity (popcount entropy + distinct non-zero bytes)
    uint32_t pop = static_cast<uint32_t>(__builtin_popcountll(rawBits));
    score += pop * 8u;

    size_t width = dataTypeSize(type);
    uint32_t nonZeroBytes = 0;
    for (size_t b = 0; b < width; ++b) {
        uint8_t byteVal = static_cast<uint8_t>((rawBits >> (b * 8)) & 0xFFu);
        if (byteVal != 0x00 && byteVal != 0xFF) {
            nonZeroBytes++;
        }
    }
    score += nonZeroBytes * 45u;
    return score;
}

// ============================================================================
// SearchEngine Implementation
// ============================================================================
SearchEngine::SearchEngine(ProcIO& io, size_t workerThreads, const std::string& spillDir)
    : m_io(io),
      m_workerThreads(workerThreads == 0 ? 1 : workerThreads),
      m_spillDir(spillDir),
      m_candidates(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, spillDir),
      m_snapshotStore(spillDir) {}

std::vector<VmaRegion> SearchEngine::refreshRegions() {
    m_regions = MapsParser::parseProcessMaps(m_io.pid(), m_regionMask, true);
    return m_regions;
}

void SearchEngine::clearSession() noexcept {
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_encPrevLogicalInt = 0;
    m_encPrevLogicalFlt = 0.0;
    m_encPrevTargetBits = 0;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};
}

void SearchEngine::fetchCandidates(size_t offset, size_t limit, std::vector<CandidateEntry>& out) const {
    out.clear();
    if (limit == 0) return;

    if (!m_candidates.empty()) {
        size_t total = m_candidates.size();
        if (offset >= total) return;
        size_t count = std::min(limit, total - offset);
        out.resize(count);
        std::memcpy(out.data(), m_candidates.data() + offset, count * sizeof(CandidateEntry));
        return;
    }

    if (m_hasFullSnapshot) {
        size_t elemSz = std::max<size_t>(1, dataTypeSize(m_snapshotType));
        size_t curIndex = 0;
        out.reserve(std::min<size_t>(limit, 4096));
        for (const auto& blk : m_snapshotStore.blocks()) {
            const uint8_t* ptr = m_snapshotStore.getBlockData(blk);
            if (!ptr) continue;
            size_t blkElems = blk.length / elemSz;
            if (curIndex + blkElems <= offset) {
                curIndex += blkElems;
                continue;
            }
            size_t startElem = (offset > curIndex) ? (offset - curIndex) : 0;
            uint8_t rTag = regionFlagToIndex(blk.regionFlag);
            for (size_t e = startElem; e < blkElems && out.size() < limit; ++e) {
                size_t byteOff = e * elemSz;
                CandidateEntry entry{};
                entry.address = blk.startAddress + byteOff;
                std::memcpy(&entry.rawValue, ptr + byteOff, std::min<size_t>(elemSz, 8));
                entry.type = static_cast<uint8_t>(m_snapshotType);
                entry.regionTag = rTag;
                out.push_back(entry);
            }
            curIndex += blkElems;
            if (out.size() >= limit) break;
        }
    }
}

uint8_t SearchEngine::regionFlagToIndex(uint32_t flag) noexcept {
    if (flag == 0) return 0;
    return static_cast<uint8_t>(__builtin_ctz(flag));
}

std::vector<SearchEngine::ChunkTask> SearchEngine::buildChunkTasks(size_t overlapBytes) const {
    std::vector<ChunkTask> tasks;
    for (const auto& vma : m_regions) {
        if (!vma.isScanSafe(m_regionMask)) continue;
        uintptr_t cur = vma.startAddress;
        while (cur < vma.endAddress) {
            size_t rem = vma.endAddress - cur;
            size_t logical = (rem < m_chunkSize) ? rem : m_chunkSize;
            size_t readLen = (rem < (logical + overlapBytes)) ? rem : (logical + overlapBytes);
            tasks.push_back(ChunkTask{cur, logical, readLen, vma.regionFlag});
            cur += logical;
        }
    }
    return tasks;
}

bool SearchEngine::isRangeInValidPages(
    size_t offsetInChunk,
    size_t itemLen,
    uintptr_t chunkStartAddr,
    size_t pageSize,
    const std::vector<uint8_t>& validPageMask
) noexcept {
    if (validPageMask.empty() || itemLen == 0) return false;
    uintptr_t pageAlignedBase = chunkStartAddr & ~(pageSize - 1);
    uintptr_t itemStart = chunkStartAddr + offsetInChunk;
    uintptr_t itemEnd   = itemStart + itemLen - 1;

    size_t firstPage = (itemStart - pageAlignedBase) / pageSize;
    size_t lastPage  = (itemEnd - pageAlignedBase) / pageSize;

    if (lastPage >= validPageMask.size()) return false;
    for (size_t p = firstPage; p <= lastPage; ++p) {
        if (validPageMask[p] == 0) return false;
    }
    return true;
}

namespace {

uint64_t extractRawValue(const uint8_t* ptr, DataType dt) noexcept {
    uint64_t raw = 0;
    size_t sz = dataTypeSize(dt);
    if (sz <= 8) {
        std::memcpy(&raw, ptr, sz);
    }
    return raw;
}

bool evalSingleExact(const uint8_t* ptr, DataType dt, int64_t intTarget, double floatTarget, double epsilon) noexcept {
    switch (dt) {
        case DataType::INT8: {
            int8_t v = 0;
            std::memcpy(&v, ptr, 1);
            return v == static_cast<int8_t>(intTarget);
        }
        case DataType::INT16: {
            int16_t v = 0;
            std::memcpy(&v, ptr, 2);
            return v == static_cast<int16_t>(intTarget);
        }
        case DataType::INT32: {
            int32_t v = 0;
            std::memcpy(&v, ptr, 4);
            return v == static_cast<int32_t>(intTarget);
        }
        case DataType::INT64: {
            int64_t v = 0;
            std::memcpy(&v, ptr, 8);
            return v == intTarget;
        }
        case DataType::FLOAT: {
            float v = 0.0f;
            std::memcpy(&v, ptr, 4);
            if (std::isnan(v)) return false;
            return std::fabs(static_cast<double>(v) - floatTarget) <= epsilon;
        }
        case DataType::DOUBLE: {
            double v = 0.0;
            std::memcpy(&v, ptr, 8);
            if (std::isnan(v)) return false;
            return std::fabs(v - floatTarget) <= epsilon;
        }
        default:
            return false;
    }
}

bool evalSingleRange(const uint8_t* ptr, DataType dt, int64_t iMin, int64_t iMax, double fMin, double fMax, bool inclusive) noexcept {
    switch (dt) {
        case DataType::INT8: {
            int8_t v = 0;
            std::memcpy(&v, ptr, 1);
            return inclusive ? (v >= static_cast<int8_t>(iMin) && v <= static_cast<int8_t>(iMax))
                             : (v >  static_cast<int8_t>(iMin) && v <  static_cast<int8_t>(iMax));
        }
        case DataType::INT16: {
            int16_t v = 0;
            std::memcpy(&v, ptr, 2);
            return inclusive ? (v >= static_cast<int16_t>(iMin) && v <= static_cast<int16_t>(iMax))
                             : (v >  static_cast<int16_t>(iMin) && v <  static_cast<int16_t>(iMax));
        }
        case DataType::INT32: {
            int32_t v = 0;
            std::memcpy(&v, ptr, 4);
            return inclusive ? (v >= static_cast<int32_t>(iMin) && v <= static_cast<int32_t>(iMax))
                             : (v >  static_cast<int32_t>(iMin) && v <  static_cast<int32_t>(iMax));
        }
        case DataType::INT64: {
            int64_t v = 0;
            std::memcpy(&v, ptr, 8);
            return inclusive ? (v >= iMin && v <= iMax)
                             : (v >  iMin && v <  iMax);
        }
        case DataType::FLOAT: {
            float v = 0.0f;
            std::memcpy(&v, ptr, 4);
            if (std::isnan(v)) return false;
            return inclusive ? (v >= static_cast<float>(fMin) && v <= static_cast<float>(fMax))
                             : (v >  static_cast<float>(fMin) && v <  static_cast<float>(fMax));
        }
        case DataType::DOUBLE: {
            double v = 0.0;
            std::memcpy(&v, ptr, 8);
            if (std::isnan(v)) return false;
            return inclusive ? (v >= fMin && v <= fMax)
                             : (v >  fMin && v <  fMax);
        }
        default:
            return false;
    }
}

} // anonymous namespace

// ============================================================================
// 1. Exact Search (Single-Type & Adaptive Multi-Type AUTO)
// ============================================================================
EngineErrc SearchEngine::searchExact(
    DataType type,
    int64_t intTarget,
    double floatTarget,
    double epsilon,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    if (m_regions.empty()) {
        refreshRegions();
    }

    size_t maxItemSize = (type == DataType::AUTO) ? 8 : dataTypeSize(type);
    size_t overlap = (maxItemSize > 1) ? (maxItemSize - 1) : 0;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> guardPages{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    std::vector<std::thread> workers;
    workers.reserve(numThreads);

    const size_t pageSize = m_io.pageSize();

    for (size_t tid = 0; tid < numThreads; ++tid) {
        workers.emplace_back([&]() {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;
            localHits.reserve(8192);

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable);
                syscalls.fetch_add(1, std::memory_order_relaxed);

                size_t chunkGuards = 0;
                for (uint8_t v : validMask) {
                    if (v == 0) chunkGuards++;
                }
                guardPages.fetch_add(chunkGuards, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    size_t chunkMatchCount = 0;

                    auto runTypeScan = [&](DataType dt) {
                        offsets.clear();
                        size_t itemSz = dataTypeSize(dt);
                        size_t step = aligned ? itemSz : 1;

                        switch (dt) {
                            case DataType::INT8:
                                SimdKernel::scanExactI8(buf.data(), task.readLen, static_cast<int8_t>(intTarget), offsets);
                                break;
                            case DataType::INT16:
                                SimdKernel::scanExactI16(buf.data(), task.readLen, static_cast<int16_t>(intTarget), step, offsets);
                                break;
                            case DataType::INT32:
                                SimdKernel::scanExactI32(buf.data(), task.readLen, static_cast<int32_t>(intTarget), step, offsets);
                                break;
                            case DataType::INT64:
                                SimdKernel::scanExactI64(buf.data(), task.readLen, intTarget, step, offsets);
                                break;
                            case DataType::FLOAT:
                                SimdKernel::scanExactF32(buf.data(), task.readLen, static_cast<float>(floatTarget), static_cast<float>(epsilon), step, offsets);
                                break;
                            case DataType::DOUBLE:
                                SimdKernel::scanExactF64(buf.data(), task.readLen, floatTarget, epsilon, step, offsets);
                                break;
                            default:
                                break;
                        }

                        bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);

                        for (size_t off : offsets) {
                            if (off >= task.logicalLen) continue;
                            if (!isRangeInValidPages(off, itemSz, task.startAddr, pageSize, validMask)) continue;

                            chunkMatchCount++;

                            if (!storeAlreadyFull) {
                                CandidateEntry entry{};
                                entry.address   = task.startAddr + off;
                                entry.rawValue  = extractRawValue(buf.data() + off, dt);
                                entry.type      = static_cast<uint8_t>(dt);
                                entry.regionTag = rTag;
                                localHits.push_back(entry);

                                if (localHits.size() >= 16384) {
                                    std::lock_guard<std::mutex> lk(storeMutex);
                                    m_candidates.appendBatch(localHits.data(), localHits.size());
                                    storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                    localHits.clear();
                                    if (m_candidates.isFull()) {
                                        storeAlreadyFull = true;
                                    }
                                }
                            }
                        }
                    };

                    if (type == DataType::AUTO) {
                        runTypeScan(DataType::INT32);
                        runTypeScan(DataType::INT64);
                        runTypeScan(DataType::FLOAT);
                        runTypeScan(DataType::DOUBLE);
                    } else {
                        runTypeScan(type);
                    }

                    if (chunkMatchCount > 0) {
                        size_t newTotal = totalMatches.fetch_add(chunkMatchCount, std::memory_order_relaxed) + chunkMatchCount;
                        m_totalMatchedCount = newTotal;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) {
                    progress(done, totalPlannedBytes);
                }
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
        });
    }

    for (auto& w : workers) w.join();

    if (token && token->isCancelled()) {
        return EngineErrc::ERR_CANCELLED;
    }

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.guardPagesSkipped = guardPages.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();

    return EngineErrc::OK;
}

// ============================================================================
// Page-Batched iovec Iterative Refinement for Exact Search
// ============================================================================
EngineErrc SearchEngine::refineExact(
    DataType type,
    int64_t intTarget,
    double floatTarget,
    double epsilon,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    groups.reserve(std::min<size_t>(count, 8192));

    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
        size_t itemSz = dataTypeSize(dt);

        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx;
        idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            DataType nextDt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
            size_t nextSz = dataTypeSize(nextDt);
            uintptr_t nextEnd = nextAddr + nextSz;

            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    size_t totalBatchBytes = 0;
    for (const auto& g : groups) totalBatchBytes += g.byteLen;

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;

    constexpr size_t BATCH_GROUP_LIMIT = 4096;
    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            ranges[i - gBase].success    = false;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;

            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(cand.type) : type;
                size_t relOff = cand.address - grp.startPageAddr;
                const uint8_t* valPtr = br.localBuf + relOff;

                if (evalSingleExact(valPtr, dt, intTarget, floatTarget, epsilon)) {
                    cand.rawValue = extractRawValue(valPtr, dt);
                    nextStore.append(cand);
                }
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) {
            progress(gEnd, groups.size());
        }
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalBytesScanned = totalBatchBytes;
    m_lastTelemetry.readableBytes     = totalBatchBytes;
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscallsIssued;
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();

    return EngineErrc::OK;
}

// ============================================================================
// Byte[] Pattern & UTF-8 / UTF-16 String Exact Search
// ============================================================================
EngineErrc SearchEngine::searchBytePattern(
    const std::vector<uint8_t>& pattern,
    const std::vector<uint8_t>& mask,
    CancellationToken* token,
    ProgressCallback progress
) {
    if (pattern.empty()) return EngineErrc::ERR_INVAL;
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    if (m_regions.empty()) refreshRegions();

    const size_t patLen = pattern.size();
    const size_t overlap = (patLen > 1) ? (patLen - 1) : 0;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> guardPages{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    std::vector<std::thread> workers;
    const size_t pageSize = m_io.pageSize();
    const uint8_t* maskPtr = (mask.size() == patLen) ? mask.data() : nullptr;

    for (size_t tid = 0; tid < numThreads; ++tid) {
        workers.emplace_back([&]() {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    offsets.clear();
                    SimdKernel::scanBytePattern(buf.data(), task.readLen, pattern.data(), maskPtr, patLen, offsets);
                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                    size_t chunkMatches = 0;

                    for (size_t off : offsets) {
                        if (off >= task.logicalLen) continue;
                        if (!isRangeInValidPages(off, patLen, task.startAddr, pageSize, validMask)) continue;

                        chunkMatches++;
                        if (!storeAlreadyFull) {
                            CandidateEntry entry{};
                            entry.address   = task.startAddr + off;
                            std::memcpy(&entry.rawValue, buf.data() + off, std::min<size_t>(patLen, 8));
                            entry.type      = static_cast<uint8_t>(DataType::BYTE_ARRAY);
                            entry.regionTag = rTag;
                            localHits.push_back(entry);

                            if (localHits.size() >= 16384) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(localHits.data(), localHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                localHits.clear();
                                if (m_candidates.isFull()) storeAlreadyFull = true;
                            }
                        }
                    }

                    if (chunkMatches > 0) {
                        m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
        });
    }

    for (auto& w : workers) w.join();
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.guardPagesSkipped = guardPages.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::searchString(
    const std::string& utf8Str,
    bool utf16le,
    CancellationToken* token,
    ProgressCallback progress
) {
    if (utf8Str.empty()) return EngineErrc::ERR_INVAL;
    std::vector<uint8_t> pattern;
    if (!utf16le) {
        pattern.assign(utf8Str.begin(), utf8Str.end());
    } else {
        // Convert ASCII/UTF-8 codepoints into UTF-16LE bytes
        pattern.reserve(utf8Str.size() * 2);
        for (unsigned char c : utf8Str) {
            pattern.push_back(c);
            pattern.push_back(0x00);
        }
    }
    return searchBytePattern(pattern, {}, token, progress);
}

// ============================================================================
// 2. Range Search ([Min, Max] Inclusive or (Min, Max) Exclusive) + Refinement
// ============================================================================
EngineErrc SearchEngine::searchRange(
    DataType type,
    int64_t intMin,
    int64_t intMax,
    double floatMin,
    double floatMax,
    bool inclusive,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    if (m_regions.empty()) refreshRegions();

    size_t itemSz = dataTypeSize(type);
    size_t overlap = (itemSz > 1) ? (itemSz - 1) : 0;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    std::vector<std::thread> workers;
    const size_t pageSize = m_io.pageSize();
    const size_t step = aligned ? itemSz : 1;

    for (size_t tid = 0; tid < numThreads; ++tid) {
        workers.emplace_back([&]() {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;
            localHits.reserve(8192);

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    offsets.clear();
                    switch (type) {
                        case DataType::INT8:
                            SimdKernel::scanRange<int8_t>(buf.data(), task.readLen, static_cast<int8_t>(intMin), static_cast<int8_t>(intMax), inclusive, 1, offsets);
                            break;
                        case DataType::INT16:
                            SimdKernel::scanRange<int16_t>(buf.data(), task.readLen, static_cast<int16_t>(intMin), static_cast<int16_t>(intMax), inclusive, step, offsets);
                            break;
                        case DataType::INT32:
                            SimdKernel::scanRange<int32_t>(buf.data(), task.readLen, static_cast<int32_t>(intMin), static_cast<int32_t>(intMax), inclusive, step, offsets);
                            break;
                        case DataType::INT64:
                            SimdKernel::scanRange<int64_t>(buf.data(), task.readLen, intMin, intMax, inclusive, step, offsets);
                            break;
                        case DataType::FLOAT:
                            SimdKernel::scanRangeF32(buf.data(), task.readLen, static_cast<float>(floatMin), static_cast<float>(floatMax), inclusive, step, offsets);
                            break;
                        case DataType::DOUBLE:
                            SimdKernel::scanRange<double>(buf.data(), task.readLen, floatMin, floatMax, inclusive, step, offsets);
                            break;
                        default:
                            break;
                    }

                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                    size_t chunkMatches = 0;

                    for (size_t off : offsets) {
                        if (off >= task.logicalLen) continue;
                        if (!isRangeInValidPages(off, itemSz, task.startAddr, pageSize, validMask)) continue;

                        chunkMatches++;
                        if (!storeAlreadyFull) {
                            CandidateEntry entry{};
                            entry.address   = task.startAddr + off;
                            entry.rawValue  = extractRawValue(buf.data() + off, type);
                            entry.type      = static_cast<uint8_t>(type);
                            entry.regionTag = rTag;
                            localHits.push_back(entry);

                            if (localHits.size() >= 16384) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(localHits.data(), localHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                localHits.clear();
                                if (m_candidates.isFull()) storeAlreadyFull = true;
                            }
                        }
                    }

                    if (chunkMatches > 0) {
                        m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
        });
    }

    for (auto& w : workers) w.join();
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineRange(
    DataType type,
    int64_t intMin,
    int64_t intMax,
    double floatMin,
    double floatMax,
    bool inclusive,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        size_t itemSz = dataTypeSize(type);
        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            uintptr_t nextEnd  = nextAddr + itemSz;
            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;

    constexpr size_t BATCH_GROUP_LIMIT = 4096;
    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;
            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                const uint8_t* valPtr = br.localBuf + (cand.address - grp.startPageAddr);
                if (evalSingleRange(valPtr, type, intMin, intMax, floatMin, floatMax, inclusive)) {
                    cand.rawValue = extractRawValue(valPtr, type);
                    nextStore.append(cand);
                }
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) progress(gEnd, groups.size());
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscallsIssued;
    m_lastTelemetry.spilledToMmap   = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

// ============================================================================
// 3. Fuzzy / Delta Search: Anonymous Demand-Paged Full Snapshot + Refinement
// ============================================================================
EngineErrc SearchEngine::startUnknownSnapshot(
    DataType type,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot   = false;
    m_encryptedSnapshot = false;
    m_snapshotType      = (type == DataType::AUTO) ? DataType::INT32 : type;
    m_totalMatchedCount = 0;
    m_lastTelemetry     = ScanTelemetry{};

    if (m_regions.empty()) refreshRegions();

    auto tasks = buildChunkTasks(0);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    if (!m_snapshotStore.openStore(totalPlannedBytes)) {
        return EngineErrc::ERR_NOMEM;
    }

    const size_t elemSz = std::max<size_t>(1, dataTypeSize(m_snapshotType));
    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> guardPages{0};
    std::atomic<size_t> syscalls{0};
    std::mutex          snapMutex;
    const size_t        pageSize = m_io.pageSize();

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    std::vector<std::thread> workers;

    for (size_t tid = 0; tid < numThreads; ++tid) {
        workers.emplace_back([&]() {
            std::vector<uint8_t> buf(m_chunkSize);
            std::vector<uint8_t> validMask;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.logicalLen) buf.resize(task.logicalLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.logicalLen, validMask, &chunkReadable);
                syscalls.fetch_add(1, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    size_t numPages = validMask.size();
                    // Filter out untouched demand-zero pages (4KB pages of 100% zeros that were faulted to ZERO_PAGE)
                    for (size_t p = 0; p < numPages; ++p) {
                        if (validMask[p] == 0) continue;
                        size_t pOff = p * pageSize;
                        if (pOff >= task.logicalLen) {
                            validMask[p] = 0;
                            continue;
                        }
                        size_t pLen = std::min(pageSize, task.logicalLen - pOff);
                        if (pLen >= pageSize) {
                            const uint64_t* w64 = reinterpret_cast<const uint64_t*>(buf.data() + pOff);
                            size_t words = pLen / sizeof(uint64_t);
                            uint64_t acc = 0;
                            for (size_t w = 0; w < words; ++w) {
                                acc |= w64[w];
                                if (acc != 0) break;
                            }
                            if (acc == 0) {
                                validMask[p] = 0;
                                if (chunkReadable >= pLen) chunkReadable -= pLen;
                            }
                        }
                    }

                    size_t p = 0;
                    while (p < numPages) {
                        if (validMask[p] == 0) {
                            p++;
                            continue;
                        }
                        size_t runStart = p;
                        while (p < numPages && validMask[p] != 0) p++;
                        size_t runByteOffset = runStart * pageSize;
                        size_t runByteLen    = std::min((p - runStart) * pageSize, task.logicalLen - runByteOffset);
                        if (runByteLen > 0) {
                            std::lock_guard<std::mutex> lk(snapMutex);
                            m_snapshotStore.appendRegionChunk(
                                task.startAddr + runByteOffset,
                                buf.data() + runByteOffset,
                                runByteLen,
                                task.regionFlag
                            );
                        }
                    }
                }

                size_t totalReadSoFar = readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed) + chunkReadable;
                m_totalMatchedCount = totalReadSoFar / elemSz;

                size_t chunkGuards = 0;
                for (uint8_t v : validMask) {
                    if (v == 0) chunkGuards++;
                }
                guardPages.fetch_add(chunkGuards, std::memory_order_relaxed);

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }
        });
    }

    for (auto& w : workers) w.join();
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_hasFullSnapshot   = !m_snapshotStore.blocks().empty();
    m_totalMatchedCount = m_snapshotStore.totalSnapshotBytes() / elemSz;

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.guardPagesSkipped = guardPages.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = true;

    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineFuzzy(
    SearchOp deltaOp,
    int64_t intDelta,
    double floatDelta,
    double epsilon,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();

    // Case 1: Refinement pass directly against the full MmapRegionSnapshotStore
    if (m_hasFullSnapshot && m_candidates.empty()) {
        const auto& blocks = m_snapshotStore.blocks();
        if (blocks.empty()) {
            m_totalMatchedCount = 0;
            return EngineErrc::OK;
        }

        const size_t totalSnapBytes = std::max<size_t>(1, m_snapshotStore.totalSnapshotBytes());
        std::atomic<size_t> nextBlock{0};
        std::atomic<size_t> syscalls{0};
        std::atomic<size_t> bytesScanned{0};
        std::atomic<size_t> totalMatches{0};
        std::atomic<size_t> storedCount{0};
        std::mutex storeMutex;

        size_t numThreads = std::min(m_workerThreads, blocks.size());
        std::vector<std::thread> workers;

        for (size_t tid = 0; tid < numThreads; ++tid) {
            workers.emplace_back([&]() {
                std::vector<uint8_t> curBuf;
                std::vector<uint8_t> validMask;
                std::vector<CandidateEntry> blockHits;
                blockHits.reserve(8192);

                while (true) {
                    if (token && token->isCancelled()) break;
                    size_t bIdx = nextBlock.fetch_add(1, std::memory_order_relaxed);
                    if (bIdx >= blocks.size()) break;

                    const auto& blk = blocks[bIdx];
                    uint8_t* oldPtr = m_snapshotStore.getBlockDataMut(blk);
                    if (!oldPtr) continue;

                    if (curBuf.size() < blk.length) curBuf.resize(blk.length);
                    size_t chunkReadable = 0;
                    EngineErrc rc = m_io.readChunkFaultTolerant(blk.startAddress, curBuf.data(), blk.length, validMask, &chunkReadable);
                    syscalls.fetch_add(1, std::memory_order_relaxed);
                    size_t doneBytes = bytesScanned.fetch_add(blk.length, std::memory_order_relaxed) + blk.length;

                    if (rc != EngineErrc::OK || chunkReadable == 0) {
                        if (progress) progress(doneBytes, totalSnapBytes);
                        continue;
                    }

                    blockHits.clear();
                    uint8_t rTag = regionFlagToIndex(blk.regionFlag);
                    switch (m_snapshotType) {
                        case DataType::INT8:
                            SimdKernel::compareBuffersDelta<int8_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<int8_t>(intDelta), rTag, blockHits);
                            break;
                        case DataType::INT16:
                            SimdKernel::compareBuffersDelta<int16_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<int16_t>(intDelta), rTag, blockHits);
                            break;
                        case DataType::INT32:
                            SimdKernel::compareBuffersDelta<int32_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<int32_t>(intDelta), rTag, blockHits);
                            break;
                        case DataType::INT64:
                            SimdKernel::compareBuffersDelta<int64_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, intDelta, rTag, blockHits);
                            break;
                        case DataType::FLOAT:
                            SimdKernel::compareBuffersDelta<float>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<float>(floatDelta), rTag, blockHits);
                            break;
                        case DataType::DOUBLE:
                            SimdKernel::compareBuffersDelta<double>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, floatDelta, rTag, blockHits);
                            break;
                        default:
                            break;
                    }

                    // Update snapshot block in-place with current live bytes so repeated UNCHANGED passes stay fresh
                    std::memcpy(oldPtr, curBuf.data(), blk.length);

                    if (!blockHits.empty()) {
                        size_t newTotal = totalMatches.fetch_add(blockHits.size(), std::memory_order_relaxed) + blockHits.size();
                        m_totalMatchedCount = newTotal;

                        if (storedCount.load(std::memory_order_relaxed) < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            std::lock_guard<std::mutex> lk(storeMutex);
                            m_candidates.appendBatch(blockHits.data(), blockHits.size());
                            storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                        }
                    }

                    if (progress) progress(doneBytes, totalSnapBytes);
                }
            });
        }

        for (auto& w : workers) w.join();
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

        size_t matched = totalMatches.load();
        if (deltaOp == SearchOp::UNCHANGED && matched > MmapCandidateStore::MAX_STORED_CANDIDATES) {
            // Keep full updated snapshot active when UNCHANGED still spans >2M values so subsequent CHANGED/INCREASED/DECREASED scans full memory
            m_candidates.clear();
            m_hasFullSnapshot = true;
            m_totalMatchedCount = matched;
        } else {
            // Narrowed into candidate store: release snapshot memory and sort candidates
            m_snapshotStore.clear();
            m_hasFullSnapshot = false;
            m_candidates.sortByAddress();
            m_totalMatchedCount = std::max(matched, m_candidates.size());
        }

        auto t1 = std::chrono::steady_clock::now();
        m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
        m_lastTelemetry.totalBytesScanned = bytesScanned.load();
        m_lastTelemetry.readableBytes     = bytesScanned.load();
        m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
        m_lastTelemetry.syscallsIssued    = syscalls.load();
        m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk() || m_hasFullSnapshot;
        return EngineErrc::OK;
    }

    // Case 2: Subsequent iterative fuzzy refinement on existing m_candidates via tight-coalesced iovec
    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        DataType dt = static_cast<DataType>(m_candidates[idx].type);
        size_t itemSz = dataTypeSize(dt);
        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            uintptr_t nextEnd  = nextAddr + itemSz;
            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;

    constexpr size_t BATCH_GROUP_LIMIT = 4096;
    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;
            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                DataType dt = static_cast<DataType>(cand.type);
                const uint8_t* valPtr = br.localBuf + (cand.address - grp.startPageAddr);

                bool keep = false;
                auto checkTyped = [&](auto oldVal, auto curVal, auto targetDelta) {
                    switch (deltaOp) {
                        case SearchOp::INCREASED:   return curVal > oldVal;
                        case SearchOp::DECREASED:   return curVal < oldVal;
                        case SearchOp::CHANGED:     return curVal != oldVal;
                        case SearchOp::UNCHANGED:   return curVal == oldVal;
                        case SearchOp::DELTA_EXACT: return (curVal == static_cast<decltype(curVal)>(oldVal + targetDelta)) ||
                                                           (curVal == static_cast<decltype(curVal)>(oldVal - targetDelta));
                        default:                    return false;
                    }
                };

                switch (dt) {
                    case DataType::INT8: {
                        int8_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 1);
                        std::memcpy(&c, valPtr, 1);
                        keep = checkTyped(o, c, static_cast<int8_t>(intDelta));
                        break;
                    }
                    case DataType::INT16: {
                        int16_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 2);
                        std::memcpy(&c, valPtr, 2);
                        keep = checkTyped(o, c, static_cast<int16_t>(intDelta));
                        break;
                    }
                    case DataType::INT32: {
                        int32_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 4);
                        std::memcpy(&c, valPtr, 4);
                        keep = checkTyped(o, c, static_cast<int32_t>(intDelta));
                        break;
                    }
                    case DataType::INT64: {
                        int64_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 8);
                        std::memcpy(&c, valPtr, 8);
                        keep = checkTyped(o, c, intDelta);
                        break;
                    }
                    case DataType::FLOAT: {
                        float o = 0.0f, c = 0.0f;
                        std::memcpy(&o, &cand.rawValue, 4);
                        std::memcpy(&c, valPtr, 4);
                        if (!std::isnan(o) && !std::isnan(c)) {
                            if (deltaOp == SearchOp::DELTA_EXACT) {
                                keep = std::fabs(static_cast<double>(c - o) - floatDelta) <= epsilon ||
                                       std::fabs(static_cast<double>(o - c) - floatDelta) <= epsilon;
                            } else if (deltaOp == SearchOp::UNCHANGED) {
                                keep = std::fabs(static_cast<double>(c - o)) <= epsilon;
                            } else if (deltaOp == SearchOp::CHANGED) {
                                keep = std::fabs(static_cast<double>(c - o)) > epsilon;
                            } else {
                                keep = checkTyped(o, c, static_cast<float>(floatDelta));
                            }
                        }
                        break;
                    }
                    case DataType::DOUBLE: {
                        double o = 0.0, c = 0.0;
                        std::memcpy(&o, &cand.rawValue, 8);
                        std::memcpy(&c, valPtr, 8);
                        if (!std::isnan(o) && !std::isnan(c)) {
                            if (deltaOp == SearchOp::DELTA_EXACT) {
                                keep = std::fabs((c - o) - floatDelta) <= epsilon ||
                                       std::fabs((o - c) - floatDelta) <= epsilon;
                            } else if (deltaOp == SearchOp::UNCHANGED) {
                                keep = std::fabs(c - o) <= epsilon;
                            } else if (deltaOp == SearchOp::CHANGED) {
                                keep = std::fabs(c - o) > epsilon;
                            } else {
                                keep = checkTyped(o, c, floatDelta);
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }

                if (keep) {
                    cand.rawValue = extractRawValue(valPtr, dt);
                    nextStore.append(cand);
                }
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) progress(gEnd, groups.size());
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscallsIssued;
    m_lastTelemetry.spilledToMmap   = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

// ============================================================================
// 4. Encoded & 6. XOR Search (Offset, Static XOR, XOR Pair 32/64, Address XOR, GG_AUTO, XOR_OFFSET)
// ============================================================================
namespace {

uint64_t encodeTargetBits(DataType type, int64_t logicalInt, double logicalFloat) noexcept {
    switch (type) {
        case DataType::INT8:
            return static_cast<uint8_t>(logicalInt);
        case DataType::INT16:
            return static_cast<uint16_t>(logicalInt);
        case DataType::INT32:
            return static_cast<uint32_t>(logicalInt);
        case DataType::INT64:
            return static_cast<uint64_t>(logicalInt);
        case DataType::FLOAT: {
            float fv = static_cast<float>(logicalFloat);
            uint32_t bits = 0;
            std::memcpy(&bits, &fv, 4);
            return bits;
        }
        case DataType::DOUBLE: {
            uint64_t bits = 0;
            std::memcpy(&bits, &logicalFloat, 8);
            return bits;
        }
        default:
            return static_cast<uint32_t>(logicalInt);
    }
}

int64_t signExtendRaw(uint64_t raw, DataType dt) noexcept {
    switch (dt) {
        case DataType::INT8:   return static_cast<int8_t>(raw & 0xFFu);
        case DataType::INT16:  return static_cast<int16_t>(raw & 0xFFFFu);
        case DataType::INT32:  return static_cast<int32_t>(raw & 0xFFFFFFFFu);
        default:               return static_cast<int64_t>(raw);
    }
}

bool evalGroupField(const uint8_t* ptr, const GroupFieldItem& fld) noexcept {
    if (fld.op == 3) return true; // Wildcard (0~~0)
    if (fld.op == 1) {
        return evalSingleRange(ptr, fld.type, fld.iVal, fld.iMax, fld.fVal, fld.fMax, true);
    }
    if (fld.op == 2) {
        return !evalSingleRange(ptr, fld.type, fld.iVal, fld.iMax, fld.fVal, fld.fMax, true);
    }
    return evalSingleExact(ptr, fld.type, fld.iVal, fld.fVal, fld.epsilon);
}

} // anonymous namespace

EngineErrc SearchEngine::searchEncoded(
    DataType type,
    EncodedMode mode,
    int64_t logicalInt,
    double logicalFloat,
    uint64_t keyOrMask,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress
) {
    DataType effectiveType = (type == DataType::AUTO) ? DataType::INT32 : type;

    // Stateful GG Encrypted Search (Initial Snapshot for V1)
    if (mode == EncodedMode::GG_AUTO) {
        EngineErrc rc = startUnknownSnapshot(effectiveType, token, progress);
        if (rc == EngineErrc::OK) {
            m_encryptedSnapshot = true;
            m_encPrevLogicalInt = logicalInt;
            m_encPrevLogicalFlt = logicalFloat;
            m_encPrevTargetBits = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
        }
        return rc;
    }

    if (mode == EncodedMode::OFFSET_ADDED) {
        int64_t offsetKey = static_cast<int64_t>(keyOrMask);
        return searchExact(effectiveType, logicalInt + offsetKey, logicalFloat + static_cast<double>(offsetKey), 1e-4, aligned, token, progress);
    }
    if (mode == EncodedMode::XOR_STATIC) {
        uint64_t rawLogical = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
        int64_t encodedTarget = static_cast<int64_t>(rawLogical ^ keyOrMask);
        DataType intEquiv = (effectiveType == DataType::FLOAT) ? DataType::INT32
                          : (effectiveType == DataType::DOUBLE) ? DataType::INT64 : effectiveType;
        return searchExact(intEquiv, encodedTarget, 0.0, 0.0, aligned, token, progress);
    }

    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    if (m_regions.empty()) refreshRegions();

    size_t elemSz = std::max<size_t>(1, dataTypeSize(effectiveType));
    int64_t xorByteOffset = static_cast<int64_t>(keyOrMask);
    size_t absOffset = (mode == EncodedMode::XOR_OFFSET)
        ? static_cast<size_t>(std::min<int64_t>(std::llabs(xorByteOffset), 4096))
        : 0;
    size_t spanBytes = (mode == EncodedMode::XOR_PAIR_64) ? 16
                     : (mode == EncodedMode::XOR_PAIR_32) ? 8
                     : (mode == EncodedMode::XOR_OFFSET)  ? (elemSz + absOffset) : 4;
    size_t step = aligned ? elemSz : 1;
    size_t overlap = spanBytes > 1 ? (spanBytes - 1) : 0;

    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    uint64_t targetBits64 = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
    uint32_t targetBits32 = static_cast<uint32_t>(targetBits64);

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    std::vector<std::thread> workers;
    const size_t pageSize = m_io.pageSize();

    for (size_t tid = 0; tid < numThreads; ++tid) {
        workers.emplace_back([&]() {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    offsets.clear();
                    if (mode == EncodedMode::XOR_PAIR_32) {
                        SimdKernel::scanXorPair32(buf.data(), task.readLen, targetBits32, aligned ? 4 : 1, offsets);
                    } else if (mode == EncodedMode::XOR_PAIR_64) {
                        SimdKernel::scanXorPair64(buf.data(), task.readLen, targetBits64, aligned ? 8 : 1, offsets);
                    } else if (mode == EncodedMode::XOR_ADDRESS) {
                        uint32_t mask32 = (keyOrMask == 0) ? 0xFFFFFFFFu : static_cast<uint32_t>(keyOrMask);
                        SimdKernel::scanAddressXor32(task.startAddr, buf.data(), task.readLen, targetBits32, mask32, aligned ? 4 : 1, offsets);
                    } else if (mode == EncodedMode::XOR_OFFSET) {
                        size_t startOff = (xorByteOffset < 0) ? absOffset : 0;
                        if (step > 1 && (startOff % step) != 0) {
                            startOff += step - (startOff % step);
                        }
                        for (size_t off = startOff; off + elemSz <= task.logicalLen; off += step) {
                            int64_t keyPos = static_cast<int64_t>(off) + xorByteOffset;
                            if (keyPos < 0 || static_cast<size_t>(keyPos) + elemSz > task.readLen) continue;
                            uint64_t v = extractRawValue(buf.data() + off, effectiveType);
                            uint64_t k = extractRawValue(buf.data() + static_cast<size_t>(keyPos), effectiveType);
                            if (v != 0 && k != 0 && (v ^ k) == targetBits64) {
                                offsets.push_back(off);
                            }
                        }
                    }

                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                    size_t chunkMatches = 0;

                    for (size_t off : offsets) {
                        if (off >= task.logicalLen) continue;
                        size_t checkStart = (mode == EncodedMode::XOR_OFFSET && xorByteOffset < 0)
                            ? (off - absOffset) : off;
                        if (!isRangeInValidPages(checkStart, spanBytes, task.startAddr, pageSize, validMask)) continue;

                        chunkMatches++;
                        if (!storeAlreadyFull) {
                            CandidateEntry entry{};
                            entry.address   = task.startAddr + off;
                            entry.rawValue  = extractRawValue(buf.data() + off, effectiveType);
                            entry.type      = static_cast<uint8_t>(effectiveType);
                            entry.regionTag = rTag;
                            entry.flags     = static_cast<uint32_t>(mode);
                            localHits.push_back(entry);

                            if (localHits.size() >= 16384) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(localHits.data(), localHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                localHits.clear();
                                if (m_candidates.isFull()) storeAlreadyFull = true;
                            }
                        }
                    }

                    if (chunkMatches > 0) {
                        m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
        });
    }

    for (auto& w : workers) w.join();
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineEncoded(
    DataType type,
    EncodedMode mode,
    int64_t logicalInt,
    double logicalFloat,
    uint64_t keyOrMask,
    CancellationToken* token,
    ProgressCallback progress
) {
    DataType effectiveType = (type == DataType::AUTO) ? m_snapshotType : type;

    if (mode == EncodedMode::OFFSET_ADDED) {
        int64_t offsetKey = static_cast<int64_t>(keyOrMask);
        return refineExact(effectiveType, logicalInt + offsetKey, logicalFloat + static_cast<double>(offsetKey), 1e-4, token, progress);
    }
    if (mode == EncodedMode::XOR_STATIC) {
        uint64_t rawLogical = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
        DataType intEquiv = (effectiveType == DataType::FLOAT) ? DataType::INT32
                          : (effectiveType == DataType::DOUBLE) ? DataType::INT64 : effectiveType;
        return refineExact(intEquiv, static_cast<int64_t>(rawLogical ^ keyOrMask), 0.0, 0.0, token, progress);
    }

    auto t0 = std::chrono::steady_clock::now();
    uint64_t targetBits64 = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
    uint32_t targetBits32 = static_cast<uint32_t>(targetBits64);

    // ========================================================================
    // Stateful GG Encrypted Search Refinement (EncodedMode::GG_AUTO)
    // ========================================================================
    if (mode == EncodedMode::GG_AUTO) {
        const uint64_t uPrev = m_encPrevTargetBits;
        const uint64_t uCur  = targetBits64;
        const int64_t  iPrev = m_encPrevLogicalInt;
        const int64_t  iCur  = logicalInt;
        const DataType dt    = m_hasFullSnapshot ? m_snapshotType : effectiveType;
        const size_t elemSz  = std::max<size_t>(1, dataTypeSize(dt));
        const bool isFloatType = (dt == DataType::FLOAT || dt == DataType::DOUBLE);

        // Pass 1: Refine directly against full resident snapshot (V1 -> V2)
        if (m_hasFullSnapshot && m_candidates.empty()) {
            const auto& blocks = m_snapshotStore.blocks();
            if (blocks.empty()) {
                m_totalMatchedCount = 0;
                return EngineErrc::OK;
            }

            const size_t totalSnapBytes = std::max<size_t>(1, m_snapshotStore.totalSnapshotBytes());
            std::atomic<size_t> nextBlock{0};
            std::atomic<size_t> syscalls{0};
            std::atomic<size_t> bytesScanned{0};
            std::atomic<size_t> totalMatches{0};
            std::atomic<size_t> storedCount{0};
            std::mutex storeMutex;

            size_t numThreads = std::min(m_workerThreads, blocks.size());
            std::vector<std::thread> workers;

            for (size_t tid = 0; tid < numThreads; ++tid) {
                workers.emplace_back([&]() {
                    std::vector<uint8_t> curBuf;
                    std::vector<uint8_t> validMask;
                    std::vector<CandidateEntry> blockHits;
                    blockHits.reserve(4096);

                    while (true) {
                        if (token && token->isCancelled()) break;
                        size_t bIdx = nextBlock.fetch_add(1, std::memory_order_relaxed);
                        if (bIdx >= blocks.size()) break;

                        const auto& blk = blocks[bIdx];
                        uint8_t* oldPtr = m_snapshotStore.getBlockDataMut(blk);
                        if (!oldPtr) continue;

                        if (curBuf.size() < blk.length) curBuf.resize(blk.length);
                        size_t chunkReadable = 0;
                        EngineErrc rc = m_io.readChunkFaultTolerant(blk.startAddress, curBuf.data(), blk.length, validMask, &chunkReadable);
                        syscalls.fetch_add(1, std::memory_order_relaxed);
                        size_t doneBytes = bytesScanned.fetch_add(blk.length, std::memory_order_relaxed) + blk.length;

                        if (rc != EngineErrc::OK || chunkReadable == 0) {
                            if (progress) progress(doneBytes, totalSnapBytes);
                            continue;
                        }

                        blockHits.clear();
                        uint8_t rTag = regionFlagToIndex(blk.regionFlag);

                        for (size_t off = 0; off + elemSz <= blk.length; off += elemSz) {
                            uint64_t e1 = extractRawValue(oldPtr + off, dt);
                            uint64_t e2 = extractRawValue(curBuf.data() + off, dt);
                            if (e1 == 0 && e2 == 0) continue;

                            uint32_t matchFlags = 0;

                            // 1. Constant XOR Key Invariant: (E1 ^ U1) == (E2 ^ U2)
                            if (uCur != uPrev) {
                                if (e1 != e2 && ((e1 ^ uPrev) == (e2 ^ uCur))) {
                                    matchFlags |= 1u;
                                }
                            } else {
                                if (e1 == e2 && e1 != 0) {
                                    matchFlags |= 1u;
                                }
                            }

                            // 2. Dynamic Neighbor XOR Key Invariant (e.g. ObscuredInt / struct [key, val] at ±4, ±8)
                            if (elemSz == 4 || elemSz == 8) {
                                const int deltas[4] = {-8, -4, 4, 8};
                                for (int d : deltas) {
                                    if (elemSz == 8 && (d == -4 || d == 4)) continue;
                                    int64_t nPos = static_cast<int64_t>(off) + d;
                                    if (nPos < 0 || static_cast<size_t>(nPos) + elemSz > blk.length) continue;
                                    uint64_t n1 = extractRawValue(oldPtr + static_cast<size_t>(nPos), dt);
                                    uint64_t n2 = extractRawValue(curBuf.data() + static_cast<size_t>(nPos), dt);
                                    if (n1 != 0 && n2 != 0 && ((e1 ^ n1) == uPrev) && ((e2 ^ n2) == uCur)) {
                                        if (uCur == uPrev || e1 != e2 || n1 != n2) {
                                            matchFlags |= 2u;
                                            break;
                                        }
                                    }
                                }
                            }

                            // 3. Constant Additive Offset Invariant: (E2 - E1) == (V2 - V1) or (E1 - E2) == (V2 - V1)
                            if (!isFloatType && iCur != iPrev && e1 != 0 && e2 != 0) {
                                int64_t s1 = signExtendRaw(e1, dt);
                                int64_t s2 = signExtendRaw(e2, dt);
                                int64_t vDiff = iCur - iPrev;
                                if ((s2 - s1) == vDiff) {
                                    matchFlags |= 4u;
                                } else if ((s1 - s2) == vDiff) {
                                    matchFlags |= 8u;
                                }
                            }

                            if (matchFlags != 0) {
                                CandidateEntry entry{};
                                entry.address   = blk.startAddress + off;
                                entry.rawValue  = e2;
                                entry.type      = static_cast<uint8_t>(dt);
                                entry.regionTag = rTag;
                                entry.flags     = matchFlags;
                                blockHits.push_back(entry);
                            }
                        }

                        // Update snapshot block in-place
                        std::memcpy(oldPtr, curBuf.data(), blk.length);

                        if (!blockHits.empty()) {
                            size_t newTotal = totalMatches.fetch_add(blockHits.size(), std::memory_order_relaxed) + blockHits.size();
                            m_totalMatchedCount = newTotal;

                            if (storedCount.load(std::memory_order_relaxed) < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(blockHits.data(), blockHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                            }
                        }

                        if (progress) progress(doneBytes, totalSnapBytes);
                    }
                });
            }

            for (auto& w : workers) w.join();
            if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

            m_encPrevLogicalInt = logicalInt;
            m_encPrevLogicalFlt = logicalFloat;
            m_encPrevTargetBits = targetBits64;

            size_t matched = totalMatches.load();
            if (uCur == uPrev && matched > MmapCandidateStore::MAX_STORED_CANDIDATES) {
                m_candidates.clear();
                m_hasFullSnapshot = true;
                m_totalMatchedCount = matched;
            } else {
                m_snapshotStore.clear();
                m_hasFullSnapshot = false;
                m_candidates.sortByAddress();
                m_totalMatchedCount = std::max(matched, m_candidates.size());
            }

            auto t1 = std::chrono::steady_clock::now();
            m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
            m_lastTelemetry.totalBytesScanned = bytesScanned.load();
            m_lastTelemetry.readableBytes     = bytesScanned.load();
            m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
            m_lastTelemetry.syscallsIssued    = syscalls.load();
            return EngineErrc::OK;
        }

        // Pass 2+: Refine existing m_candidates (V2 -> V3 -> ...)
        if (m_candidates.empty()) {
            m_totalMatchedCount = 0;
            return EngineErrc::OK;
        }

        MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
        size_t syscalls = 0;
        uint8_t winBuf[24];

        for (size_t i = 0; i < m_candidates.size(); ++i) {
            if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
            CandidateEntry cand = m_candidates[i];
            DataType cDt = static_cast<DataType>(cand.type);
            size_t cSz = std::max<size_t>(1, dataTypeSize(cDt));

            uint64_t e1 = cand.rawValue;
            uint64_t e2 = 0;
            bool hasWin = false;
            if (cand.address >= 8 && m_io.readMemory(cand.address - 8, winBuf, 24) == EngineErrc::OK) {
                hasWin = true;
                e2 = extractRawValue(winBuf + 8, cDt);
            } else if (m_io.readMemory(cand.address, winBuf + 8, cSz) == EngineErrc::OK) {
                e2 = extractRawValue(winBuf + 8, cDt);
            } else {
                continue;
            }
            syscalls++;

            uint32_t prevFlags = (cand.flags == 0) ? 0x0Fu : cand.flags;
            uint32_t nextFlags = 0;

            if (prevFlags & 1u) {
                if (uCur != uPrev) {
                    if (e1 != e2 && ((e1 ^ uPrev) == (e2 ^ uCur))) nextFlags |= 1u;
                } else {
                    if (e1 == e2) nextFlags |= 1u;
                }
            }
            if ((prevFlags & 2u) && hasWin && (cSz == 4 || cSz == 8)) {
                const int offsetsInWin[4] = {0, 4, 12, 16}; // -8, -4, +4, +8 relative to winBuf+8
                for (int wOff : offsetsInWin) {
                    if (cSz == 8 && (wOff == 4 || wOff == 12)) continue;
                    uint64_t n2 = extractRawValue(winBuf + wOff, cDt);
                    if (n2 != 0 && ((e2 ^ n2) == uCur)) {
                        nextFlags |= 2u;
                        break;
                    }
                }
            }
            if ((prevFlags & 4u) && !isFloatType) {
                int64_t s1 = signExtendRaw(e1, cDt);
                int64_t s2 = signExtendRaw(e2, cDt);
                if ((s2 - s1) == (iCur - iPrev)) nextFlags |= 4u;
            }
            if ((prevFlags & 8u) && !isFloatType) {
                int64_t s1 = signExtendRaw(e1, cDt);
                int64_t s2 = signExtendRaw(e2, cDt);
                if ((s1 - s2) == (iCur - iPrev)) nextFlags |= 8u;
            }

            if (nextFlags != 0) {
                cand.rawValue = e2;
                cand.flags    = nextFlags;
                nextStore.append(cand);
            }
        }

        m_encPrevLogicalInt = logicalInt;
        m_encPrevLogicalFlt = logicalFloat;
        m_encPrevTargetBits = targetBits64;
        m_candidates = std::move(nextStore);
        m_totalMatchedCount = m_candidates.size();

        auto t1 = std::chrono::steady_clock::now();
        m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
        m_lastTelemetry.candidatesFound = m_totalMatchedCount;
        m_lastTelemetry.syscallsIssued  = syscalls;
        return EngineErrc::OK;
    }

    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    size_t elemSz = std::max<size_t>(1, dataTypeSize(effectiveType));
    int64_t xorByteOffset = static_cast<int64_t>(keyOrMask);
    size_t spanBytes = (mode == EncodedMode::XOR_PAIR_64) ? 16
                     : (mode == EncodedMode::XOR_PAIR_32) ? 8 : 4;

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscalls = 0;
    uint8_t rawBuf[16];

    for (size_t i = 0; i < m_candidates.size(); ++i) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        CandidateEntry cand = m_candidates[i];

        bool matched = false;
        uint64_t curRaw = cand.rawValue;

        if (mode == EncodedMode::XOR_OFFSET) {
            uint8_t vBuf[8] = {0};
            uint8_t kBuf[8] = {0};
            uintptr_t kAddr = static_cast<uintptr_t>(static_cast<int64_t>(cand.address) + xorByteOffset);
            if (m_io.readMemory(cand.address, vBuf, elemSz) != EngineErrc::OK) continue;
            if (m_io.readMemory(kAddr, kBuf, elemSz) != EngineErrc::OK) continue;
            syscalls += 2;
            uint64_t v = extractRawValue(vBuf, effectiveType);
            uint64_t k = extractRawValue(kBuf, effectiveType);
            matched = (v != 0 && k != 0 && (v ^ k) == targetBits64);
            curRaw = v;
        } else {
            if (m_io.readMemory(cand.address, rawBuf, spanBytes) != EngineErrc::OK) continue;
            syscalls++;
            if (mode == EncodedMode::XOR_PAIR_32) {
                uint32_t w0 = 0, w1 = 0;
                std::memcpy(&w0, rawBuf, 4);
                std::memcpy(&w1, rawBuf + 4, 4);
                matched = (w0 != 0 && w1 != 0 && (w0 ^ w1) == targetBits32);
                curRaw = w0;
            } else if (mode == EncodedMode::XOR_PAIR_64) {
                uint64_t q0 = 0, q1 = 0;
                std::memcpy(&q0, rawBuf, 8);
                std::memcpy(&q1, rawBuf + 8, 8);
                matched = (q0 != 0 && q1 != 0 && (q0 ^ q1) == targetBits64);
                curRaw = q0;
            } else if (mode == EncodedMode::XOR_ADDRESS) {
                uint32_t stored = 0;
                std::memcpy(&stored, rawBuf, 4);
                uint32_t mask32 = (keyOrMask == 0) ? 0xFFFFFFFFu : static_cast<uint32_t>(keyOrMask);
                uint32_t addrKey = static_cast<uint32_t>(cand.address) & mask32;
                matched = ((stored ^ addrKey) == targetBits32);
                curRaw = stored;
            }
        }

        if (matched) {
            cand.rawValue = curRaw;
            nextStore.append(cand);
        }
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscalls;
    return EngineErrc::OK;
}

// ============================================================================
// 5. Group / Struct Search (Ordered & Unordered with Anchor-First Scheduling)
// ============================================================================
EngineErrc SearchEngine::searchGroup(
    const GroupSearchQuery& query,
    CancellationToken* token,
    ProgressCallback progress
) {
    if (query.fields.empty()) return EngineErrc::ERR_INVAL;
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    if (m_regions.empty()) refreshRegions();

    // 1. Anchor-First Selectivity Scheduling: pick the field with highest entropy score
    size_t anchorIdx = 0;
    uint32_t bestScore = 0;
    for (size_t i = 0; i < query.fields.size(); ++i) {
        uint32_t sc = query.fields[i].computeSelectivityScore();
        if (sc > bestScore) {
            bestScore = sc;
            anchorIdx = i;
        }
    }
    const GroupFieldItem& anchor = query.fields[anchorIdx];

    size_t overlap = query.maxSpanBytes * 2 + 16;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    std::vector<std::thread> workers;
    const size_t pageSize = m_io.pageSize();

    for (size_t tid = 0; tid < numThreads; ++tid) {
        workers.emplace_back([&]() {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<size_t> anchorOffsets;
            std::vector<CandidateEntry> localHits;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc != EngineErrc::OK || chunkReadable == 0) {
                    size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                    if (progress) progress(done, totalPlannedBytes);
                    continue;
                }

                anchorOffsets.clear();
                size_t anchorSz = dataTypeSize(anchor.type);
                size_t anchorStep = query.aligned ? anchorSz : 1;

                if (anchor.op == 1) {
                    switch (anchor.type) {
                        case DataType::INT8:
                            SimdKernel::scanRange<int8_t>(buf.data(), task.readLen, static_cast<int8_t>(anchor.iVal), static_cast<int8_t>(anchor.iMax), true, 1, anchorOffsets);
                            break;
                        case DataType::INT16:
                            SimdKernel::scanRange<int16_t>(buf.data(), task.readLen, static_cast<int16_t>(anchor.iVal), static_cast<int16_t>(anchor.iMax), true, anchorStep, anchorOffsets);
                            break;
                        case DataType::INT32:
                            SimdKernel::scanRange<int32_t>(buf.data(), task.readLen, static_cast<int32_t>(anchor.iVal), static_cast<int32_t>(anchor.iMax), true, anchorStep, anchorOffsets);
                            break;
                        case DataType::INT64:
                            SimdKernel::scanRange<int64_t>(buf.data(), task.readLen, anchor.iVal, anchor.iMax, true, anchorStep, anchorOffsets);
                            break;
                        case DataType::FLOAT:
                            SimdKernel::scanRangeF32(buf.data(), task.readLen, static_cast<float>(anchor.fVal), static_cast<float>(anchor.fMax), true, anchorStep, anchorOffsets);
                            break;
                        case DataType::DOUBLE:
                            SimdKernel::scanRange<double>(buf.data(), task.readLen, anchor.fVal, anchor.fMax, true, anchorStep, anchorOffsets);
                            break;
                        default:
                            break;
                    }
                } else if (anchor.op == 0) {
                    switch (anchor.type) {
                        case DataType::INT8:
                            SimdKernel::scanExactI8(buf.data(), task.readLen, static_cast<int8_t>(anchor.iVal), anchorOffsets);
                            break;
                        case DataType::INT16:
                            SimdKernel::scanExactI16(buf.data(), task.readLen, static_cast<int16_t>(anchor.iVal), anchorStep, anchorOffsets);
                            break;
                        case DataType::INT32:
                            SimdKernel::scanExactI32(buf.data(), task.readLen, static_cast<int32_t>(anchor.iVal), anchorStep, anchorOffsets);
                            break;
                        case DataType::INT64:
                            SimdKernel::scanExactI64(buf.data(), task.readLen, anchor.iVal, anchorStep, anchorOffsets);
                            break;
                        case DataType::FLOAT:
                            SimdKernel::scanExactF32(buf.data(), task.readLen, static_cast<float>(anchor.fVal), static_cast<float>(anchor.epsilon), anchorStep, anchorOffsets);
                            break;
                        case DataType::DOUBLE:
                            SimdKernel::scanExactF64(buf.data(), task.readLen, anchor.fVal, anchor.epsilon, anchorStep, anchorOffsets);
                            break;
                        default:
                            break;
                    }
                } else {
                    for (size_t off = 0; off + anchorSz <= task.readLen; off += anchorStep) {
                        if (evalGroupField(buf.data() + off, anchor)) {
                            anchorOffsets.push_back(off);
                        }
                    }
                }

                uint8_t rTag = regionFlagToIndex(task.regionFlag);
                bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                size_t chunkMatches = 0;

                for (size_t aOff : anchorOffsets) {
                    if (!isRangeInValidPages(aOff, anchorSz, task.startAddr, pageSize, validMask)) continue;

                    size_t winStart = (aOff > query.maxSpanBytes) ? (aOff - query.maxSpanBytes) : 0;
                    size_t winEnd   = std::min(task.readLen, aOff + query.maxSpanBytes + 16);

                    bool allMatched = true;
                    size_t minMatchedOffset = aOff;
                    size_t maxMatchedEnd    = aOff + anchorSz;

                    if (query.ordered) {
                        size_t curUpperBound = aOff;
                        size_t firstFieldOffset = aOff;

                        for (size_t rev = 0; rev < anchorIdx; ++rev) {
                            size_t fIdx = anchorIdx - 1 - rev;
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = dataTypeSize(fld.type);
                            size_t fStep = query.aligned ? std::max<size_t>(fSz, 4) : 1;

                            if (curUpperBound < winStart + fSz) {
                                allMatched = false;
                                break;
                            }

                            size_t maxPos = curUpperBound - fSz;
                            if (fStep > 1) {
                                maxPos -= (maxPos % fStep);
                            }

                            bool foundField = false;
                            for (size_t pos = maxPos; pos >= winStart; ) {
                                if (evalGroupField(buf.data() + pos, fld)) {
                                    curUpperBound = pos;
                                    if (fIdx == 0) firstFieldOffset = pos;
                                    foundField = true;
                                    break;
                                }
                                if (pos < winStart + fStep) break;
                                pos -= fStep;
                            }
                            if (!foundField) {
                                allMatched = false;
                                break;
                            }
                        }

                        if (!allMatched) continue;
                        minMatchedOffset = (anchorIdx == 0) ? aOff : firstFieldOffset;

                        size_t prevEnd = aOff + anchorSz;
                        size_t allowedEnd = std::min(task.readLen, minMatchedOffset + query.maxSpanBytes);

                        for (size_t fIdx = anchorIdx + 1; fIdx < query.fields.size(); ++fIdx) {
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = dataTypeSize(fld.type);
                            size_t fStep = query.aligned ? fSz : 1;
                            size_t alignedStart = prevEnd;
                            if (fStep > 1 && (alignedStart % fStep) != 0) {
                                alignedStart += fStep - (alignedStart % fStep);
                            }

                            bool foundField = false;
                            for (size_t pos = alignedStart; pos + fSz <= allowedEnd; pos += fStep) {
                                if (evalGroupField(buf.data() + pos, fld)) {
                                    prevEnd = pos + fSz;
                                    maxMatchedEnd = prevEnd;
                                    foundField = true;
                                    break;
                                }
                            }
                            if (!foundField) {
                                allMatched = false;
                                break;
                            }
                        }
                    } else {
                        for (size_t fIdx = 0; fIdx < query.fields.size(); ++fIdx) {
                            if (fIdx == anchorIdx) continue;
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = dataTypeSize(fld.type);
                            size_t fStep = query.aligned ? fSz : 1;
                            size_t alignedStart = winStart;
                            if (fStep > 1 && (alignedStart % fStep) != 0) {
                                alignedStart += fStep - (alignedStart % fStep);
                            }

                            bool foundField = false;
                            for (size_t pos = alignedStart; pos + fSz <= winEnd; pos += fStep) {
                                if (pos == aOff) continue;
                                size_t candMin = std::min(minMatchedOffset, pos);
                                size_t candMax = std::max(maxMatchedEnd, pos + fSz);
                                if ((candMax - candMin) > query.maxSpanBytes) continue;

                                if (evalGroupField(buf.data() + pos, fld)) {
                                    minMatchedOffset = candMin;
                                    maxMatchedEnd    = candMax;
                                    foundField       = true;
                                    break;
                                }
                            }
                            if (!foundField) {
                                allMatched = false;
                                break;
                            }
                        }
                    }

                    if (allMatched && minMatchedOffset < task.logicalLen) {
                        size_t groupSpan = maxMatchedEnd - minMatchedOffset;
                        if (isRangeInValidPages(minMatchedOffset, groupSpan, task.startAddr, pageSize, validMask)) {
                            chunkMatches++;
                            if (!storeAlreadyFull) {
                                CandidateEntry entry{};
                                entry.address   = task.startAddr + minMatchedOffset;
                                entry.rawValue  = extractRawValue(buf.data() + minMatchedOffset, query.fields[0].type);
                                entry.type      = static_cast<uint8_t>(query.fields[0].type);
                                entry.regionTag = rTag;
                                localHits.push_back(entry);

                                if (localHits.size() >= 16384) {
                                    std::lock_guard<std::mutex> lk(storeMutex);
                                    m_candidates.appendBatch(localHits.data(), localHits.size());
                                    storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                    localHits.clear();
                                    if (m_candidates.isFull()) storeAlreadyFull = true;
                                }
                            }
                        }
                    }
                }

                if (chunkMatches > 0) {
                    m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
        });
    }

    for (auto& w : workers) w.join();
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.anchorFieldIndex  = anchorIdx;
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

} // namespace mem_engine
