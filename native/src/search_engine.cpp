#include "mem_engine/search_engine.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/simd_scanner.hpp"
#include <thread>
#include <algorithm>
#include <cmath>
#include <cstring>

namespace mem_engine {

namespace {

constexpr size_t CHUNK_SIZE = 2 * 1024 * 1024; // 2 MB chunk size
constexpr size_t COALESCE_GAP = 64 * 1024;     // 64 KB gap for refine read coalescing
constexpr size_t MAX_FUZZY_VMA_BYTES = 128ULL * 1024ULL * 1024ULL; // Skip giant >128MB single buffers in fuzzy init

inline ScanValue readValueAt(const uint8_t* ptr, DataType type) noexcept {
    ScanValue v{};
    switch (type) {
        case DataType::I8:  { int8_t x;  std::memcpy(&x, ptr, 1); v.i64 = x; break; }
        case DataType::U8:  { uint8_t x; std::memcpy(&x, ptr, 1); v.u64 = x; break; }
        case DataType::I16: { int16_t x; std::memcpy(&x, ptr, 2); v.i64 = x; break; }
        case DataType::U16: { uint16_t x;std::memcpy(&x, ptr, 2); v.u64 = x; break; }
        case DataType::I32:
        case DataType::XOR32: { int32_t x; std::memcpy(&x, ptr, 4); v.i64 = x; break; }
        case DataType::U32: { uint32_t x;std::memcpy(&x, ptr, 4); v.u64 = x; break; }
        case DataType::I64: { int64_t x; std::memcpy(&x, ptr, 8); v.i64 = x; break; }
        case DataType::U64: { uint64_t x;std::memcpy(&x, ptr, 8); v.u64 = x; break; }
        case DataType::F32: { float x;   std::memcpy(&x, ptr, 4); v.f32 = x; break; }
        case DataType::F64: { double x;  std::memcpy(&x, ptr, 8); v.f64 = x; break; }
        default: { int32_t x; std::memcpy(&x, ptr, 4); v.i64 = x; break; }
    }
    return v;
}

inline bool compareValues(ScanValue current, ScanValue ref, DataType type, CompareOp op) noexcept {
    if (type == DataType::F32) {
        if (std::isnan(current.f32) || std::isnan(ref.f32)) return false;
        float diff = std::fabs(current.f32 - ref.f32);
        switch (op) {
            case CompareOp::EQUAL:         return diff <= 1e-4f;
            case CompareOp::NOT_EQUAL:     return diff > 1e-4f;
            case CompareOp::GREATER:       return current.f32 > ref.f32;
            case CompareOp::GREATER_EQUAL: return current.f32 >= ref.f32 - 1e-5f;
            case CompareOp::LESS:          return current.f32 < ref.f32;
            case CompareOp::LESS_EQUAL:    return current.f32 <= ref.f32 + 1e-5f;
        }
    } else if (type == DataType::F64) {
        if (std::isnan(current.f64) || std::isnan(ref.f64)) return false;
        double diff = std::fabs(current.f64 - ref.f64);
        switch (op) {
            case CompareOp::EQUAL:         return diff <= 1e-7;
            case CompareOp::NOT_EQUAL:     return diff > 1e-7;
            case CompareOp::GREATER:       return current.f64 > ref.f64;
            case CompareOp::GREATER_EQUAL: return current.f64 >= ref.f64 - 1e-8;
            case CompareOp::LESS:          return current.f64 < ref.f64;
            case CompareOp::LESS_EQUAL:    return current.f64 <= ref.f64 + 1e-8;
        }
    } else if (type == DataType::U8 || type == DataType::U16 ||
               type == DataType::U32 || type == DataType::U64) {
        switch (op) {
            case CompareOp::EQUAL:         return current.u64 == ref.u64;
            case CompareOp::NOT_EQUAL:     return current.u64 != ref.u64;
            case CompareOp::GREATER:       return current.u64 > ref.u64;
            case CompareOp::GREATER_EQUAL: return current.u64 >= ref.u64;
            case CompareOp::LESS:          return current.u64 < ref.u64;
            case CompareOp::LESS_EQUAL:    return current.u64 <= ref.u64;
        }
    } else {
        switch (op) {
            case CompareOp::EQUAL:         return current.i64 == ref.i64;
            case CompareOp::NOT_EQUAL:     return current.i64 != ref.i64;
            case CompareOp::GREATER:       return current.i64 > ref.i64;
            case CompareOp::GREATER_EQUAL: return current.i64 >= ref.i64;
            case CompareOp::LESS:          return current.i64 < ref.i64;
            case CompareOp::LESS_EQUAL:    return current.i64 <= ref.i64;
        }
    }
    return false;
}

inline bool evaluateFuzzy(ScanValue cur, ScanValue prev, ScanValue delta,
                          DataType type, FuzzyCondition cond) noexcept {
    switch (cond) {
        case FuzzyCondition::UNCHANGED:
            return compareValues(cur, prev, type, CompareOp::EQUAL);
        case FuzzyCondition::CHANGED:
            return compareValues(cur, prev, type, CompareOp::NOT_EQUAL);
        case FuzzyCondition::INCREASED:
            return compareValues(cur, prev, type, CompareOp::GREATER);
        case FuzzyCondition::DECREASED:
            return compareValues(cur, prev, type, CompareOp::LESS);
        case FuzzyCondition::INCREASED_BY:
            if (type == DataType::F32) {
                return std::fabs((cur.f32 - prev.f32) - delta.f32) <= 1e-4f;
            } else if (type == DataType::F64) {
                return std::fabs((cur.f64 - prev.f64) - delta.f64) <= 1e-7;
            } else {
                return (cur.i64 - prev.i64) == delta.i64;
            }
        case FuzzyCondition::DECREASED_BY:
            if (type == DataType::F32) {
                return std::fabs((prev.f32 - cur.f32) - delta.f32) <= 1e-4f;
            } else if (type == DataType::F64) {
                return std::fabs((prev.f64 - cur.f64) - delta.f64) <= 1e-7;
            } else {
                return (prev.i64 - cur.i64) == delta.i64;
            }
    }
    return false;
}

} // namespace

SearchEngine::SearchEngine(std::shared_ptr<IMemoryReader> reader, std::string tempDir)
    : m_reader(std::move(reader)),
      m_tempDir(tempDir),
      m_candidates(MmapCandidateStore::DEFAULT_RAM_LIMIT, tempDir),
      m_snapshotStore(tempDir) {}

void SearchEngine::clearResults() noexcept {
    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
}

void SearchEngine::fetchCandidates(size_t offset, size_t limit, std::vector<CandidateEntry>& out) const {
    out.clear();
    if (limit == 0) return;

    if (!m_candidates.empty()) {
        const size_t total = m_candidates.size();
        if (offset >= total) return;
        const size_t end = std::min(total, offset + limit);
        out.reserve(end - offset);
        const CandidateEntry* ptr = m_candidates.data();
        for (size_t i = offset; i < end; ++i) {
            out.push_back(ptr[i]);
        }
        return;
    }

    if (m_hasFullSnapshot && m_snapshotStore.isValid()) {
        const size_t elemSize = dataTypeSize(m_currentType);
        const size_t step = m_currentAligned ? elemSize : 1;
        size_t currentIndex = 0;
        out.reserve(limit);

        for (const auto& block : m_snapshotStore.blocks()) {
            if (block.byteLength < elemSize) continue;
            const size_t blockCount = (block.byteLength - elemSize) / step + 1;
            if (currentIndex + blockCount <= offset) {
                currentIndex += blockCount;
                continue;
            }

            const uint8_t* ptr = m_snapshotStore.readBlockData(block);
            if (!ptr) {
                currentIndex += blockCount;
                continue;
            }

            size_t startElem = (offset > currentIndex) ? (offset - currentIndex) : 0;
            for (size_t e = startElem; e < blockCount && out.size() < limit; ++e) {
                const size_t byteOff = e * step;
                ScanValue val = readValueAt(ptr + byteOff, m_currentType);
                out.push_back(CandidateEntry{
                    block.startAddress + byteOff,
                    val,
                    m_currentType,
                    block.regionFlag
                });
            }
            currentIndex += blockCount;
            if (out.size() >= limit) break;
        }
    }
}

Result<std::vector<VmaRegion>> SearchEngine::fetchScanRegions() const {
    if (!m_reader || m_reader->targetPid() <= 0) {
        return Result<std::vector<VmaRegion>>::err(StatusCode::ERR_INVALID_PID, "Invalid reader or PID");
    }

    auto parsed = MapsParser::parse(m_reader->targetPid(), m_regionMask);
    if (!parsed.isOk()) return parsed;

    std::vector<VmaRegion> safeRegions;
    safeRegions.reserve(parsed.value.size());

    for (const auto& vma : parsed.value) {
        if (!vma.isScanSafe()) continue;
        if (vma.end <= m_rangeStart || vma.start >= m_rangeEnd) continue;

        VmaRegion clipped = vma;
        clipped.start = std::max(vma.start, m_rangeStart);
        clipped.end = std::min(vma.end, m_rangeEnd);
        if (clipped.size() > 0) {
            safeRegions.push_back(std::move(clipped));
        }
    }

    if (safeRegions.empty()) {
        return Result<std::vector<VmaRegion>>::err(StatusCode::ERR_EMPTY_MAPS, "No readable regions match mask");
    }
    return Result<std::vector<VmaRegion>>::ok(std::move(safeRegions));
}

std::vector<SearchEngine::ScanChunkTask> SearchEngine::buildChunkTasks(
    const std::vector<VmaRegion>& regions, size_t typeSize) const {
    std::vector<ScanChunkTask> tasks;
    tasks.reserve(regions.size() * 2);

    for (const auto& r : regions) {
        uint64_t cur = r.start;
        while (cur < r.end) {
            uint64_t next = std::min(cur + CHUNK_SIZE, r.end);
            uint64_t readEnd = (next < r.end) ? std::min(next + typeSize - 1, r.end) : next;
            tasks.push_back(ScanChunkTask{cur, static_cast<size_t>(readEnd - cur), r.regionFlag});
            cur = next;
        }
    }
    return tasks;
}

size_t SearchEngine::computeThreadCount(size_t taskCount) const noexcept {
    if (taskCount == 0) return 1;
    size_t hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 4;
    size_t desired = (m_maxThreads > 0) ? m_maxThreads : std::min<size_t>(hw, 8);
    return std::min(desired, taskCount);
}

Result<size_t> SearchEngine::searchExact(ScanValue target, DataType type, bool aligned,
                                         const ProgressCallback& progressCb) {
    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
    m_currentType = type;
    m_currentAligned = aligned;

    auto regRes = fetchScanRegions();
    if (!regRes.isOk()) return Result<size_t>::err(regRes.status, regRes.message);

    const size_t elemSize = dataTypeSize(type);
    const size_t step = aligned ? elemSize : 1;
    auto tasks = buildChunkTasks(regRes.value, elemSize);

    size_t totalBytes = 0;
    for (const auto& t : tasks) totalBytes += t.length;

    const size_t numThreads = computeThreadCount(tasks.size());
    std::atomic<size_t> nextTaskIdx{0};
    std::atomic<size_t> bytesProcessed{0};
    std::atomic<size_t> totalFound{0};
    std::atomic<size_t> storedReserved{0};

    // Per-task results guarantee ascending address order without an expensive std::sort
    std::vector<std::vector<CandidateEntry>> taskHits(tasks.size());

    auto worker = [&]() {
        std::vector<uint8_t> buf(CHUNK_SIZE + 16);
        std::vector<size_t> matchOffsets;
        matchOffsets.reserve(4096);

        while (!m_cancelled.load(std::memory_order_relaxed)) {
            size_t idx = nextTaskIdx.fetch_add(1, std::memory_order_relaxed);
            if (idx >= tasks.size()) break;

            const auto& task = tasks[idx];
            if (buf.size() < task.length) buf.resize(task.length);

            auto rd = m_reader->read(task.startAddr, buf.data(), task.length);
            if (rd.isOk() && rd.value >= elemSize) {
                matchOffsets.clear();
                SimdScanner::scanExactBuffer(buf.data(), rd.value, type, target, step, matchOffsets);

                const size_t hitCount = matchOffsets.size();
                if (hitCount > 0) {
                    const size_t newFound = totalFound.fetch_add(hitCount, std::memory_order_relaxed) + hitCount;
                    // Only allocate CandidateEntry structs up to MAX_STORED_CANDIDATES (2M = 48MB)
                    size_t prevStored = storedReserved.load(std::memory_order_relaxed);
                    if (prevStored < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                        prevStored = storedReserved.fetch_add(hitCount, std::memory_order_relaxed);
                        if (prevStored < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            const size_t toKeep = std::min(hitCount, MmapCandidateStore::MAX_STORED_CANDIDATES - prevStored);
                            auto& localOut = taskHits[idx];
                            localOut.reserve(toKeep);
                            for (size_t k = 0; k < toKeep; ++k) {
                                const size_t off = matchOffsets[k];
                                ScanValue val = readValueAt(buf.data() + off, type);
                                localOut.push_back(CandidateEntry{
                                    task.startAddr + off, val, type, task.regionFlag
                                });
                            }
                        }
                    }
                    (void)newFound;
                }
            }

            size_t done = bytesProcessed.fetch_add(task.length, std::memory_order_relaxed) + task.length;
            if (progressCb) {
                progressCb(done, totalBytes, totalFound.load(std::memory_order_relaxed));
            }
        }
    };

    std::vector<std::thread> pool;
    pool.reserve(numThreads);
    for (size_t i = 0; i < numThreads; ++i) pool.emplace_back(worker);
    for (auto& t : pool) t.join();

    if (m_cancelled.load(std::memory_order_relaxed)) {
        return Result<size_t>::err(StatusCode::ERR_CANCELLED, "Exact search cancelled");
    }

    size_t totalStored = 0;
    for (const auto& v : taskHits) totalStored += v.size();
    m_candidates.reserve(std::min(totalStored, MmapCandidateStore::MAX_STORED_CANDIDATES));
    for (const auto& v : taskHits) {
        if (!v.empty()) {
            m_candidates.appendBatch(v.data(), v.size());
        }
    }

    m_totalMatchedCount = std::max(totalFound.load(std::memory_order_relaxed), m_candidates.size());
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<size_t> SearchEngine::refineExact(ScanValue target, CompareOp op,
                                         const ProgressCallback& progressCb) {
    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);

    if (m_candidates.empty() && !m_hasFullSnapshot) {
        return Result<size_t>::ok(0);
    }

    const size_t elemSize = dataTypeSize(m_currentType);

    if (m_hasFullSnapshot && m_candidates.empty()) {
        MmapCandidateStore refined(MmapCandidateStore::DEFAULT_RAM_LIMIT, m_tempDir);
        const auto& blocks = m_snapshotStore.blocks();
        std::vector<uint8_t> curBuf;
        size_t step = m_currentAligned ? elemSize : 1;
        size_t processed = 0;
        size_t totalFound = 0;

        for (const auto& blk : blocks) {
            if (m_cancelled.load(std::memory_order_relaxed)) {
                return Result<size_t>::err(StatusCode::ERR_CANCELLED, "Refine cancelled");
            }
            curBuf.resize(blk.byteLength);
            auto rd = m_reader->read(blk.startAddress, curBuf.data(), blk.byteLength);
            if (rd.isOk() && rd.value >= elemSize) {
                for (size_t off = 0; off + elemSize <= rd.value; off += step) {
                    ScanValue curVal = readValueAt(curBuf.data() + off, m_currentType);
                    if (compareValues(curVal, target, m_currentType, op)) {
                        ++totalFound;
                        if (!refined.isFull()) {
                            refined.push_back(CandidateEntry{
                                blk.startAddress + off, curVal, m_currentType, blk.regionFlag
                            });
                        }
                    }
                }
            }
            processed += blk.byteLength;
            if (progressCb) progressCb(processed, m_snapshotStore.totalBytes(), totalFound);
        }

        m_snapshotStore.clear();
        m_hasFullSnapshot = false;
        m_candidates.swap(refined);
        m_totalMatchedCount = std::max(totalFound, m_candidates.size());
        return Result<size_t>::ok(m_totalMatchedCount);
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_RAM_LIMIT, m_tempDir);
    const size_t count = m_candidates.size();
    const CandidateEntry* entries = m_candidates.data();

    std::vector<uint8_t> readBuf;
    size_t i = 0;
    while (i < count) {
        if (m_cancelled.load(std::memory_order_relaxed)) {
            return Result<size_t>::err(StatusCode::ERR_CANCELLED, "Refine cancelled");
        }

        size_t batchEnd = i + 1;
        uint64_t spanStart = entries[i].address;
        uint64_t spanEnd = spanStart + elemSize;

        while (batchEnd < count &&
               (entries[batchEnd].address - spanEnd) <= COALESCE_GAP &&
               (entries[batchEnd].address + elemSize - spanStart) <= CHUNK_SIZE) {
            spanEnd = entries[batchEnd].address + elemSize;
            ++batchEnd;
        }

        const size_t spanLen = static_cast<size_t>(spanEnd - spanStart);
        readBuf.resize(spanLen);
        auto rd = m_reader->read(spanStart, readBuf.data(), spanLen);

        if (rd.isOk()) {
            for (size_t k = i; k < batchEnd; ++k) {
                size_t relOff = static_cast<size_t>(entries[k].address - spanStart);
                if (relOff + elemSize <= rd.value) {
                    ScanValue curVal = readValueAt(readBuf.data() + relOff, entries[k].type);
                    if (compareValues(curVal, target, entries[k].type, op)) {
                        CandidateEntry updated = entries[k];
                        updated.previousValue = curVal;
                        nextStore.push_back(updated);
                    }
                }
            }
        }

        i = batchEnd;
        if (progressCb) progressCb(i, count, nextStore.size());
    }

    m_candidates.swap(nextStore);
    m_totalMatchedCount = m_candidates.size();
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<size_t> SearchEngine::searchRange(ScanValue minVal, ScanValue maxVal, DataType type,
                                         bool aligned, const ProgressCallback& progressCb) {
    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
    m_currentType = type;
    m_currentAligned = aligned;

    auto regRes = fetchScanRegions();
    if (!regRes.isOk()) return Result<size_t>::err(regRes.status, regRes.message);

    const size_t elemSize = dataTypeSize(type);
    const size_t step = aligned ? elemSize : 1;
    auto tasks = buildChunkTasks(regRes.value, elemSize);

    size_t totalBytes = 0;
    for (const auto& t : tasks) totalBytes += t.length;

    const size_t numThreads = computeThreadCount(tasks.size());
    std::atomic<size_t> nextTaskIdx{0};
    std::atomic<size_t> bytesProcessed{0};
    std::atomic<size_t> totalFound{0};
    std::atomic<size_t> storedReserved{0};
    std::vector<std::vector<CandidateEntry>> taskHits(tasks.size());

    auto worker = [&]() {
        std::vector<uint8_t> buf(CHUNK_SIZE + 16);
        std::vector<size_t> matchOffsets;
        matchOffsets.reserve(4096);

        while (!m_cancelled.load(std::memory_order_relaxed)) {
            size_t idx = nextTaskIdx.fetch_add(1, std::memory_order_relaxed);
            if (idx >= tasks.size()) break;

            const auto& task = tasks[idx];
            if (buf.size() < task.length) buf.resize(task.length);

            auto rd = m_reader->read(task.startAddr, buf.data(), task.length);
            if (rd.isOk() && rd.value >= elemSize) {
                matchOffsets.clear();
                SimdScanner::scanRangeBuffer(buf.data(), rd.value, type, minVal, maxVal, step, matchOffsets);

                const size_t hitCount = matchOffsets.size();
                if (hitCount > 0) {
                    totalFound.fetch_add(hitCount, std::memory_order_relaxed);
                    size_t prevStored = storedReserved.load(std::memory_order_relaxed);
                    if (prevStored < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                        prevStored = storedReserved.fetch_add(hitCount, std::memory_order_relaxed);
                        if (prevStored < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            const size_t toKeep = std::min(hitCount, MmapCandidateStore::MAX_STORED_CANDIDATES - prevStored);
                            auto& localOut = taskHits[idx];
                            localOut.reserve(toKeep);
                            for (size_t k = 0; k < toKeep; ++k) {
                                const size_t off = matchOffsets[k];
                                ScanValue val = readValueAt(buf.data() + off, type);
                                localOut.push_back(CandidateEntry{
                                    task.startAddr + off, val, type, task.regionFlag
                                });
                            }
                        }
                    }
                }
            }

            size_t done = bytesProcessed.fetch_add(task.length, std::memory_order_relaxed) + task.length;
            if (progressCb) progressCb(done, totalBytes, totalFound.load(std::memory_order_relaxed));
        }
    };

    std::vector<std::thread> pool;
    pool.reserve(numThreads);
    for (size_t i = 0; i < numThreads; ++i) pool.emplace_back(worker);
    for (auto& t : pool) t.join();

    if (m_cancelled.load(std::memory_order_relaxed)) {
        return Result<size_t>::err(StatusCode::ERR_CANCELLED, "Range search cancelled");
    }

    size_t totalStored = 0;
    for (const auto& v : taskHits) totalStored += v.size();
    m_candidates.reserve(std::min(totalStored, MmapCandidateStore::MAX_STORED_CANDIDATES));
    for (const auto& v : taskHits) {
        if (!v.empty()) {
            m_candidates.appendBatch(v.data(), v.size());
        }
    }

    m_totalMatchedCount = std::max(totalFound.load(std::memory_order_relaxed), m_candidates.size());
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<size_t> SearchEngine::refineRange(ScanValue minVal, ScanValue maxVal,
                                         const ProgressCallback& progressCb) {
    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);
    if (m_candidates.empty()) return Result<size_t>::ok(0);

    const size_t elemSize = dataTypeSize(m_currentType);
    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_RAM_LIMIT, m_tempDir);
    const size_t count = m_candidates.size();
    const CandidateEntry* entries = m_candidates.data();

    std::vector<uint8_t> readBuf;
    size_t i = 0;
    while (i < count) {
        size_t batchEnd = i + 1;
        uint64_t spanStart = entries[i].address;
        uint64_t spanEnd = spanStart + elemSize;

        while (batchEnd < count &&
               (entries[batchEnd].address - spanEnd) <= COALESCE_GAP &&
               (entries[batchEnd].address + elemSize - spanStart) <= CHUNK_SIZE) {
            spanEnd = entries[batchEnd].address + elemSize;
            ++batchEnd;
        }

        const size_t spanLen = static_cast<size_t>(spanEnd - spanStart);
        readBuf.resize(spanLen);
        auto rd = m_reader->read(spanStart, readBuf.data(), spanLen);

        if (rd.isOk()) {
            for (size_t k = i; k < batchEnd; ++k) {
                size_t relOff = static_cast<size_t>(entries[k].address - spanStart);
                if (relOff + elemSize <= rd.value) {
                    ScanValue curVal = readValueAt(readBuf.data() + relOff, entries[k].type);
                    if (compareValues(curVal, minVal, entries[k].type, CompareOp::GREATER_EQUAL) &&
                        compareValues(curVal, maxVal, entries[k].type, CompareOp::LESS_EQUAL)) {
                        CandidateEntry updated = entries[k];
                        updated.previousValue = curVal;
                        nextStore.push_back(updated);
                    }
                }
            }
        }
        i = batchEnd;
        if (progressCb) progressCb(i, count, nextStore.size());
    }

    m_candidates.swap(nextStore);
    m_totalMatchedCount = m_candidates.size();
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<size_t> SearchEngine::startUnknownSnapshot(DataType type, bool aligned,
                                                  const ProgressCallback& progressCb) {
    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
    m_currentType = type;
    m_currentAligned = aligned;

    auto regRes = fetchScanRegions();
    if (!regRes.isOk()) return Result<size_t>::err(regRes.status, regRes.message);

    if (!m_snapshotStore.initialize()) {
        return Result<size_t>::err(StatusCode::ERR_MMAP_FAILED, "Failed to initialize anonymous snapshot buffer");
    }

    const size_t elemSize = dataTypeSize(type);
    const size_t step = aligned ? elemSize : 1;

    size_t totalBytes = 0;
    for (const auto& r : regRes.value) {
        if (!r.writable || r.size() > MAX_FUZZY_VMA_BYTES) continue;
        totalBytes += r.size();
    }
    if (totalBytes == 0) {
        // Fallback if user selected read-only regions only
        for (const auto& r : regRes.value) {
            if (r.size() <= MAX_FUZZY_VMA_BYTES) totalBytes += r.size();
        }
    }

    std::vector<uint8_t> buf(CHUNK_SIZE);
    size_t doneBytes = 0;
    size_t readableBytes = 0;
    size_t sampledElements = 0;

    for (const auto& r : regRes.value) {
        if (r.size() > MAX_FUZZY_VMA_BYTES) continue;
        // Prefer writable regions for fuzzy value changes unless none matched
        uint64_t cur = r.start;
        while (cur < r.end) {
            if (m_cancelled.load(std::memory_order_relaxed)) {
                m_snapshotStore.clear();
                return Result<size_t>::err(StatusCode::ERR_CANCELLED, "Snapshot cancelled");
            }
            size_t len = static_cast<size_t>(std::min<uint64_t>(CHUNK_SIZE, r.end - cur));
            auto rd = m_reader->read(cur, buf.data(), len);
            if (rd.isOk() && rd.value >= elemSize) {
                if (m_snapshotStore.appendRegion(cur, buf.data(), rd.value, r.regionFlag)) {
                    readableBytes += rd.value;
                    sampledElements += (rd.value - elemSize) / step + 1;
                } else {
                    // Snapshot cap reached (256 MB); stop adding more blocks cleanly
                    cur = r.end;
                    break;
                }
            }
            cur += len;
            doneBytes += len;
            if (progressCb) progressCb(std::min(doneBytes, totalBytes), std::max(totalBytes, doneBytes), sampledElements);
        }
        if (m_snapshotStore.totalBytes() >= MmapRegionSnapshotStore::MAX_SNAPSHOT_BYTES) {
            break;
        }
    }

    if (sampledElements == 0) {
        return Result<size_t>::err(StatusCode::ERR_PARTIAL_READ, "No readable pages captured in snapshot");
    }

    m_hasFullSnapshot = true;
    m_totalMatchedCount = sampledElements;
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<size_t> SearchEngine::refineFuzzy(FuzzyCondition condition, ScanValue delta,
                                         const ProgressCallback& progressCb) {
    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);

    const size_t elemSize = dataTypeSize(m_currentType);
    const size_t step = m_currentAligned ? elemSize : 1;

    if (m_hasFullSnapshot && m_candidates.empty()) {
        // If user clicked UNCHANGED right after initial snapshot, update snapshot values in place
        // and keep m_hasFullSnapshot if still > MAX_STORED_CANDIDATES, or materialize up to MAX_STORED_CANDIDATES!
        MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_RAM_LIMIT, m_tempDir);
        const auto& blocks = m_snapshotStore.blocks();
        std::vector<uint8_t> curBuf;
        size_t doneBytes = 0;
        size_t totalMatched = 0;

        for (const auto& blk : blocks) {
            if (m_cancelled.load(std::memory_order_relaxed)) {
                return Result<size_t>::err(StatusCode::ERR_CANCELLED, "Fuzzy refine cancelled");
            }
            const uint8_t* oldPtr = m_snapshotStore.readBlockData(blk);
            if (!oldPtr) continue;

            curBuf.resize(blk.byteLength);
            auto rd = m_reader->read(blk.startAddress, curBuf.data(), blk.byteLength);
            if (rd.isOk() && rd.value >= elemSize) {
                const size_t limit = std::min(rd.value, blk.byteLength);
                for (size_t off = 0; off + elemSize <= limit; off += step) {
                    ScanValue oldVal = readValueAt(oldPtr + off, m_currentType);
                    ScanValue curVal = readValueAt(curBuf.data() + off, m_currentType);
                    if (evaluateFuzzy(curVal, oldVal, delta, m_currentType, condition)) {
                        ++totalMatched;
                        if (!nextStore.isFull()) {
                            nextStore.push_back(CandidateEntry{
                                blk.startAddress + off, curVal, m_currentType, blk.regionFlag
                            });
                        }
                    }
                }
            }
            doneBytes += blk.byteLength;
            if (progressCb) progressCb(doneBytes, m_snapshotStore.totalBytes(), totalMatched);
        }

        m_snapshotStore.clear();
        m_hasFullSnapshot = false;
        m_candidates.swap(nextStore);
        m_totalMatchedCount = std::max(totalMatched, m_candidates.size());
        return Result<size_t>::ok(m_totalMatchedCount);
    }

    if (m_candidates.empty()) return Result<size_t>::ok(0);

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_RAM_LIMIT, m_tempDir);
    const size_t count = m_candidates.size();
    const CandidateEntry* entries = m_candidates.data();

    std::vector<uint8_t> readBuf;
    size_t i = 0;
    while (i < count) {
        size_t batchEnd = i + 1;
        uint64_t spanStart = entries[i].address;
        uint64_t spanEnd = spanStart + elemSize;

        while (batchEnd < count &&
               (entries[batchEnd].address - spanEnd) <= COALESCE_GAP &&
               (entries[batchEnd].address + elemSize - spanStart) <= CHUNK_SIZE) {
            spanEnd = entries[batchEnd].address + elemSize;
            ++batchEnd;
        }

        const size_t spanLen = static_cast<size_t>(spanEnd - spanStart);
        readBuf.resize(spanLen);
        auto rd = m_reader->read(spanStart, readBuf.data(), spanLen);

        if (rd.isOk()) {
            for (size_t k = i; k < batchEnd; ++k) {
                size_t relOff = static_cast<size_t>(entries[k].address - spanStart);
                if (relOff + elemSize <= rd.value) {
                    ScanValue curVal = readValueAt(readBuf.data() + relOff, entries[k].type);
                    if (evaluateFuzzy(curVal, entries[k].previousValue, delta, entries[k].type, condition)) {
                        CandidateEntry updated = entries[k];
                        updated.previousValue = curVal;
                        nextStore.push_back(updated);
                    }
                }
            }
        }
        i = batchEnd;
        if (progressCb) progressCb(i, count, nextStore.size());
    }

    m_candidates.swap(nextStore);
    m_totalMatchedCount = m_candidates.size();
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<size_t> SearchEngine::searchGroup(const GroupSearchQuery& query,
                                         const ProgressCallback& progressCb) {
    if (query.items.empty() || query.maxRadiusBytes == 0 || query.maxRadiusBytes > 65536) {
        return Result<size_t>::err(StatusCode::ERR_INVALID_ARGUMENT, "Invalid group query parameters");
    }

    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;

    size_t anchorIdx = 0;
    size_t maxAnchorSize = 0;
    for (size_t i = 0; i < query.items.size(); ++i) {
        size_t sz = dataTypeSize(query.items[i].type);
        if (sz > maxAnchorSize) {
            maxAnchorSize = sz;
            anchorIdx = i;
        }
    }
    const auto& anchor = query.items[anchorIdx];
    m_currentType = anchor.type;

    auto regRes = fetchScanRegions();
    if (!regRes.isOk()) return Result<size_t>::err(regRes.status, regRes.message);

    auto tasks = buildChunkTasks(regRes.value, query.maxRadiusBytes);
    size_t totalBytes = 0;
    for (const auto& t : tasks) totalBytes += t.length;

    const size_t numThreads = computeThreadCount(tasks.size());
    std::atomic<size_t> nextTaskIdx{0};
    std::atomic<size_t> bytesProcessed{0};
    std::atomic<size_t> totalFound{0};
    std::atomic<size_t> storedReserved{0};
    std::vector<std::vector<CandidateEntry>> taskHits(tasks.size());

    auto worker = [&]() {
        std::vector<uint8_t> buf(CHUNK_SIZE + query.maxRadiusBytes + 16);
        std::vector<size_t> anchorOffsets;
        anchorOffsets.reserve(1024);

        while (!m_cancelled.load(std::memory_order_relaxed)) {
            size_t idx = nextTaskIdx.fetch_add(1, std::memory_order_relaxed);
            if (idx >= tasks.size()) break;

            const auto& task = tasks[idx];
            if (buf.size() < task.length) buf.resize(task.length);

            auto rd = m_reader->read(task.startAddr, buf.data(), task.length);
            if (rd.isOk() && rd.value >= maxAnchorSize) {
                anchorOffsets.clear();
                SimdScanner::scanExactBuffer(buf.data(), rd.value, anchor.type,
                                             anchor.value, dataTypeSize(anchor.type), anchorOffsets);

                auto& localOut = taskHits[idx];
                for (size_t anchorOff : anchorOffsets) {
                    size_t winStart = (anchorOff > query.maxRadiusBytes) ? (anchorOff - query.maxRadiusBytes) : 0;
                    size_t winEnd = std::min(rd.value, anchorOff + query.maxRadiusBytes);

                    bool allMatched = true;
                    size_t prevMatchOff = winStart;
                    std::vector<CandidateEntry> groupEntries;
                    groupEntries.reserve(query.items.size());

                    for (size_t qIdx = 0; qIdx < query.items.size(); ++qIdx) {
                        if (qIdx == anchorIdx) {
                            if (query.ordered && anchorOff < prevMatchOff) {
                                allMatched = false;
                                break;
                            }
                            prevMatchOff = anchorOff;
                            groupEntries.push_back(CandidateEntry{
                                task.startAddr + anchorOff, anchor.value, anchor.type, task.regionFlag
                            });
                            continue;
                        }

                        const auto& sub = query.items[qIdx];
                        const size_t subSize = dataTypeSize(sub.type);
                        size_t searchFrom = query.ordered ? prevMatchOff : winStart;
                        size_t alignedFrom = searchFrom;
                        size_t rem = alignedFrom % subSize;
                        if (rem != 0) alignedFrom += (subSize - rem);

                        bool foundSub = false;
                        for (size_t pos = alignedFrom; pos + subSize <= winEnd; pos += subSize) {
                            ScanValue candVal = readValueAt(buf.data() + pos, sub.type);
                            if (compareValues(candVal, sub.value, sub.type, CompareOp::EQUAL)) {
                                foundSub = true;
                                prevMatchOff = pos;
                                groupEntries.push_back(CandidateEntry{
                                    task.startAddr + pos, candVal, sub.type, task.regionFlag
                                });
                                break;
                            }
                        }
                        if (!foundSub) {
                            allMatched = false;
                            break;
                        }
                    }

                    if (allMatched) {
                        const size_t gCount = groupEntries.size();
                        totalFound.fetch_add(gCount, std::memory_order_relaxed);
                        size_t prevStored = storedReserved.fetch_add(gCount, std::memory_order_relaxed);
                        if (prevStored < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            const size_t toKeep = std::min(gCount, MmapCandidateStore::MAX_STORED_CANDIDATES - prevStored);
                            localOut.insert(localOut.end(), groupEntries.begin(), groupEntries.begin() + toKeep);
                        }
                    }
                }
            }

            size_t done = bytesProcessed.fetch_add(task.length, std::memory_order_relaxed) + task.length;
            if (progressCb) progressCb(done, totalBytes, totalFound.load(std::memory_order_relaxed));
        }
    };

    std::vector<std::thread> pool;
    pool.reserve(numThreads);
    for (size_t i = 0; i < numThreads; ++i) pool.emplace_back(worker);
    for (auto& t : pool) t.join();

    for (const auto& v : taskHits) {
        if (!v.empty()) {
            m_candidates.appendBatch(v.data(), v.size());
        }
    }
    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalFound.load(std::memory_order_relaxed), m_candidates.size());
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<size_t> SearchEngine::searchEncrypted(int64_t plainTarget, DataType type,
                                             uint64_t explicitXorKey,
                                             uint32_t nearbyKeyOffsetBytes,
                                             const ProgressCallback& progressCb) {
    if (explicitXorKey != 0) {
        ScanValue encoded{};
        encoded.u64 = static_cast<uint64_t>(plainTarget) ^ explicitXorKey;
        return searchExact(encoded, type, true, progressCb);
    }

    std::lock_guard<std::mutex> lock(m_engineMutex);
    m_cancelled.store(false, std::memory_order_relaxed);
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_totalMatchedCount = 0;
    m_currentType = type;

    auto regRes = fetchScanRegions();
    if (!regRes.isOk()) return Result<size_t>::err(regRes.status, regRes.message);

    const size_t elemSize = dataTypeSize(type);
    auto tasks = buildChunkTasks(regRes.value, elemSize + nearbyKeyOffsetBytes);
    size_t totalBytes = 0;
    for (const auto& t : tasks) totalBytes += t.length;

    const size_t numThreads = computeThreadCount(tasks.size());
    std::atomic<size_t> nextTaskIdx{0};
    std::atomic<size_t> bytesProcessed{0};
    std::atomic<size_t> totalFound{0};
    std::atomic<size_t> storedReserved{0};
    std::vector<std::vector<CandidateEntry>> taskHits(tasks.size());

    auto worker = [&]() {
        std::vector<uint8_t> buf(CHUNK_SIZE + 32);

        while (!m_cancelled.load(std::memory_order_relaxed)) {
            size_t idx = nextTaskIdx.fetch_add(1, std::memory_order_relaxed);
            if (idx >= tasks.size()) break;

            const auto& task = tasks[idx];
            if (buf.size() < task.length) buf.resize(task.length);

            auto rd = m_reader->read(task.startAddr, buf.data(), task.length);
            if (rd.isOk() && rd.value >= elemSize + nearbyKeyOffsetBytes) {
                auto& localOut = taskHits[idx];
                for (size_t off = 0; off + elemSize + nearbyKeyOffsetBytes <= rd.value; off += 4) {
                    uint32_t valWord = 0;
                    uint32_t keyWord = 0;
                    std::memcpy(&valWord, buf.data() + off, 4);
                    std::memcpy(&keyWord, buf.data() + off + nearbyKeyOffsetBytes, 4);
                    if (keyWord != 0 && static_cast<int32_t>(valWord ^ keyWord) == static_cast<int32_t>(plainTarget)) {
                        totalFound.fetch_add(1, std::memory_order_relaxed);
                        size_t prev = storedReserved.fetch_add(1, std::memory_order_relaxed);
                        if (prev < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            ScanValue sv{};
                            sv.u64 = valWord;
                            localOut.push_back(CandidateEntry{
                                task.startAddr + off, sv, DataType::XOR32, task.regionFlag
                            });
                        }
                    }
                }
            }
            size_t done = bytesProcessed.fetch_add(task.length, std::memory_order_relaxed) + task.length;
            if (progressCb) progressCb(done, totalBytes, totalFound.load(std::memory_order_relaxed));
        }
    };

    std::vector<std::thread> pool;
    pool.reserve(numThreads);
    for (size_t i = 0; i < numThreads; ++i) pool.emplace_back(worker);
    for (auto& t : pool) t.join();

    for (const auto& v : taskHits) {
        if (!v.empty()) {
            m_candidates.appendBatch(v.data(), v.size());
        }
    }
    m_totalMatchedCount = std::max(totalFound.load(std::memory_order_relaxed), m_candidates.size());
    return Result<size_t>::ok(m_totalMatchedCount);
}

Result<std::vector<PointerChainResult>> SearchEngine::scanPointerChains(
    const PointerScanConfig& config,
    const ProgressCallback& progressCb) {
    if (config.targetAddress == 0 || config.maxDepth == 0) {
        return Result<std::vector<PointerChainResult>>::err(StatusCode::ERR_INVALID_ARGUMENT,
                                                            "Invalid pointer scan config");
    }

    auto regRes = fetchScanRegions();
    if (!regRes.isOk()) return Result<std::vector<PointerChainResult>>::err(regRes.status, regRes.message);

    const size_t ptrSize = config.is64Bit ? 8 : 4;
    struct DiscoveredPtr {
        uint64_t address;
        uint64_t pointsTo;
        RegionFlag region;
        std::string moduleName;
    };

    std::vector<DiscoveredPtr> allPointers;
    std::vector<uint8_t> buf(CHUNK_SIZE);

    uint64_t minValidAddr = regRes.value.front().start;
    uint64_t maxValidAddr = regRes.value.back().end;

    for (const auto& r : regRes.value) {
        uint64_t cur = r.start;
        while (cur < r.end) {
            size_t len = static_cast<size_t>(std::min<uint64_t>(CHUNK_SIZE, r.end - cur));
            auto rd = m_reader->read(cur, buf.data(), len);
            if (rd.isOk() && rd.value >= ptrSize) {
                for (size_t off = 0; off + ptrSize <= rd.value; off += ptrSize) {
                    uint64_t val = 0;
                    if (config.is64Bit) {
                        std::memcpy(&val, buf.data() + off, 8);
                        val &= 0x00FFFFFFFFFFFFFFULL;
                    } else {
                        uint32_t v32 = 0;
                        std::memcpy(&v32, buf.data() + off, 4);
                        val = v32;
                    }
                    if (val >= minValidAddr && val <= maxValidAddr) {
                        allPointers.push_back(DiscoveredPtr{cur + off, val, r.regionFlag, r.path});
                    }
                }
            }
            cur += len;
        }
    }

    std::sort(allPointers.begin(), allPointers.end(), [](const DiscoveredPtr& a, const DiscoveredPtr& b) {
        return a.pointsTo < b.pointsTo;
    });

    std::vector<PointerChainResult> results;
    struct QueueNode {
        uint64_t currentTarget;
        std::vector<int64_t> offsetsSoFar;
    };

    std::vector<QueueNode> currentLevel{{config.targetAddress, {}}};

    for (uint32_t depth = 1; depth <= config.maxDepth && results.size() < config.maxResults; ++depth) {
        std::vector<QueueNode> nextLevel;
        for (const auto& node : currentLevel) {
            uint64_t low = (node.currentTarget > config.maxOffset) ? (node.currentTarget - config.maxOffset) : 0;
            uint64_t high = node.currentTarget;

            auto itLow = std::lower_bound(allPointers.begin(), allPointers.end(), low,
                                          [](const DiscoveredPtr& p, uint64_t v) { return p.pointsTo < v; });

            for (auto it = itLow; it != allPointers.end() && it->pointsTo <= high; ++it) {
                int64_t offset = static_cast<int64_t>(node.currentTarget - it->pointsTo);
                std::vector<int64_t> chain = node.offsetsSoFar;
                chain.push_back(offset);

                bool isStaticRoot = (it->region == RegionFlag::CD ||
                                     it->region == RegionFlag::CB ||
                                     it->region == RegionFlag::XA);
                if (isStaticRoot || depth == config.maxDepth) {
                    std::reverse(chain.begin(), chain.end());
                    results.push_back(PointerChainResult{
                        it->address,
                        it->moduleName,
                        0,
                        std::move(chain)
                    });
                    if (results.size() >= config.maxResults) break;
                } else {
                    nextLevel.push_back(QueueNode{it->address, std::move(chain)});
                }
            }
            if (results.size() >= config.maxResults) break;
        }
        currentLevel = std::move(nextLevel);
        if (progressCb) progressCb(depth, config.maxDepth, results.size());
        if (currentLevel.empty()) break;
    }

    return Result<std::vector<PointerChainResult>>::ok(std::move(results));
}

Result<size_t> SearchEngine::batchWrite(const std::vector<uint64_t>& addresses,
                                        ScanValue newValue, DataType type) {
    if (addresses.empty()) return Result<size_t>::ok(0);

    const size_t elemSize = dataTypeSize(type);
    uint8_t rawBytes[8]{};
    switch (type) {
        case DataType::I8:
        case DataType::U8:  std::memcpy(rawBytes, &newValue.u64, 1); break;
        case DataType::I16:
        case DataType::U16: std::memcpy(rawBytes, &newValue.u64, 2); break;
        case DataType::I32:
        case DataType::U32:
        case DataType::XOR32: std::memcpy(rawBytes, &newValue.u64, 4); break;
        case DataType::I64:
        case DataType::U64: std::memcpy(rawBytes, &newValue.u64, 8); break;
        case DataType::F32: std::memcpy(rawBytes, &newValue.f32, 4); break;
        case DataType::F64: std::memcpy(rawBytes, &newValue.f64, 8); break;
        default: std::memcpy(rawBytes, &newValue.u64, 4); break;
    }

    size_t successCount = 0;
    for (uint64_t addr : addresses) {
        auto wr = m_reader->write(addr, rawBytes, elemSize);
        if (wr.isOk() && wr.value == elemSize) {
            ++successCount;
        }
    }
    return Result<size_t>::ok(successCount);
}

} // namespace mem_engine
