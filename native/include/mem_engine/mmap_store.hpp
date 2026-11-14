#pragma once

#include "mem_engine/types.hpp"
#include <vector>
#include <string>
#include <cstdint>
#include <cstddef>

namespace mem_engine {

class MmapCandidateStore {
public:
    static constexpr size_t DEFAULT_RAM_LIMIT = 500'000;
    static constexpr size_t MAX_STORED_CANDIDATES = 2'000'000;

    explicit MmapCandidateStore(size_t ramLimitEntries = DEFAULT_RAM_LIMIT,
                                std::string tempDir = "/data/local/tmp");
    ~MmapCandidateStore();

    MmapCandidateStore(const MmapCandidateStore&) = delete;
    MmapCandidateStore& operator=(const MmapCandidateStore&) = delete;
    MmapCandidateStore(MmapCandidateStore&& other) noexcept;
    MmapCandidateStore& operator=(MmapCandidateStore&& other) noexcept;

    void clear() noexcept;
    void reserve(size_t expectedEntries);
    void push_back(const CandidateEntry& entry);
    void appendBatch(const CandidateEntry* entries, size_t count);

    [[nodiscard]] size_t size() const noexcept { return m_size; }
    [[nodiscard]] bool empty() const noexcept { return m_size == 0; }
    [[nodiscard]] bool isMmapBacked() const noexcept { return m_usingMmap; }
    [[nodiscard]] bool isFull() const noexcept { return m_size >= MAX_STORED_CANDIDATES; }

    [[nodiscard]] const CandidateEntry* data() const noexcept;
    [[nodiscard]] CandidateEntry* data() noexcept;

    [[nodiscard]] const CandidateEntry& operator[](size_t index) const noexcept {
        return data()[index];
    }
    [[nodiscard]] CandidateEntry& operator[](size_t index) noexcept {
        return data()[index];
    }

    void sortByAddress();
    void swap(MmapCandidateStore& other) noexcept;

private:
    bool switchToAnonymousMmap(size_t initialCapacity);
    bool growMmap(size_t minCapacity);
    void cleanupMmap() noexcept;

    size_t m_ramLimit{DEFAULT_RAM_LIMIT};
    std::string m_tempDir;
    std::vector<CandidateEntry> m_ramBuffer;

    bool m_usingMmap{false};
    void* m_mmapPtr{nullptr};
    size_t m_mmapCapacity{0};
    size_t m_size{0};
};

struct SnapshotRegionBlock {
    uint64_t startAddress{0};
    size_t byteLength{0};
    size_t fileOffset{0};
    RegionFlag regionFlag{RegionFlag::A};
};

class MmapRegionSnapshotStore {
public:
    static constexpr size_t MAX_SNAPSHOT_BYTES = 256ULL * 1024ULL * 1024ULL;

    explicit MmapRegionSnapshotStore(std::string tempDir = "/data/local/tmp");
    ~MmapRegionSnapshotStore();

    MmapRegionSnapshotStore(const MmapRegionSnapshotStore&) = delete;
    MmapRegionSnapshotStore& operator=(const MmapRegionSnapshotStore&) = delete;

    bool initialize();
    void clear() noexcept;

    bool appendRegion(uint64_t startAddr, const uint8_t* data, size_t len, RegionFlag flag);

    [[nodiscard]] const std::vector<SnapshotRegionBlock>& blocks() const noexcept { return m_blocks; }
    [[nodiscard]] const uint8_t* readBlockData(const SnapshotRegionBlock& block) const noexcept;
    [[nodiscard]] size_t totalBytes() const noexcept { return m_totalBytes; }
    [[nodiscard]] bool isValid() const noexcept { return m_mmapPtr != nullptr && m_totalBytes > 0; }

private:
    bool ensureCapacity(size_t requiredBytes);

    std::string m_tempDir;
    void* m_mmapPtr{nullptr};
    size_t m_mmapCapacity{0};
    size_t m_totalBytes{0};
    std::vector<SnapshotRegionBlock> m_blocks;
};

} // namespace mem_engine
