#include "mem_engine/mmap_store.hpp"
#include <sys/mman.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <algorithm>

namespace mem_engine {

// ============================================================================
// MmapCandidateStore Implementation (Anonymous Demand-Paged Virtual Memory)
// ============================================================================
MmapCandidateStore::MmapCandidateStore(size_t inMemoryThreshold, const std::string& spillDir)
    : m_threshold(std::min<size_t>(inMemoryThreshold, MAX_STORED_CANDIDATES)),
      m_spillDir(spillDir) {
    m_ramVec.reserve(std::min<size_t>(m_threshold, 65536));
}

MmapCandidateStore::~MmapCandidateStore() {
    clear();
}

MmapCandidateStore::MmapCandidateStore(MmapCandidateStore&& other) noexcept
    : m_threshold(other.m_threshold),
      m_spillDir(std::move(other.m_spillDir)),
      m_ramVec(std::move(other.m_ramVec)),
      m_count(other.m_count),
      m_isMmap(other.m_isMmap),
      m_fd(other.m_fd),
      m_mmapPtr(other.m_mmapPtr),
      m_mmapCapacityBytes(other.m_mmapCapacityBytes) {
    other.m_count = 0;
    other.m_isMmap = false;
    other.m_fd = -1;
    other.m_mmapPtr = nullptr;
    other.m_mmapCapacityBytes = 0;
}

MmapCandidateStore& MmapCandidateStore::operator=(MmapCandidateStore&& other) noexcept {
    if (this != &other) {
        clear();
        m_threshold = other.m_threshold;
        m_spillDir = std::move(other.m_spillDir);
        m_ramVec = std::move(other.m_ramVec);
        m_count = other.m_count;
        m_isMmap = other.m_isMmap;
        m_fd = other.m_fd;
        m_mmapPtr = other.m_mmapPtr;
        m_mmapCapacityBytes = other.m_mmapCapacityBytes;

        other.m_count = 0;
        other.m_isMmap = false;
        other.m_fd = -1;
        other.m_mmapPtr = nullptr;
        other.m_mmapCapacityBytes = 0;
    }
    return *this;
}

bool MmapCandidateStore::transitionToMmap(size_t requiredCount) {
    size_t capCount = std::min<size_t>(
        MAX_STORED_CANDIDATES,
        std::max(requiredCount * 2, m_threshold * 2)
    );
    if (capCount < requiredCount) capCount = requiredCount;
    size_t capBytes = capCount * sizeof(CandidateEntry);

    void* ptr = ::mmap(nullptr, capBytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (ptr == MAP_FAILED) {
        return false;
    }

    ::madvise(ptr, capBytes, MADV_SEQUENTIAL);
    m_mmapPtr = static_cast<CandidateEntry*>(ptr);
    m_mmapCapacityBytes = capBytes;
    m_isMmap = true;
    m_fd = -1;

    if (!m_ramVec.empty()) {
        std::memcpy(m_mmapPtr, m_ramVec.data(), m_ramVec.size() * sizeof(CandidateEntry));
        std::vector<CandidateEntry>().swap(m_ramVec);
    }

    return true;
}

bool MmapCandidateStore::growMmap(size_t requiredCount) {
    size_t neededBytes = requiredCount * sizeof(CandidateEntry);
    if (neededBytes <= m_mmapCapacityBytes) return true;

    size_t maxBytes = MAX_STORED_CANDIDATES * sizeof(CandidateEntry);
    size_t newCapBytes = std::min<size_t>(
        maxBytes,
        std::max(m_mmapCapacityBytes * 2, neededBytes + (8 * 1024 * 1024))
    );
    if (newCapBytes < neededBytes) newCapBytes = neededBytes;

#if defined(__linux__) && defined(MREMAP_MAYMOVE)
    void* newPtr = ::mremap(m_mmapPtr, m_mmapCapacityBytes, newCapBytes, MREMAP_MAYMOVE);
    if (newPtr == MAP_FAILED) return false;
#else
    void* newPtr = ::mmap(nullptr, newCapBytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (newPtr == MAP_FAILED) return false;
    if (m_count > 0) {
        std::memcpy(newPtr, m_mmapPtr, m_count * sizeof(CandidateEntry));
    }
    ::munmap(m_mmapPtr, m_mmapCapacityBytes);
#endif

    m_mmapPtr = static_cast<CandidateEntry*>(newPtr);
    m_mmapCapacityBytes = newCapBytes;
    return true;
}

bool MmapCandidateStore::append(const CandidateEntry& entry) {
    return appendBatch(&entry, 1);
}

bool MmapCandidateStore::appendBatch(const CandidateEntry* entries, size_t count) {
    if (count == 0 || !entries) return true;
    if (m_count >= MAX_STORED_CANDIDATES) return true; // Cap reached safely

    size_t allowed = std::min(count, MAX_STORED_CANDIDATES - m_count);
    size_t newCount = m_count + allowed;

    if (!m_isMmap) {
        if (newCount <= m_threshold) {
            m_ramVec.insert(m_ramVec.end(), entries, entries + allowed);
            m_count = newCount;
            return true;
        }
        if (!transitionToMmap(newCount)) {
            return false;
        }
    }

    if (!growMmap(newCount)) {
        return false;
    }

    std::memcpy(m_mmapPtr + m_count, entries, allowed * sizeof(CandidateEntry));
    m_count = newCount;
    return true;
}

const CandidateEntry& MmapCandidateStore::operator[](size_t idx) const noexcept {
    return m_isMmap ? m_mmapPtr[idx] : m_ramVec[idx];
}

CandidateEntry& MmapCandidateStore::operator[](size_t idx) noexcept {
    return m_isMmap ? m_mmapPtr[idx] : m_ramVec[idx];
}

const CandidateEntry* MmapCandidateStore::data() const noexcept {
    return m_isMmap ? m_mmapPtr : m_ramVec.data();
}

CandidateEntry* MmapCandidateStore::data() noexcept {
    return m_isMmap ? m_mmapPtr : m_ramVec.data();
}

void MmapCandidateStore::sortByAddress() {
    if (m_count <= 1) return;
    CandidateEntry* base = data();
    auto cmp = [](const CandidateEntry& a, const CandidateEntry& b) noexcept {
        if (a.address != b.address) return a.address < b.address;
        return a.type < b.type;
    };
    if (std::is_sorted(base, base + m_count, cmp)) {
        return;
    }
    std::sort(base, base + m_count, cmp);
}

void MmapCandidateStore::clear() noexcept {
    if (m_mmapPtr) {
        ::munmap(m_mmapPtr, m_mmapCapacityBytes);
        m_mmapPtr = nullptr;
    }
    if (m_fd >= 0) {
        ::close(m_fd);
        m_fd = -1;
    }
    m_mmapCapacityBytes = 0;
    m_isMmap = false;
    m_count = 0;
    std::vector<CandidateEntry>().swap(m_ramVec);
}

// ============================================================================
// MmapRegionSnapshotStore Implementation (Anonymous Demand-Paged Snapshot)
// ============================================================================
MmapRegionSnapshotStore::MmapRegionSnapshotStore(const std::string& spillDir)
    : m_spillDir(spillDir) {}

MmapRegionSnapshotStore::~MmapRegionSnapshotStore() {
    clear();
}

MmapRegionSnapshotStore::MmapRegionSnapshotStore(MmapRegionSnapshotStore&& other) noexcept
    : m_spillDir(std::move(other.m_spillDir)),
      m_fd(other.m_fd),
      m_mmapPtr(other.m_mmapPtr),
      m_capacity(other.m_capacity),
      m_writeOffset(other.m_writeOffset),
      m_blocks(std::move(other.m_blocks)) {
    other.m_fd = -1;
    other.m_mmapPtr = nullptr;
    other.m_capacity = 0;
    other.m_writeOffset = 0;
}

MmapRegionSnapshotStore& MmapRegionSnapshotStore::operator=(MmapRegionSnapshotStore&& other) noexcept {
    if (this != &other) {
        clear();
        m_spillDir = std::move(other.m_spillDir);
        m_fd = other.m_fd;
        m_mmapPtr = other.m_mmapPtr;
        m_capacity = other.m_capacity;
        m_writeOffset = other.m_writeOffset;
        m_blocks = std::move(other.m_blocks);

        other.m_fd = -1;
        other.m_mmapPtr = nullptr;
        other.m_capacity = 0;
        other.m_writeOffset = 0;
    }
    return *this;
}

bool MmapRegionSnapshotStore::openStore(size_t reserveBytes) {
    clear();
    size_t cap = std::min<size_t>(
        MAX_SNAPSHOT_BYTES,
        std::max<size_t>(reserveBytes, 16 * 1024 * 1024)
    );

    void* ptr = ::mmap(nullptr, cap, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (ptr == MAP_FAILED) {
        // Fallback to 64 MB initial reservation if target reserve was large
        cap = 64 * 1024 * 1024;
        ptr = ::mmap(nullptr, cap, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (ptr == MAP_FAILED) {
            return false;
        }
    }

    ::madvise(ptr, cap, MADV_SEQUENTIAL);
    m_mmapPtr = static_cast<uint8_t*>(ptr);
    m_capacity = cap;
    m_writeOffset = 0;
    m_fd = -1;
    return true;
}

bool MmapRegionSnapshotStore::ensureCapacity(size_t totalBytesNeeded) {
    if (!m_mmapPtr) {
        return openStore(totalBytesNeeded);
    }
    if (totalBytesNeeded <= m_capacity) return true;
    if (totalBytesNeeded > MAX_SNAPSHOT_BYTES) return false;

    size_t newCap = std::min<size_t>(
        MAX_SNAPSHOT_BYTES,
        std::max(m_capacity * 2, totalBytesNeeded + (16 * 1024 * 1024))
    );

#if defined(__linux__) && defined(MREMAP_MAYMOVE)
    void* newPtr = ::mremap(m_mmapPtr, m_capacity, newCap, MREMAP_MAYMOVE);
    if (newPtr == MAP_FAILED) return false;
#else
    void* newPtr = ::mmap(nullptr, newCap, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (newPtr == MAP_FAILED) return false;
    if (m_writeOffset > 0) {
        std::memcpy(newPtr, m_mmapPtr, m_writeOffset);
    }
    ::munmap(m_mmapPtr, m_capacity);
#endif

    m_mmapPtr = static_cast<uint8_t*>(newPtr);
    m_capacity = newCap;
    return true;
}

bool MmapRegionSnapshotStore::appendRegionChunk(uintptr_t startAddr, const uint8_t* data, size_t len, uint32_t regionFlag) {
    if (len == 0 || !data) return true;
    if (m_writeOffset >= MAX_SNAPSHOT_BYTES) {
        // Gracefully cap snapshot at MAX_SNAPSHOT_BYTES rather than failing the scan
        return true;
    }

    size_t actualLen = std::min(len, MAX_SNAPSHOT_BYTES - m_writeOffset);
    if (!ensureCapacity(m_writeOffset + actualLen)) {
        return true; // Keep existing snapshot blocks rather than aborting
    }

    std::memcpy(m_mmapPtr + m_writeOffset, data, actualLen);

    SnapshotBlock blk;
    blk.startAddress = startAddr;
    blk.length       = actualLen;
    blk.fileOffset   = m_writeOffset;
    blk.regionFlag   = regionFlag;
    m_blocks.push_back(blk);

    m_writeOffset += actualLen;
    return true;
}

const uint8_t* MmapRegionSnapshotStore::getBlockData(const SnapshotBlock& block) const noexcept {
    if (!m_mmapPtr || block.fileOffset + block.length > m_writeOffset) return nullptr;
    return m_mmapPtr + block.fileOffset;
}

uint8_t* MmapRegionSnapshotStore::getBlockDataMut(const SnapshotBlock& block) noexcept {
    if (!m_mmapPtr || block.fileOffset + block.length > m_writeOffset) return nullptr;
    return m_mmapPtr + block.fileOffset;
}

void MmapRegionSnapshotStore::clear() noexcept {
    if (m_mmapPtr) {
        ::munmap(m_mmapPtr, m_capacity);
        m_mmapPtr = nullptr;
    }
    if (m_fd >= 0) {
        ::close(m_fd);
        m_fd = -1;
    }
    m_capacity = 0;
    m_writeOffset = 0;
    m_blocks.clear();
}

} // namespace mem_engine
