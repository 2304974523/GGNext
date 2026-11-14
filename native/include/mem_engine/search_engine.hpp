#pragma once

#include "mem_engine/mmap_store.hpp"
#include "mem_engine/memory_reader.hpp"
#include "mem_engine/types.hpp"
#include <memory>
#include <atomic>
#include <mutex>
#include <vector>

namespace mem_engine {

class SearchEngine {
public:
    explicit SearchEngine(std::shared_ptr<IMemoryReader> reader,
                          std::string tempDir = "/data/local/tmp");
    ~SearchEngine() = default;

    void setRegionMask(uint32_t mask) noexcept { m_regionMask = mask; }
    [[nodiscard]] uint32_t regionMask() const noexcept { return m_regionMask; }

    void setAddressRange(uint64_t startAddr, uint64_t endAddr) noexcept {
        m_rangeStart = startAddr;
        m_rangeEnd = endAddr;
    }

    void setMaxThreads(size_t threads) noexcept { m_maxThreads = threads; }
    void requestCancel() noexcept { m_cancelled.store(true, std::memory_order_relaxed); }
    void clearResults() noexcept;

    Result<size_t> searchExact(ScanValue target, DataType type, bool aligned = true,
                               const ProgressCallback& progressCb = nullptr);
    Result<size_t> refineExact(ScanValue target, CompareOp op = CompareOp::EQUAL,
                               const ProgressCallback& progressCb = nullptr);

    Result<size_t> searchRange(ScanValue minVal, ScanValue maxVal, DataType type,
                               bool aligned = true, const ProgressCallback& progressCb = nullptr);
    Result<size_t> refineRange(ScanValue minVal, ScanValue maxVal,
                               const ProgressCallback& progressCb = nullptr);

    Result<size_t> startUnknownSnapshot(DataType type, bool aligned = true,
                                        const ProgressCallback& progressCb = nullptr);
    Result<size_t> refineFuzzy(FuzzyCondition condition, ScanValue delta = ScanValue::fromI64(0),
                               const ProgressCallback& progressCb = nullptr);

    Result<size_t> searchGroup(const GroupSearchQuery& query,
                               const ProgressCallback& progressCb = nullptr);

    Result<size_t> searchEncrypted(int64_t plainTarget, DataType type, uint64_t explicitXorKey = 0,
                                   uint32_t nearbyKeyOffsetBytes = 8,
                                   const ProgressCallback& progressCb = nullptr);

    Result<std::vector<PointerChainResult>> scanPointerChains(
        const PointerScanConfig& config,
        const ProgressCallback& progressCb = nullptr);

    Result<size_t> batchWrite(const std::vector<uint64_t>& addresses,
                              ScanValue newValue, DataType type);

    [[nodiscard]] const MmapCandidateStore& candidates() const noexcept { return m_candidates; }
    [[nodiscard]] size_t candidateCount() const noexcept { return m_candidates.size(); }
    [[nodiscard]] size_t totalMatchedCount() const noexcept {
        return m_totalMatchedCount > m_candidates.size() ? m_totalMatchedCount : m_candidates.size();
    }
    [[nodiscard]] bool hasActiveSession() const noexcept {
        return !m_candidates.empty() || m_hasFullSnapshot;
    }
    [[nodiscard]] DataType currentDataType() const noexcept { return m_currentType; }

    void fetchCandidates(size_t offset, size_t limit, std::vector<CandidateEntry>& out) const;

private:
    struct ScanChunkTask {
        uint64_t startAddr;
        size_t length;
        RegionFlag regionFlag;
    };

    Result<std::vector<VmaRegion>> fetchScanRegions() const;
    std::vector<ScanChunkTask> buildChunkTasks(const std::vector<VmaRegion>& regions, size_t typeSize) const;
    size_t computeThreadCount(size_t taskCount) const noexcept;

    std::shared_ptr<IMemoryReader> m_reader;
    std::string m_tempDir;
    uint32_t m_regionMask{DEFAULT_SCAN_REGION_MASK};
    uint64_t m_rangeStart{0};
    uint64_t m_rangeEnd{UINT64_MAX};
    size_t m_maxThreads{0};

    DataType m_currentType{DataType::I32};
    bool m_currentAligned{true};
    bool m_hasFullSnapshot{false};
    size_t m_totalMatchedCount{0};

    MmapCandidateStore m_candidates;
    MmapRegionSnapshotStore m_snapshotStore;
    std::atomic<bool> m_cancelled{false};
    mutable std::mutex m_engineMutex;
};

} // namespace mem_engine
